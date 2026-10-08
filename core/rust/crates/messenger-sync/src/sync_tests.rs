//! Sync-engine integration tests over wiremock: the pull cycle with all
//! delta guards, the push cycle with pending queues, and the cursor
//! sanity checks.

use std::sync::{Arc, Mutex};

use messenger_store::model::{StoredAgent, StoredConversation, StoredMessage};
use messenger_store::Store;

use crate::api::Session;
use crate::models::*;
use crate::models::{BUILTIN_PROVIDER_ID, BUILTIN_TITLE_AGENT_ID};
use crate::sync::SyncEngine;

fn signed_in_engine<'a>(store: &'a Store, _server: &wiremock::MockServer) -> SyncEngine<'a> {
    let engine = SyncEngine::new(store, Session::default());
    engine.save_session(&Session {
        cookie: Some("messenger_session=abc".into()),
        host: Some("127.0.0.1".into()),
    }).unwrap();
    engine.save_user(&CloudUser {
        id: "acct-1".into(),
        email: "a@b.c".into(),
        sync_version: 5,
        ai_api_key: Some("sk-cloud".into()),
        ..Default::default()
    }).unwrap();
    // Point the server URL at the mock.
    store.kv_set("cloud_server_url", _server.uri().as_str()).unwrap();
    engine
}

fn delta_body() -> serde_json::Value {
    serde_json::json!({
        "agents": [
            // Normal agent.
            {"_id": "a1", "name": "Cloud Agent", "systemPrompt": "sp", "description": "d",
             "temperature": 0.5, "topP": 0.9, "isDefault": true, "role": "chat",
             "createdAt": 1, "updatedAt": 2, "version": 10},
            // New title holder — the previous local title holder must demote.
            {"_id": "a2", "name": "Remote Title", "systemPrompt": "sp2", "role": "title",
             "createdAt": 3, "updatedAt": 4, "version": 11}
        ],
        "conversations": [
            // Conversation whose agent exists (a1) → replace-all messages.
            {"_id": "c1", "agentId": "a1", "title": "t", "providerId": "p1",
             "projectId": "proj1",
             "writable": true,
             "messages": [
                 {"id": "m1", "role": "user", "content": "hi", "timestamp": 1, "status": "sent"},
                 {"id": "m2", "role": "assistant", "content": "yo", "timestamp": 2, "status": "sent"}
             ],
             "createdAt": 1, "updatedAt": 5, "version": 12},
            // Conversation whose agent is missing locally → dropped entirely.
            {"_id": "c2", "agentId": "ghost", "title": "orphan",
             "messages": [{"id": "m3", "role": "user", "content": "x", "timestamp": 1, "status": "sent"}],
             "createdAt": 1, "updatedAt": 6, "version": 13}
        ],
        "providers": [
            {"_id": "p1", "name": "Cloud Provider", "baseUrl": "https://api", "apiKey": "k",
             "models": [{"id": "p1:m", "modelId": "m", "displayName": "m", "isEnabled": true}],
             "createdAt": 1, "updatedAt": 2, "version": 14},
            // Builtin provider never syncs — a remote copy is ignored.
            {"_id": "builtin-messenger-cloud-ai", "name": "x", "baseUrl": "u", "apiKey": "k",
             "models": [], "createdAt": 1, "updatedAt": 2, "version": 15}
        ],
        "projects": [
            {"_id": "proj1", "name": "Messenger", "workspace": "/w/messenger",
             "createdAt": 1, "updatedAt": 2, "version": 16}
        ],
        "latestVersion": 20
    })
}

fn page_response(documents: serde_json::Value, latest: i64) -> wiremock::ResponseTemplate {
    wiremock::ResponseTemplate::new(200).set_body_json(serde_json::json!({
        "documents": documents,
        "hasMore": false,
        "latestVersion": latest
    }))
}

