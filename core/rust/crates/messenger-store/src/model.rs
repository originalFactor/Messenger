//! Typed row structs mirroring the SQLite tables. Column names live in the
//! store queries; these structs are the layer the FFI facade and the agent
//! core consume.

use serde::{Deserialize, Serialize};

/// `providers` row.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StoredProvider {
    pub id: String,
    pub name: String,
    pub base_url: String,
    pub api_key: String,
    pub created_at: i64,
    pub updated_at: i64,
}

/// `models` row (one available model under a provider).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StoredModel {
    pub id: String,
    pub provider_id: String,
    pub model_id: String,
    pub display_name: String,
    pub is_enabled: bool,
    pub context_window: i64,
    pub input_rate: Option<f64>,
    pub output_rate: Option<f64>,
    pub input_modalities: String,
    pub output_modalities: String,
    pub supports_tool_calling: bool,
    pub supports_thinking: bool,
    pub supports_json_output: bool,
    pub supports_temperature: bool,
    pub created_at: i64,
}

/// `agents` row.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StoredAgent {
    pub id: String,
    pub name: String,
    pub avatar: Option<String>,
    pub system_prompt: String,
    pub description: String,
    pub default_model_id: Option<String>,
    pub temperature: Option<f64>,
    pub top_p: Option<f64>,
    pub max_tokens: Option<i64>,
    pub reasoning_effort: Option<String>,
    pub is_default: bool,
    pub follow_default_system_prompt: bool,
    pub follow_default_model: bool,
    pub follow_default_temperature: bool,
    pub follow_default_top_p: bool,
    pub follow_default_max_tokens: bool,
    pub follow_default_reasoning_effort: bool,
    pub market_agent_id: Option<String>,
    pub market_agent_version: Option<i64>,
    pub market_agent_role: Option<String>,
    /// `chat` or `title` (title-role single holder).
    pub role: String,
    pub tools_enabled: bool,
    pub tools_follow_default: bool,
    /// JSON `Map<String, Boolean>`; `''` = all on.
    pub tools_config: String,
    pub created_at: i64,
    pub updated_at: i64,
}

/// `conversations` row with the per-conversation override columns.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct StoredConversation {
    pub id: String,
    pub title: String,
    pub provider_id: String,
    pub agent_id: String,
    pub override_model_id: Option<String>,
    pub override_temperature: Option<f64>,
    pub override_top_p: Option<f64>,
    pub override_max_tokens: Option<i64>,
    pub override_reasoning_effort: Option<String>,
    pub override_tools_enabled: Option<bool>,
    pub override_tools_config: Option<String>,
    /// Conversation-level read-only/writable mode.
    pub writable: bool,
    pub created_at: i64,
    pub updated_at: i64,
    pub last_message: Option<String>,
    /// `reasoning_content` / `reasoning_summary` / `think_tag`, latched from
    /// the first streaming response that carried reasoning.
    pub reasoning_format: Option<String>,
    pub context_summary: Option<String>,
    pub context_summary_until: i64,
    pub context_tokens: i64,
    pub context_tokens_at: i64,
}

/// `messages` row. `role`/`status` are lowercase enum names
/// (`user|assistant|system|tool`, `sending|sent|error`); `parts_json` holds
/// the ContentPart JSON array (opaque here; the codecs live in the core).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StoredMessage {
    pub id: String,
    pub conversation_id: String,
    pub role: String,
    pub content: String,
    pub parts_json: Option<String>,
    pub timestamp: i64,
    pub status: String,
    pub error_message: Option<String>,
}
