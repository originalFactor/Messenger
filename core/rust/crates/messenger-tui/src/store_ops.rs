//! Typed store accessors, turn resolution, first-run seeding.
//!
//! `resolve_turn` is the TUI's port of `ChatViewModel.resolveEffectiveAgent`
//! + `getActiveModelAndProvider` + the FFI `build_turn_request`: it merges the
//! default-agent follow flags, applies the per-conversation overrides, binds
//! the model/provider, and resolves the declared tool list (built-ins plus
//! every connected MCP server's tools).

use std::collections::HashMap;

use messenger_core::agent::{TitleConfig, TurnRequest};
use messenger_mcp::client::McpChatTool;
use messenger_store::model::{StoredAgent, StoredConversation, StoredMessage, StoredModel};
use messenger_store::Store;
use messenger_tools::BuiltinTool;

use crate::config;

/// The localized summarization prompt, verbatim from
/// `values/strings.xml`'s `context_summarize_prompt`.
pub const SUMMARIZE_PROMPT: &str = "You compress conversation history for a chat app. Compress the conversation below into a concise summary that preserves the user's requests and preferences, key facts, decisions, and unfinished tasks. Reply with the summary text only — no preamble, no quotes — written in the same language as the conversation.";

/// The kv key the UI uses for the selected Agent.
pub const CURRENT_AGENT_KEY: &str = "current_agent_id";
/// The kv key holding the persisted MCP server list.
pub const MCP_SERVERS_KEY: &str = "mcp_servers_json";

/// A turn fully resolved and ready for `run_chat_turn`.
#[derive(Debug)]
pub struct ResolvedTurn {
    pub request: TurnRequest,
    pub conversation_title: String,
}

/// Why a turn could not be resolved (shown verbatim in the status line).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TurnError {
    /// The conversation row disappeared.
    NoConversation,
    /// The bound Agent row disappeared.
    NoAgent,
    /// The Agent has no model and no enabled model exists in the store.
    ModelNotConfigured,
    /// The bound model's provider row disappeared.
    NoProvider,
    /// There is no enabled model anywhere.
    NoEnabledModel,
}

impl TurnError {
    pub fn message(&self) -> &'static str {
        match self {
            TurnError::NoConversation => "Conversation not found.",
            TurnError::NoAgent => "Agent not found.",
            TurnError::ModelNotConfigured => "Set a model for this Agent first.",
            TurnError::NoProvider => "The model's provider no longer exists.",
            TurnError::NoEnabledModel => "Enable at least one model first.",
        }
    }
}

/// Read the persisted cloud session out of the kv table.
pub fn session_from_kv(store: &Store) -> messenger_sync::Session {
    messenger_sync::Session {
        cookie: store.kv_get(messenger_sync::KV_SESSION).ok().flatten(),
        host: store.kv_get(messenger_sync::KV_SESSION_HOST).ok().flatten(),
    }
}

/// Seed the default Agent when none exists, mirroring
/// `AppContainer.createDefaultAgentIfNeededLocked` (same name, prompt, and
/// sampling parameters) and pin `current_agent_id` when it is unset.
pub fn ensure_default_agent(store: &Store) -> Result<(), String> {
    if store.get_default_agent().map_err(|e| e.to_string())?.is_none() {
        let now = messenger_store::now_ms();
        let id = uuid::Uuid::new_v4().to_string();
        store
            .upsert_agent(&StoredAgent {
                id: id.clone(),
                name: "默认 Agent".into(),
                system_prompt: "You are a helpful assistant.".into(),
                avatar: None,
                description: String::new(),
                default_model_id: None,
                temperature: Some(0.7),
                top_p: Some(1.0),
                max_tokens: None,
                reasoning_effort: None,
                is_default: true,
                follow_default_system_prompt: false,
                follow_default_model: false,
                follow_default_temperature: false,
                follow_default_top_p: false,
                follow_default_max_tokens: false,
                follow_default_reasoning_effort: false,
                market_agent_id: None,
                market_agent_version: None,
                market_agent_role: None,
                role: "chat".into(),
                tools_enabled: false,
                tools_follow_default: false,
                tools_config: String::new(),
                created_at: now,
                updated_at: now,
            })
            .map_err(|e| e.to_string())?;
        if store
            .kv_get(CURRENT_AGENT_KEY)
            .map_err(|e| e.to_string())?
            .unwrap_or_default()
            .is_empty()
        {
            store.kv_set(CURRENT_AGENT_KEY, &id).map_err(|e| e.to_string())?;
        }
    } else if store
        .kv_get(CURRENT_AGENT_KEY)
        .map_err(|e| e.to_string())?
        .unwrap_or_default()
        .is_empty()
    {
        // The default exists but no Agent was ever selected.
        if let Some(default) = store.get_default_agent().map_err(|e| e.to_string())? {
            store
                .kv_set(CURRENT_AGENT_KEY, &default.id)
                .map_err(|e| e.to_string())?;
        }
    }
    Ok(())
}

