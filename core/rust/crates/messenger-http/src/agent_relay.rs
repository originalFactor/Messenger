//! Loopback relay for agent-facing model APIs.
//!
//! Relay model IDs use the local model row ID, so providers with identical
//! upstream model names remain independently addressable. Upstream credentials
//! never appear in the advertised catalog or in the agent's configuration.
use super::*;
use axum::{body::Body, extract::DefaultBodyLimit, http::HeaderMap};
use futures_util::StreamExt as FuturesStreamExt;
use serde_json::{json, Value};

mod convert;
mod matrix;
mod stream;

const MODEL_PREFIX: &str = "messenger/";

#[derive(Clone)]
struct RelayState {
    store: Arc<Store>,
    token: Arc<str>,
    http: reqwest::Client,
    shutdown: CancellationToken,
}

pub(crate) struct AgentRelay {
    pub base_url: String,
    pub token: String,
    shutdown: CancellationToken,
}

impl AgentRelay {
    pub async fn start(store: Arc<Store>, parent: &CancellationToken) -> Result<Self, std::io::Error> {
        let listener = TcpListener::bind(SocketAddr::from(([127, 0, 0, 1], 0))).await?;
        let address = listener.local_addr()?;
        let token = format!("messenger-relay-{}", Uuid::new_v4());
        let shutdown = parent.child_token();
        let state = RelayState {
            store,
            token: Arc::from(token.clone()),
            // Do not forward provider credentials to a redirect target.
            http: reqwest::Client::builder().redirect(reqwest::redirect::Policy::none())
                .build().map_err(std::io::Error::other)?,
            shutdown: shutdown.clone(),
        };
        let app = Router::new()
            .route("/v1/models", get(models))
            .route("/v1/chat/completions", post(chat))
            .route("/v1/responses", post(responses))
            .route("/v1/messages", post(messages))
            .route("/v1/interactions", post(interactions))
            .layer(DefaultBodyLimit::max(24 * 1024 * 1024))
            .layer(middleware::from_fn_with_state(state.clone(), require_relay_token))
            .with_state(state);
        let signal = shutdown.clone();
        tokio::spawn(async move {
            let _ = axum::serve(listener, app).with_graceful_shutdown(signal.cancelled_owned()).await;
        });
        Ok(Self { base_url: format!("http://{address}/v1"), token, shutdown })
    }
}

impl Drop for AgentRelay {
    fn drop(&mut self) { self.shutdown.cancel(); }
}

pub(crate) async fn status(State(state): State<ApiState>) -> Json<Value> {
    let relay = state.agent_relay.lock().await;
    Json(match relay.as_ref() {
        Some(relay) => json!({"running":true,"baseUrl":relay.base_url}),
        None => json!({"running":false}),
    })
}

pub(crate) async fn start(State(state): State<ApiState>) -> Result<impl IntoResponse, ApiError> {
    let (base_url, token) = ensure(&state).await?;
    // The control API is bearer protected; the relay token is returned only
    // here so an authorized client can configure an agent to use the relay.
    Ok(([(header::CACHE_CONTROL, "no-store")], Json(json!({"baseUrl":base_url,"token":token}))))
}

pub(crate) async fn ensure(state: &ApiState) -> Result<(String, String), ApiError> {
    let mut relay = state.agent_relay.lock().await;
    if relay.is_none() {
        *relay = Some(AgentRelay::start(state.store.clone(), &state.shutdown).await.map_err(ApiError::internal)?);
    }
    let relay = relay.as_ref().expect("relay started");
    Ok((relay.base_url.clone(), relay.token.clone()))
}

pub(crate) async fn stop(State(state): State<ApiState>) -> StatusCode {
    state.agent_relay.lock().await.take();
    StatusCode::NO_CONTENT
}

fn authorized(headers: &HeaderMap, token: &str) -> bool {
    let bearer = headers.get(header::AUTHORIZATION).and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "));
    let api_key = headers.get("x-api-key").and_then(|v| v.to_str().ok());
    [bearer, api_key].into_iter().flatten()
        .any(|candidate| bool::from(candidate.as_bytes().ct_eq(token.as_bytes())))
}

