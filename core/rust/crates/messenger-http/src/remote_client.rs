//! Local authenticated control client for an opted-in device on the same account.
use super::*;
use messenger_remote::SseParser;
use serde_json::json;
use std::time::Duration;

pub(super) struct ClientLane {
    signals: tokio::sync::mpsc::Sender<Option<String>>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    target: String,
    payload: Value,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Signal {
    option_id: Option<String>,
}

pub(super) fn routes() -> Router<ApiState> {
    Router::new()
        .route("/remote/client/commands/:id", post(command))
        .route("/remote/client/commands/:id/signal", post(signal))
}

fn credentials(state: &ApiState) -> Result<(String, String), ApiError> {
    let host = state.store.kv_get(KV_SESSION_HOST).map_err(ApiError::store)?
        .filter(|v| !v.is_empty()).ok_or_else(|| ApiError::cloud(CloudError::SessionExpired { message: "Sign in to Messenger Cloud".into() }))?;
    let cookie = state.store.kv_get(KV_SESSION).map_err(ApiError::store)?
        .filter(|v| !v.is_empty()).ok_or_else(|| ApiError::cloud(CloudError::SessionExpired { message: "Sign in to Messenger Cloud".into() }))?;
    if sync_engine(&state.store)?.server_url().trim_end_matches('/') != host.trim_end_matches('/') {
        return Err(ApiError::cloud(CloudError::SessionExpired { message: "Cloud session belongs to a different server".into() }));
    }
    Ok((host.trim_end_matches('/').to_owned(), cookie))
}

fn clear_if_current(state: &ApiState, host: &str, cookie: &str) {
    if credentials(state).is_ok_and(|(h, c)| h == host && c == cookie) {
        let _ = sync_engine(&state.store).and_then(|engine| engine.clear_session().map_err(ApiError::store));
    }
}

fn error(message: &str) -> String {
    let code = if message.starts_with("Remote relay HTTP 401") { "session_expired" } else { "remote_relay_error" };
    json!({"type":"Error", "code":code, "message":message}).to_string()
}

async fn post_relay(client: &reqwest::Client, host: &str, cookie: &str, body: Value, state: &ApiState) -> Result<(), String> {
    let response = client.post(format!("{host}/api/remote/control")).header("Cookie", cookie)
        .timeout(Duration::from_secs(15)).json(&body).send().await.map_err(|e| e.to_string())?;
    let status = response.status();
    if status.is_success() { return Ok(()); }
    if status.as_u16() == 401 { clear_if_current(state, host, cookie); }
    let body = response.text().await.unwrap_or_default();
    Err(format!("Remote relay HTTP {status}: {}", body.chars().take(2048).collect::<String>()))
}

async fn command(State(state): State<ApiState>, Path(id): Path<Uuid>, Json(request): Json<Request>)
    -> Result<Sse<impl Stream<Item = Result<Event, Infallible>>>, ApiError> {
    if request.target.is_empty() || request.target.len() > 64 || !request.target.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-') {
        return Err(ApiError::BadRequest("invalid target device".into()));
    }
    let action = request.payload.get("action").and_then(Value::as_str).unwrap_or("");
    if !matches!(action, "history" | "prompt" | "notificationOpen" | "notificationDecision" | "notificationCancel") || request.payload.to_string().len() > 240_000 {
        return Err(ApiError::BadRequest("invalid remote control command".into()));
    }
    let (host, cookie) = credentials(&state)?;
    let client = reqwest::Client::builder().redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(10)).build().map_err(ApiError::internal)?;
    let (signals, mut signal_rx) = tokio::sync::mpsc::channel::<Option<String>>(8);
    {
        let mut lanes = state.remote_clients.lock().await;
        if lanes.contains_key(&id) { return Err(ApiError::Conflict { code: "duplicate_request".into(), message: "remote request already exists".into() }); }
        if lanes.len() >= 32 { return Err(ApiError::BadRequest("too many remote requests".into())); }
        lanes.insert(id, ClientLane { signals });
    }
    // Backpressure prevents a fast remote host from accumulating unbounded
    // replies if the local observer is slow or suspended.
    let (tx, rx) = tokio::sync::mpsc::channel::<String>(8);
    let cancel = state.shutdown.child_token();
    let task_cancel = cancel.clone();
    let task_state = state.clone();
    tokio::spawn(async move {
        let work = async {
            let device = format!("client-{}", Uuid::new_v4());
            let response = tokio::time::timeout(Duration::from_secs(15), client.get(format!("{host}/api/remote/events"))
                .query(&[("deviceId", device.as_str()), ("control", "client"), ("controlOnly", "1")])
                .header("Cookie", &cookie).header("Accept", "text/event-stream").send()).await
                .map_err(|_| "Remote relay connection timed out".to_owned())?
                .map_err(|e| e.to_string())?;
            if !response.status().is_success() {
                if response.status().as_u16() == 401 { clear_if_current(&task_state, &host, &cookie); }
                return Err(format!("Remote relay HTTP {}", response.status()));
            }
            let mut stream = response.bytes_stream();
            let mut parser = SseParser::default();
            let mut frame_bytes = 0usize;
            let connection = tokio::time::timeout(Duration::from_secs(15), async {
                loop {
                    let chunk = stream.next().await.ok_or_else(|| "Remote relay disconnected".to_owned())?.map_err(|e| e.to_string())?;
                    frame_bytes += chunk.len();
                    if frame_bytes > 512 * 1024 { return Err("Remote relay frame too large".to_owned()); }
                    let frames = parser.feed(&chunk);
                    if !frames.is_empty() { frame_bytes = 0; }
                    for (event, data) in frames {
                        if event == "controlReady" {
                            return serde_json::from_str::<Value>(&data).ok().and_then(|v| v["connectionId"].as_str().map(str::to_owned))
                                .ok_or_else(|| "Invalid remote relay handshake".to_owned());
                        }
                    }
                }
            }).await.map_err(|_| "Remote relay handshake timed out".to_owned())??;
            post_relay(&client, &host, &cookie, json!({"connectionId":connection,"kind":"request","id":id,"target":request.target,"payload":request.payload}), &task_state).await?;
            let mut session_check = tokio::time::interval(Duration::from_secs(2));
            loop {
                tokio::select! {
                    _ = task_cancel.cancelled() => return Ok(()),
                    _ = tx.closed() => return Ok(()),
                    _ = session_check.tick() => if !credentials(&task_state).is_ok_and(|(h,c)| h == host && c == cookie) { return Err("Cloud session changed".into()); },
                    signal = signal_rx.recv() => {
                        let Some(option) = signal else { return Ok(()); };
                        let body = match option { Some(value) => json!({"connectionId":connection,"kind":"signal","id":id,"optionId":value}), None => json!({"connectionId":connection,"kind":"signal","id":id}) };
                        post_relay(&client, &host, &cookie, body, &task_state).await?;
                    }
                    chunk = stream.next() => {
                        let chunk = chunk.ok_or_else(|| "Remote relay disconnected".to_owned())?.map_err(|e| e.to_string())?;
                        frame_bytes += chunk.len();
                        if frame_bytes > 512 * 1024 { return Err("Remote relay frame too large".into()); }
                        let frames = parser.feed(&chunk);
                        if !frames.is_empty() { frame_bytes = 0; }
                        for (event, data) in frames {
                            if event != "controlReply" { continue; }
                            let frame: Value = serde_json::from_str(&data).map_err(|_| "Invalid remote reply".to_owned())?;
                            if frame["id"].as_str() != Some(&id.to_string()) { continue; }
                            let payload = frame.get("payload").cloned().ok_or_else(|| "Invalid remote reply".to_owned())?;
                            let done = frame["done"].as_bool().unwrap_or(false);
                            if tx.send(payload.to_string()).await.is_err() { return Ok(()); }
                            if done { return Ok(()); }
                        }
                    }
                }
            }
        };
        tokio::select! {
            _ = task_cancel.cancelled() => {},
            _ = tx.closed() => {},
            result = work => if let Err(message) = result {
                tokio::select! { _ = task_cancel.cancelled() => {}, _ = tx.closed() => {}, _ = tx.send(error(&message)) => {} }
            },
        }
        task_state.remote_clients.lock().await.remove(&id);
    });
    let guard = cancel.drop_guard();
    let stream = tokio_stream::wrappers::ReceiverStream::new(rx).map(move |data| {
        let _guard = &guard;
        Ok(Event::default().data(data))
    });
    Ok(Sse::new(stream).keep_alive(KeepAlive::default()))
}

