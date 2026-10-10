//! wasm-bindgen boundary for the browser build.
//!
//! The whole crate is gated to `wasm32`: it is only ever loaded as a wasm
//! module, and on a host target it compiles to an empty rlib so `cargo test`
//! / `cargo check` for the workspace stay green without special-casing it.
#![cfg(target_arch = "wasm32")]

//!
//! UniFFI cannot target wasm (its generator has no wasm backend, and the KMP
//! wrapper emits stubs for wasmJs), so this crate re-exports the same surface
//! over `wasm-bindgen` instead. Its shape mirrors `messenger-ffi`'s
//! `CoreHandle` method for method: JSON strings in, JSON strings out, plus
//! `Promise`-returning async methods — which is exactly what `CoreBridge`
//! expects on the Kotlin side, so `WasmCoreBridge` and `AndroidCoreBridge`
//! stay mechanically parallel instead of diverging.
//!
//! Deliberately absent (browser sandbox): MCP stdio processes, the legacy
//! Room import, and terminal/workspace tool execution (the client registers
//! no built-in tools on web).

use std::rc::Rc;
use std::sync::Arc;

use js_sys::{Function, Promise};
use messenger_core::agent::{run_chat_turn, AgentEvent, TitleConfig, ToolHost, TurnRequest, TurnSink};
use messenger_store::model::{
    StoredAgent, StoredConversation, StoredMessage, StoredModel, StoredProject, StoredProvider,
};
use messenger_store::Store;
use messenger_sync::{CloudMarketAgent, Session, SyncEngine, KV_SESSION, KV_SESSION_HOST};
use messenger_tools::{apply_writable_mode, ToolExecutionResult};
use wasm_bindgen::prelude::*;
use wasm_bindgen_futures::future_to_promise;

/// A JS function handed to Rust across the boundary.
///
/// `Function` is not `Send`/`Sync`, but the core's `ToolHost`/`TurnSink`
/// traits require both. There is exactly one thread in `wasm32-unknown-unknown`,
/// so no cross-thread use is possible; the impls make that explicit rather
/// than dropping the bounds.
struct SendFn(Function);

// SAFETY: wasm32-unknown-unknown is single-threaded; nothing can observe the
// value from another thread.
unsafe impl Send for SendFn {}
unsafe impl Sync for SendFn {}

/// Turn parameters the platform resolves (model binding, overrides, tools).
///
/// Every field carries `#[serde(default)]` because the Kotlin bridge encodes
/// with `encodeDefaults = false`: any field still holding its Kotlin default
/// (`writable = false`, `contextWindow = 0`, an empty `toolNames`, …) is
/// omitted from the JSON entirely. Same contract as the store DTOs.
#[derive(serde::Deserialize)]
#[serde(default)]
struct TurnConfig {
    #[serde(rename = "conversationId")]
    conversation_id: String,
    #[serde(rename = "modelId")]
    model_id: String,
    #[serde(rename = "baseUrl")]
    base_url: String,
    #[serde(rename = "apiKey")]
    api_key: String,
    #[serde(rename = "systemPrompt")]
    system_prompt: String,
    temperature: Option<f64>,
    #[serde(rename = "topP")]
    top_p: Option<f64>,
    #[serde(rename = "maxTokens")]
    max_tokens: Option<i64>,
    #[serde(rename = "reasoningEffort")]
    reasoning_effort: Option<String>,
    #[serde(rename = "toolNames")]
    tool_names: Vec<String>,
    writable: bool,
    #[serde(rename = "contextWindow")]
    context_window: i64,
    #[serde(rename = "summarizePrompt")]
    summarize_prompt: String,
    #[serde(rename = "titleAgentId")]
    title_agent_id: Option<String>,
    #[serde(rename = "titleAgentSystemPrompt")]
    title_agent_system_prompt: Option<String>,
    #[serde(rename = "titleAgentModelId")]
    title_agent_model_id: Option<String>,
    #[serde(rename = "hasWorkspace")]
    has_workspace: bool,
    #[serde(rename = "workspaceNote")]
    workspace_note: String,
}

