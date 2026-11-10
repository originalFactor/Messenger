//! ACP v1 client for device-installed agents.
use super::*;
use agent_client_protocol::{
    schema::{v1::*, ProtocolVersion},
    Agent, ByteStreams, Client, ConnectionTo,
};
use serde_json::{json, Value};
use std::{collections::HashSet, path::PathBuf, time::Duration};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio_util::compat::{TokioAsyncReadCompatExt, TokioAsyncWriteCompatExt};

const SESSIONS: &str = "local_acp_sessions";

#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Profile {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default = "profile_enabled")]
    pub enabled: bool,
    command: String,
    #[serde(default)]
    args: Vec<String>,
    #[serde(default)]
    env: HashMap<String, String>,
    cwd: Option<String>,
}

fn profile_enabled() -> bool { true }

pub(crate) fn default_profiles() -> Vec<Profile> {
    [
        ("codex", "Codex", "OpenAI coding agent via ACP", "pnpm", vec!["dlx", "@agentclientprotocol/codex-acp"]),
        ("claude-code", "Claude Code", "Claude coding agent via ACP", "pnpm", vec!["dlx", "@agentclientprotocol/claude-agent-acp"]),
        ("opencode", "OpenCode", "OpenCode coding agent via ACP", "opencode", vec!["acp"]),
    ].into_iter().map(|(id, name, description, command, args)| Profile {
        id: id.into(), name: name.into(), description: description.into(), enabled: true,
        command: command.into(), args: args.into_iter().map(str::to_string).collect(),
        env: HashMap::new(), cwd: None,
    }).collect()
}

pub(crate) fn validate_profiles(profiles: &[Profile]) -> Result<(), ApiError> {
    let mut ids = HashSet::new();
    if profiles.len() > 50
        || profiles.iter().any(|profile| {
            profile.id.is_empty()
                || profile.id.len() > 80
                || !ids.insert(&profile.id)
                || profile.name.trim().is_empty()
                || profile.name.len() > 120
                || profile.description.len() > 4000
                || profile.command.trim().is_empty()
                || profile.command.contains('\0')
                || profile
                    .cwd
                    .as_ref()
                    .is_some_and(|cwd| !std::path::Path::new(cwd).is_absolute())
                || profile.args.iter().any(|arg| arg.contains('\0'))
                || profile.env.iter().any(|(key, value)| {
                    key.is_empty() || key.contains(['=', '\0']) || value.contains('\0')
                })
        })
    {
        return Err(ApiError::BadRequest("invalid ACP agent profile".into()));
    }
    Ok(())
}

#[derive(Clone, Deserialize, Serialize)]
pub(crate) struct Record {
    pub id: String,
    pub title: String,
    profile: String,
    pub project: Option<String>,
    #[serde(default)]
    pub updated_at: i64,
    cwd: PathBuf,
    agent_session: SessionId,
    pub messages: Vec<Value>,
}

struct Prompt {
    text: String,
    cancel: CancellationToken,
    permissions: mpsc::Receiver<String>,
    updates: mpsc::Sender<Value>,
    answer: Arc<std::sync::Mutex<String>>,
    done: oneshot::Sender<Value>,
}

struct LiveTurn {
    cancel: CancellationToken,
    permissions: Arc<Mutex<mpsc::Receiver<String>>>,
    updates: mpsc::Sender<Value>,
    answer: Arc<std::sync::Mutex<String>>,
}

#[derive(Default)]
struct Output {
    replay: Option<Vec<Value>>,
    turn: Option<LiveTurn>,
}

#[derive(Default)]
pub(crate) struct ExternalSessions {
    records: std::sync::Mutex<()>,
    workers: Mutex<HashMap<String, mpsc::Sender<Prompt>>>,
}

impl ExternalSessions {
    pub fn sessions(&self, store: &Store) -> Result<HashMap<String, Record>, ApiError> {
        store
            .kv_get(SESSIONS)
            .map_err(ApiError::store)?
            .map(|value| serde_json::from_str(&value).map_err(ApiError::store))
            .unwrap_or_else(|| Ok(HashMap::new()))
    }

