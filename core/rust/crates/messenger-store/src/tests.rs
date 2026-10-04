//! Store CRUD, notification, and legacy-import tests. The import test builds
//! a real Room-v20-shaped SQLite file (all five tables + WAL-style rollback
//! is out of scope here) and copies it through `import_legacy`.

use std::collections::VecDeque;
use std::sync::Mutex;

use tempfile::TempDir;

use crate::import::{import_legacy, ImportSummary, IMPORT_MARKER, LEGACY_DB_FILE};
use crate::model::*;
use crate::store::{EntityKind, Store};

fn sample_provider(id: &str) -> StoredProvider {
    StoredProvider {
        id: id.to_string(),
        name: "OpenRouter".into(),
        base_url: "https://openrouter.ai/api/v1".into(),
        api_key: "sk-test".into(),
        created_at: 1_000,
        updated_at: 1_000,
    }
}

fn sample_agent(id: &str, is_default: bool) -> StoredAgent {
    StoredAgent {
        id: id.into(),
        name: "默认 Agent".into(),
        avatar: None,
        system_prompt: "be helpful".into(),
        description: "the default".into(),
        default_model_id: None,
        temperature: Some(0.7),
        top_p: Some(0.9),
        max_tokens: None,
        reasoning_effort: None,
        is_default,
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
        tools_enabled: true,
        tools_follow_default: false,
        tools_config: "".to_string(),
        created_at: 1_000,
        updated_at: 1_000,
    }
}

