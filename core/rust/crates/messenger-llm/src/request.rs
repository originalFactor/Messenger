//! Chat completion request DTOs and the request message builder (port of the
//! builder half of `ApiRepositoryImpl.kt`).
//!
//! Wire-shape rule: optional fields are omitted when absent (`None`), and the
//! message `content` is a raw `Value` so one field carries the legacy string,
//! the multipart array, and the explicit `null` the tool-call round-trip
//! sends for empty assistant text.

use serde::Serialize;
use serde_json::{json, Map, Value};

use crate::domain::{ContentPart, Message, MessageRole};
use crate::think::{extract_think_content, strip_think_block};

// ---------------------------------------------------------------------------
// Wire DTOs
// ---------------------------------------------------------------------------

/// One message in the request `messages` array.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct WireMessage {
    pub role: String,
    pub content: Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[serde(rename = "reasoning_content")]
    pub reasoning_content: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[serde(rename = "tool_calls")]
    pub tool_calls: Option<Vec<WireToolCall>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[serde(rename = "tool_call_id")]
    pub tool_call_id: Option<String>,
}

impl WireMessage {
    pub fn text(role: &str, content: impl Into<String>) -> Self {
        Self {
            role: role.to_string(),
            content: Value::String(content.into()),
            reasoning_content: None,
            tool_calls: None,
            tool_call_id: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct WireToolCall {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub function: WireToolCallFunction,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct WireToolCallFunction {
    pub name: String,
    pub arguments: String,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Thinking {
    #[serde(rename = "type")]
    pub kind: String,
}

/// A `tools` entry declaring one callable function.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ToolSpec {
    #[serde(rename = "type")]
    pub kind: String,
    pub function: ToolSpecFunction,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ToolSpecFunction {
    pub name: String,
    pub description: String,
    /// JSON Schema of the arguments, carried as raw JSON.
    pub parameters: Value,
}

/// The chat completions request body.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ChatCompletionRequest {
    pub model: String,
    pub messages: Vec<WireMessage>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub temperature: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[serde(rename = "top_p")]
    pub top_p: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[serde(rename = "max_tokens")]
    pub max_tokens: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[serde(rename = "reasoning_effort")]
    pub reasoning_effort: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub thinking: Option<Thinking>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tools: Option<Vec<ToolSpec>>,
    /// Never serialized when false, matching the Kotlin encodeDefaults=false
    /// wire shape.
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub stream: bool,
}

// ---------------------------------------------------------------------------
// Builders
// ---------------------------------------------------------------------------

/// Translate a tool declaration into the request `tools` entry. The JSON
/// Schema parameters string is parsed here; malformed schema degrades to an
/// empty object so the request still goes out.
pub fn build_tool_spec(name: &str, description: &str, parameters_json: &str) -> ToolSpec {
    ToolSpec {
        kind: "function".to_string(),
        function: ToolSpecFunction {
            name: name.to_string(),
            description: description.to_string(),
            parameters: serde_json::from_str(parameters_json).unwrap_or_else(|_| json!({})),
        },
    }
}

/// Reasoning parameters for the API request:
/// - `None` (default): send nothing (API default behavior);
/// - `Some("none")`: send `reasoning_effort:"none"` AND `thinking.type =
///   "disabled"` for DeepSeek compatibility;
/// - any other value: send only `reasoning_effort` with that value.
pub fn build_reasoning_params(reasoning_effort: Option<&str>) -> (Option<String>, Option<Thinking>) {
    match reasoning_effort {
        None => (None, None),
        Some("none") => (
            Some("none".to_string()),
            Some(Thinking {
                kind: "disabled".to_string(),
            }),
        ),
        Some(other) => (Some(other.to_string()), None),
    }
}

/// Build the request payload's `messages` array (port of
/// `buildRequestMessages`).
///
/// Pure-text messages are sent as a `content` string (the legacy shape every
/// provider accepts); multimodal messages go out as `image_url` / `text`
/// part arrays. Tool turns round-trip the persisted parts: an assistant
/// message carrying ToolCall parts is re-sent as an assistant message with
/// `tool_calls`, and a Tool message becomes `role:"tool"` +
/// `tool_call_id` — OpenAI rejects histories where these pairs are broken.
pub fn build_request_messages(
    messages: &[Message],
    system_prompt: Option<&str>,
    reasoning_format: Option<&str>,
) -> Vec<WireMessage> {
    let mut result = Vec::new();
    if let Some(prompt) = system_prompt.filter(|p| !p.is_empty()) {
        result.push(WireMessage::text("system", prompt));
    }
    for message in messages {
        let role = match message.role {
            MessageRole::User => "user",
            MessageRole::Assistant => "assistant",
            MessageRole::System => "system",
            MessageRole::Tool => "tool",
        };

        if role == "tool" {
            let tool_result = message.parts.iter().find_map(|p| match p {
                ContentPart::ToolResult {
                    call_id, output, ..
                } => Some((call_id.as_str(), output.as_str())),
                _ => None,
            });
            result.push(WireMessage {
                role: "tool".to_string(),
                content: Value::String(
                    tool_result.map_or_else(|| message.content.clone(), |(_, output)| output.to_string()),
                ),
                reasoning_content: None,
                tool_calls: None,
                tool_call_id: Some(tool_result.map(|(id, _)| id.to_string()).unwrap_or_default()),
            });
            continue;
        }

        let has_image_parts = message.has_images()
            && message
                .parts
                .iter()
                .any(|p| matches!(p, ContentPart::Image { .. }));

        if has_image_parts {
            result.push(WireMessage {
                role: role.to_string(),
                content: build_multipart_content(&message.parts),
                reasoning_content: None,
                tool_calls: None,
                tool_call_id: None,
            });
        } else if role == "assistant" && has_tool_calls(message) {
            // Tool-call round: echo the (possibly empty) text together with
            // tool_calls. reasoning_summary (encrypted-CoT models) strips the
            // thinking and sends no reasoning fields; think_tag echoes tags
            // verbatim; everything else maps a leading think block back to
            // the reasoning_content field.
            let text = message.text_body();
            let (reasoning, main_content) = match reasoning_format {
                Some("think_tag") => (None, text),
                Some("reasoning_summary") => (None, strip_think_block(&text)),
                _ => extract_think_content(&text),
            };
            let tool_calls = message
                .parts
                .iter()
                .filter_map(|p| match p {
                    ContentPart::ToolCall {
                        call_id,
                        name,
                        arguments,
                    } => Some(WireToolCall {
                        id: call_id.clone(),
                        kind: "function".to_string(),
                        function: WireToolCallFunction {
                            name: name.clone(),
                            arguments: arguments.clone(),
                        },
                    }),
                    _ => None,
                })
                .collect();
            result.push(WireMessage {
                role: role.to_string(),
                content: if main_content.is_empty() {
                    Value::Null
                } else {
                    Value::String(main_content)
                },
                reasoning_content: reasoning,
                tool_calls: Some(tool_calls),
                tool_call_id: None,
            });
        } else {
            // Text-only path: legacy string content.
            let text = message.text_body();
            if role == "assistant" && reasoning_format == Some("reasoning_summary") {
                let stripped = strip_think_block(&text);
                result.push(WireMessage::text(
                    role,
                    if stripped.is_empty() { String::new() } else { stripped },
                ));
            } else if (reasoning_format == Some("reasoning_content") || reasoning_format.is_none())
                && role == "assistant"
            {
                let (reasoning, main_content) = extract_think_content(&text);
                if let Some(reasoning) = reasoning {
                    let mut wire = WireMessage::text(role, main_content);
                    wire.reasoning_content = Some(reasoning);
                    result.push(wire);
                } else {
                    result.push(WireMessage::text(role, text));
                }
            } else {
                result.push(WireMessage::text(role, text));
            }
        }
    }
    result
}

fn has_tool_calls(message: &Message) -> bool {
    message
        .parts
        .iter()
        .any(|p| matches!(p, ContentPart::ToolCall { .. }))
}

/// Translate ContentParts to the OpenAI multipart shape. Always emits
/// `image_url` parts (the only vision-input variant the spec defines) with
/// the data: URI captured at send time.
fn build_multipart_content(parts: &[ContentPart]) -> Value {
    let mut array = Vec::new();
    for part in parts {
        match part {
            ContentPart::Text { text } => array.push(json!({ "type": "text", "text": text })),
            ContentPart::Image { image } => array.push(json!({
                "type": "image_url",
                "image_url": { "url": image.data_uri }
            })),
            // Tool call/result parts never reach the multipart branch.
            ContentPart::ToolCall { .. } | ContentPart::ToolResult { .. } => {}
        }
    }
    Value::Array(array)
}

/// Coerce a reply `content` (string OR multipart array) into a flat string
/// the chat bubble can render. Multipart responses keep text segments
/// verbatim and turn `image_url` parts into markdown images.
pub fn extract_response_content(content: &Value) -> String {
    match content {
        Value::String(s) => s.clone(),
        Value::Array(parts) => parts
            .iter()
            .filter_map(|element| {
                let obj = element.as_object()?;
                match obj.get("type").and_then(Value::as_str) {
                    Some("text") => obj.get("text").and_then(Value::as_str).map(String::from),
                    Some("image_url") => obj
                        .get("image_url")
                        .and_then(Value::as_object)
                        .and_then(|img| img.get("url"))
                        .and_then(Value::as_str)
                        .map(|url| format!("![image]({url})")),
                    _ => None,
                }
            })
            .collect::<Vec<_>>()
            .join("\n"),
        other => other.to_string(),
    }
}

/// Extract `error.message` / `message` from an HTTP error body, falling back
/// to the raw body (port of `extractHttpErrorMessage`).
pub fn extract_http_error_message(body: &str) -> String {
    let parsed: Result<Value, _> = serde_json::from_str(body);
    match parsed {
        Ok(Value::Object(map)) => message_from_object(&map).unwrap_or_else(|| body.to_string()),
        _ => body.to_string(),
    }
}

fn message_from_object(map: &Map<String, Value>) -> Option<String> {
    let from_error = map
        .get("error")
        .and_then(Value::as_object)
        .and_then(|e| e.get("message"))
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
        .map(String::from);
    let from_message = map
        .get("message")
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
        .map(String::from);
    from_error.or(from_message)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::MessageImage;

    fn text_message(id: &str, role: MessageRole, content: &str) -> Message {
        Message {
            id: id.to_string(),
            role,
            content: content.to_string(),
            parts: vec![ContentPart::Text {
                text: content.to_string(),
            }],
        }
    }

    #[test]
    fn system_prompt_is_prepended() {
        let msgs = vec![text_message("1", MessageRole::User, "hi")];
        let wire = build_request_messages(&msgs, Some("you are helpful"), None);
        assert_eq!(wire[0], WireMessage::text("system", "you are helpful"));
        assert_eq!(wire[1].role, "user");
    }

    #[test]
    fn tool_message_round_trips_call_id_and_output() {
        let mut msg = text_message("1", MessageRole::Tool, "fallback");
        msg.parts = vec![ContentPart::ToolResult {
            call_id: "call_9".to_string(),
            name: "terminal".to_string(),
            output: "ls output".to_string(),
            is_error: false,
        }];
        let wire = build_request_messages(&[msg], None, None);
        assert_eq!(wire[0].role, "tool");
        assert_eq!(wire[0].content, Value::String("ls output".into()));
        assert_eq!(wire[0].tool_call_id.as_deref(), Some("call_9"));
    }

    #[test]
    fn assistant_tool_call_round_maps_reasoning_content_back() {
        let msg = Message {
            id: "1".into(),
            role: MessageRole::Assistant,
            content: String::new(),
            parts: vec![
                ContentPart::Text {
                    text: "<think>why</think>".to_string(),
                },
                ContentPart::ToolCall {
                    call_id: "call_1".to_string(),
                    name: "terminal".to_string(),
                    arguments: "{}".to_string(),
                },
            ],
        };
        let wire = build_request_messages(&[msg], None, Some("reasoning_content"));
        assert_eq!(wire[0].reasoning_content.as_deref(), Some("why"));
        assert_eq!(wire[0].content, Value::Null);
        let calls = wire[0].tool_calls.as_ref().unwrap();
        assert_eq!(calls[0].id, "call_1");
        assert_eq!(calls[0].function.name, "terminal");
    }

    #[test]
    fn reasoning_summary_strips_think_on_assistant_text() {
        let msg = text_message("1", MessageRole::Assistant, "<think>x</think>body");
        let wire = build_request_messages(&[msg], None, Some("reasoning_summary"));
        assert_eq!(wire[0].content, Value::String("body".into()));
        assert!(wire[0].reasoning_content.is_none());
    }

    #[test]
    fn think_tag_echoes_tags_verbatim() {
        let msg = text_message("1", MessageRole::Assistant, "<think>x</think>body");
        let wire = build_request_messages(&[msg], None, Some("think_tag"));
        assert_eq!(wire[0].content, Value::String("<think>x</think>body".into()));
    }

    #[test]
    fn image_message_uses_multipart_content() {
        let msg = Message {
            id: "1".into(),
            role: MessageRole::User,
            content: "look".into(),
            parts: vec![
                ContentPart::Text {
                    text: "look".into(),
                },
                ContentPart::Image {
                    image: MessageImage {
                        data_uri: "data:image/png;base64,AAAA".into(),
                        local_path: "/files/chat_images/1.png".into(),
                    },
                },
            ],
        };
        let wire = build_request_messages(&[msg], None, None);
        let arr = wire[0].content.as_array().unwrap();
        assert_eq!(arr[0]["type"], "text");
        assert_eq!(arr[1]["type"], "image_url");
        assert_eq!(arr[1]["image_url"]["url"], "data:image/png;base64,AAAA");
    }

    #[test]
    fn reasoning_params_none_sends_deepseek_disable_pair() {
        let (effort, thinking) = build_reasoning_params(Some("none"));
        assert_eq!(effort.as_deref(), Some("none"));
        assert_eq!(thinking.unwrap().kind, "disabled");
        let (effort, thinking) = build_reasoning_params(Some("high"));
        assert_eq!(effort.as_deref(), Some("high"));
        assert!(thinking.is_none());
        let (effort, thinking) = build_reasoning_params(None);
        assert!(effort.is_none() && thinking.is_none());
    }

    #[test]
    fn tool_spec_parses_schema_and_degrades_to_empty_object() {
        let ok = build_tool_spec("t", "d", r#"{"type":"object"}"#);
        assert_eq!(ok.function.parameters, json!({"type":"object"}));
        let bad = build_tool_spec("t", "d", "not json");
        assert_eq!(bad.function.parameters, json!({}));
    }

    #[test]
    fn request_serialization_omits_absent_fields() {
        let request = ChatCompletionRequest {
            model: "m".into(),
            messages: vec![WireMessage::text("user", "hi")],
            temperature: None,
            top_p: None,
            max_tokens: None,
            reasoning_effort: None,
            thinking: None,
            tools: None,
            stream: false,
        };
        let json = serde_json::to_value(&request).unwrap();
        assert!(json.get("temperature").is_none());
        assert!(json.get("stream").is_none());
        assert!(json.get("tools").is_none());
    }

    #[test]
    fn response_content_coercion_handles_both_shapes() {
        assert_eq!(extract_response_content(&json!("plain")), "plain");
        let multipart = json!([
            {"type": "text", "text": "see this"},
            {"type": "image_url", "image_url": {"url": "https://x/y.png"}}
        ]);
        assert_eq!(
            extract_response_content(&multipart),
            "see this\n![image](https://x/y.png)"
        );
    }

    #[test]
    fn http_error_message_extraction() {
        assert_eq!(
            extract_http_error_message(r#"{"error":{"message":"quota exceeded"}}"#),
            "quota exceeded"
        );
        assert_eq!(
            extract_http_error_message(r#"{"message":"nope"}"#),
            "nope"
        );
        assert_eq!(extract_http_error_message("raw body"), "raw body");
    }
}