    fn save(&self, store: &Store, mut record: Record) -> Result<(), ApiError> {
        let _write = self.records.lock().unwrap();
        let mut records = self.sessions(store)?;
        record.updated_at = now_ms();
        records.insert(record.id.clone(), record);
        store
            .kv_set(
                SESSIONS,
                &serde_json::to_string(&records).map_err(ApiError::store)?,
            )
            .map_err(ApiError::store)
    }

    #[cfg(test)]
    fn history(&self, store: &Store, id: &str) -> Result<Vec<Value>, ApiError> {
        Ok(self.record(store, id)?.messages)
    }

    pub fn record(&self, store: &Store, id: &str) -> Result<Record, ApiError> {
        self.sessions(store)?
            .remove(id)
            .ok_or_else(|| ApiError::not_found("ACP session"))
    }

    pub fn conversations(&self, store: &Store) -> Result<Vec<StoredConversation>, ApiError> {
        self.sessions(store)?.into_values().map(|record| Self::conversation(&record)).collect()
    }

    pub fn conversation(record: &Record) -> Result<StoredConversation, ApiError> {
        serde_json::from_value(json!({
            "id": record.id, "title": record.title, "agent_id": format!("acp:{}", record.profile),
            "provider_id": "", "project_id": record.project, "created_at": record.updated_at,
            "updated_at": record.updated_at, "last_message": record.messages.last().and_then(|row| row["text"].as_str())
        })).map_err(ApiError::internal)
    }

    pub async fn update_conversation(&self, state: &ApiState, row: &StoredConversation) -> Result<(), ApiError> {
        let active = state.active_turns.lock().await;
        if active.contains_key(&row.id) {
            return Err(ApiError::Conflict { code: "turn_in_progress".into(), message: "The ACP conversation is generating.".into() });
        }
        let mut record = self.record(&state.store, &row.id)?;
        if row.agent_id != format!("acp:{}", record.profile) || row.project_id != record.project {
            return Err(ApiError::BadRequest("ACP conversations keep their original agent and workspace".into()));
        }
        record.title = row.title.clone();
        self.save(&state.store, record)
    }

    pub async fn delete_conversation(&self, state: &ApiState, id: &str, messages_only: bool) -> Result<(), ApiError> {
        let active = state.active_turns.lock().await;
        if active.contains_key(id) {
            return Err(ApiError::Conflict { code: "turn_in_progress".into(), message: "Stop the ACP turn before deleting its conversation.".into() });
        }
        self.workers.lock().await.remove(id);
        let _write = self.records.lock().unwrap();
        let mut records = self.sessions(&state.store)?;
        if messages_only {
            if let Some(record) = records.get_mut(id) { record.messages.clear(); }
        } else { records.remove(id); }
        state.store.kv_set(SESSIONS, &serde_json::to_string(&records).map_err(ApiError::internal)?).map_err(ApiError::store)
    }

    pub async fn create(
        &self,
        state: &ApiState,
        profile: &str,
        project: Option<StoredProject>,
        cancel: &CancellationToken,
    ) -> Result<String, ApiError> {
        let profile = profile_for(state, profile)?;
        let cwd = match project
            .as_ref()
            .map(|project| PathBuf::from(&project.workspace))
            .or_else(|| profile.cwd.as_ref().map(PathBuf::from))
        {
            Some(cwd) => cwd,
            None => default_cwd()?,
        };
        let id = format!("acp:{}", Uuid::new_v4());
        let (worker, agent_session, _) =
            start_worker(state, profile.clone(), cwd.clone(), None, cancel).await?;
        self.save(
            &state.store,
            Record {
                id: id.clone(),
                title: profile.name,
                profile: profile.id,
                project: project.map(|project| project.id),
                updated_at: 0,
                cwd,
                agent_session,
                messages: Vec::new(),
            },
        )?;
        self.workers.lock().await.insert(id.clone(), worker);
        Ok(id)
    }

