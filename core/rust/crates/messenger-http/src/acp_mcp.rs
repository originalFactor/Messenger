//! Per-worker loopback MCP endpoints for ACP Skills and optional agent delegation.
use super::*;
use serde_json::{json, Value};
use tokio_stream::wrappers::UnboundedReceiverStream;

#[derive(Clone)]
struct Gateway {
    state: ApiState,
    project: Option<StoredProject>,
    output: Arc<Mutex<external_acp::Output>>,
    secret: String,
    outgoing: Arc<Mutex<Option<mpsc::UnboundedSender<Event>>>>,
    stream_cancel: Arc<Mutex<Option<CancellationToken>>>,
    calls: Arc<Mutex<HashMap<String, CancellationToken>>>,
    lifetime: CancellationToken,
    allow_delegation: bool,
}

pub(crate) async fn start(
    state: ApiState,
    project: Option<StoredProject>,
    output: Arc<Mutex<external_acp::Output>>,
    lifetime: CancellationToken,
    allow_delegation: bool,
) -> Result<String, std::io::Error> {
    let listener = TcpListener::bind(SocketAddr::from(([127, 0, 0, 1], 0))).await?;
    let addr = listener.local_addr()?;
    let secret = Uuid::new_v4().to_string();
    let gateway = Gateway {
        state, project, output, secret: secret.clone(), outgoing: Default::default(), stream_cancel: Default::default(),
        calls: Default::default(), lifetime: lifetime.clone(), allow_delegation,
    };
    let app = Router::new().route("/:secret/events", get(events))
        .route("/:secret/messages", post(messages))
        .route("/:secret/rpc", post(rpc)).with_state(gateway);
    tokio::spawn(async move {
        let _ = axum::serve(listener, app).with_graceful_shutdown(lifetime.cancelled_owned()).await;
    });
    Ok(format!("http://{addr}/{secret}/events"))
}

fn authorized(gateway: &Gateway, secret: &str) -> bool {
    gateway.secret.as_bytes().ct_eq(secret.as_bytes()).into()
}

async fn events(State(gateway): State<Gateway>, Path(secret): Path<String>) -> Result<Sse<impl Stream<Item = Result<Event, Infallible>>>, StatusCode> {
    if !authorized(&gateway, &secret) { return Err(StatusCode::NOT_FOUND); }
    let (sender, receiver) = mpsc::unbounded_channel();
    sender.send(Event::default().event("endpoint").data("messages")).map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let mut outgoing = gateway.outgoing.lock().await;
    if outgoing.as_ref().is_some_and(|current| !current.is_closed()) { return Err(StatusCode::CONFLICT); }
    *outgoing = Some(sender);
    drop(outgoing);
    let cancel = gateway.lifetime.child_token();
    if let Some(previous) = gateway.stream_cancel.lock().await.replace(cancel.clone()) { previous.cancel(); }
    let guard = cancel.drop_guard();
    let stream = UnboundedReceiverStream::new(receiver).map(move |event| {
        let _guard = &guard;
        Ok(event)
    });
    Ok(Sse::new(ShutdownStream::new(stream, &gateway.lifetime)).keep_alive(KeepAlive::default()))
}

async fn messages(State(gateway): State<Gateway>, Path(secret): Path<String>, Json(request): Json<Value>) -> StatusCode {
    if !authorized(&gateway, &secret) { return StatusCode::NOT_FOUND; }
    let Some(sender) = gateway.outgoing.lock().await.clone().filter(|sender| !sender.is_closed()) else { return StatusCode::CONFLICT; };
    let Some(stream_cancel) = gateway.stream_cancel.lock().await.clone() else { return StatusCode::CONFLICT; };
    tokio::spawn(async move {
        if let Some(response) = process_request(gateway, request, Some(stream_cancel)).await {
            let _ = sender.send(Event::default().event("message").data(response.to_string()));
        }
    });
    StatusCode::ACCEPTED
}

async fn rpc(State(gateway): State<Gateway>, Path(secret): Path<String>, Json(request): Json<Value>) -> Response {
    if !authorized(&gateway, &secret) { return StatusCode::NOT_FOUND.into_response(); }
    match process_request(gateway, request, None).await {
        Some(response) => Json(response).into_response(),
        None => StatusCode::ACCEPTED.into_response(),
    }
}

