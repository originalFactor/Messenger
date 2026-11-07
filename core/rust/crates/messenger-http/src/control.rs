//! Session commands shared by the local UI, ACP adapter and cloud relay.
use super::*;
use axum::body::to_bytes;
use messenger_remote::ControlHost;
use serde_json::{json, Value};

const CONFIG: &str = "device_control_config";
const GRANT: &str = "device_control_grant";

#[derive(Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct PlatformCatalog {
    pub workspace_supported: bool,
    pub tools: Vec<ToolSpecRequest>,
}

pub(crate) async fn platform_tools(
    State(state): State<ApiState>,
    Json(catalog): Json<PlatformCatalog>,
) -> StatusCode {
    *state.platform_catalog.lock().await = catalog;
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
        .unwrap_or_else(|| Ok(Settings::default()))
}

pub(crate) async fn get_settings(
    State(state): State<ApiState>,
) -> Result<Json<Settings>, ApiError> {
    let mut value = settings(&state.store)?;
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
    Prompt {
        session_id: String,
        text: String,
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
        dispatch(self.0.clone(), request, cancel, permissions, updates).await
    }
}

pub(crate) struct CommandHandle {
    permissions: mpsc::Sender<String>,
    cancel: CancellationToken,
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
    option_id: String,
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

async fn dispatch(
    state: ApiState,
    request: Value,
    cancel: CancellationToken,
    permissions: mpsc::Receiver<String>,
    updates: mpsc::Sender<Value>,
) -> Value {
    match execute(state, request, cancel, permissions, updates).await {
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
) -> Result<Value, ApiError> {
    if cancel.is_cancelled() {
        return Ok(json!({"type":"Cancelled"}));
    }
    let request: Command =
        serde_json::from_value(request).map_err(|err| ApiError::BadRequest(err.to_string()))?;
    match request {
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
                .into_iter().filter(|conversation| local_workspace(&state, conversation.project_id.as_deref()).is_ok())
                .take(100).map(|conversation| json!({"id":conversation.id,"title":short(&conversation.title, 160),"kind":"messenger"}));
            let profiles = settings(&state.store)?.agents;
            let external_agents = profiles.into_iter().map(
                |agent| json!({"id":format!("acp:{}",agent.id),"name":agent.name,"kind":"acp"}),
            );
            let external = state.external.sessions(&state.store)?
                .into_values().take(100).map(|session| json!({"id":session.id,"title":short(&session.title,160),"kind":"acp"}));
            Ok(
                json!({"type":"Catalog","agents":agents.chain(external_agents).take(100).collect::<Vec<_>>(),
                "projects":projects.take(100).collect::<Vec<_>>(),
                "sessions":sessions.chain(external).collect::<Vec<_>>() }),
            )
        }
        Command::History { session_id, before } => {
            let mut writable = None;
            let mut rows = if session_id.starts_with("acp:") {
                state.external.history(&state.store, &session_id)?
            } else {
                let conversation = conversation(&state, &session_id)?;
                writable = Some(conversation.writable);
                local_workspace(&state, conversation.project_id.as_deref())?;
                state.store.list_messages_by_conversation(&session_id).map_err(ApiError::store)?
                    .into_iter().map(|message| json!({
                        "id":message.id,"role":message.role,
                        "text":if let Some(error) = &message.error_message { error } else { &message.content },
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
                row["text"] = json!(short(row["text"].as_str().unwrap_or_default(), 36_000));
                encoded_size += row.to_string().len();
                if encoded_size > 235_000 {
                    break;
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
            local_workspace(&state, row.project_id.as_deref())?;
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
            let project = local_workspace(&state, project_id.as_deref())?;
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
        Command::Prompt { session_id, text } => {
            if text.trim().is_empty() || text.len() > 128_000 {
                return Err(ApiError::BadRequest(
                    "prompt must contain 1–128000 bytes of text".into(),
                ));
            }
            if session_id.starts_with("acp:") {
                return state
                    .external
                    .prompt(&state, session_id, text, cancel, permissions, updates)
                    .await;
            }
            let conversation = conversation(&state, &session_id)?;
            local_workspace(&state, conversation.project_id.as_deref())?;
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
        content: Some(text),
        parts_json: None,
        message_group: None,
        operation: ChatOperation::Send,
        disable_tools: false,
        message_id: None,
        tool_specs: Vec::new(),
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
