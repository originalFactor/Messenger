//! Real-core FFI surface: store lifecycle, legacy import, change
//! notifications, and agent turns. The echo/ping demo lives in `lib.rs`;
//! this module is the M2 re-anchor surface.

use std::sync::Arc;

use messenger_core::agent::{run_chat_turn, AgentEvent, TitleConfig, ToolHost, TurnRequest};
use messenger_store::model::{StoredAgent, StoredConversation, StoredMessage, StoredModel, StoredProvider};
use messenger_store::{import_legacy, Store};
use messenger_sync::{
    AvatarManager, Session, SyncEngine, KV_SESSION, KV_SESSION_HOST,
};
use messenger_tools::ToolExecutionResult;

/// The handle the app shell holds: store + paths.
#[derive(uniffi::Object)]
pub struct CoreHandle {
    pub(crate) store: Store,
    /// One active turn at a time in M1; the facade cancel it from Kotlin.
    cancel: std::sync::Mutex<tokio_util::sync::CancellationToken>,
}

/// Per-entity change notification bridged to Kotlin.
#[derive(uniffi::Record)]
pub struct StoreChangeEvent {
    pub kind: String,
    pub ids: Vec<String>,
}

#[uniffi::export(callback_interface)]
pub trait StoreChangeListener: Send + Sync {
    fn on_change(&self, event: StoreChangeEvent);
}

/// One conversation for the UI list (JSON keeps the FFI surface small while
/// the repository facades are built out in M2).
#[derive(uniffi::Record)]
pub struct ConversationSummary {
    pub id: String,
    pub title: String,
    pub agent_id: String,
    pub last_message: Option<String>,
    pub updated_at: i64,
}

/// One stored message (parts arrive as the persisted JSON string).
#[derive(uniffi::Record)]
pub struct MessageRow {
    pub id: String,
    pub conversation_id: String,
    pub role: String,
    pub content: String,
    pub parts_json: Option<String>,
    pub timestamp: i64,
    pub status: String,
    pub error_message: Option<String>,
}

/// Tool execution routed back into Kotlin (AIDL companion bridge, desktop
/// process handling). Synchronous by design: the platform side blocks on its
/// own transport; Rust awaits it via spawn_blocking.
#[uniffi::export(callback_interface)]
pub trait PlatformToolHost: Send + Sync {
    fn execute(&self, name: String, arguments_json: String) -> ToolResultFfi;
}

#[derive(uniffi::Record)]
pub struct ToolResultFfi {
    pub output: String,
    pub is_error: bool,
}

struct BridgeToolHost {
    platform: Arc<Box<dyn PlatformToolHost>>,
}

#[async_trait::async_trait]
impl ToolHost for BridgeToolHost {
    async fn execute(&self, name: &str, arguments_json: &str) -> ToolExecutionResult {
        let name = name.to_string();
        let arguments = arguments_json.to_string();
        // The JNI upcall pins the calling thread; hop to a blocking thread.
        let platform = Arc::clone(&self.platform);
        let result = tokio::task::spawn_blocking(move || {
            let r = platform.execute(name, arguments);
            ToolExecutionResult {
                output: r.output,
                is_error: r.is_error,
            }
        })
        .await
        .unwrap_or(ToolExecutionResult {
            output: "tool host cancelled".into(),
            is_error: true,
        });
        result
    }
}

/// Turn parameters the platform resolves (model binding, overrides, tools).
#[derive(uniffi::Record)]
pub struct TurnConfig {
    pub conversation_id: String,
    pub model_id: String,
    pub base_url: String,
    pub api_key: String,
    pub system_prompt: String,
    pub temperature: Option<f64>,
    pub top_p: Option<f64>,
    pub max_tokens: Option<i64>,
    pub reasoning_effort: Option<String>,
    /// Pre-resolved tool names ("terminal", "glob", ..., MCP prefixed).
    pub tool_names: Vec<String>,
    pub writable: bool,
    pub context_window: i64,
    pub summarize_prompt: String,
    pub title_agent_id: Option<String>,
    pub title_agent_system_prompt: Option<String>,
    pub title_agent_model_id: Option<String>,
}