/// The Agent the UI currently has selected (falling back to the default).
pub fn current_agent(store: &Store) -> Result<Option<StoredAgent>, String> {
    let id = store
        .kv_get(CURRENT_AGENT_KEY)
        .map_err(|e| e.to_string())?
        .unwrap_or_default();
    if id.is_empty() {
        return store.get_default_agent().map_err(|e| e.to_string());
    }
    match store.get_agent(&id).map_err(|e| e.to_string())? {
        Some(agent) => Ok(Some(agent)),
        None => store.get_default_agent().map_err(|e| e.to_string()),
    }
}

pub fn list_conversations(store: &Store) -> Result<Vec<StoredConversation>, String> {
    store.list_conversations().map_err(|e| e.to_string())
}

pub fn load_messages(store: &Store, conversation_id: &str) -> Result<Vec<StoredMessage>, String> {
    store
        .list_messages_by_conversation(conversation_id)
        .map_err(|e| e.to_string())
}

/// Decode the per-tool config JSON; malformed input means "all on".
pub fn tools_config_map(raw: &str) -> HashMap<String, bool> {
    if raw.trim().is_empty() {
        return HashMap::new();
    }
    serde_json::from_str(raw).unwrap_or_default()
}

/// Create a new conversation bound to the current Agent.
pub fn create_conversation(
    store: &Store,
    agent: &StoredAgent,
    provider_id: &str,
) -> Result<StoredConversation, String> {
    let now = messenger_store::now_ms();
    let conversation = StoredConversation {
        id: uuid::Uuid::new_v4().to_string(),
        title: "新对话".into(),
        provider_id: provider_id.to_string(),
        agent_id: agent.id.clone(),
        override_model_id: None,
        override_temperature: None,
        override_top_p: None,
        override_max_tokens: None,
        override_reasoning_effort: None,
        override_tools_enabled: None,
        override_tools_config: None,
        writable: false,
        created_at: now,
        updated_at: now,
        last_message: None,
        reasoning_format: None,
        context_summary: None,
        context_summary_until: 0,
        context_tokens: 0,
        context_tokens_at: 0,
    };
    store
        .upsert_conversation(&conversation)
        .map_err(|e| e.to_string())?;
    Ok(conversation)
}

/// Apply the default-agent follow flags, then the conversation overrides —
/// the merge order `ChatViewModel.resolveEffectiveAgent` uses.
pub fn resolve_effective_agent(
    agent: StoredAgent,
    conversation: Option<&StoredConversation>,
    default_agent: Option<&StoredAgent>,
) -> StoredAgent {
    let mut effective = agent;
    if !effective.is_default {
        if let Some(default) = default_agent {
            if effective.follow_default_system_prompt {
                effective.system_prompt = default.system_prompt.clone();
            }
            if effective.follow_default_model {
                effective.default_model_id = default.default_model_id.clone();
            }
            if effective.follow_default_temperature {
                effective.temperature = default.temperature;
            }
            if effective.follow_default_top_p {
                effective.top_p = default.top_p;
            }
            if effective.follow_default_max_tokens {
                effective.max_tokens = default.max_tokens;
            }
            if effective.follow_default_reasoning_effort {
                effective.reasoning_effort = default.reasoning_effort.clone();
            }
            if effective.tools_follow_default {
                effective.tools_enabled = default.tools_enabled;
                effective.tools_config = default.tools_config.clone();
            }
        }
    }
    if let Some(conversation) = conversation {
        if let Some(model) = conversation.override_model_id.clone() {
            effective.default_model_id = Some(model);
        }
        if let Some(temperature) = conversation.override_temperature {
            effective.temperature = Some(temperature);
        }
        if let Some(top_p) = conversation.override_top_p {
            effective.top_p = Some(top_p);
        }
        if let Some(max_tokens) = conversation.override_max_tokens {
            effective.max_tokens = Some(max_tokens);
        }
        if let Some(effort) = conversation.override_reasoning_effort.clone() {
            effective.reasoning_effort = Some(effort);
        }
        if let Some(enabled) = conversation.override_tools_enabled {
            effective.tools_enabled = enabled;
        }
        if let Some(config) = conversation.override_tools_config.clone() {
            effective.tools_config = config;
        }
    }
    effective
}

