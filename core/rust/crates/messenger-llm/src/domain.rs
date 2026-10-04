//! LLM-facing message model (port of the `domain/model` Message &
//! ContentPart shapes the request builder consumes). The agent core reuses
//! these directly — this is the projection the LLM layer needs.

/// Message roles in a chat history.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MessageRole {
    User,
    Assistant,
    System,
    Tool,
}

/// A message image: the `data:` URI goes straight to the API; the local path
/// is the render-time cache copy (never sent).
#[derive(Debug, Clone, PartialEq)]
pub struct MessageImage {
    pub data_uri: String,
    pub local_path: String,
}

/// One part of a multimodal message payload.
#[derive(Debug, Clone, PartialEq)]
pub enum ContentPart {
    Text {
        text: String,
    },
    Image {
        image: MessageImage,
    },
    /// Tool call record on an assistant message (the request source of the
    /// paired Tool message). `arguments` is the model's raw JSON string.
    ToolCall {
        call_id: String,
        name: String,
        arguments: String,
    },
    /// Execution result on a Tool message. `is_error` only tints UI state;
    /// the request side does not distinguish.
    ToolResult {
        call_id: String,
        name: String,
        output: String,
        is_error: bool,
    },
}

/// A message in a conversation history.
#[derive(Debug, Clone, PartialEq)]
pub struct Message {
    pub id: String,
    pub conversation_id: String,
    pub role: MessageRole,
    /// Plain-text projection of the text parts (previews/titles/search);
    /// image parts are NOT inlined here.
    pub content: String,
    /// Full payload; single-text messages carry one Text part.
    pub parts: Vec<ContentPart>,
    /// Strictly-increasing ordering key within a conversation.
    pub timestamp: i64,
}

impl Message {
    pub fn has_images(&self) -> bool {
        self.parts
            .iter()
            .any(|p| matches!(p, ContentPart::Image { .. }))
    }

    /// Concatenation of the Text parts with "\n", or the plain-text
    /// projection when no parts are stored.
    pub fn text_body(&self) -> String {
        let segments: Vec<&str> = self
            .parts
            .iter()
            .filter_map(|p| match p {
                ContentPart::Text { text } => Some(text.as_str()),
                _ => None,
            })
            .collect();
        if segments.is_empty() {
            self.content.clone()
        } else {
            segments.join("\n")
        }
    }
}
