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

fn acp_mcp_servers(
    configs: &[messenger_mcp::McpServerConfig],
    supports_sse: bool,
) -> Result<Vec<McpServer>, agent_client_protocol::Error> {
    configs.iter().filter(|config| config.is_enabled).map(|config| {
        match config.transport_type {
            messenger_mcp::McpTransportType::STDIO => Ok(McpServer::Stdio(
                McpServerStdio::new(&config.name, &config.command)
                    .args(config.args.clone())
                    .env(config.env.iter().map(|(name, value)| EnvVariable::new(name, value)).collect()),
            )),
            messenger_mcp::McpTransportType::SSE if supports_sse => Ok(McpServer::Sse(
                McpServerSse::new(&config.name, &config.url)
                    .headers(config.headers.iter().map(|(name, value)| HttpHeader::new(name, value)).collect()),
            )),
            messenger_mcp::McpTransportType::SSE => Err(agent_client_protocol::Error::new(
                -32000, format!("ACP agent does not support the SSE MCP server: {}", config.name),
            )),
        }
    }).collect()
}

#[cfg(test)]
mod mcp_injection_tests {
    use super::*;
    use messenger_mcp::{McpServerConfig, McpTransportType};

    #[test]
    fn enabled_servers_are_forwarded_with_credentials_and_disabled_are_omitted() {
        let stdio = McpServerConfig {
            name: "local".into(), command: "server".into(), args: vec!["--stdio".into()],
            env: [("TOKEN".into(), "secret".into())].into_iter().collect(), ..Default::default()
        };
        let sse = McpServerConfig {
            name: "remote".into(), transport_type: McpTransportType::SSE,
            url: "https://example.org/sse".into(),
            headers: [("Authorization".into(), "secret".into())].into_iter().collect(),
            ..Default::default()
        };
        let disabled = McpServerConfig { name: "disabled".into(), is_enabled: false, ..Default::default() };
        let result = acp_mcp_servers(&[stdio, sse, disabled], true).unwrap();
        assert_eq!(result.len(), 2);
        let values = serde_json::to_value(result).unwrap();
        assert_eq!(values[0]["env"][0]["value"], "secret");
        assert_eq!(values[1]["headers"][0]["value"], "secret");
    }

    #[test]
    fn unsupported_sse_is_reported() {
        let server = McpServerConfig { name: "remote".into(), transport_type: McpTransportType::SSE, ..Default::default() };
        assert!(acp_mcp_servers(&[server], false).is_err());
    }
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Profile {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default = "profile_enabled")]
    pub enabled: bool,
    #[serde(default, rename = "providerTakeover")]
    pub provider_takeover: bool,
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
        id: id.into(), name: name.into(), description: description.into(), enabled: false, provider_takeover: false,
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
                || (profile.provider_takeover && !matches!(profile.id.as_str(), "codex" | "claude-code" | "opencode"))
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
    #[serde(default)]
    delegated: bool,
    pub messages: Vec<Value>,
}

#[derive(Clone)]
struct Worker {
    requests: mpsc::Sender<WorkerRequest>,
    lifetime: CancellationToken,
    output: Arc<Mutex<Output>>,
}

enum WorkerRequest {
    Prompt(Prompt),
    Steer { content: Vec<ContentBlock>, done: oneshot::Sender<Result<Value, String>> },
    Configure { change: Value, done: oneshot::Sender<Result<(), String>> },
}

struct Prompt {
    content: Vec<ContentBlock>,
    cancel: CancellationToken,
    permissions: mpsc::Receiver<String>,
    updates: mpsc::Sender<Value>,
    answer: Arc<std::sync::Mutex<String>>,
    transcript: Arc<std::sync::Mutex<Vec<Value>>>,
    done: oneshot::Sender<Value>,
}

struct LiveTurn {
    cancel: CancellationToken,
    tool_cancel: CancellationToken,
    permissions: Arc<Mutex<mpsc::Receiver<String>>>,
    updates: mpsc::Sender<Value>,
    answer: Arc<std::sync::Mutex<String>>,
    transcript: Arc<std::sync::Mutex<Vec<Value>>>,
}

#[derive(Default)]
pub(crate) struct Output {
    replay: Option<Vec<Value>>,
    turn: Option<LiveTurn>,
    settings: Value,
}

pub(crate) async fn active_cancel(output: &Mutex<Output>) -> Option<CancellationToken> {
    output.lock().await.turn.as_ref().map(|turn| turn.tool_cancel.clone()).filter(|cancel| !cancel.is_cancelled())
}