async fn require_relay_token(State(state): State<RelayState>, request: Request<Body>, next: Next) -> Response {
    if !authorized(request.headers(), &state.token) {
        return error(StatusCode::UNAUTHORIZED, "unauthorized", "Invalid relay token");
    }
    next.run(request).await
}

fn error(status: StatusCode, code: &str, message: &str) -> Response {
    (status, Json(json!({"error":{"code":code,"message":message}}))).into_response()
}

async fn models(State(state): State<RelayState>) -> Response {
    let models = match state.store.list_models() {
        Ok(models) => models,
        Err(_) => return error(StatusCode::INTERNAL_SERVER_ERROR, "store_error", "Could not load models"),
    };
    let data: Vec<_> = models.into_iter().filter(|model| model.is_enabled).map(|model| json!({
        "id":format!("{MODEL_PREFIX}{}", model.id), "object":"model",
        "created":model.created_at / 1000, "owned_by":model.provider_id, "name":model.display_name,
    })).collect();
    Json(json!({"object":"list","data":data})).into_response()
}

async fn chat(State(state): State<RelayState>, headers: HeaderMap, Json(body): Json<Value>) -> Response {
    forward(state, headers, body, "chat", "/chat/completions").await
}
async fn responses(State(state): State<RelayState>, headers: HeaderMap, Json(body): Json<Value>) -> Response {
    forward(state, headers, body, "responses", "/responses").await
}
async fn messages(State(state): State<RelayState>, headers: HeaderMap, Json(body): Json<Value>) -> Response {
    forward(state, headers, body, "messages", "/messages").await
}
async fn interactions(State(state): State<RelayState>, headers: HeaderMap, Json(body): Json<Value>) -> Response {
    forward(state, headers, body, "interactions", "/interactions").await
}