    pub async fn prompt(
        &self,
        state: &ApiState,
        id: String,
        text: String,
        cancel: CancellationToken,
        permissions: mpsc::Receiver<String>,
        updates: mpsc::Sender<Value>,
    ) -> Result<Value, ApiError> {
        {
            let mut active = state.active_turns.lock().await;
            if active.contains_key(&id) {
                return Err(ApiError::Conflict {
                    code: "turn_in_progress".into(),
                    message: "a chat turn is already active for this conversation".into(),
                });
            }
            active.insert(id.clone(), cancel.clone());
        }
        let result = self
            .run_prompt(state, &id, text, cancel, permissions, updates)
            .await;
        state.active_turns.lock().await.remove(&id);
        result
    }

    async fn run_prompt(
        &self,
        state: &ApiState,
        id: &str,
        text: String,
        cancel: CancellationToken,
        permissions: mpsc::Receiver<String>,
        updates: mpsc::Sender<Value>,
    ) -> Result<Value, ApiError> {
        let mut record = self.record(&state.store, id)?;
        let profile = profile_for(state, &record.profile)?;
        if let Some(project) = crate::control::local_workspace(state, record.project.as_deref())? {
            if PathBuf::from(&project.workspace) != record.cwd {
                return Err(ApiError::BadRequest(
                    "the ACP session workspace has moved".into(),
                ));
            }
        }
        let previous = self.workers.lock().await.get(id).cloned();
        let worker = if let Some(worker) = previous.filter(|worker| !worker.is_closed()) {
            worker
        } else {
            let (worker, _, replay) = start_worker(
                state,
                profile,
                record.cwd.clone(),
                Some(record.agent_session.clone()),
                &cancel,
            )
            .await?;
            if let Some(replay) = replay {
                record.messages = replay;
            }
            self.workers
                .lock()
                .await
                .insert(id.to_owned(), worker.clone());
            worker
        };
        if cancel.is_cancelled() {
            return Ok(json!({"type":"Cancelled"}));
        }
        if record.messages.is_empty() {
            record.title = crate::control::short(&text, 80);
        }
        record.messages.push(message("user", &text, "sent"));
        self.save(&state.store, record.clone())?;
        let (done, reply) = oneshot::channel();
        let answer = Arc::new(std::sync::Mutex::new(String::new()));
        let _ = updates.send(json!({"type":"TurnStarted"})).await;
        let job = Prompt {
            text,
            cancel,
            permissions,
            updates,
            answer: answer.clone(),
            done,
        };
        let outcome = if worker.send(job).await.is_ok() {
            reply.await.ok()
        } else {
            None
        };
        let result = outcome.unwrap_or_else(
            || json!({"type":"Error","code":"agent_exited","message":"The ACP agent exited."}),
        );
        let answer = std::mem::take(&mut *answer.lock().unwrap());
        if !answer.is_empty() {
            record.messages.push(message("assistant", &answer, "sent"));
        }
        if result["type"] == "Error" {
            record.messages.push(message(
                "assistant",
                result["message"].as_str().unwrap_or("Agent error"),
                "error",
            ));
        }
        self.save(&state.store, record)?;
        Ok(result)
    }
}

fn message(role: &str, text: &str, status: &str) -> Value {
    json!({"id":Uuid::new_v4(),"role":role,"text":text,"status":status})
}

fn profile_for(state: &ApiState, id: &str) -> Result<Profile, ApiError> {
    crate::control::settings(&state.store)?
        .agents
        .into_iter()
        .find(|profile| profile.id == id && profile.enabled)
        .ok_or_else(|| ApiError::not_found("ACP agent profile"))
}

fn default_cwd() -> Result<PathBuf, ApiError> {
    #[cfg(target_os = "android")]
    {
        Err(ApiError::BadRequest(
            "select a Runtime project or configure its absolute workspace path".into(),
        ))
    }
    #[cfg(not(target_os = "android"))]
    {
        std::env::var_os("HOME")
            .or_else(|| std::env::var_os("USERPROFILE"))
            .map(PathBuf::from)
            .filter(|path| path.is_absolute())
            .ok_or_else(|| {
                ApiError::BadRequest("configure an absolute ACP working directory".into())
            })
    }
}

