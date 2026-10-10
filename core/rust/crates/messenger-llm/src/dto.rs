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
    /// Cache breakdown; omitted by providers that do not cache prompts.
    #[serde(rename = "prompt_tokens_details")]
    pub prompt_tokens_details: Option<PromptTokensDetails>,
}

/// Cache hit/miss breakdown of the prompt (OpenAI `prompt_tokens_details`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Deserialize)]
#[serde(default)]
pub struct PromptTokensDetails {
    #[serde(rename = "cached_tokens")]
    pub cached_tokens: i64,
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

// ---------------------------------------------------------------------------
// Non-streaming response shapes
// ---------------------------------------------------------------------------

/// Non-streaming chat completions response.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct ChatCompletionResponse {
    pub id: String,
    pub choices: Vec<ChatChoice>,
    pub model: String,
    pub usage: Option<Usage>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct ChatChoice {
    pub index: i64,
    pub message: ResponseMessage,
    #[serde(rename = "finish_reason")]
    pub finish_reason: Option<String>,
}

/// The non-streaming assistant message: `content` may be a string or
/// multipart array; reasoning rides `reasoning_content` (full CoT) or
/// `reasoning` (GPT-style summary).
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct ResponseMessage {
    pub role: Option<String>,
    pub content: Option<Value>,
    #[serde(rename = "reasoning_content")]
    pub reasoning_content: Option<String>,
    pub reasoning: Option<String>,
    #[serde(rename = "tool_calls")]
    pub tool_calls: Option<Vec<ResponseToolCall>>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct ResponseToolCall {
    pub id: Option<String>,
    #[serde(rename = "type")]
    pub kind: Option<String>,
    pub function: Option<ToolCallFunction>,
}

/// `GET /models` response. `context_window` / `input_rate` / `output_rate`
/// are Messenger cloud-proxy extensions; other providers usually omit them.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct ModelsResponse {
    pub data: Vec<ModelEntry>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct ModelEntry {
    pub id: String,
    #[serde(rename = "object")]
    pub object: String,
    pub created: Option<i64>,
    #[serde(rename = "owned_by")]
    pub owned_by: Option<String>,
    #[serde(rename = "context_window")]
    pub context_window: Option<i64>,
    #[serde(rename = "input_rate")]
    pub input_rate: Option<f64>,
    #[serde(rename = "output_rate")]
    pub output_rate: Option<f64>,
}
