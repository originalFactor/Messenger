//! Loopback relay for agent-facing model APIs.
//!
//! Relay model IDs use the local model row ID, so providers with identical
//! upstream model names remain independently addressable. Upstream credentials
//! never appear in the advertised catalog or in the agent's configuration.
use super::*;
use axum::{body::Body, extract::DefaultBodyLimit, http::HeaderMap};
use futures_util::StreamExt as FuturesStreamExt;
use serde_json::{json, Value};

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
    let mut relay = state.agent_relay.lock().await;
    if relay.is_none() {
        *relay = Some(AgentRelay::start(state.store.clone(), &state.shutdown).await.map_err(ApiError::internal)?);
    }
    let relay = relay.as_ref().expect("relay started");
    // The control API is bearer protected; the relay token is returned only
    // here so an authorized client can configure an agent to use the relay.
    Ok(([(header::CACHE_CONTROL, "no-store")], Json(json!({"baseUrl":relay.base_url,"token":relay.token}))))
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
    if format != requested_format {
        return error(StatusCode::UNPROCESSABLE_ENTITY, "unsupported_format", "The selected provider uses a different API format");
    }
    body["model"] = json!(model.model_id);
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
    let content_type = response.headers().get(header::CONTENT_TYPE).cloned();
    let stream = response.bytes_stream().take_until(state.shutdown.clone().cancelled_owned());
    let mut result = Response::new(Body::from_stream(stream));
    *result.status_mut() = status;
    if let Some(content_type) = content_type { result.headers_mut().insert(header::CONTENT_TYPE, content_type); }
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
}
