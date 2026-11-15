//! Local, interval-based agent jobs. The Core process is the scheduler.
use super::*;

const PREFIX: &str = "scheduled_task:";
pub(crate) const TOOL_NAME: &str = "messenger_schedule_task";
const TOOL_ENABLED: &str = "scheduled_task_tool_enabled";

pub(crate) fn enabled(store: &Store) -> bool {
    matches!(store.kv_get(TOOL_ENABLED), Ok(Some(value)) if value == "true")
}
pub(crate) fn tool() -> messenger_tools::BuiltinTool {
    messenger_tools::BuiltinTool {
        name: TOOL_NAME.into(),
        description: "Create a recurring scheduled task for the current Agent. It runs while Core is active; missed runs are coalesced. A project task uses the current local project and its Agent tools unattended. Choose projectless to run without a workspace. Do not create a task unless requested by the user.".into(),
        parameters_json: serde_json::json!({"type":"object","properties":{
            "name":{"type":"string","description":"Task name"},
            "prompt":{"type":"string","description":"Instructions for each run"},
            "intervalMinutes":{"type":"integer","minimum":1,"maximum":525600},
            "nextRunAt":{"type":"integer","description":"Optional first execution time, Unix milliseconds UTC; use exactly one of nextRunAt or firstRunDelayMinutes"},
            "firstRunDelayMinutes":{"type":"integer","minimum":1,"maximum":525600,"description":"Delay from now until first execution; use exactly one time option"},
            "projectless":{"type":"boolean","description":"Set true to run without the current project"}
        },"required":["name","prompt","intervalMinutes","projectless"],"additionalProperties":false}).to_string(),
        write_access:true, read_only:false, workspace_required:false,
    }
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ToolSettings { enabled: bool }
async fn get_tool_settings(State(state): State<ApiState>) -> Json<Value> {
    Json(serde_json::json!({"enabled": enabled(&state.store)}))
}
async fn put_tool_settings(State(state): State<ApiState>, Json(body): Json<ToolSettings>) -> Result<Json<Value>, ApiError> {
    let _guard = state.schedule_gate.lock().await;
    state.store.kv_set(TOOL_ENABLED, if body.enabled { "true" } else { "false" }).map_err(ApiError::store)?;
    Ok(Json(serde_json::json!({"enabled":body.enabled})))
}
#[derive(Deserialize)]
#[serde(rename_all="camelCase", deny_unknown_fields)]
struct ToolArgs { name: String, prompt: String, interval_minutes: i64, next_run_at: Option<i64>, first_run_delay_minutes: Option<i64>, projectless: bool }
pub(crate) async fn execute_tool(state: &ApiState, agent_id: &str, conversation_id: &str, permitted: bool, args: &str, current_tool_names: &[String], cancel: &CancellationToken) -> ToolExecutionResult {
    let result = async {
        let args: ToolArgs = serde_json::from_str(args).map_err(ApiError::internal)?;
        let _guard = state.schedule_gate.lock().await;
        if !permitted || !enabled(&state.store) || cancel.is_cancelled() { return Err(ApiError::BadRequest("Scheduled task tool is disabled for this turn".into())); }
        let conversation = state.store.get_conversation(conversation_id).map_err(ApiError::store)?.ok_or_else(|| ApiError::not_found("conversation"))?;
        if conversation.agent_id != agent_id || !conversation.writable || conversation.override_tools_enabled.is_some() || conversation.override_tools_config.is_some() {
            return Err(ApiError::BadRequest("Creating scheduled tasks requires a writable conversation without tool overrides".into()));
        }
        let agent = state.store.get_agent(agent_id).map_err(ApiError::store)?.ok_or_else(|| ApiError::not_found("agent"))?;
        let agent = effective_agent(&state.store, agent, &conversation)?;
        let config = serde_json::from_str::<HashMap<String, bool>>(&agent.tools_config).unwrap_or_default();
        if !agent.tools_enabled || config.get(TOOL_NAME).copied() == Some(false) {
            return Err(ApiError::BadRequest("Scheduled task tool is disabled by Agent policy".into()));
        }
        let project_id = if args.projectless { None } else { Some(conversation.project_id.clone().ok_or_else(|| ApiError::BadRequest("This conversation has no project; select projectless".into()))?) };
        let first_run = match (args.next_run_at, args.first_run_delay_minutes) {
            (Some(at), None) => at,
            (None, Some(delay)) if (1..=525600).contains(&delay) => now_ms().saturating_add(delay * 60_000),
            _ => return Err(ApiError::BadRequest("Specify either nextRunAt or firstRunDelayMinutes".into())),
        };
        let task = Task { id: Uuid::new_v4().to_string(), name: args.name, prompt: args.prompt, agent_id: agent_id.to_owned(), project_id, interval_minutes: args.interval_minutes, next_run_at: first_run, enabled: true, last_run_at: None, last_error: None, last_conversation_id: None, allowed_tools: Some(current_tool_names.iter().filter(|name| name.as_str() != TOOL_NAME && name.as_str() != delegation::TOOL_NAME).cloned().collect()) };
        if task.next_run_at <= now_ms() { return Err(ApiError::BadRequest("first run must be in the future".into())); }
        validate(state, &task)?;
        if cancel.is_cancelled() { return Err(ApiError::BadRequest("Tool call cancelled".into())); }
        write(&state.store, &task)?;
        Ok(task)
    }.await;
    match result {
        Ok(task) => ToolExecutionResult { output: format!("Scheduled task created: {}", task.id), is_error: false, structured_content: Some(serde_json::json!({"scheduledTask":task})) },
        Err(error) => ToolExecutionResult { output: format!("{error:?}"), is_error: true, structured_content: None },
    }
}


#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Task {
    id: String,
    name: String,
    prompt: String,
    agent_id: String,
    project_id: Option<String>,
    interval_minutes: i64,
    next_run_at: i64,
    enabled: bool,
    #[serde(default)]
    last_run_at: Option<i64>,
    #[serde(default)]
    last_error: Option<String>,
    #[serde(default)]
    last_conversation_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    allowed_tools: Option<Vec<String>>,
}

