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
    NotificationOpen { session_id: String, before: Option<String> },
    NotificationDecision { session_id: String, request_id: String, option_id: String },
    NotificationCancel { session_id: String, request_id: String },
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
    let tasks = state.tasks.clone();
    let task_cancel = cancel.clone();
    tasks.spawn(async move {
        let result = dispatch(task_state, request, task_cancel, choices, updates.clone(), ControlScope::Local).await;
        let _ = updates.send(result).await;
    });
    state.tasks.clone().spawn(async move {
        while let Some(event) = events.recv().await {
            let status = match event["type"].as_str() {
                Some("Permission") => Some("approval"),
                Some("PermissionResolved") => Some("approvalResolved"),
                Some("Finished") => Some("completed"),
                Some("Error") => Some("failed"),
                Some("Cancelled") => Some("cancelled"),
                _ => None,
            };
            if let Some(status) = status {
                let _ = state.events.send(CoreEvent::Change(ChangeEvent { kind: "taskStatus".into(), ids: vec![status.into(), "acp".into(), job.session_id.clone()] }));
            }
            let mut log = job.log.lock().await;
            record_background_event(&mut log, event);
        }
        let mut log = job.log.lock().await;
        if !log.events.back().and_then(|v| v["type"].as_str()).is_some_and(|kind| matches!(kind, "Finished" | "Cancelled" | "Error")) {
            let _ = state.events.send(CoreEvent::Change(ChangeEvent { kind: "taskStatus".into(), ids: vec!["failed".into(), "acp".into(), job.session_id.clone()] }));
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
    let tasks = state.tasks.clone();
    let task_cancel = cancel.clone();
    tasks.spawn(async move {
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
        Command::NotificationOpen { session_id, before } => {
            let (title, writable, mut messages) = if session_id.starts_with("acp:") {
                let session = state.external.record(&state.store, &session_id)?;
                (session.title, None, session.messages)
            } else {
                let conversation = conversation(&state, &session_id)?;
                let rows = state.store.list_messages_by_conversation(&session_id).map_err(ApiError::store)?
                    .into_iter().map(|message| json!({"id":message.id,"role":message.role,
                        "text":message.error_message.as_ref().unwrap_or(&message.content),"status":message.status})).collect();
                (conversation.title, Some(conversation.writable), rows)
            };
            // Relay packets are bounded; return a small tail without credentials or binary content.
            let end = before.as_ref().and_then(|id| messages.iter().position(|row| row["id"] == *id)).unwrap_or(messages.len());
            messages.truncate(end);
            let mut page = Vec::new();
            let mut bytes = 0usize;
            for row in messages.iter().rev().take(20) {
                let sanitized = json!({"id":row["id"], "role":row["role"],
                    "text":short(row["text"].as_str().unwrap_or_default(), 12_000), "status":row["status"]});
                let size = sanitized.to_string().len();
                if !page.is_empty() && bytes + size > 120_000 { break; }
                bytes += size;
                page.push(sanitized);
            }
            page.reverse();
            let has_more = page.len() < end;
            messages = page;
            let task = if session_id.starts_with("acp:") {
                let jobs = state.background_commands.lock().await;
                let entry = jobs.iter().filter(|(_, job)| job.session_id == session_id)
                    .max_by_key(|(_, job)| job.created).map(|(id, job)| (*id, job.clone()));
                drop(jobs);
                if let Some((request_id, job)) = entry {
                    let log = job.log.lock().await;
                    let pending = log.pending_permission.as_ref().map(|(_, event)| event.clone());
                    let mut text = String::new();
                    for event in &log.events {
                        if event["type"] == "TextDelta" {
                            text.push_str(event["text"].as_str().unwrap_or_default());
                            if text.len() > 36_000 { break; }
                        }
                    }
                    let mut events = Vec::new();
                    if !log.done && !text.is_empty() { events.push(json!({"type":"TextDelta","text":short(&text, 8_000)})); }
                    if !log.done { if let Some(pending) = pending { events.push(pending); } }
                    json!({"active":!log.done,"requestId":request_id,"events":events})
                } else { json!({"active":false,"events":[]}) }
            } else { background_turn::remote_snapshot(&state, &session_id).await };
            let next_before = has_more.then(|| messages.first().and_then(|row| row["id"].as_str()).map(str::to_owned)).flatten();
            Ok(json!({"type":"Session","title":short(&title,160),"writable":writable,"messages":messages,"before":next_before,
                "active":task["active"],"requestId":task["requestId"],"events":task["events"]}))
        }
        Command::NotificationDecision { session_id, request_id, option_id } => {
            if session_id.starts_with("acp:") {
                state.external.record(&state.store, &session_id)?;
                let jobs = state.background_commands.lock().await;
                let id = Uuid::parse_str(&request_id).map_err(|_| ApiError::BadRequest("invalid background request".into()))?;
                let entry = jobs.get(&id).filter(|job| job.session_id == session_id).map(|job| (id, job.clone()));
                drop(jobs);
                let (id, job) = entry.ok_or_else(|| ApiError::not_found("background command"))?;
                let log = job.log.lock().await;
                let pending = log.pending_permission.as_ref().map(|(_, event)| event).ok_or_else(|| ApiError::not_found("pending permission"))?;
                if !pending["options"].as_array().is_some_and(|options| options.iter().any(|o| o["id"] == option_id)) {
                    return Err(ApiError::BadRequest("invalid permission option".into()));
                }
                drop(log);
                permission(State(state.clone()), Path(id), Json(Choice { option_id })).await?;
            } else {
                conversation(&state, &session_id)?;
                background_turn::remote_decide(&state, &session_id, &request_id, &option_id).await?;
            }
            Ok(json!({"type":"DecisionAccepted"}))
        }
        Command::NotificationCancel { session_id, request_id } => {
            if session_id.starts_with("acp:") {
                state.external.record(&state.store, &session_id)?;
                let id = Uuid::parse_str(&request_id).map_err(|_| ApiError::BadRequest("invalid background request".into()))?;
                let jobs = state.background_commands.lock().await;
                let job = jobs.get(&id).filter(|job| job.session_id == session_id).ok_or_else(|| ApiError::not_found("background command"))?;
                if job.log.lock().await.done { return Err(ApiError::Conflict { code: "turn_changed".into(), message: "The active task has changed".into() }); }
                cancel_command(State(state.clone()), Path(id)).await?;
            } else {
                conversation(&state, &session_id)?;
                background_turn::remote_cancel(&state, &session_id, &request_id).await?;
            }
            Ok(json!({"type":"CancelAccepted"}))
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
    let tasks = state.tasks.clone();
    let conversation_id = conversation_id.to_owned();
    tasks.spawn(async move {
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

    async fn notification_command(state: &ApiState, command: Value) -> Value {
        let (_permission_tx, permissions) = mpsc::channel(1);
        let (updates, _update_rx) = mpsc::channel(1);
        dispatch(state.clone(), command, CancellationToken::new(), permissions, updates, ControlScope::Remote).await
    }

    fn insert_conversation(state: &ApiState, id: &str, project_id: Option<&str>) {
        if state.store.get_agent("test-agent").unwrap().is_none() {
            let agent: StoredAgent = serde_json::from_value(json!({
                "id":"test-agent", "name":"Test", "system_prompt":"", "created_at":1, "updated_at":1
            })).unwrap();
            state.store.upsert_agent(&agent).unwrap();
        }
        state.store.upsert_conversation(&StoredConversation {
            id: id.into(), title: format!("Title {id}"), project_id: project_id.map(str::to_owned),
            agent_id: "test-agent".into(), writable: true, ..Default::default()
        }).unwrap();
    }

    #[tokio::test]
    async fn notification_commands_open_decide_and_cancel_projectless_turns() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "token");
        insert_conversation(&state, "first", None);
        insert_conversation(&state, "second", None);
        state.store.upsert_message(&StoredMessage {
            id: "message".into(), conversation_id: "first".into(), role: "user".into(),
            content: "hello".into(), parts_json: None, timestamp: 1, status: "sent".into(), error_message: None,
        }).unwrap();
        let mut first_approval = background_turn::insert_test_turn_with_approval(&state, "first", "secret-first").await;
        let mut second_approval = background_turn::insert_test_turn_with_approval(&state, "second", "secret-second").await;
        let first_cancel = CancellationToken::new();
        let second_cancel = CancellationToken::new();
        state.active_turns.lock().await.insert("first".into(), first_cancel.clone());
        state.active_turns.lock().await.insert("second".into(), second_cancel.clone());

        let opened = notification_command(&state, json!({"action":"notificationOpen","sessionId":"first"})).await;
        assert_eq!(opened["type"], "Session");
        assert_eq!(opened["title"], "Title first");
        assert_eq!(opened["messages"][0]["text"], "hello");
        assert_eq!(opened["active"], true);
        assert_eq!(opened["requestId"], "test");
        assert_eq!(opened["events"][0]["text"], "working");
        assert_eq!(opened["events"][1]["type"], "Permission");
        assert_eq!(opened["events"][1]["options"][0]["id"], "approve:secret-first");

        let invalid = notification_command(&state, json!({"action":"notificationDecision","sessionId":"first","requestId":"test","optionId":"approve:secret-second"})).await;
        assert_eq!(invalid["type"], "Error");
        assert!(first_approval.try_recv().is_err());
        assert!(second_approval.try_recv().is_err());
        let stale = notification_command(&state, json!({"action":"notificationDecision","sessionId":"first","requestId":"old","optionId":"approve:secret-first"})).await;
        assert_eq!(stale["type"], "Error");
        let accepted = notification_command(&state, json!({"action":"notificationDecision","sessionId":"first","requestId":"test","optionId":"approve:secret-first"})).await;
        assert_eq!(accepted["type"], "DecisionAccepted");
        assert_eq!(first_approval.await.unwrap(), true);
        assert!(second_approval.try_recv().is_err());
        let repeated = notification_command(&state, json!({"action":"notificationDecision","sessionId":"first","requestId":"test","optionId":"approve:secret-first"})).await;
        assert_eq!(repeated["type"], "Error");

        let stale_cancel = notification_command(&state, json!({"action":"notificationCancel","sessionId":"first","requestId":"old"})).await;
        assert_eq!(stale_cancel["type"], "Error");
        assert!(!first_cancel.is_cancelled());
        let cancelled = notification_command(&state, json!({"action":"notificationCancel","sessionId":"first","requestId":"test"})).await;
        assert_eq!(cancelled["type"], "CancelAccepted");
        assert!(first_cancel.is_cancelled());
        assert!(!second_cancel.is_cancelled());
    }

    #[tokio::test]
    async fn notification_commands_open_exact_remote_project_without_expanding_catalog_scope() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "token");
        state.store.upsert_project(&StoredProject {
            id: "foreign".into(), name: "Foreign".into(), workspace: "/foreign".into(),
            device_id: Some("different-device".into()), created_at: 1, updated_at: 1,
        }).unwrap();
        insert_conversation(&state, "foreign-chat", Some("foreign"));
        let mut approval = background_turn::insert_test_turn_with_approval(&state, "foreign-chat", "foreign-call").await;
        let token = CancellationToken::new();
        state.active_turns.lock().await.insert("foreign-chat".into(), token.clone());
        let opened = notification_command(&state, json!({"action":"notificationOpen","sessionId":"foreign-chat"})).await;
        assert_eq!(opened["type"], "Session");
        let history = notification_command(&state, json!({"action":"history","sessionId":"foreign-chat"})).await;
        assert_eq!(history["type"], "Error");
        let decision = notification_command(&state, json!({"action":"notificationDecision","sessionId":"foreign-chat","requestId":"test","optionId":"approve:foreign-call"})).await;
        assert_eq!(decision["type"], "DecisionAccepted");
        assert_eq!(approval.try_recv().unwrap(), true);
        let cancelled = notification_command(&state, json!({"action":"notificationCancel","sessionId":"foreign-chat","requestId":"test"})).await;
        assert_eq!(cancelled["type"], "CancelAccepted");
        assert!(token.is_cancelled());
    }

    #[tokio::test]
    async fn notification_open_pages_and_bounds_projectless_history() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "token");
        insert_conversation(&state, "history", None);
        for index in 0..23 {
            state.store.upsert_message(&StoredMessage {
                id: format!("m{index:02}"), conversation_id: "history".into(), role: "assistant".into(),
                content: if index == 22 { "x".repeat(12_500) } else { format!("message {index}") },
                parts_json: None, timestamp: index, status: "sent".into(), error_message: None,
            }).unwrap();
        }
        let page = notification_command(&state, json!({"action":"notificationOpen","sessionId":"history"})).await;
        assert_eq!(page["messages"].as_array().unwrap().len(), 20);
        assert_eq!(page["messages"][0]["id"], "m03");
        assert_eq!(page["before"], "m03");
        assert_eq!(page["messages"][19]["text"].as_str().unwrap().chars().count(), 12_001);
        assert!(page["messages"][19]["text"].as_str().unwrap().ends_with('…'));

        let previous = notification_command(&state, json!({"action":"notificationOpen","sessionId":"history","before":"m03"})).await;
        assert_eq!(previous["messages"].as_array().unwrap().len(), 3);
        assert_eq!(previous["messages"][2]["id"], "m02");
        assert!(previous["before"].is_null());
    }

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