async fn start_worker(
    state: &ApiState,
    profile: Profile,
    cwd: PathBuf,
    existing: Option<SessionId>,
    cancel: &CancellationToken,
) -> Result<(mpsc::Sender<Prompt>, SessionId, Option<Vec<Value>>), ApiError> {
    let (input, mut prompts) = mpsc::channel::<Prompt>(1);
    let (ready, initialized) = oneshot::channel();
    let (failure, mut failed) = oneshot::channel();
    let lifetime = state.shutdown.child_token();
    let mut start_guard = Some(lifetime.clone().drop_guard());
    let transport = spawn_process(state, &profile, &cwd).await?;
    let output = Arc::new(Mutex::new(Output::default()));
    let notify_output = output.clone();
    let permission_output = output.clone();
    tokio::spawn(async move {
        let Process {
            stdin,
            stdout,
            _lifetime,
        } = transport;
        let connection = Client
            .builder()
            .name("Messenger")
            .on_receive_notification(
                async move |event: SessionNotification, _| {
                    handle_notification(&notify_output, event).await;
                    Ok(())
                },
                agent_client_protocol::on_receive_notification!(),
            )
            .on_receive_request(
                async move |request: RequestPermissionRequest, responder, _| {
                    responder.respond(RequestPermissionResponse::new(
                        choose(&permission_output, request).await,
                    ))
                },
                agent_client_protocol::on_receive_request!(),
            )
            .connect_with(
                ByteStreams::new(stdin.compat_write(), stdout.compat()),
                async move |connection: ConnectionTo<Agent>| {
                    let init = connection
                        .send_request(InitializeRequest::new(ProtocolVersion::V1))
                        .block_task()
                        .await?;
                    if init.protocol_version != ProtocolVersion::V1 {
                        return Err(agent_client_protocol::Error::new(
                            -32602,
                            "Unsupported ACP protocol version",
                        ));
                    }
                    let session = match existing {
                        Some(id) => {
                            if init
                                .agent_capabilities
                                .session_capabilities
                                .resume
                                .is_some()
                            {
                                connection
                                    .send_request(ResumeSessionRequest::new(id.clone(), cwd))
                                    .block_task()
                                    .await?;
                            } else if init.agent_capabilities.load_session {
                                output.lock().await.replay = Some(Vec::new());
                                connection
                                    .send_request(LoadSessionRequest::new(id.clone(), cwd))
                                    .block_task()
                                    .await?;
                            } else {
                                return Err(agent_client_protocol::Error::new(
                                    -32000,
                                    "This ACP agent cannot restore sessions. Create a new session.",
                                ));
                            }
                            id
                        }
                        None => {
                            connection
                                .send_request(NewSessionRequest::new(cwd))
                                .block_task()
                                .await?
                                .session_id
                        }
                    };
                    let replay = output.lock().await.replay.take();
                    let _ = ready.send((session.clone(), replay));
                    loop {
                        let prompt = tokio::select! {
                            prompt = prompts.recv() => prompt,
                            _ = connection.incoming_closed() => None,
                        };
                        let Some(prompt) = prompt else { break };
                        let keep = run_prompt(&connection, &session, &output, prompt).await;
                        if !keep {
                            break;
                        }
                    }
                    Ok(())
                },
            );
        let error = tokio::select! {
            result = connection => result.err().map(|err| err.to_string()),
            _ = lifetime.cancelled() => Some("The ACP process was cancelled.".into()),
        };
        let _ = failure.send(error.unwrap_or_else(|| "The ACP agent exited.".into()));
    });
    tokio::select! {
        biased;
        result = initialized => {
            match result {
                Ok((id, history)) => {
                    if let Some(guard) = start_guard.take() { guard.disarm(); }
                    Ok((input, id, history))
                }
                Err(_) => Err(ApiError::internal((&mut failed).await.unwrap_or_else(|_| "The ACP agent exited.".into()))),
            }
        }
        _ = cancel.cancelled() => Err(ApiError::internal("ACP session setup cancelled")),
        _ = tokio::time::sleep(Duration::from_secs(30)) => Err(ApiError::internal("ACP session setup timed out")),
    }
}