async fn signal(State(state): State<ApiState>, Path(id): Path<Uuid>, Json(body): Json<Signal>) -> Result<StatusCode, ApiError> {
    if body.option_id.as_ref().is_some_and(|id| id.is_empty() || id.len() > 256) { return Err(ApiError::BadRequest("invalid permission option".into())); }
    let lanes = state.remote_clients.lock().await;
    lanes.get(&id).ok_or_else(|| ApiError::not_found("remote command"))?.signals.try_send(body.option_id)
        .map_err(|_| ApiError::Conflict { code: "remote_closed".into(), message: "remote command is no longer active".into() })?;
    Ok(StatusCode::ACCEPTED)
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use http_body_util::BodyExt;
    use std::sync::Arc;
    use tower::ServiceExt;

    #[derive(Clone, Default)]
    struct MockRelay {
        sender: Arc<Mutex<Option<tokio::sync::mpsc::UnboundedSender<String>>>>,
        requests: Arc<Mutex<Vec<Value>>>,
        cookies: Arc<Mutex<Vec<String>>>,
        queries: Arc<Mutex<Vec<HashMap<String, String>>>>,
    }

    async fn events(State(mock): State<MockRelay>, Query(query): Query<HashMap<String, String>>, headers: axum::http::HeaderMap)
        -> Sse<impl Stream<Item = Result<Event, Infallible>>> {
        mock.cookies.lock().await.push(headers.get("cookie").and_then(|v| v.to_str().ok()).unwrap_or_default().to_owned());
        mock.queries.lock().await.push(query);
        let (sender, rx) = tokio::sync::mpsc::unbounded_channel();
        sender.send("event: controlReady\ndata: {\"connectionId\":\"connection\"}\n\n".into()).unwrap();
        *mock.sender.lock().await = Some(sender);
        // Deliver exact SSE frames, rather than nesting them in a second SSE envelope.
        let stream = tokio_stream::wrappers::UnboundedReceiverStream::new(rx).map(|frame: String| {
            let (event, data) = frame.split_once('\n').unwrap();
            Ok(Event::default().event(event.trim_start_matches("event: ")).data(data.trim_start_matches("data: ").trim()))
        });
        Sse::new(stream)
    }

    async fn control(State(mock): State<MockRelay>, headers: axum::http::HeaderMap, Json(body): Json<Value>) -> StatusCode {
        mock.cookies.lock().await.push(headers.get("cookie").and_then(|v| v.to_str().ok()).unwrap_or_default().to_owned());
        mock.requests.lock().await.push(body.clone());
        if body["kind"] == "request" {
            let prompt = body["payload"]["action"] == "prompt";
            let reply = json!({"id":body["id"],"payload": if prompt { json!({"type":"Permission"}) } else { json!({"type":"Session","title":"Remote"}) },"done":!prompt});
            if let Some(sender) = mock.sender.lock().await.as_ref() {
                let _ = sender.send(format!("event: controlReply\ndata: {reply}\n\n"));
            }
        } else if body["kind"] == "signal" {
            let reply = json!({"id":body["id"],"payload":{"type":"Finished"},"done":true});
            if let Some(sender) = mock.sender.lock().await.as_ref() {
                let _ = sender.send(format!("event: controlReply\ndata: {reply}\n\n"));
            }
        }
        StatusCode::ACCEPTED
    }

    async fn mock_server(app: Router) -> (String, tokio::task::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let task = tokio::spawn(async move { let _ = axum::serve(listener, app).await; });
        (format!("http://{addr}"), task)
    }

    fn state_for(host: &str) -> ApiState {
        let store = Arc::new(Store::open_memory().unwrap());
        store.kv_set(KV_SESSION, "messenger_session=token").unwrap();
        store.kv_set(KV_SESSION_HOST, host).unwrap();
        SyncEngine::new(&store, Session::default()).set_server_url(host).unwrap();
        ApiState::new(store, "local-token")
    }

    #[tokio::test]
    async fn proxies_remote_reply_and_never_exposes_cookie() {
        let mock = MockRelay::default();
        let app = Router::new().route("/api/remote/events", get(events)).route("/api/remote/control", post(control)).with_state(mock.clone());
        let (host, server) = mock_server(app).await;
        let state = state_for(&host);
        let id = Uuid::new_v4();
        let body = json!({"target":"desktop-1","payload":{"action":"notificationOpen","sessionId":"c1"}});
        let unauthorized = axum::http::Request::builder().method("POST").uri(format!("/api/v1/remote/client/commands/{id}"))
            .header("content-type", "application/json").body(Body::from(body.to_string())).unwrap();
        assert_eq!(router(state.clone()).oneshot(unauthorized).await.unwrap().status(), StatusCode::UNAUTHORIZED);
        let request = axum::http::Request::builder().method("POST").uri(format!("/api/v1/remote/client/commands/{id}"))
            .header("authorization", "Bearer local-token").header("content-type", "application/json")
            .body(Body::from(body.to_string())).unwrap();
        let response = router(state).oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let bytes = tokio::time::timeout(Duration::from_secs(3), response.into_body().collect()).await.unwrap().unwrap().to_bytes();
        let output = String::from_utf8(bytes.to_vec()).unwrap();
        assert!(output.contains("Remote"));
        assert!(!output.contains("token"));
        let sent = mock.requests.lock().await;
        assert_eq!(sent.len(), 1);
        assert_eq!(sent[0]["connectionId"], "connection");
        assert_eq!(sent[0]["target"], "desktop-1");
        assert_eq!(sent[0]["payload"], body["payload"]);
        assert_eq!(*mock.cookies.lock().await, vec!["messenger_session=token", "messenger_session=token"]);
        let queries = mock.queries.lock().await;
        assert_eq!(queries[0].get("control").map(String::as_str), Some("client"));
        assert_eq!(queries[0].get("controlOnly").map(String::as_str), Some("1"));
        server.abort();
    }

    #[tokio::test]
    async fn rejected_cloud_session_is_cleared_and_reported() {
        let app = Router::new().route("/api/remote/events", get(|| async { StatusCode::UNAUTHORIZED }));
        let (host, server) = mock_server(app).await;
        let state = state_for(&host);
        let request = axum::http::Request::builder().method("POST").uri(format!("/api/v1/remote/client/commands/{}", Uuid::new_v4()))
            .header("authorization", "Bearer local-token").header("content-type", "application/json")
            .body(Body::from(json!({"target":"desktop", "payload":{"action":"history","sessionId":"c1"}}).to_string())).unwrap();
        let response = router(state.clone()).oneshot(request).await.unwrap();
        let body = tokio::time::timeout(Duration::from_secs(3), response.into_body().collect()).await.unwrap().unwrap().to_bytes();
        assert!(String::from_utf8_lossy(&body).contains("session_expired"));
        assert!(state.store.kv_get(KV_SESSION).unwrap().is_none());
        server.abort();
    }

    #[tokio::test]
    async fn signal_forwards_approval_to_the_same_control_lane() {
        let mock = MockRelay::default();
        let app = Router::new().route("/api/remote/events", get(events)).route("/api/remote/control", post(control)).with_state(mock.clone());
        let (host, server) = mock_server(app).await;
        let state = state_for(&host);
        let id = Uuid::new_v4();
        let stream = command(State(state.clone()), Path(id), Json(Request {
            target: "desktop".into(), payload: json!({"action":"prompt","sessionId":"c1","text":"hello"}),
        })).await.unwrap().into_response().into_body();
        tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                if !mock.requests.lock().await.is_empty() { break; }
                tokio::task::yield_now().await;
            }
        }).await.unwrap();
        assert_eq!(signal(State(state), Path(id), Json(Signal { option_id: Some("approve".into()) })).await.unwrap(), StatusCode::ACCEPTED);
        let bytes = tokio::time::timeout(Duration::from_secs(3), stream.collect()).await.unwrap().unwrap().to_bytes();
        let output = String::from_utf8_lossy(&bytes);
        assert!(output.contains("Permission") && output.contains("Finished"));
        let sent = mock.requests.lock().await;
        assert_eq!(sent[1]["kind"], "signal");
        assert_eq!(sent[1]["id"], id.to_string());
        assert_eq!(sent[1]["optionId"], "approve");
        server.abort();
    }

    #[tokio::test]
    async fn dropping_observer_closes_lane_without_revoking_new_session() {
        let mock = MockRelay::default();
        let app = Router::new().route("/api/remote/events", get(events)).route("/api/remote/control", post(control)).with_state(mock.clone());
        let (host, server) = mock_server(app).await;
        let state = state_for(&host);
        let id = Uuid::new_v4();
        let stream = command(State(state.clone()), Path(id), Json(Request {
            target: "desktop".into(), payload: json!({"action":"prompt","sessionId":"c1","text":"hello"}),
        })).await.unwrap().into_response().into_body();
        tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                if !mock.requests.lock().await.is_empty() { break; }
                tokio::task::yield_now().await;
            }
        }).await.unwrap();
        drop(stream);
        tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                if state.remote_clients.lock().await.is_empty() && mock.sender.lock().await.as_ref().is_some_and(|s| s.is_closed()) { break; }
                tokio::task::yield_now().await;
            }
        }).await.unwrap();
        state.store.kv_set(KV_SESSION, "messenger_session=new").unwrap();
        clear_if_current(&state, &host, "messenger_session=token");
        assert_eq!(state.store.kv_get(KV_SESSION).unwrap().as_deref(), Some("messenger_session=new"));
        server.abort();
    }
}
