//! Session commands shared by the local UI, ACP adapter and cloud relay.
use super::*;
use axum::body::to_bytes;
use messenger_remote::ControlHost;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

const CONFIG: &str = "device_control_config";
const GRANT: &str = "device_control_grant";

#[derive(Default)]
pub(crate) struct PlatformCatalog {
    pub workspace_supported: bool,
    pub tools: Vec<ToolSpecRequest>,
    pub mcp_servers: Vec<messenger_mcp::McpServerConfig>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct PlatformCatalogUpdate {
    workspace_supported: bool,
    tools: Vec<ToolSpecRequest>,
    #[serde(default)]
    mcp_servers: Option<Vec<messenger_mcp::McpServerConfig>>,
    #[serde(default)]
    tools_ready: bool,
}

pub(crate) async fn platform_tools(
    State(state): State<ApiState>,
    Json(update): Json<PlatformCatalogUpdate>,
) -> StatusCode {
    let mut catalog = state.platform_catalog.lock().await;
    catalog.workspace_supported = update.workspace_supported;
    catalog.tools = update.tools;
    if let Some(mcp_servers) = update.mcp_servers {
        catalog.mcp_servers = mcp_servers;
        state.platform_catalog_ready.cancel();
        if update.tools_ready { state.platform_tools_ready.cancel(); }
    }
    StatusCode::NO_CONTENT
}

#[derive(Clone, Default, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
pub(crate) struct Settings {
    pub enabled: bool,
    pub name: String,
    pub agents: Vec<crate::external_acp::Profile>,
}

fn signed_in_scope(store: &Store) -> Option<(String, String)> {
    let host = store
        .kv_get(KV_SESSION_HOST)
        .ok()
        .flatten()
        .filter(|host| !host.is_empty())?;
    store
        .kv_get(KV_SESSION)
        .ok()
        .flatten()
        .filter(|cookie| !cookie.is_empty())?;
    let engine = sync_engine(store).ok()?;
    if engine.server_url().trim_end_matches('/') != host.trim_end_matches('/') {
        return None;
    }
    let account = engine.current_user()?.id;
    Some((host, account))
}

fn has_grant(store: &Store) -> bool {
    let Some(scope) = signed_in_scope(store) else {
        return false;
    };
    store
        .kv_get(GRANT)
        .ok()
        .flatten()
        .and_then(|json| serde_json::from_str::<(String, String)>(&json).ok())
        == Some(scope)
}

pub(crate) fn settings(store: &Store) -> Result<Settings, ApiError> {
    store
        .kv_get(CONFIG)
        .map_err(ApiError::store)?
        .map(|value| serde_json::from_str(&value).map_err(ApiError::store))
        .unwrap_or_else(|| Ok(Settings { agents: crate::external_acp::default_profiles(), ..Settings::default() }))
}

pub(crate) async fn get_settings(
    State(state): State<ApiState>,
) -> Result<Json<Settings>, ApiError> {
    let mut value = settings(&state.store)?;
    if state.store.kv_get("acp_templates_seeded").map_err(ApiError::store)?.is_none() {
        for template in crate::external_acp::default_profiles() {
            if value.agents.len() < 50 && !value.agents.iter().any(|profile| profile.id == template.id) { value.agents.push(template); }
        }
        state.store.kv_set(CONFIG, &serde_json::to_string(&value).map_err(ApiError::store)?).map_err(ApiError::store)?;
        state.store.kv_set("acp_templates_seeded", "1").map_err(ApiError::store)?;
    }
    value.enabled &= has_grant(&state.store);
    Ok(Json(value))
}

pub(crate) async fn set_settings(
    State(state): State<ApiState>,
    Json(value): Json<Settings>,
) -> Result<Json<Settings>, ApiError> {
    if value.name.chars().count() > 120 {
        return Err(ApiError::BadRequest("device name is too long".into()));
    }
    crate::external_acp::validate_profiles(&value.agents)?;
    if value.enabled {
        let scope = signed_in_scope(&state.store).ok_or_else(|| {
            ApiError::BadRequest("sign in to Messenger Cloud to enable remote control".into())
        })?;
        state
            .store
            .kv_set(
                GRANT,
                &serde_json::to_string(&scope).map_err(ApiError::store)?,
            )
            .map_err(ApiError::store)?;
    } else {
        state.store.kv_delete(GRANT).map_err(ApiError::store)?;
    }
    state
        .store
        .kv_set(
            CONFIG,
            &serde_json::to_string(&value).map_err(ApiError::store)?,
        )
        .map_err(ApiError::store)?;
    Ok(Json(value))
}

#[derive(Deserialize)]
#[serde(
    tag = "action",
    rename_all = "camelCase",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum Command {
    Catalog,
    Store { kind: String },
    SessionSettings { session_id: String },
    SetSessionConfig {
        session_id: String,
        config_id: Option<String>,
        value: Option<Value>,
        mode_id: Option<String>,
    },
    History {
        session_id: String,
        before: Option<String>,
    },
    Create {
        agent_id: String,
        project_id: Option<String>,
        #[serde(default)]
        writable: bool,
    },
    SetMode {
        session_id: String,
        writable: bool,
    },
    Steer { session_id: String, content: Vec<agent_client_protocol::schema::v1::ContentBlock> },
    Prompt {
        session_id: String,
        text: String,
        #[serde(default)]
        content: Vec<agent_client_protocol::schema::v1::ContentBlock>,
    },
}

pub(crate) struct DeviceControl(pub ApiState);

#[async_trait]
impl ControlHost for DeviceControl {
    fn advertised_name(&self) -> Option<String> {
        let settings = settings(&self.0.store).ok()?;
        (settings.enabled && has_grant(&self.0.store)).then(|| {
            if settings.name.trim().is_empty() {
                let device = self.0.remote.device_id();
                format!(
                    "{} · {}",
                    if cfg!(target_os = "android") {
                        "Android"
                    } else {
                        "Messenger"
                    },
                    device.chars().take(8).collect::<String>()
                )
            } else {
                settings.name
            }
        })
    }