impl Default for TurnConfig {
    fn default() -> Self {
        Self {
            conversation_id: String::new(),
            model_id: String::new(),
            base_url: String::new(),
            api_key: String::new(),
            system_prompt: String::new(),
            temperature: None,
            top_p: None,
            max_tokens: None,
            reasoning_effort: None,
            tool_names: Vec::new(),
            writable: false,
            context_window: 0,
            summarize_prompt: String::new(),
            title_agent_id: None,
            title_agent_system_prompt: None,
            title_agent_model_id: None,
            has_workspace: false,
            workspace_note: String::new(),
        }
    }
}

/// Tool execution routed back into Kotlin, synchronously, exactly like the
/// UniFFI `PlatformToolHost` callback.
struct JsToolHost {
    execute: Arc<SendFn>,
}

#[async_trait::async_trait]
impl ToolHost for JsToolHost {
    async fn execute(&self, name: &str, arguments_json: &str) -> ToolExecutionResult {
        let result = self
            .execute
            .0
            .call2(&JsValue::NULL, &JsValue::from_str(name), &JsValue::from_str(arguments_json));
        match result {
            Ok(value) => {
                // Kotlin returns `Pair<String, Boolean>`, which arrives as a
                // two-element JS array.
                let array: js_sys::Array = value.unchecked_into();
                ToolExecutionResult {
                    output: array.get(0).as_string().unwrap_or_default(),
                    is_error: array.get(1).as_bool().unwrap_or(true),
                }
            }
            Err(error) => ToolExecutionResult {
                output: format!("tool host failed: {error:?}"),
                is_error: true,
            },
        }
    }
}

struct JsEventSink {
    on_event: Arc<SendFn>,
}

impl TurnSink for JsEventSink {
    fn on_event(&self, event: &AgentEvent) {
        let _ = self
            .on_event
            .0
            .call1(&JsValue::NULL, &JsValue::from_str(&agent_event_to_json(event)));
    }
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
        AgentEvent::UsageRecorded { prompt_tokens, completion_tokens, cached_tokens } => serde_json::json!({
            "type": "UsageRecorded", "promptTokens": prompt_tokens, "completionTokens": completion_tokens,
            "cachedTokens": cached_tokens
        }),
        AgentEvent::TitleGenerated { title } => {
            serde_json::json!({"type": "TitleGenerated", "title": title})
        }
        AgentEvent::TitleFailed { code } => serde_json::json!({"type": "TitleFailed", "code": code}),
        AgentEvent::Finished { message_id } => {
            serde_json::json!({"type": "Finished", "messageId": message_id})
        }
        AgentEvent::Cancelled => serde_json::json!({"type": "Cancelled"}),
        AgentEvent::Error { code, message } => serde_json::json!({
            "type": "Error", "code": code, "message": message
        }),
    };
    serde_json::to_string(&value).unwrap_or_else(|_| r#"{"type":"Error"}"#.to_string())
}

/// Opens the store. `prepare_store` must have been awaited first: it loads the
/// persisted image from OPFS into memory.
///
/// A free function rather than an associated one: wasm-bindgen exports an
/// associated fn as a JS *static* method, while a free fn becomes a plain
/// module export that Kotlin's `external object` can declare directly.
#[wasm_bindgen]
pub fn open_wasm_core(store_path: String, _now_ms: f64) -> Result<WasmCore, JsValue> {
    let store = Store::open(std::path::Path::new(&store_path)).map_err(js_error)?;
    Ok(WasmCore {
        store: Rc::new(store),
        cancel: Rc::new(std::cell::RefCell::new(
            tokio_util::sync::CancellationToken::new(),
        )),
    })
}

/// Loads the persisted database image from the browser's origin-private file
/// system. The `name` is a logical database name, not a path.
#[wasm_bindgen]
pub fn prepare_wasm_store(name: String) -> Promise {
    future_to_promise(async move {
        messenger_store::prepare_store(&name)
            .await
            .map(|()| JsValue::UNDEFINED)
            .map_err(|e| JsValue::from_str(&e))
    })
}

/// The handle the Kotlin `WasmCoreBridge` holds.
///
/// The store is `Rc`-held so the `Promise`-returning methods can move a clone
/// into a `'static` future without borrowing `&self`.
#[wasm_bindgen]
pub struct WasmCore {
    store: Rc<Store>,
    cancel: Rc<std::cell::RefCell<tokio_util::sync::CancellationToken>>,
}

