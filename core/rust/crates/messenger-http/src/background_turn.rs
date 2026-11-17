//! Core-owned native turns. Subscribers may come and go without owning execution.
use super::*;
use std::collections::VecDeque;
use sha2::{Digest, Sha256};
use tokio_stream::wrappers::ReceiverStream;

// At most 64 retained turns, each with a 256 KiB event log, text snapshot,
// and pending approval. Recent idempotency entries hold only fixed-size digests.
// This bounds retained payloads to 48 MiB plus metadata/allocator overhead.
const MAX_REPLAY_BYTES: usize = 256 * 1024;
const MAX_SNAPSHOT_BYTES: usize = 256 * 1024;
const MAX_APPROVAL_BYTES: usize = 256 * 1024;
const MAX_RETAINED_TURNS: usize = 64;

struct History {
    events: VecDeque<String>,
    bytes: usize,
    base: u64,
    done: bool,
    message_id: Option<String>,
    text: String,
    text_truncated: bool,
    pending_approval: Option<String>,
    changed: broadcast::Sender<()>,
}

#[derive(Default)]
pub(super) struct BackgroundRegistry {
    current: HashMap<String, Arc<BackgroundTurn>>,
    recent: HashMap<(String, String), [u8; 32]>,
}

pub(super) struct BackgroundTurn { key: String, fingerprint: [u8; 32], history: Mutex<History> }
impl BackgroundTurn {
    fn new(key: String, fingerprint: [u8; 32]) -> Self {
        let (changed, _) = broadcast::channel(32);
        Self { key, fingerprint, history: Mutex::new(History { events: VecDeque::new(), bytes: 0, base: 0, done: false, message_id: None, text: String::new(), text_truncated: false, pending_approval: None, changed }) }
    }
    async fn push(&self, data: String) {
        let mut h = self.history.lock().await;
        if let Ok(event) = serde_json::from_str::<Value>(&data) {
            match event["type"].as_str() {
                Some("StreamingStarted") => {
                    h.message_id = event["messageId"].as_str().map(str::to_owned);
                    h.text.clear();
                    h.text_truncated = false;
                }
                Some("RoundPersisted") => { h.text.clear(); h.text_truncated = false; }
                Some("TextDelta") => if let Some(delta) = event["text"].as_str() {
                    h.text.push_str(delta);
                    if h.text.len() > MAX_SNAPSHOT_BYTES {
                        let mut cut = h.text.len() - MAX_SNAPSHOT_BYTES;
                        while !h.text.is_char_boundary(cut) { cut += 1; }
                        // Allocate a bounded tail: drain() alone retains a potentially
                        // huge capacity after a single oversized provider chunk.
                        h.text = h.text[cut..].to_owned();
                        h.text_truncated = true;
                    }
                },
                Some("ToolApprovalRequested") if data.len() <= MAX_APPROVAL_BYTES => h.pending_approval = Some(data.clone()),
                Some("ToolApprovalResolved") => {
                    let resolved = event["callId"].as_str();
                    if h.pending_approval.as_ref().and_then(|s| serde_json::from_str::<Value>(s).ok())
                        .and_then(|v| v["callId"].as_str().map(str::to_owned)).as_deref() == resolved {
                        h.pending_approval = None;
                    }
                }
                _ => {}
            }
        }
        h.bytes += data.len();
        h.events.push_back(data);
        while h.bytes > MAX_REPLAY_BYTES {
            if let Some(old) = h.events.pop_front() { h.bytes -= old.len(); h.base += 1; }
        }
        let _ = h.changed.send(());
    }
    async fn finish(&self) {
        let mut h = self.history.lock().await;
        h.done = true;
        let _ = h.changed.send(());
    }
}

pub(super) fn routes() -> Router<ApiState> {
    Router::new()
        .route("/conversations/:id/background-turn", get(status).post(start))
        .route("/conversations/:id/background-turn/events", get(events))
}