fn seed_title_holder(store: &Store) {
    store.upsert_agent(&StoredAgent {
        id: "old-title".into(),
        name: "Old Title".into(),
        avatar: None,
        system_prompt: "p".into(),
        description: String::new(),
        default_model_id: None,
        temperature: None,
        top_p: None,
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
        role: "title".into(),
        tools_enabled: false,
        tools_follow_default: false,
        tools_config: String::new(),
        created_at: 1,
        updated_at: 1,
    }).unwrap();
    // A second default agent that must demote when the cloud default lands.
    store.upsert_agent(&StoredAgent {
        id: "local-default".into(),
        name: "Local Default".into(),
        is_default: true,
        ..seed_agent_base("local-default")
    }).unwrap();
}

fn seed_agent_base(id: &str) -> StoredAgent {
    StoredAgent {
        id: id.into(),
        name: id.into(),
        avatar: None,
        system_prompt: String::new(),
        description: String::new(),
        default_model_id: None,
        temperature: None,
        top_p: None,
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
        role: "chat".into(),
        tools_enabled: false,
        tools_follow_default: false,
        tools_config: String::new(),
        created_at: 1,
        updated_at: 1,
    }
}

#[tokio::test]
async fn pull_applies_delta_with_guards() {
    let server = wiremock::MockServer::start().await;
    wiremock::Mock::given(wiremock::matchers::method("GET"))
        .and(wiremock::matchers::path("/api/sync"))
        .and(wiremock::matchers::query_param("collection", "agents"))
        .respond_with(page_response(delta_body()["agents"].clone(), 20))
        .mount(&server)
        .await;
    wiremock::Mock::given(wiremock::matchers::method("GET"))
        .and(wiremock::matchers::path("/api/sync"))
        .and(wiremock::matchers::query_param("collection", "conversations"))
        .respond_with(page_response(delta_body()["conversations"].clone(), 20))
        .mount(&server)
        .await;
    wiremock::Mock::given(wiremock::matchers::method("GET"))
        .and(wiremock::matchers::path("/api/sync"))
        .and(wiremock::matchers::query_param("collection", "providers"))
        .respond_with(page_response(delta_body()["providers"].clone(), 20))
        .mount(&server)
        .await;
    wiremock::Mock::given(wiremock::matchers::method("GET"))
        .and(wiremock::matchers::path("/api/sync"))
        .and(wiremock::matchers::query_param("collection", "projects"))
        .respond_with(page_response(delta_body()["projects"].clone(), 20))
        .mount(&server)
        .await;

    let store = Store::open_memory().unwrap();
    seed_title_holder(&store);
    let engine = signed_in_engine(&store, &server);

    let result = engine.sync_internal(None, false, None).await.unwrap();
    assert_eq!(result.latest_version, 20);

    // Agents applied; roles stay single-holder.
    let a1 = store.get_agent("a1").unwrap().unwrap();
    assert!(a1.is_default);
    let local_default = store.get_agent("local-default").unwrap().unwrap();
    assert!(!local_default.is_default, "previous default must demote");
    let a2 = store.get_agent("a2").unwrap().unwrap();
    assert_eq!(a2.role, "title");
    let old_title = store.get_agent("old-title").unwrap().unwrap();
    assert_eq!(old_title.role, "chat", "previous title holder must demote");

    // Conversation replace-all + orphan dropped.
    assert!(store.get_conversation("c1").unwrap().is_some());
    assert_eq!(store.list_messages_by_conversation("c1").unwrap().len(), 2);
    assert!(store.get_conversation("c2").unwrap().is_none());

    // The pulled project landed and the conversation kept its membership —
    // applying projects before conversations is what makes the FK hold.
    let project = store.get_project("proj1").unwrap().unwrap();
    assert_eq!(project.workspace, "/w/messenger");
    assert_eq!(
        store.get_conversation("c1").unwrap().unwrap().project_id.as_deref(),
        Some("proj1")
    );
    assert_eq!(store.list_conversations_by_project("proj1").unwrap().len(), 1);

    // Provider models reinserted; builtin provider NOT created from the delta.
    assert!(store.get_provider("p1").unwrap().is_some());
    assert_eq!(store.list_models_by_provider("p1").unwrap().len(), 1);

    // Builtin seeding: provider from the signed-in user's AI key, title agent
    // NOT re-seeded (a2 holds the role now).
    assert!(store.get_provider(BUILTIN_PROVIDER_ID).unwrap().is_some());
    assert!(store.get_agent(BUILTIN_TITLE_AGENT_ID).unwrap().is_none());

    // Cursor advanced.
    assert_eq!(store.sync_cursor("acct-1").unwrap(), 20);
}