#[wasm_bindgen]
impl WasmCore {
    /// Subscribes to store changes. The Rust listener is a `Fn` closure, so a
    /// JS function is wrapped in the same single-threaded `Send`/`Sync` shim
    /// the tool host uses. `kind` is the entity kind; `ids` a JSON array.
    ///
    /// Subscriptions live for the store's lifetime (the app keeps one).
    pub fn subscribe(&self, listener: Function) -> Result<(), JsValue> {
        let listener = SendFn(listener);
        self.store.subscribe(Box::new(move |event| {
            let ids = serde_json::to_string(&event.ids).unwrap_or_else(|_| "[]".into());
            let _ = listener.0.call2(
                &JsValue::NULL,
                &JsValue::from_str(&format!("{:?}", event.kind)),
                &JsValue::from_str(&ids),
            );
        }));
        Ok(())
    }

    // -- store CRUD (JSON in / JSON out) --

    pub fn list_providers_json(&self) -> Result<String, JsValue> {
        json(&self.store.list_providers().map_err(js_error)?)
    }

    pub fn get_provider_json(&self, id: String) -> Result<Option<String>, JsValue> {
        optional(self.store.get_provider(&id).map_err(js_error)?)
    }

    pub fn upsert_provider_json(&self, json: String) -> Result<(), JsValue> {
        let row: StoredProvider = parse(&json)?;
        self.store.upsert_provider(&row).map_err(js_error)
    }

    pub fn delete_provider(&self, id: String) -> Result<(), JsValue> {
        self.store.delete_provider(&id).map_err(js_error)
    }

    pub fn list_models_json(&self) -> Result<String, JsValue> {
        json(&self.store.list_models().map_err(js_error)?)
    }

    pub fn list_models_by_provider_json(&self, provider_id: String) -> Result<String, JsValue> {
        json(&self.store.list_models_by_provider(&provider_id).map_err(js_error)?)
    }

    pub fn get_model_json(&self, id: String) -> Result<Option<String>, JsValue> {
        optional(self.store.get_model(&id).map_err(js_error)?)
    }

    pub fn upsert_model_json(&self, json: String) -> Result<(), JsValue> {
        let row: StoredModel = parse(&json)?;
        self.store.upsert_model(&row).map_err(js_error)
    }

    pub fn set_model_enabled(&self, id: String, enabled: bool) -> Result<(), JsValue> {
        self.store.set_model_enabled(&id, enabled).map_err(js_error)
    }

    pub fn delete_model(&self, id: String) -> Result<(), JsValue> {
        self.store.delete_model(&id).map_err(js_error)
    }

    pub fn list_agents_json(&self) -> Result<String, JsValue> {
        json(&self.store.list_agents().map_err(js_error)?)
    }

    pub fn get_agent_json(&self, id: String) -> Result<Option<String>, JsValue> {
        optional(self.store.get_agent(&id).map_err(js_error)?)
    }

    pub fn get_default_agent_json(&self) -> Result<Option<String>, JsValue> {
        optional(self.store.get_default_agent().map_err(js_error)?)
    }

    pub fn get_title_agent_json(&self) -> Result<Option<String>, JsValue> {
        optional(self.store.get_title_agent().map_err(js_error)?)
    }

    pub fn upsert_agent_json(&self, json: String) -> Result<(), JsValue> {
        let row: StoredAgent = parse(&json)?;
        self.store.upsert_agent(&row).map_err(js_error)
    }

    pub fn delete_agent(&self, id: String) -> Result<(), JsValue> {
        self.store.delete_agent(&id).map_err(js_error)
    }

    pub fn list_projects_json(&self) -> Result<String, JsValue> {
        json(&self.store.list_projects().map_err(js_error)?)
    }

    pub fn get_project_json(&self, id: String) -> Result<Option<String>, JsValue> {
        optional(self.store.get_project(&id).map_err(js_error)?)
    }

    pub fn upsert_project_json(&self, json: String) -> Result<(), JsValue> {
        let row: StoredProject = parse(&json)?;
        self.store.upsert_project(&row).map_err(js_error)
    }

    pub fn delete_project(&self, id: String) -> Result<(), JsValue> {
        self.store.delete_project(&id).map_err(js_error)
    }

    pub fn list_conversations_by_project_json(&self, project_id: String) -> Result<String, JsValue> {
        json(&self.store.list_conversations_by_project(&project_id).map_err(js_error)?)
    }

    pub fn list_conversations_json(&self) -> Result<String, JsValue> {
        json(&self.store.list_conversations().map_err(js_error)?)
    }