async fn start(State(state): State<ApiState>, Path(id): Path<String>, headers: axum::http::HeaderMap, Json(body): Json<Value>) -> Result<StatusCode, ApiError> {
    let key = headers.get("Idempotency-Key").and_then(|v| v.to_str().ok())
        .filter(|v| !v.is_empty() && v.len() <= 128 && v.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_'))
        .ok_or_else(|| ApiError::BadRequest("a valid Idempotency-Key is required".into()))?.to_owned();
    let fingerprint: [u8; 32] = Sha256::digest(serde_json::to_vec(&body).map_err(|_| ApiError::BadRequest("invalid request".into()))?).into();
    let request: ChatRequest = serde_json::from_value(body).map_err(|_| ApiError::BadRequest("invalid chat request".into()))?;
    // A replay of an already-admitted request must not depend on the current
    // model or tool catalog state.
    {
        let turns = state.background_turns.lock().await;
        let previous = turns.recent.get(&(id.clone(), key.clone())).copied()
            .or_else(|| turns.current.get(&id).filter(|turn| turn.key == key).map(|turn| turn.fingerprint));
        if let Some(previous) = previous {
            if previous == fingerprint { return Ok(StatusCode::ACCEPTED); }
            return Err(ApiError::Conflict { code: "idempotency_conflict".into(), message: "Idempotency-Key was already used with a different request".into() });
        }
    }
    // Catalog publication may take time; never block subscribers of unrelated turns
    // while waiting. Preserve the model-required admission check before the wait.
    if !request.disable_tools && !state.platform_tools_ready.is_cancelled() {
        let conversation = state.store.get_conversation(&id).map_err(ApiError::store)?.ok_or_else(|| ApiError::not_found("conversation"))?;
        let agent = state.store.get_agent(&conversation.agent_id).map_err(ApiError::store)?.ok_or_else(|| ApiError::not_found("agent"))?;
        let agent = effective_agent(&state.store, agent, &conversation)?;
        if conversation.override_model_id.is_none() && agent.default_model_id.is_none() {
            return Err(ApiError::Conflict { code: "model_required".into(), message: "set a model for this agent before sending a message".into() });
        }
        tokio::time::timeout(std::time::Duration::from_secs(20), wait_for_platform_catalog(&state, &state.shutdown.child_token(), true))
            .await.map_err(|_| ApiError::Conflict { code: "platform_catalog_unavailable".into(), message: "platform tools did not become ready".into() })??;
    }
    let mut turns = state.background_turns.lock().await;
    if let Some(old) = turns.recent.get(&(id.clone(), key.clone())) {
        if *old == fingerprint { return Ok(StatusCode::ACCEPTED); }
        return Err(ApiError::Conflict { code: "idempotency_conflict".into(), message: "Idempotency-Key was already used with a different request".into() });
    }
    if let Some(old) = turns.current.get(&id) {
        if old.key == key {
            if old.fingerprint == fingerprint { return Ok(StatusCode::ACCEPTED); }
            return Err(ApiError::Conflict { code: "idempotency_conflict".into(), message: "Idempotency-Key was already used with a different request".into() });
        }
        if !old.history.lock().await.done {
            return Err(ApiError::Conflict { code: "turn_in_progress".into(), message: "a chat turn is already active for this conversation".into() });
        }
    }
    if turns.current.len() >= MAX_RETAINED_TURNS && !turns.current.contains_key(&id) {
        let mut removable = None;
        for (key, turn) in turns.current.iter() {
            if turn.history.lock().await.done { removable = Some(key.clone()); break; }
        }
        if let Some(key) = removable { turns.current.remove(&key); }
        else { return Err(ApiError::Conflict { code: "too_many_turns".into(), message: "too many background turns".into() }); }
    }
    let cancel = state.shutdown.child_token();
    let mut rx = start_turn_inner(state.clone(), id.clone(), request, cancel, true, true).await?;
    let turn = Arc::new(BackgroundTurn::new(key, fingerprint));
    if let Some(old) = turns.current.insert(id.clone(), turn.clone()) {
        if turns.recent.len() >= MAX_RETAINED_TURNS {
            if let Some(key) = turns.recent.keys().next().cloned() { turns.recent.remove(&key); }
        }
        turns.recent.insert((id.clone(), old.key.clone()), old.fingerprint);
    }
    drop(turns);
    let event_sender = state.events.clone();
    tokio::spawn(async move {
        while let Some(mut data) = rx.recv().await {
            if data.len() > MAX_APPROVAL_BYTES {
                if let Ok(mut value) = serde_json::from_str::<Value>(&data) {
                    match value["type"].as_str() {
                        Some("ToolApprovalRequested") => {
                            // An incomplete approval prompt cannot safely authorize a tool.
                            if let Some(id) = value["callId"].as_str() {
                                if let Some(sender) = state.pending_approvals.lock().await.remove(id) { let _ = sender.send(false); }
                            }
                            continue;
                        }
                        Some("Error") => {
                            if let Some(message) = value["message"].as_str() {
                                let mut end = message.len().min(MAX_REPLAY_BYTES / 8);
                                while !message.is_char_boundary(end) { end -= 1; }
                                value["message"] = Value::String(format!("{} [truncated]", &message[..end]));
                                data = value.to_string();
                            }
                        }
                        _ => {}
                    }
                }
            }
            let status = serde_json::from_str::<Value>(&data).ok().and_then(|value| match value["type"].as_str() {
                Some("ToolApprovalRequested") => Some("approval"),
                Some("ToolApprovalResolved") => Some("approvalResolved"),
                Some("Finished") => Some("completed"),
                Some("Error") => Some("failed"),
                Some("Cancelled") => Some("cancelled"),
                _ => None,
            });
            if let Some(status) = status {
                let _ = event_sender.send(CoreEvent::Change(ChangeEvent { kind: "taskStatus".into(), ids: vec![status.into(), "chat".into(), id.clone()] }));
            }
            turn.push(data).await;
        }
        turn.finish().await;
    });
    Ok(StatusCode::ACCEPTED)
}

async fn find(state: &ApiState, id: &str) -> Result<Arc<BackgroundTurn>, ApiError> {
    state.background_turns.lock().await.current.get(id).cloned().ok_or_else(|| ApiError::not_found("background turn"))
}

#[cfg(test)]
pub(super) async fn insert_test_turn_with_approval(state: &ApiState, id: &str, call_id: &str) -> oneshot::Receiver<bool> {
    let (sender, receiver) = oneshot::channel();
    state.pending_approvals.lock().await.insert(call_id.into(), sender);
    let turn = Arc::new(BackgroundTurn::new("test".into(), [0; 32]));
    turn.push(serde_json::json!({"type":"TextDelta", "text":"working"}).to_string()).await;
    turn.push(serde_json::json!({"type":"ToolApprovalRequested", "callId":call_id,
        "name":"terminal", "argumentsJson":"{\"command\":\"ls\"}"}).to_string()).await;
    state.background_turns.lock().await.current.insert(id.into(), turn);
    receiver
}

/// A bounded snapshot for an authenticated, explicitly enabled remote controller.
pub(super) async fn remote_snapshot(state: &ApiState, id: &str) -> Value {
    let Ok(turn) = find(state, id).await else { return serde_json::json!({"active":false,"events":[]}) };
    let h = turn.history.lock().await;
    let mut events = Vec::new();
    if !h.done && !h.text.is_empty() {
        events.push(serde_json::json!({"type":"TextDelta","text":control::short(&h.text,8_000)}));
    }
    if let Some(pending) = h.pending_approval.as_ref().filter(|_| !h.done) {
        if let Ok(value) = serde_json::from_str::<Value>(pending) {
            if let Some(call_id) = value["callId"].as_str() {
                if state.pending_approvals.lock().await.contains_key(call_id) {
                    events.push(serde_json::json!({"type":"Permission","options":[{"id":format!("approve:{call_id}"),"name":"Approve"},{"id":format!("deny:{call_id}"),"name":"Deny"}],"tool":{
                        "title":control::short(value["name"].as_str().unwrap_or_default(),180),
                        "detail":control::short(value["argumentsJson"].as_str().unwrap_or_default(),1500)
                    }}));
                }
            }
        }
    }
    serde_json::json!({"active":!h.done,"requestId":turn.key,"events":events})
}

pub(super) async fn remote_cancel(state: &ApiState, id: &str, request_id: &str) -> Result<(), ApiError> {
    let turns = state.background_turns.lock().await;
    let turn = turns.current.get(id).ok_or_else(|| ApiError::not_found("background turn"))?;
    if turn.key != request_id || turn.history.lock().await.done {
        return Err(ApiError::Conflict { code: "turn_changed".into(), message: "The active task has changed".into() });
    }
    let active = state.active_turns.lock().await;
    let cancel = active.get(id).ok_or_else(|| ApiError::not_found("active turn"))?;
    cancel.cancel();
    Ok(())
}

pub(super) async fn remote_decide(state: &ApiState, id: &str, request_id: &str, option: &str) -> Result<(), ApiError> {
    let turn = find(state, id).await?;
    if turn.key != request_id { return Err(ApiError::Conflict { code: "turn_changed".into(), message: "The active task has changed".into() }); }
    let call_id = turn.history.lock().await.pending_approval.as_ref()
        .and_then(|raw| serde_json::from_str::<Value>(raw).ok())
        .and_then(|v| v["callId"].as_str().map(str::to_owned))
        .ok_or_else(|| ApiError::not_found("pending approval"))?;
    let (decision, requested_id) = option.split_once(':').ok_or_else(|| ApiError::BadRequest("invalid approval option".into()))?;
    if requested_id != call_id { return Err(ApiError::Conflict { code: "approval_changed".into(), message: "The pending approval has changed".into() }); }
    let approved = match decision { "approve" => true, "deny" => false, _ => return Err(ApiError::BadRequest("invalid approval option".into())) };
    approval::resolve(State(state.clone()), Path(call_id), Json(approval::Decision { approved })).await?;
    Ok(())
}

async fn status(State(state): State<ApiState>, Path(id): Path<String>) -> Result<Json<Value>, ApiError> {
    let turn = find(&state, &id).await?;
    let active = !turn.history.lock().await.done;
    Ok(Json(serde_json::json!({"active": active, "requestId": turn.key})))
}

async fn events(State(state): State<ApiState>, Path(id): Path<String>) -> Result<Sse<impl Stream<Item = Result<Event, Infallible>>>, ApiError> {
    let turn = find(&state, &id).await?;
    let (tx, rx) = mpsc::channel::<String>(64);
    let shutdown = state.shutdown.clone();
    let stream_shutdown = shutdown.clone();
    tokio::spawn(async move {
        let mut cursor = 0u64;
        let mut replay_complete = false;
        loop {
            let (batch, base, done, snapshot, pending, mut changed) = {
                let h = turn.history.lock().await;
                let changed = h.changed.subscribe();
                let offset = cursor.saturating_sub(h.base) as usize;
                (h.events.iter().skip(offset).cloned().collect::<Vec<_>>(), h.base, h.done, serde_json::json!({"type":"ReplaySnapshot", "messageId":h.message_id, "content":h.text, "truncated":h.text_truncated}).to_string(), h.pending_approval.clone(), changed)
            };
            let gap = cursor < base;
            if gap {
                if tx.send(r#"{"type":"ReplayGap"}"#.into()).await.is_err() { return; }
                cursor = base;
                if tx.send(snapshot).await.is_err() { return; }
            }
            let mut replayed_approval = false;
            for data in batch {
                cursor += 1;
                if let Ok(value) = serde_json::from_str::<Value>(&data) {
                    if gap && matches!(value["type"].as_str(), Some("StreamingStarted" | "TextDelta" | "RoundPersisted")) { continue; }
                    if value["type"] == "ToolApprovalRequested" {
                        if let Some(id) = value["callId"].as_str() {
                            if !state.pending_approvals.lock().await.contains_key(id) { continue; }
                            replayed_approval = true;
                        }
                    }
                }
                if tx.send(data).await.is_err() { return; }
            }
            if gap && !replayed_approval {
                if let Some(pending) = pending {
                    let id = serde_json::from_str::<Value>(&pending).ok()
                        .and_then(|v| v["callId"].as_str().map(str::to_owned));
                    if let Some(id) = id {
                        let still_pending = state.pending_approvals.lock().await.contains_key(&id);
                        if still_pending && tx.send(pending).await.is_err() { return; }
                    }
                }
            }
            if !replay_complete {
                if tx.send(r#"{"type":"ReplayComplete"}"#.into()).await.is_err() { return; }
                replay_complete = true;
            }
            if done { return; }
            tokio::select! {
                _ = shutdown.cancelled() => return,
                _ = tx.closed() => return,
                _ = changed.recv() => {},
            }
        }
    });
    let stream = ReceiverStream::new(rx).map(|data| Ok(Event::default().event("message").data(data)));
    Ok(Sse::new(ShutdownStream::new(stream, &stream_shutdown)).keep_alive(KeepAlive::default()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn pending_approval_survives_history_eviction() {
        use axum::body::Body;
        use http_body_util::BodyExt;
        use tower::ServiceExt;
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "test-token");
        let (sender, _receiver) = oneshot::channel();
        state.pending_approvals.lock().await.insert("approval".into(), sender);
        let turn = Arc::new(BackgroundTurn::new("key".into(), [0; 32]));
        turn.push(r#"{"type":"ToolApprovalRequested","callId":"approval","name":"terminal","argumentsJson":"{}"}"#.into()).await;
        turn.push(serde_json::json!({"type":"TextDelta","text":"x".repeat(MAX_REPLAY_BYTES + 1)}).to_string()).await;
        turn.finish().await;
        state.background_turns.lock().await.current.insert("c".into(), turn);
        let request = Request::builder().uri("/api/v1/conversations/c/background-turn/events")
            .header("authorization", "Bearer test-token").body(Body::empty()).unwrap();
        let response = router(state).oneshot(request).await.unwrap();
        let text = String::from_utf8(response.into_body().collect().await.unwrap().to_bytes().to_vec()).unwrap();
        assert!(text.contains("ReplayGap"));
        assert!(text.contains("ToolApprovalRequested"));
    }

    #[tokio::test]
    async fn replay_buffer_tracks_streaming_snapshot_after_eviction() {
        let turn = BackgroundTurn::new("key".into(), [0; 32]);
        turn.push(r#"{"type":"StreamingStarted","messageId":"m"}"#.into()).await;
        let text = "x".repeat(MAX_REPLAY_BYTES + 10);
        turn.push(serde_json::json!({"type":"TextDelta","text":text}).to_string()).await;
        let h = turn.history.lock().await;
        assert!(h.base > 0);
        assert_eq!(h.message_id.as_deref(), Some("m"));
        assert_eq!(h.text.len(), MAX_SNAPSHOT_BYTES);
        assert!(h.text.capacity() <= MAX_SNAPSHOT_BYTES * 2);
        assert!(h.text_truncated);
        assert!(h.bytes <= MAX_REPLAY_BYTES);
    }
}
