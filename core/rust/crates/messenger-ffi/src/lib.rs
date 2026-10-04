//! Messenger Rust Core — UniFFI boundary.
//!
//! `core` exposes the complete store, sync, and agent loop surface;
//! `lib.rs` keeps the M0 echo/ping demo used by the walking-skeleton probe.

pub mod core;
pub use core::*;

use std::time::Duration;

uniffi::setup_scaffolding!();

/// Liveness probe for the bindings loader.
#[uniffi::export]
pub fn core_ping() -> String {
    "pong".to_string()
}

/// Version of the Rust core, reported to the app shell for diagnostics.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Events the Agent Runtime emits.
#[derive(uniffi::Enum)]
pub enum AgentEvent {
    TextDelta { round: u32, text: String },
    Finished,
}

/// Foreign sink for [`AgentEvent`]s.
#[uniffi::export(callback_interface)]
pub trait AgentEventSink: Send + Sync {
    fn on_event(&self, event: AgentEvent);
}

/// M0 demo: emit `count` synthetic deltas 30 ms apart, then `Finished`.
#[uniffi::export]
pub fn echo_events(count: u32, sink: Box<dyn AgentEventSink>) {
    for i in 0..count {
        sink.on_event(AgentEvent::TextDelta {
            round: 0,
            text: format!("token-{i} "),
        });
        std::thread::sleep(Duration::from_millis(30));
    }
    sink.on_event(AgentEvent::Finished);
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    #[test]
    fn ping_round_trips() {
        assert_eq!(core_ping(), "pong");
    }

    #[test]
    fn echo_emits_expected_events() {
        let events: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
        echo_events(3, Box::new(RecordingSink(Arc::clone(&events))));
        let recorded = events.lock().unwrap().clone();
        assert_eq!(recorded.len(), 4);
        assert_eq!(recorded.last().unwrap(), "finished");
    }

    #[tokio::test]
    async fn core_handle_store_facade_round_trips() {
        let temp = tempfile::tempdir().unwrap();
        let db_path = temp.path().join("test.db");
        let core = CoreHandle::open(db_path.to_string_lossy().to_string(), None, 1).unwrap();

        // 1. Providers
        let provider_json = serde_json::json!({
            "id": "p1",
            "name": "Provider 1",
            "base_url": "https://api.openai.com",
            "api_key": "sk-123",
            "created_at": 10,
            "updated_at": 20
        }).to_string();
        core.upsert_provider_json(provider_json).unwrap();
        let got_p = core.get_provider_json("p1".into()).unwrap().unwrap();
        assert!(got_p.contains("Provider 1"));
        let all_p = core.list_providers_json().unwrap();
        assert!(all_p.contains("Provider 1"));

        // 2. Models
        let model_json = serde_json::json!({
            "id": "m1",
            "provider_id": "p1",
            "model_id": "gpt-4",
            "display_name": "GPT-4",
            "is_enabled": true,
            "context_window": 8192,
            "input_rate": null,
            "output_rate": null,
            "input_modalities": "text",
            "output_modalities": "text",
            "supports_tool_calling": true,
            "supports_thinking": false,
            "supports_json_output": true,
            "supports_temperature": true,
            "created_at": 10
        }).to_string();
        core.upsert_model_json(model_json).unwrap();
        let p_models = core.list_models_by_provider_json("p1".into()).unwrap();
        assert!(p_models.contains("GPT-4"));
        core.set_model_enabled("m1".into(), false).unwrap();
        let got_m = core.get_model_json("m1".into()).unwrap().unwrap();
        assert!(got_m.contains("\"is_enabled\":false"));

        // 3. Agents
        let agent_json = serde_json::json!({
            "id": "a1",
            "name": "Default Agent",
            "avatar": null,
            "system_prompt": "You are helpful",
            "description": "Default helper",
            "default_model_id": "m1",
            "temperature": 0.7,
            "top_p": 1.0,
            "max_tokens": null,
            "reasoning_effort": null,
            "is_default": true,
            "follow_default_system_prompt": false,
            "follow_default_model": false,
            "follow_default_temperature": false,
            "follow_default_top_p": false,
            "follow_default_max_tokens": false,
            "follow_default_reasoning_effort": false,
            "market_agent_id": null,
            "market_agent_version": null,
            "market_agent_role": null,
            "role": "chat",
            "tools_enabled": true,
            "tools_follow_default": false,
            "tools_config": "",
            "created_at": 10,
            "updated_at": 20
        }).to_string();
        core.upsert_agent_json(agent_json).unwrap();
        let def_a = core.get_default_agent_json().unwrap().unwrap();
        assert!(def_a.contains("Default Agent"));

        // 4. Conversations & Messages
        let conv_json = serde_json::json!({
            "id": "c1",
            "title": "Chat 1",
            "provider_id": "p1",
            "agent_id": "a1",
            "override_model_id": null,
            "override_temperature": null,
            "override_top_p": null,
            "override_max_tokens": null,
            "override_reasoning_effort": null,
            "override_tools_enabled": null,
            "override_tools_config": null,
            "writable": false,
            "created_at": 10,
            "updated_at": 20,
            "last_message": "hi",
            "reasoning_format": null,
            "context_summary": null,
            "context_summary_until": 0,
            "context_tokens": 0,
            "context_tokens_at": 0
        }).to_string();
        core.upsert_conversation_json(conv_json).unwrap();
        let convs = core.list_conversations_json().unwrap();
        assert!(convs.contains("Chat 1"));

        let msg_json = serde_json::json!({
            "id": "msg1",
            "conversation_id": "c1",
            "role": "user",
            "content": "hi",
            "parts_json": null,
            "timestamp": 15,
            "status": "sent",
            "error_message": null
        }).to_string();
        core.upsert_message_json(msg_json).unwrap();
        let msgs = core.list_messages_by_conversation_json("c1".into()).unwrap();
        assert!(msgs.contains("msg1"));

        // 5. KV
        core.kv_set("foo".into(), "bar".into()).unwrap();
        assert_eq!(core.kv_get("foo".into()).unwrap(), Some("bar".into()));
        core.kv_delete("foo".into()).unwrap();
        assert_eq!(core.kv_get("foo".into()).unwrap(), None);

        // 6. Cloud config
        core.cloud_set_server_url("https://example.com/api/".into()).unwrap();
        assert_eq!(core.cloud_get_server_url(), "https://example.com/api");
    }

    struct RecordingSink(Arc<Mutex<Vec<String>>>);

    impl AgentEventSink for RecordingSink {
        fn on_event(&self, event: AgentEvent) {
            let label = match event {
                AgentEvent::TextDelta { text, .. } => text,
                AgentEvent::Finished => "finished".to_string(),
            };
            self.0.lock().unwrap().push(label);
        }
    }
}
