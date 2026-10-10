//! Streaming chat parser (port of `ChatStreamParser.kt`).
//!
//! The Kotlin version is a Flow transformer over JSON chunk payloads; here it
//! is a stateful struct — `feed` one SSE data payload at a time and drain the
//! emitted events. All semantics are behavioral-compat targets guarded by the
//! ported test suite: think-block wrapping of both reasoning fields,
//! `reasoning_content` precedence, blank-delta suppression inside think,
//! tool-call accumulation by index with synthesized ids, usage chunk stashing,
//! and the double `Done` (finish_reason block + `[DONE]`).

use std::collections::BTreeMap;

use serde_json::Value;

use crate::dto::{ChatCompletionChunk, ChatDelta, Usage};
#[cfg(test)]
use crate::dto::PromptTokensDetails;
use crate::events::{ChatStreamEvent, ToolCallData};

/// Accumulator for one streaming tool call: id/name arrive with the first
/// fragment, arguments concatenate across chunks.
#[derive(Default)]
struct ToolCallAccumulator {
    call_id: Option<String>,
    name: Option<String>,
    arguments: String,
}

/// Event-emitting streaming parser (`parseToEvents`).
#[derive(Default)]
pub struct ChatStreamParser {
    in_think_block: bool,
    reasoning_emitted: bool,
    // The usage-only chunk arrives before [DONE]; stashed and attached to Done
    // so callers can record context usage.
    last_usage: Option<Usage>,
    tool_call_accumulators: BTreeMap<i64, ToolCallAccumulator>,
}

impl ChatStreamParser {
    pub fn new() -> Self {
        Self::default()
    }

    /// Feed one SSE data payload — the `[DONE]` sentinel included. Returns
    /// the events the payload produced, in order.
    pub fn feed(&mut self, data: &str) -> Vec<ChatStreamEvent> {
        if data == "[DONE]" {
            let mut events = Vec::new();
            if self.in_think_block {
                events.push(ChatStreamEvent::Content("</think>\n".to_string()));
                self.in_think_block = false;
            }
            events.push(ChatStreamEvent::Done {
                finish_reason: None,
                usage: self.last_usage,
                tool_calls: self.accumulated_tool_calls(),
            });
            return events;
        }

        let Ok(chunk) = serde_json::from_str::<ChatCompletionChunk>(data) else {
            // Malformed chunk (unexpected shape) — skip silently, matching the
            // Kotlin IllegalArgumentException path.
            return Vec::new();
        };

        let mut events = Vec::new();
        if chunk.usage.is_some() {
            self.last_usage = chunk.usage;
        }
        let Some(choice) = chunk.choices.first() else {
            return events;
        };

        if let Some(reasoning) = incoming_reasoning(&choice.delta) {
            if !self.reasoning_emitted {
                let format = if choice.delta.reasoning_content.as_deref().is_none_or(str::is_empty)
                {
                    "reasoning_summary"
                } else {
                    "reasoning_content"
                };
                events.push(ChatStreamEvent::ReasoningDetected {
                    format: format.to_string(),
                });
                self.reasoning_emitted = true;
            }
            if !self.in_think_block {
                events.push(ChatStreamEvent::Content("<think>".to_string()));
                self.in_think_block = true;
            }
            events.push(ChatStreamEvent::Content(reasoning.to_string()));
        }

        if let Some(content) = &choice.delta.content {
            let content_text = extract_content_text(content);
            if !content_text.is_empty() {
                // Some providers split multi-segment thinking with blank content
                // deltas ("\n\n"). Closing the think block on those would tear
                // one contiguous reasoning into several blocks — drop them.
                if !(content_text.trim().is_empty() && self.in_think_block) {
                    if self.in_think_block {
                        events.push(ChatStreamEvent::Content("</think>\n".to_string()));
                        self.in_think_block = false;
                    }
                    events.push(ChatStreamEvent::Content(content_text));
                }
            }
        }

        for fragment in choice.delta.tool_calls.iter().flatten() {
            let index = fragment.index.unwrap_or(0);
            let acc = self.tool_call_accumulators.entry(index).or_default();
            if let Some(id) = &fragment.id {
                acc.call_id = Some(id.clone());
            }
            if let Some(name) = fragment.function.as_ref().and_then(|f| f.name.as_deref()) {
                if !name.is_empty() {
                    acc.name = Some(name.to_string());
                }
            }
            if let Some(arguments) = fragment.function.as_ref().and_then(|f| f.arguments.as_deref())
            {
                acc.arguments.push_str(arguments);
            }
        }

        if let Some(finish_reason) = &choice.finish_reason {
            if self.in_think_block {
                events.push(ChatStreamEvent::Content("</think>\n".to_string()));
                self.in_think_block = false;
            }
            events.push(ChatStreamEvent::Done {
                finish_reason: Some(finish_reason.clone()),
                usage: self.last_usage,
                tool_calls: self.accumulated_tool_calls(),
            });
        }

        events
    }

