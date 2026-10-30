//! Loopback-only HTTP boundary for the Rust Agent Core.
//!
//! The API is deliberately transport-oriented: JSON is used for CRUD and
//! command responses, while chat turns use SSE so Android/Desktop can consume
//! the same event stream without a platform FFI. The server generates a fresh
//! bearer token for every process and refuses non-loopback listeners.

use std::{collections::HashMap, convert::Infallible, net::SocketAddr, sync::Arc};

use async_trait::async_trait;
use axum::{
    extract::{Path, Query, State},
    http::{header, Request, StatusCode},
    middleware::{self, Next},
    response::{
        sse::{Event, KeepAlive, Sse},
        IntoResponse, Response,
    },
    routing::{get, post, put},
    Json, Router,
};
use futures_core::Stream;
use messenger_core::{
    agent::{run_chat_turn, AgentEvent, TitleConfig, ToolHost, TurnRequest, TurnSink},
    ApiFormat,
};
use messenger_store::{
    model::{
        StoredAgent, StoredConversation, StoredMessage, StoredModel, StoredProject, StoredProvider,
    },
    now_ms, EntityKind, Store, StoreEvent,
};
use messenger_sync::api::CloudError;
use messenger_sync::{
    discover_provider_models, enrich_with_models_dev, models_dev_metadata, Session, SyncEngine,
    KV_SESSION, KV_SESSION_HOST,
};
use messenger_tools::{apply_writable_mode, ToolExecutionResult};
use serde::{Deserialize, Serialize};
use subtle::ConstantTimeEq;
use tokio::{
    net::TcpListener,
    sync::{broadcast, mpsc, oneshot, Mutex},
};
use tokio_stream::{
    wrappers::{BroadcastStream, UnboundedReceiverStream},
    StreamExt,
};
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

/// Stable API prefix. Keep this in one place so clients and documentation do
/// not drift when a future incompatible version is introduced.
pub const API_PREFIX: &str = "/api/v1";

#[derive(Clone)]
pub struct ApiState {
    pub store: Arc<Store>,
    pub token: Arc<str>,
    active_turns: Arc<Mutex<HashMap<String, CancellationToken>>>,
    agent_mutations: Arc<Mutex<()>>,
    pending_tools: Arc<Mutex<HashMap<String, oneshot::Sender<ToolExecutionResult>>>>,
    events: broadcast::Sender<ChangeEvent>,
    listener_id: usize,
}

impl ApiState {
    pub fn new(store: Arc<Store>, token: impl Into<Arc<str>>) -> Self {
        let (events, _) = broadcast::channel(256);
        let sender = events.clone();
        let listener_id = store.subscribe(Box::new(move |event| {
            let _ = sender.send(ChangeEvent::from_store(event));
        }));
        Self {
            store,
            token: token.into(),
            active_turns: Arc::new(Mutex::new(HashMap::new())),
            agent_mutations: Arc::new(Mutex::new(())),
            pending_tools: Arc::new(Mutex::new(HashMap::new())),
            events,
            listener_id,
        }
    }
}

