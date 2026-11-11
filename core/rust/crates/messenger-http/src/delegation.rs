//! A bounded, device-local delegation tool for Messenger turns.
use super::*;
use serde_json::{json, Value};

pub(crate) const TOOL_NAME: &str = "messenger_agents";
const SETTING: &str = "mcp_agent_delegation_enabled";

pub(crate) fn enabled(store: &Store) -> bool {
    matches!(store.kv_get(SETTING), Ok(Some(value)) if value == "true")
}

pub(crate) fn tool() -> messenger_tools::BuiltinTool {
    messenger_tools::BuiltinTool {
        name: TOOL_NAME.into(),
        description: "List available agents or delegate a task to an agent. ACP agent IDs start with acp:.".into(),
        parameters_json: json!({"type":"object","properties":{
            "action":{"type":"string","enum":["list","call"]},
            "agentId":{"type":"string","description":"Agent ID returned by list"},
            "task":{"type":"string","description":"Task to delegate"}
        },"required":["action"]}).to_string(),
        write_access: false, read_only: false, workspace_required: false,
    }
}

pub(crate) async fn get_setting(State(state): State<ApiState>) -> Json<Value> {
    Json(json!({"enabled":enabled(&state.store)}))
}

#[derive(Deserialize)]
pub(crate) struct Setting { enabled: bool }

pub(crate) async fn set_setting(State(state): State<ApiState>, Json(setting): Json<Setting>) -> Result<StatusCode, ApiError> {
    state.store.kv_set(SETTING, if setting.enabled { "true" } else { "false" }).map_err(ApiError::store)?;
    Ok(StatusCode::NO_CONTENT)
}

pub(crate) async fn execute(state: &ApiState, project: Option<&StoredProject>, writable: bool, args: &str, cancel: &CancellationToken) -> ToolExecutionResult {
    let result = execute_inner(state, project, writable, args, cancel).await;
    match result {
        Ok(output) => ToolExecutionResult { output, is_error: false },
        Err(error) => ToolExecutionResult { output: error, is_error: true },
    }
}

