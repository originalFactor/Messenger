//! Context-window management (port of the ChatViewModel context block):
//! token estimation, the 80% auto-summarization threshold math, transcript
//! building, and the request-context assembly with orphan tool trimming.
//! Pure functions — persistence and LLM calls stay with the caller.

use messenger_llm::domain::{ContentPart, Message, MessageRole};

use crate::parts::text_projection;

/// Fallback history cap when no context window is declared.
pub const HISTORY_MAX_MESSAGES: usize = 20;
/// Recent messages always kept out of summarization.
pub const SUMMARY_KEEP_COUNT: usize = 10;
/// The injected summary message's stable id.
pub const SUMMARY_MESSAGE_ID: &str = "context-summary";

/// Rough token estimate: CJK ≈ 1 token/char, others ≈ 4 chars/token.
/// Threshold checks only — the builtin cloud proxy reports exact usage.
pub fn estimate_tokens_text(text: &str) -> i64 {
    if text.is_empty() {
        return 0;
    }
    let (mut cjk, mut other) = (0i64, 0i64);
    for c in text.chars() {
        if (c as u32) > 0x2E7F {
            cjk += 1;
        } else {
            other += 1;
        }
    }
    cjk + (other + 3) / 4
}

/// Per-message estimate: text body + ~800 tokens per image + ~4 overhead.
pub fn estimate_tokens_message(message: &Message) -> i64 {
    let body = message
        .parts
        .iter()
        .filter_map(|p| match p {
            ContentPart::Text { text } => Some(text.as_str()),
            _ => None,
        })
        .collect::<Vec<_>>()
        .join("\n");
    let body = if body.trim().is_empty() {
        message.content.as_str()
    } else {
        body.as_str()
    };
    let images = message
        .parts
        .iter()
        .filter(|p| matches!(p, ContentPart::Image { .. }))
        .count();
    estimate_tokens_text(body) + images as i64 * 800 + 4
}

/// The fields of a conversation the context math needs (kept as a plain
/// struct so the caller can project from its own conversation row).
#[derive(Debug, Clone, Default)]
pub struct ContextState {
    pub context_summary: Option<String>,
    pub context_summary_until: i64,
    pub context_tokens: i64,
    pub context_tokens_at: i64,
}

/// Estimated tokens of the "next request's full context": seeded from the
/// last exact usage when present, plus estimates for newer messages;
/// otherwise system prompt + folded summary + all messages since the
/// summary cutoff.
pub fn estimate_context_tokens(
    state: &ContextState,
    sent_messages: &[Message],
    system_prompt: Option<&str>,
) -> i64 {
    if state.context_tokens > 0 {
        state.context_tokens
            + sent_messages
                .iter()
                .filter(|m| m.id != SUMMARY_MESSAGE_ID)
                .filter(|m| m.timestamp > state.context_tokens_at)
                .map(estimate_tokens_message)
                .sum::<i64>()
    } else {
        estimate_tokens_text(system_prompt.unwrap_or_default())
            + estimate_tokens_text(state.context_summary.as_deref().unwrap_or_default())
            + sent_messages
                .iter()
                .filter(|m| m.id != SUMMARY_MESSAGE_ID)
                .filter(|m| m.timestamp >= state.context_summary_until)
                .map(estimate_tokens_message)
                .sum::<i64>()
    }
}

/// The 80%-of-context-window threshold.
pub fn summarize_threshold(context_window: i64) -> i64 {
    context_window * 80 / 100
}

/// Rows of the transcript for the summarization call: role label + brief.
pub fn role_label(role: MessageRole) -> &'static str {
    match role {
        MessageRole::User => "User",
        MessageRole::Assistant => "Assistant",
        _ => "System",
    }
}