    /// Summarize the accumulated fragments into complete calls, ordered by
    /// index. Fragments whose name never arrived are dropped as incomplete;
    /// providers that omit ids fall back to a stable per-index id so tool
    /// results round-trip.
    fn accumulated_tool_calls(&self) -> Vec<ToolCallData> {
        self.tool_call_accumulators
            .iter()
            .filter_map(|(index, acc)| {
                let name = acc.name.as_ref()?;
                Some(ToolCallData {
                    call_id: acc
                        .call_id
                        .clone()
                        .unwrap_or_else(|| format!("call_{index}")),
                    name: name.clone(),
                    arguments: acc.arguments.clone(),
                })
            })
            .collect()
    }
}

/// Text-only streaming parser (`parseToText`): same think-block handling,
/// no tool/usage/finish tracking, all errors swallowed.
#[derive(Default)]
pub struct TextStreamParser {
    in_think_block: bool,
}

impl TextStreamParser {
    pub fn new() -> Self {
        Self::default()
    }

    /// Feed one SSE data payload; returns the text pieces it produced.
    pub fn feed(&mut self, data: &str) -> Vec<String> {
        if data == "[DONE]" {
            return Vec::new();
        }
        let Ok(chunk) = serde_json::from_str::<ChatCompletionChunk>(data) else {
            return Vec::new();
        };
        let mut out = Vec::new();
        let Some(choice) = chunk.choices.first() else {
            return out;
        };
        if let Some(reasoning) = incoming_reasoning(&choice.delta) {
            if !self.in_think_block {
                out.push("<think>".to_string());
                self.in_think_block = true;
            }
            out.push(reasoning.to_string());
        }
        if let Some(content) = &choice.delta.content {
            let text = extract_content_text(content);
            if !text.is_empty() && !(text.trim().is_empty() && self.in_think_block) {
                if self.in_think_block {
                    out.push("</think>\n".to_string());
                    self.in_think_block = false;
                }
                out.push(text);
            }
        }
        out
    }
}

/// The reasoning delta carried by this chunk: DeepSeek-style full chain of
/// thought on `reasoning_content`, GPT-style summary on `reasoning`; when a
/// chunk carries both, `reasoning_content` wins.
fn incoming_reasoning(delta: &ChatDelta) -> Option<&str> {
    delta
        .reasoning_content
        .as_deref()
        .filter(|s| !s.is_empty())
        .or_else(|| delta.reasoning.as_deref().filter(|s| !s.is_empty()))
}