async fn execute_inner(state: &ApiState, project: Option<&StoredProject>, writable: bool, args: &str, cancel: &CancellationToken) -> Result<String, String> {
    if !enabled(&state.store) { return Err("Agent delegation is disabled.".into()); }
    let args: Value = serde_json::from_str(args).map_err(|e| e.to_string())?;
    if args["action"] == "list" {
        let mut agents: Vec<Value> = state.store.list_agents().map_err(|e| e.to_string())?.into_iter()
            .filter(|agent| agent.role != "title")
            .map(|agent| json!({"id":agent.id,"name":agent.name,"kind":"messenger"})).collect();
        agents.extend(control::settings(&state.store).map_err(|e| format!("{e:?}"))?.agents.into_iter()
            .filter(|agent| agent.enabled).map(|agent| json!({"id":format!("acp:{}",agent.id),"name":agent.name,"description":agent.description,"kind":"acp"})));
        return Ok(json!({"agents":agents}).to_string());
    }
    if args["action"] != "call" { return Err("action must be list or call".into()); }
    let agent = args["agentId"].as_str().filter(|s| !s.is_empty()).ok_or("agentId is required")?;
    let task = args["task"].as_str().filter(|s| !s.trim().is_empty() && s.len() <= 128_000).ok_or("task must contain 1–128000 bytes")?;
    if let Some(project) = project {
        if state.remote.owner_of(project) != ProjectOwner::Local { return Err("Agent delegation requires a local project.".into()); }
    }
    let child_cancel = cancel.child_token();
    if let Some(profile) = agent.strip_prefix("acp:") {
        if !writable { return Err("ACP delegation requires writable mode because ACP agents manage their own tools.".into()); }
        let id = state.external.create(state, profile, project.cloned(), &child_cancel).await.map_err(|e| format!("{e:?}"))?;
        let (choice_tx, choices) = mpsc::channel(1);
        drop(choice_tx); // A delegated agent cannot approve its own permission requests.
        let (updates, mut events) = mpsc::channel::<Value>(32);
        let drain = tokio::spawn(async move {
            let mut permission_requested = false;
            while let Some(event) = events.recv().await { permission_requested |= event["type"] == "Permission"; }
            permission_requested
        });
        let result = state.external.prompt(state, id.clone(), task.into(), Vec::new(), child_cancel, choices, updates).await;
        let permission_requested = drain.await.unwrap_or(false);
        let result = result.map_err(|e| format!("{e:?}"))?;
        if permission_requested { return Err("The delegated ACP agent requested permission; the request was cancelled.".into()); }
        if result["type"] != "Finished" { return Err(result.to_string()); }
        let record = state.external.record(&state.store, &id).map_err(|e| format!("{e:?}"))?;
        return Ok(record.messages.last().and_then(|row| row["text"].as_str()).unwrap_or("").to_string());
    }
    let conversation = control::create_conversation(state, agent, project.map(|p| p.id.clone()), writable).map_err(|e| format!("{e:?}"))?;
    let mut request = control::text_request(task.into());
    request.no_agent_delegation = true;
    let mut events = start_turn(state.clone(), conversation.id, request, child_cancel, true).await.map_err(|e| format!("{e:?}"))?;
    let mut answer = String::new();
    while let Some(event) = events.recv().await {
        let value: Value = serde_json::from_str(&event).map_err(|e| e.to_string())?;
        match value["type"].as_str() {
            Some("FinalMessagePersisted") => answer = value["content"].as_str().unwrap_or_default().into(),
            Some("Finished") => return Ok(answer),
            Some("Error" | "Cancelled") => return Err(value.to_string()),
            _ => {}
        }
    }
    Err("Agent turn ended without a result.".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn setting_defaults_off_and_rejects_calls_when_disabled() {
        let store = Arc::new(Store::open_memory().unwrap());
        let state = ApiState::new(store.clone(), "token");
        assert!(!enabled(&store));
        let result = execute(&state, None, false, r#"{"action":"list"}"#, &CancellationToken::new()).await;
        assert!(result.is_error);
        set_setting(State(state.clone()), Json(Setting { enabled: true })).await.unwrap();
        let result = execute(&state, None, false, r#"{"action":"list"}"#, &CancellationToken::new()).await;
        assert!(!result.is_error);
        assert!(serde_json::from_str::<Value>(&result.output).unwrap()["agents"].is_array());
        set_setting(State(state.clone()), Json(Setting { enabled: false })).await.unwrap();
        assert!(!enabled(&store));
        state.shutdown.cancel();
    }

    #[tokio::test]
    async fn delegation_rejects_bad_arguments_and_unknown_agents() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "token");
        set_setting(State(state.clone()), Json(Setting { enabled: true })).await.unwrap();
        let cancel = CancellationToken::new();
        for args in ["not json", r#"{"action":"call"}"#, r#"{"action":"call","agentId":"missing","task":"hello"}"#] {
            assert!(execute(&state, None, false, args, &cancel).await.is_error);
        }
        assert!(state.store.list_conversations().unwrap().is_empty());
        state.shutdown.cancel();
    }

    #[cfg(all(unix, not(target_os = "android")))]
    #[tokio::test]
    async fn delegates_to_acp_and_reports_cancelled_permission_request() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "token");
        set_setting(State(state.clone()), Json(Setting { enabled: true })).await.unwrap();
        let fixture = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/acp_agent.py");
        let settings: control::Settings = serde_json::from_value(json!({"agents":[{
            "id":"fixture", "name":"Fixture", "command":"python3", "args":[fixture,"load"]
        }]})).unwrap();
        let _ = control::set_settings(State(state.clone()), Json(settings)).await.unwrap();
        let project = StoredProject { id:"p".into(), name:"P".into(), workspace:std::env::temp_dir().to_string_lossy().into_owned(), device_id:None, created_at:1, updated_at:1 };
        state.store.upsert_project(&project).unwrap();
        let result = tokio::time::timeout(std::time::Duration::from_secs(5), execute(
            &state, Some(&project), true,
            r#"{"action":"call","agentId":"acp:fixture","task":"hello"}"#,
            &CancellationToken::new(),
        )).await.expect("delegation must finish");
        assert!(result.is_error);
        assert!(result.output.contains("requested permission"));
        state.shutdown.cancel();
    }
}
