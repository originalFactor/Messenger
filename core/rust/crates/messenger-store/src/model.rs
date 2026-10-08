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
///
/// The Kotlin bridge encodes with `encodeDefaults = false`, so fields whose
/// value equals the DTO default (`context_window = 0`, modality `"text"`,
/// capability `false`) are OMITTED from upsert JSON. serde fills missing
/// `Option<T>` fields with `None` automatically, but plain scalar fields need
/// an explicit `#[serde(default)]` — keep the two sides in sync when adding
/// columns.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StoredModel {
    pub id: String,
    pub provider_id: String,
    pub model_id: String,
    pub display_name: String,
    pub is_enabled: bool,
    #[serde(default)]
    pub context_window: i64,
    pub input_rate: Option<f64>,
    pub output_rate: Option<f64>,
    #[serde(default = "default_text_modality")]
    pub input_modalities: String,
    #[serde(default = "default_text_modality")]
    pub output_modalities: String,
    #[serde(default)]
    pub supports_tool_calling: bool,
    #[serde(default)]
    pub supports_thinking: bool,
    #[serde(default)]
    pub supports_json_output: bool,
    #[serde(default)]
    pub supports_temperature: bool,
    pub created_at: i64,
}

fn default_text_modality() -> String {
    "text".to_string()
}

/// `agents` row.
///
/// Same `encodeDefaults = false` note as [`StoredModel`]: every boolean and
/// plain-string column with a Kotlin-side default needs `#[serde(default)]`
/// or the upsert fails with `missing field`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StoredAgent {
    pub id: String,
    pub name: String,
    pub avatar: Option<String>,
    pub system_prompt: String,
    #[serde(default)]
    pub description: String,
    pub default_model_id: Option<String>,
    pub temperature: Option<f64>,
    pub top_p: Option<f64>,
    pub max_tokens: Option<i64>,
    pub reasoning_effort: Option<String>,
    #[serde(default)]
    pub is_default: bool,
    #[serde(default)]
    pub follow_default_system_prompt: bool,
    #[serde(default)]
    pub follow_default_model: bool,
    #[serde(default)]
    pub follow_default_temperature: bool,
    #[serde(default)]
    pub follow_default_top_p: bool,
    #[serde(default)]
    pub follow_default_max_tokens: bool,
    #[serde(default)]
    pub follow_default_reasoning_effort: bool,
    pub market_agent_id: Option<String>,
    pub market_agent_version: Option<i64>,
    pub market_agent_role: Option<String>,
    /// `chat` or `title` (title-role single holder).
    #[serde(default = "default_agent_role")]
    pub role: String,
    #[serde(default)]
    pub tools_enabled: bool,
    #[serde(default)]
    pub tools_follow_default: bool,
    /// JSON `Map<String, Boolean>`; `''` = all on.
    #[serde(default)]
    pub tools_config: String,
    pub created_at: i64,
    pub updated_at: i64,
}

fn default_agent_role() -> String {
    "chat".to_string()
}

/// `projects` row: a named workspace that owns a set of conversations.
/// A conversation may only call the workspace-bound tools (terminal,
/// glob/grep/read/edit/create) while it belongs to a project.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct StoredProject {
    pub id: String,
    pub name: String,
    /// Absolute workspace directory the project's tools operate in.
    pub workspace: String,
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
    /// Owning project, or `None` for a plain conversation (no workspace tools).
    pub project_id: Option<String>,
    pub override_model_id: Option<String>,
    pub override_temperature: Option<f64>,
    pub override_top_p: Option<f64>,
    pub override_max_tokens: Option<i64>,
    pub override_reasoning_effort: Option<String>,
    pub override_tools_enabled: Option<bool>,
    pub override_tools_config: Option<String>,
    /// Conversation-level read-only/writable mode.
    #[serde(default)]
    pub writable: bool,
    pub created_at: i64,
    pub updated_at: i64,
    pub last_message: Option<String>,
    /// `reasoning_content` / `reasoning_summary` / `think_tag`, latched from
    /// the first streaming response that carried reasoning.
    pub reasoning_format: Option<String>,
    pub context_summary: Option<String>,
    #[serde(default)]
    pub context_summary_until: i64,
    #[serde(default)]
    pub context_tokens: i64,
    #[serde(default)]
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