pub(super) fn routes() -> Router<ApiState> {
    Router::new()
        .route("/scheduled-tasks", get(list).post(create))
        .route("/scheduled-tasks/tool-settings", get(get_tool_settings).put(put_tool_settings))
        .route("/scheduled-tasks/:id", put(update).delete(remove))
}
fn key(id: &str) -> String {
    format!("{PREFIX}{id}")
}
fn read(store: &Store, id: &str) -> Result<Option<Task>, ApiError> {
    store
        .kv_get(&key(id))
        .map_err(ApiError::store)?
        .map(|s| serde_json::from_str(&s).map_err(ApiError::internal))
        .transpose()
}
fn write(store: &Store, task: &Task) -> Result<(), ApiError> {
    store
        .kv_set(
            &key(&task.id),
            &serde_json::to_string(task).map_err(ApiError::internal)?,
        )
        .map_err(ApiError::store)
}
fn validate(state: &ApiState, task: &Task) -> Result<(), ApiError> {
    if Uuid::parse_str(&task.id).is_err()
        || task.name.trim().is_empty()
        || task.name.len() > 256
        || task.prompt.trim().is_empty()
        || task.prompt.len() > 65536
        || !(1..=525600).contains(&task.interval_minutes)
        || task.next_run_at <= 0
    {
        return Err(ApiError::BadRequest(
            "invalid task id, name, prompt, interval or next run time".into(),
        ));
    }
    state
        .store
        .get_agent(&task.agent_id)
        .map_err(ApiError::store)?
        .filter(|a| a.role != "title")
        .ok_or_else(|| ApiError::not_found("agent"))?;
    control::local_workspace(state, task.project_id.as_deref())?;
    Ok(())
}
async fn list(State(state): State<ApiState>) -> Result<Json<Vec<Task>>, ApiError> {
    let _guard = state.schedule_gate.lock().await;
    let mut tasks: Vec<Task> = state
        .store
        .kv_list_prefix(PREFIX)
        .map_err(ApiError::store)?
        .into_iter()
        .map(|(_, s)| serde_json::from_str(&s).map_err(ApiError::internal))
        .collect::<Result<_, _>>()?;
    tasks.sort_by(|a, b| a.name.cmp(&b.name).then(a.id.cmp(&b.id)));
    Ok(Json(tasks))
}
async fn create(
    State(state): State<ApiState>,
    Json(mut task): Json<Task>,
) -> Result<Json<Task>, ApiError> {
    let _guard = state.schedule_gate.lock().await;
    validate(&state, &task)?;
    if read(&state.store, &task.id)?.is_some() {
        return Err(ApiError::Conflict {
            code: "task_exists".into(),
            message: "task already exists".into(),
        });
    }
    task.allowed_tools = None;
    task.last_run_at = None;
    task.last_error = None;
    task.last_conversation_id = None;
    write(&state.store, &task)?;
    Ok(Json(task))
}
async fn update(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(mut task): Json<Task>,
) -> Result<Json<Task>, ApiError> {
    let guard = state.schedule_gate.lock().await;
    if id != task.id {
        return Err(ApiError::BadRequest("task id does not match path".into()));
    }
    validate(&state, &task)?;
    let old = read(&state.store, &id)?.ok_or_else(|| ApiError::not_found("task"))?;
    if task.last_run_at != old.last_run_at {
        return Err(ApiError::Conflict { code: "task_changed".into(), message: "Task ran since it was loaded; reload and try again".into() });
    }
    task.allowed_tools = old.allowed_tools;
    task.last_run_at = old.last_run_at;
    task.last_error = old.last_error;
    task.last_conversation_id = old.last_conversation_id;
    write(&state.store, &task)?;
    if !task.enabled {
        if let Some(cancel) = guard.get(&id) {
            cancel.cancel();
        }
    }
    Ok(Json(task))
}
async fn remove(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    let guard = state.schedule_gate.lock().await;
    if let Some(cancel) = guard.get(&id) {
        cancel.cancel();
    }
    if read(&state.store, &id)?.is_none() {
        return Err(ApiError::not_found("task"));
    }
    state.store.kv_delete(&key(&id)).map_err(ApiError::store)?;
    Ok(StatusCode::NO_CONTENT)
}
pub(super) async fn clear(state: &ApiState) -> Result<(), ApiError> {
    let guard = state.schedule_gate.lock().await;
    for cancel in guard.values() {
        cancel.cancel();
    }
    for (key, _) in state
        .store
        .kv_list_prefix(PREFIX)
        .map_err(ApiError::store)?
    {
        state.store.kv_delete(&key).map_err(ApiError::store)?;
    }
    Ok(())
}

