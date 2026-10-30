use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use messenger_store::model::{StoredAgent, StoredConversation, StoredProject};
use messenger_store::Store;
use messenger_sync::{KV_SERVER_URL, KV_SESSION, KV_SESSION_HOST};
use messenger_tools::ToolExecutionResult;
use tokio_util::sync::CancellationToken;
use wiremock::matchers::{body_partial_json, header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use crate::*;

#[derive(Default)]
struct RecordingExecutor {
    calls: Mutex<Vec<InboundToolCall>>,
}

#[async_trait]
impl LocalToolExecutor for RecordingExecutor {
    async fn execute(&self, call: &InboundToolCall, _cancel: CancellationToken) -> ToolExecutionResult {
        self.calls.lock().unwrap().push(call.clone());
        ToolExecutionResult { output: format!("ran {}", call.name), is_error: false }
    }
}

fn executor() -> (Arc<RecordingExecutor>, Arc<dyn LocalToolExecutor>) {
    let concrete = Arc::new(RecordingExecutor::default());
    (concrete.clone(), concrete)
}

fn signed_in_store(server: &MockServer) -> Arc<Store> {
    let store = Arc::new(Store::open_memory().unwrap());
    store.kv_set(KV_SESSION, "messenger_session=abc").unwrap();
    store.kv_set(KV_SESSION_HOST, "127.0.0.1").unwrap();
    store.kv_set(KV_SERVER_URL, &server.uri()).unwrap();
    store
}

fn project(id: &str, device: Option<&str>) -> StoredProject {
    StoredProject {
        id: id.into(),
        name: "P".into(),
        workspace: "/ws".into(),
        device_id: device.map(str::to_string),
        created_at: 1,
        updated_at: 1,
    }
}

fn presence(devices: &[&str]) -> String {
    serde_json::json!({ "devices": devices }).to_string()
}

fn call_json(hub: &RemoteHub, name: &str, project_id: &str, conversation_id: &str) -> String {
    serde_json::to_string(&ToolCall {
        call_id: "c1".into(),
        from_device_id: "initiator".into(),
        target_device_id: hub.device_id().into(),
        project_id: project_id.into(),
        conversation_id: conversation_id.into(),
        name: name.into(),
        arguments_json: "{}".into(),
    })
    .unwrap()
}

fn outgoing(target: &str) -> OutgoingToolCall {
    OutgoingToolCall {
        target_device_id: target.into(),
        call_id: "c1".into(),
        project_id: "proj".into(),
        conversation_id: "conv".into(),
        name: "grep".into(),
        arguments_json: "{\"pattern\":\"x\"}".into(),
    }
}

#[test]
fn ownership_classification_follows_presence() {
    let store = Arc::new(Store::open_memory().unwrap());
    let hub = Arc::new(RemoteHub::new(store).unwrap());
    let (_, exec) = executor();
    let local = hub.device_id().to_string();

    assert_eq!(hub.owner_of(&project("a", None)), ProjectOwner::Local);
    assert_eq!(hub.owner_of(&project("a", Some(&local))), ProjectOwner::Local);
    // Disconnected: a remote owner is offline even if it is in the set.
    hub.handle_frame("presence", &presence(&["other"]), &exec);
    assert_eq!(
        hub.owner_of(&project("a", Some("other"))),
        ProjectOwner::Remote { device_id: "other".into(), online: false }
    );
    assert!(!hub.owner_of(&project("a", Some("other"))).is_available());
}

#[tokio::test]
async fn a_call_to_an_offline_device_fails_without_touching_the_network() {
    let store = Arc::new(Store::open_memory().unwrap());
    let hub = RemoteHub::new(store).unwrap();
    let result = hub.call_tool(outgoing("ghost"), &CancellationToken::new()).await;
    assert!(result.is_error);
    assert!(result.output.contains("offline"));
}

#[tokio::test]
async fn a_relayed_call_resolves_with_the_owners_result() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/api/remote/tool-call"))
        .and(header("Cookie", "messenger_session=abc"))
        .and(body_partial_json(serde_json::json!({
            "callId": "c1", "targetDeviceId": "owner", "projectId": "proj", "name": "grep"
        })))
        .respond_with(ResponseTemplate::new(202))
        .expect(1)
        .mount(&server)
        .await;
    let hub = Arc::new(RemoteHub::new(signed_in_store(&server)).unwrap());
    let (_, exec) = executor();
    mark_connected(&hub, &["owner"], &exec);

    let waiting = {
        let hub = hub.clone();
        tokio::spawn(async move { hub.call_tool(outgoing("owner"), &CancellationToken::new()).await })
    };
    tokio::time::sleep(Duration::from_millis(100)).await;
    hub.handle_frame(
        "toolResult",
        r#"{"callId":"c1","targetDeviceId":"x","output":"3 matches","isError":false}"#,
        &exec,
    );
    let result = waiting.await.unwrap();
    assert_eq!(result, ToolExecutionResult { output: "3 matches".into(), is_error: false });
}