#[derive(Default)]
pub(crate) struct ExternalSessions {
    records: std::sync::Mutex<()>,
    workers: Mutex<HashMap<String, Worker>>,
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
        self.create_with_delegation(state, profile, project, cancel, true).await
    }

    pub(crate) async fn create_with_delegation(
        &self, state: &ApiState, profile: &str, project: Option<StoredProject>,
        cancel: &CancellationToken, allow_delegation: bool,
    ) -> Result<String, ApiError> {
        let project = project.ok_or_else(|| ApiError::BadRequest(
            "ACP conversations require a project".into(),
        ))?;
        let profile = profile_for(state, profile)?;
        let cwd = PathBuf::from(&project.workspace);
        let id = format!("acp:{}", Uuid::new_v4());
        let (worker, agent_session, _) =
            start_worker(state, profile.clone(), cwd.clone(), None, cancel, Some(project.clone()), allow_delegation).await?;
        self.save(
            &state.store,
            Record {
                id: id.clone(),
                title: profile.name,
                profile: profile.id,
                project: Some(project.id),
                updated_at: 0,
                cwd,
                agent_session,
                delegated: !allow_delegation,
                messages: Vec::new(),
            },
        )?;
        self.workers.lock().await.insert(id.clone(), worker);
        Ok(id)
    }

    pub async fn steer(&self, _state: &ApiState, id: &str, content: Vec<ContentBlock>) -> Result<Value, ApiError> {
        let worker = self.workers.lock().await.get(id).cloned().ok_or_else(|| ApiError::BadRequest("No running ACP session".into()))?;
        let (done, reply) = oneshot::channel();
        worker.requests.send(WorkerRequest::Steer { content, done }).await.map_err(ApiError::internal)?;
        let result = tokio::time::timeout(Duration::from_secs(30), reply).await.map_err(ApiError::internal)?.map_err(ApiError::internal)?.map_err(ApiError::BadRequest)?;
        Ok(json!({"type":"Steering","result":result}))
    }

    pub async fn prompt(
        &self,
        state: &ApiState,
        id: String,
        text: String,
        mut content: Vec<ContentBlock>,
        cancel: CancellationToken,
        permissions: mpsc::Receiver<String>,
        updates: mpsc::Sender<Value>,
    ) -> Result<Value, ApiError> {
        if content.is_empty() { content.push(ContentBlock::Text(TextContent::new(text.clone()))); }
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
            .run_prompt(state, &id, text, content, cancel, permissions, updates)
            .await;
        state.active_turns.lock().await.remove(&id);
        result
    }

    async fn run_prompt(
        &self,
        state: &ApiState,
        id: &str,
        text: String,
        content: Vec<ContentBlock>,
        cancel: CancellationToken,
        permissions: mpsc::Receiver<String>,
        updates: mpsc::Sender<Value>,
    ) -> Result<Value, ApiError> {
        let mut record = self.record(&state.store, id)?;
        if let Some(project) = crate::control::local_workspace(state, record.project.as_deref())? {
            if PathBuf::from(&project.workspace) != record.cwd {
                return Err(ApiError::BadRequest(
                    "the ACP session workspace has moved".into(),
                ));
            }
        }
        let (worker, replay) = self.worker(state, &record, &cancel).await?;
        if let Some(replay) = replay { record.messages = replay; }
        if cancel.is_cancelled() {
            return Ok(json!({"type":"Cancelled"}));
        }
        if record.messages.is_empty() {
            record.title = crate::control::short(&text, 80);
        }
        let mut user = message("user", &text, "sent");
        user["content"] = json!(content);
        record.messages.push(user);
        self.save(&state.store, record.clone())?;
        let (done, reply) = oneshot::channel();
        let answer = Arc::new(std::sync::Mutex::new(String::new()));
        let transcript = Arc::new(std::sync::Mutex::new(Vec::new()));
        let _ = updates.send(json!({"type":"TurnStarted"})).await;
        let job = Prompt {
            content,
            cancel,
            permissions,
            updates,
            answer: answer.clone(),
            transcript: transcript.clone(),
            done,
        };
        let outcome = if worker.requests.send(WorkerRequest::Prompt(job)).await.is_ok() {
            reply.await.ok()
        } else {
            None
        };
        let result = outcome.unwrap_or_else(
            || json!({"type":"Error","code":"agent_exited","message":"The ACP agent exited."}),
        );
        let answer = std::mem::take(&mut *answer.lock().unwrap());
        let events = std::mem::take(&mut *transcript.lock().unwrap());
        for event in &events { if event["type"] == "SteeredUser" { let mut row = message("user", event["text"].as_str().unwrap_or_default(), "sent"); row["content"] = event["content"].clone(); record.messages.push(row); } }
        if !answer.is_empty() || !events.is_empty() {
            let mut row = message("assistant", &answer, "sent");
            row["events"] = json!(events);
            record.messages.push(row);
        }
        if result["type"] == "Error" {
            record.messages.push(message(
                "assistant",
                result["message"].as_str().unwrap_or("Agent error"),
                "error",
            ));
        }
        self.save(&state.store, record)?;
        let settings = worker.output.lock().await.settings.clone();
        let mut result = result;
        result["settings"] = settings;
        Ok(result)
    }

    async fn worker(&self, state: &ApiState, record: &Record, cancel: &CancellationToken) -> Result<(Worker, Option<Vec<Value>>), ApiError> {
        let profile = profile_for(state, &record.profile)?;
        if let Some(worker) = self.workers.lock().await.get(&record.id).cloned().filter(|worker| !worker.requests.is_closed()) {
            return Ok((worker, None));
        }
        let project = crate::control::local_workspace(state, record.project.as_deref())?;
        let (worker, _, replay) = start_worker(state, profile, record.cwd.clone(), Some(record.agent_session.clone()), cancel, project, !record.delegated).await?;
        self.workers.lock().await.insert(record.id.clone(), worker.clone());
        Ok((worker, replay))
    }

    pub async fn settings(&self, state: &ApiState, id: &str, change: Option<Value>, cancel: &CancellationToken) -> Result<Value, ApiError> {
        {
            let mut active = state.active_turns.lock().await;
            if active.contains_key(id) {
                return Err(ApiError::Conflict { code: "turn_in_progress".into(), message: "Wait for the ACP turn to finish before changing session settings.".into() });
            }
            active.insert(id.to_owned(), cancel.clone());
        }
        let result = async {
            let record = self.record(&state.store, id)?;
            let project = crate::control::local_workspace(state, record.project.as_deref())?;
            if project.as_ref().is_some_and(|project| PathBuf::from(&project.workspace) != record.cwd) {
                return Err(ApiError::BadRequest("the ACP session workspace has moved".into()));
            }
            let (worker, _) = self.worker(state, &record, cancel).await?;
            if let Some(change) = change {
                let (done, result) = oneshot::channel();
                worker.requests.send(WorkerRequest::Configure { change, done }).await.map_err(|_| ApiError::internal("The ACP agent exited."))?;
                tokio::select! {
                    result = result => result.map_err(ApiError::internal)?.map_err(ApiError::internal)?,
                    _ = cancel.cancelled() => {
                        // A configuration response may be ambiguous after cancellation. Drop this worker
                        // so the next read obtains the agent's actual state through session restore.
                        worker.lifetime.cancel();
                        self.workers.lock().await.remove(id);
                        return Err(ApiError::internal("ACP settings request cancelled"));
                    }
                    _ = tokio::time::sleep(Duration::from_secs(30)) => {
                        worker.lifetime.cancel();
                        self.workers.lock().await.remove(id);
                        return Err(ApiError::internal("ACP settings request timed out"));
                    }
                }
            }
            let settings = worker.output.lock().await.settings.clone();
            Ok(json!({"type":"SessionSettings","settings":settings}))
        }.await;
        state.active_turns.lock().await.remove(id);
        result
    }
}

fn message(role: &str, text: &str, status: &str) -> Value {
    json!({"id":Uuid::new_v4(),"role":role,"text":text,"status":status,"timestamp":now_ms()})
}

fn profile_for(state: &ApiState, id: &str) -> Result<Profile, ApiError> {
    crate::control::settings(&state.store)?
        .agents
        .into_iter()
        .find(|profile| profile.id == id && profile.enabled)
        .ok_or_else(|| ApiError::not_found("ACP agent profile"))
}