async fn run_prompt(
    connection: &ConnectionTo<Agent>,
    session: &SessionId,
    output: &Mutex<Output>,
    prompt: Prompt,
) -> bool {
    let Prompt {
        text,
        cancel,
        permissions,
        updates,
        answer,
        done,
    } = prompt;
    output.lock().await.turn = Some(LiveTurn {
        cancel: cancel.clone(),
        permissions: Arc::new(Mutex::new(permissions)),
        updates,
        answer,
    });
    let request = connection
        .send_request(PromptRequest::new(
            session.clone(),
            vec![ContentBlock::Text(TextContent::new(text))],
        ))
        .block_task();
    tokio::pin!(request);
    let (result, keep) = tokio::select! {
        result = &mut request => {
            match result {
                Ok(result) if result.stop_reason == StopReason::Cancelled =>
                    (json!({"type":"Cancelled"}), true),
                Ok(result) => (json!({"type":"Finished","stopReason":result.stop_reason}), true),
                Err(error) => (json!({"type":"Error","code":"acp_error","message":error.to_string()}), false),
            }
        }
        _ = cancel.cancelled() => {
            let _ = connection.send_notification(CancelNotification::new(session.clone()));
            let keep = tokio::time::timeout(Duration::from_secs(2), request).await.is_ok_and(|r| r.is_ok());
            (json!({"type":"Cancelled"}), keep)
        }
    };
    output.lock().await.turn.take();
    let _ = done.send(result);
    keep
}

async fn handle_notification(output: &Mutex<Output>, event: SessionNotification) {
    let Ok(update) = serde_json::to_value(event.update) else {
        return;
    };
    let kind = update["sessionUpdate"].as_str().unwrap_or_default();
    let text = update["content"]["text"].as_str().unwrap_or_default();
    let mut output = output.lock().await;
    if let Some(replay) = &mut output.replay {
        let role = match kind {
            "user_message_chunk" => "user",
            "agent_message_chunk" => "assistant",
            _ => return,
        };
        let message_id = update["messageId"].as_str();
        if let Some(last) = replay.last_mut().filter(|last| {
            last["role"] == role && (message_id.is_none() || last["id"].as_str() == message_id)
        }) {
            let previous = last["text"].as_str().unwrap_or_default();
            last["text"] = json!(format!("{previous}{text}"));
        } else {
            let mut row = message(role, text, "sent");
            if let Some(id) = message_id {
                row["id"] = json!(id);
            }
            replay.push(row);
        }
    } else if let Some(turn) = &mut output.turn {
        if kind == "agent_message_chunk" {
            turn.answer.lock().unwrap().push_str(text);
        }
        let data = match kind {
            "agent_message_chunk" => json!({"type":"TextDelta","text":text}),
            "agent_thought_chunk" => json!({"type":"ThoughtDelta","text":text}),
            "tool_call" | "tool_call_update" => json!({"type":"ToolUpdate","update":{
                "toolCallId":update["toolCallId"],"title":update["title"],"status":update["status"]
            }}),
            _ => return,
        };
        let updates = turn.updates.clone();
        let cancel = turn.cancel.clone();
        drop(output);
        tokio::select! {
            _ = updates.send(data) => {},
            _ = cancel.cancelled() => {},
        }
    }
}

async fn choose(
    output: &Mutex<Output>,
    request: RequestPermissionRequest,
) -> RequestPermissionOutcome {
    let (cancel, choices, updates) = {
        let output = output.lock().await;
        let Some(turn) = &output.turn else {
            return RequestPermissionOutcome::Cancelled;
        };
        (
            turn.cancel.clone(),
            turn.permissions.clone(),
            turn.updates.clone(),
        )
    };
    let mut choices = choices.lock().await;
    let id = Uuid::new_v4();
    let options: Vec<_> = request
        .options
        .iter()
        .enumerate()
        .map(|(index, option)| json!({
            "id":format!("{id}/{index}"), "name":crate::control::short(&option.name, 180), "kind":option.kind
        }))
        .collect();
    let tool = serde_json::to_value(&request.tool_call).unwrap_or_default();
    let context = json!({
        "title":crate::control::short(tool["title"].as_str().unwrap_or_default(), 180),
        "detail":if tool["rawInput"].is_null() { String::new() }
            else { crate::control::short(&tool["rawInput"].to_string(), 1500) },
    });
    let wait = async {
        updates
            .send(json!({"type":"Permission","options":options,"tool":context}))
            .await
            .ok()?;
        while let Some(choice) = choices.recv().await {
            if let Some(index) = options.iter().position(|option| option["id"] == choice) {
                return Some(request.options[index].option_id.clone());
            }
        }
        None
    };
    tokio::select! {
        option = wait => option.map(|id| RequestPermissionOutcome::Selected(SelectedPermissionOutcome::new(id)))
            .unwrap_or(RequestPermissionOutcome::Cancelled),
        _ = cancel.cancelled() => RequestPermissionOutcome::Cancelled,
        _ = tokio::time::sleep(Duration::from_secs(120)) => RequestPermissionOutcome::Cancelled,
    }
}