async fn forward(state: RelayState, headers: HeaderMap, mut body: Value, requested_format: &str, path: &str) -> Response {
    let Some(id) = body.get("model").and_then(Value::as_str).and_then(|id| id.strip_prefix(MODEL_PREFIX)) else {
        return error(StatusCode::BAD_REQUEST, "invalid_model", "Select a model from the relay catalog");
    };
    let model = match state.store.get_model(id) {
        Ok(Some(model)) if model.is_enabled => model,
        Ok(_) => return error(StatusCode::NOT_FOUND, "model_not_found", "The selected model is unavailable"),
        Err(_) => return error(StatusCode::INTERNAL_SERVER_ERROR, "store_error", "Could not load model"),
    };
    let provider = match state.store.get_provider(&model.provider_id) {
        Ok(Some(provider)) => provider,
        Ok(None) => return error(StatusCode::NOT_FOUND, "provider_not_found", "The selected model's provider is unavailable"),
        Err(_) => return error(StatusCode::INTERNAL_SERVER_ERROR, "store_error", "Could not load provider"),
    };
    let format = if provider.api_format == "auto" { "chat" } else { provider.api_format.as_str() };
    let alias = format!("{MODEL_PREFIX}{}", model.id);
    let generic = format != requested_format && !(requested_format == "responses" && format == "chat");
    let converted = if generic {
        match matrix::request(requested_format, format, &body, &model.model_id) {
            Ok(body) => Some(convert::Request {stream:body["stream"].as_bool().unwrap_or(false),body, custom_tools:Default::default()}),
            Err(message) => return error(StatusCode::UNPROCESSABLE_ENTITY, "unsupported_feature", &message),
        }
    } else if requested_format == "responses" && format == "chat" {
        match convert::request(&body, &model.model_id) {
            Ok(converted) => Some(converted),
            Err(message) => return error(StatusCode::UNPROCESSABLE_ENTITY, "unsupported_feature", &message),
        }
    } else { None };
    if format != requested_format && converted.is_none() {
        return error(StatusCode::UNPROCESSABLE_ENTITY, "unsupported_format", "The selected provider uses a different API format");
    }
    let path = if let Some(converted) = &converted {
        body = converted.body.clone();
        match format { "responses" => "/responses", "messages" => "/messages", "interactions" => "/interactions", _ => "/chat/completions" }
    } else {
        body["model"] = json!(model.model_id);
        path
    };
    let mut request = state.http.post(format!("{}{path}", provider.base_url.trim_end_matches('/'))).json(&body);
    if !provider.api_key.is_empty() {
        request = request.bearer_auth(&provider.api_key);
        if format == "messages" { request = request.header("x-api-key", &provider.api_key); }
        if format == "interactions" { request = request.header("x-goog-api-key", &provider.api_key); }
    }
    if format == "messages" {
        request = request.header("anthropic-version", headers.get("anthropic-version").and_then(|v| v.to_str().ok()).unwrap_or("2023-06-01"));
        if let Some(beta) = headers.get("anthropic-beta") { request = request.header("anthropic-beta", beta.clone()); }
    }
    let response = match tokio::select! {
        result = request.send() => result,
        _ = state.shutdown.cancelled() => return error(StatusCode::SERVICE_UNAVAILABLE, "relay_stopped", "The relay has stopped"),
    } {
        Ok(response) => response,
        Err(failure) => return error(StatusCode::BAD_GATEWAY, "upstream_error", &format!(
            "Could not reach the selected provider: {}", failure.without_url(),
        )),
    };
    let status = response.status();
    if status.is_success() {
        if let Some(converted) = converted {
            if converted.stream {
                let is_sse = response.headers().get(header::CONTENT_TYPE).and_then(|v| v.to_str().ok())
                    .is_some_and(|v| v.starts_with("text/event-stream"));
                if !is_sse { return error(StatusCode::BAD_GATEWAY, "invalid_upstream", "Expected an upstream event stream"); }
                return convert_stream(response, state.shutdown, alias, converted.custom_tools, format.to_owned(), requested_format.to_owned());
            }
            let mut stream = response.bytes_stream();
            let mut bytes = Vec::new();
            loop {
                let chunk = tokio::select! {
                    result = FuturesStreamExt::next(&mut stream) => result,
                    _ = state.shutdown.cancelled() => return error(StatusCode::SERVICE_UNAVAILABLE, "relay_stopped", "The relay has stopped"),
                };
                match chunk {
                    None => break,
                    Some(Err(failure)) => return error(StatusCode::BAD_GATEWAY, "upstream_error", &failure.without_url().to_string()),
                    Some(Ok(chunk)) if bytes.len().saturating_add(chunk.len()) > 24 * 1024 * 1024 =>
                        return error(StatusCode::BAD_GATEWAY, "invalid_upstream", "Upstream response is too large"),
                    Some(Ok(chunk)) => bytes.extend_from_slice(&chunk),
                }
            }
            let payload: Value = match serde_json::from_slice(&bytes) {
                Ok(value) => value,
                Err(_) => return error(StatusCode::BAD_GATEWAY, "invalid_upstream", "Upstream response is not valid JSON"),
            };
            let result = if generic { matrix::response(format, requested_format, &payload, &alias) }
                else { convert::nonstream(&payload, alias, converted.custom_tools) };
            return match result {
                Ok(body) => Json(body).into_response(),
                Err(message) => error(StatusCode::BAD_GATEWAY, "invalid_upstream", &message),
            };
        }
    }
    let content_type = response.headers().get(header::CONTENT_TYPE).cloned();
    let stream = response.bytes_stream().take_until(state.shutdown.clone().cancelled_owned());
    let mut result = Response::new(Body::from_stream(stream));
    *result.status_mut() = status;
    if let Some(content_type) = content_type { result.headers_mut().insert(header::CONTENT_TYPE, content_type); }
    result
}