/// Test helper: pretend the relay connection is up.
fn mark_connected(hub: &Arc<RemoteHub>, devices: &[&str], exec: &Arc<dyn LocalToolExecutor>) {
    hub.force_presence_for_tests(devices);
    let _ = exec;
}

#[tokio::test]
async fn the_owner_going_offline_fails_the_waiting_call() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/api/remote/tool-call"))
        .respond_with(ResponseTemplate::new(202))
        .mount(&server)
        .await;
    let hub = Arc::new(RemoteHub::new(signed_in_store(&server)).unwrap());
    let (_, exec) = executor();
    mark_connected(&hub, &["owner"], &exec);
    let waiting = {
        let hub = hub.clone();
        tokio::spawn(async move { hub.call_tool(outgoing("owner"), &CancellationToken::new()).await })
    };
    tokio::time::sleep(Duration::from_millis(100)).await;
    hub.handle_frame("presence", &presence(&[]), &exec);
    let result = waiting.await.unwrap();
    assert!(result.is_error);
    assert!(result.output.contains("went offline"));
}

#[tokio::test]
async fn cancelling_a_waiting_call_tells_the_owner_to_stop() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/api/remote/tool-call"))
        .respond_with(ResponseTemplate::new(202))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/api/remote/tool-cancel"))
        .and(body_partial_json(serde_json::json!({"callId": "c1", "targetDeviceId": "owner"})))
        .respond_with(ResponseTemplate::new(202))
        .expect(1)
        .mount(&server)
        .await;
    let hub = Arc::new(RemoteHub::new(signed_in_store(&server)).unwrap());
    let (_, exec) = executor();
    mark_connected(&hub, &["owner"], &exec);
    let cancel = CancellationToken::new();
    let waiting = {
        let hub = hub.clone();
        let cancel = cancel.clone();
        tokio::spawn(async move { hub.call_tool(outgoing("owner"), &cancel).await })
    };
    tokio::time::sleep(Duration::from_millis(100)).await;
    cancel.cancel();
    let result = waiting.await.unwrap();
    assert!(result.is_error);
    assert!(result.output.contains("cancelled"));
}

#[tokio::test]
async fn relay_409_means_the_device_is_offline() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/api/remote/tool-call"))
        .respond_with(ResponseTemplate::new(409))
        .mount(&server)
        .await;
    let hub = Arc::new(RemoteHub::new(signed_in_store(&server)).unwrap());
    let (_, exec) = executor();
    mark_connected(&hub, &["owner"], &exec);
    let result = hub.call_tool(outgoing("owner"), &CancellationToken::new()).await;
    assert!(result.is_error);
    assert!(result.output.contains("offline"));
}