    pub fn list_conversations_by_agent_json(&self, agent_id: String) -> Result<String, JsValue> {
        json(&self.store.list_conversations_by_agent(&agent_id).map_err(js_error)?)
    }

    pub fn get_conversation_json(&self, id: String) -> Result<Option<String>, JsValue> {
        optional(self.store.get_conversation(&id).map_err(js_error)?)
    }

    pub fn upsert_conversation_json(&self, json: String) -> Result<(), JsValue> {
        let row: StoredConversation = parse(&json)?;
        self.store.upsert_conversation(&row).map_err(js_error)
    }

    pub fn update_conversation_last_message(
        &self,
        id: String,
        last_message: Option<String>,
        updated_at: f64,
    ) -> Result<(), JsValue> {
        self.store
            .update_conversation_last_message(&id, last_message.as_deref(), updated_at as i64)
            .map_err(js_error)
    }

    pub fn delete_conversation(&self, id: String) -> Result<(), JsValue> {
        self.store.delete_conversation(&id).map_err(js_error)
    }

    pub fn list_messages_by_conversation_json(&self, conversation_id: String) -> Result<String, JsValue> {
        json(&self.store.list_messages_by_conversation(&conversation_id).map_err(js_error)?)
    }

    pub fn get_message_json(&self, id: String) -> Result<Option<String>, JsValue> {
        optional(self.store.get_message(&id).map_err(js_error)?)
    }

    pub fn upsert_message_json(&self, json: String) -> Result<(), JsValue> {
        let row: StoredMessage = parse(&json)?;
        self.store.upsert_message(&row).map_err(js_error)
    }

    pub fn delete_message(&self, id: String) -> Result<(), JsValue> {
        self.store.delete_message(&id).map_err(js_error)
    }

    pub fn delete_messages_by_conversation(&self, conversation_id: String) -> Result<(), JsValue> {
        self.store
            .delete_messages_by_conversation(&conversation_id)
            .map_err(js_error)
    }

    pub fn kv_get(&self, key: String) -> Result<Option<String>, JsValue> {
        self.store.kv_get(&key).map_err(js_error)
    }

    pub fn kv_set(&self, key: String, value: String) -> Result<(), JsValue> {
        self.store.kv_set(&key, &value).map_err(js_error)
    }

    pub fn kv_delete(&self, key: String) -> Result<(), JsValue> {
        self.store.kv_delete(&key).map_err(js_error)
    }

    /// Wipes every row plus the stored session/user state (the settings
    /// screen's "clear all data"). Deliberately not a
    /// `cloud_sync(replace_local = true)`, which pulls the remote state back.
    pub fn clear_local_data_for_reinit(&self) -> Result<(), JsValue> {
        for provider in self.store.list_providers().map_err(js_error)? {
            self.store.delete_provider(&provider.id).map_err(js_error)?;
        }
        for conversation in self.store.list_conversations().map_err(js_error)? {
            self.store
                .delete_messages_by_conversation(&conversation.id)
                .map_err(js_error)?;
            self.store.delete_conversation(&conversation.id).map_err(js_error)?;
        }
        for agent in self.store.list_agents().map_err(js_error)? {
            self.store.delete_agent(&agent.id).map_err(js_error)?;
        }
        for project in self.store.list_projects().map_err(js_error)? {
            self.store.delete_project(&project.id).map_err(js_error)?;
        }
        for key in [
            messenger_sync::KV_SESSION,
            messenger_sync::KV_SESSION_HOST,
            messenger_sync::KV_USER,
            "current_agent_id",
        ] {
            self.store.kv_delete(key).map_err(js_error)?;
        }
        Ok(())
    }

    // -- cloud --