/// One message folded into a summary transcript: text body with
/// `[image ×N]` placeholders for images.
pub fn message_brief(message: &Message) -> String {
    let text = text_projection(&message.parts);
    let text = if text.trim().is_empty() {
        message.content.clone()
    } else {
        text
    };
    let image_count = message
        .parts
        .iter()
        .filter(|p| matches!(p, ContentPart::Image { .. }))
        .count();
    if image_count > 0 {
        let prefix = if text.trim().is_empty() {
            String::new()
        } else {
            format!("{text}\n")
        };
        format!("{prefix}[image ×{image_count}]")
    } else {
        text
    }
}

/// Build the summarization transcript: previous summary (if any) plus the
/// messages being folded away. Port of the Kotlin `buildString` block.
pub fn build_summary_transcript(
    previous_summary: Option<&str>,
    previous_summary_until: i64,
    to_summarize: &[Message],
) -> String {
    let mut transcript = String::new();
    if let Some(previous) = previous_summary
        .filter(|s| !s.trim().is_empty())
        .filter(|_| previous_summary_until > 0)
    {
        transcript.push_str("[Earlier summary]\n");
        transcript.push_str(previous);
        transcript.push_str("\n\n[Conversation since then]\n");
    }
    for message in to_summarize {
        transcript.push_str(&format!(
            "{}: {}\n",
            role_label(message.role),
            message_brief(message)
        ));
    }
    transcript
}

/// Assemble the request context: the folded summary (as a leading SYSTEM
/// message, if present) + verbatim messages at/after the cutoff, capped at
/// [`HISTORY_MAX_MESSAGES`], with leading orphan TOOL rows trimmed.
pub fn build_api_context_messages(
    state: &ContextState,
    conversation_id: &str,
    sent_messages: &[Message],
) -> Vec<Message> {
    let recent: Vec<Message> = sent_messages
        .iter()
        .filter(|m| m.timestamp >= state.context_summary_until)
        .cloned()
        .collect();
    let with_summary = match state
        .context_summary
        .as_deref()
        .filter(|s| !s.trim().is_empty())
        .filter(|_| state.context_summary_until > 0)
    {
        None => recent,
        Some(summary) => {
            let summary_message = Message {
                id: SUMMARY_MESSAGE_ID.to_string(),
                conversation_id: conversation_id.to_string(),
                role: MessageRole::System,
                content: format!("[Summary of earlier conversation]\n{summary}"),
                parts: Vec::new(),
                timestamp: 0,
            };
            std::iter::once(summary_message)
                .chain(recent)
                .collect::<Vec<_>>()
        }
    };
    trim_orphan_tool_messages(with_summary.into_iter().rev().take(HISTORY_MAX_MESSAGES).rev().collect())
}