/// Configure only the spawned process. Existing user configuration files are
/// never rewritten, and upstream credentials remain inside the relay.
async fn configure_provider_takeover(state: &ApiState, profile: &mut Profile, opencode_major: Option<u64>) -> Result<(), ApiError> {
    if !profile.provider_takeover { return Ok(()); }
    let format = match profile.id.as_str() {
        "codex" => "responses",
        "claude-code" => "messages",
        "opencode" => "chat",
        _ => return Err(ApiError::BadRequest("Provider takeover is only available for built-in ACP agents".into())),
    };
    let providers: HashMap<_, _> = state.store.list_providers().map_err(ApiError::store)?
        .into_iter().map(|provider| (provider.id.clone(), provider)).collect();
    let models: Vec<_> = state.store.list_models().map_err(ApiError::store)?.into_iter()
        .filter(|model| model.is_enabled && providers.get(&model.provider_id).is_some_and(|provider| {
            let actual = if provider.api_format == "auto" { "chat" } else { &provider.api_format };
            matches!(actual, "chat" | "responses" | "messages" | "interactions")
        })).collect();
    let preferred = state.store.get_default_agent().map_err(ApiError::store)?
        .and_then(|agent| agent.default_model_id);
    let selected = preferred.as_deref().and_then(|id| models.iter().find(|model| model.id == id))
        .or_else(|| models.iter().find(|model| providers.get(&model.provider_id).is_some_and(|provider| provider.api_format == format)))
        .or_else(|| models.first()).ok_or_else(|| ApiError::Conflict {
            code: "takeover_no_compatible_model".into(),
            message: format!("Provider takeover for {} requires an enabled model in a supported API format.", profile.name),
        })?;
    let selected_id = format!("messenger/{}", selected.id);
    // Validate existing inline settings before allocating the relay listener.
    let mut inline = match profile.id.as_str() {
        "codex" => Some(inline_config(&profile.env, "CODEX_CONFIG")?),
        "opencode" => Some(inline_config(&profile.env, "OPENCODE_CONFIG_CONTENT")?),
        _ => None,
    };
    if let Some(config) = &inline {
        let key = if profile.id == "codex" { "model_providers" } else if opencode_major.unwrap_or(1) >= 2 { "providers" } else { "provider" };
        if config.get(key).is_some_and(|value| !value.is_object()) {
            return Err(ApiError::BadRequest(format!("{key} must be a JSON object")));
        }
    }
    let (base_url, token) = crate::agent_relay::ensure(state).await?;
    match profile.id.as_str() {
        "claude-code" => {
            profile.env.insert("ANTHROPIC_BASE_URL".into(), base_url.trim_end_matches("/v1").into());
            profile.env.insert("ANTHROPIC_AUTH_TOKEN".into(), token);
            profile.env.insert("ANTHROPIC_API_KEY".into(), String::new());
            for key in ["ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL"] {
                profile.env.insert(key.into(), selected_id.clone());
            }
        }
        "codex" => {
            let mut config = inline.take().expect("Codex inline configuration was checked");
            config["model_provider"] = json!("messenger");
            config["model"] = json!(selected_id);
            if config.get("model_providers").is_none() { config["model_providers"] = json!({}); }
            let providers = config["model_providers"].as_object_mut().ok_or_else(|| ApiError::BadRequest("CODEX_CONFIG model_providers must be an object".into()))?;
            providers.insert("messenger".into(), json!({"name":"Messenger","base_url":base_url,"wire_api":"responses","env_key":"MESSENGER_RELAY_TOKEN"}));
            profile.env.insert("CODEX_CONFIG".into(), config.to_string());
            profile.env.insert("MODEL_PROVIDER".into(), "messenger".into());
            profile.env.insert("MESSENGER_RELAY_TOKEN".into(), token);
        }
        "opencode" => {
            let mut config = inline.take().expect("OpenCode inline configuration was checked");
            let v2 = opencode_major.ok_or_else(|| ApiError::BadRequest("OpenCode version was not detected".into()))? >= 2;
            let key = if v2 { "providers" } else { "provider" };
            if config.get(key).is_none() { config[key] = json!({}); }
            let provider_formats: HashMap<String, String> = providers.iter().map(|(id, provider)| (id.clone(), provider.api_format.clone())).collect();
            let providers = config[key].as_object_mut().expect("provider configuration was checked");
            let catalog: serde_json::Map<String, Value> = models.iter().map(|model| (
                model.id.clone(), {
                    let format = provider_formats.get(&model.provider_id).map(String::as_str).unwrap_or("chat");
                    let (native, legacy) = match format {
                        "responses" => ("@opencode/ai/providers/openai-compatible/responses", "@ai-sdk/openai"),
                        "messages" => ("@opencode/ai/providers/anthropic-compatible", "@ai-sdk/anthropic"),
                        "interactions" => ("@opencode/ai/providers/google/interactions", "@ai-sdk/openai-compatible"),
                        _ => ("@opencode/ai/providers/openai-compatible", "@ai-sdk/openai-compatible"),
                    };
                    if v2 { json!({"name":model.display_name, "modelID":format!("messenger/{}", model.id), "package":native}) }
                    else { json!({"name":model.display_name, "id":format!("messenger/{}", model.id), "npm":legacy}) }
                }
            )).collect();
            let provider = if v2 {
                json!({"name":"Messenger","package":"@opencode/ai/providers/openai-compatible", "settings":{"baseURL":base_url,"apiKey":token},"models":catalog})
            } else {
                json!({"name":"Messenger","npm":"@ai-sdk/openai-compatible", "options":{"baseURL":base_url,"apiKey":token},"models":catalog})
            };
            providers.insert("messenger".into(), provider);
            let mut model_reference = selected_id;
            if !v2 {
                // V1 chooses its SDK at provider level; per-model npm overrides
                // are ignored, so each native protocol needs a provider entry.
                for (format, package) in [("responses", "@ai-sdk/openai"), ("messages", "@ai-sdk/anthropic")] {
                    let mut catalog = serde_json::Map::new();
                    for model in &models {
                        if provider_formats.get(&model.provider_id).map(String::as_str) != Some(format) { continue; }
                        catalog.insert(model.id.clone(), json!({"name":model.display_name,"id":format!("messenger/{}",model.id)}));
                        providers["messenger"]["models"].as_object_mut().expect("generated catalog").remove(&model.id);
                    }
                    if !catalog.is_empty() {
                        let id = format!("messenger-{format}");
                        providers.insert(id.clone(), json!({"name":format!("Messenger {format}"),"npm":package,"options":{"baseURL":base_url,"apiKey":token},"models":catalog}));
                        if provider_formats.get(&selected.provider_id).map(String::as_str) == Some(format) {
                            model_reference = format!("{id}/{}",selected.id);
                        }
                    }
                }
            }
            config["model"] = json!(model_reference);
            profile.env.insert("OPENCODE_CONFIG_CONTENT".into(), config.to_string());
        }
        _ => unreachable!(),
    }
    Ok(())
}

fn parse_opencode_major(output: &str) -> Result<u64, ApiError> {
    let version = output.split_whitespace().find(|word| {
        let mut parts = word.trim_start_matches('v').split('.');
        parts.next().and_then(|part| part.parse::<u64>().ok()).is_some()
            && parts.next().and_then(|part| part.parse::<u64>().ok()).is_some()
            && parts.next().is_some_and(|part| part.split(['-', '+']).next().and_then(|number| number.parse::<u64>().ok()).is_some())
    }).ok_or_else(|| ApiError::BadRequest("Could not determine OpenCode version from --version".into()))?;
    let major = version.trim_start_matches('v').split('.').next().and_then(|part| part.parse::<u64>().ok())
        .filter(|major| matches!(major, 1 | 2)).ok_or_else(|| ApiError::BadRequest("Only OpenCode versions 1 and 2 are supported".into()))?;
    Ok(major)
}

async fn detect_opencode_major(state: &ApiState, profile: &Profile, cwd: &std::path::Path, cancel: &CancellationToken) -> Result<u64, ApiError> {
    use tokio::io::{AsyncBufReadExt, AsyncReadExt, BufReader};
    let mut probe = profile.clone();
    probe.args = vec!["--version".into()];
    let process = spawn_process(state, &probe, cwd).await?;
    let mut stdout = BufReader::new(process.stdout.take(4096));
    let mut output = String::new();
    tokio::select! {
        result = stdout.read_line(&mut output) => { result.map_err(ApiError::internal)?; },
        _ = cancel.cancelled() => return Err(ApiError::BadRequest("OpenCode version detection cancelled".into())),
        _ = state.shutdown.cancelled() => return Err(ApiError::BadRequest("Core stopped during OpenCode version detection".into())),
        _ = tokio::time::sleep(Duration::from_secs(10)) => return Err(ApiError::BadRequest("OpenCode --version timed out".into())),
    }
    parse_opencode_major(&output)
}

fn inline_config(env: &HashMap<String, String>, key: &str) -> Result<Value, ApiError> {
    let raw = env.get(key).cloned().or_else(|| std::env::var(key).ok());
    let Some(raw) = raw.filter(|value| !value.trim().is_empty()) else { return Ok(json!({})); };
    let config: Value = serde_json::from_str(&raw).map_err(|error| ApiError::BadRequest(format!("Invalid {key}: {error}")))?;
    if !config.is_object() { return Err(ApiError::BadRequest(format!("{key} must be a JSON object"))); }
    Ok(config)
}

