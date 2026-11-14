//! Local, interval-based agent jobs. The Core process is the scheduler.
use super::*;

const PREFIX: &str = "scheduled_task:";

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
}

pub(super) fn routes() -> Router<ApiState> {
    Router::new()
        .route("/scheduled-tasks", get(list).post(create))
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
    let mut events = start_turn(
        state.clone(),
        id.clone(),
        control::text_request(task.prompt.clone()),
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
            last_conversation_id: None,
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