fn convert_stream(response: reqwest::Response, shutdown: CancellationToken, model: String, custom_tools: std::collections::HashSet<String>, source:String, target:String) -> Response {
    use axum::body::Bytes;
    async fn send(sender: &mpsc::Sender<Result<Bytes, std::io::Error>>, shutdown: &CancellationToken, value: String) -> bool {
        tokio::select! {
            result = sender.send(Ok(Bytes::from(value))) => result.is_ok(),
            _ = shutdown.cancelled() => false,
        }
    }
    let (sender, receiver) = mpsc::channel::<Result<Bytes, std::io::Error>>(16);
    tokio::spawn(async move {
        let mut converter = stream::Converter::new(source, target, model, custom_tools);
        if !send(&sender, &shutdown, converter.start()).await { return; }
        let mut upstream = response.bytes_stream();
        let mut buffer = Vec::new();
        let mut data = Vec::<String>::new();
        let mut data_len = 0usize;
        let failure = loop {
            let chunk = tokio::select! {
                _ = shutdown.cancelled() => return,
                _ = sender.closed() => return,
                chunk = FuturesStreamExt::next(&mut upstream) => chunk,
            };
            let chunk = match chunk {
                Some(Ok(chunk)) => chunk,
                Some(Err(err)) => break format!("Upstream stream failed: {}", err.without_url()),
                None => break "Upstream stream ended before its terminal event".to_owned(),
            };
            buffer.extend_from_slice(&chunk);
            if buffer.len() > 8 * 1024 * 1024 { break "Upstream SSE frame is too large".into(); }
            while let Some(newline) = buffer.iter().position(|&byte| byte == b'\n') {
                let line: Vec<u8> = buffer.drain(..=newline).collect();
                let line = match std::str::from_utf8(&line[..line.len()-1]) {
                    Ok(line) => line.trim_end_matches('\r'),
                    Err(_) => { send(&sender, &shutdown, converter.failed("Invalid UTF-8 in upstream event")).await; return; },
                };
                if line.is_empty() {
                    if data.is_empty() { continue; }
                    let payload = data.join("\n");
                    data.clear();
                    data_len = 0;
                    match converter.feed(&payload) {
                        Ok(out) => {
                            if !out.is_empty() && !send(&sender, &shutdown, out).await { return; }
                            if converter.is_finished() { return; }
                        }
                        Err(message) => { send(&sender, &shutdown, converter.failed(&message)).await; return; }
                    }
                } else if let Some(value) = line.strip_prefix("data:") {
                    let value = value.strip_prefix(' ').unwrap_or(value);
                    data_len += value.len() + 1;
                    if data_len > 8 * 1024 * 1024 {
                        send(&sender, &shutdown, converter.failed("Upstream SSE frame is too large")).await;
                        return;
                    }
                    data.push(value.to_owned());
                }
            }
        };
        send(&sender, &shutdown, converter.failed(&failure)).await;
    });
    let mut result = Response::new(Body::from_stream(tokio_stream::wrappers::ReceiverStream::new(receiver)));
    result.headers_mut().insert(header::CONTENT_TYPE, header::HeaderValue::from_static("text/event-stream"));
    result.headers_mut().insert(header::CACHE_CONTROL, header::HeaderValue::from_static("no-cache"));
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use tower::ServiceExt;

    fn add_model(store: &Store, provider_id: &str, base_url: &str, id: &str, enabled: bool) {
        store.upsert_provider(&StoredProvider {
            id: provider_id.into(), name: provider_id.into(), base_url: base_url.into(),
            api_key: format!("secret-{provider_id}"), api_format: "chat".into(), created_at: 1, updated_at: 1,
        }).unwrap();
        let model: StoredModel = serde_json::from_value(json!({
            "id":id, "provider_id":provider_id, "model_id":"same-upstream-name",
            "display_name":"Model", "is_enabled":enabled, "created_at":1,
        })).unwrap();
        store.upsert_model(&model).unwrap();
    }

    #[tokio::test]
    async fn chat_stream_uses_messages_provider_endpoint() {
        let upstream = wiremock::MockServer::start().await;
        wiremock::Mock::given(wiremock::matchers::path("/v1/messages"))
            .and(wiremock::matchers::body_partial_json(json!({"stream":true})))
            .respond_with(wiremock::ResponseTemplate::new(200).set_body_raw(
                format!("data: {}\n\ndata: {}\n\n",json!({"type":"content_block_delta","delta":{"type":"text_delta","text":"converted"}}),json!({"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}})), "text/event-stream"))
            .expect(1).mount(&upstream).await;
        let store = Arc::new(Store::open_memory().unwrap());
        add_model(&store,"p",&format!("{}/v1",upstream.uri()),"m",true);
        let mut provider=store.get_provider("p").unwrap().unwrap();
        provider.api_format="messages".into();store.upsert_provider(&provider).unwrap();
        let relay=AgentRelay::start(store,&CancellationToken::new()).await.unwrap();
        let response=reqwest::Client::new().post(format!("{}/chat/completions",relay.base_url)).bearer_auth(&relay.token)
            .json(&json!({"model":"messenger/m","stream":true,"messages":[{"role":"user","content":"hello"}]})).send().await.unwrap();
        assert_eq!(response.status(),StatusCode::OK);
        let output=response.text().await.unwrap();
        assert!(output.contains("converted") && output.contains("[DONE]"),"{output}");
    }

    #[tokio::test]
    async fn responses_to_chat_converts_stream_and_tool_round_trip() {
        let upstream = wiremock::MockServer::start().await;
        let chunks = [
            json!({"choices":[{"delta":{"content":"Hello "},"finish_reason":null}]}),
            json!({"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"read","arguments":r#"{"path":"#}}]},"finish_reason":null}]}),
            json!({"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":r#""file"}"#}}]},"finish_reason":"tool_calls"}]}),
            json!({"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}),
        ];
        let sse = chunks.iter().map(|chunk| format!("data: {chunk}\n\n")).collect::<String>() + "data: [DONE]\n\n";
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .and(wiremock::matchers::path("/v1/chat/completions"))
            .and(wiremock::matchers::header("authorization", "Bearer secret-p"))
            .and(wiremock::matchers::body_partial_json(json!({"model":"same-upstream-name","stream":true,
                "messages":[{"role":"system","content":"Follow instructions"},{"role":"user","content":"Hi"}],
                "tools":[{"type":"function","function":{"name":"read","parameters":{"type":"object"}}}]})))
            .respond_with(wiremock::ResponseTemplate::new(200).set_body_raw(sse, "text/event-stream"))
            .expect(1).mount(&upstream).await;
        let store = Arc::new(Store::open_memory().unwrap());
        add_model(&store, "p", &format!("{}/v1", upstream.uri()), "m", true);
        let relay = AgentRelay::start(store, &CancellationToken::new()).await.unwrap();
        let client = reqwest::Client::new();
        let response = client.post(format!("{}/responses", relay.base_url)).bearer_auth(&relay.token)
            .json(&json!({"model":"messenger/m","instructions":"Follow instructions","input":"Hi","stream":true,
                "tools":[{"type":"function","name":"read","parameters":{"type":"object"}}]})).send().await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let text = response.text().await.unwrap();
        let events: Vec<Value> = text.lines().filter_map(|line| line.strip_prefix("data: "))
            .map(|line| serde_json::from_str(line).unwrap()).collect();
        assert!(events.iter().any(|event| event["type"] == "response.output_text.delta" && event["delta"] == "Hello "));
        let final_event = events.last().unwrap();
        assert_eq!(final_event["type"], "response.completed");
        assert_eq!(final_event["response"]["model"], "messenger/m");
        assert_eq!(final_event["response"]["usage"]["total_tokens"], 17);
        assert_eq!(final_event["response"]["output"][1]["call_id"], "call-1");
        assert_eq!(final_event["response"]["output"][1]["arguments"], r#"{"path":"file"}"#);
    }

    #[tokio::test]
    async fn converted_stream_reports_truncation_and_nonstream_converts_response() {
        let upstream = wiremock::MockServer::start().await;
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .and(wiremock::matchers::body_partial_json(json!({"stream":true})))
            .respond_with(wiremock::ResponseTemplate::new(200).set_body_raw(
                "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n", "text/event-stream"))
            .expect(1).mount(&upstream).await;
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .and(wiremock::matchers::body_partial_json(json!({"stream":false})))
            .respond_with(wiremock::ResponseTemplate::new(200).set_body_json(json!({
                "choices":[{"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}
            }))).expect(1).mount(&upstream).await;
        let store = Arc::new(Store::open_memory().unwrap());
        add_model(&store, "p", &format!("{}/v1", upstream.uri()), "m", true);
        let relay = AgentRelay::start(store, &CancellationToken::new()).await.unwrap();
        let client = reqwest::Client::new();
        let request = |stream| client.post(format!("{}/responses", relay.base_url)).bearer_auth(&relay.token)
            .json(&json!({"model":"messenger/m","input":"hi","stream":stream}));
        let stream = request(true).send().await.unwrap().text().await.unwrap();
        assert!(stream.contains("response.failed") && stream.contains("terminal event"));
        assert!(!stream.contains("response.completed"));
        let response: Value = request(false).send().await.unwrap().json().await.unwrap();
        assert_eq!(response["output"][0]["content"][0]["text"], "answer");
        assert_eq!(response["usage"]["total_tokens"], 3);
        let unsupported = client.post(format!("{}/responses", relay.base_url)).bearer_auth(&relay.token)
            .json(&json!({"model":"messenger/m","previous_response_id":"resp-old","input":"hi"})).send().await.unwrap();
        assert_eq!(unsupported.status(), StatusCode::UNPROCESSABLE_ENTITY);
        assert!(unsupported.text().await.unwrap().contains("previous_response_id"));
    }

    #[tokio::test]
    async fn relay_routes_distinct_model_ids_and_preserves_streams_without_exposing_secrets() {
        let first = wiremock::MockServer::start().await;
        let second = wiremock::MockServer::start().await;
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .and(wiremock::matchers::path("/v1/chat/completions"))
            .and(wiremock::matchers::header("authorization", "Bearer secret-p2"))
            .and(wiremock::matchers::body_partial_json(json!({"model":"same-upstream-name"})))
            .respond_with(wiremock::ResponseTemplate::new(200)
                .set_body_raw("data: {\"choices\":[]}\n\ndata: [DONE]\n\n", "text/event-stream"))
            .expect(1).mount(&second).await;
        let store = Arc::new(Store::open_memory().unwrap());
        add_model(&store, "p1", &format!("{}/v1", first.uri()), "m1", true);
        add_model(&store, "p2", &format!("{}/v1", second.uri()), "m2", true);
        add_model(&store, "p3", &format!("{}/v1", first.uri()), "m3", false);
        let shutdown = CancellationToken::new();
        let relay = AgentRelay::start(store, &shutdown).await.unwrap();
        let client = reqwest::Client::new();
        let url = format!("{}/models", relay.base_url);
        assert_eq!(client.get(&url).send().await.unwrap().status(), StatusCode::UNAUTHORIZED);
        let catalog = client.get(&url).bearer_auth(&relay.token).send().await.unwrap().text().await.unwrap();
        assert!(catalog.contains("messenger/m1") && catalog.contains("messenger/m2"));
        assert!(!catalog.contains("messenger/m3") && !catalog.contains("secret-"));
        let response = client.post(format!("{}/chat/completions", relay.base_url)).bearer_auth(&relay.token)
            .json(&json!({"model":"messenger/m2","messages":[],"stream":true})).send().await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert!(response.headers().get(header::CONTENT_TYPE).unwrap().to_str().unwrap().starts_with("text/event-stream"));
        assert_eq!(response.text().await.unwrap(), "data: {\"choices\":[]}\n\ndata: [DONE]\n\n");
        let rejected = client.post(format!("{}/responses", relay.base_url)).bearer_auth(&relay.token)
            .json(&json!({"model":"messenger/m1"})).send().await.unwrap();
        assert_eq!(rejected.status(), StatusCode::UNPROCESSABLE_ENTITY);
        shutdown.cancel();
    }

    #[tokio::test]
    async fn relay_lifecycle_is_authenticated_and_reuses_the_running_instance() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "control-token");
        let request = |method: &str, authenticated: bool| {
            let mut builder = axum::http::Request::builder().method(method).uri("/api/v1/control/agent-relay");
            if authenticated { builder = builder.header("authorization", "Bearer control-token"); }
            builder.body(Body::empty()).unwrap()
        };
        let response = router(state.clone()).oneshot(request("POST", false)).await.unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
        let response = router(state.clone()).oneshot(request("POST", true)).await.unwrap();
        assert_eq!(response.headers().get(header::CACHE_CONTROL).unwrap(), "no-store");
        let first: Value = serde_json::from_slice(&axum::body::to_bytes(response.into_body(), 4096).await.unwrap()).unwrap();
        let response = router(state.clone()).oneshot(request("POST", true)).await.unwrap();
        let second: Value = serde_json::from_slice(&axum::body::to_bytes(response.into_body(), 4096).await.unwrap()).unwrap();
        assert_eq!(first, second);
        let response = router(state.clone()).oneshot(request("GET", true)).await.unwrap();
        let status = axum::body::to_bytes(response.into_body(), 4096).await.unwrap();
        assert!(!String::from_utf8_lossy(&status).contains(first["token"].as_str().unwrap()));
        let response = router(state.clone()).oneshot(request("DELETE", true)).await.unwrap();
        assert_eq!(response.status(), StatusCode::NO_CONTENT);
        let response = router(state.clone()).oneshot(request("POST", true)).await.unwrap();
        let restarted: Value = serde_json::from_slice(&axum::body::to_bytes(response.into_body(), 4096).await.unwrap()).unwrap();
        assert_ne!(first["token"], restarted["token"]);
        state.shutdown.cancel();
    }

    #[tokio::test]
    async fn messages_forward_provider_auth_and_upstream_errors_once() {
        let upstream = wiremock::MockServer::start().await;
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .and(wiremock::matchers::path("/v1/messages"))
            .and(wiremock::matchers::header("x-api-key", "secret-p1"))
            .and(wiremock::matchers::header("anthropic-version", "2023-06-01"))
            .respond_with(wiremock::ResponseTemplate::new(401).set_body_json(json!({"error":{"message":"invalid upstream key"}})))
            .expect(1).mount(&upstream).await;
        let store = Arc::new(Store::open_memory().unwrap());
        add_model(&store, "p1", &format!("{}/v1", upstream.uri()), "m1", true);
        let mut provider = store.get_provider("p1").unwrap().unwrap();
        provider.api_format = "messages".into();
        store.upsert_provider(&provider).unwrap();
        let shutdown = CancellationToken::new();
        let relay = AgentRelay::start(store, &shutdown).await.unwrap();
        let response = reqwest::Client::new().post(format!("{}/messages", relay.base_url))
            .header("x-api-key", &relay.token).json(&json!({"model":"messenger/m1","messages":[]}))
            .send().await.unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
        assert!(response.text().await.unwrap().contains("invalid upstream key"));
        shutdown.cancel();
    }

    #[tokio::test]
    async fn relay_does_not_send_provider_credentials_to_redirect_targets() {
        let upstream = wiremock::MockServer::start().await;
        let other = wiremock::MockServer::start().await;
        wiremock::Mock::given(wiremock::matchers::any()).respond_with(wiremock::ResponseTemplate::new(200))
            .expect(0).mount(&other).await;
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .respond_with(wiremock::ResponseTemplate::new(307).insert_header("location", other.uri()))
            .expect(1).mount(&upstream).await;
        let store = Arc::new(Store::open_memory().unwrap());
        add_model(&store, "p1", &format!("{}/v1", upstream.uri()), "m1", true);
        let shutdown = CancellationToken::new();
        let relay = AgentRelay::start(store, &shutdown).await.unwrap();
        let response = reqwest::Client::builder().redirect(reqwest::redirect::Policy::none()).build().unwrap()
            .post(format!("{}/chat/completions", relay.base_url)).bearer_auth(&relay.token)
            .json(&json!({"model":"messenger/m1","messages":[]})).send().await.unwrap();
        assert_eq!(response.status(), StatusCode::TEMPORARY_REDIRECT);
        shutdown.cancel();
    }

    #[tokio::test]
    async fn stopping_relay_closes_active_upstream_stream() {
        let listener = TcpListener::bind(SocketAddr::from(([127, 0, 0, 1], 0))).await.unwrap();
        let address = listener.local_addr().unwrap();
        let upstream_stop = CancellationToken::new();
        let upstream_shutdown = upstream_stop.clone();
        let app = Router::new().route("/v1/chat/completions", post(|| async {
            let first = futures_util::stream::iter(vec![Ok::<_, std::io::Error>(
                axum::body::Bytes::from_static(b"data: first\n\n")
            )]);
            let stream = futures_util::StreamExt::chain(first, futures_util::stream::pending());
            Response::builder().header(header::CONTENT_TYPE, "text/event-stream")
                .body(Body::from_stream(stream)).unwrap()
        }));
        tokio::spawn(async move { let _ = axum::serve(listener, app).with_graceful_shutdown(upstream_shutdown.cancelled_owned()).await; });
        let store = Arc::new(Store::open_memory().unwrap());
        add_model(&store, "p1", &format!("http://{address}/v1"), "m1", true);
        let relay = AgentRelay::start(store, &CancellationToken::new()).await.unwrap();
        let response = reqwest::Client::new().post(format!("{}/chat/completions", relay.base_url))
            .bearer_auth(&relay.token).json(&json!({"model":"messenger/m1","stream":true})).send().await.unwrap();
        let mut stream = response.bytes_stream();
        assert!(tokio::time::timeout(std::time::Duration::from_secs(2), futures_util::StreamExt::next(&mut stream)).await.unwrap().is_some());
        drop(relay);
        let result = tokio::time::timeout(std::time::Duration::from_secs(2), futures_util::StreamExt::next(&mut stream)).await;
        assert!(matches!(result, Ok(None) | Ok(Some(Err(_)))), "relay must close its stream on shutdown");
        upstream_stop.cancel();
    }

    #[tokio::test]
    async fn converted_stream_handles_split_sse_and_stops_with_the_relay() {
        let listener = TcpListener::bind(SocketAddr::from(([127, 0, 0, 1], 0))).await.unwrap();
        let address = listener.local_addr().unwrap();
        let upstream_stop = CancellationToken::new();
        let upstream_shutdown = upstream_stop.clone();
        let app = Router::new().route("/v1/chat/completions", post(|| async {
            let chunks = vec![
                Ok::<_, std::io::Error>(axum::body::Bytes::from_static(b"data: {\"choices\":[{\"delta\":{\"content\":\"spl")),
                Ok(axum::body::Bytes::from_static(b"it\"},\"finish_reason\":null}]}\r\n\r\n")),
            ];
            let stream = futures_util::StreamExt::chain(futures_util::stream::iter(chunks), futures_util::stream::pending());
            Response::builder().header(header::CONTENT_TYPE, "text/event-stream")
                .body(Body::from_stream(stream)).unwrap()
        }));
        tokio::spawn(async move { let _ = axum::serve(listener, app).with_graceful_shutdown(upstream_shutdown.cancelled_owned()).await; });
        let store = Arc::new(Store::open_memory().unwrap());
        add_model(&store, "p", &format!("http://{address}/v1"), "m", true);
        let relay = AgentRelay::start(store, &CancellationToken::new()).await.unwrap();
        let response = reqwest::Client::new().post(format!("{}/responses", relay.base_url))
            .bearer_auth(&relay.token).json(&json!({"model":"messenger/m","input":"hi","stream":true})).send().await.unwrap();
        let mut stream = response.bytes_stream();
        let mut content = String::new();
        while !content.contains("response.output_text.delta") {
            let chunk = tokio::time::timeout(std::time::Duration::from_secs(2), futures_util::StreamExt::next(&mut stream)).await.unwrap().unwrap().unwrap();
            content.push_str(std::str::from_utf8(&chunk).unwrap());
        }
        assert!(content.contains("split"));
        drop(relay);
        let end = tokio::time::timeout(std::time::Duration::from_secs(2), async {
            while let Some(Ok(_)) = futures_util::StreamExt::next(&mut stream).await {}
        }).await;
        assert!(end.is_ok(), "converted stream must close when the relay stops");
        upstream_stop.cancel();
    }
}