/// The takeLast cap can cut a tool pair in half: leading role=TOOL rows
/// whose assistant tool_calls pairing was trimmed must be dropped — OpenAI
/// rejects tool messages without a preceding tool_calls message.
pub fn trim_orphan_tool_messages(messages: Vec<Message>) -> Vec<Message> {
    messages
        .into_iter()
        .skip_while(|m| m.role == MessageRole::Tool)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn text_message(id: &str, role: MessageRole, content: &str, timestamp: i64) -> Message {
        Message {
            id: id.into(),
            conversation_id: "c1".into(),
            role,
            content: content.into(),
            parts: vec![ContentPart::Text {
                text: content.into(),
            }],
            timestamp,
        }
    }

    #[test]
    fn token_estimation_matches_kotlin_formula() {
        assert_eq!(estimate_tokens_text(""), 0);
        // 4 ASCII chars → 1 token; +3 rounding.
        assert_eq!(estimate_tokens_text("abcd"), 1);
        assert_eq!(estimate_tokens_text("abcde"), 2);
        // CJK counts 1 per char.
        assert_eq!(estimate_tokens_text("你好"), 2);
        // Mixed: 2 CJK + 4 ASCII → 2 + 1.
        assert_eq!(estimate_tokens_text("你好abcd"), 3);
    }

    #[test]
    fn message_estimate_counts_images_and_overhead() {
        let message = Message {
            id: "m".into(),
            conversation_id: "c".into(),
            role: MessageRole::User,
            content: "look".into(),
            parts: vec![
                ContentPart::Text {
                    text: "look".into(),
                },
                ContentPart::Image {
                    image: messenger_llm::domain::MessageImage {
                        data_uri: "data:x".into(),
                        local_path: "/p".into(),
                    },
                },
            ],
            timestamp: 0,
        };
        // 1 token body + 800 image + 4 overhead.
        assert_eq!(estimate_tokens_message(&message), 805);
    }

    #[test]
    fn context_estimate_seeds_from_exact_usage() {
        let state = ContextState {
            context_tokens: 1_000,
            context_tokens_at: 100,
            ..Default::default()
        };
        let messages = vec![
            text_message("a", MessageRole::User, "old", 50),
            text_message("b", MessageRole::User, "abcd", 200),
        ];
        // 1000 + (1 + 4) — only the newer message adds.
        assert_eq!(estimate_context_tokens(&state, &messages, None), 1005);
    }

    #[test]
    fn summary_message_is_injected_in_front() {
        let state = ContextState {
            context_summary: Some("earlier".into()),
            context_summary_until: 100,
            ..Default::default()
        };
        let messages = vec![
            text_message("t1", MessageRole::Tool, "result", 150),
            text_message("m1", MessageRole::User, "hello", 200),
        ];
        let context = build_api_context_messages(&state, "c1", &messages);
        // Summary first; the tool row is no longer "leading" once the summary
        // precedes it, so it survives — faithful to the Kotlin behavior.
        assert_eq!(context.len(), 3);
        assert_eq!(context[0].content, "[Summary of earlier conversation]\nearlier");
        assert_eq!(context[0].role, MessageRole::System);
    }

    #[test]
    fn orphan_tool_rows_are_trimmed_without_summary() {
        let state = ContextState::default();
        let messages = vec![
            text_message("t1", MessageRole::Tool, "result", 50),
            text_message("t2", MessageRole::Tool, "result2", 60),
            text_message("m1", MessageRole::User, "hello", 100),
        ];
        let context = build_api_context_messages(&state, "c1", &messages);
        assert_eq!(context.len(), 1);
        assert_eq!(context[0].id, "m1");
    }

    #[test]
    fn take_last_cap_applies_without_summary() {
        let state = ContextState::default();
        let messages: Vec<Message> = (0..30)
            .map(|i| text_message(&format!("m{i}"), MessageRole::User, "x", i))
            .collect();
        let context = build_api_context_messages(&state, "c1", &messages);
        assert_eq!(context.len(), HISTORY_MAX_MESSAGES);
        assert_eq!(context[0].id, "m10");
    }

    #[test]
    fn summary_transcript_includes_previous() {
        let transcript = build_summary_transcript(
            Some("old summary"),
            100,
            &[text_message("u1", MessageRole::User, "hi", 50)],
        );
        assert!(transcript.contains("[Earlier summary]"));
        assert!(transcript.contains("old summary"));
        assert!(transcript.contains("[Conversation since then]"));
        assert!(transcript.contains("User: hi"));
    }

    #[test]
    fn message_brief_renders_image_placeholders() {
        let message = Message {
            id: "m".into(),
            conversation_id: "c".into(),
            role: MessageRole::User,
            content: String::new(),
            parts: vec![
                ContentPart::Text {
                    text: "see".into(),
                },
                ContentPart::Image {
                    image: messenger_llm::domain::MessageImage {
                        data_uri: "data:x".into(),
                        local_path: "/p".into(),
                    },
                },
                ContentPart::Image {
                    image: messenger_llm::domain::MessageImage {
                        data_uri: "data:y".into(),
                        local_path: "/q".into(),
                    },
                },
            ],
            timestamp: 0,
        };
        assert_eq!(message_brief(&message), "see\n[image ×2]");
    }
}