    pub fn cloud_login(&self, email: String, password: String) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let user = engine.login(&email, &password).await.map_err(js_str)?;
            to_js_string(&user)
        })
    }

    pub fn cloud_register(&self, email: String, password: String) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let user = engine.register(&email, &password).await.map_err(js_str)?;
            to_js_string(&user)
        })
    }

    pub fn cloud_logout(&self) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            engine.logout().await.map_err(js_str)?;
            Ok(JsValue::UNDEFINED)
        })
    }

    pub fn cloud_refresh_user(&self) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let user = engine.refresh_user().await.map_err(js_str)?;
            to_js_string(&user)
        })
    }

    pub fn cloud_current_user_json(&self) -> Option<String> {
        engine_for(&self.store)
            .current_user()
            .and_then(|user| serde_json::to_string(&user).ok())
    }

    pub fn cloud_set_server_url(&self, url: String) -> Result<(), JsValue> {
        engine_for(&self.store)
            .set_server_url(&url)
            .map_err(js_error)
    }

    pub fn cloud_get_server_url(&self) -> String {
        engine_for(&self.store).server_url()
    }

    pub fn cloud_has_local_data(&self) -> Result<bool, JsValue> {
        engine_for(&self.store).has_local_data().map_err(js_error)
    }

    pub fn cloud_mark_change(&self, kind: String, id: String, deleted: bool) -> Result<(), JsValue> {
        engine_for(&self.store)
            .request_local_change(&kind, &id, deleted)
            .map_err(js_error)
    }

    pub fn cloud_sync(&self, replace_local: bool) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let res = engine
                .sync_internal(None, replace_local, None)
                .await
                .map_err(js_str)?;
            to_js_string(&res)
        })
    }

    pub fn cloud_sync_with(
        &self,
        since: Option<f64>,
        replace_local: bool,
        expected_server_version: Option<f64>,
    ) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let res = engine
                .sync_internal(
                    since.map(|v| v as i64),
                    replace_local,
                    expected_server_version.map(|v| v as i64),
                )
                .await
                .map_err(js_str)?;
            to_js_string(&res)
        })
    }

    pub fn cloud_push_pending(&self) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let res = engine.push_pending_changes().await.map_err(js_str)?;
            to_js_string(&res)
        })
    }

    pub fn cloud_push_snapshot(&self) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let version = engine.push_snapshot().await.map_err(js_str)?;
            Ok(JsValue::from_f64(version as f64))
        })
    }

    pub fn cloud_replace_cloud_with_local(&self) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let res = engine.replace_cloud_with_local().await.map_err(js_str)?;
            to_js_string(&res)
        })
    }

    pub fn cloud_change_password(&self, current_password: String, new_password: String) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            engine
                .change_password(&current_password, &new_password)
                .await
                .map_err(js_str)?;
            Ok(JsValue::UNDEFINED)
        })
    }

    pub fn cloud_delete_account(&self, current_password: String) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            engine.delete_account(&current_password).await.map_err(js_str)?;
            Ok(JsValue::UNDEFINED)
        })
    }

    pub fn cloud_ensure_builtin_title_agent(&self) -> Result<(), JsValue> {
        engine_for(&self.store)
            .ensure_title_agent()
            .map_err(js_error)
    }

    pub fn cloud_clear_market_links_and_builtin_provider(&self) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            engine_for(&store)
                .clear_market_links_and_builtin_provider()
                .map_err(js_str)?;
            Ok(JsValue::UNDEFINED)
        })
    }

    pub fn cloud_preview_card(&self, code: String) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let preview = engine.preview_redeem_card(&code).await.map_err(js_str)?;
            to_js_string(&preview)
        })
    }

    pub fn cloud_redeem_card(&self, code: String) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let res = engine.redeem_card(&code).await.map_err(js_str)?;
            to_js_string(&res)
        })
    }

    pub fn cloud_sync_builtin_models(&self, force: bool) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let count = engine
                .sync_builtin_provider_models(force)
                .await
                .map_err(js_str)?;
            Ok(JsValue::from_f64(count as f64))
        })
    }

    pub fn cloud_list_market_agents(&self, query: String, cursor: Option<String>) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let res = engine
                .list_market_agents(&query, cursor.as_deref())
                .await
                .map_err(js_str)?;
            to_js_string(&res)
        })
    }

    pub fn cloud_get_market_agent(&self, id: String) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let res = engine.get_market_agent(&id).await.map_err(js_str)?;
            to_js_string(&res)
        })
    }

    pub fn cloud_publish_market_agent(&self, agent_id: String) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let res = engine.publish_market_agent(&agent_id).await.map_err(js_str)?;
            to_js_string(&res)
        })
    }

    pub fn cloud_import_market_agent(&self, market_id: String) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let agent = engine.import_market_agent(&market_id).await.map_err(js_str)?;
            to_js_string(&agent)
        })
    }

    pub fn cloud_import_market_agent_with_avatar(&self, market_id: String) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let agent = engine
                .import_market_agent_with_avatar(&market_id)
                .await
                .map_err(js_str)?;
            to_js_string(&agent)
        })
    }

    pub fn cloud_push_market_agent_update(&self, agent_id: String) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let res = engine
                .push_market_agent_update(&agent_id)
                .await
                .map_err(js_str)?;
            to_js_string(&res)
        })
    }

    pub fn cloud_remove_market_agent(&self, agent_id: String) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            engine.remove_market_agent(&agent_id).await.map_err(js_str)?;
            Ok(JsValue::UNDEFINED)
        })
    }

    pub fn cloud_check_market_agent_update(&self, agent_id: String) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let res = engine
                .check_market_agent_update(&agent_id)
                .await
                .map_err(js_str)?;
            to_js_string(&res)
        })
    }

    pub fn cloud_apply_market_agent_update(&self, agent_id: String, market_json: String) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let market: CloudMarketAgent = serde_json::from_str(&market_json).map_err(js_str)?;
            let engine = engine_for(&store);
            let agent = engine
                .apply_market_agent_update(&agent_id, &market)
                .await
                .map_err(js_str)?;
            to_js_string(&agent)
        })
    }

    /// Avatar caching on web returns a `data:` URI: the browser has no
    /// filesystem for the core to write into, and a data URI is a model Coil
    /// renders directly.
    pub fn cloud_cache_avatar(
        &self,
        scope: String,
        account_id: String,
        id: String,
        url: String,
        version: Option<String>,
        _dest_dir: String,
    ) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let cached = engine
                .cache_avatar(&scope, &account_id, &id, &url, version.as_deref())
                .await
                .map_err(js_str)?;
            Ok(JsValue::from_str(&cached))
        })
    }

    pub fn cloud_upload_user_avatar(&self, bytes: Vec<u8>, filename: String, mime: String) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let url = engine
                .upload_user_avatar(bytes, &filename, &mime)
                .await
                .map_err(js_str)?;
            Ok(JsValue::from_str(&url))
        })
    }

    pub fn cloud_delete_user_avatar(&self) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let url = engine.delete_user_avatar().await.map_err(js_str)?;
            Ok(JsValue::from_str(&url))
        })
    }

    pub fn cloud_upload_agent_avatar(
        &self,
        agent_id: String,
        bytes: Vec<u8>,
        filename: String,
        mime: String,
    ) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let url = engine
                .upload_agent_avatar(&agent_id, bytes, &filename, &mime)
                .await
                .map_err(js_str)?;
            Ok(JsValue::from_str(&url))
        })
    }

    pub fn cloud_delete_agent_avatar(&self, agent_id: String) -> Promise {
        let store = Rc::clone(&self.store);
        future_to_promise(async move {
            let engine = engine_for(&store);
            let url = engine.delete_agent_avatar(&agent_id).await.map_err(js_str)?;
            Ok(JsValue::from_str(&url))
        })
    }

    // -- turn loop --

    pub fn cancel_turn(&self) {
        self.cancel.borrow().cancel();
    }

    /// Runs one agent turn. Events stream to `event_sink`; tool calls go to
    /// `tool_host`, which returns `[output, isError]` synchronously.
    pub fn run_turn(&self, config_json: String, tool_host: Function, event_sink: Function) -> Promise {
        let config: TurnConfig = match serde_json::from_str(&config_json) {
            Ok(config) => config,
            Err(error) => {
                return future_to_promise(async move {
                    Err(JsValue::from_str(&format!("bad turn config: {error}")))
                })
            }
        };
        let store = Rc::clone(&self.store);
        let cancel = self.cancel_token();
        let host = JsToolHost {
            execute: Arc::new(SendFn(tool_host)),
        };
        let sink = JsEventSink {
            on_event: Arc::new(SendFn(event_sink)),
        };
        future_to_promise(async move {
            let request = build_turn_request(&config);
            run_chat_turn(&store, &host, &sink, &request, cancel)
                .await
                .map(|_| JsValue::UNDEFINED)
                .map_err(|e| JsValue::from_str(&e))
        })
    }
}