#[tokio::test]
async fn pull_rejects_cursor_backwards() {
    let server = wiremock::MockServer::start().await;
    wiremock::Mock::given(wiremock::matchers::method("GET"))
        .and(wiremock::matchers::path("/api/sync"))
        .respond_with(page_response(serde_json::json!([]), 3))
        .mount(&server)
        .await;
    let store = Store::open_memory().unwrap();
    let engine = signed_in_engine(&store, &server);
    store.set_sync_meta("acct-1", 10, "[]", "[]").unwrap();
    let err = engine.sync_internal(None, false, None).await.unwrap_err();
    assert!(err.to_string().contains("cursor moved backwards"), "{err}");
}

#[tokio::test]
async fn push_sends_pending_upserts_and_deletes_then_pulls_since_pushed() {
    let server = wiremock::MockServer::start().await;
    let pushes: Arc<Mutex<Vec<(String, String)>>> = Arc::new(Mutex::new(Vec::new())); // (method path, body)

    // PUT agents
    let sink = Arc::clone(&pushes);
    wiremock::Mock::given(wiremock::matchers::method("PUT"))
        .and(wiremock::matchers::path_regex(r"^/api/agents/.+$"))
        .respond_with(move |_req: &wiremock::Request| {
            let body = String::from_utf8_lossy(&_req.body).to_string();
            sink.lock().unwrap().push(("PUT agent".into(), body));
            wiremock::ResponseTemplate::new(200).set_body_json(serde_json::json!({"id": "a9", "version": 30}))
        })
        .mount(&server)
        .await;
    // DELETE providers
    let sink = Arc::clone(&pushes);
    wiremock::Mock::given(wiremock::matchers::method("DELETE"))
        .and(wiremock::matchers::path("/api/providers/p9"))
        .respond_with(move |_req: &wiremock::Request| {
            sink.lock().unwrap().push(("DELETE provider".into(), String::new()));
            wiremock::ResponseTemplate::new(200).set_body_json(serde_json::json!({"id": "p9", "version": 31}))
        })
        .mount(&server)
        .await;
    // Post-push pull: every collection returns empty with latest 31.
    for collection in ["agents", "conversations", "providers", "projects"] {
        wiremock::Mock::given(wiremock::matchers::method("GET"))
            .and(wiremock::matchers::path("/api/sync"))
            .and(wiremock::matchers::query_param("collection", collection))
            .and(wiremock::matchers::query_param("since", "31"))
            .respond_with(page_response(serde_json::json!([]), 31))
            .mount(&server)
            .await;
    }

    let store = Store::open_memory().unwrap();
    let engine = signed_in_engine(&store, &server);

    // Seed: one dirty agent, one pending delete.
    let mut agent = seed_agent_base("a9");
    agent.name = "Pushed".into();
    agent.description = "always upstream".into();
    store.upsert_agent(&agent).unwrap();
    engine.request_local_change("agent", "a9", false).unwrap();
    engine.request_local_change("provider", "p9", true).unwrap();

    let result = engine.push_pending_changes().await.unwrap();
    assert_eq!(result.latest_version, 31);

    let log = pushes.lock().unwrap();
    let agent_push = log.iter().find(|(kind, _)| kind == "PUT agent").unwrap();
    let body: serde_json::Value = serde_json::from_str(&agent_push.1).unwrap();
    assert_eq!(body["id"], "a9");
    assert_eq!(body["description"], "always upstream");
    assert_eq!(body["role"], "chat");
    assert!(log.iter().any(|(kind, _)| kind == "DELETE provider"));

    // Pending queues drained.
    assert_eq!(
        store.sync_meta_field("acct-1", "pendingUpserts").unwrap(),
        "[]"
    );
    assert_eq!(
        store.sync_meta_field("acct-1", "pendingDeletes").unwrap(),
        "[]"
    );
    assert_eq!(store.sync_cursor("acct-1").unwrap(), 31);
}

