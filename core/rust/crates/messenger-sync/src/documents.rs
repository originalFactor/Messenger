//! Document ↔ store-row mappings (port of the `toEntity`/`toCloudRequest`
//! mapping block at the tail of `CloudSyncRepository.kt`) plus the shared
//! HTTP error extraction.

use serde_json::Value;
use std::collections::HashMap;

use crate::models::*;
use messenger_store::model::{
    StoredAgent, StoredConversation, StoredMessage, StoredModel, StoredProvider,
};

/// Extract `error.message` / `message` from an error body (server errors all
/// use OpenAI-shaped `{"error":{"message"}}` or plain `{"message"}`).
pub fn extract_error_message(body: &str) -> String {
    let Ok(Value::Object(map)) = serde_json::from_str::<Value>(body) else {
        return body.to_string();
    };
    let from_error = map
        .get("error")
        .and_then(Value::as_object)
        .and_then(|e| e.get("message"))
        .and_then(Value::as_str);
    let from_message = map.get("message").and_then(Value::as_str);
    from_error
        .or(from_message)
        .filter(|s| !s.trim().is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| body.to_string())
}

// ---------------------------------------------------------------------------
// pull side: document → row
// ---------------------------------------------------------------------------

pub fn agent_document_to_row(doc: &CloudAgentDocument, avatar_override: Option<String>) -> StoredAgent {
    StoredAgent {
        id: doc.id.clone(),
        name: doc.name.clone(),
        avatar: avatar_override.or_else(|| doc.avatar_url.clone()),
        system_prompt: doc.system_prompt.clone(),
        description: doc.description.clone(),
        default_model_id: doc.default_model_id.clone(),
        temperature: Some(doc.temperature),
        top_p: Some(doc.top_p),
        max_tokens: doc.max_tokens,
        reasoning_effort: doc.reasoning_effort.clone(),
        is_default: doc.is_default,
        follow_default_system_prompt: doc.follow_default_system_prompt,
        follow_default_model: doc.follow_default_model,
        follow_default_temperature: doc.follow_default_temperature,
        follow_default_top_p: doc.follow_default_top_p,
        follow_default_max_tokens: doc.follow_default_max_tokens,
        follow_default_reasoning_effort: doc.follow_default_reasoning_effort,
        market_agent_id: doc.market_agent_id.clone(),
        market_agent_version: doc.market_agent_version,
        market_agent_role: doc.market_agent_role.clone(),
        role: doc.role.clone().unwrap_or_else(|| ROLE_CHAT.to_string()),
        tools_enabled: doc.tools_enabled,
        tools_follow_default: doc.tools_follow_default,
        tools_config: doc
            .tools_config
            .as_ref()
            .map(|m| serde_json::to_string(m).unwrap_or_default())
            .unwrap_or_default(),
        created_at: doc.created_at,
        updated_at: doc.updated_at,
    }
}

pub fn message_document_to_row(doc: &CloudMessageDocument, conversation_id: &str) -> StoredMessage {
    StoredMessage {
        id: doc.id.clone(),
        conversation_id: conversation_id.to_string(),
        role: doc.role.clone(),
        content: doc.content.clone(),
        parts_json: doc.parts_json.clone(),
        timestamp: doc.timestamp,
        status: doc.status.clone(),
        error_message: doc.error_message.clone(),
    }
}

pub fn conversation_document_to_row(doc: &CloudConversationDocument) -> StoredConversation {
    StoredConversation {
        id: doc.id.clone(),
        title: doc.title.clone(),
        provider_id: doc.provider_id.clone(),
        agent_id: doc.agent_id.clone(),
        override_model_id: doc.override_model_id.clone(),
        override_temperature: doc.override_temperature,
        override_top_p: doc.override_top_p,
        override_max_tokens: doc.override_max_tokens,
        override_reasoning_effort: doc.override_reasoning_effort.clone(),
        override_tools_enabled: doc.override_tools_enabled,
        override_tools_config: doc
            .override_tools_config
            .as_ref()
            .map(|m| serde_json::to_string(m).unwrap_or_default()),
        writable: doc.writable,
        created_at: doc.created_at,
        updated_at: doc.updated_at,
        last_message: doc
            .messages
            .last()
            .map(|m| m.content.clone()),
        reasoning_format: doc.reasoning_format.clone(),
        context_summary: doc.context_summary.clone(),
        context_summary_until: doc.context_summary_until,
        context_tokens: doc.context_tokens,
        context_tokens_at: doc.context_tokens_at,
    }
}

pub fn model_document_to_row(doc: &CloudModelDocument, provider_id: &str) -> StoredModel {
    StoredModel {
        id: doc.id.clone(),
        provider_id: provider_id.to_string(),
        model_id: doc.model_id.clone(),
        display_name: doc.display_name.clone(),
        is_enabled: doc.is_enabled,
        context_window: doc.context_window,
        input_rate: doc.input_rate,
        output_rate: doc.output_rate,
        input_modalities: doc.input_modalities.clone(),
        output_modalities: doc.output_modalities.clone(),
        supports_tool_calling: doc.supports_tool_calling,
        supports_thinking: doc.supports_thinking,
        supports_json_output: doc.supports_json_output,
        supports_temperature: doc.supports_temperature,
        created_at: doc.created_at,
    }
}

pub fn provider_document_to_row(doc: &CloudProviderDocument) -> StoredProvider {
    StoredProvider {
        id: doc.id.clone(),
        name: doc.name.clone(),
        base_url: doc.base_url.clone(),
        api_key: doc.api_key.clone(),
        created_at: doc.created_at,
        updated_at: doc.updated_at,
    }
}