    async fn dispatch(
        &self,
        request: Value,
        cancel: CancellationToken,
        permissions: mpsc::Receiver<String>,
        updates: mpsc::Sender<Value>,
    ) -> Value {
        dispatch(
            self.0.clone(),
            request,
            cancel,
            permissions,
            updates,
            ControlScope::Remote,
        )
        .await
    }
}

pub(crate) struct CommandHandle {
    permissions: mpsc::Sender<String>,
    cancel: CancellationToken,
}

// Retained only for the lifetime of this Core process; cursors index an
// append-only log, so a reconnect never depends on a live SSE consumer.
pub(crate) struct BackgroundCommand {
    session_id: String,
    request_digest: [u8; 32],
    created: std::time::Instant,
    log: Mutex<BackgroundLog>,
}

#[derive(Default)]
struct BackgroundLog {
    events: std::collections::VecDeque<Value>,
    base: usize,
    bytes: usize,
    done: bool,
    pending_permission: Option<(usize, Value)>,
    completed: Option<std::time::Instant>,
}

const BACKGROUND_MAX_JOBS: usize = 32;
const BACKGROUND_MAX_BYTES: usize = 4 * 1024 * 1024;
const BACKGROUND_RETENTION: std::time::Duration = std::time::Duration::from_secs(30 * 60);

#[derive(Default, Deserialize)]
pub(crate) struct BackgroundCursor { #[serde(default)] after: usize }

async fn snapshot(id: Uuid, job: &BackgroundCommand, after: usize) -> Result<Json<Value>, ApiError> {
    let log = job.log.lock().await;
    if log.completed.is_some_and(|at| at.elapsed() >= BACKGROUND_RETENTION) {
        return Err(ApiError::not_found("background command"));
    }
    let end = log.base + log.events.len();
    if after > end {
        return Err(ApiError::BadRequest("invalid background event cursor".into()));
    }
    let mut events = Vec::new();
    if after < log.base {
        events.push(json!({"type":"ReplayGap"}));
        if let Some((index, permission)) = &log.pending_permission {
            if *index < log.base { events.push(permission.clone()); }
        }
    }
    events.extend(log.events.iter().skip(after.saturating_sub(log.base)).cloned());
    Ok(Json(json!({"requestId": id, "events": events, "nextCursor": end, "done": log.done})))
}

fn record_background_event(log: &mut BackgroundLog, event: Value) {
    let size = event.to_string().len();
    let index = log.base + log.events.len();
    match event["type"].as_str() {
        Some("Permission") => log.pending_permission = Some((index, event.clone())),
        Some("PermissionResolved" | "Finished" | "Cancelled" | "Error") => log.pending_permission = None,
        _ => {}
    }
    while !log.events.is_empty() && (log.bytes.saturating_add(size) > BACKGROUND_MAX_BYTES || log.events.len() >= 16_384) {
        let removed = log.events.pop_front().unwrap();
        log.bytes -= removed.to_string().len();
        log.base += 1;
    }
    if size > BACKGROUND_MAX_BYTES {
        log.base += 1;
    } else {
        log.bytes += size;
        log.events.push_back(event);
    }
}

pub(crate) async fn background_snapshot(
    State(state): State<ApiState>, Path(id): Path<Uuid>, Query(cursor): Query<BackgroundCursor>,
) -> Result<Json<Value>, ApiError> {
    let job = state.background_commands.lock().await.get(&id).cloned()
        .ok_or_else(|| ApiError::not_found("background command"))?;
    snapshot(id, &job, cursor.after).await
}

pub(crate) async fn background_session(
    State(state): State<ApiState>, Path(session_id): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let jobs = state.background_commands.lock().await;
    let (id, job) = jobs.iter().filter(|(_, job)| job.session_id == session_id)
        .max_by_key(|(_, job)| job.created)
        .map(|(id, job)| (*id, job.clone()))
        .ok_or_else(|| ApiError::not_found("background command"))?;
    drop(jobs);
    snapshot(id, &job, 0).await
}

pub(crate) async fn start_background(
    State(state): State<ApiState>, Path(request_id): Path<Uuid>, Json(request): Json<Value>,
) -> Result<StatusCode, ApiError> {
    let command: Command = serde_json::from_value(request.clone()).map_err(|e| ApiError::BadRequest(e.to_string()))?;
    let session_id = match command {
        Command::Prompt { session_id, ref text, .. } if session_id.starts_with("acp:") && !text.trim().is_empty() && text.len() <= 128_000 => session_id,
        _ => return Err(ApiError::BadRequest("background command must be a valid ACP prompt".into())),
    };
    // Validate the session before accepting ownership of a task.
    state.external.record(&state.store, &session_id)?;
    let cancel = state.shutdown.child_token();
    let (permissions, choices) = mpsc::channel(8);
    let request_digest: [u8; 32] = Sha256::digest(serde_json::to_vec(&request).map_err(ApiError::internal)?).into();
    let job = Arc::new(BackgroundCommand { session_id: session_id.clone(), request_digest, created: std::time::Instant::now(), log: Mutex::new(BackgroundLog::default()) });
    {
        let mut jobs = state.background_commands.lock().await;
        let now = std::time::Instant::now();
        let mut remove = Vec::new();
        for (id, previous) in jobs.iter() {
            let log = previous.log.lock().await;
            if log.completed.is_some_and(|at| now.duration_since(at) >= BACKGROUND_RETENTION) { remove.push(*id); }
            if *id == request_id && !remove.contains(id) {
                return if previous.request_digest == request_digest { Ok(StatusCode::ACCEPTED) } else { Err(ApiError::BadRequest("duplicate control request ID".into())) };
            }
            if previous.session_id == session_id && !log.done {
                return Err(ApiError::Conflict { code: "turn_in_progress".into(), message: "The session is generating.".into() });
            }
        }
        for id in remove { jobs.remove(&id); }
        if jobs.len() >= BACKGROUND_MAX_JOBS {
            let mut oldest = None;
            for (id, previous) in jobs.iter() {
                if let Some(at) = previous.log.lock().await.completed {
                    if oldest.as_ref().is_none_or(|(_, time)| at < *time) { oldest = Some((*id, at)); }
                }
            }
            if let Some((id, _)) = oldest { jobs.remove(&id); }
        }
        if jobs.len() >= BACKGROUND_MAX_JOBS { return Err(ApiError::BadRequest("too many active background commands".into())); }
        let mut pending = state.control_permissions.lock().await;
        if pending.contains_key(&request_id) || jobs.contains_key(&request_id) { return Err(ApiError::BadRequest("duplicate control request ID".into())); }
        pending.insert(request_id, CommandHandle { permissions, cancel: cancel.clone() });
        jobs.insert(request_id, job.clone());
    }
    let (updates, mut events) = mpsc::channel(64);
    let task_state = state.clone();
    let task_cancel = cancel.clone();
    tokio::spawn(async move {
        let result = dispatch(task_state, request, task_cancel, choices, updates.clone(), ControlScope::Local).await;
        let _ = updates.send(result).await;
    });
    tokio::spawn(async move {
        while let Some(event) = events.recv().await {
            let status = match event["type"].as_str() {
                Some("Permission") => Some("approval"),
                Some("Finished") => Some("completed"),
                Some("Error") => Some("failed"),
                _ => None,
            };
            if let Some(status) = status {
                let _ = state.events.send(CoreEvent::Change(ChangeEvent { kind: "taskStatus".into(), ids: vec![status.into()] }));
            }
            let mut log = job.log.lock().await;
            record_background_event(&mut log, event);
        }
        let mut log = job.log.lock().await;
        if !log.events.back().and_then(|v| v["type"].as_str()).is_some_and(|kind| matches!(kind, "Finished" | "Cancelled" | "Error")) {
            let _ = state.events.send(CoreEvent::Change(ChangeEvent { kind: "taskStatus".into(), ids: vec!["failed".into()] }));
            record_background_event(&mut log, json!({"type":"Error","code":"background_ended","message":"Background command ended unexpectedly."}));
        }
        log.done = true;
        log.completed = Some(std::time::Instant::now());
        drop(log);
        state.control_permissions.lock().await.remove(&request_id);
    });
    Ok(StatusCode::ACCEPTED)
}

pub(crate) async fn cancel_command(
    State(state): State<ApiState>,
    Path(request_id): Path<Uuid>,
) -> Result<StatusCode, ApiError> {
    let requests = state.control_permissions.lock().await;
    requests
        .get(&request_id)
        .ok_or_else(|| ApiError::not_found("control request"))?
        .cancel
        .cancel();
    Ok(StatusCode::NO_CONTENT)
}

pub(crate) async fn commands(
    State(state): State<ApiState>,
    Path(request_id): Path<Uuid>,
    Json(request): Json<Value>,
) -> Result<Sse<impl Stream<Item = Result<Event, Infallible>>>, ApiError> {
    let cancel = state.shutdown.child_token();
    let (permissions, choices) = mpsc::channel(8);
    {
        let mut pending = state.control_permissions.lock().await;
        if pending.contains_key(&request_id) {
            return Err(ApiError::BadRequest("duplicate control request ID".into()));
        }
        pending.insert(
            request_id,
            CommandHandle {
                permissions,
                cancel: cancel.clone(),
            },
        );
    }
    let (updates, events) = mpsc::channel(64);
    let task_cancel = cancel.clone();
    tokio::spawn(async move {
        let result = dispatch(
            state.clone(),
            request,
            task_cancel,
            choices,
            updates.clone(),
            ControlScope::Local,
        )
        .await;
        let _ = updates.send(result).await;
        state.control_permissions.lock().await.remove(&request_id);
    });
    let guard = cancel.drop_guard();
    Ok(Sse::new(
        tokio_stream::wrappers::ReceiverStream::new(events).map(move |data| {
            let _guard = &guard;
            Ok(Event::default().data(data.to_string()))
        }),
    )
    .keep_alive(KeepAlive::default()))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Choice {
    pub(crate) option_id: String,
}

pub(crate) async fn permission(
    State(state): State<ApiState>,
    Path(request_id): Path<Uuid>,
    Json(choice): Json<Choice>,
) -> Result<StatusCode, ApiError> {
    let choices = state.control_permissions.lock().await;
    choices
        .get(&request_id)
        .ok_or_else(|| ApiError::not_found("control request"))?
        .permissions
        .try_send(choice.option_id)
        .map_err(ApiError::internal)?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Clone, Copy)]
enum ControlScope {
    Local,
    Remote,
}

impl ControlScope {
    fn workspace(
        self,
        state: &ApiState,
        project_id: Option<&str>,
    ) -> Result<Option<StoredProject>, ApiError> {
        if matches!(self, Self::Remote) && project_id.is_none() {
            return Err(ApiError::BadRequest(
                "remote control requires a project".into(),
            ));
        }
        local_workspace(state, project_id)
    }
}

async fn dispatch(
    state: ApiState,
    request: Value,
    cancel: CancellationToken,
    permissions: mpsc::Receiver<String>,
    updates: mpsc::Sender<Value>,
    scope: ControlScope,
) -> Value {
    match execute(state, request, cancel, permissions, updates, scope).await {
        Ok(result) => result,
        Err(error) => {
            let reply = error.into_response();
            let status = reply.status();
            let mut detail: Value = to_bytes(reply.into_body(), 16_384)
                .await
                .ok()
                .and_then(|body| serde_json::from_slice(&body).ok())
                .unwrap_or_else(|| json!({"code":"core_error","message":status.to_string()}));
            detail["type"] = json!("Error");
            detail
        }
    }
}

async fn execute(
    state: ApiState,
    request: Value,
    cancel: CancellationToken,
    permissions: mpsc::Receiver<String>,
    updates: mpsc::Sender<Value>,
    scope: ControlScope,
) -> Result<Value, ApiError> {
    if cancel.is_cancelled() {
        return Ok(json!({"type":"Cancelled"}));
    }
    let request: Command =
        serde_json::from_value(request).map_err(|err| ApiError::BadRequest(err.to_string()))?;
    match request {
        Command::Store { kind } => {
            if !matches!(kind.as_str(), "acp" | "mcp") { return Err(ApiError::BadRequest("invalid integration kind".into())); }
            let engine = sync_engine(&state.store)?;
            let catalog = engine.integration_catalog().await.map_err(ApiError::cloud)?;
            Ok(json!({"type":"Store", "items":catalog.get(&kind).cloned().unwrap_or_else(|| json!([]))}))
        }
        Command::Catalog => {
            let agents = state
                .store
                .list_agents()
                .map_err(ApiError::store)?
                .into_iter()
                .filter(|agent| agent.role != "title")
                .map(|agent| json!({"id":agent.id,"name":agent.name,"kind":"messenger"}));
            let projects = state
                .store
                .list_projects()
                .map_err(ApiError::store)?
                .into_iter()
                .filter(|project| state.remote.owner_of(project) == ProjectOwner::Local)
                .map(|project| json!({"id":project.id,"name":project.name}));
            let sessions = state.store.list_conversations().map_err(ApiError::store)?
                .into_iter()
                .filter(|conversation| scope.workspace(&state, conversation.project_id.as_deref()).is_ok())
                .take(100)
                .map(|conversation| json!({
                    "id": conversation.id, "title": short(&conversation.title, 160), "kind": "messenger",
                    "projectId": conversation.project_id, "updatedAt": conversation.updated_at
                }));
            let profiles = settings(&state.store)?.agents;
            let external_agents = profiles.into_iter().filter(|profile| profile.enabled).map(
                |agent| json!({"id":format!("acp:{}",agent.id),"name":agent.name,"description":agent.description,"avatar":if matches!(scope, ControlScope::Local) { agent.avatar } else { None },"kind":"acp"}),
            );
            let mut external: Vec<_> = state
                .external
                .sessions(&state.store)?
                .into_values()
                .filter(|session| {
                    matches!(scope, ControlScope::Local)
                        || scope.workspace(&state, session.project.as_deref()).is_ok()
                })
                .collect();
            external.sort_by_key(|session| std::cmp::Reverse(session.updated_at));
            let external = external.into_iter().take(100).map(|session| {
                json!({
                    "id": session.id, "title": short(&session.title, 160), "kind": "acp",
                    "projectId": session.project, "updatedAt": session.updated_at
                })
            });
            Ok(
                json!({"type":"Catalog","agents":agents.chain(external_agents).take(100).collect::<Vec<_>>(),
                "projects":projects.take(100).collect::<Vec<_>>(),
                "sessions":sessions.chain(external).collect::<Vec<_>>() }),
            )
        }
        Command::SessionSettings { session_id } => {
            let record = state.external.record(&state.store, &session_id)?;
            scope.workspace(&state, record.project.as_deref())?;
            state.external.settings(&state, &session_id, None, &cancel).await
        }
        Command::SetSessionConfig { session_id, config_id, value, mode_id } => {
            if config_id.is_some() == mode_id.is_some() {
                return Err(ApiError::BadRequest("Supply either configId/value or modeId.".into()));
            }
            let change = json!({"configId":config_id,"value":value,"modeId":mode_id});
            let record = state.external.record(&state.store, &session_id)?;
            scope.workspace(&state, record.project.as_deref())?;
            state.external.settings(&state, &session_id, Some(change), &cancel).await
        }
        Command::History { session_id, before } => {
            let mut writable = None;
            let mut rows = if session_id.starts_with("acp:") {
                let session = state.external.record(&state.store, &session_id)?;
                if matches!(scope, ControlScope::Remote) {
                    scope.workspace(&state, session.project.as_deref())?;
                }
                session.messages
            } else {
                let conversation = conversation(&state, &session_id)?;
                writable = Some(conversation.writable);
                scope.workspace(&state, conversation.project_id.as_deref())?;
                state.store.list_messages_by_conversation(&session_id).map_err(ApiError::store)?
                    .into_iter().map(|message| json!({
                        "id":message.id,"role":message.role,
                        "text":if let Some(error) = &message.error_message { error } else { &message.content },
                        "partsJson":message.parts_json,
                        "status":message.status
                    })).collect()
            };
            let end = before
                .as_ref()
                .and_then(|before| rows.iter().position(|row| row["id"] == *before))
                .unwrap_or(rows.len());
            rows.truncate(end);
            let mut messages = Vec::new();
            let mut encoded_size = 0;
            for mut row in rows.into_iter().rev().take(20) {
                if matches!(scope, ControlScope::Remote) {
                    row["text"] = json!(short(row["text"].as_str().unwrap_or_default(), 36_000));
                    // Native history retains the full transcript. Cloud relay history remains a
                    // bounded preview; an oversized tool trace must not stall the page cursor.
                    if row.to_string().len() > 235_000 {
                        row.as_object_mut().unwrap().remove("events");
                        row["eventsTruncated"] = json!(true);
                    }
                    encoded_size += row.to_string().len();
                    if encoded_size > 235_000 { break; }
                }
                messages.push(row);
            }
            messages.reverse();
            let next = if messages.len() < end {
                messages.first().and_then(|row| row["id"].as_str())
            } else {
                None
            };
            Ok(json!({"type":"History","messages":messages,"before":next,"writable":writable}))
        }
        Command::SetMode {
            session_id,
            writable,
        } => {
            let active = state.active_turns.lock().await;
            if active.contains_key(&session_id) {
                return Err(ApiError::Conflict {
                    code: "turn_in_progress".into(),
                    message: "The session is generating.".into(),
                });
            }
            let mut row = conversation(&state, &session_id)?;
            scope.workspace(&state, row.project_id.as_deref())?;
            row.writable = writable;
            upsert_conversation_checked(&state.store, &row)?;
            queue_sync(&state, &row.id);
            Ok(json!({"type":"Mode","writable":writable}))
        }
        Command::Create {
            agent_id,
            project_id,
            writable,
        } => {
            let project = scope.workspace(&state, project_id.as_deref())?;
            let id = if let Some(profile) = agent_id.strip_prefix("acp:") {
                state
                    .external
                    .create(&state, profile, project, &cancel)
                    .await?
            } else {
                create_conversation(&state, &agent_id, project_id, writable)?.id
            };
            Ok(json!({"type":"Created","sessionId":id}))
        }
        Command::Steer { session_id, content } => {
            let session = state.external.record(&state.store, &session_id)?;
            scope.workspace(&state, session.project.as_deref())?;
            state.external.steer(&state, &session_id, content).await
        }
        Command::Prompt { session_id, text, mut content } => {
            if content.is_empty() { content.push(agent_client_protocol::schema::v1::ContentBlock::Text(agent_client_protocol::schema::v1::TextContent::new(text.clone()))); }
            if text.trim().is_empty() || text.len() > 128_000 {
                return Err(ApiError::BadRequest(
                    "prompt must contain 1–128000 bytes of text".into(),
                ));
            }
            if session_id.starts_with("acp:") {
                if matches!(scope, ControlScope::Remote) {
                    let session = state.external.record(&state.store, &session_id)?;
                    scope.workspace(&state, session.project.as_deref())?;
                }
                return state
                    .external
                    .prompt(&state, session_id, text, content, cancel, permissions, updates)
                    .await;
            }
            let conversation = conversation(&state, &session_id)?;
            scope.workspace(&state, conversation.project_id.as_deref())?;
            let mut events =
                start_turn(state, session_id, text_request(text), cancel.clone(), true).await?;
            while let Some(event) = events.recv().await {
                let value: Value = serde_json::from_str(&event).map_err(ApiError::internal)?;
                if matches!(
                    value["type"].as_str(),
                    Some("Finished" | "Cancelled" | "Error")
                ) {
                    return Ok(value);
                }
                let mut value = value;
                match value["type"].as_str() {
                    Some("FinalMessagePersisted") => continue,
                    Some("ToolCallFinished") => {
                        value.as_object_mut().unwrap().remove("output");
                    }
                    _ => {}
                }
                if updates.send(value).await.is_err() {
                    cancel.cancel();
                    break;
                }
            }
            if cancel.is_cancelled() {
                Ok(json!({"type":"Cancelled"}))
            } else {
                Err(ApiError::internal("turn ended without a terminal event"))
            }
        }
    }
}

pub(crate) fn text_request(text: String) -> ChatRequest {
    ChatRequest {
        supports_tool_approval: false,
        content: Some(text),
        parts_json: None,
        message_group: None,
        operation: ChatOperation::Send,
        disable_tools: false,
        message_id: None,
        tool_specs: Vec::new(),
        workspace_path: None,
        no_agent_delegation: false,
        scheduled_run: false,
        scheduled_allowed_tools: None,
    }
}

pub(crate) fn conversation(state: &ApiState, id: &str) -> Result<StoredConversation, ApiError> {
    state
        .store
        .get_conversation(id)
        .map_err(ApiError::store)?
        .ok_or_else(|| ApiError::not_found("conversation"))
}

pub(crate) fn local_workspace(
    state: &ApiState,
    project_id: Option<&str>,
) -> Result<Option<StoredProject>, ApiError> {
    let Some(id) = project_id else {
        return Ok(None);
    };
    let project = state
        .store
        .get_project(id)
        .map_err(ApiError::store)?
        .ok_or_else(|| ApiError::not_found("project"))?;
    if state.remote.owner_of(&project) != ProjectOwner::Local {
        return Err(ApiError::BadRequest(
            "project belongs to another device".into(),
        ));
    }
    Ok(Some(project))
}

pub(crate) fn create_conversation(
    state: &ApiState,
    agent_id: &str,
    project_id: Option<String>,
    writable: bool,
) -> Result<StoredConversation, ApiError> {
    let agent = state
        .store
        .get_agent(agent_id)
        .map_err(ApiError::store)?
        .filter(|agent| agent.role != "title")
        .ok_or_else(|| ApiError::not_found("agent"))?;
    let model = agent
        .default_model_id
        .as_deref()
        .map(|id| state.store.get_model(id))
        .transpose()
        .map_err(ApiError::store)?
        .flatten();
    let now = now_ms();
    let conversation = StoredConversation {
        id: Uuid::new_v4().to_string(),
        title: "新对话".into(),
        agent_id: agent.id,
        provider_id: model.map(|model| model.provider_id).unwrap_or_default(),
        project_id,
        writable,
        created_at: now,
        updated_at: now,
        ..Default::default()
    };
    upsert_conversation_checked(&state.store, &conversation)?;
    queue_sync(state, &conversation.id);
    Ok(conversation)
}

pub(crate) fn queue_sync(state: &ApiState, conversation_id: &str) {
    let Some(scope) = signed_in_scope(&state.store) else {
        return;
    };
    let state = state.clone();
    let conversation_id = conversation_id.to_owned();
    tokio::spawn(async move {
        let _sync = state.cloud_sync_gate.lock().await;
        if signed_in_scope(&state.store) != Some(scope) || state.shutdown.is_cancelled() {
            return;
        }
        if let Ok(engine) = sync_engine(&state.store) {
            if engine
                .request_local_change("conversation", &conversation_id, false)
                .is_ok()
            {
                // The pending queue survives failures; ordinary sync can flush it later.
                let _ = engine.push_local_snapshot(true).await;
            }
        }
    });
}

pub(crate) fn short(value: &str, limit: usize) -> String {
    let mut chars = value.chars();
    let mut text = chars.by_ref().take(limit).collect::<String>();
    if chars.next().is_some() {
        text.push('…');
    }
    text
}

#[cfg(test)]
mod background_tests {
    use super::*;