#[tokio::test]
async fn push_excludes_builtin_rows() {
    let server = wiremock::MockServer::start().await;
    let saw_builtin = Arc::new(Mutex::new(false));
    let sink = Arc::clone(&saw_builtin);
    wiremock::Mock::given(wiremock::matchers::method("PUT"))
        .respond_with(move |req: &wiremock::Request| {
            let body = String::from_utf8_lossy(&req.body).to_string();
            if body.contains(BUILTIN_PROVIDER_ID) || body.contains(BUILTIN_TITLE_AGENT_ID) {
                *sink.lock().unwrap() = true;
            }
            wiremock::ResponseTemplate::new(200).set_body_json(serde_json::json!({"version": 1}))
        })
        .mount(&server)
        .await;
    for collection in ["agents", "conversations", "providers"] {
        wiremock::Mock::given(wiremock::matchers::method("GET"))
            .and(wiremock::matchers::path("/api/sync"))
            .and(wiremock::matchers::query_param("collection", collection))
            .respond_with(page_response(serde_json::json!([]), 1))
            .mount(&server)
            .await;
    }

    let store = Store::open_memory().unwrap();
    let engine = signed_in_engine(&store, &server);

    // Seed builtin rows directly (full push — not pending-only — touches all).
    engine.ensure_builtin_provider(&CloudUser {
        id: "acct-1".into(),
        ai_api_key: Some("sk".into()),
        ..Default::default()
    }).unwrap();
    engine.ensure_builtin_title_agent().unwrap();
    // A non-builtin conversation must push too (exercises message embedding).
    store.upsert_agent(&seed_agent_base("a1")).unwrap();
    store.upsert_conversation(&StoredConversation {
        id: "c1".into(),
        title: "t".into(),
        provider_id: String::new(),
        agent_id: "a1".into(),
        created_at: 1,
        updated_at: 1,
        ..Default::default()
    }).unwrap();
    store.upsert_message(&StoredMessage {
        id: "m1".into(),
        conversation_id: "c1".into(),
        role: "user".into(),
        content: "hi".into(),
        parts_json: None,
        timestamp: 1,
        status: "sent".into(),
        error_message: None,
    }).unwrap();

    engine.push_local_snapshot(false).await.unwrap();
    assert!(!*saw_builtin.lock().unwrap(), "builtin rows must never push");
}

#[tokio::test]
async fn replace_local_restores_from_cloud() {
    let server = wiremock::MockServer::start().await;
    wiremock::Mock::given(wiremock::matchers::method("GET"))
        .and(wiremock::matchers::path("/api/sync"))
        .and(wiremock::matchers::query_param("collection", "agents"))
        .respond_with(page_response(delta_body()["agents"].clone(), 40))
        .mount(&server)
        .await;
    for collection in ["conversations", "providers", "projects"] {
        wiremock::Mock::given(wiremock::matchers::method("GET"))
            .and(wiremock::matchers::path("/api/sync"))
            .and(wiremock::matchers::query_param("collection", collection))
            .respond_with(page_response(serde_json::json!([]), 40))
            .mount(&server)
            .await;
    }

    let store = Store::open_memory().unwrap();
    // Pre-existing local rows that must vanish.
    store.upsert_agent(&seed_agent_base("local-only")).unwrap();
    let engine = signed_in_engine(&store, &server);

    engine.sync_internal(None, true, None).await.unwrap();
    assert!(store.get_agent("local-only").unwrap().is_none());
    assert!(store.get_agent("a1").unwrap().is_some());
}

