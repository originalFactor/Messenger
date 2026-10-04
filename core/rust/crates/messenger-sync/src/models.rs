//! Cloud document models (port of `CloudModels.kt` + the request DTOs in
//! `CloudApiClient.kt`). Field names are the server's Mongo document shape —
//! `_id` via serde rename — and every field defaults so old-server payloads
//! keep parsing.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

pub const DEFAULT_CLOUD_SERVER_URL: &str = "https://messenger.ptoe.cc";
pub const BUILTIN_PROVIDER_ID: &str = "builtin-messenger-cloud-ai";
pub const BUILTIN_PROVIDER_NAME: &str = "Messenger Cloud AI";
/// The builtin title agent never syncs (locally seeded, see the core crate).
pub const BUILTIN_TITLE_AGENT_ID: &str = "builtin-title-agent";
pub const ROLE_CHAT: &str = "chat";
pub const ROLE_TITLE: &str = "title";

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudQuotaEntitlement {
    #[serde(rename = "planName")]
    pub plan_name: Option<String>,
    pub source: Option<String>,
    pub balance: i64,
    /// null = unlimited validity.
    #[serde(rename = "expiresAt")]
    pub expires_at: Option<i64>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudUser {
    pub id: String,
    pub email: String,
    pub role: Option<String>,
    #[serde(rename = "aiApiKey")]
    pub ai_api_key: Option<String>,
    #[serde(rename = "quotaBalance")]
    pub quota_balance: Option<i64>,
    #[serde(rename = "quotaExpiresAt")]
    pub quota_expires_at: Option<i64>,
    #[serde(rename = "quotaEntitlements")]
    pub quota_entitlements: Vec<CloudQuotaEntitlement>,
    #[serde(rename = "avatarUrl")]
    pub avatar_url: Option<String>,
    #[serde(rename = "avatarVersion")]
    pub avatar_version: Option<i64>,
    #[serde(rename = "syncVersion")]
    pub sync_version: i64,
    #[serde(rename = "createdAt")]
    pub created_at: Option<i64>,
    #[serde(rename = "updatedAt")]
    pub updated_at: Option<i64>,
    #[serde(rename = "lastLoginAt")]
    pub last_login_at: Option<i64>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudSyncResult {
    #[serde(rename = "latestVersion")]
    pub latest_version: i64,
    pub agents: i64,
    pub conversations: i64,
    pub providers: i64,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudAgentDocument {
    #[serde(rename = "_id")]
    pub id: String,
    pub name: String,
    #[serde(rename = "avatarUrl")]
    pub avatar_url: Option<String>,
    #[serde(rename = "avatarVersion")]
    pub avatar_version: Option<i64>,
    #[serde(rename = "systemPrompt")]
    pub system_prompt: String,
    pub description: String,
    #[serde(rename = "defaultModelId")]
    pub default_model_id: Option<String>,
    pub temperature: f64,
    #[serde(rename = "topP")]
    pub top_p: f64,
    #[serde(rename = "maxTokens")]
    pub max_tokens: Option<i64>,
    #[serde(rename = "reasoningEffort")]
    pub reasoning_effort: Option<String>,
    #[serde(rename = "isDefault")]
    pub is_default: bool,
    #[serde(rename = "followDefaultSystemPrompt")]
    pub follow_default_system_prompt: bool,
    #[serde(rename = "followDefaultModel")]
    pub follow_default_model: bool,
    #[serde(rename = "followDefaultTemperature")]
    pub follow_default_temperature: bool,
    #[serde(rename = "followDefaultTopP")]
    pub follow_default_top_p: bool,
    #[serde(rename = "followDefaultMaxTokens")]
    pub follow_default_max_tokens: bool,
    #[serde(rename = "followDefaultReasoningEffort")]
    pub follow_default_reasoning_effort: bool,
    #[serde(rename = "marketAgentId")]
    pub market_agent_id: Option<String>,
    #[serde(rename = "marketAgentVersion")]
    pub market_agent_version: Option<i64>,
    #[serde(rename = "marketAgentRole")]
    pub market_agent_role: Option<String>,
    pub role: Option<String>,
    #[serde(rename = "toolsEnabled")]
    pub tools_enabled: bool,
    #[serde(rename = "toolsFollowDefault")]
    pub tools_follow_default: bool,
    #[serde(rename = "toolsConfig")]
    pub tools_config: Option<HashMap<String, bool>>,
    #[serde(rename = "createdAt")]
    pub created_at: i64,
    #[serde(rename = "updatedAt")]
    pub updated_at: i64,
    pub version: i64,
    pub deleted: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudMessageDocument {
    pub id: String,
    pub role: String,
    pub content: String,
    #[serde(rename = "partsJson")]
    pub parts_json: Option<String>,
    pub timestamp: i64,
    pub status: String,
    #[serde(rename = "errorMessage")]
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudConversationDocument {
    #[serde(rename = "_id")]
    pub id: String,
    #[serde(rename = "agentId")]
    pub agent_id: String,
    pub title: String,
    #[serde(rename = "providerId")]
    pub provider_id: String,
    #[serde(rename = "overrideModelId")]
    pub override_model_id: Option<String>,
    #[serde(rename = "overrideTemperature")]
    pub override_temperature: Option<f64>,
    #[serde(rename = "overrideTopP")]
    pub override_top_p: Option<f64>,
    #[serde(rename = "overrideMaxTokens")]
    pub override_max_tokens: Option<i64>,
    #[serde(rename = "overrideReasoningEffort")]
    pub override_reasoning_effort: Option<String>,
    #[serde(rename = "overrideToolsEnabled")]
    pub override_tools_enabled: Option<bool>,
    #[serde(rename = "overrideToolsConfig")]
    pub override_tools_config: Option<HashMap<String, bool>>,
    pub writable: bool,
    #[serde(rename = "reasoningFormat")]
    pub reasoning_format: Option<String>,
    #[serde(rename = "contextSummary")]
    pub context_summary: Option<String>,
    #[serde(rename = "contextSummaryUntil")]
    pub context_summary_until: i64,
    #[serde(rename = "contextTokens")]
    pub context_tokens: i64,
    #[serde(rename = "contextTokensAt")]
    pub context_tokens_at: i64,
    pub messages: Vec<CloudMessageDocument>,
    #[serde(rename = "createdAt")]
    pub created_at: i64,
    #[serde(rename = "updatedAt")]
    pub updated_at: i64,
    pub version: i64,
    pub deleted: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudModelDocument {
    pub id: String,
    #[serde(rename = "modelId")]
    pub model_id: String,
    #[serde(rename = "displayName")]
    pub display_name: String,
    #[serde(rename = "isEnabled")]
    pub is_enabled: bool,
    #[serde(rename = "contextWindow")]
    pub context_window: i64,
    #[serde(rename = "inputRate")]
    pub input_rate: Option<f64>,
    #[serde(rename = "outputRate")]
    pub output_rate: Option<f64>,
    #[serde(rename = "inputModalities")]
    pub input_modalities: String,
    #[serde(rename = "outputModalities")]
    pub output_modalities: String,
    #[serde(rename = "supportsToolCalling")]
    pub supports_tool_calling: bool,
    #[serde(rename = "supportsThinking")]
    pub supports_thinking: bool,
    #[serde(rename = "supportsJsonOutput")]
    pub supports_json_output: bool,
    #[serde(rename = "supportsTemperature")]
    pub supports_temperature: bool,
    #[serde(rename = "createdAt")]
    pub created_at: i64,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudProviderDocument {
    #[serde(rename = "_id")]
    pub id: String,
    pub name: String,
    #[serde(rename = "baseUrl")]
    pub base_url: String,
    #[serde(rename = "apiKey")]
    pub api_key: String,
    pub models: Vec<CloudModelDocument>,
    #[serde(rename = "createdAt")]
    pub created_at: i64,
    #[serde(rename = "updatedAt")]
    pub updated_at: i64,
    pub version: i64,
    pub deleted: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudSyncResponse {
    pub agents: Vec<CloudAgentDocument>,
    pub conversations: Vec<CloudConversationDocument>,
    pub providers: Vec<CloudProviderDocument>,
    #[serde(rename = "latestVersion")]
    pub latest_version: i64,
}

/// Paged sync response for one collection.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct CloudSyncPage<D> {
    pub documents: Vec<D>,
    #[serde(rename = "hasMore")]
    pub has_more: bool,
    #[serde(rename = "nextCursor")]
    pub next_cursor: Option<String>,
    #[serde(rename = "latestVersion")]
    pub latest_version: i64,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct CloudUpsertResponse {
    pub id: String,
    pub version: i64,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct UserResponse {
    pub user: CloudUser,
}

#[derive(Debug, Clone, Serialize)]
pub struct CredentialsRequest {
    pub email: String,
    pub password: String,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct SuccessResponse {
    pub success: bool,
}

// ---------------------------------------------------------------------------
// Avatars, Market & Cards DTOs
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct CloudAvatarResponse {
    pub url: Option<String>,
    pub version: i64,
    #[serde(rename = "avatarVersion")]
    pub avatar_version: Option<i64>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudMarketAgent {
    pub id: String,
    pub name: String,
    #[serde(rename = "avatarUrl")]
    pub avatar_url: Option<String>,
    #[serde(rename = "avatarVersion")]
    pub avatar_version: Option<i64>,
    #[serde(rename = "systemPrompt")]
    pub system_prompt: String,
    pub temperature: f64,
    #[serde(rename = "topP")]
    pub top_p: f64,
    #[serde(rename = "maxTokens")]
    pub max_tokens: Option<i64>,
    #[serde(rename = "reasoningEffort")]
    pub reasoning_effort: Option<String>,
    #[serde(rename = "createdAt")]
    pub created_at: i64,
    #[serde(rename = "updatedAt")]
    pub updated_at: i64,
    pub version: i64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudMarketAgentListResponse {
    pub agents: Vec<CloudMarketAgent>,
    #[serde(rename = "nextCursor")]
    pub next_cursor: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct CloudMarketAgentResponse {
    pub agent: CloudMarketAgent,
    #[serde(rename = "isOwner")]
    pub is_owner: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudMarketAgentUpdate {
    pub agent: CloudMarketAgent,
    #[serde(rename = "hasUpdate")]
    pub has_update: bool,
}

#[derive(Debug, Clone, Default, Serialize)]
pub struct CloudMarketAgentRequest {
    pub name: String,
    #[serde(rename = "systemPrompt")]
    pub system_prompt: String,
    pub temperature: f64,
    #[serde(rename = "topP")]
    pub top_p: f64,
    #[serde(rename = "maxTokens", skip_serializing_if = "Option::is_none")]
    pub max_tokens: Option<i64>,
    #[serde(rename = "reasoningEffort", skip_serializing_if = "Option::is_none")]
    pub reasoning_effort: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudCardPreview {
    pub code: String,
    #[serde(rename = "planName")]
    pub plan_name: String,
    #[serde(rename = "quotaTokens")]
    pub quota_tokens: i64,
    #[serde(rename = "validityDays")]
    pub validity_days: i64,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct CloudCardPreviewResponse {
    pub card: CloudCardPreview,
}

#[derive(Debug, Clone, Serialize)]
pub struct RedeemCodeRequest {
    pub code: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudRedeemRedemption {
    #[serde(rename = "cardCode")]
    pub card_code: String,
    #[serde(rename = "planName")]
    pub plan_name: String,
    #[serde(rename = "quotaTokens")]
    pub quota_tokens: i64,
    #[serde(rename = "validityDays")]
    pub validity_days: i64,
    #[serde(rename = "redeemedAt")]
    pub redeemed_at: i64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudQuotaSummary {
    pub balance: Option<i64>,
    #[serde(rename = "expiresAt")]
    pub expires_at: Option<i64>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct CloudRedeemResponse {
    pub redemption: CloudRedeemRedemption,
    pub quota: Option<CloudQuotaSummary>,
}

// ---------------------------------------------------------------------------
// push request DTOs
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize)]
pub struct CloudAgentRequest {
    pub id: String,
    pub name: String,
    #[serde(rename = "avatarUrl", skip_serializing_if = "Option::is_none")]
    pub avatar_url: Option<String>,
    #[serde(rename = "systemPrompt")]
    pub system_prompt: String,
    /// Always sent upstream (Kotlin: no default value so encodeDefaults never
    /// drops it).
    pub description: String,
    #[serde(rename = "defaultModelId", skip_serializing_if = "Option::is_none")]
    pub default_model_id: Option<String>,
    pub temperature: f64,
    #[serde(rename = "topP")]
    pub top_p: f64,
    #[serde(rename = "maxTokens", skip_serializing_if = "Option::is_none")]
    pub max_tokens: Option<i64>,
    #[serde(rename = "reasoningEffort", skip_serializing_if = "Option::is_none")]
    pub reasoning_effort: Option<String>,
    #[serde(rename = "isDefault")]
    pub is_default: bool,
    #[serde(rename = "followDefaultSystemPrompt")]
    pub follow_default_system_prompt: bool,
    #[serde(rename = "followDefaultModel")]
    pub follow_default_model: bool,
    #[serde(rename = "followDefaultTemperature")]
    pub follow_default_temperature: bool,
    #[serde(rename = "followDefaultTopP")]
    pub follow_default_top_p: bool,
    #[serde(rename = "followDefaultMaxTokens")]
    pub follow_default_max_tokens: bool,
    #[serde(rename = "followDefaultReasoningEffort")]
    pub follow_default_reasoning_effort: bool,
    #[serde(rename = "marketAgentId", skip_serializing_if = "Option::is_none")]
    pub market_agent_id: Option<String>,
    #[serde(rename = "marketAgentVersion", skip_serializing_if = "Option::is_none")]
    pub market_agent_version: Option<i64>,
    #[serde(rename = "marketAgentRole", skip_serializing_if = "Option::is_none")]
    pub market_agent_role: Option<String>,
    pub role: String,
    #[serde(rename = "toolsEnabled")]
    pub tools_enabled: bool,
    #[serde(rename = "toolsFollowDefault")]
    pub tools_follow_default: bool,
    #[serde(rename = "toolsConfig")]
    pub tools_config: HashMap<String, bool>,
    #[serde(rename = "createdAt")]
    pub created_at: i64,
    #[serde(rename = "updatedAt")]
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize)]
pub struct CloudMessageRequest {
    pub id: String,
    pub role: String,
    pub content: String,
    #[serde(rename = "partsJson", skip_serializing_if = "Option::is_none")]
    pub parts_json: Option<String>,
    pub timestamp: i64,
    pub status: String,
    #[serde(rename = "errorMessage", skip_serializing_if = "Option::is_none")]
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct CloudConversationRequest {
    pub id: String,
    pub title: String,
    #[serde(rename = "agentId")]
    pub agent_id: String,
    #[serde(rename = "providerId")]
    pub provider_id: String,
    #[serde(rename = "overrideModelId", skip_serializing_if = "Option::is_none")]
    pub override_model_id: Option<String>,
    #[serde(rename = "overrideTemperature", skip_serializing_if = "Option::is_none")]
    pub override_temperature: Option<f64>,
    #[serde(rename = "overrideTopP", skip_serializing_if = "Option::is_none")]
    pub override_top_p: Option<f64>,
    #[serde(rename = "overrideMaxTokens", skip_serializing_if = "Option::is_none")]
    pub override_max_tokens: Option<i64>,
    #[serde(rename = "overrideReasoningEffort", skip_serializing_if = "Option::is_none")]
    pub override_reasoning_effort: Option<String>,
    #[serde(rename = "overrideToolsEnabled", skip_serializing_if = "Option::is_none")]
    pub override_tools_enabled: Option<bool>,
    #[serde(rename = "overrideToolsConfig", skip_serializing_if = "Option::is_none")]
    pub override_tools_config: Option<HashMap<String, bool>>,
    pub writable: bool,
    #[serde(rename = "reasoningFormat", skip_serializing_if = "Option::is_none")]
    pub reasoning_format: Option<String>,
    #[serde(rename = "contextSummary", skip_serializing_if = "Option::is_none")]
    pub context_summary: Option<String>,
    #[serde(rename = "contextSummaryUntil")]
    pub context_summary_until: i64,
    #[serde(rename = "contextTokens")]
    pub context_tokens: i64,
    #[serde(rename = "contextTokensAt")]
    pub context_tokens_at: i64,
    pub messages: Vec<CloudMessageRequest>,
    #[serde(rename = "createdAt")]
    pub created_at: i64,
    #[serde(rename = "updatedAt")]
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize)]
pub struct CloudProviderRequest {
    pub id: String,
    pub name: String,
    #[serde(rename = "baseUrl")]
    pub base_url: String,
    #[serde(rename = "apiKey")]
    pub api_key: String,
    pub models: Vec<CloudModelDocument>,
    #[serde(rename = "createdAt")]
    pub created_at: i64,
    #[serde(rename = "updatedAt")]
    pub updated_at: i64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn documents_parse_with_underscore_id_and_defaults() {
        let agent: CloudAgentDocument = serde_json::from_value(serde_json::json!({
            "_id": "a1", "name": "agent", "role": "title"
        }))
        .unwrap();
        assert_eq!(agent.id, "a1");
        assert_eq!(agent.role.as_deref(), Some("title"));
        assert_eq!(agent.temperature, 0.0);
        assert!(!agent.deleted);

        let conversation: CloudConversationDocument = serde_json::from_value(serde_json::json!({
            "_id": "c1", "agentId": "a1", "messages": []
        }))
        .unwrap();
        assert_eq!(conversation.context_tokens, 0);
        assert!(conversation.messages.is_empty());
    }

    #[test]
    fn agent_request_always_serializes_description() {
        let request = CloudAgentRequest {
            id: "a1".into(),
            name: "n".into(),
            system_prompt: "sp".into(),
            description: String::new(),
            temperature: 0.7,
            top_p: 1.0,
            is_default: true,
            role: ROLE_CHAT.into(),
            tools_config: Default::default(),
            created_at: 1,
            updated_at: 2,
            ..Default::default()
        };
        let json = serde_json::to_value(&request).unwrap();
        assert!(json.get("description").is_some());
        assert_eq!(json["isDefault"], true);
    }
}

impl Default for CloudAgentRequest {
    fn default() -> Self {
        Self {
            id: String::new(),
            name: String::new(),
            avatar_url: None,
            system_prompt: String::new(),
            description: String::new(),
            default_model_id: None,
            temperature: 0.0,
            top_p: 0.0,
            max_tokens: None,
            reasoning_effort: None,
            is_default: false,
            follow_default_system_prompt: false,
            follow_default_model: false,
            follow_default_temperature: false,
            follow_default_top_p: false,
            follow_default_max_tokens: false,
            follow_default_reasoning_effort: false,
            market_agent_id: None,
            market_agent_version: None,
            market_agent_role: None,
            role: ROLE_CHAT.to_string(),
            tools_enabled: false,
            tools_follow_default: false,
            tools_config: Default::default(),
            created_at: 0,
            updated_at: 0,
        }
    }
}

impl Default for CloudConversationRequest {
    fn default() -> Self {
        Self {
            id: String::new(),
            title: String::new(),
            agent_id: String::new(),
            provider_id: String::new(),
            override_model_id: None,
            override_temperature: None,
            override_top_p: None,
            override_max_tokens: None,
            override_reasoning_effort: None,
            override_tools_enabled: None,
            override_tools_config: None,
            writable: false,
            reasoning_format: None,
            context_summary: None,
            context_summary_until: 0,
            context_tokens: 0,
            context_tokens_at: 0,
            messages: Vec::new(),
            created_at: 0,
            updated_at: 0,
        }
    }
}