#[uniffi::export(callback_interface)]
pub trait TurnEventSink: Send + Sync {
    fn on_event(&self, event_json: String);
}

fn agent_event_to_json(event: &AgentEvent) -> String {
    let value = match event {
        AgentEvent::TurnStarted => serde_json::json!({"type": "TurnStarted"}),
        AgentEvent::StreamingStarted { message_id } => serde_json::json!({
            "type": "StreamingStarted", "messageId": message_id
        }),
        AgentEvent::TextDelta { round, text } => serde_json::json!({
            "type": "TextDelta", "round": round, "text": text
        }),
        AgentEvent::ReasoningFormatDetected { format } => serde_json::json!({
            "type": "ReasoningFormatDetected", "format": format
        }),
        AgentEvent::RoundPersisted { round, message_id } => serde_json::json!({
            "type": "RoundPersisted", "round": round, "messageId": message_id
        }),
        AgentEvent::FinalMessagePersisted { message_id, content } => serde_json::json!({
            "type": "FinalMessagePersisted", "messageId": message_id, "content": content
        }),
        AgentEvent::ToolCallStarted { call_id, name } => serde_json::json!({
            "type": "ToolCallStarted", "callId": call_id, "name": name
        }),
        AgentEvent::ToolCallFinished { call_id, name, output, is_error } => serde_json::json!({
            "type": "ToolCallFinished", "callId": call_id, "name": name,
            "output": output, "isError": is_error
        }),
        AgentEvent::UsageRecorded { prompt_tokens, completion_tokens } => serde_json::json!({
            "type": "UsageRecorded", "promptTokens": prompt_tokens, "completionTokens": completion_tokens
        }),
        AgentEvent::TitleGenerated { title } => {
            serde_json::json!({"type": "TitleGenerated", "title": title})
        }
        AgentEvent::TitleFailed { code } => {
            serde_json::json!({"type": "TitleFailed", "code": code})
        }
        AgentEvent::Finished { message_id } => {
            serde_json::json!({"type": "Finished", "messageId": message_id})
        }
        AgentEvent::Cancelled => serde_json::json!({"type": "Cancelled"}),
        AgentEvent::Error { code, message } => {
            serde_json::json!({"type": "Error", "code": code, "message": message})
        }
    };
    serde_json::to_string(&value).unwrap_or_else(|_| r#"{"type":"Error"}"#.to_string())
}

struct SinkBridge {
    sink: Box<dyn TurnEventSink>,
}

impl messenger_core::agent::TurnSink for SinkBridge {
    fn on_event(&self, event: &AgentEvent) {
        self.sink.on_event(agent_event_to_json(event));
    }
}


#[uniffi::export]
impl CoreHandle {
    #[uniffi::constructor]
    pub fn open(
        store_path: String,
        legacy_db_dir: Option<String>,
        now_ms: i64,
    ) -> Result<Arc<Self>, CoreError> {
        let store = Store::open(std::path::Path::new(&store_path))
            .map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        if let Some(dir) = legacy_db_dir {
            match import_legacy(&store, std::path::Path::new(&dir), now_ms) {
                Ok(_) => {}
                Err(e) => log_import_failure(&e.to_string()),
            }
        }
        Ok(Arc::new(Self {
            store,
            cancel: std::sync::Mutex::new(tokio_util::sync::CancellationToken::new()),
        }))
    }

    /// Subscribe to store changes. Returns a listener id (ignored for now —
    /// the M2 facade keeps subscriptions for the app lifetime).
    pub fn subscribe(&self, listener: Box<dyn StoreChangeListener>) {
        let forward = move |event: &messenger_store::StoreEvent| {
            listener.on_change(StoreChangeEvent {
                kind: format!("{:?}", event.kind),
                ids: event.ids.clone(),
            });
        };
        self.store.subscribe(Box::new(forward));
    }

    pub fn list_conversations(&self) -> Result<Vec<ConversationSummary>, CoreError> {
        self.store
            .list_conversations()
            .map(|rows| {
                rows.into_iter()
                    .map(|c| ConversationSummary {
                        id: c.id,
                        title: c.title,
                        agent_id: c.agent_id,
                        last_message: c.last_message,
                        updated_at: c.updated_at,
                    })
                    .collect()
            })
            .map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn list_messages(&self, conversation_id: String) -> Result<Vec<MessageRow>, CoreError> {
        self.store
            .list_messages_by_conversation(&conversation_id)
            .map(|rows| rows.into_iter().map(to_message_row).collect())
            .map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    // -- Store CRUD via JSON --

    pub fn list_providers_json(&self) -> Result<String, CoreError> {
        let rows = self.store.list_providers().map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&rows).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn get_provider_json(&self, id: String) -> Result<Option<String>, CoreError> {
        let row = self.store.get_provider(&id).map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        match row {
            Some(r) => Ok(Some(serde_json::to_string(&r).map_err(|e| CoreError::Generic { detail: e.to_string() })?)),
            None => Ok(None),
        }
    }

    pub fn upsert_provider_json(&self, json: String) -> Result<(), CoreError> {
        let row: StoredProvider = serde_json::from_str(&json).map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        self.store.upsert_provider(&row).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn delete_provider(&self, id: String) -> Result<(), CoreError> {
        self.store.delete_provider(&id).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn list_models_json(&self) -> Result<String, CoreError> {
        let rows = self.store.list_models().map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&rows).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn list_models_by_provider_json(&self, provider_id: String) -> Result<String, CoreError> {
        let rows = self.store.list_models_by_provider(&provider_id).map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&rows).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn get_model_json(&self, id: String) -> Result<Option<String>, CoreError> {
        let row = self.store.get_model(&id).map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        match row {
            Some(r) => Ok(Some(serde_json::to_string(&r).map_err(|e| CoreError::Generic { detail: e.to_string() })?)),
            None => Ok(None),
        }
    }

    pub fn upsert_model_json(&self, json: String) -> Result<(), CoreError> {
        let row: StoredModel = serde_json::from_str(&json).map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        self.store.upsert_model(&row).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn set_model_enabled(&self, id: String, enabled: bool) -> Result<(), CoreError> {
        self.store.set_model_enabled(&id, enabled).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn delete_model(&self, id: String) -> Result<(), CoreError> {
        self.store.delete_model(&id).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn list_agents_json(&self) -> Result<String, CoreError> {
        let rows = self.store.list_agents().map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&rows).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn get_agent_json(&self, id: String) -> Result<Option<String>, CoreError> {
        let row = self.store.get_agent(&id).map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        match row {
            Some(r) => Ok(Some(serde_json::to_string(&r).map_err(|e| CoreError::Generic { detail: e.to_string() })?)),
            None => Ok(None),
        }
    }

    pub fn get_default_agent_json(&self) -> Result<Option<String>, CoreError> {
        let row = self.store.get_default_agent().map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        match row {
            Some(r) => Ok(Some(serde_json::to_string(&r).map_err(|e| CoreError::Generic { detail: e.to_string() })?)),
            None => Ok(None),
        }
    }

    pub fn get_title_agent_json(&self) -> Result<Option<String>, CoreError> {
        let row = self.store.get_title_agent().map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        match row {
            Some(r) => Ok(Some(serde_json::to_string(&r).map_err(|e| CoreError::Generic { detail: e.to_string() })?)),
            None => Ok(None),
        }
    }

    pub fn upsert_agent_json(&self, json: String) -> Result<(), CoreError> {
        let row: StoredAgent = serde_json::from_str(&json).map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        self.store.upsert_agent(&row).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn delete_agent(&self, id: String) -> Result<(), CoreError> {
        self.store.delete_agent(&id).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn list_conversations_json(&self) -> Result<String, CoreError> {
        let rows = self.store.list_conversations().map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&rows).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn list_conversations_by_agent_json(&self, agent_id: String) -> Result<String, CoreError> {
        let rows = self.store.list_conversations_by_agent(&agent_id).map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&rows).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn get_conversation_json(&self, id: String) -> Result<Option<String>, CoreError> {
        let row = self.store.get_conversation(&id).map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        match row {
            Some(r) => Ok(Some(serde_json::to_string(&r).map_err(|e| CoreError::Generic { detail: e.to_string() })?)),
            None => Ok(None),
        }
    }

    pub fn upsert_conversation_json(&self, json: String) -> Result<(), CoreError> {
        let row: StoredConversation = serde_json::from_str(&json).map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        self.store.upsert_conversation(&row).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn update_conversation_last_message(&self, id: String, last_message: Option<String>, updated_at: i64) -> Result<(), CoreError> {
        self.store.update_conversation_last_message(&id, last_message.as_deref(), updated_at).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn delete_conversation(&self, id: String) -> Result<(), CoreError> {
        self.store.delete_conversation(&id).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn list_messages_by_conversation_json(&self, conversation_id: String) -> Result<String, CoreError> {
        let rows = self.store.list_messages_by_conversation(&conversation_id).map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&rows).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn get_message_json(&self, id: String) -> Result<Option<String>, CoreError> {
        let row = self.store.get_message(&id).map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        match row {
            Some(r) => Ok(Some(serde_json::to_string(&r).map_err(|e| CoreError::Generic { detail: e.to_string() })?)),
            None => Ok(None),
        }
    }

    pub fn upsert_message_json(&self, json: String) -> Result<(), CoreError> {
        let row: StoredMessage = serde_json::from_str(&json).map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        self.store.upsert_message(&row).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn delete_message(&self, id: String) -> Result<(), CoreError> {
        self.store.delete_message(&id).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn delete_messages_by_conversation(&self, conversation_id: String) -> Result<(), CoreError> {
        self.store.delete_messages_by_conversation(&conversation_id).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn kv_get(&self, key: String) -> Result<Option<String>, CoreError> {
        self.store.kv_get(&key).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn kv_set(&self, key: String, value: String) -> Result<(), CoreError> {
        self.store.kv_set(&key, &value).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn kv_delete(&self, key: String) -> Result<(), CoreError> {
        self.store.kv_delete(&key).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    // -- Cloud SaaS & Sync --

    #[uniffi::method(async_runtime = "tokio")]
    pub async fn cloud_login(&self, email: String, password: String) -> Result<String, CoreError> {
        let engine = self.sync_engine();
        let user = engine.login(&email, &password).await.map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&user).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    #[uniffi::method(async_runtime = "tokio")]
    pub async fn cloud_register(&self, email: String, password: String) -> Result<String, CoreError> {
        let engine = self.sync_engine();
        let user = engine.register(&email, &password).await.map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&user).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    #[uniffi::method(async_runtime = "tokio")]
    pub async fn cloud_logout(&self) -> Result<(), CoreError> {
        let engine = self.sync_engine();
        engine.logout().await.map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    #[uniffi::method(async_runtime = "tokio")]
    pub async fn cloud_refresh_user(&self) -> Result<String, CoreError> {
        let engine = self.sync_engine();
        let user = engine.refresh_user().await.map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&user).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    pub fn cloud_current_user_json(&self) -> Result<Option<String>, CoreError> {
        let engine = self.sync_engine();
        match engine.current_user() {
            Some(u) => Ok(Some(serde_json::to_string(&u).map_err(|e| CoreError::Generic { detail: e.to_string() })?)),
            None => Ok(None),
        }
    }

    pub fn cloud_set_server_url(&self, url: String) -> Result<(), CoreError> {
        let engine = self.sync_engine();
        engine.set_server_url(&url).map_err(|e| CoreError::Generic { detail: e })
    }

    pub fn cloud_get_server_url(&self) -> String {
        self.sync_engine().server_url()
    }

    pub fn cloud_has_local_data(&self) -> Result<bool, CoreError> {
        self.sync_engine().has_local_data().map_err(|e| CoreError::Generic { detail: e })
    }

    pub fn cloud_mark_change(&self, kind: String, id: String, deleted: bool) -> Result<(), CoreError> {
        self.sync_engine().request_local_change(&kind, &id, deleted).map_err(|e| CoreError::Generic { detail: e })
    }

    #[uniffi::method(async_runtime = "tokio")]
    pub async fn cloud_sync(&self, replace_local: bool) -> Result<String, CoreError> {
        let engine = self.sync_engine();
        let res = engine.sync_internal(None, replace_local, None).await.map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&res).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    #[uniffi::method(async_runtime = "tokio")]
    pub async fn cloud_push_pending(&self) -> Result<String, CoreError> {
        let engine = self.sync_engine();
        let res = engine.push_pending_changes().await.map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&res).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    #[uniffi::method(async_runtime = "tokio")]
    pub async fn cloud_preview_card(&self, code: String) -> Result<String, CoreError> {
        let engine = self.sync_engine();
        let preview = engine.preview_redeem_card(&code).await.map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&preview).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    #[uniffi::method(async_runtime = "tokio")]
    pub async fn cloud_redeem_card(&self, code: String) -> Result<String, CoreError> {
        let engine = self.sync_engine();
        let res = engine.redeem_card(&code).await.map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&res).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    #[uniffi::method(async_runtime = "tokio")]
    pub async fn cloud_sync_builtin_models(&self, force: bool) -> Result<u32, CoreError> {
        let engine = self.sync_engine();
        let count = engine.sync_builtin_provider_models(force).await.map_err(|e| CoreError::Generic { detail: e })?;
        Ok(count as u32)
    }

    #[uniffi::method(async_runtime = "tokio")]
    pub async fn cloud_list_market_agents(&self, query: String, cursor: Option<String>) -> Result<String, CoreError> {
        let engine = self.sync_engine();
        let res = engine.list_market_agents(&query, cursor.as_deref()).await.map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&res).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    #[uniffi::method(async_runtime = "tokio")]
    pub async fn cloud_get_market_agent(&self, id: String) -> Result<String, CoreError> {
        let engine = self.sync_engine();
        let res = engine.get_market_agent(&id).await.map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&res).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    #[uniffi::method(async_runtime = "tokio")]
    pub async fn cloud_publish_market_agent(&self, agent_id: String) -> Result<String, CoreError> {
        let engine = self.sync_engine();
        let res = engine.publish_market_agent(&agent_id).await.map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&res).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    #[uniffi::method(async_runtime = "tokio")]
    pub async fn cloud_import_market_agent(&self, market_id: String) -> Result<String, CoreError> {
        let engine = self.sync_engine();
        let agent = engine.import_market_agent(&market_id).await.map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        serde_json::to_string(&agent).map_err(|e| CoreError::Generic { detail: e.to_string() })
    }

    #[uniffi::method(async_runtime = "tokio")]
    pub async fn cloud_cache_avatar(
        &self,
        scope: String,
        account_id: String,
        id: String,
        url: String,
        version: Option<String>,
        dest_dir: String,
    ) -> Result<String, CoreError> {
        let engine = self.sync_engine();
        let manager = AvatarManager::new(engine.client(), std::path::PathBuf::from(dest_dir));
        let path = manager
            .cache_remote_avatar(&scope, &account_id, &id, &url, version.as_deref(), &engine.server_url())
            .await
            .map_err(|e| CoreError::Generic { detail: e.to_string() })?;
        Ok(path)
    }

    /// Cancel the active turn (partial rows are preserved by the loop).
    pub fn cancel_turn(&self) {
        self.cancel.lock().unwrap().cancel();
    }

    /// Run one agent turn on the tokio runtime (exported as a Kotlin
    /// suspend function); events stream to `sink`. Cancel via `cancel_turn`.
    #[uniffi::method(async_runtime = "tokio")]
    pub async fn run_turn(
        &self,
        config: TurnConfig,
        tool_host: Box<dyn PlatformToolHost>,
        sink: Box<dyn TurnEventSink>,
    ) -> Result<(), CoreError> {
        let request = build_turn_request(&config);
        let cancel = self.cancel_token();
        let host = BridgeToolHost {
            platform: Arc::new(tool_host),
        };
        let sink = SinkBridge { sink };
        run_chat_turn(&self.store, &host, &sink, &request, cancel)
            .await
            .map_err(|e| CoreError::Generic { detail: e })
    }
}

impl CoreHandle {
    fn sync_engine(&self) -> SyncEngine<'_> {
        let cookie = self.store.kv_get(KV_SESSION).ok().flatten();
        let host = self.store.kv_get(KV_SESSION_HOST).ok().flatten();
        let session = Session { cookie, host };
        SyncEngine::new(&self.store, session)
    }

    fn cancel_token(&self) -> tokio_util::sync::CancellationToken {
        let mut guard = self.cancel.lock().unwrap();
        let token = guard.clone();
        if token.is_cancelled() {
            *guard = tokio_util::sync::CancellationToken::new();
        }
        token
    }
}

fn to_message_row(m: StoredMessage) -> MessageRow {
    MessageRow {
        id: m.id,
        conversation_id: m.conversation_id,
        role: m.role,
        content: m.content,
        parts_json: m.parts_json,
        timestamp: m.timestamp,
        status: m.status,
        error_message: m.error_message,
    }
}

fn build_turn_request(config: &TurnConfig) -> TurnRequest {
    let registry = messenger_tools::builtin_registry();
    let resolved: Vec<_> = registry
        .into_iter()
        .filter(|tool| config.tool_names.iter().any(|n| n == &tool.name))
        .collect();
    TurnRequest {
        conversation_id: config.conversation_id.clone(),
        model_id: config.model_id.clone(),
        base_url: config.base_url.clone(),
        api_key: config.api_key.clone(),
        system_prompt: config.system_prompt.clone(),
        temperature: config.temperature,
        top_p: config.top_p,
        max_tokens: config.max_tokens,
        reasoning_effort: config.reasoning_effort.clone(),
        tools: resolved,
        context_window: config.context_window,
        summarize_prompt: config.summarize_prompt.clone(),
        title: config.title_agent_id.as_ref().map(|id| TitleConfig {
            agent_id: id.clone(),
            system_prompt: config.title_agent_system_prompt.clone().unwrap_or_default(),
            model_id: config.title_agent_model_id.clone(),
            temperature: None,
            top_p: None,
            max_tokens: None,
        }),
    }
}

#[derive(uniffi::Error, thiserror::Error, Debug)]
pub enum CoreError {
    #[error("{detail}")]
    Generic { detail: String },
}

fn log_import_failure(detail: &str) {
    // Import failures must never block startup; surface via logs for now.
    eprintln!("messenger-core: legacy import failed: {detail}");
}

// ---------------------------------------------------------------------------
// Document Engine FFI Boundary (TARGET.md §4, §5, §6)
// ---------------------------------------------------------------------------

#[derive(uniffi::Object)]
pub struct DocumentHandle {
    session: std::sync::Mutex<messenger_markdown::StreamingSession>,
}

#[uniffi::export]
impl DocumentHandle {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            session: std::sync::Mutex::new(messenger_markdown::StreamingSession::new()),
        })
    }

    /// Feed incoming text delta, returning JSON `DiffBatch` if any diffs accumulated.
    pub fn feed(&self, text: String) -> String {
        let mut session = self.session.lock().unwrap();
        session.feed(&text);
        let batch = session.drain_batch();
        serde_json::to_string(&batch).unwrap_or_else(|_| r#"{"diffs":[]}"#.into())
    }

    /// Flush session on stream completion.
    pub fn finish(&self) -> String {
        let mut session = self.session.lock().unwrap();
        let batch = session.finish();
        serde_json::to_string(&batch).unwrap_or_else(|_| r#"{"diffs":[]}"#.into())
    }

    /// Get current full document block array as JSON.
    pub fn get_document_json(&self) -> String {
        let session = self.session.lock().unwrap();
        serde_json::to_string(session.document().blocks()).unwrap_or_else(|_| "[]".into())
    }
}

/// Parse full markdown document text into JSON Block array for static rendering.
#[uniffi::export]
pub fn parse_markdown_to_blocks_json(markdown: String) -> String {
    let mut session = messenger_markdown::StreamingSession::new();
    session.feed(&markdown);
    let _ = session.finish();
    serde_json::to_string(session.document().blocks()).unwrap_or_else(|_| "[]".into())
}