fn default_agent() -> StoredAgent {
    StoredAgent {
        id: "a".into(),
        name: "Default".into(),
        avatar: None,
        system_prompt: String::new(),
        description: String::new(),
        default_model_id: None,
        temperature: None,
        top_p: None,
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
        tools_enabled: true,
        tools_follow_default: false,
        tools_config: String::new(),
        created_at: 1,
        updated_at: 1,
    }
}

fn seed_owned_project(store: &Store, hub: &RemoteHub, writable: bool) {
    store.upsert_agent(&default_agent()).unwrap();
    store.upsert_project(&project("proj", Some(hub.device_id()))).unwrap();
    store
        .upsert_conversation(&StoredConversation {
            id: "conv".into(),
            agent_id: "a".into(),
            project_id: Some("proj".into()),
            writable,
            ..Default::default()
        })
        .unwrap();
}

async fn deliver_inbound(hub: &Arc<RemoteHub>, exec: &Arc<dyn LocalToolExecutor>, name: &str) {
    hub.handle_frame("toolCall", &call_json(hub, name, "proj", "conv"), exec);
    tokio::time::sleep(Duration::from_millis(150)).await;
}

#[tokio::test]
async fn an_owned_workspace_call_runs_locally_and_the_result_is_sent_back() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/api/remote/tool-result"))
        .and(body_partial_json(serde_json::json!({
            "callId": "c1", "targetDeviceId": "initiator", "output": "ran grep", "isError": false
        })))
        .respond_with(ResponseTemplate::new(202))
        .expect(1)
        .mount(&server)
        .await;
    let store = signed_in_store(&server);
    let hub = Arc::new(RemoteHub::new(store.clone()).unwrap());
    seed_owned_project(&store, &hub, false);
    let (recorded, exec) = executor();

    deliver_inbound(&hub, &exec, "grep").await;

    let calls = recorded.calls.lock().unwrap();
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].workspace, "/ws");
    assert_eq!(calls[0].project_id, "proj");
}

async fn assert_rejected(hub: &Arc<RemoteHub>, exec: &Arc<dyn LocalToolExecutor>, recorded: &RecordingExecutor, name: &str) {
    deliver_inbound(hub, exec, name).await;
    assert!(recorded.calls.lock().unwrap().is_empty(), "{name} must not reach the executor");
}

fn rejection_server_body() -> serde_json::Value {
    serde_json::json!({"isError": true})
}

async fn rejecting_server() -> MockServer {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/api/remote/tool-result"))
        .and(body_partial_json(rejection_server_body()))
        .respond_with(ResponseTemplate::new(202))
        .expect(1)
        .mount(&server)
        .await;
    server
}

#[tokio::test]
async fn a_write_tool_needs_a_writable_conversation() {
    let server = rejecting_server().await;
    let store = signed_in_store(&server);
    let hub = Arc::new(RemoteHub::new(store.clone()).unwrap());
    seed_owned_project(&store, &hub, false);
    let (recorded, exec) = executor();
    assert_rejected(&hub, &exec, &recorded, "edit").await;
}

