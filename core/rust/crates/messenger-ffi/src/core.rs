//! Real-core FFI surface: store lifecycle, legacy import, change
//! notifications, and agent turns. The echo/ping demo lives in `lib.rs`;
//! this module is the M2 re-anchor surface.

use std::sync::Arc;

use messenger_core::agent::{run_chat_turn, AgentEvent, TitleConfig, ToolHost, TurnRequest};
use messenger_store::model::StoredMessage;
use messenger_store::{import_legacy, Store};
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