pub(crate) struct Process {
    pub(crate) stdin: Box<dyn AsyncWrite + Unpin + Send>,
    pub(crate) stdout: Box<dyn AsyncRead + Unpin + Send>,
    pub(crate) _lifetime: Box<dyn Send>,
}

#[cfg(not(target_os = "android"))]
async fn spawn_process(
    _state: &ApiState,
    profile: &Profile,
    cwd: &std::path::Path,
) -> Result<Process, ApiError> {
    use std::process::Stdio;
    let command = if cfg!(target_os = "windows") && profile.command == "pnpm" { "pnpm.cmd" } else { &profile.command };
    let mut child = tokio::process::Command::new(command)
        .args(&profile.args)
        .envs(&profile.env)
        .current_dir(cwd)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .map_err(ApiError::internal)?;
    let stdin = Box::new(child.stdin.take().expect("piped stdin"));
    let stdout = Box::new(child.stdout.take().expect("piped stdout"));
    Ok(Process {
        stdin,
        stdout,
        _lifetime: Box::new(child),
    })
}

#[cfg(target_os = "android")]
async fn spawn_process(
    state: &ApiState,
    profile: &Profile,
    cwd: &std::path::Path,
) -> Result<Process, ApiError> {
    crate::android_acp::spawn(state, profile, cwd).await
}

#[cfg(all(test, unix, not(target_os = "android")))]
mod tests {
    use super::*;
    use tower::ServiceExt;

    async fn setup(mode: &str) -> (ApiState, String) {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "local");
        let fixture = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/acp_agent.py");
        let response = router(state.clone())
            .oneshot(
                axum::http::Request::builder()
                    .uri("/api/v1/control/settings")
                    .method("PUT")
                    .header("authorization", "Bearer local")
                    .header("content-type", "application/json")
                    .body(axum::body::Body::from(
                        json!({"agents":[{
                            "id":"fixture","name":"Fixture","command":"python3",
                            "args":[fixture,mode],"cwd":std::env::temp_dir()
                        }]})
                        .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let session = state
            .external
            .create(&state, "fixture", None, &CancellationToken::new())
            .await
            .unwrap();
        (state, session)
    }

    #[tokio::test]
    async fn acp_sessions_are_regular_conversation_entries_without_sync_rows() {
        let (state, session) = setup("load").await;
        let app = router(state.clone());
        let response = app.oneshot(axum::http::Request::builder()
            .uri("/api/v1/conversations").header("authorization", "Bearer local")
            .body(axum::body::Body::empty()).unwrap()).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = axum::body::to_bytes(response.into_body(), 65536).await.unwrap();
        let page: Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(page["items"][0]["id"], session);
        let mut row = ExternalSessions::conversation(&state.external.record(&state.store, &session).unwrap()).unwrap();
        row.title = "Renamed ACP conversation".into();
        state.external.update_conversation(&state, &row).await.unwrap();
        assert_eq!(state.external.record(&state.store, &session).unwrap().title, row.title);
        assert!(state.store.list_conversations().unwrap().is_empty(), "ACP sessions must never become Cloud sync rows");
        state.active_turns.lock().await.insert(session.clone(), CancellationToken::new());
        assert!(state.external.delete_conversation(&state, &session, false).await.is_err());
        state.active_turns.lock().await.remove(&session);
        state.external.delete_conversation(&state, &session, false).await.unwrap();
        assert!(state.external.record(&state.store, &session).is_err());
        state.shutdown.cancel();
    }

    #[tokio::test]
    async fn acp_templates_are_seeded_once_and_preserve_user_configuration() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "local");
        let settings = crate::control::get_settings(State(state.clone())).await.unwrap().0;
        assert!(!settings.enabled);
        assert_eq!(settings.agents.len(), 3);
        assert_eq!(settings.agents.iter().map(|profile| profile.name.as_str()).collect::<Vec<_>>(), ["Codex", "Claude Code", "OpenCode"]);
        let mut settings = settings;
        settings.name = "My device".into();
        settings.agents.remove(0);
        settings.agents[0].description = "My coding assistant".into();
        settings.agents[0].enabled = false;
        let _ = crate::control::set_settings(State(state.clone()), Json(settings)).await.unwrap();
        let reloaded = crate::control::get_settings(State(state.clone())).await.unwrap().0;
        assert_eq!(reloaded.agents.len(), 2, "deleted templates must not reappear");
        assert_eq!(reloaded.name, "My device");
        assert_eq!(reloaded.agents[0].description, "My coding assistant");
        assert!(!reloaded.agents[0].enabled);
        assert!(profile_for(&state, &reloaded.agents[0].id).is_err());
    }