impl Drop for ApiState {
    fn drop(&mut self) {
        self.store.unsubscribe(self.listener_id);
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ChangeEvent {
    kind: String,
    ids: Vec<String>,
}

impl ChangeEvent {
    fn from_store(event: &StoreEvent) -> Self {
        let kind = match event.kind {
            EntityKind::Project => "Project",
            EntityKind::Provider => "Provider",
            EntityKind::Model => "Model",
            EntityKind::Agent => "Agent",
            EntityKind::Conversation => "Conversation",
            EntityKind::Message => "Message",
            EntityKind::Other => "Other",
        };
        Self {
            kind: kind.into(),
            ids: event.ids.clone(),
        }
    }
}

/// A running API server. Call `stop` to request graceful shutdown.
pub struct ServerHandle {
    pub local_addr: SocketAddr,
    pub token: String,
    shutdown: CancellationToken,
    task: Option<tokio::task::JoinHandle<Result<(), std::io::Error>>>,
}

impl ServerHandle {
    /// Start on an explicitly selected loopback address. `0` is useful for
    /// tests and lets the OS choose a free port.
    pub async fn start(store: Arc<Store>, bind_addr: SocketAddr) -> Result<Self, std::io::Error> {
        if !bind_addr.ip().is_loopback() {
            return Err(std::io::Error::new(
                std::io::ErrorKind::PermissionDenied,
                "messenger core API must bind to loopback",
            ));
        }
        let listener = TcpListener::bind(bind_addr).await?;
        let local_addr = listener.local_addr()?;
        let token = format!("messenger-{}", Uuid::new_v4());
        let state = ApiState::new(store, Arc::<str>::from(token.clone()));
        let router = router(state);
        let shutdown = CancellationToken::new();
        let shutdown_for_task = shutdown.clone();
        let task = tokio::spawn(async move {
            axum::serve(listener, router)
                .with_graceful_shutdown(shutdown_for_task.cancelled_owned())
                .await
        });
        Ok(Self {
            local_addr,
            token,
            shutdown,
            task: Some(task),
        })
    }

    pub async fn start_local(store: Arc<Store>) -> Result<Self, std::io::Error> {
        Self::start(store, SocketAddr::from(([127, 0, 0, 1], 0))).await
    }

    pub async fn stop(mut self) -> Result<(), std::io::Error> {
        self.shutdown.cancel();
        if let Some(task) = self.task.take() {
            task.await
                .map_err(|e| std::io::Error::other(e.to_string()))?
        } else {
            Ok(())
        }
    }
}

impl Drop for ServerHandle {
    fn drop(&mut self) {
        self.shutdown.cancel();
    }
}

pub fn router(state: ApiState) -> Router {
    let protected = Router::new()
        .route("/health", get(health))
        .route("/meta", get(meta))
        .route("/events", get(core_events))
        .route("/providers", get(list_providers).put(upsert_provider_body))
        .route(
            "/providers/:id",
            get(get_provider)
                .put(upsert_provider)
                .delete(delete_provider),
        )
        .route("/providers/:id/models", get(list_provider_models))
        .route("/models", get(list_models).put(upsert_model_body))
        .route(
            "/models/:id",
            get(get_model).put(upsert_model).delete(delete_model),
        )
        .route("/models/:id/enabled", put(set_model_enabled))
        .route("/agents", get(list_agents).put(upsert_agent_body))
        .route("/agents/default", get(get_default_agent))
        .route("/agents/title", get(get_title_agent))
        .route("/agents/:id/conversations", get(list_agent_conversations))
        .route(
            "/agents/:id",
            get(get_agent).put(upsert_agent).delete(delete_agent),
        )
        .route("/projects", get(list_projects).put(upsert_project_body))
        .route(
            "/projects/:id/conversations",
            get(list_project_conversations),
        )
        .route(
            "/projects/:id",
            get(get_project).put(upsert_project).delete(delete_project),
        )
        .route(
            "/conversations",
            get(list_conversations).put(upsert_conversation_body),
        )
        .route(
            "/conversations/:id",
            get(get_conversation)
                .put(upsert_conversation)
                .delete(delete_conversation),
        )
        .route(
            "/conversations/:id/messages",
            get(list_messages)
                .post(send_message)
                .delete(delete_conversation_messages),
        )
        .route(
            "/conversations/:id/last-message",
            put(update_conversation_last_message),
        )
        .route(
            "/conversations/sweep-empty",
            post(sweep_empty_conversations),
        )
        .route(
            "/messages/:id",
            get(get_message).put(upsert_message).delete(delete_message),
        )
        .route("/conversations/:id/cancel", post(cancel_turn))
        .route("/turns/tools/:call_id/result", post(resolve_tool_call))
        .route("/changes", post(mark_change))
        .route("/kv/:key", get(kv_get).put(kv_set).delete(kv_delete))
        .route("/migration/import", post(import_legacy_data))
        .route("/models/discover", post(discover_models))
        .route("/models/metadata/:id", get(model_metadata))
        .route("/cloud/login", post(cloud_login))
        .route("/cloud/register", post(cloud_register))
        .route("/cloud/logout", post(cloud_logout))
        .route("/cloud/refresh", post(cloud_refresh))
        .route("/cloud/me", get(cloud_me))
        .route(
            "/cloud/server-url",
            get(cloud_get_server_url).put(cloud_set_server_url),
        )
        .route("/cloud/has-local-data", get(cloud_has_local_data))
        .route("/cloud/sync", post(cloud_sync))
        .route("/cloud/sync-with", post(cloud_sync_with))
        .route("/cloud/push-pending", post(cloud_push_pending))
        .route("/cloud/push-snapshot", post(cloud_push_snapshot))
        .route(
            "/cloud/replace-cloud-with-local",
            post(cloud_replace_cloud_with_local),
        )
        .route("/cloud/cards/preview", post(cloud_preview_card))
        .route("/cloud/cards/redeem", post(cloud_redeem_card))
        .route(
            "/cloud/builtin-models/sync",
            post(cloud_sync_builtin_models),
        )
        .route("/cloud/ensure-title-agent", post(cloud_ensure_title_agent))
        .route("/cloud/change-password", post(cloud_change_password))
        .route(
            "/cloud/account",
            axum::routing::delete(cloud_delete_account),
        )
        .route("/cloud/cache-avatar", post(cloud_cache_avatar))
        .route(
            "/cloud/avatars/user",
            post(cloud_upload_user_avatar).delete(cloud_delete_user_avatar),
        )
        .route(
            "/cloud/avatars/agents/:id",
            post(cloud_upload_agent_avatar).delete(cloud_delete_agent_avatar),
        )
        .route("/cloud/market", get(cloud_list_market_agents))
        .route(
            "/cloud/market/:id",
            get(cloud_get_market_agent).delete(cloud_remove_market_agent),
        )
        .route(
            "/cloud/market/publish/:agent_id",
            post(cloud_publish_market_agent),
        )
        .route(
            "/cloud/market/import/:market_id",
            post(cloud_import_market_agent),
        )
        .route(
            "/cloud/market/import-avatar/:market_id",
            post(cloud_import_market_agent_with_avatar),
        )
        .route(
            "/cloud/market/update/:agent_id",
            post(cloud_push_market_agent_update),
        )
        .route(
            "/cloud/market/check/:agent_id",
            get(cloud_check_market_agent_update),
        )
        .route(
            "/cloud/market/apply/:agent_id",
            post(cloud_apply_market_agent_update),
        )
        .route(
            "/cloud/market-links",
            axum::routing::delete(cloud_clear_market_links),
        )
        .route("/local-data", axum::routing::delete(clear_local_data))
        .with_state(state.clone())
        .layer(middleware::from_fn_with_state(state.clone(), authenticate));
    Router::new().nest(API_PREFIX, protected)
}

async fn authenticate(
    State(state): State<ApiState>,
    request: Request<axum::body::Body>,
    next: Next,
) -> Response {
    if request.uri().path().ends_with("/health") {
        return next.run(request).await;
    }
    let authorized = request
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .map(|value| token_matches(value, &state.token))
        .or_else(|| {
            request
                .headers()
                .get("x-messenger-token")
                .and_then(|v| v.to_str().ok())
                .map(|v| token_matches(v, &state.token))
        })
        .unwrap_or(false);
    if authorized {
        next.run(request).await
    } else {
        ApiError::unauthorized().into_response()
    }
}

async fn core_events(
    State(state): State<ApiState>,
) -> Sse<impl Stream<Item = Result<Event, Infallible>>> {
    let stream = BroadcastStream::new(state.events.subscribe()).filter_map(|item| match item {
        Ok(event) => Some(Ok(Event::default()
            .event("change")
            .json_data(event)
            .expect("ChangeEvent serializes"))),
        Err(tokio_stream::wrappers::errors::BroadcastStreamRecvError::Lagged(skipped)) => {
            let event = ChangeEvent {
                kind: "Resync".into(),
                ids: vec![skipped.to_string()],
            };
            Some(Ok(Event::default()
                .event("resync")
                .json_data(event)
                .expect("ChangeEvent serializes")))
        }
    });
    Sse::new(stream).keep_alive(KeepAlive::default())
}

fn token_matches(candidate: &str, expected: &str) -> bool {
    candidate.len() == expected.len() && bool::from(candidate.as_bytes().ct_eq(expected.as_bytes()))
}

#[derive(Debug, Serialize)]
struct HealthResponse {
    status: &'static str,
    api_version: &'static str,
}

async fn health() -> Json<HealthResponse> {
    Json(HealthResponse {
        status: "ok",
        api_version: "v1",
    })
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct MetaResponse {
    api_version: &'static str,
    server_version: &'static str,
    capabilities: Capabilities,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Capabilities {
    crud: bool,
    chat_sse: bool,
    cancellation: bool,
    platform_tools: bool,
    cloud_sync: bool,
}

async fn meta() -> Json<MetaResponse> {
    Json(MetaResponse {
        api_version: "v1",
        server_version: messenger_core::version::NAME,
        capabilities: Capabilities {
            crud: true,
            chat_sse: true,
            cancellation: true,
            platform_tools: false,
            cloud_sync: false,
        },
    })
}

#[derive(Debug, Deserialize, Default)]
struct PageQuery {
    #[serde(default = "default_limit")]
    limit: usize,
    #[serde(default)]
    offset: usize,
}
fn default_limit() -> usize {
    100
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Page<T> {
    items: Vec<T>,
    offset: usize,
    limit: usize,
    total: usize,
    next_offset: Option<usize>,
}

fn page<T>(mut items: Vec<T>, query: PageQuery) -> Page<T> {
    let total = items.len();
    let offset = query.offset.min(total);
    let limit = query.limit.clamp(1, 500);
    let end = (offset + limit).min(total);
    let next_offset = (end < total).then_some(end);
    Page {
        items: items.drain(offset..end).collect(),
        offset,
        limit,
        total,
        next_offset,
    }
}

#[derive(Debug, Serialize, Deserialize)]
struct ErrorBody {
    code: String,
    message: String,
    detail: Option<String>,
}

#[derive(Debug)]
enum ApiError {
    Unauthorized,
    BadRequest(String),
    NotFound(&'static str),
    Conflict { code: String, message: String },
    Store(String),
    Cloud(CloudError),
    Internal(String),
}

impl ApiError {
    fn unauthorized() -> Self {
        Self::Unauthorized
    }
    fn store(error: impl std::fmt::Display) -> Self {
        Self::Store(error.to_string())
    }
    fn not_found(kind: &'static str) -> Self {
        Self::NotFound(kind)
    }
    fn cloud(error: CloudError) -> Self {
        Self::Cloud(error)
    }
    fn internal(error: impl std::fmt::Display) -> Self {
        Self::Internal(error.to_string())
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, body) = match self {
            Self::Unauthorized => (
                StatusCode::UNAUTHORIZED,
                ErrorBody {
                    code: "unauthorized".into(),
                    message: "invalid local API credential".into(),
                    detail: None,
                },
            ),
            Self::BadRequest(detail) => (
                StatusCode::BAD_REQUEST,
                ErrorBody {
                    code: "bad_request".into(),
                    message: "request validation failed".into(),
                    detail: Some(detail),
                },
            ),
            Self::NotFound(kind) => (
                StatusCode::NOT_FOUND,
                ErrorBody {
                    code: "not_found".into(),
                    message: format!("{kind} not found"),
                    detail: None,
                },
            ),
            Self::Conflict { code, message } => (
                StatusCode::CONFLICT,
                ErrorBody {
                    code,
                    message,
                    detail: None,
                },
            ),
            Self::Store(detail) => (
                StatusCode::INTERNAL_SERVER_ERROR,
                ErrorBody {
                    code: "storage_error".into(),
                    message: "local storage operation failed".into(),
                    detail: Some(detail),
                },
            ),
            Self::Cloud(error) => {
                let (status, code, detail) = match &error {
                    CloudError::SessionExpired { message } => {
                        (StatusCode::UNAUTHORIZED, "session_expired", message.clone())
                    }
                    CloudError::Http { status, message } => (
                        StatusCode::from_u16(*status).unwrap_or(StatusCode::BAD_GATEWAY),
                        "cloud_http_error",
                        message.clone(),
                    ),
                    CloudError::Network(message) => (
                        StatusCode::BAD_GATEWAY,
                        "cloud_network_error",
                        message.clone(),
                    ),
                    CloudError::InvalidBody(message) => (
                        StatusCode::BAD_GATEWAY,
                        "cloud_invalid_body",
                        message.clone(),
                    ),
                };
                (
                    status,
                    ErrorBody {
                        code: code.into(),
                        message: "cloud operation failed".into(),
                        detail: Some(detail),
                    },
                )
            }
            Self::Internal(detail) => (
                StatusCode::INTERNAL_SERVER_ERROR,
                ErrorBody {
                    code: "internal_error".into(),
                    message: "core operation failed".into(),
                    detail: Some(detail),
                },
            ),
        };
        (status, Json(body)).into_response()
    }
}

async fn list_providers(
    State(state): State<ApiState>,
    Query(query): Query<PageQuery>,
) -> Result<Json<Page<StoredProvider>>, ApiError> {
    Ok(Json(page(
        state.store.list_providers().map_err(ApiError::store)?,
        query,
    )))
}
async fn get_provider(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<Json<StoredProvider>, ApiError> {
    get_one(
        state.store.get_provider(&id).map_err(ApiError::store)?,
        "provider",
    )
}
async fn upsert_provider_body(
    State(state): State<ApiState>,
    Json(value): Json<StoredProvider>,
) -> Result<Json<StoredProvider>, ApiError> {
    upsert_provider_inner(&state.store, value)
}
async fn upsert_provider(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(value): Json<StoredProvider>,
) -> Result<Json<StoredProvider>, ApiError> {
    if id != value.id {
        return Err(ApiError::BadRequest(
            "path id does not match payload id".into(),
        ));
    }
    upsert_provider_inner(&state.store, value)
}
fn upsert_provider_inner(
    store: &Store,
    value: StoredProvider,
) -> Result<Json<StoredProvider>, ApiError> {
    store.upsert_provider(&value).map_err(ApiError::store)?;
    Ok(Json(value))
}
async fn delete_provider(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    state.store.delete_provider(&id).map_err(ApiError::store)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn list_models(
    State(state): State<ApiState>,
    Query(query): Query<PageQuery>,
) -> Result<Json<Page<StoredModel>>, ApiError> {
    Ok(Json(page(
        state.store.list_models().map_err(ApiError::store)?,
        query,
    )))
}
async fn list_provider_models(
    State(state): State<ApiState>,
    Path(provider_id): Path<String>,
    Query(query): Query<PageQuery>,
) -> Result<Json<Page<StoredModel>>, ApiError> {
    if state
        .store
        .get_provider(&provider_id)
        .map_err(ApiError::store)?
        .is_none()
    {
        return Err(ApiError::not_found("provider"));
    }
    Ok(Json(page(
        state
            .store
            .list_models_by_provider(&provider_id)
            .map_err(ApiError::store)?,
        query,
    )))
}

async fn get_model(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<Json<StoredModel>, ApiError> {
    get_one(
        state.store.get_model(&id).map_err(ApiError::store)?,
        "model",
    )
}
async fn upsert_model_body(
    State(state): State<ApiState>,
    Json(value): Json<StoredModel>,
) -> Result<Json<StoredModel>, ApiError> {
    upsert_model_inner(&state.store, value)
}
async fn upsert_model(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(value): Json<StoredModel>,
) -> Result<Json<StoredModel>, ApiError> {
    if id != value.id {
        return Err(ApiError::BadRequest(
            "path id does not match payload id".into(),
        ));
    }
    upsert_model_inner(&state.store, value)
}
fn upsert_model_inner(store: &Store, value: StoredModel) -> Result<Json<StoredModel>, ApiError> {
    store.upsert_model(&value).map_err(ApiError::store)?;
    Ok(Json(value))
}
#[derive(Debug, Deserialize)]
struct SetEnabledRequest {
    enabled: bool,
}

async fn set_model_enabled(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(request): Json<SetEnabledRequest>,
) -> Result<StatusCode, ApiError> {
    if state
        .store
        .get_model(&id)
        .map_err(ApiError::store)?
        .is_none()
    {
        return Err(ApiError::not_found("model"));
    }
    state
        .store
        .set_model_enabled(&id, request.enabled)
        .map_err(ApiError::store)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn delete_model(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    state.store.delete_model(&id).map_err(ApiError::store)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn list_agents(
    State(state): State<ApiState>,
    Query(query): Query<PageQuery>,
) -> Result<Json<Page<StoredAgent>>, ApiError> {
    Ok(Json(page(
        state.store.list_agents().map_err(ApiError::store)?,
        query,
    )))
}
async fn get_default_agent(State(state): State<ApiState>) -> Result<Json<StoredAgent>, ApiError> {
    get_one(
        state.store.get_default_agent().map_err(ApiError::store)?,
        "default agent",
    )
}
async fn get_title_agent(State(state): State<ApiState>) -> Result<Json<StoredAgent>, ApiError> {
    get_one(
        state.store.get_title_agent().map_err(ApiError::store)?,
        "title agent",
    )
}

async fn get_agent(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<Json<StoredAgent>, ApiError> {
    get_one(
        state.store.get_agent(&id).map_err(ApiError::store)?,
        "agent",
    )
}
async fn upsert_agent_body(
    State(state): State<ApiState>,
    Json(value): Json<StoredAgent>,
) -> Result<Json<StoredAgent>, ApiError> {
    upsert_agent_inner(&state, value).await
}
async fn upsert_agent(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(value): Json<StoredAgent>,
) -> Result<Json<StoredAgent>, ApiError> {
    if id != value.id {
        return Err(ApiError::BadRequest(
            "path id does not match payload id".into(),
        ));
    }
    upsert_agent_inner(&state, value).await
}
async fn upsert_agent_inner(
    state: &ApiState,
    mut value: StoredAgent,
) -> Result<Json<StoredAgent>, ApiError> {
    let _guard = state.agent_mutations.lock().await;
    let existing = state.store.list_agents().map_err(ApiError::store)?;
    let current_default = existing
        .iter()
        .find(|agent| agent.is_default)
        .map(|agent| agent.id.clone());
    let desired_default = if value.is_default {
        value.id.clone()
    } else {
        current_default.unwrap_or_else(|| value.id.clone())
    };
    value.is_default = value.id == desired_default;

    let mut changed_ids = vec![value.id.clone()];
    state
        .store
        .with_tx(|tx| {
            for old in &existing {
                if old.id == value.id {
                    continue;
                }
                let desired = old.id == desired_default;
                if old.is_default != desired {
                    let mut normalized = old.clone();
                    normalized.is_default = desired;
                    tx.upsert_agent_n(&normalized)?;
                    changed_ids.push(old.id.clone());
                }
            }
            tx.upsert_agent_n(&value)?;
            Ok(())
        })
        .map_err(ApiError::store)?;
    changed_ids.sort();
    changed_ids.dedup();
    state.store.notify(messenger_store::StoreEvent {
        kind: messenger_store::EntityKind::Agent,
        ids: changed_ids,
    });
    Ok(Json(value))
}
async fn delete_agent(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    let _guard = state.agent_mutations.lock().await;
    let agent = state
        .store
        .get_agent(&id)
        .map_err(ApiError::store)?
        .ok_or_else(|| ApiError::not_found("agent"))?;
    if agent.is_default {
        return Err(ApiError::Conflict {
            code: "default_agent_protected".into(),
            message: "the default agent cannot be deleted".into(),
        });
    }
    state.store.delete_agent(&id).map_err(ApiError::store)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn list_projects(
    State(state): State<ApiState>,
    Query(query): Query<PageQuery>,
) -> Result<Json<Page<StoredProject>>, ApiError> {
    Ok(Json(page(
        state.store.list_projects().map_err(ApiError::store)?,
        query,
    )))
}
async fn get_project(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<Json<StoredProject>, ApiError> {
    get_one(
        state.store.get_project(&id).map_err(ApiError::store)?,
        "project",
    )
}
async fn upsert_project_body(
    State(state): State<ApiState>,
    Json(value): Json<StoredProject>,
) -> Result<Json<StoredProject>, ApiError> {
    upsert_project_inner(&state.store, value)
}
async fn upsert_project(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(value): Json<StoredProject>,
) -> Result<Json<StoredProject>, ApiError> {
    if id != value.id {
        return Err(ApiError::BadRequest(
            "path id does not match payload id".into(),
        ));
    }
    upsert_project_inner(&state.store, value)
}
fn upsert_project_inner(
    store: &Store,
    value: StoredProject,
) -> Result<Json<StoredProject>, ApiError> {
    store.upsert_project(&value).map_err(ApiError::store)?;
    Ok(Json(value))
}
async fn delete_project(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    state.store.delete_project(&id).map_err(ApiError::store)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn list_conversations(
    State(state): State<ApiState>,
    Query(query): Query<PageQuery>,
) -> Result<Json<Page<StoredConversation>>, ApiError> {
    Ok(Json(page(
        state.store.list_conversations().map_err(ApiError::store)?,
        query,
    )))
}
async fn list_agent_conversations(
    State(state): State<ApiState>,
    Path(agent_id): Path<String>,
    Query(query): Query<PageQuery>,
) -> Result<Json<Page<StoredConversation>>, ApiError> {
    if state
        .store
        .get_agent(&agent_id)
        .map_err(ApiError::store)?
        .is_none()
    {
        return Err(ApiError::not_found("agent"));
    }
    Ok(Json(page(
        state
            .store
            .list_conversations_by_agent(&agent_id)
            .map_err(ApiError::store)?,
        query,
    )))
}

async fn list_project_conversations(
    State(state): State<ApiState>,
    Path(project_id): Path<String>,
    Query(query): Query<PageQuery>,
) -> Result<Json<Page<StoredConversation>>, ApiError> {
    if state
        .store
        .get_project(&project_id)
        .map_err(ApiError::store)?
        .is_none()
    {
        return Err(ApiError::not_found("project"));
    }
    Ok(Json(page(
        state
            .store
            .list_conversations_by_project(&project_id)
            .map_err(ApiError::store)?,
        query,
    )))
}

async fn get_conversation(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<Json<StoredConversation>, ApiError> {
    get_one(
        state.store.get_conversation(&id).map_err(ApiError::store)?,
        "conversation",
    )
}
async fn upsert_conversation_body(
    State(state): State<ApiState>,
    Json(value): Json<StoredConversation>,
) -> Result<Json<StoredConversation>, ApiError> {
    upsert_conversation_inner(&state.store, value)
}
async fn upsert_conversation(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(value): Json<StoredConversation>,
) -> Result<Json<StoredConversation>, ApiError> {
    if id != value.id {
        return Err(ApiError::BadRequest(
            "path id does not match payload id".into(),
        ));
    }
    upsert_conversation_inner(&state.store, value)
}
fn upsert_conversation_inner(
    store: &Store,
    value: StoredConversation,
) -> Result<Json<StoredConversation>, ApiError> {
    upsert_conversation_checked(store, &value)?;
    Ok(Json(value))
}
fn upsert_conversation_checked(store: &Store, value: &StoredConversation) -> Result<(), ApiError> {
    if store
        .get_agent(&value.agent_id)
        .map_err(ApiError::store)?
        .is_none()
    {
        return Err(ApiError::BadRequest(
            "conversation references a missing agent".into(),
        ));
    }
    store.upsert_conversation(value).map_err(ApiError::store)
}
async fn delete_conversation(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    state
        .store
        .delete_conversation(&id)
        .map_err(ApiError::store)?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Debug, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct MessageQuery {
    limit: Option<usize>,
    before_id: Option<String>,
    from_id: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct MessagePage {
    items: Vec<StoredMessage>,
    total: usize,
    next_before_id: Option<String>,
}

async fn list_messages(
    State(state): State<ApiState>,
    Path(conversation_id): Path<String>,
    Query(query): Query<MessageQuery>,
) -> Result<Json<MessagePage>, ApiError> {
    if state
        .store
        .get_conversation(&conversation_id)
        .map_err(ApiError::store)?
        .is_none()
    {
        return Err(ApiError::not_found("conversation"));
    }
    let total = state
        .store
        .list_messages_by_conversation(&conversation_id)
        .map_err(ApiError::store)?
        .len();
    let limit = query.limit.unwrap_or(100).clamp(1, 500) as u32;
    let items = if let Some(from_id) = query.from_id.as_deref() {
        state
            .store
            .list_messages_from(&conversation_id, from_id)
            .map_err(ApiError::store)?
    } else if let Some(before_id) = query.before_id.as_deref() {
        state
            .store
            .list_messages_before(&conversation_id, before_id, limit)
            .map_err(ApiError::store)?
    } else {
        state
            .store
            .list_recent_messages_by_conversation(&conversation_id, limit)
            .map_err(ApiError::store)?
    };
    // For the first newest-page request, `items.first()` is the oldest row in
    // that page, so it is the correct cursor for the next older page. Once a
    // `beforeId`/`fromId` request is made, the caller owns paging; returning the
    // same first-row cursor here would make the next request repeat the page.
    let next_before_id =
        (query.before_id.is_none() && query.from_id.is_none() && items.len() == limit as usize)
            .then(|| items.first().map(|message| message.id.clone()))
            .flatten();
    Ok(Json(MessagePage {
        items,
        total,
        next_before_id,
    }))
}

async fn get_message(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<Json<StoredMessage>, ApiError> {
    get_one(
        state.store.get_message(&id).map_err(ApiError::store)?,
        "message",
    )
}
async fn upsert_message(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(value): Json<StoredMessage>,
) -> Result<Json<StoredMessage>, ApiError> {
    if id != value.id {
        return Err(ApiError::BadRequest(
            "path id does not match payload id".into(),
        ));
    }
    state
        .store
        .upsert_message(&value)
        .map_err(ApiError::store)?;
    Ok(Json(value))
}
async fn delete_message(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    state.store.delete_message(&id).map_err(ApiError::store)?;
    Ok(StatusCode::NO_CONTENT)
}

fn sync_engine(store: &Store) -> Result<SyncEngine<'_>, ApiError> {
    let cookie = store.kv_get(KV_SESSION).map_err(ApiError::store)?;
    let host = store.kv_get(KV_SESSION_HOST).map_err(ApiError::store)?;
    Ok(SyncEngine::new(store, Session { cookie, host }))
}

fn json<T: Serialize>(value: T) -> Result<Json<serde_json::Value>, ApiError> {
    serde_json::to_value(value)
        .map(Json)
        .map_err(ApiError::internal)
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CredentialsBody {
    email: String,
    password: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ServerUrlBody {
    url: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct MarkChangeBody {
    kind: String,
    id: String,
    #[serde(default)]
    deleted: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LegacyImportBody {
    legacy_db_dir: String,
    #[serde(default)]
    now_ms: Option<i64>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DiscoverModelsBody {
    base_url: String,
    api_key: String,
    api_format: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReplaceLocalBody {
    #[serde(default)]
    replace_local: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SyncWithBody {
    since: Option<i64>,
    #[serde(default)]
    replace_local: bool,
    expected_server_version: Option<i64>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CardBody {
    code: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BuiltinModelsBody {
    #[serde(default)]
    force: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PasswordBody {
    current_password: String,
    new_password: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CurrentPasswordBody {
    current_password: String,
}

#[derive(Debug, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct MarketQuery {
    #[serde(default)]
    query: String,
    cursor: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApplyMarketBody {
    market: messenger_sync::CloudMarketAgent,
}

async fn mark_change(
    State(state): State<ApiState>,
    Json(body): Json<MarkChangeBody>,
) -> Result<StatusCode, ApiError> {
    sync_engine(&state.store)?
        .request_local_change(&body.kind, &body.id, body.deleted)
        .map_err(ApiError::internal)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn import_legacy_data(
    State(state): State<ApiState>,
    Json(body): Json<LegacyImportBody>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let summary = messenger_store::import_legacy(
        &state.store,
        std::path::Path::new(&body.legacy_db_dir),
        body.now_ms.unwrap_or_else(now_ms),
    )
    .map_err(ApiError::internal)?;
    match summary {
        Some(summary) => json(serde_json::json!({
            "imported": true,
            "providers": summary.providers,
            "models": summary.models,
            "agents": summary.agents,
            "conversations": summary.conversations,
            "messages": summary.messages,
        })),
        None => json(serde_json::json!({ "imported": false })),
    }
}

async fn discover_models(
    State(state): State<ApiState>,
    Json(body): Json<DiscoverModelsBody>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let discovered =
        discover_provider_models(&body.base_url, &body.api_key, body.api_format.as_deref())
            .await
            .map_err(ApiError::BadRequest)?;
    let enriched = enrich_with_models_dev(&state.store, discovered).await;
    json(enriched)
}

async fn model_metadata(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let metadata = models_dev_metadata(&state.store, &id).await;
    json(metadata)
}

async fn cloud_login(
    State(state): State<ApiState>,
    Json(body): Json<CredentialsBody>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let user = sync_engine(&state.store)?
        .login(&body.email, &body.password)
        .await
        .map_err(ApiError::cloud)?;
    json(user)
}

async fn cloud_register(
    State(state): State<ApiState>,
    Json(body): Json<CredentialsBody>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let user = sync_engine(&state.store)?
        .register(&body.email, &body.password)
        .await
        .map_err(ApiError::cloud)?;
    json(user)
}

async fn cloud_logout(State(state): State<ApiState>) -> Result<StatusCode, ApiError> {
    sync_engine(&state.store)?
        .logout()
        .await
        .map_err(ApiError::cloud)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn cloud_refresh(State(state): State<ApiState>) -> Result<Json<serde_json::Value>, ApiError> {
    let user = sync_engine(&state.store)?
        .refresh_user()
        .await
        .map_err(ApiError::cloud)?;
    json(user)
}

async fn cloud_me(State(state): State<ApiState>) -> Result<Json<serde_json::Value>, ApiError> {
    json(sync_engine(&state.store)?.current_user())
}

async fn cloud_get_server_url(State(state): State<ApiState>) -> Json<String> {
    Json(
        sync_engine(&state.store)
            .map(|engine| engine.server_url())
            .unwrap_or_default(),
    )
}

async fn cloud_set_server_url(
    State(state): State<ApiState>,
    Json(body): Json<ServerUrlBody>,
) -> Result<StatusCode, ApiError> {
    sync_engine(&state.store)?
        .set_server_url(&body.url)
        .map_err(ApiError::internal)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn cloud_has_local_data(State(state): State<ApiState>) -> Result<Json<bool>, ApiError> {
    sync_engine(&state.store)?
        .has_local_data()
        .map(Json)
        .map_err(ApiError::internal)
}

async fn cloud_sync(
    State(state): State<ApiState>,
    Json(body): Json<ReplaceLocalBody>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let result = sync_engine(&state.store)?
        .sync_internal(None, body.replace_local, None)
        .await
        .map_err(ApiError::cloud)?;
    json(result)
}

async fn cloud_sync_with(
    State(state): State<ApiState>,
    Json(body): Json<SyncWithBody>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let result = sync_engine(&state.store)?
        .sync_internal(body.since, body.replace_local, body.expected_server_version)
        .await
        .map_err(ApiError::cloud)?;
    json(result)
}

async fn cloud_push_pending(
    State(state): State<ApiState>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let result = sync_engine(&state.store)?
        .push_pending_changes()
        .await
        .map_err(ApiError::cloud)?;
    json(result)
}

async fn cloud_push_snapshot(State(state): State<ApiState>) -> Result<Json<i64>, ApiError> {
    let version = sync_engine(&state.store)?
        .push_snapshot()
        .await
        .map_err(ApiError::cloud)?;
    Ok(Json(version))
}

async fn cloud_replace_cloud_with_local(
    State(state): State<ApiState>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let result = sync_engine(&state.store)?
        .replace_cloud_with_local()
        .await
        .map_err(ApiError::cloud)?;
    json(result)
}

async fn cloud_preview_card(
    State(state): State<ApiState>,
    Json(body): Json<CardBody>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let card = sync_engine(&state.store)?
        .preview_redeem_card(&body.code)
        .await
        .map_err(ApiError::cloud)?;
    json(card)
}

async fn cloud_redeem_card(
    State(state): State<ApiState>,
    Json(body): Json<CardBody>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let result = sync_engine(&state.store)?
        .redeem_card(&body.code)
        .await
        .map_err(ApiError::cloud)?;
    json(result)
}

async fn cloud_sync_builtin_models(
    State(state): State<ApiState>,
    Json(body): Json<BuiltinModelsBody>,
) -> Result<Json<usize>, ApiError> {
    let count = sync_engine(&state.store)?
        .sync_builtin_provider_models(body.force)
        .await
        .map_err(ApiError::BadRequest)?;
    Ok(Json(count))
}

async fn cloud_ensure_title_agent(State(state): State<ApiState>) -> Result<StatusCode, ApiError> {
    sync_engine(&state.store)?
        .ensure_title_agent()
        .map_err(ApiError::internal)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn cloud_change_password(
    State(state): State<ApiState>,
    Json(body): Json<PasswordBody>,
) -> Result<StatusCode, ApiError> {
    sync_engine(&state.store)?
        .change_password(&body.current_password, &body.new_password)
        .await
        .map_err(ApiError::cloud)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn cloud_delete_account(
    State(state): State<ApiState>,
    Json(body): Json<CurrentPasswordBody>,
) -> Result<StatusCode, ApiError> {
    sync_engine(&state.store)?
        .delete_account(&body.current_password)
        .await
        .map_err(ApiError::cloud)?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CacheAvatarBody {
    scope: String,
    account_id: String,
    id: String,
    url: String,
    version: Option<String>,
    dest_dir: String,
}

async fn cloud_cache_avatar(
    State(state): State<ApiState>,
    Json(body): Json<CacheAvatarBody>,
) -> Result<Json<String>, ApiError> {
    let engine =
        sync_engine(&state.store)?.with_avatars_dir(std::path::PathBuf::from(body.dest_dir));
    let path = engine
        .cache_avatar(
            &body.scope,
            &body.account_id,
            &body.id,
            &body.url,
            body.version.as_deref(),
        )
        .await
        .map_err(ApiError::cloud)?;
    Ok(Json(path))
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AvatarUploadBody {
    bytes_base64: String,
    filename: String,
    mime: String,
}

fn decode_avatar(body: AvatarUploadBody) -> Result<(Vec<u8>, String, String), ApiError> {
    use base64::Engine;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(body.bytes_base64)
        .map_err(|error| ApiError::BadRequest(error.to_string()))?;
    Ok((bytes, body.filename, body.mime))
}

async fn cloud_upload_user_avatar(
    State(state): State<ApiState>,
    Json(body): Json<AvatarUploadBody>,
) -> Result<Json<String>, ApiError> {
    let (bytes, filename, mime) = decode_avatar(body)?;
    let url = sync_engine(&state.store)?
        .upload_user_avatar(bytes, &filename, &mime)
        .await
        .map_err(ApiError::cloud)?;
    Ok(Json(url))
}

async fn cloud_delete_user_avatar(State(state): State<ApiState>) -> Result<Json<String>, ApiError> {
    let url = sync_engine(&state.store)?
        .delete_user_avatar()
        .await
        .map_err(ApiError::cloud)?;
    Ok(Json(url))
}

async fn cloud_upload_agent_avatar(
    State(state): State<ApiState>,
    Path(agent_id): Path<String>,
    Json(body): Json<AvatarUploadBody>,
) -> Result<Json<String>, ApiError> {
    let (bytes, filename, mime) = decode_avatar(body)?;
    let url = sync_engine(&state.store)?
        .upload_agent_avatar(&agent_id, bytes, &filename, &mime)
        .await
        .map_err(ApiError::cloud)?;
    Ok(Json(url))
}

async fn cloud_delete_agent_avatar(
    State(state): State<ApiState>,
    Path(agent_id): Path<String>,
) -> Result<Json<String>, ApiError> {
    let url = sync_engine(&state.store)?
        .delete_agent_avatar(&agent_id)
        .await
        .map_err(ApiError::cloud)?;
    Ok(Json(url))
}

async fn cloud_list_market_agents(
    State(state): State<ApiState>,
    Query(query): Query<MarketQuery>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let result = sync_engine(&state.store)?
        .list_market_agents(&query.query, query.cursor.as_deref())
        .await
        .map_err(ApiError::cloud)?;
    json(result)
}

async fn cloud_get_market_agent(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let agent = sync_engine(&state.store)?
        .get_market_agent(&id)
        .await
        .map_err(ApiError::cloud)?;
    json(agent)
}

async fn cloud_publish_market_agent(
    State(state): State<ApiState>,
    Path(agent_id): Path<String>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let agent = sync_engine(&state.store)?
        .publish_market_agent(&agent_id)
        .await
        .map_err(ApiError::cloud)?;
    json(agent)
}

async fn cloud_import_market_agent(
    State(state): State<ApiState>,
    Path(market_id): Path<String>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let agent = sync_engine(&state.store)?
        .import_market_agent(&market_id)
        .await
        .map_err(ApiError::cloud)?;
    json(agent)
}

async fn cloud_import_market_agent_with_avatar(
    State(state): State<ApiState>,
    Path(market_id): Path<String>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let agent = sync_engine(&state.store)?
        .import_market_agent_with_avatar(&market_id)
        .await
        .map_err(ApiError::cloud)?;
    json(agent)
}

async fn cloud_push_market_agent_update(
    State(state): State<ApiState>,
    Path(agent_id): Path<String>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let agent = sync_engine(&state.store)?
        .push_market_agent_update(&agent_id)
        .await
        .map_err(ApiError::cloud)?;
    json(agent)
}

async fn cloud_check_market_agent_update(
    State(state): State<ApiState>,
    Path(agent_id): Path<String>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let update = sync_engine(&state.store)?
        .check_market_agent_update(&agent_id)
        .await
        .map_err(ApiError::cloud)?;
    json(update)
}

async fn cloud_apply_market_agent_update(
    State(state): State<ApiState>,
    Path(agent_id): Path<String>,
    Json(body): Json<ApplyMarketBody>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let agent = sync_engine(&state.store)?
        .apply_market_agent_update(&agent_id, &body.market)
        .await
        .map_err(ApiError::cloud)?;
    json(agent)
}

async fn cloud_remove_market_agent(
    State(state): State<ApiState>,
    Path(agent_id): Path<String>,
) -> Result<StatusCode, ApiError> {
    sync_engine(&state.store)?
        .remove_market_agent(&agent_id)
        .await
        .map_err(ApiError::cloud)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn cloud_clear_market_links(State(state): State<ApiState>) -> Result<StatusCode, ApiError> {
    sync_engine(&state.store)?
        .clear_market_links_and_builtin_provider()
        .map_err(ApiError::cloud)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn clear_local_data(State(state): State<ApiState>) -> Result<StatusCode, ApiError> {
    for provider in state.store.list_providers().map_err(ApiError::store)? {
        state
            .store
            .delete_provider(&provider.id)
            .map_err(ApiError::store)?;
    }
    for conversation in state.store.list_conversations().map_err(ApiError::store)? {
        state
            .store
            .delete_messages_by_conversation(&conversation.id)
            .map_err(ApiError::store)?;
        state
            .store
            .delete_conversation(&conversation.id)
            .map_err(ApiError::store)?;
    }
    state
        .store
        .clear_agents_for_reinit()
        .map_err(ApiError::store)?;
    for project in state.store.list_projects().map_err(ApiError::store)? {
        state
            .store
            .delete_project(&project.id)
            .map_err(ApiError::store)?;
    }
    for key in [
        KV_SESSION,
        KV_SESSION_HOST,
        messenger_sync::KV_USER,
        "current_agent_id",
    ] {
        state.store.kv_delete(key).map_err(ApiError::store)?;
    }
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LastMessageBody {
    last_message: Option<String>,
    updated_at: i64,
}

async fn update_conversation_last_message(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(body): Json<LastMessageBody>,
) -> Result<StatusCode, ApiError> {
    if state
        .store
        .get_conversation(&id)
        .map_err(ApiError::store)?
        .is_none()
    {
        return Err(ApiError::not_found("conversation"));
    }
    state
        .store
        .update_conversation_last_message(&id, body.last_message.as_deref(), body.updated_at)
        .map_err(ApiError::store)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn delete_conversation_messages(
    State(state): State<ApiState>,
    Path(conversation_id): Path<String>,
) -> Result<StatusCode, ApiError> {
    if state
        .store
        .get_conversation(&conversation_id)
        .map_err(ApiError::store)?
        .is_none()
    {
        return Err(ApiError::not_found("conversation"));
    }
    state
        .store
        .delete_messages_by_conversation(&conversation_id)
        .map_err(ApiError::store)?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Debug, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct SweepBody {
    #[serde(default)]
    exclude_ids: Vec<String>,
}

async fn sweep_empty_conversations(
    State(state): State<ApiState>,
    Json(body): Json<SweepBody>,
) -> Result<Json<Vec<String>>, ApiError> {
    let signed_in = sync_engine(&state.store)?.current_user().is_some();
    let deleted = messenger_core::conversation::sweep_empty_conversations(
        &state.store,
        now_ms(),
        messenger_core::conversation::DEFAULT_GRACE_PERIOD_MS,
        &body.exclude_ids,
        |id| {
            state
                .store
                .delete_messages_by_conversation(id)
                .map_err(|error| error.to_string())?;
            state
                .store
                .delete_conversation(id)
                .map_err(|error| error.to_string())?;
            if signed_in {
                let _ = sync_engine(&state.store).and_then(|engine| {
                    engine
                        .request_local_change("conversation", id, true)
                        .map_err(ApiError::internal)
                });
            }
            Ok(())
        },
    )
    .map_err(ApiError::internal)?;
    Ok(Json(deleted))
}

async fn kv_get(
    State(state): State<ApiState>,
    Path(key): Path<String>,
) -> Result<Json<Option<String>>, ApiError> {
    state.store.kv_get(&key).map(Json).map_err(ApiError::store)
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct KvValueBody {
    value: String,
}

async fn kv_set(
    State(state): State<ApiState>,
    Path(key): Path<String>,
    Json(body): Json<KvValueBody>,
) -> Result<StatusCode, ApiError> {
    state
        .store
        .kv_set(&key, &body.value)
        .map_err(ApiError::store)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn kv_delete(
    State(state): State<ApiState>,
    Path(key): Path<String>,
) -> Result<StatusCode, ApiError> {
    state.store.kv_delete(&key).map_err(ApiError::store)?;
    Ok(StatusCode::NO_CONTENT)
}

fn get_one<T>(value: Option<T>, kind: &'static str) -> Result<Json<T>, ApiError> {
    value.map(Json).ok_or_else(|| ApiError::not_found(kind))
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatRequest {
    /// Required for `send`; retry/regenerate reuse the persisted message.
    pub content: Option<String>,
    #[serde(default)]
    pub operation: ChatOperation,
    pub message_id: Option<String>,
    #[serde(default)]
    pub tool_specs: Vec<ToolSpecRequest>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolSpecRequest {
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default = "empty_parameters")]
    pub parameters_json: String,
}

fn empty_parameters() -> String {
    r#"{"type":"object","properties":{}}"#.into()
}

#[derive(Debug, Clone, Copy, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum ChatOperation {
    #[default]
    Send,
    Retry,
    Regenerate,
    Continue,
}

async fn send_message(
    State(state): State<ApiState>,
    Path(conversation_id): Path<String>,
    Json(request): Json<ChatRequest>,
) -> Result<Sse<impl Stream<Item = Result<Event, Infallible>>>, ApiError> {
    let conversation = state
        .store
        .get_conversation(&conversation_id)
        .map_err(ApiError::store)?
        .ok_or_else(|| ApiError::not_found("conversation"))?;
    let agent = state
        .store
        .get_agent(&conversation.agent_id)
        .map_err(ApiError::store)?
        .ok_or_else(|| ApiError::not_found("agent"))?;
    let model_id = conversation
        .override_model_id
        .clone()
        .or(agent.default_model_id.clone());
    let Some(model_id) = model_id else {
        return Err(ApiError::Conflict {
            code: "model_required".into(),
            message: "set a model for this agent before sending a message".into(),
        });
    };
    match request.operation {
        ChatOperation::Send
            if request
                .content
                .as_deref()
                .is_none_or(|content| content.trim().is_empty()) =>
        {
            return Err(ApiError::BadRequest("content is required for send".into()));
        }
        ChatOperation::Retry | ChatOperation::Regenerate if request.message_id.is_none() => {
            return Err(ApiError::BadRequest(
                "messageId is required for retry/regenerate".into(),
            ));
        }
        _ => {}
    }
    let model = state
        .store
        .get_model(&model_id)
        .map_err(ApiError::store)?
        .ok_or_else(|| ApiError::not_found("model"))?;
    let provider = state
        .store
        .get_provider(&model.provider_id)
        .map_err(ApiError::store)?
        .ok_or_else(|| ApiError::not_found("provider"))?;
    let (message_to_insert, messages_to_delete) = match request.operation {
        ChatOperation::Send => {
            let content = request
                .content
                .as_deref()
                .expect("send content validated")
                .trim();
            (
                Some(StoredMessage {
                    id: Uuid::new_v4().to_string(),
                    conversation_id: conversation_id.clone(),
                    role: "user".into(),
                    content: content.into(),
                    parts_json: None,
                    timestamp: now_ms(),
                    status: "sent".into(),
                    error_message: None,
                }),
                Vec::new(),
            )
        }
        ChatOperation::Retry => {
            let id = request.message_id.as_deref().expect("message id validated");
            let message = state
                .store
                .get_message(id)
                .map_err(ApiError::store)?
                .ok_or_else(|| ApiError::not_found("message"))?;
            if message.conversation_id != conversation_id {
                return Err(ApiError::BadRequest(
                    "message does not belong to this conversation".into(),
                ));
            }
            if message.role != "assistant" || message.status != "error" {
                return Err(ApiError::Conflict {
                    code: "message_not_retryable".into(),
                    message: "only a failed assistant message can be retried".into(),
                });
            }
            (None, vec![id.to_string()])
        }
        ChatOperation::Continue => (None, Vec::new()),
        ChatOperation::Regenerate => {
            let id = request.message_id.as_deref().expect("message id validated");
            let selected = state
                .store
                .get_message(id)
                .map_err(ApiError::store)?
                .ok_or_else(|| ApiError::not_found("message"))?;
            if selected.conversation_id != conversation_id {
                return Err(ApiError::BadRequest(
                    "message does not belong to this conversation".into(),
                ));
            }
            if selected.role != "assistant" {
                return Err(ApiError::Conflict {
                    code: "message_not_regeneratable".into(),
                    message: "only an assistant message can be regenerated".into(),
                });
            }
            let messages = state
                .store
                .list_messages_by_conversation(&conversation_id)
                .map_err(ApiError::store)?;
            let start = messages
                .iter()
                .position(|message| message.id == id)
                .ok_or_else(|| ApiError::not_found("message"))?;
            let mut ids = Vec::new();
            for message in messages.iter().skip(start) {
                if message.id != id && message.role == "user" {
                    break;
                }
                if message.role == "assistant" || message.role == "tool" {
                    ids.push(message.id.clone());
                }
            }
            (None, ids)
        }
    };

    let cancel = CancellationToken::new();
    {
        let mut active = state.active_turns.lock().await;
        if active.contains_key(&conversation_id) {
            return Err(ApiError::Conflict {
                code: "turn_in_progress".into(),
                message: "a chat turn is already active for this conversation".into(),
            });
        }
        active.insert(conversation_id.clone(), cancel.clone());
    }

    let mutation = if let Some(message) = message_to_insert {
        let timestamp = message.timestamp;
        let last_message = message.content.clone();
        let message_id = message.id.clone();
        state
            .store
            .with_tx(|tx| {
                tx.upsert_message_n(&message)?;
                tx.update_conversation_last_message_n(
                    &conversation_id,
                    Some(&last_message),
                    timestamp,
                )?;
                Ok(())
            })
            .map_err(ApiError::store)
            .map(|()| {
                state.store.notify(messenger_store::StoreEvent {
                    kind: messenger_store::EntityKind::Message,
                    ids: vec![message_id],
                });
                state.store.notify(messenger_store::StoreEvent {
                    kind: messenger_store::EntityKind::Conversation,
                    ids: vec![conversation_id.clone()],
                });
            })
    } else if !messages_to_delete.is_empty() {
        let ids = messages_to_delete.clone();
        state
            .store
            .with_tx(|tx| {
                for id in &ids {
                    tx.delete_message_n(id)?;
                }
                Ok(())
            })
            .map_err(ApiError::store)
            .map(|()| {
                state.store.notify(messenger_store::StoreEvent {
                    kind: messenger_store::EntityKind::Message,
                    ids,
                });
            })
    } else {
        Ok(())
    };
    if let Err(error) = mutation {
        state.active_turns.lock().await.remove(&conversation_id);
        return Err(error);
    }

    let (tx, rx) = mpsc::unbounded_channel::<String>();
    let task_state = state.clone();
    let task_conversation = conversation.clone();
    let task_agent = agent.clone();
    let task_model = model.clone();
    let task_provider = provider.clone();
    let task_request = request.clone();
    let task_cancel = cancel.clone();
    tokio::spawn(async move {
        let result = run_turn(TurnTask {
            state: task_state.clone(),
            conversation: task_conversation,
            agent: task_agent,
            model: task_model,
            provider: task_provider,
            request: task_request,
            cancel: task_cancel,
            tx: tx.clone(),
        })
        .await;
        if let Err(error) = result {
            let detail = match &error {
                ApiError::Internal(detail) => detail.clone(),
                other => format!("{other:?}"),
            };
            let failed = StoredMessage {
                id: Uuid::new_v4().to_string(),
                conversation_id: conversation_id.clone(),
                role: "assistant".into(),
                content: String::new(),
                parts_json: None,
                timestamp: now_ms(),
                status: "error".into(),
                error_message: Some(detail.clone()),
            };
            let _ = task_state.store.upsert_message(&failed);
            let _ = tx.send(event_json(AgentEvent::Error {
                code: "core_error".into(),
                message: detail,
            }));
        }
        task_state
            .active_turns
            .lock()
            .await
            .remove(&conversation_id);
    });
    Ok(Sse::new(CancelOnDropStream::new(rx, cancel)).keep_alive(KeepAlive::default()))
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ToolResultBody {
    output: String,
    #[serde(default)]
    is_error: bool,
}

async fn resolve_tool_call(
    State(state): State<ApiState>,
    Path(call_id): Path<String>,
    Json(body): Json<ToolResultBody>,
) -> Result<StatusCode, ApiError> {
    let sender = state
        .pending_tools
        .lock()
        .await
        .remove(&call_id)
        .ok_or_else(|| ApiError::not_found("pending tool call"))?;
    sender
        .send(ToolExecutionResult {
            output: body.output,
            is_error: body.is_error,
        })
        .map_err(|_| ApiError::Conflict {
            code: "tool_call_closed".into(),
            message: "the chat turn is no longer waiting for this tool result".into(),
        })?;
    Ok(StatusCode::ACCEPTED)
}

async fn cancel_turn(
    State(state): State<ApiState>,
    Path(conversation_id): Path<String>,
) -> Result<StatusCode, ApiError> {
    if let Some(token) = state
        .active_turns
        .lock()
        .await
        .get(&conversation_id)
        .cloned()
    {
        token.cancel();
        Ok(StatusCode::ACCEPTED)
    } else {
        Err(ApiError::not_found("active turn"))
    }
}

struct TurnTask {
    state: ApiState,
    conversation: StoredConversation,
    agent: StoredAgent,
    model: StoredModel,
    provider: StoredProvider,
    request: ChatRequest,
    cancel: CancellationToken,
    tx: mpsc::UnboundedSender<String>,
}

async fn run_turn(task: TurnTask) -> Result<(), ApiError> {
    let TurnTask {
        state,
        conversation,
        agent,
        model,
        provider,
        request,
        cancel,
        tx,
    } = task;
    let tools = configured_tools(
        &agent,
        conversation.writable,
        conversation.project_id.is_some(),
        &request.tool_specs,
    );
    let title = state
        .store
        .get_title_agent()
        .map_err(ApiError::store)?
        .and_then(|title| {
            title.default_model_id.map(|model_id| TitleConfig {
                agent_id: title.id,
                system_prompt: title.system_prompt,
                model_id: Some(model_id),
                temperature: title.temperature,
                top_p: title.top_p,
                max_tokens: title.max_tokens,
            })
        });
    let turn = TurnRequest {
        conversation_id: conversation.id,
        model_id: model.model_id,
        base_url: provider.base_url,
        api_key: provider.api_key,
        api_format: ApiFormat::from_store(Some(provider.api_format.as_str())),
        system_prompt: agent.system_prompt,
        temperature: conversation.override_temperature.or(agent.temperature),
        top_p: conversation.override_top_p.or(agent.top_p),
        max_tokens: conversation.override_max_tokens.or(agent.max_tokens),
        reasoning_effort: conversation
            .override_reasoning_effort
            .or(agent.reasoning_effort),
        tools,
        workspace_note: String::new(),
        context_window: model.context_window,
        summarize_prompt:
            "Summarize the conversation while preserving decisions, facts, and open tasks.".into(),
        title,
    };
    let sink = ChannelSink { tx: tx.clone() };
    let host = RemoteToolHost {
        pending: Arc::clone(&state.pending_tools),
        events: tx,
        cancel: cancel.clone(),
    };
    let _ = request;
    run_chat_turn(&state.store, &host, &sink, &turn, cancel)
        .await
        .map_err(ApiError::Internal)
}

fn configured_tools(
    agent: &StoredAgent,
    writable: bool,
    has_workspace: bool,
    requested: &[ToolSpecRequest],
) -> Vec<messenger_tools::BuiltinTool> {
    if !agent.tools_enabled {
        return Vec::new();
    }
    let config =
        serde_json::from_str::<HashMap<String, bool>>(&agent.tools_config).unwrap_or_default();
    let mut tools: Vec<_> = messenger_tools::builtin_registry()
        .into_iter()
        .filter(|tool| config.get(&tool.name).copied().unwrap_or(true))
        .filter(|tool| has_workspace || !tool.workspace_required)
        .collect();
    for spec in requested {
        if config.get(&spec.name).copied() == Some(false) {
            continue;
        }
        if tools.iter().any(|tool| tool.name == spec.name) {
            continue;
        }
        tools.push(messenger_tools::BuiltinTool {
            name: spec.name.clone(),
            description: spec.description.clone(),
            write_access: false,
            parameters_json: spec.parameters_json.clone(),
            read_only: false,
            workspace_required: false,
        });
    }
    apply_writable_mode(tools, writable)
}

struct RemoteToolHost {
    pending: Arc<Mutex<HashMap<String, oneshot::Sender<ToolExecutionResult>>>>,
    events: mpsc::UnboundedSender<String>,
    cancel: CancellationToken,
}

#[async_trait]
impl ToolHost for RemoteToolHost {
    async fn execute(
        &self,
        call_id: &str,
        name: &str,
        arguments_json: &str,
    ) -> ToolExecutionResult {
        let (sender, receiver) = oneshot::channel();
        self.pending
            .lock()
            .await
            .insert(call_id.to_string(), sender);
        let request = serde_json::json!({
            "type": "ToolCallRequested",
            "callId": call_id,
            "name": name,
            "argumentsJson": arguments_json,
        })
        .to_string();
        if self.events.send(request).is_err() {
            self.pending.lock().await.remove(call_id);
            return ToolExecutionResult {
                output: "chat stream closed before tool execution".into(),
                is_error: true,
            };
        }
        tokio::select! {
            result = receiver => result.unwrap_or(ToolExecutionResult {
                output: "tool execution was cancelled".into(),
                is_error: true,
            }),
            _ = self.cancel.cancelled() => {
                self.pending.lock().await.remove(call_id);
                ToolExecutionResult {
                    output: "tool execution was cancelled".into(),
                    is_error: true,
                }
            }
        }
    }
}

struct ChannelSink {
    tx: mpsc::UnboundedSender<String>,
}
impl TurnSink for ChannelSink {
    fn on_event(&self, event: &AgentEvent) {
        let _ = self.tx.send(event_json(event.clone()));
    }
}

fn event_json(event: AgentEvent) -> String {
    match event {
        AgentEvent::TurnStarted => serde_json::json!({"type":"TurnStarted"}).to_string(),
        AgentEvent::StreamingStarted { message_id } => serde_json::json!({"type":"StreamingStarted","messageId":message_id}).to_string(),
        AgentEvent::TextDelta { round, text } => serde_json::json!({"type":"TextDelta","round":round,"text":text}).to_string(),
        AgentEvent::ReasoningFormatDetected { format } => serde_json::json!({"type":"ReasoningFormatDetected","format":format}).to_string(),
        AgentEvent::RoundPersisted { round, message_id } => serde_json::json!({"type":"RoundPersisted","round":round,"messageId":message_id}).to_string(),
        AgentEvent::FinalMessagePersisted { message_id, content } => serde_json::json!({"type":"FinalMessagePersisted","messageId":message_id,"content":content}).to_string(),
        AgentEvent::ToolCallStarted { call_id, name } => serde_json::json!({"type":"ToolCallStarted","callId":call_id,"name":name}).to_string(),
        AgentEvent::ToolCallFinished { call_id, name, output, is_error } => serde_json::json!({"type":"ToolCallFinished","callId":call_id,"name":name,"output":output,"isError":is_error}).to_string(),
        AgentEvent::UsageRecorded { prompt_tokens, completion_tokens, cached_tokens } => serde_json::json!({"type":"UsageRecorded","promptTokens":prompt_tokens,"completionTokens":completion_tokens,"cachedTokens":cached_tokens}).to_string(),
        AgentEvent::TitleGenerated { title } => serde_json::json!({"type":"TitleGenerated","title":title}).to_string(),
        AgentEvent::TitleFailed { code } => serde_json::json!({"type":"TitleFailed","code":code}).to_string(),
        AgentEvent::Finished { message_id } => serde_json::json!({"type":"Finished","messageId":message_id}).to_string(),
        AgentEvent::Cancelled => serde_json::json!({"type":"Cancelled"}).to_string(),
        AgentEvent::Error { code, message } => serde_json::json!({"type":"Error","code":code,"message":message}).to_string(),
    }
}

struct CancelOnDropStream {
    rx: UnboundedReceiverStream<String>,
    cancel: CancellationToken,
}
impl CancelOnDropStream {
    fn new(rx: mpsc::UnboundedReceiver<String>, cancel: CancellationToken) -> Self {
        Self {
            rx: UnboundedReceiverStream::new(rx),
            cancel,
        }
    }
}
impl Stream for CancelOnDropStream {
    type Item = Result<Event, Infallible>;
    fn poll_next(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Option<Self::Item>> {
        std::pin::Pin::new(&mut self.rx)
            .poll_next(cx)
            .map(|item| item.map(|data| Ok(Event::default().event("message").data(data))))
    }
}
impl Drop for CancelOnDropStream {
    fn drop(&mut self) {
        self.cancel.cancel();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    fn test_state() -> ApiState {
        ApiState::new(Arc::new(Store::open_memory().unwrap()), "test-token")
    }
    fn request(method: axum::http::Method, uri: &str, body: Option<&str>) -> Request<Body> {
        let mut builder = Request::builder()
            .method(method)
            .uri(uri)
            .header("authorization", "Bearer test-token");
        if body.is_some() {
            builder = builder.header("content-type", "application/json");
        }
        builder
            .body(Body::from(body.unwrap_or_default().to_owned()))
            .unwrap()
    }

    #[tokio::test]
    async fn health_is_public_and_meta_requires_token() {
        let app = router(test_state());
        let health = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/api/v1/health")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(health.status(), StatusCode::OK);
        let unauthorized = app
            .oneshot(
                Request::builder()
                    .uri("/api/v1/meta")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(unauthorized.status(), StatusCode::UNAUTHORIZED);
    }

    fn agent_json(id: &str, is_default: bool) -> String {
        serde_json::json!({
            "id": id,
            "name": id,
            "avatar": null,
            "system_prompt": "help",
            "description": "",
            "default_model_id": null,
            "temperature": null,
            "top_p": null,
            "max_tokens": null,
            "reasoning_effort": null,
            "is_default": is_default,
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
            "tools_enabled": false,
            "tools_follow_default": false,
            "tools_config": "",
            "created_at": 1,
            "updated_at": 1
        })
        .to_string()
    }

    #[tokio::test]
    async fn agent_upsert_keeps_one_default_and_delete_protects_it() {
        let state = test_state();
        let app = router(state.clone());
        for (id, is_default) in [("a1", false), ("a2", true)] {
            let response = app
                .clone()
                .oneshot(request(
                    axum::http::Method::PUT,
                    &format!("/api/v1/agents/{id}"),
                    Some(&agent_json(id, is_default)),
                ))
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::OK);
        }
        let agents = state.store.list_agents().unwrap();
        assert_eq!(agents.iter().filter(|agent| agent.is_default).count(), 1);
        assert_eq!(state.store.get_default_agent().unwrap().unwrap().id, "a2");
        let response = app
            .oneshot(request(
                axum::http::Method::DELETE,
                "/api/v1/agents/a2",
                None,
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::CONFLICT);
        assert!(state.store.get_default_agent().unwrap().is_some());
    }

    #[tokio::test]
    async fn bound_server_is_loopback_only_and_uses_fresh_bearer_auth() {
        let store = Arc::new(Store::open_memory().unwrap());
        let rejected =
            ServerHandle::start(Arc::clone(&store), SocketAddr::from(([0, 0, 0, 0], 0))).await;
        assert!(
            matches!(rejected, Err(error) if error.kind() == std::io::ErrorKind::PermissionDenied)
        );

        let server = ServerHandle::start_local(store).await.unwrap();
        assert!(server.local_addr.ip().is_loopback());
        assert!(server.token.starts_with("messenger-"));
        let base = format!("http://{}{}", server.local_addr, API_PREFIX);
        let client = reqwest::Client::new();
        let unauthorized = client.get(format!("{base}/meta")).send().await.unwrap();
        assert_eq!(unauthorized.status(), StatusCode::UNAUTHORIZED);
        let authorized = client
            .get(format!("{base}/meta"))
            .bearer_auth(&server.token)
            .send()
            .await
            .unwrap();
        assert_eq!(authorized.status(), StatusCode::OK);
        assert_eq!(
            authorized.json::<serde_json::Value>().await.unwrap()["apiVersion"],
            "v1"
        );
        let handle = server;
        handle.stop().await.unwrap();
    }

    #[tokio::test]
    async fn dropping_sse_stream_cancels_turn_token() {
        let cancel = CancellationToken::new();
        let (_sender, receiver) = mpsc::unbounded_channel();
        let stream = CancelOnDropStream::new(receiver, cancel.clone());
        drop(stream);
        assert!(cancel.is_cancelled());
    }

    fn seed_text_chat(state: &ApiState, base_url: &str) {
        state
            .store
            .upsert_provider(&StoredProvider {
                id: "p1".into(),
                name: "test".into(),
                base_url: base_url.into(),
                api_key: "sk".into(),
                api_format: "chat".into(),
                created_at: 1,
                updated_at: 1,
            })
            .unwrap();
        state
            .store
            .upsert_model(&StoredModel {
                id: "m1".into(),
                provider_id: "p1".into(),
                model_id: "test-model".into(),
                display_name: "test".into(),
                is_enabled: true,
                context_window: 0,
                input_rate: None,
                output_rate: None,
                cache_read_rate: None,
                input_modalities: "text".into(),
                output_modalities: "text".into(),
                supports_tool_calling: false,
                supports_thinking: false,
                supports_json_output: false,
                supports_temperature: true,
                created_at: 1,
            })
            .unwrap();
        state
            .store
            .upsert_agent(&StoredAgent {
                id: "a1".into(),
                name: "agent".into(),
                avatar: None,
                system_prompt: "help".into(),
                description: String::new(),
                default_model_id: Some("m1".into()),
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
                tools_enabled: false,
                tools_follow_default: false,
                tools_config: String::new(),
                created_at: 1,
                updated_at: 1,
            })
            .unwrap();
        state
            .store
            .upsert_conversation(&StoredConversation {
                id: "c1".into(),
                title: "chat".into(),
                provider_id: "p1".into(),
                agent_id: "a1".into(),
                project_id: None,
                override_model_id: None,
                override_temperature: None,
                override_top_p: None,
                override_max_tokens: None,
                override_reasoning_effort: None,
                override_tools_enabled: None,
                override_tools_config: None,
                writable: false,
                created_at: 1,
                updated_at: 1,
                last_message: None,
                reasoning_format: None,
                context_summary: None,
                context_summary_until: 0,
                context_tokens: 0,
                context_tokens_at: 0,
            })
            .unwrap();
    }

    #[tokio::test]
    async fn chat_sse_runs_the_agent_and_persists_the_reply() {
        let provider = wiremock::MockServer::start().await;
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .respond_with(wiremock::ResponseTemplate::new(200).set_body_string(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
            ))
            .mount(&provider)
            .await;
        let state = test_state();
        seed_text_chat(&state, &provider.uri());
        let response = router(state.clone())
            .oneshot(request(
                axum::http::Method::POST,
                "/api/v1/conversations/c1/messages",
                Some(r#"{"content":"Hi"}"#),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let text = String::from_utf8(body.to_vec()).unwrap();
        assert!(text.contains("\"type\":\"TextDelta\""));
        assert!(text.contains("\"type\":\"Finished\""));
        let messages = state.store.list_messages_by_conversation("c1").unwrap();
        assert_eq!(messages.len(), 2);
        assert_eq!(messages[0].role, "user");
        assert_eq!(messages[1].role, "assistant");
        assert_eq!(messages[1].content, "Hello");
        assert_eq!(messages[1].status, "sent");
    }

    #[tokio::test]
    async fn truncated_provider_stream_emits_sse_error_and_persists_error_row() {
        let provider = wiremock::MockServer::start().await;
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .respond_with(
                wiremock::ResponseTemplate::new(200).set_body_string(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n",
                ),
            )
            .mount(&provider)
            .await;
        let state = test_state();
        seed_text_chat(&state, &provider.uri());
        let response = router(state.clone())
            .oneshot(request(
                axum::http::Method::POST,
                "/api/v1/conversations/c1/messages",
                Some(r#"{"content":"Hi"}"#),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let text = String::from_utf8(body.to_vec()).unwrap();
        assert!(text.contains("\"type\":\"Error\""));
        let messages = state.store.list_messages_by_conversation("c1").unwrap();
        let failed = messages.last().unwrap();
        assert_eq!(failed.role, "assistant");
        assert_eq!(failed.status, "error");
        assert!(failed.error_message.is_some());
    }

    #[tokio::test]
    async fn remote_tool_host_waits_for_client_result() {
        let state = test_state();
        let (events, mut receiver) = mpsc::unbounded_channel();
        let cancel = CancellationToken::new();
        let host = RemoteToolHost {
            pending: Arc::clone(&state.pending_tools),
            events,
            cancel: cancel.clone(),
        };
        let task = tokio::spawn(async move {
            host.execute("call-1", "terminal", r#"{"command":"ls"}"#)
                .await
        });
        let event: serde_json::Value =
            serde_json::from_str(&receiver.recv().await.unwrap()).unwrap();
        assert_eq!(event["type"], "ToolCallRequested");
        assert_eq!(event["callId"], "call-1");
        assert_eq!(event["argumentsJson"], r#"{"command":"ls"}"#);
        state
            .pending_tools
            .lock()
            .await
            .remove("call-1")
            .unwrap()
            .send(ToolExecutionResult {
                output: "ok".into(),
                is_error: false,
            })
            .unwrap();
        let result = task.await.unwrap();
        assert_eq!(result.output, "ok");
        assert!(!result.is_error);
    }

    #[tokio::test]
    async fn cancelling_turn_releases_waiting_tool_host() {
        let state = test_state();
        let (events, mut receiver) = mpsc::unbounded_channel();
        let cancel = CancellationToken::new();
        let host = RemoteToolHost {
            pending: Arc::clone(&state.pending_tools),
            events,
            cancel: cancel.clone(),
        };
        let task = tokio::spawn(async move { host.execute("call-2", "grep", "{}").await });
        receiver.recv().await.unwrap();
        cancel.cancel();
        let result = task.await.unwrap();
        assert!(result.is_error);
        assert!(state.pending_tools.lock().await.get("call-2").is_none());
    }

    #[tokio::test]
    async fn cloud_login_persists_the_server_session_cookie() {
        let remote = wiremock::MockServer::start().await;
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .and(wiremock::matchers::path("/api/auth/login"))
            .respond_with(
                wiremock::ResponseTemplate::new(200)
                    .append_header("Set-Cookie", "messenger_session=abc; Path=/; HttpOnly")
                    .set_body_json(serde_json::json!({
                        "user": {
                            "id": "acct-1",
                            "email": "a@b.c",
                            "syncVersion": 1
                        }
                    })),
            )
            .mount(&remote)
            .await;
        let state = test_state();
        state
            .store
            .kv_set(messenger_sync::KV_SERVER_URL, &remote.uri())
            .unwrap();
        let response = router(state.clone())
            .oneshot(request(
                axum::http::Method::POST,
                "/api/v1/cloud/login",
                Some(r#"{"email":"a@b.c","password":"secret"}"#),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let user: serde_json::Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(user["id"], "acct-1");
        assert!(state
            .store
            .kv_get(messenger_sync::KV_SESSION)
            .unwrap()
            .unwrap()
            .contains("messenger_session=abc"));
    }

    #[tokio::test]
    async fn cloud_session_expiry_maps_to_terminal_401() {
        let remote = wiremock::MockServer::start().await;
        wiremock::Mock::given(wiremock::matchers::method("GET"))
            .and(wiremock::matchers::path("/api/sync"))
            .respond_with(
                wiremock::ResponseTemplate::new(401)
                    .set_body_json(serde_json::json!({"error": "session expired"})),
            )
            .mount(&remote)
            .await;
        let state = test_state();
        state
            .store
            .kv_set(messenger_sync::KV_SESSION_HOST, &remote.uri())
            .unwrap();
        state
            .store
            .kv_set(
                messenger_sync::KV_SESSION,
                "messenger_session=expired; Path=/",
            )
            .unwrap();
        state
            .store
            .kv_set(
                messenger_sync::KV_USER,
                &serde_json::to_string(&messenger_sync::CloudUser {
                    id: "acct-1".into(),
                    email: "a@b.c".into(),
                    ..Default::default()
                })
                .unwrap(),
            )
            .unwrap();
        let response = router(state.clone())
            .oneshot(request(
                axum::http::Method::POST,
                "/api/v1/cloud/sync",
                Some(r#"{"replaceLocal":false}"#),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        let error: ErrorBody = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(error.code, "session_expired");
        assert!(state
            .store
            .kv_get(messenger_sync::KV_SESSION)
            .unwrap()
            .is_none());
    }

    #[tokio::test]
    async fn malformed_json_is_a_visible_http_error() {
        let response = router(test_state())
            .oneshot(request(
                axum::http::Method::PUT,
                "/api/v1/providers",
                Some("{not-json"),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        let raw = String::from_utf8(bytes.to_vec()).unwrap();
        assert!(raw.contains("Failed to parse the request body as JSON"));
    }

    #[tokio::test]
    async fn crud_and_pagination_are_json() {
        let state = test_state();
        let app = router(state.clone());
        let provider = r#"{"id":"p1","name":"test","base_url":"http://localhost","api_key":"key","api_format":"chat","created_at":1,"updated_at":1}"#;
        let response = app
            .clone()
            .oneshot(request(
                axum::http::Method::PUT,
                "/api/v1/providers/p1",
                Some(provider),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let response = app
            .clone()
            .oneshot(request(
                axum::http::Method::GET,
                "/api/v1/providers?limit=1",
                None,
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        let json: Page<StoredProvider> = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(json.items.len(), 1);
    }

    #[tokio::test]
    async fn missing_model_aborts_before_message_insert() {
        let state = test_state();
        state
            .store
            .upsert_provider(&StoredProvider {
                id: "p1".into(),
                name: "p".into(),
                base_url: "http://localhost".into(),
                api_key: "key".into(),
                api_format: "chat".into(),
                created_at: 1,
                updated_at: 1,
            })
            .unwrap();
        state
            .store
            .upsert_agent(&StoredAgent {
                id: "a1".into(),
                name: "a".into(),
                avatar: None,
                system_prompt: "help".into(),
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
                tools_enabled: false,
                tools_follow_default: false,
                tools_config: String::new(),
                created_at: 1,
                updated_at: 1,
            })
            .unwrap();
        state
            .store
            .upsert_conversation(&StoredConversation {
                id: "c1".into(),
                title: "New".into(),
                provider_id: "p1".into(),
                agent_id: "a1".into(),
                project_id: None,
                override_model_id: None,
                override_temperature: None,
                override_top_p: None,
                override_max_tokens: None,
                override_reasoning_effort: None,
                override_tools_enabled: None,
                override_tools_config: None,
                writable: false,
                created_at: 1,
                updated_at: 1,
                last_message: None,
                reasoning_format: None,
                context_summary: None,
                context_summary_until: 0,
                context_tokens: 0,
                context_tokens_at: 0,
            })
            .unwrap();
        for body in [
            r#"{"content":"hello"}"#,
            r#"{"operation":"retry","messageId":"missing"}"#,
            r#"{"operation":"regenerate","messageId":"missing"}"#,
        ] {
            let response = router(state.clone())
                .oneshot(request(
                    axum::http::Method::POST,
                    "/api/v1/conversations/c1/messages",
                    Some(body),
                ))
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::CONFLICT);
            assert!(state
                .store
                .list_messages_by_conversation("c1")
                .unwrap()
                .is_empty());
            let bytes = response.into_body().collect().await.unwrap().to_bytes();
            let error: ErrorBody = serde_json::from_slice(&bytes).unwrap();
            assert_eq!(error.code, "model_required");
        }
    }
}