/// Model binding, mirroring `getActiveModelAndProvider`: the Agent's model,
/// else the conversation's provider's first model, else the first enabled
/// model in the store.
fn resolve_model(
    store: &Store,
    conversation: &StoredConversation,
    agent: &StoredAgent,
) -> Result<StoredModel, TurnError> {
    if let Some(model_id) = agent.default_model_id.as_deref().filter(|id| !id.is_empty()) {
        if let Ok(Some(model)) = store.get_model(model_id) {
            return Ok(model);
        }
        return Err(TurnError::ModelNotConfigured);
    }
    if !conversation.provider_id.trim().is_empty() {
        if let Ok(models) = store.list_models_by_provider(&conversation.provider_id) {
            if let Some(model) = models.into_iter().next() {
                return Ok(model);
            }
        }
    }
    let enabled = store
        .list_models()
        .map_err(|_| TurnError::NoEnabledModel)?
        .into_iter()
        .find(|model| model.is_enabled);
    enabled.ok_or(TurnError::NoEnabledModel)
}

/// Build the full `TurnRequest` for a conversation.
///
/// `mcp_tools` is the Engine's cached MCP tool list (`McpClient::tools()` is
/// async and this resolver is not).
pub fn resolve_turn(
    store: &Store,
    conversation_id: &str,
    mcp_tools: &[McpChatTool],
    cloud_api_key: Option<&str>,
) -> Result<ResolvedTurn, TurnError> {
    let conversation = store
        .get_conversation(conversation_id)
        .map_err(|_| TurnError::NoConversation)?
        .ok_or(TurnError::NoConversation)?;
    let agent = store
        .get_agent(&conversation.agent_id)
        .map_err(|_| TurnError::NoAgent)?
        .ok_or(TurnError::NoAgent)?;
    let default_agent = store.get_default_agent().ok().flatten();
    let effective = resolve_effective_agent(agent.clone(), Some(&conversation), default_agent.as_ref());

    let model = resolve_model(store, &conversation, &effective)?;
    let provider = store
        .get_provider(&model.provider_id)
        .map_err(|_| TurnError::NoProvider)?
        .ok_or(TurnError::NoProvider)?;

    // The builtin cloud provider has no user-entered key of its own: it
    // authenticates with the signed-in account's AI key.
    let api_key = if provider.id == messenger_sync::BUILTIN_PROVIDER_ID {
        cloud_api_key
            .filter(|key| !key.is_empty())
            .unwrap_or(provider.api_key.as_str())
            .to_string()
    } else {
        provider.api_key.clone()
    };

    let tools = resolve_tools(&effective, &conversation, mcp_tools);

    let title_agent = store
        .get_title_agent()
        .ok()
        .flatten()
        .map(|holder| TitleConfig {
            agent_id: holder.id.clone(),
            system_prompt: holder.system_prompt.clone(),
            model_id: holder
                .default_model_id
                .clone()
                .or_else(|| Some(model.model_id.clone())),
            temperature: holder.temperature,
            top_p: holder.top_p,
            max_tokens: holder.max_tokens,
        });

    Ok(ResolvedTurn {
        conversation_title: conversation.title.clone(),
        request: TurnRequest {
            conversation_id: conversation.id.clone(),
            model_id: model.model_id.clone(),
            base_url: provider.base_url.clone(),
            api_key,
            system_prompt: effective.system_prompt.clone(),
            temperature: effective.temperature,
            top_p: effective.top_p,
            max_tokens: effective.max_tokens,
            reasoning_effort: effective.reasoning_effort.clone(),
            tools,
            context_window: model.context_window,
            summarize_prompt: SUMMARIZE_PROMPT.to_string(),
            title: title_agent,
        },
    })
}

/// Built-in registry filtered by the effective per-tool config and writable
/// mode, plus every connected MCP server's tools (mode-agnostic, their write
/// nature is not classified).
pub fn resolve_tools(
    agent: &StoredAgent,
    conversation: &StoredConversation,
    mcp_tools: &[McpChatTool],
) -> Vec<BuiltinTool> {
    let mut tools = messenger_tools::resolve_request_tools(
        &messenger_tools::builtin_registry(),
        agent.tools_enabled,
        &tools_config_map(&agent.tools_config),
        conversation.writable,
    );
    for tool in mcp_tools {
        tools.push(BuiltinTool {
            name: tool.name.clone(),
            description: tool.description.clone(),
            write_access: false,
            parameters_json: tool.parameters_json.clone(),
            read_only: false,
        });
    }
    tools
}

/// The workspace path used when the config is missing.
pub fn default_workspace_dir_string() -> String {
    config::default_workspace_dir().to_string_lossy().to_string()
}