async fn process_request(gateway: Gateway, request: Value, stream_cancel: Option<CancellationToken>) -> Option<Value> {
    let method = request["method"].as_str().unwrap_or_default();
    if method == "notifications/cancelled" {
        if let Some(id) = request["params"].get("requestId") {
            if let Some(cancel) = gateway.calls.lock().await.get(&id.to_string()) { cancel.cancel(); }
        }
        return None;
    }
    let id = request.get("id").cloned().filter(|id| !id.is_null())?;
    let params = request["params"].clone();
    Some(match method {
            "initialize" => json!({"jsonrpc":"2.0","id":id,"result":{
                "protocolVersion":params["protocolVersion"].as_str().filter(|version| matches!(*version, "2024-11-05" | "2025-03-26" | "2025-06-18")).unwrap_or("2025-03-26"), "capabilities":{"tools":{}},
                "serverInfo":{"name":"Messenger Tools","version":messenger_core::version::NAME}
            }}),
            "ping" => json!({"jsonrpc":"2.0","id":id,"result":{}}),
            "tools/list" => {
                let mut declared = Vec::new();
                if gateway.allow_delegation && delegation::enabled(&gateway.state.store) { declared.push(delegation::tool()); }
                // Keep the reader available even for an empty catalog, so enabling or
                // installing a Skill takes effect on the next prompt without a restart.
                declared.push(skills::tool());
                let tools: Vec<_> = declared.into_iter().map(|tool| json!({
                    "name":tool.name,"description":tool.description,
                    "inputSchema":serde_json::from_str::<Value>(&tool.parameters_json).unwrap_or_default()
                })).collect();
                json!({"jsonrpc":"2.0","id":id,"result":{"tools":tools}})
            }
            "tools/call" if params["name"] == skills::TOOL_NAME || (params["name"] == delegation::TOOL_NAME && gateway.allow_delegation) => {
                let result = if let Some(turn) = external_acp::active_cancel(&gateway.output).await {
                    let cancel = turn.child_token();
                    let key = id.to_string();
                    let registered = {
                        let mut calls = gateway.calls.lock().await;
                        if calls.contains_key(&key) || calls.len() >= 32 { false }
                        else { calls.insert(key.clone(), cancel.clone()); true }
                    };
                    if !registered {
                        ToolExecutionResult { output:"Duplicate request ID or too many concurrent calls".into(), is_error:true }
                    } else {
                        let args = params.get("arguments").cloned().unwrap_or_else(|| json!({})).to_string();
                        let result = tokio::select! {
                            result = async {
                                if params["name"] == skills::TOOL_NAME { skills::execute(&gateway.state.store, true, &args) }
                                else { delegation::execute(&gateway.state, gateway.project.as_ref(), true, &args, &cancel).await }
                            } => result,
                            _ = gateway.lifetime.cancelled() => ToolExecutionResult { output:"ACP session closed".into(), is_error:true },
                            _ = async { if let Some(token) = &stream_cancel { token.cancelled().await } else { std::future::pending::<()>().await } } => ToolExecutionResult { output:"MCP connection closed".into(), is_error:true },
                            _ = cancel.cancelled() => ToolExecutionResult { output:"Tool call cancelled".into(), is_error:true },
                        };
                        cancel.cancel();
                        gateway.calls.lock().await.remove(&key);
                        result
                    }
                } else { ToolExecutionResult { output:"No active ACP turn".into(), is_error:true } };
                json!({"jsonrpc":"2.0","id":id,"result":{"content":[{"type":"text","text":result.output}],"isError":result.is_error}})
            }
            _ => json!({"jsonrpc":"2.0","id":id,"error":{"code":-32601,"message":"Method not found"}}),
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use messenger_mcp::{McpClient, McpServerConfig, SseTransport};

    #[tokio::test]
    async fn skills_gateway_does_not_acquire_delegation_when_the_setting_changes() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "token");
        let lifetime = CancellationToken::new();
        let url = start(state.clone(), None, Arc::new(Mutex::new(external_acp::Output::default())), lifetime.clone(), false).await.unwrap();
        let rpc = format!("{}/rpc", url.rsplit_once('/').unwrap().0);
        let client = reqwest::Client::new();
        for enabled in [true, false, true] {
            state.store.kv_set("mcp_agent_delegation_enabled", if enabled { "true" } else { "false" }).unwrap();
            let result: Value = client.post(&rpc).json(&json!({"jsonrpc":"2.0","id":1,"method":"tools/list"})).send().await.unwrap().json().await.unwrap();
            let tools = result["result"]["tools"].as_array().unwrap();
            assert_eq!(tools.len(), 1);
            assert_eq!(tools[0]["name"], skills::TOOL_NAME);
            let result: Value = client.post(&rpc).json(&json!({"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":delegation::TOOL_NAME,"arguments":{"action":"list"}}})).send().await.unwrap().json().await.unwrap();
            assert_eq!(result["error"]["code"], -32601);
        }
        let result: Value = client.post(&rpc).json(&json!({"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":skills::TOOL_NAME,"arguments":{"name":"example"}}})).send().await.unwrap().json().await.unwrap();
        assert_eq!(result["result"]["isError"], true);
        assert!(result.to_string().contains("No active ACP turn"));
        let wrong = format!("{}/wrong/rpc", url.rsplit_once('/').unwrap().0.rsplit_once('/').unwrap().0);
        assert_eq!(client.post(wrong).json(&json!({"id":1,"method":"tools/list"})).send().await.unwrap().status(), StatusCode::NOT_FOUND);
        lifetime.cancel();
        state.shutdown.cancel();
    }

    #[tokio::test]
    async fn gateway_requires_its_capability_and_closes_with_the_session() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "token");
        state.store.kv_set("mcp_agent_delegation_enabled", "true").unwrap();
        let lifetime = CancellationToken::new();
        let url = start(state.clone(), None, Arc::new(Mutex::new(external_acp::Output::default())), lifetime.clone(), true).await.unwrap();
        let wrong = url.rsplit_once('/').unwrap().0.rsplit_once('/').unwrap().0.to_string() + "/wrong/events";
        assert_eq!(reqwest::get(wrong).await.unwrap().status(), StatusCode::NOT_FOUND);
        let client = McpClient::new(McpServerConfig { name:"test".into(), ..Default::default() },
            Arc::new(SseTransport::new(url.clone(), Default::default())));
        assert!(client.connect().await);
        assert_eq!(client.tools().await[0].tool_name, delegation::TOOL_NAME);
        let result = client.call_tool(delegation::TOOL_NAME, r#"{"action":"list"}"#).await;
        assert!(result.is_error);
        assert!(result.output.contains("No active ACP turn"));
        lifetime.cancel();
        client.close().await;
        let closed = tokio::time::timeout(std::time::Duration::from_secs(2), async {
            loop {
                if reqwest::get(&url).await.is_err() { break; }
                tokio::task::yield_now().await;
            }
        }).await;
        assert!(closed.is_ok(), "MCP listener must close with its ACP worker");
        state.shutdown.cancel();
    }
}