impl WasmCore {
    fn cancel_token(&self) -> tokio_util::sync::CancellationToken {
        let token = self.cancel.borrow().clone();
        if token.is_cancelled() {
            *self.cancel.borrow_mut() = tokio_util::sync::CancellationToken::new();
        }
        token
    }
}

fn engine_for(store: &Store) -> SyncEngine<'_> {
    let cookie = store.kv_get(KV_SESSION).ok().flatten();
    let host = store.kv_get(KV_SESSION_HOST).ok().flatten();
    SyncEngine::new(store, Session { cookie, host })
}

fn build_turn_request(config: &TurnConfig) -> TurnRequest {
    let registry = messenger_tools::builtin_registry();
    // `tool_names` carries the per-tool agent config; the writable mode
    // (write-tool exclusion + terminal description swap) is applied here so
    // the declared list matches what the platform host actually executes.
    //
    // A conversation outside any project has no workspace, so the
    // workspace-bound tools (terminal + glob/grep/read/edit/create) are
    // dropped from the declared list whatever the platform resolved. The
    // web client declares no built-in tools at all, so in practice this list
    // is already empty here — the gate keeps the boundary honest.
    let resolved = apply_writable_mode(
        registry
            .into_iter()
            .filter(|tool| config.tool_names.iter().any(|n| n == &tool.name))
            .filter(|tool| config.has_workspace || !tool.workspace_required)
            .collect(),
        config.writable,
    );
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
        workspace_note: config.workspace_note.clone(),
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

// ---------------------------------------------------------------------------
// Document engine
// ---------------------------------------------------------------------------

/// Incremental streaming session for the Document engine.
#[wasm_bindgen]
pub struct WasmDocument {
    session: std::sync::Mutex<messenger_markdown::StreamingSession>,
}

/// Creates a streaming session. A free function for the same reason as
/// `open_wasm_core`.
#[wasm_bindgen]
pub fn new_wasm_document() -> WasmDocument {
    WasmDocument {
        session: std::sync::Mutex::new(messenger_markdown::StreamingSession::new()),
    }
}

impl Default for WasmDocument {
    fn default() -> Self {
        Self {
            session: std::sync::Mutex::new(messenger_markdown::StreamingSession::new()),
        }
    }
}

#[wasm_bindgen]
impl WasmDocument {
    pub fn feed(&self, text: String) -> String {
        let mut session = self.session.lock().unwrap();
        session.feed(&text);
        let batch = session.drain_batch();
        serde_json::to_string(&batch).unwrap_or_else(|_| r#"{"diffs":[]}"#.into())
    }

    pub fn finish(&self) -> String {
        let mut session = self.session.lock().unwrap();
        let batch = session.finish();
        serde_json::to_string(&batch).unwrap_or_else(|_| r#"{"diffs":[]}"#.into())
    }

    pub fn get_document_json(&self) -> String {
        let session = self.session.lock().unwrap();
        serde_json::to_string(session.document().blocks()).unwrap_or_else(|_| "[]".into())
    }
}

/// Parses full markdown into a JSON Block array for static rendering.
#[wasm_bindgen]
pub fn parse_markdown_to_blocks_json(markdown: String) -> String {
    let mut session = messenger_markdown::StreamingSession::new();
    session.feed(&markdown);
    let _ = session.finish();
    serde_json::to_string(session.document().blocks()).unwrap_or_else(|_| "[]".into())
}

/// Syntax-highlights a code block (syntect), returning JSON byte spans.
#[wasm_bindgen]
pub fn highlight_code_json(code: String, language: String, dark: bool) -> String {
    messenger_highlight::highlight_code_json(&code, &language, dark)
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

fn js_error(error: impl std::fmt::Display) -> JsValue {
    JsValue::from_str(&error.to_string())
}

/// Errors crossing into JS are strings; `JsValue` does not implement
/// `From<E>` for arbitrary error types.
fn js_str(error: impl std::fmt::Display) -> JsValue {
    JsValue::from_str(&error.to_string())
}

fn json<T: serde::Serialize>(value: &T) -> Result<String, JsValue> {
    serde_json::to_string(value).map_err(js_error)
}

fn optional<T: serde::Serialize>(value: Option<T>) -> Result<Option<String>, JsValue> {
    match value {
        Some(inner) => Ok(Some(json(&inner)?)),
        None => Ok(None),
    }
}

fn parse<T: serde::de::DeserializeOwned>(text: &str) -> Result<T, JsValue> {
    serde_json::from_str(text).map_err(js_error)
}

fn to_js_string<T: serde::Serialize>(value: &T) -> Result<JsValue, JsValue> {
    json(value).map(|text| JsValue::from_str(&text))
}