    #[tokio::test]
    async fn replay_gap_keeps_pending_permission_without_cancelling_work() {
        let job = BackgroundCommand { session_id: "acp:test".into(), request_digest: [0; 32], created: std::time::Instant::now(), log: Mutex::new(BackgroundLog::default()) };
        {
            let mut log = job.log.lock().await;
            record_background_event(&mut log, json!({"type":"Permission","options":[{"id":"choice"}]}));
            for _ in 0..17 { record_background_event(&mut log, json!({"type":"TextDelta","text":"x".repeat(256 * 1024)})); }
        }
        let value = snapshot(Uuid::new_v4(), &job, 0).await.unwrap().0;
        assert_eq!(value["events"][0]["type"], "ReplayGap");
        assert_eq!(value["events"][1]["type"], "Permission");
        assert_eq!(value["done"], false);
        let cursor = value["nextCursor"].as_u64().unwrap() as usize;
        {
            let mut log = job.log.lock().await;
            record_background_event(&mut log, json!({"type":"PermissionResolved"}));
        }
        let value = snapshot(Uuid::new_v4(), &job, cursor).await.unwrap().0;
        assert_eq!(value["events"][0]["type"], "PermissionResolved");
        let value = snapshot(Uuid::new_v4(), &job, 0).await.unwrap().0;
        assert!(!value["events"].as_array().unwrap().iter().any(|event| event["type"] == "Permission"));
    }
}