fn start_worker<'a>(
    state: &'a ApiState,
    profile: Profile,
    cwd: PathBuf,
    existing: Option<SessionId>,
    cancel: &'a CancellationToken,
    project: Option<StoredProject>,
    allow_delegation: bool,
) -> Pin<Box<dyn Future<Output = Result<(Worker, SessionId, Option<Vec<Value>>), ApiError>> + Send + 'a>> {
    // Delegated ACP calls can start another worker through the MCP gateway.
    // Erase this future at that boundary to break the recursive future type.
    Box::pin(start_worker_impl(state, profile, cwd, existing, cancel, project, allow_delegation))
}

async fn start_worker_impl(
    state: &ApiState,
    mut profile: Profile,
    cwd: PathBuf,
    existing: Option<SessionId>,
    cancel: &CancellationToken,
    project: Option<StoredProject>,
    allow_delegation: bool,
) -> Result<(Worker, SessionId, Option<Vec<Value>>), ApiError> {
    // Native clients load MCP settings asynchronously after the loopback server
    // starts. Never mistake their not-yet-published catalog for an empty one.
    wait_for_platform_catalog(state, cancel, false).await?;
    let opencode_major = if profile.provider_takeover && profile.id == "opencode" {
        Some(detect_opencode_major(state, &profile, &cwd, cancel).await?)
    } else { None };
    configure_provider_takeover(state, &mut profile, opencode_major).await?;
    let mcp_servers = state.platform_catalog.lock().await.mcp_servers.clone();
    let (input, mut prompts) = mpsc::channel::<WorkerRequest>(1);
    let (ready, initialized) = oneshot::channel();
    let (failure, mut failed) = oneshot::channel();
    let lifetime = state.shutdown.child_token();
    let mut start_guard = Some(lifetime.clone().drop_guard());
    let transport = spawn_process(state, &profile, &cwd).await?;
    let output = Arc::new(Mutex::new(Output::default()));
    let worker = Worker { requests: input, output: output.clone(), lifetime: lifetime.clone() };
    let notify_output = output.clone();
    let permission_output = output.clone();
    let gateway_output = output.clone();
    let gateway_state = state.clone();
    let gateway_lifetime = lifetime.clone();
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
                        .send_request(InitializeRequest::new(ProtocolVersion::V1).client_capabilities(
                            ClientCapabilities::new().session(ClientSessionCapabilities::new().config_options(
                                SessionConfigOptionsCapabilities::new().boolean(BooleanConfigOptionCapabilities::new())
                            ))
                        ))
                        .block_task()
                        .await?;
                    if init.protocol_version != ProtocolVersion::V1 {
                        return Err(agent_client_protocol::Error::new(
                            -32602,
                            "Unsupported ACP protocol version",
                        ));
                    }
                    let name_conflict = mcp_servers.iter().any(|server| server.is_enabled && server.name == "Messenger Agents");
                    let mut mcp_servers = acp_mcp_servers(&mcp_servers, init.agent_capabilities.mcp_capabilities.sse)?;
                    if allow_delegation && crate::delegation::enabled(&gateway_state.store) {
                        if !init.agent_capabilities.mcp_capabilities.sse && !init.agent_capabilities.mcp_capabilities.http {
                            return Err(agent_client_protocol::Error::new(-32000, "Agent delegation requires ACP MCP SSE or HTTP support"));
                        }
                        if name_conflict {
                            return Err(agent_client_protocol::Error::new(-32000, "Rename the configured MCP server named Messenger Agents to enable agent delegation"));
                        }
                        let url = crate::acp_mcp::start(gateway_state, project, gateway_output, gateway_lifetime)
                            .await.map_err(|error| agent_client_protocol::Error::new(-32000, error.to_string()))?;
                        if init.agent_capabilities.mcp_capabilities.sse {
                            mcp_servers.push(McpServer::Sse(McpServerSse::new("Messenger Agents", url)));
                        } else {
                            let rpc_url = format!("{}/rpc", url.rsplit_once('/').unwrap().0);
                            mcp_servers.push(McpServer::Http(McpServerHttp::new("Messenger Agents", rpc_url)));
                        }
                    }
                    output.lock().await.settings = json!({"configOptions":[],"steering": init.meta.as_ref().and_then(|meta| meta.get("steering")).cloned().unwrap_or(Value::Null)});
                    let session = match existing {
                        Some(id) => {
                            if init
                                .agent_capabilities
                                .session_capabilities
                                .resume
                                .is_some()
                            {
                                let response = connection.send_request(ResumeSessionRequest::new(id.clone(), cwd).mcp_servers(mcp_servers.clone())).block_task().await?;
                                merge_settings(&mut output.lock().await.settings, serde_json::to_value(response).unwrap_or_default());
                            } else if init.agent_capabilities.load_session {
                                output.lock().await.replay = Some(Vec::new());
                                let response = connection.send_request(LoadSessionRequest::new(id.clone(), cwd).mcp_servers(mcp_servers.clone())).block_task().await?;
                                merge_settings(&mut output.lock().await.settings, serde_json::to_value(response).unwrap_or_default());
                            } else {
                                return Err(agent_client_protocol::Error::new(
                                    -32000,
                                    "This ACP agent cannot restore sessions. Create a new session.",
                                ));
                            }
                            id
                        }
                        None => {
                            let response = connection.send_request(NewSessionRequest::new(cwd).mcp_servers(mcp_servers)).block_task().await?;
                            merge_settings(&mut output.lock().await.settings, serde_json::to_value(&response).unwrap_or_default());
                            response.session_id
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
                        match prompt {
                            WorkerRequest::Prompt(prompt) => {
                                if !run_prompt(&connection, &session, &output, prompt, &mut prompts).await { break; }
                            }
                            WorkerRequest::Steer { done, .. } => { let _ = done.send(Ok(json!({"outcome":"promptRequired"}))); }
                            WorkerRequest::Configure { change, done } => {
                                let result = configure(&connection, &session, &output, change).await;
                                let _ = done.send(result);
                            }
                        }
                    }
                    Ok(())
                },
            );
        let error = tokio::select! {
            result = connection => result.err().map(|err| err.to_string()),
            _ = lifetime.cancelled() => Some("The ACP process was cancelled.".into()),
        };
        lifetime.cancel();
        let _ = failure.send(error.unwrap_or_else(|| "The ACP agent exited.".into()));
    });
    tokio::select! {
        biased;
        result = initialized => {
            match result {
                Ok((id, history)) => {
                    if let Some(guard) = start_guard.take() { guard.disarm(); }
                    Ok((worker, id, history))
                }
                Err(_) => Err(ApiError::internal((&mut failed).await.unwrap_or_else(|_| "The ACP agent exited.".into()))),
            }
        }
        _ = cancel.cancelled() => Err(ApiError::internal("ACP session setup cancelled")),
        _ = tokio::time::sleep(Duration::from_secs(30)) => Err(ApiError::internal("ACP session setup timed out")),
    }
}

fn merge_settings(settings: &mut Value, update: Value) {
    if !settings.is_object() { *settings = json!({"configOptions":[]}); }
    for key in ["configOptions", "modes", "availableCommands", "usage"] {
        if let Some(value) = update.get(key) { settings[key] = value.clone(); }
    }
    if let Some(mode) = update.get("currentModeId") {
        if settings["modes"].is_object() { settings["modes"]["currentModeId"] = mode.clone(); }
    }
}

fn select_contains(options: &Value, value: &Value) -> bool {
    options.as_array().is_some_and(|options| options.iter().any(|option| {
        option.get("value") == Some(value) || select_contains(&option["options"], value)
    }))
}