#[tokio::test]
async fn login_captures_and_persists_the_session_cookie() {
    let server = wiremock::MockServer::start().await;
    wiremock::Mock::given(wiremock::matchers::method("POST"))
        .and(wiremock::matchers::path("/api/auth/login"))
        .respond_with(
            wiremock::ResponseTemplate::new(200)
                .append_header(
                    "Set-Cookie",
                    "messenger_session=abc123; Path=/; HttpOnly; SameSite=Lax",
                )
                .set_body_json(serde_json::json!({
                    "user": {"id": "acct-1", "email": "a@b.c", "syncVersion": 5,
                             "aiApiKey": "sk-cloud"}
                })),
        )
        .mount(&server)
        .await;

    let store = Store::open_memory().unwrap();
    let engine = SyncEngine::new(&store, Session::default());
    store.kv_set("cloud_server_url", server.uri().as_str()).unwrap();

    let user = engine.login("a@b.c", "pw").await.unwrap();
    assert_eq!(user.email, "a@b.c");

    // The session must be readable from the store: the TUI has no cookie jar,
    // so without this write every later request is unauthenticated.
    assert_eq!(
        store.kv_get(crate::sync::KV_SESSION).unwrap().as_deref(),
        Some("messenger_session=abc123")
    );
    assert_eq!(
        store.kv_get(crate::sync::KV_SESSION_HOST).unwrap().as_deref(),
        Some(server.uri().as_str())
    );
    assert!(store.kv_get(crate::sync::KV_USER).unwrap().is_some());
}

#[tokio::test]
async fn login_without_a_set_cookie_header_keeps_the_previous_session() {
    let server = wiremock::MockServer::start().await;
    wiremock::Mock::given(wiremock::matchers::method("POST"))
        .and(wiremock::matchers::path("/api/auth/login"))
        .respond_with(wiremock::ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "user": {"id": "acct-2", "email": "c@d.e", "syncVersion": 6}
        })))
        .mount(&server)
        .await;

    let store = Store::open_memory().unwrap();
    let engine = SyncEngine::new(&store, Session::default());
    engine
        .save_session(&Session {
            cookie: Some("messenger_session=keepme".into()),
            host: Some(server.uri()),
        })
        .unwrap();
    store.kv_set("cloud_server_url", server.uri().as_str()).unwrap();

    engine.login("c@d.e", "pw").await.unwrap();
    assert_eq!(
        store.kv_get(crate::sync::KV_SESSION).unwrap().as_deref(),
        Some("messenger_session=keepme")
    );
}

#[tokio::test]
async fn failed_login_surfaces_the_server_error_message() {
    let server = wiremock::MockServer::start().await;
    wiremock::Mock::given(wiremock::matchers::method("POST"))
        .and(wiremock::matchers::path("/api/auth/login"))
        .respond_with(
            wiremock::ResponseTemplate::new(401)
                .set_body_json(serde_json::json!({"error": {"message": "Invalid credentials"}})),
        )
        .mount(&server)
        .await;

    let store = Store::open_memory().unwrap();
    let engine = SyncEngine::new(&store, Session::default());
    store.kv_set("cloud_server_url", server.uri().as_str()).unwrap();

    let error = engine.login("a@b.c", "nope").await.unwrap_err();
    let text = error.to_string();
    assert!(text.contains("Invalid credentials"), "{text}");
    assert!(store.kv_get(crate::sync::KV_SESSION).unwrap().is_none());
}