#[tokio::test]
async fn a_write_tool_runs_once_the_conversation_is_writable() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/api/remote/tool-result"))
        .respond_with(ResponseTemplate::new(202))
        .mount(&server)
        .await;
    let store = signed_in_store(&server);
    let hub = Arc::new(RemoteHub::new(store.clone()).unwrap());
    seed_owned_project(&store, &hub, true);
    let (recorded, exec) = executor();
    deliver_inbound(&hub, &exec, "edit").await;
    assert_eq!(recorded.calls.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn a_non_workspace_tool_is_rejected() {
    let server = rejecting_server().await;
    let store = signed_in_store(&server);
    let hub = Arc::new(RemoteHub::new(store.clone()).unwrap());
    seed_owned_project(&store, &hub, true);
    let (recorded, exec) = executor();
    assert_rejected(&hub, &exec, &recorded, "delete_everything").await;
}

#[tokio::test]
async fn a_project_owned_by_another_device_is_rejected() {
    let server = rejecting_server().await;
    let store = signed_in_store(&server);
    let hub = Arc::new(RemoteHub::new(store.clone()).unwrap());
    seed_owned_project(&store, &hub, true);
    store.upsert_project(&project("proj", Some("someone-else"))).unwrap();
    let (recorded, exec) = executor();
    assert_rejected(&hub, &exec, &recorded, "grep").await;
}

#[tokio::test]
async fn a_call_for_another_device_is_rejected() {
    let server = rejecting_server().await;
    let store = signed_in_store(&server);
    let hub = Arc::new(RemoteHub::new(store.clone()).unwrap());
    seed_owned_project(&store, &hub, true);
    let (recorded, exec) = executor();
    let misaddressed = call_json(&hub, "grep", "proj", "conv").replace(hub.device_id(), "not-me");
    hub.handle_frame("toolCall", &misaddressed, &exec);
    tokio::time::sleep(Duration::from_millis(150)).await;
    assert!(recorded.calls.lock().unwrap().is_empty());
}

#[tokio::test]
async fn the_connection_loop_reads_presence_and_dispatches_calls() {
    let server = MockServer::start().await;
    let store = signed_in_store(&server);
    let hub = Arc::new(RemoteHub::new(store.clone()).unwrap().with_timing(RelayTiming {
        reconnect_min: Duration::from_millis(20),
        reconnect_max: Duration::from_millis(40),
        idle_poll: Duration::from_millis(20),
        call_timeout: Duration::from_secs(5),
    }));
    seed_owned_project(&store, &hub, false);
    let stream = format!(
        ": hello\n\nevent: presence\ndata: {}\n\nevent: toolCall\ndata: {}\n\n",
        presence(&["initiator", hub.device_id()]),
        call_json(&hub, "read", "proj", "conv"),
    );
    Mock::given(method("GET"))
        .and(path("/api/remote/events"))
        .and(header("Cookie", "messenger_session=abc"))
        .and(wiremock::matchers::query_param("deviceId", hub.device_id()))
        .respond_with(ResponseTemplate::new(200).insert_header("content-type", "text/event-stream").set_body_string(stream))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/api/remote/tool-result"))
        .respond_with(ResponseTemplate::new(202))
        .mount(&server)
        .await;
    let (recorded, exec) = executor();
    let shutdown = CancellationToken::new();
    let task = tokio::spawn(hub.clone().run(exec, shutdown.clone()));

    let deadline = tokio::time::Instant::now() + Duration::from_secs(3);
    while recorded.calls.lock().unwrap().is_empty() && tokio::time::Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    shutdown.cancel();
    task.await.unwrap();

    assert!(!recorded.calls.lock().unwrap().is_empty(), "the streamed tool call must be executed");
    // The stream closed and the loop stopped: nothing stays "online".
    assert_eq!(hub.status().connected, false);
    assert!(hub.status().online_devices.is_empty());
}

#[tokio::test]
async fn a_401_on_the_stream_signs_the_device_out_locally() {
    let server = MockServer::start().await;
    let store = signed_in_store(&server);
    let hub = Arc::new(RemoteHub::new(store.clone()).unwrap().with_timing(RelayTiming {
        idle_poll: Duration::from_millis(20),
        ..RelayTiming::default()
    }));
    Mock::given(method("GET"))
        .and(path("/api/remote/events"))
        .respond_with(ResponseTemplate::new(401))
        .mount(&server)
        .await;
    let (_, exec) = executor();
    let shutdown = CancellationToken::new();
    let task = tokio::spawn(hub.clone().run(exec, shutdown.clone()));
    tokio::time::sleep(Duration::from_millis(200)).await;
    shutdown.cancel();
    task.await.unwrap();
    assert_eq!(store.kv_get(KV_SESSION).unwrap(), None);
}