async fn configure(connection: &ConnectionTo<Agent>, session: &SessionId, output: &Mutex<Output>, change: Value) -> Result<(), String> {
    let settings = output.lock().await.settings.clone();
    if let Some(mode) = change["modeId"].as_str() {
        let valid = settings["modes"]["availableModes"].as_array().is_some_and(|modes| modes.iter().any(|item| item["id"] == mode));
        if !valid { return Err("This ACP session does not offer that mode.".into()); }
        connection.send_request(SetSessionModeRequest::new(session.clone(), mode.to_owned())).block_task().await.map_err(|error| error.to_string())?;
        output.lock().await.settings["modes"]["currentModeId"] = json!(mode);
    } else {
        let id = change["configId"].as_str().ok_or("Missing ACP configId")?;
        let option = settings["configOptions"].as_array().and_then(|options| options.iter().find(|option| option["id"] == id)).ok_or("This ACP session does not offer that setting.")?;
        let value = &change["value"];
        let value = match option["type"].as_str() {
            Some("select") if select_contains(&option["options"], value) => SessionConfigOptionValue::value_id(value.as_str().ok_or("Invalid select value")?.to_owned()),
            Some("boolean") if value.is_boolean() => SessionConfigOptionValue::boolean(value.as_bool().unwrap()),
            _ => return Err("Invalid value for this ACP session setting.".into()),
        };
        let response = connection.send_request(SetSessionConfigOptionRequest::new(session.clone(), id.to_owned(), value)).block_task().await.map_err(|error| error.to_string())?;
        merge_settings(&mut output.lock().await.settings, serde_json::to_value(response).map_err(|error| error.to_string())?);
    }
    Ok(())
}