#[test]
fn crud_round_trips_all_five_entities() {
    let store = Store::open_memory().unwrap();

    store.upsert_provider(&sample_provider("p1")).unwrap();
    let mut model = StoredModel {
        id: "p1:gpt-x".into(),
        provider_id: "p1".into(),
        model_id: "gpt-x".into(),
        display_name: "gpt-x".into(),
        is_enabled: true,
        context_window: 128_000,
        input_rate: Some(0.5),
        output_rate: None,
        input_modalities: "text,image".into(),
        output_modalities: "text".into(),
        supports_tool_calling: true,
        supports_thinking: false,
        supports_json_output: false,
        supports_temperature: true,
        created_at: 1_000,
    };
    store.upsert_model(&model).unwrap();
    store.upsert_agent(&sample_agent("a1", true)).unwrap();

    let conversation = StoredConversation {
        id: "c1".into(),
        title: "新对话".into(),
        provider_id: "p1".into(),
        agent_id: "a1".into(),
        override_model_id: Some("p1:gpt-x".into()),
        override_temperature: Some(0.3),
        override_top_p: None,
        override_max_tokens: None,
        override_reasoning_effort: Some("high".into()),
        override_tools_enabled: Some(true),
        override_tools_config: Some(r#"{"terminal":false}"#.into()),
        writable: true,
        created_at: 1_100,
        updated_at: 1_200,
        last_message: Some("hello".into()),
        reasoning_format: Some("reasoning_content".into()),
        context_summary: None,
        context_summary_until: 0,
        context_tokens: 432,
        context_tokens_at: 1_250,
    };
    store.upsert_conversation(&conversation).unwrap();

    let message = StoredMessage {
        id: "m1".into(),
        conversation_id: "c1".into(),
        role: "user".into(),
        content: "hello".into(),
        parts_json: Some(r#"[{"type":"text","text":"hello"}]"#.into()),
        timestamp: 1_150,
        status: "sent".into(),
        error_message: None,
    };
    store.upsert_message(&message).unwrap();

    assert_eq!(store.list_providers().unwrap().len(), 1);
    assert_eq!(store.list_models_by_provider("p1").unwrap().len(), 1);
    assert_eq!(store.get_default_agent().unwrap().unwrap().id, "a1");
    let loaded = store.get_conversation("c1").unwrap().unwrap();
    assert_eq!(loaded.override_tools_config.as_deref(), Some(r#"{"terminal":false}"#));
    assert!(loaded.writable);
    assert_eq!(loaded.context_tokens, 432);
    assert_eq!(store.list_messages_by_conversation("c1").unwrap()[0].id, "m1");

    // Updates round-trip.
    model.is_enabled = false;
    store.upsert_model(&model).unwrap();
    assert!(!store.get_model("p1:gpt-x").unwrap().unwrap().is_enabled);
    store.set_model_enabled("p1:gpt-x", true).unwrap();
    assert!(store.get_model("p1:gpt-x").unwrap().unwrap().is_enabled);

    store
        .update_conversation_last_message("c1", Some("updated"), 1_300)
        .unwrap();
    assert_eq!(
        store.get_conversation("c1").unwrap().unwrap().last_message.as_deref(),
        Some("updated")
    );

    assert_eq!(store.max_message_timestamp("c1").unwrap(), Some(1_150));

    // Deletes cascade: removing the provider removes its model.
    store.delete_provider("p1").unwrap();
    assert!(store.get_model("p1:gpt-x").unwrap().is_none());
}

#[test]
fn change_events_fire_with_ids() {
    let store = Store::open_memory().unwrap();
    let events = std::sync::Arc::new(Mutex::new(VecDeque::<(EntityKind, Vec<String>)>::new()));
    {
        let sink = std::sync::Arc::clone(&events);
        store.subscribe(Box::new(move |event| {
            sink.lock().unwrap().push_back((event.kind.clone(), event.ids.clone()));
        }));
    }
    store.upsert_provider(&sample_provider("p1")).unwrap();
    store.upsert_agent(&sample_agent("a1", true)).unwrap();
    store.delete_agent("a1").unwrap();

    let drained: Vec<_> = events.lock().unwrap().drain(..).collect();
    assert_eq!(
        drained,
        vec![
            (EntityKind::Provider, vec!["p1".to_string()]),
            (EntityKind::Agent, vec!["a1".to_string()]),
            (EntityKind::Agent, vec!["a1".to_string()]),
        ]
    );
}

#[test]
fn kv_and_sync_meta_round_trip() {
    let store = Store::open_memory().unwrap();
    assert_eq!(store.kv_get("missing").unwrap(), None);
    store.kv_set("k", "v1").unwrap();
    store.kv_set("k", "v2").unwrap();
    assert_eq!(store.kv_get("k").unwrap().as_deref(), Some("v2"));
    store.kv_delete("k").unwrap();
    assert_eq!(store.kv_get("k").unwrap(), None);

    assert_eq!(store.sync_cursor("acct").unwrap(), 0);
    store
        .set_sync_meta("acct", 42, r#"["agent:a1"]"#, r#"["provider:p9"]"#)
        .unwrap();
    assert_eq!(store.sync_cursor("acct").unwrap(), 42);
}

#[test]
fn legacy_import_copies_room_rows_and_is_idempotent() {
    let dir = TempDir::new().unwrap();
    let legacy_dir = dir.path().join("databases");
    std::fs::create_dir_all(&legacy_dir).unwrap();

    // Build a legacy Room-v20-shaped database.
    let legacy_path = legacy_dir.join(LEGACY_DB_FILE);
    let conn = rusqlite::Connection::open(&legacy_path).unwrap();
    for ddl in crate::schema::CREATE_TABLES {
        conn.execute_batch(ddl).unwrap();
    }
    conn.execute(
        "INSERT INTO providers (id, name, baseUrl, apiKey, createdAt, updatedAt) VALUES ('p1','n','u','k',1,1)",
        [],
    )
    .unwrap();
    conn.execute(
        "INSERT INTO agents (id, name, systemPrompt, description, role, toolsConfig, createdAt, updatedAt)
         VALUES ('a1','agent','sp','desc','chat','',1,1)",
        [],
    )
    .unwrap();
    conn.execute(
        "INSERT INTO conversations (id, title, providerId, agentId, writable, createdAt, updatedAt)
         VALUES ('c1','t','p1','a1',0,1,1)",
        [],
    )
    .unwrap();
    conn.execute(
        "INSERT INTO messages (id, conversationId, role, content, partsJson, timestamp, status)
         VALUES ('m1','c1','user','hi',NULL,1,'sent')",
        [],
    )
    .unwrap();
    drop(conn);

    let store = Store::open_memory().unwrap();
    let summary = import_legacy(&store, &legacy_dir, 9_999).unwrap().unwrap();
    assert_eq!(
        summary,
        ImportSummary { providers: 1, models: 0, agents: 1, conversations: 1, messages: 1 }
    );

    // Second call is a no-op (marker present).
    assert!(import_legacy(&store, &legacy_dir, 10_000).unwrap().is_none());
    assert_eq!(store.list_providers().unwrap().len(), 1);

    // Marker recorded.
    assert!(store.kv_get(IMPORT_MARKER).unwrap().is_some());
}

#[test]
fn legacy_import_without_db_records_marker() {
    let dir = TempDir::new().unwrap();
    let store = Store::open_memory().unwrap();
    let summary = import_legacy(&store, dir.path(), 1).unwrap();
    assert!(summary.is_none());
    assert!(store.kv_get(IMPORT_MARKER).unwrap().is_some());
}