    #[tokio::test]
    async fn installed_agent_can_stream_authorize_cancel_and_replay() {
        tokio::time::timeout(Duration::from_secs(15), async {
            let (state, session) = setup("load").await;
            let (choices, permissions) = mpsc::channel(8);
            let (updates, mut events) = mpsc::channel(8);
            let cancel = CancellationToken::new();
            let turn = state.external.prompt(
                &state,
                session.clone(),
                "one".into(),
                cancel,
                permissions,
                updates,
            );
            let allow = async {
                assert_eq!(events.recv().await.unwrap()["type"], "TurnStarted");
                let ask = events.recv().await.unwrap();
                assert_eq!(ask["type"], "Permission");
                choices.send("expired/yes".into()).await.unwrap();
                choices
                    .send(ask["options"][0]["id"].as_str().unwrap().into())
                    .await
                    .unwrap();
                assert_eq!(events.recv().await.unwrap()["text"], "selected");
            };
            let (result, _) = tokio::join!(turn, allow);
            assert_eq!(result.unwrap()["type"], "Finished");
            assert_eq!(
                state.external.history(&state.store, &session).unwrap()[1]["text"],
                "selected"
            );

            state.external.workers.lock().await.remove(&session);
            let (_choices, permissions) = mpsc::channel(8);
            let (updates, mut events) = mpsc::channel(8);
            let cancel = CancellationToken::new();
            let turn = state.external.prompt(
                &state,
                session.clone(),
                "wait".into(),
                cancel.clone(),
                permissions,
                updates,
            );
            let interrupt = async {
                assert_eq!(events.recv().await.unwrap()["type"], "TurnStarted");
                assert_eq!(events.recv().await.unwrap()["text"], "partial");
                cancel.cancel();
            };
            let (result, _) = tokio::join!(turn, interrupt);
            assert_eq!(result.unwrap()["type"], "Cancelled");
            let history = state.external.history(&state.store, &session).unwrap();
            assert_eq!(
                history
                    .iter()
                    .map(|row| row["text"].as_str().unwrap())
                    .collect::<Vec<_>>(),
                ["replayed question", "replayed answer", "wait", "partial"]
            );
            state.shutdown.cancel();
        })
        .await
        .unwrap();
    }

    #[tokio::test]
    async fn an_unrestorable_agent_keeps_existing_history_without_new_prompt() {
        let (state, session) = setup("no-resume").await;
        state.external.workers.lock().await.remove(&session);
        let (_choices, permissions) = mpsc::channel(8);
        let (updates, _) = mpsc::channel(8);
        let error = state
            .external
            .prompt(
                &state,
                session.clone(),
                "never sent".into(),
                CancellationToken::new(),
                permissions,
                updates,
            )
            .await
            .unwrap_err();
        assert!(format!("{error:?}").contains("cannot restore sessions"));
        assert!(state
            .external
            .history(&state.store, &session)
            .unwrap()
            .is_empty());
        state.shutdown.cancel();
    }
}