async fn run_prompt(
    connection: &ConnectionTo<Agent>,
    session: &SessionId,
    output: &Mutex<Output>,
    prompt: Prompt,
    requests: &mut mpsc::Receiver<WorkerRequest>,
) -> bool {
    let Prompt {
        content,
        cancel,
        permissions,
        updates,
        answer,
        transcript,
        done,
    } = prompt;
    let tool_cancel = cancel.child_token();
    let _tool_guard = tool_cancel.clone().drop_guard();
    output.lock().await.turn = Some(LiveTurn {
        cancel: cancel.clone(),
        tool_cancel,
        permissions: Arc::new(Mutex::new(permissions)),
        updates,
        answer,
        transcript: transcript.clone(),
    });
    let request = connection
        .send_request(PromptRequest::new(
            session.clone(),
            content,
        ))
        .block_task();
    tokio::pin!(request);
    let (result, keep) = loop { tokio::select! {
        Some(WorkerRequest::Steer { content, done }) = requests.recv() => {
            let steered_text = content.iter().filter_map(|block| match block { ContentBlock::Text(text) => Some(text.text.as_str()), _ => None }).collect::<Vec<_>>().join("\n");
            let params = json!({"sessionId":session,"prompt":content,"_meta":{"steering":{"idleBehavior":"promptRequired"}}});
            let result = tokio::select! {
                result = tokio::time::timeout(Duration::from_secs(10), connection.send_request(agent_client_protocol::UntypedMessage::new("_session/steering", params).unwrap()).block_task()) => result.map_err(|e| e.to_string()).and_then(|r| r.map_err(|e| e.to_string())),
                _ = cancel.cancelled() => Err("Turn cancelled during steering".into()),
            };
            if result.as_ref().is_ok_and(|value| value["outcome"] == "injected") {
                transcript.lock().unwrap().push(json!({"type":"SteeredUser","text":steered_text,"content":content}));
            }
            let _ = done.send(result);
        }
        result = &mut request => {
            break match result {
                Ok(result) if result.stop_reason == StopReason::Cancelled =>
                    (json!({"type":"Cancelled"}), true),
                Ok(result) => (json!({"type":"Finished","stopReason":result.stop_reason}), true),
                Err(error) => (json!({"type":"Error","code":"acp_error","message":error.to_string()}), false),
            }
        }
        _ = cancel.cancelled() => {
            let _ = connection.send_notification(CancelNotification::new(session.clone()));
            let keep = tokio::time::timeout(Duration::from_secs(2), request).await.is_ok_and(|r| r.is_ok());
            break (json!({"type":"Cancelled"}), keep)
        }
    } };
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
    if kind == "usage_update" { output.settings["usage"] = update.clone(); }
    if kind == "available_commands_update" { output.settings["availableCommands"] = update["availableCommands"].clone(); }
    if matches!(kind, "config_option_update" | "current_mode_update" | "usage_update" | "available_commands_update") {
        merge_settings(&mut output.settings, update.clone());
        if let Some(turn) = &output.turn {
            let data = json!({"type":"SessionSettings","settings":output.settings});
            let updates = turn.updates.clone();
            let cancel = turn.cancel.clone();
            drop(output);
            tokio::select! { _ = updates.send(data) => {}, _ = cancel.cancelled() => {} }
        }
        return;
    }
    if let Some(replay) = &mut output.replay {
        let role = match kind {
            "user_message_chunk" => "user",
            "agent_message_chunk" | "agent_thought_chunk" | "tool_call" | "tool_call_update" => "assistant",
            _ => return,
        };
        if matches!(kind, "agent_thought_chunk" | "tool_call" | "tool_call_update") {
            if !replay.last().is_some_and(|row| row["role"] == "assistant") {
                replay.push(message("assistant", "", "sent"));
            }
            let row = replay.last_mut().unwrap();
            if !row["events"].is_array() { row["events"] = json!([]); }
            row["events"].as_array_mut().unwrap().push(if kind == "agent_thought_chunk" {
                json!({"type":"ThoughtDelta","text":text})
            } else { json!({"type":"ToolUpdate","update":update}) });
            return;
        }
        let message_id = update["messageId"].as_str();
        if let Some(last) = replay.last_mut().filter(|last| {
            last["role"] == role && (message_id.is_none() || last["id"].as_str() == message_id)
        }) {
            let previous = last["text"].as_str().unwrap_or_default();
            last["text"] = json!(format!("{previous}{text}"));
            if role == "assistant" {
                if !last["events"].is_array() { last["events"] = json!([]); }
                last["events"].as_array_mut().unwrap().push(json!({"type":"TextDelta","text":text}));
            }
        } else {
            let mut row = message(role, text, "sent");
            if role == "assistant" { row["events"] = json!([{ "type":"TextDelta", "text":text }]); }
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
            "tool_call" | "tool_call_update" => json!({"type":"ToolUpdate","update":update}),
            _ => return,
        };
        turn.transcript.lock().unwrap().push(data.clone());
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

    #[tokio::test]
    async fn builtin_takeover_uses_process_scoped_relay_configuration() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "local");
        for (id, format) in [("chat-model", "chat"), ("response-model", "responses"), ("message-model", "messages"), ("interaction-model", "interactions")] {
            state.store.upsert_provider(&StoredProvider {
                id: id.into(), name: id.into(), base_url: "https://example.invalid/v1".into(),
                api_key: format!("upstream-secret-{id}"), api_format: format.into(), created_at: 1, updated_at: 1,
            }).unwrap();
            let model: StoredModel = serde_json::from_value(json!({
                "id":id,"provider_id":id,"model_id":"upstream","display_name":id,"is_enabled":true,"created_at":1
            })).unwrap();
            state.store.upsert_model(&model).unwrap();
        }
        for mut profile in default_profiles() {
            assert!(!profile.provider_takeover, "takeover must be opt-in");
            profile.provider_takeover = true;
            match profile.id.as_str() {
                "codex" => { profile.env.insert("CODEX_CONFIG".into(), r#"{"approval_policy":"never"}"#.into()); }
                "opencode" => { profile.env.insert("OPENCODE_CONFIG_CONTENT".into(), r#"{"theme":"test"}"#.into()); }
                _ => {}
            }
            configure_provider_takeover(&state, &mut profile, Some(1)).await.unwrap();
            let env = serde_json::to_string(&profile.env).unwrap();
            assert!(!env.contains("upstream-secret-"));
            match profile.id.as_str() {
                "codex" => {
                    let config: Value = serde_json::from_str(&profile.env["CODEX_CONFIG"]).unwrap();
                    assert_eq!(config["approval_policy"], "never");
                    assert_eq!(config["model"], "messenger/response-model");
                    assert_eq!(config["model_providers"]["messenger"]["env_key"], "MESSENGER_RELAY_TOKEN");
                    assert!(profile.env["MESSENGER_RELAY_TOKEN"].starts_with("messenger-relay-"));
                }
                "claude-code" => {
                    assert_eq!(profile.env["ANTHROPIC_MODEL"], "messenger/message-model");
                    assert!(profile.env["ANTHROPIC_AUTH_TOKEN"].starts_with("messenger-relay-"));
                }
                "opencode" => {
                    let config: Value = serde_json::from_str(&profile.env["OPENCODE_CONFIG_CONTENT"]).unwrap();
                    assert_eq!(config["theme"], "test");
                    assert_eq!(config["model"], "messenger/chat-model");
                    assert_eq!(config["provider"]["messenger"]["models"]["chat-model"]["id"], "messenger/chat-model");
                    assert_eq!(config["provider"]["messenger-responses"]["npm"], "@ai-sdk/openai");
                    assert_eq!(config["provider"]["messenger-messages"]["npm"], "@ai-sdk/anthropic");
                    configure_provider_takeover(&state, &mut profile, Some(2)).await.unwrap();
                    let config: Value = serde_json::from_str(&profile.env["OPENCODE_CONFIG_CONTENT"]).unwrap();
                    for (id, package) in [
                        ("chat-model", "@opencode/ai/providers/openai-compatible"),
                        ("response-model", "@opencode/ai/providers/openai-compatible/responses"),
                        ("message-model", "@opencode/ai/providers/anthropic-compatible"),
                        ("interaction-model", "@opencode/ai/providers/google/interactions"),
                    ] {
                        assert_eq!(config["providers"]["messenger"]["models"][id]["package"], package);
                        assert_eq!(config["providers"]["messenger"]["models"][id]["modelID"], format!("messenger/{id}"));
                    }
                }
                _ => unreachable!(),
            }
        }
        state.shutdown.cancel();
    }

    #[tokio::test]
    async fn takeover_rejects_missing_compatible_models_before_starting_relay() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "local");
        let mut profile = default_profiles().remove(0);
        profile.provider_takeover = true;
        let result = configure_provider_takeover(&state, &mut profile, None).await;
        assert!(matches!(result, Err(ApiError::Conflict { code, .. }) if code == "takeover_no_compatible_model"));
        assert!(state.agent_relay.lock().await.is_none());
        state.shutdown.cancel();
    }

    #[tokio::test]
    async fn codex_takeover_accepts_chat_models_for_conversion() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "local");
        state.store.upsert_provider(&StoredProvider {
            id:"p".into(), name:"provider".into(), base_url:"https://example.invalid/v1".into(),
            api_key:"secret".into(), api_format:"chat".into(), created_at:1, updated_at:1,
        }).unwrap();
        let model: StoredModel = serde_json::from_value(json!({"id":"chat-model","provider_id":"p",
            "model_id":"upstream","display_name":"Model","is_enabled":true,"created_at":1})).unwrap();
        state.store.upsert_model(&model).unwrap();
        let mut profile = default_profiles().remove(0);
        profile.provider_takeover = true;
        configure_provider_takeover(&state, &mut profile, None).await.unwrap();
        let config: Value = serde_json::from_str(&profile.env["CODEX_CONFIG"]).unwrap();
        assert_eq!(config["model"], "messenger/chat-model");
        state.shutdown.cancel();
    }

    #[tokio::test]
    async fn opencode_detects_version_and_injects_v2_provider_configuration() {
        use std::os::unix::fs::PermissionsExt;
        let directory = std::env::temp_dir().join(format!("messenger-opencode-{}", Uuid::new_v4()));
        std::fs::create_dir(&directory).unwrap();
        let command = directory.join("opencode");
        std::fs::write(&command, "#!/bin/sh\n[ \"$1\" = --version ] || exit 1\nprintf 'opencode 2.4.1\\n'\n").unwrap();
        std::fs::set_permissions(&command, std::fs::Permissions::from_mode(0o700)).unwrap();
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "local");
        state.store.upsert_provider(&StoredProvider {
            id: "p".into(), name: "Provider".into(), base_url: "https://example.invalid/v1".into(),
            api_key: "upstream-secret".into(), api_format: "chat".into(), created_at: 1, updated_at: 1,
        }).unwrap();
        let model: StoredModel = serde_json::from_value(json!({"id":"m", "provider_id":"p", "model_id":"upstream",
            "display_name":"Model", "is_enabled":true, "created_at":1})).unwrap();
        state.store.upsert_model(&model).unwrap();
        let mut profile = default_profiles().into_iter().find(|profile| profile.id == "opencode").unwrap();
        profile.command = command.to_string_lossy().into_owned();
        profile.provider_takeover = true;
        profile.env.insert("OPENCODE_CONFIG_CONTENT".into(), r#"{"providers":{"existing":{}}}"#.into());
        let major = detect_opencode_major(&state, &profile, &directory, &CancellationToken::new()).await.unwrap();
        assert_eq!(major, 2);
        configure_provider_takeover(&state, &mut profile, Some(major)).await.unwrap();
        let config: Value = serde_json::from_str(&profile.env["OPENCODE_CONFIG_CONTENT"]).unwrap();
        assert!(config["providers"]["existing"].is_object());
        assert_eq!(config["providers"]["messenger"]["package"], "@opencode/ai/providers/openai-compatible");
        assert!(config["providers"]["messenger"]["settings"]["apiKey"].as_str().unwrap().starts_with("messenger-relay-"));
        assert_eq!(config["model"], "messenger/m");
        assert_eq!(config["providers"]["messenger"]["models"]["m"]["modelID"], "messenger/m");
        assert!(!config.to_string().contains("upstream-secret"));
        state.shutdown.cancel();
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn opencode_versions_are_checked_explicitly() {
        assert_eq!(parse_opencode_major("1.2.3\n").unwrap(), 1);
        assert_eq!(parse_opencode_major("opencode v2.3.4-beta+1\n").unwrap(), 2);
        assert!(parse_opencode_major("not a version").is_err());
        assert!(parse_opencode_major("3.0.0").is_err());
    }

    #[tokio::test]
    async fn takeover_starts_relay_before_launching_the_agent() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "local");
        state.store.upsert_provider(&StoredProvider {
            id:"p".into(), name:"provider".into(), base_url:"https://example.invalid/v1".into(),
            api_key:"upstream-secret".into(), api_format:"messages".into(), created_at:1, updated_at:1,
        }).unwrap();
        let model: StoredModel = serde_json::from_value(json!({"id":"message-model","provider_id":"p",
            "model_id":"upstream","display_name":"Model","is_enabled":true,"created_at":1})).unwrap();
        state.store.upsert_model(&model).unwrap();
        let fixture = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/acp_agent.py");
        let mut profile = default_profiles().into_iter().find(|profile| profile.id == "claude-code").unwrap();
        profile.command = "python3".into();
        profile.args = vec![fixture.to_string_lossy().into_owned(), "provider-env".into()];
        profile.provider_takeover = true;
        let (worker, _, _) = start_worker(&state, profile, std::env::temp_dir(), None,
            &CancellationToken::new(), None, true).await.unwrap();
        worker.lifetime.cancel();
        state.shutdown.cancel();
    }

    #[tokio::test]
    async fn native_worker_waits_for_the_published_mcp_configuration() {
        let state = ApiState::with_platform_catalog(
            Arc::new(Store::open_memory().unwrap()), "local", CancellationToken::new(), true,
        );
        let fixture = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/acp_agent.py");
        let profile = Profile {
            id: "fixture".into(), name: "Fixture".into(), description: String::new(), enabled: true, provider_takeover: false,
            command: "python3".into(), args: vec![fixture.to_string_lossy().into_owned(), "mcp-load".into()],
            env: HashMap::new(), cwd: None,
        };
        let cancel = CancellationToken::new();
        let start = start_worker(&state, profile, std::env::temp_dir(), None, &cancel, None, true);
        tokio::pin!(start);
        assert!(tokio::time::timeout(Duration::from_millis(50), &mut start).await.is_err(),
            "the ACP worker must wait for the first native catalog");
        let tools_only: crate::control::PlatformCatalogUpdate = serde_json::from_value(json!({
            "workspaceSupported":false,"tools":[]
        })).unwrap();
        crate::control::platform_tools(State(state.clone()), Json(tools_only)).await;
        assert!(tokio::time::timeout(Duration::from_millis(50), &mut start).await.is_err(),
            "a tools-only publication must not be mistaken for an MCP configuration");
        let catalog: crate::control::PlatformCatalogUpdate = serde_json::from_value(json!({
            "workspaceSupported":false,"tools":[],"mcpServers":[{
                "id":"test","name":"test-mcp","transportType":"STDIO","command":"test-server",
                "env":{"TOKEN":"secret"}
            }]
        })).unwrap();
        crate::control::platform_tools(State(state.clone()), Json(catalog)).await;
        let (worker, _, _) = tokio::time::timeout(Duration::from_secs(3), start).await.unwrap().unwrap();
        worker.lifetime.cancel();
        state.shutdown.cancel();
    }

    #[tokio::test]
    async fn waiting_for_native_catalog_honors_cancellation_and_shutdown() {
        let state = ApiState::with_platform_catalog(
            Arc::new(Store::open_memory().unwrap()), "local", CancellationToken::new(), true,
        );
        let profile = default_profiles().remove(0);
        let cancel = CancellationToken::new();
        cancel.cancel();
        let result = start_worker(&state, profile.clone(), std::env::temp_dir(), None, &cancel, None, true).await;
        assert!(matches!(result, Err(ApiError::Internal(message)) if message.contains("cancelled")));
        state.shutdown.cancel();
        let result = start_worker(&state, profile, std::env::temp_dir(), None, &CancellationToken::new(), None, true).await;
        assert!(matches!(result, Err(ApiError::Internal(message)) if message.contains("shutting down")));
    }

    #[tokio::test(start_paused = true)]
    async fn missing_native_catalog_returns_a_visible_timeout() {
        let state = ApiState::with_platform_catalog(
            Arc::new(Store::open_memory().unwrap()), "local", CancellationToken::new(), true,
        );
        let result = start_worker(&state, default_profiles().remove(0), std::env::temp_dir(), None,
            &CancellationToken::new(), None, true).await;
        assert!(matches!(result, Err(ApiError::Conflict { code, .. }) if code == "platform_catalog_unavailable"));
        state.shutdown.cancel();
    }

    #[tokio::test]
    async fn configured_mcp_reaches_new_loaded_and_resumed_acp_sessions() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "local");
        state.platform_catalog.lock().await.mcp_servers = vec![messenger_mcp::McpServerConfig {
            name: "test-mcp".into(), command: "test-server".into(),
            env: [("TOKEN".into(), "secret".into())].into_iter().collect(),
            ..Default::default()
        }];
        let profile = Profile {
            id: "fixture".into(), name: "Fixture".into(), description: String::new(), enabled: true, provider_takeover: false,
            command: "python3".into(),
            args: vec![PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/acp_agent.py").to_string_lossy().into_owned(), "mcp-load".into()],
            env: HashMap::new(), cwd: None,
        };
        let cancel = CancellationToken::new();
        let cwd = std::env::temp_dir();
        for mode in ["mcp-load", "mcp-resume"] {
            let mut profile = profile.clone();
            profile.args[1] = mode.into();
            let (worker, session, _) = start_worker(&state, profile.clone(), cwd.clone(), None, &cancel, None, true).await.unwrap();
            worker.lifetime.cancel();
            let (worker, _, _) = start_worker(&state, profile, cwd.clone(), Some(session), &cancel, None, true).await.unwrap();
            worker.lifetime.cancel();
        }
        state.shutdown.cancel();
    }

    async fn setup(mode: &str) -> (ApiState, String) {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "local");
        if mode.starts_with("agent-mcp") { state.store.kv_set("mcp_agent_delegation_enabled", "true").unwrap(); }
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
        assert!(state.external.create(&state, "fixture", None, &CancellationToken::new()).await.is_err());
        let project = StoredProject {
            id: "fixture-project".into(), name: "Fixture".into(),
            workspace: std::env::temp_dir().to_string_lossy().into_owned(),
            device_id: None, created_at: 1, updated_at: 1,
        };
        state.store.upsert_project(&project).unwrap();
        let session = state
            .external
            .create(&state, "fixture", Some(project), &CancellationToken::new())
            .await
            .unwrap();
        (state, session)
    }

    #[tokio::test]
    async fn acp_agent_discovers_and_calls_delegation_mcp_tool() {
        for mode in ["agent-mcp", "agent-mcp-http"] {
        let (state, session) = setup(mode).await;
        let (_choices, permissions) = mpsc::channel(1);
        let (updates, _events) = mpsc::channel(32);
        let result = tokio::time::timeout(Duration::from_secs(10), state.external.prompt(
            &state, session.clone(), "list agents".into(), Vec::new(), CancellationToken::new(), permissions, updates,
        )).await.expect("ACP MCP call timed out").unwrap();
        assert_eq!(result["type"], "Finished");
        let rows = state.external.history(&state.store, &session).unwrap();
        assert_eq!(rows.last().unwrap()["text"], "child reply");
        let delegated = state.external.sessions(&state.store).unwrap().into_values().find(|record| record.delegated).unwrap();
        assert_eq!(delegated.messages.last().unwrap()["text"], "child reply");
        state.shutdown.cancel();
        }
    }

    #[tokio::test]
    async fn reports_when_acp_agent_does_not_support_sse_delegation() {
        let (state, session) = setup("load").await;
        state.external.workers.lock().await.remove(&session).unwrap().lifetime.cancel();
        state.store.kv_set("mcp_agent_delegation_enabled", "true").unwrap();
        let error = state.external.settings(&state, &session, None, &CancellationToken::new()).await.unwrap_err();
        assert!(format!("{error:?}").contains("requires ACP MCP SSE or HTTP support"));
        state.shutdown.cancel();
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

    async fn settings_command(state: &ApiState, action: &str, change: Value, session: &str) -> Value {
        let mut body = change;
        body["action"] = json!(action);
        body["sessionId"] = json!(session);
        let response = router(state.clone()).oneshot(axum::http::Request::builder()
            .uri(format!("/api/v1/control/commands/{}", Uuid::new_v4())).method("POST")
            .header("authorization", "Bearer local").header("content-type", "application/json")
            .body(axum::body::Body::from(body.to_string())).unwrap()).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = axum::body::to_bytes(response.into_body(), 65536).await.unwrap();
        let body = String::from_utf8(body.to_vec()).unwrap();
        serde_json::from_str(body.lines().find_map(|line| line.strip_prefix("data: ")).unwrap()).unwrap()
    }

    #[tokio::test]
    async fn acp_settings_follow_agent_responses_notifications_and_rejections() {
        let (state, session) = setup("settings").await;
        let initial = settings_command(&state, "sessionSettings", json!({}), &session).await;
        assert_eq!(initial["settings"]["configOptions"][0]["currentValue"], "read-only");
        let changed = settings_command(&state, "setSessionConfig", json!({"configId":"permissions","value":"full-access"}), &session).await;
        // The fixture normalizes full-access to workspace-write: use the response, never the request.
        assert_eq!(changed["settings"]["configOptions"][0]["currentValue"], "workspace-write");
        let rejected = settings_command(&state, "setSessionConfig", json!({"configId":"network","value":true}), &session).await;
        assert_eq!(rejected["type"], "Error");
        let unchanged = settings_command(&state, "sessionSettings", json!({}), &session).await;
        assert_eq!(unchanged["settings"]["configOptions"][1]["currentValue"], false);
        assert_eq!(unchanged["settings"]["configOptions"][0]["currentValue"], "workspace-write");
        let mode = settings_command(&state, "setSessionConfig", json!({"modeId":"code"}), &session).await;
        assert_eq!(mode["settings"]["modes"]["currentModeId"], "code");
        let invalid = settings_command(&state, "setSessionConfig", json!({"configId":"permissions","value":"unknown"}), &session).await;
        assert_eq!(invalid["type"], "Error");
        state.active_turns.lock().await.insert(session.clone(), CancellationToken::new());
        let busy = settings_command(&state, "setSessionConfig", json!({"modeId":"plan"}), &session).await;
        assert_eq!(busy["code"], "turn_in_progress");
        state.active_turns.lock().await.remove(&session);
        state.external.workers.lock().await.remove(&session);
        let restored = settings_command(&state, "sessionSettings", json!({}), &session).await;
        assert_eq!(restored["settings"]["configOptions"][0]["currentValue"], "read-only", "restored agent is authoritative");
        state.shutdown.cancel();
    }

    #[tokio::test]
    async fn cancelling_acp_settings_releases_admission_and_restores_actual_values() {
        let (state, session) = setup("settings-hang").await;
        let cancel = CancellationToken::new();
        let change = state.external.settings(&state, &session, Some(json!({"configId":"permissions","value":"full-access"})), &cancel);
        let interrupt = async { tokio::time::sleep(Duration::from_millis(20)).await; cancel.cancel(); };
        let (result, _) = tokio::join!(change, interrupt);
        assert!(result.is_err());
        assert!(!state.active_turns.lock().await.contains_key(&session));
        let restored = state.external.settings(&state, &session, None, &CancellationToken::new()).await.unwrap();
        assert_eq!(restored["settings"]["configOptions"][0]["currentValue"], "read-only");
        state.shutdown.cancel();
    }

    #[tokio::test]
    async fn steering_injects_content_and_keeps_commands_usage_and_transcript() {
        let (state, session) = setup("settings").await;
        let (_choices, permissions) = mpsc::channel(8);
        let (updates, mut events) = mpsc::channel(32);
        let content: Vec<ContentBlock> = serde_json::from_value(json!([
            {"type":"text","text":"change direction"},
            {"type":"image","mimeType":"image/png","data":"aGVsbG8="}
        ])).unwrap();
        let prompt = state.external.prompt(&state, session.clone(), "wait".into(), vec![], CancellationToken::new(), permissions, updates);
        let steer = async {
            while let Some(event) = events.recv().await { if event["type"] == "TextDelta" { break; } }
            let result = state.external.steer(&state, &session, content).await.unwrap();
            assert_eq!(result["result"]["outcome"], "injected");
        };
        let (result, _) = tokio::join!(prompt, steer);
        assert_eq!(result.unwrap()["type"], "Finished");
        let rows = state.external.history(&state.store, &session).unwrap();
        assert!(rows.iter().any(|row| row["role"] == "user" && row["text"] == "change direction" && row["content"][1]["type"] == "image"));
        let settings = state.external.settings(&state, &session, None, &CancellationToken::new()).await.unwrap();
        assert_eq!(settings["settings"]["usage"]["used"], 123);
        assert_eq!(settings["settings"]["availableCommands"][0]["name"], "status");
        let idle = state.external.steer(&state, &session, vec![]).await.unwrap();
        assert_eq!(idle["result"]["outcome"], "promptRequired");
        state.shutdown.cancel();
    }

    #[tokio::test]
    async fn acp_tool_and_thought_events_survive_reopening_history() {
        let (state, session) = setup("settings").await;
        let (_choices, permissions) = mpsc::channel(8);
        let (updates, mut events) = mpsc::channel(32);
        let result = state.external.prompt(&state, session.clone(), "tools".into(), vec![], CancellationToken::new(), permissions, updates).await.unwrap();
        assert_eq!(result["type"], "Finished");
        assert_eq!(result["settings"]["configOptions"][0]["currentValue"], "workspace-write");
        let history = state.external.history(&state.store, &session).unwrap();
        let events_saved = history.last().unwrap()["events"].as_array().unwrap();
        assert_eq!(events_saved[0]["type"], "ThoughtDelta");
        assert_eq!(events_saved[1]["text"], "I will read the file. ");
        assert_eq!(events_saved[2]["update"]["rawInput"]["path"], "sample.txt");
        assert_eq!(events_saved[3]["update"]["content"][0]["content"]["text"], "file content");
        assert_eq!(events_saved[4]["text"], "Read complete");
        let mut settings_update = false;
        while let Ok(event) = events.try_recv() { settings_update |= event["type"] == "SessionSettings"; }
        assert!(settings_update, "agent-side changes must be forwarded while streaming");
        state.shutdown.cancel();
    }

    #[tokio::test]
    async fn acp_templates_are_seeded_once_and_preserve_user_configuration() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "local");
        let settings = crate::control::get_settings(State(state.clone())).await.unwrap().0;
        assert!(!settings.enabled);
        assert_eq!(settings.agents.len(), 3);
        assert!(settings.agents.iter().all(|profile| !profile.enabled));
        assert_eq!(settings.agents.iter().map(|profile| profile.name.as_str()).collect::<Vec<_>>(), ["Codex", "Claude Code", "OpenCode"]);
        let mut settings = settings;
        settings.name = "My device".into();
        settings.agents.remove(0);
        settings.agents[0].description = "My coding assistant".into();
        settings.agents[0].enabled = false;
        settings.agents[1].enabled = true;
        let _ = crate::control::set_settings(State(state.clone()), Json(settings)).await.unwrap();
        let reloaded = crate::control::get_settings(State(state.clone())).await.unwrap().0;
        assert_eq!(reloaded.agents.len(), 2, "deleted templates must not reappear");
        assert_eq!(reloaded.name, "My device");
        assert_eq!(reloaded.agents[0].description, "My coding assistant");
        assert!(!reloaded.agents[0].enabled);
        assert!(reloaded.agents[1].enabled, "explicitly enabled profiles stay enabled");
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
                vec![],
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
                vec![],
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
                vec![],
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
