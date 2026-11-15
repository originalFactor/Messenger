//! Tool approval is enforced before Core dispatches any tool host.
use super::*;
const KEY: &str = "tool_approval_mode";
#[derive(Serialize, Deserialize)]
pub(crate) struct Settings { mode: String }
pub(crate) async fn get_settings(State(state): State<ApiState>) -> Result<Json<Settings>, ApiError> { Ok(Json(Settings { mode: mode(&state)? })) }
pub(crate) async fn set_settings(State(state): State<ApiState>, Json(settings): Json<Settings>) -> Result<Json<Settings>, ApiError> {
    if !matches!(settings.mode.as_str(), "manual" | "automatic") { return Err(ApiError::BadRequest("mode must be manual or automatic".into())); }
    state.store.kv_set(KEY, &settings.mode).map_err(ApiError::store)?;
    Ok(Json(settings))
}
fn mode(state: &ApiState) -> Result<String, ApiError> {
    Ok(match state.store.kv_get(KEY).map_err(ApiError::store)?.as_deref() { Some("automatic") => "automatic", _ => "manual" }.into())
}
#[derive(Deserialize)]
pub(crate) struct Decision { pub(crate) approved: bool }
pub(crate) async fn resolve(State(state): State<ApiState>, Path(id): Path<String>, Json(decision): Json<Decision>) -> Result<StatusCode, ApiError> {
    let sender = state.pending_approvals.lock().await.remove(&id).ok_or_else(|| ApiError::not_found("pending approval"))?;
    sender.send(decision.approved).map_err(|_| ApiError::Conflict { code: "approval_closed".into(), message: "the approval request has closed".into() })?;
    Ok(StatusCode::ACCEPTED)
}
fn read_only(name: &str, args: &str) -> bool {
    match name {
        "glob" | "grep" | "read" | skills::TOOL_NAME => true,
        learning::TOOL_NAME => matches!(serde_json::from_str::<Value>(args).ok().and_then(|v| v.get("action")?.as_str().map(str::to_owned)).as_deref(), Some("overview" | "list" | "get")),
        artifacts::TOOL_NAME => matches!(serde_json::from_str::<Value>(args).ok().and_then(|v| v.get("action")?.as_str().map(str::to_owned)).as_deref(), Some("get")),
        _ => false,
    }
}
pub(crate) async fn authorize(state: &ApiState, turn: &TurnRequest, user_request: &str, events: &mpsc::UnboundedSender<String>, supports: bool, name: &str, args: &str, cancel: &CancellationToken) -> Result<(), String> {
    if read_only(name, args) { return Ok(()); }
    if mode(state).map_err(|_| "Cannot read approval settings".to_string())? == "automatic" {
        return match approval_agent::approve(turn, user_request, name, args, cancel).await {
            Ok(true) => Ok(()), Ok(false) => Err("Automatic approval agent denied this tool call".into()), Err(error) => Err(format!("Automatic approval failed: {error}")),
        };
    }
    if !supports { return Err("Tool requires manual approval, but this client cannot request it".into()); }
    let id = Uuid::new_v4().to_string();
    let (sender, receiver) = oneshot::channel();
    state.pending_approvals.lock().await.insert(id.clone(), sender);
    if events.send(serde_json::json!({"type":"ToolApprovalRequested", "callId":id, "name":name, "argumentsJson":args}).to_string()).is_err() {
        state.pending_approvals.lock().await.remove(&id); return Err("Tool approval stream closed".into());
    }
    let approved = tokio::select! {
        result = receiver => result.unwrap_or(false), _ = cancel.cancelled() => false, _ = state.shutdown.cancelled() => false,
        _ = tokio::time::sleep(std::time::Duration::from_secs(300)) => false,
    };
    state.pending_approvals.lock().await.remove(&id);
    let _ = events.send(serde_json::json!({"type":"ToolApprovalResolved", "callId": id}).to_string());
    if approved { Ok(()) } else { Err("Tool approval denied or cancelled".into()) }
}
#[cfg(test)]
mod tests {
    use super::read_only;
    #[test]
    fn classification_is_fail_closed() {
        for name in ["read", "grep", "glob", "messenger_skills"] { assert!(read_only(name, "{}")); }
        for name in ["terminal", "edit", "create", "unknown", "messenger_generate_image"] { assert!(!read_only(name, "{}")); }
        assert!(read_only("messenger_learning", r#"{"action":"get"}"#));
        assert!(!read_only("messenger_learning", r#"{"action":"upsert"}"#));
        assert!(!read_only("messenger_learning", "invalid"));
    }
}
