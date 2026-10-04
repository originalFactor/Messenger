//! Stream events emitted by the chat parser (port of `ChatStreamEvent.kt`).

/// One completed tool call accumulated over a streaming round.
#[derive(Debug, Clone, PartialEq)]
pub struct ToolCallData {
    pub call_id: String,
    pub name: String,
    /// Raw JSON arguments string (not yet parsed).
    pub arguments: String,
}

/// Events the streaming parser emits per chunk. Mirrors the Kotlin sealed
/// class: the parser emits `Done` twice (once on `finish_reason`, once on
/// `[DONE]`) carrying the same accumulated tool calls — consumers act on the
/// final event only.
#[derive(Debug, Clone, PartialEq)]
pub enum ChatStreamEvent {
    Content(String),
    Done {
        finish_reason: Option<String>,
        /// Token usage attached by the stream (`stream_options.include_usage`);
        /// `None` when the provider does not report usage.
        usage: Option<crate::dto::Usage>,
        tool_calls: Vec<ToolCallData>,
    },
    Error(String),
    /// Reasoning content was detected in the stream; `format` names the wire
    /// field it arrived on:
    /// - `"reasoning_content"` — DeepSeek-style full chain of thought; later
    ///   requests must map think tags back to the `reasoning_content` field;
    /// - `"reasoning_summary"` — GPT-style encrypted reasoning surfaced only
    ///   as a summary via the `reasoning` field; later requests strip the
    ///   thinking and send no reasoning fields back.
    ReasoningDetected { format: String },
}