pub(super) async fn run(state: ApiState) {
    let mut tick = tokio::time::interval(std::time::Duration::from_secs(5));
    loop {
        tokio::select! { _ = state.shutdown.cancelled() => break, _ = tick.tick() => {} }
        let tasks = {
            let mut guard = state.schedule_gate.lock().await;
            let now = now_ms();
            let mut due = Vec::new();
            if let Ok(rows) = state.store.kv_list_prefix(PREFIX) {
                for (_, value) in rows {
                    if let Ok(mut task) = serde_json::from_str::<Task>(&value) {
                        if task.enabled && task.next_run_at <= now && !guard.contains_key(&task.id)
                        {
                            task.last_run_at = Some(now);
                            task.last_error =
                                Some("Execution interrupted before completion".into());
                            task.last_conversation_id = None;
                            task.next_run_at =
                                now.saturating_add(task.interval_minutes.saturating_mul(60_000));
                            if write(&state.store, &task).is_ok() {
                                let cancel = state.shutdown.child_token();
                                guard.insert(task.id.clone(), cancel.clone());
                                due.push((task, cancel));
                            }
                        }
                    }
                }
            }
            due
        };
        for (task, cancel) in tasks {
            let state = state.clone();
            tokio::spawn(async move {
                execute(state, task, cancel).await;
            });
        }
    }
}
async fn execute(state: ApiState, task: Task, cancel: CancellationToken) {
    let result = execute_inner(&state, &task, cancel).await;
    let mut guard = state.schedule_gate.lock().await;
    guard.remove(&task.id);
    if let Ok(Some(mut current)) = read(&state.store, &task.id) {
        // An intervening run owns the newer status.
        if current.last_run_at == task.last_run_at {
            match result {
                Ok(id) => {
                    current.last_error = None;
                    current.last_conversation_id = Some(id);
                }
                Err((error, id)) => {
                    current.last_error = Some(error);
                    current.last_conversation_id = id;
                }
            }
            let _ = write(&state.store, &current);
        }
    }
}
async fn execute_inner(
    state: &ApiState,
    task: &Task,
    cancel: CancellationToken,
) -> Result<String, (String, Option<String>)> {
    let guard = state.schedule_gate.lock().await;
    if cancel.is_cancelled() {
        return Err(("Execution cancelled".into(), None));
    }
    let prepare = || -> Result<StoredConversation, ApiError> {
        control::local_workspace(state, task.project_id.as_deref())?;
        let agent = state
            .store
            .get_agent(&task.agent_id)
            .map_err(ApiError::store)?
            .filter(|a| a.role != "title")
            .ok_or_else(|| ApiError::not_found("agent"))?;
        let agent = effective_agent(&state.store, agent, &StoredConversation::default())?;
        if agent.default_model_id.is_none() {
            return Err(ApiError::Conflict {
                code: "model_required".into(),
                message: "set a model for this agent before sending a message".into(),
            });
        }
        control::create_conversation(state, &task.agent_id, task.project_id.clone(), true)
    };
    let conversation = prepare().map_err(|e| (format!("{e:?}"), None))?;
    let id = conversation.id;
    if let Ok(Some(mut current)) = read(&state.store, &task.id) {
        if current.last_run_at == task.last_run_at {
            current.last_conversation_id = Some(id.clone());
            let _ = write(&state.store, &current);
        }
    }
    drop(guard);
    let mut request = control::text_request(task.prompt.clone());
    request.no_agent_delegation = true;
    request.scheduled_run = true;
    request.scheduled_allowed_tools = task.allowed_tools.clone();
    let mut events = start_turn(
        state.clone(),
        id.clone(),
        request,
        cancel,
        true,
    )
    .await
    .map_err(|e| (format!("{e:?}"), Some(id.clone())))?;
    while let Some(event) = events.recv().await {
        if let Ok(value) = serde_json::from_str::<Value>(&event) {
            match value.get("type").and_then(Value::as_str) {
                Some("Finished") => return Ok(id),
                Some("Cancelled") => return Err(("Execution cancelled".into(), Some(id))),
                Some("Error") => {
                    return Err((
                        value
                            .get("message")
                            .and_then(Value::as_str)
                            .unwrap_or("Execution failed")
                            .to_owned(),
                        Some(id),
                    ))
                }
                _ => {}
            }
        }
    }
    Err(("Execution ended without a terminal event".into(), Some(id)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn agent_creation_obeys_scope_and_policy() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "test");
        crate::tests::seed_text_chat(&state, "http://localhost");
        let mut agent = state.store.get_agent("a1").unwrap().unwrap();
        agent.tools_enabled = true;
        state.store.upsert_agent(&agent).unwrap();
        let args = serde_json::json!({"name":"Reminder", "prompt":"Do work", "intervalMinutes":60, "firstRunDelayMinutes":10, "projectless":true}).to_string();
        let token = CancellationToken::new();
        let names = vec!["read".into(), TOOL_NAME.into(), delegation::TOOL_NAME.into()];
        let call = |permitted, args: String| { let state = state.clone(); let token = token.clone(); let names = names.clone(); async move { execute_tool(&state, "a1", "c1", permitted, &args, &names, &token).await } };
        assert!(call(true, args.clone()).await.is_error); // switch defaults off
        state.store.kv_set(TOOL_ENABLED, "true").unwrap();
        assert!(call(false, args.clone()).await.is_error);
        assert!(call(true, args.clone()).await.is_error); // conversation is read-only
        let mut conversation = state.store.get_conversation("c1").unwrap().unwrap();
        conversation.writable = true;
        conversation.override_tools_config = Some("{}".into());
        state.store.upsert_conversation(&conversation).unwrap();
        assert!(call(true, args.clone()).await.is_error);
        conversation.override_tools_config = None;
        state.store.upsert_conversation(&conversation).unwrap();
        agent.tools_config = serde_json::json!({TOOL_NAME:false}).to_string();
        state.store.upsert_agent(&agent).unwrap();
        assert!(call(true, args.clone()).await.is_error);
        agent.tools_config.clear();
        state.store.upsert_agent(&agent).unwrap();
        assert!(call(true, args.replace("\"intervalMinutes\":60", "\"intervalMinutes\":0")).await.is_error);
        let result = call(true, args.clone()).await;
        assert!(!result.is_error, "{}", result.output);
        let data = result.structured_content.unwrap();
        assert_eq!(data["scheduledTask"]["agentId"], "a1");
        assert!(data["scheduledTask"]["projectId"].is_null());
        assert_eq!(data["scheduledTask"]["allowedTools"], serde_json::json!(["read"]));
        let project = StoredProject { id:"proj".into(), name:"Project".into(), workspace:"/tmp".into(), device_id:None, created_at:1, updated_at:1 };
        state.store.upsert_project(&project).unwrap();
        conversation.project_id = Some(project.id);
        state.store.upsert_conversation(&conversation).unwrap();
        let project_args = args.replace("\"projectless\":true", "\"projectless\":false");
        let result = call(true, project_args).await;
        assert!(!result.is_error, "{}", result.output);
        assert_eq!(result.structured_content.unwrap()["scheduledTask"]["projectId"], "proj");
    }

    #[tokio::test]
    async fn clear_cancels_claimed_tasks_and_removes_persisted_data() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "test");
        let cancel = CancellationToken::new();
        state.schedule_gate.lock().await.insert("a".into(), cancel.clone());
        state.store.kv_set(&key("a"), "{}").unwrap();
        clear(&state).await.unwrap();
        assert!(cancel.is_cancelled());
        assert!(state.store.kv_get(&key("a")).unwrap().is_none());
    }

    #[tokio::test]
    async fn overdue_task_is_claimed_once_and_failure_is_visible() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "test");
        let task = Task {
            id: Uuid::new_v4().to_string(), name: "Test".into(), prompt: "Hello".into(),
            agent_id: "missing".into(), project_id: None, interval_minutes: 1,
            next_run_at: 1, enabled: true, last_run_at: None, last_error: None,
            last_conversation_id: None, allowed_tools: None,
        };
        write(&state.store, &task).unwrap();
        let worker = tokio::spawn(run(state.clone()));
        for _ in 0..100 {
            let saved = read(&state.store, &task.id).unwrap().unwrap();
            if saved.last_error.as_deref().is_some_and(|e| e.contains("NotFound")) {
                assert!(saved.next_run_at > saved.last_run_at.unwrap());
                assert!(saved.last_conversation_id.is_none());
                state.shutdown.cancel();
                worker.await.unwrap();
                return;
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
        state.shutdown.cancel();
        worker.await.unwrap();
        panic!("scheduled failure was not recorded");
    }
}
