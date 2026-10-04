//! Wire DTOs for the OpenAI-compatible chat completions API (streaming
//! subset first; request DTOs arrive with the client). Field names and
//! optionality mirror the kotlinx.serialization DTOs exactly.

use serde::Deserialize;
use serde_json::Value;

/// Token usage report (`prompt_tokens` / `completion_tokens` / `total_tokens`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Deserialize)]
#[serde(default)]
pub struct Usage {
    #[serde(rename = "prompt_tokens")]
    pub prompt_tokens: i64,
    #[serde(rename = "completion_tokens")]
    pub completion_tokens: i64,
    #[serde(rename = "total_tokens")]
    pub total_tokens: i64,
}

/// A streaming chat completions chunk. All fields tolerated-missing: providers
/// vary wildly and malformed chunks are skipped by the parser.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct ChatCompletionChunk {
    pub id: String,
    pub choices: Vec<ChatChunkChoice>,
    pub model: String,
    /// Usage-only chunk arrives before `[DONE]` with empty `choices`.
    pub usage: Option<Usage>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct ChatChunkChoice {
    pub index: i64,
    pub delta: ChatDelta,
    #[serde(rename = "finish_reason")]
    pub finish_reason: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct ChatDelta {
    pub role: Option<String>,
    /// String content, or an OpenAI multipart array of `{type, text|image_url}`
    /// parts — carried as raw JSON so one field serves both shapes.
    pub content: Option<Value>,
    #[serde(rename = "reasoning_content")]
    pub reasoning_content: Option<String>,
    /// GPT-style Reasoning Summary (OpenRouter / Responses-gateway standard).
    pub reasoning: Option<String>,
    #[serde(rename = "tool_calls")]
    pub tool_calls: Option<Vec<ToolCallFragment>>,
}

/// Streaming tool call fragment: id/name ride the first chunk, `arguments`
/// accumulate across chunks.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct ToolCallFragment {
    pub index: Option<i64>,
    pub id: Option<String>,
    #[serde(rename = "type")]
    pub kind: Option<String>,
    pub function: Option<ToolCallFunction>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct ToolCallFunction {
    pub name: Option<String>,
    pub arguments: Option<String>,
}