/// Content arrives as a plain string or an OpenAI multipart array; extract
/// the text parts, skipping `image_url` entries.
fn extract_content_text(content: &Value) -> String {
    match content {
        Value::String(s) => s.clone(),
        Value::Array(parts) => parts
            .iter()
            .filter_map(Value::as_object)
            .filter_map(|part| match part.get("type").and_then(Value::as_str) {
                Some("text") => part.get("text").and_then(Value::as_str).map(String::from),
                _ => None,
            })
            .collect(),
        _ => String::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn feed_all(parser: &mut ChatStreamParser, payloads: &[&str]) -> Vec<ChatStreamEvent> {
        payloads.iter().flat_map(|p| parser.feed(p)).collect()
    }

    fn contents(events: &[ChatStreamEvent]) -> Vec<String> {
        events
            .iter()
            .filter_map(|e| match e {
                ChatStreamEvent::Content(t) => Some(t.clone()),
                _ => None,
            })
            .collect()
    }

    fn dones(events: &[ChatStreamEvent]) -> Vec<(Option<String>, Vec<ToolCallData>)> {
        events
            .iter()
            .filter_map(|e| match e {
                ChatStreamEvent::Done {
                    finish_reason,
                    tool_calls,
                    ..
                } => Some((finish_reason.clone(), tool_calls.clone())),
                _ => None,
            })
            .collect()
    }

    fn reasoning_formats(events: &[ChatStreamEvent]) -> Vec<String> {
        events
            .iter()
            .filter_map(|e| match e {
                ChatStreamEvent::ReasoningDetected { format } => Some(format.clone()),
                _ => None,
            })
            .collect()
    }

    // -- ported: ChatStreamParserReasoningTest --

    #[test]
    fn wraps_reasoning_summary_in_think_block() {
        let mut parser = ChatStreamParser::new();
        let events = feed_all(
            &mut parser,
            &[
                r#"{"choices":[{"delta":{"reasoning":"Reading the directory first"}}]}"#,
                r#"{"choices":[{"delta":{"content":"Listing files now."}}]}"#,
                "[DONE]",
            ],
        );
        assert_eq!(reasoning_formats(&events), ["reasoning_summary"]);
        assert_eq!(
            contents(&events),
            ["<think>", "Reading the directory first", "</think>\n", "Listing files now."]
        );
    }

    #[test]
    fn reasoning_content_reports_reasoning_content_format() {
        let mut parser = ChatStreamParser::new();
        let events = feed_all(
            &mut parser,
            &[
                r#"{"choices":[{"delta":{"reasoning_content":"step by step"}}]}"#,
                "[DONE]",
            ],
        );
        assert_eq!(reasoning_formats(&events), ["reasoning_content"]);
        // Stream ends inside the think block; [DONE] closes it.
        assert_eq!(contents(&events), ["<think>", "step by step", "</think>\n"]);
    }

    #[test]
    fn reasoning_content_takes_precedence_in_same_chunk() {
        let mut parser = ChatStreamParser::new();
        let events = feed_all(
            &mut parser,
            &[
                r#"{"choices":[{"delta":{"reasoning":"summary text","reasoning_content":"full chain"}}]}"#,
                "[DONE]",
            ],
        );
        assert_eq!(reasoning_formats(&events), ["reasoning_content"]);
        assert_eq!(contents(&events), ["<think>", "full chain", "</think>\n"]);
    }

    #[test]
    fn empty_reasoning_fields_are_ignored() {
        let mut parser = ChatStreamParser::new();
        let events = feed_all(
            &mut parser,
            &[
                r#"{"choices":[{"delta":{"reasoning":"","reasoning_content":""}}]}"#,
                "[DONE]",
            ],
        );
        assert!(reasoning_formats(&events).is_empty());
        assert!(contents(&events).is_empty());
    }

    // -- ported: ChatStreamParserToolCallTest --

    #[test]
    fn accumulates_fragmented_tool_calls_across_chunks() {
        let mut parser = ChatStreamParser::new();
        let events = feed_all(
            &mut parser,
            &[
                r#"{"choices":[{"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"terminal","arguments":"{\"co"}}]},"finish_reason":null}]}"#,
                r#"{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"mmand\":\"ls -la\"}"}}]},"finish_reason":"tool_calls"}]}"#,
                "[DONE]",
            ],
        );
        let dones = dones(&events);
        assert_eq!(dones.len(), 2);
        assert_eq!(dones[0].0.as_deref(), Some("tool_calls"));
        let call = &dones[0].1[0];
        assert_eq!(call.call_id, "call_1");
        assert_eq!(call.name, "terminal");
        assert_eq!(call.arguments, r#"{"command":"ls -la"}"#);
        // The [DONE]-triggered Done carries the same accumulated result.
        assert_eq!(dones[0].1, dones[1].1);
        assert_eq!(dones[1].0, None);
    }

    #[test]
    fn accumulates_multiple_calls_by_index_and_synthesizes_missing_ids() {
        let mut parser = ChatStreamParser::new();
        let events = feed_all(
            &mut parser,
            &[
                r#"{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"terminal","arguments":"{}"}},{"index":0,"id":"call_a","type":"function","function":{"name":"other","arguments":"{\"x\":1"}}]},"finish_reason":null}]}"#,
                r#"{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]},"finish_reason":"tool_calls"}]}"#,
            ],
        );
        let dones = dones(&events);
        assert_eq!(dones.len(), 1);
        let calls = &dones[0].1;
        assert_eq!(calls.len(), 2);
        // Ordered by index.
        assert_eq!(calls[0].call_id, "call_a");
        assert_eq!(calls[0].name, "other");
        assert_eq!(calls[0].arguments, r#"{"x":1}"#);
        assert_eq!(calls[1].call_id, "call_b");
    }

    #[test]
    fn no_tool_calls_on_plain_text_stream() {
        let mut parser = ChatStreamParser::new();
        let events = feed_all(
            &mut parser,
            &[r#"{"choices":[{"delta":{"content":"hello"},"finish_reason":"stop"}]}"#],
        );
        let dones = dones(&events);
        assert_eq!(dones.len(), 1);
        assert!(dones[0].1.is_empty());
        assert_eq!(dones[0].0.as_deref(), Some("stop"));
    }

    // -- additional behavior guards --

    #[test]
    fn usage_only_chunk_is_stashed_and_attached_to_done() {
        let mut parser = ChatStreamParser::new();
        let events = feed_all(
            &mut parser,
            &[
                r#"{"choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}]}"#,
                r#"{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":34,"total_tokens":46}}"#,
                "[DONE]",
            ],
        );
        let usage = events.iter().find_map(|e| match e {
            ChatStreamEvent::Done { usage, .. } => *usage,
            _ => None,
        });
        assert_eq!(
            usage,
            Some(Usage {
                prompt_tokens: 12,
                completion_tokens: 34,
                total_tokens: 46,
                prompt_tokens_details: None,
            })
        );
    }

    /// A provider that reports a prompt-cache breakdown carries it through to
    /// `Done` — the cached count is what the clients display as "(N cached)".
    #[test]
    fn a_prompt_cache_breakdown_rides_along_on_the_usage() {
        let mut parser = ChatStreamParser::new();
        let events = feed_all(
            &mut parser,
            &[
                r#"{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":7,"total_tokens":107,"prompt_tokens_details":{"cached_tokens":64}}}"#,
                "[DONE]",
            ],
        );
        let usage = events.iter().find_map(|e| match e {
            ChatStreamEvent::Done { usage, .. } => *usage,
            _ => None,
        });
        assert_eq!(
            usage,
            Some(Usage {
                prompt_tokens: 100,
                completion_tokens: 7,
                total_tokens: 107,
                prompt_tokens_details: Some(PromptTokensDetails { cached_tokens: 64 }),
            })
        );
    }

    #[test]
    fn multipart_content_array_extracts_text_parts() {
        let mut parser = ChatStreamParser::new();
        let events = feed_all(
            &mut parser,
            &[
                r#"{"choices":[{"delta":{"content":[{"type":"text","text":"a"},{"type":"image_url","image_url":{"url":"data:…"}}]}}]}"#,
            ],
        );
        assert_eq!(contents(&events), ["a"]);
    }

    #[test]
    fn malformed_chunk_is_skipped_silently() {
        let mut parser = ChatStreamParser::new();
        let events = feed_all(
            &mut parser,
            &["not json at all", r#"{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"#],
        );
        assert!(matches!(events.first(), Some(ChatStreamEvent::Content(t)) if t == "ok"));
    }

    #[test]
    fn blank_content_delta_inside_think_is_dropped() {
        let mut parser = ChatStreamParser::new();
        let events = feed_all(
            &mut parser,
            &[
                r#"{"choices":[{"delta":{"reasoning_content":"part1"}}]}"#,
                r#"{"choices":[{"delta":{"content":"\n\n"}}]}"#,
                r#"{"choices":[{"delta":{"reasoning_content":"part2"}}]}"#,
                "[DONE]",
            ],
        );
        assert_eq!(
            contents(&events),
            ["<think>", "part1", "part2", "</think>\n"]
        );
    }

    #[test]
    fn text_parser_mirrors_think_semantics() {
        let mut parser = TextStreamParser::new();
        let mut text = String::new();
        for piece in [
            r#"{"choices":[{"delta":{"reasoning":"thinking"}}]}"#,
            r#"{"choices":[{"delta":{"content":"answer"}}"#,
        ] {
            for t in parser.feed(piece) {
                text.push_str(&t);
            }
        }
        for t in parser.feed(r#"}"#) {
            // Malformed (the previous line was intentionally cut) — ignored.
            text.push_str(&t);
        }
        for t in parser.feed(r#"{"choices":[{"delta":{"content":"answer"}}]}"#) {
            text.push_str(&t);
        }
        assert_eq!(text, "<think>thinking</think>\nanswer");
    }
}