// ---------------------------------------------------------------------------
// push side: row → request
// ---------------------------------------------------------------------------

fn parse_tools_config(json: &str) -> HashMap<String, bool> {
    serde_json::from_str(json).unwrap_or_default()
}

pub fn agent_row_to_request(row: &StoredAgent) -> CloudAgentRequest {
    CloudAgentRequest {
        id: row.id.clone(),
        name: row.name.clone(),
        avatar_url: None, // avatars ride the dedicated endpoints
        system_prompt: row.system_prompt.clone(),
        description: row.description.clone(),
        default_model_id: row.default_model_id.clone(),
        temperature: row.temperature.unwrap_or(0.0),
        top_p: row.top_p.unwrap_or(0.0),
        max_tokens: row.max_tokens,
        reasoning_effort: row.reasoning_effort.clone(),
        is_default: row.is_default,
        follow_default_system_prompt: row.follow_default_system_prompt,
        follow_default_model: row.follow_default_model,
        follow_default_temperature: row.follow_default_temperature,
        follow_default_top_p: row.follow_default_top_p,
        follow_default_max_tokens: row.follow_default_max_tokens,
        follow_default_reasoning_effort: row.follow_default_reasoning_effort,
        market_agent_id: row.market_agent_id.clone(),
        market_agent_version: row.market_agent_version,
        market_agent_role: row.market_agent_role.clone(),
        role: row.role.clone(),
        tools_enabled: row.tools_enabled,
        tools_follow_default: row.tools_follow_default,
        tools_config: parse_tools_config(&row.tools_config),
        created_at: row.created_at,
        updated_at: row.updated_at,
    }
}

pub fn message_row_to_request(row: &StoredMessage) -> CloudMessageRequest {
    CloudMessageRequest {
        id: row.id.clone(),
        role: row.role.clone(),
        content: row.content.clone(),
        parts_json: row.parts_json.clone(),
        timestamp: row.timestamp,
        status: row.status.clone(),
        error_message: row.error_message.clone(),
    }
}

pub fn conversation_row_to_request(
    row: &StoredConversation,
    messages: &[StoredMessage],
) -> CloudConversationRequest {
    CloudConversationRequest {
        id: row.id.clone(),
        title: row.title.clone(),
        agent_id: row.agent_id.clone(),
        provider_id: row.provider_id.clone(),
        override_model_id: row.override_model_id.clone(),
        override_temperature: row.override_temperature,
        override_top_p: row.override_top_p,
        override_max_tokens: row.override_max_tokens,
        override_reasoning_effort: row.override_reasoning_effort.clone(),
        override_tools_enabled: row.override_tools_enabled,
        override_tools_config: row
            .override_tools_config
            .as_deref()
            .map(parse_tools_config),
        writable: row.writable,
        reasoning_format: row.reasoning_format.clone(),
        context_summary: row.context_summary.clone(),
        context_summary_until: row.context_summary_until,
        context_tokens: row.context_tokens,
        context_tokens_at: row.context_tokens_at,
        messages: messages.iter().map(message_row_to_request).collect(),
        created_at: row.created_at,
        updated_at: row.updated_at,
    }
}

pub fn provider_row_to_request(row: &StoredProvider, models: &[StoredModel]) -> CloudProviderRequest {
    CloudProviderRequest {
        id: row.id.clone(),
        name: row.name.clone(),
        base_url: row.base_url.clone(),
        api_key: row.api_key.clone(),
        models: models
            .iter()
            .map(|m| CloudModelDocument {
                id: m.id.clone(),
                model_id: m.model_id.clone(),
                display_name: m.display_name.clone(),
                is_enabled: m.is_enabled,
                context_window: m.context_window,
                input_rate: m.input_rate,
                output_rate: m.output_rate,
                input_modalities: m.input_modalities.clone(),
                output_modalities: m.output_modalities.clone(),
                supports_tool_calling: m.supports_tool_calling,
                supports_thinking: m.supports_thinking,
                supports_json_output: m.supports_json_output,
                supports_temperature: m.supports_temperature,
                created_at: m.created_at,
            })
            .collect(),
        created_at: row.created_at,
        updated_at: row.updated_at,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn agent_document_round_trips_to_row_and_back() {
        let doc: CloudAgentDocument = serde_json::from_value(serde_json::json!({
            "_id": "a1", "name": "n", "systemPrompt": "sp", "description": "d",
            "temperature": 0.5, "topP": 0.9, "isDefault": true, "role": "title",
            "toolsEnabled": true, "toolsConfig": {"terminal": false},
            "createdAt": 1, "updatedAt": 2
        }))
        .unwrap();
        let row = agent_document_to_row(&doc, None);
        assert_eq!(row.role, "title");
        assert_eq!(row.tools_config, r#"{"terminal":false}"#);
        let request = agent_row_to_request(&row);
        assert_eq!(request.role, "title");
        assert_eq!(request.tools_config.get("terminal"), Some(&false));
        assert!(request.avatar_url.is_none());
    }

    #[test]
    fn conversation_row_carries_last_message_projection() {
        let doc: CloudConversationDocument = serde_json::from_value(serde_json::json!({
            "_id": "c1", "agentId": "a1",
            "messages": [
                {"id": "m1", "role": "user", "content": "hi", "timestamp": 1, "status": "sent"},
                {"id": "m2", "role": "assistant", "content": "yo", "timestamp": 2, "status": "sent"}
            ]
        }))
        .unwrap();
        let row = conversation_document_to_row(&doc);
        assert_eq!(row.last_message.as_deref(), Some("yo"));
    }
}
