//! Persisted ContentPart JSON codec (port of `data/local/ContentPartCodec.kt`).
//! The wire shape is shared verbatim with the cloud sync documents; older
//! clients drop unknown part types, and malformed payloads degrade to empty
//! parts so the message list never crashes on future data.

use serde::{Deserialize, Serialize};

use messenger_llm::domain::{ContentPart, MessageImage};

/// Mirror of [`ContentPart`] used purely for (de)serialization.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
struct StoredContentPart {
    #[serde(rename = "type")]
    kind: String,
    text: Option<String>,
    #[serde(rename = "dataUri")]
    data_uri: Option<String>,
    #[serde(rename = "localPath")]
    local_path: Option<String>,
    #[serde(rename = "callId")]
    call_id: Option<String>,
    name: Option<String>,
    arguments: Option<String>,
    output: Option<String>,
    #[serde(rename = "isError")]
    is_error: bool,
}

impl Default for StoredContentPart {
    fn default() -> Self {
        Self {
            kind: String::new(),
            text: None,
            data_uri: None,
            local_path: None,
            call_id: None,
            name: None,
            arguments: None,
            output: None,
            is_error: false,
        }
    }
}

fn to_stored(part: &ContentPart) -> StoredContentPart {
    match part {
        ContentPart::Text { text } => StoredContentPart {
            kind: "text".into(),
            text: Some(text.clone()),
            ..Default::default()
        },
        ContentPart::Image { image } => StoredContentPart {
            kind: "image".into(),
            data_uri: Some(image.data_uri.clone()),
            local_path: Some(image.local_path.clone()),
            ..Default::default()
        },
        ContentPart::ToolCall {
            call_id,
            name,
            arguments,
        } => StoredContentPart {
            kind: "tool_call".into(),
            call_id: Some(call_id.clone()),
            name: Some(name.clone()),
            arguments: Some(arguments.clone()),
            ..Default::default()
        },
        ContentPart::ToolResult {
            call_id,
            name,
            output,
            is_error,
        } => StoredContentPart {
            kind: "tool_result".into(),
            call_id: Some(call_id.clone()),
            name: Some(name.clone()),
            output: Some(output.clone()),
            is_error: *is_error,
            ..Default::default()
        },
    }
}

fn to_domain(stored: StoredContentPart) -> Option<ContentPart> {
    match stored.kind.as_str() {
        "text" => stored.text.map(|text| ContentPart::Text { text }),
        "image" => {
            let (data_uri, local_path) = (stored.data_uri?, stored.local_path?);
            Some(ContentPart::Image {
                image: MessageImage { data_uri, local_path },
            })
        }
        "tool_call" => {
            let (call_id, name) = (stored.call_id?, stored.name?);
            Some(ContentPart::ToolCall {
                call_id,
                name,
                arguments: stored.arguments.unwrap_or_default(),
            })
        }
        "tool_result" => {
            let (call_id, name) = (stored.call_id?, stored.name?);
            Some(ContentPart::ToolResult {
                call_id,
                name,
                output: stored.output.unwrap_or_default(),
                is_error: stored.is_error,
            })
        }
        _ => None,
    }
}

/// Encode parts to the persisted JSON; empty lists store as NULL.
pub fn encode_parts(parts: &[ContentPart]) -> Option<String> {
    if parts.is_empty() {
        return None;
    }
    let stored: Vec<StoredContentPart> = parts.iter().map(to_stored).collect();
    serde_json::to_string(&stored).ok()
}

/// Decode persisted JSON; blank or malformed input degrades to empty parts.
pub fn decode_parts(json: Option<&str>) -> Vec<ContentPart> {
    let Some(json) = json.filter(|s| !s.trim().is_empty()) else {
        return Vec::new();
    };
    match serde_json::from_str::<Vec<StoredContentPart>>(json) {
        Ok(stored) => stored.into_iter().filter_map(to_domain).collect(),
        Err(_) => Vec::new(),
    }
}

/// The plain-text projection of a parts list (the `content` column).
pub fn text_projection(parts: &[ContentPart]) -> String {
    parts
        .iter()
        .filter_map(|p| match p {
            ContentPart::Text { text } => Some(text.as_str()),
            _ => None,
        })
        .collect::<Vec<_>>()
        .join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_all_part_types() {
        let parts = vec![
            ContentPart::Text {
                text: "look".into(),
            },
            ContentPart::Image {
                image: MessageImage {
                    data_uri: "data:image/png;base64,AA".into(),
                    local_path: "/files/chat_images/1.png".into(),
                },
            },
            ContentPart::ToolCall {
                call_id: "call_1".into(),
                name: "terminal".into(),
                arguments: r#"{"command":"ls"}"#.into(),
            },
            ContentPart::ToolResult {
                call_id: "call_1".into(),
                name: "terminal".into(),
                output: "file.txt".into(),
                is_error: false,
            },
            ContentPart::ToolResult {
                call_id: "call_2".into(),
                name: "terminal".into(),
                output: "boom".into(),
                is_error: true,
            },
        ];
        let encoded = encode_parts(&parts).unwrap();
        assert_eq!(decode_parts(Some(&encoded)), parts);
    }

    #[test]
    fn empty_encodes_to_none_and_blank_decodes_to_empty() {
        assert_eq!(encode_parts(&[]), None);
        assert!(decode_parts(None).is_empty());
        assert!(decode_parts(Some("  ")).is_empty());
    }

    #[test]
    fn malformed_and_future_payloads_degrade_to_empty() {
        assert!(decode_parts(Some("not json")).is_empty());
        assert!(decode_parts(Some(r#"[{"type":"hologram"}]"#)).is_empty());
    }

    #[test]
    fn incomplete_parts_are_dropped_not_crashing() {
        let decoded = decode_parts(Some(
            r#"[{"type":"tool_call","name":"terminal"},{"type":"image","dataUri":"data:x"}]"#,
        ));
        assert!(decoded.is_empty());
    }
}
