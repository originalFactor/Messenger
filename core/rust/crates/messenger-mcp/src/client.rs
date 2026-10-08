//! MCP client (port of `McpClient.kt`): connect → initialize handshake →
//! notifications/initialized → tools/list; request/response correlation by
//! incrementing id, 30-second request timeout, pending requests completed
//! by incoming lines, and process-exit error propagation.

use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::Duration;

use serde_json::Value;
use tokio::sync::{oneshot, Mutex};

use crate::config::{McpServerConfig, McpTransportType};
use crate::protocol::{
    JsonRpcError, JsonRpcRequest, JsonRpcResponse, McpCallToolResult, McpInitializeParams,
    McpToolDefinition, McpToolsListResult,
};
use crate::transport::{IncomingHandler, McpTransport};

/// Request timeout (Kotlin `withTimeoutOrNull(30_000)`).
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

/// One callable MCP tool, shaped like the built-in tools (`parameters_json`
/// is the tool's input schema, sent verbatim as the request `tools` entry).
#[derive(Debug, Clone, PartialEq)]
pub struct McpChatTool {
    /// `${serverName}_${toolName}` — matches the Kotlin naming so per-tool
    /// configs survive the port.
    pub name: String,
    pub server_name: String,
    pub tool_name: String,
    pub description: String,
    pub parameters_json: String,
}

type PendingMap = Arc<StdMutex<HashMap<i64, oneshot::Sender<JsonRpcResponse>>>>;

struct IncomingBridge {
    pending: PendingMap,
}

impl IncomingHandler for IncomingBridge {
    fn on_line(&self, line: String) {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            return;
        }
        let Ok(response) = serde_json::from_str::<JsonRpcResponse>(trimmed) else {
            return;
        };
        let Some(id) = response.id else {
            return;
        };
        if let Some(sender) = self.pending.lock().unwrap().remove(&id) {
            let _ = sender.send(response);
        }
    }

    fn on_error(&self, _message: String) {
        // stderr log only, matching Kotlin.
    }

    fn on_close(&self, code: i32) {
        let mut pending = self.pending.lock().unwrap();
        for (_, sender) in pending.drain() {
            let _ = sender.send(JsonRpcResponse {
                jsonrpc: "2.0".into(),
                id: None,
                result: None,
                error: Some(JsonRpcError {
                    code: -32000,
                    message: format!("MCP server process exited with code {code}"),
                    data: None,
                }),
            });
        }
    }
}

/// Async client for one MCP server.
pub struct McpClient {
    config: McpServerConfig,
    transport: Arc<dyn McpTransport>,
    request_id: AtomicI64,
    pending: PendingMap,
    tools: Mutex<Vec<McpChatTool>>,
    is_initialized: Mutex<bool>,
}

impl McpClient {
    pub fn new(config: McpServerConfig, transport: Arc<dyn McpTransport>) -> Self {
        Self {
            config,
            transport,
            request_id: AtomicI64::new(0),
            pending: Arc::new(StdMutex::new(HashMap::new())),
            tools: Mutex::new(Vec::new()),
            is_initialized: Mutex::new(false),
        }
    }

    /// Connect + handshake. Returns false on any failure (matching Kotlin).
    pub async fn connect(&self) -> bool {
        let mut initialized = self.is_initialized.lock().await;
        if *initialized {
            return true;
        }
        let bridge = Arc::new(IncomingBridge {
            pending: Arc::clone(&self.pending),
        });
        if !self.transport.start(bridge).await {
            return false;
        }
        if !self.initialize_handshake().await {
            return false;
        }
        self.refresh_tools().await;
        *initialized = true;
        true
    }

    async fn initialize_handshake(&self) -> bool {
        let Some(params) = serde_json::to_value(McpInitializeParams::default()).ok() else {
            return false;
        };
        let Some(response) = self.send_request("initialize", Some(params)).await else {
            return false;
        };
        if response.error.is_some() {
            return false;
        }
        // notifications/initialized
        let notification = serde_json::to_string(&JsonRpcRequest::new(
            None,
            "notifications/initialized",
            None,
        ))
        .unwrap_or_default();
        let _ = self.transport.send_line(&notification).await;
        true
    }

    /// Re-fetch the server's tool list; returns the (prefixed) declarations.
    pub async fn refresh_tools(&self) -> Vec<McpChatTool> {
        let mut tools = Vec::new();
        if let Some(response) = self.send_request("tools/list", None).await {
            if let Some(result) = response.result {
                if let Ok(list) = serde_json::from_value::<McpToolsListResult>(result) {
                    tools = list
                        .tools
                        .into_iter()
                        .map(|tool| Self::to_chat_tool(&self.config.name, tool))
                        .collect();
                }
            }
        }
        *self.tools.lock().await = tools.clone();
        tools
    }

    pub async fn tools(&self) -> Vec<McpChatTool> {
        self.tools.lock().await.clone()
    }

    /// This client's server configuration id (the engine uses it to reconcile
    /// its live client list against the persisted server list).
    pub fn config_id(&self) -> String {
        self.config.id.clone()
    }

    pub fn config(&self) -> &McpServerConfig {
        &self.config
    }

    fn to_chat_tool(server_name: &str, tool: McpToolDefinition) -> McpChatTool {
        McpChatTool {
            name: format!("{server_name}_{}", tool.name),
            server_name: server_name.to_string(),
            tool_name: tool.name.clone(),
            description: tool
                .description
                .unwrap_or_else(|| format!("MCP tool {} from {server_name}", tool.name)),
            parameters_json: tool.input_schema.to_string(),
        }
    }

    /// Call one tool; errors never throw — they come back as
    /// `is_error = true` results so the model can self-correct.
    pub async fn call_tool(&self, name: &str, arguments_json: &str) -> crate::ToolExecutionResultShape {
        let args: Value = serde_json::from_str(arguments_json).unwrap_or_else(|_| Value::Object(Default::default()));
        let params = serde_json::json!({ "name": name, "arguments": args });
        let Some(mut response) = self.send_request("tools/call", Some(params)).await else {
            return crate::ToolExecutionResultShape {
                output: "Tool call failed: server timeout or disconnected.".into(),
                is_error: true,
            };
        };
        if let Some(error) = &response.error {
            return crate::ToolExecutionResultShape {
                output: format!("MCP error ({}): {}", error.code, error.message),
                is_error: true,
            };
        }
        if let Some(result) = response.result.take() {
            if let Ok(call_result) = serde_json::from_value::<McpCallToolResult>(result) {
                let text_output = call_result
                    .content
                    .iter()
                    .map(|part| part.text.clone().unwrap_or_default())
                    .collect::<Vec<_>>()
                    .join("\n");
                return crate::ToolExecutionResultShape {
                    output: if text_output.is_empty() {
                        "(no output)".to_string()
                    } else {
                        text_output
                    },
                    is_error: call_result.is_error,
                };
            }
        }
        crate::ToolExecutionResultShape {
            output: response
                .result
                .as_ref()
                .map(|v| v.to_string())
                .unwrap_or_else(|| "(no result)".to_string()),
            is_error: false,
        }
    }

    /// Send one request and await the correlated response (`None` on send
    /// failure or timeout).
    pub async fn send_request(&self, method: &str, params: Option<Value>) -> Option<JsonRpcResponse> {
        let req_id = self.request_id.fetch_add(1, Ordering::SeqCst) + 1;
        let (tx, rx) = oneshot::channel();
        self.pending.lock().unwrap().insert(req_id, tx);

        let request = JsonRpcRequest::new(Some(req_id), method, params);
        let text = serde_json::to_string(&request).ok()?;

        let sent = self.transport.send_line(&text).await;
        if !sent {
            self.pending.lock().unwrap().remove(&req_id);
            return None;
        }

        match tokio::time::timeout(REQUEST_TIMEOUT, rx).await {
            Ok(Ok(response)) => Some(response),
            _ => {
                self.pending.lock().unwrap().remove(&req_id);
                None
            }
        }
    }

    pub async fn close(&self) {
        *self.is_initialized.lock().await = false;
        self.transport.close().await;
        self.tools.lock().await.clear();
    }
}

/// True when the config uses the in-process native stdio transport (only
/// these can be constructed on the desktop); Android builds its own.
pub fn native_stdio_transport(
    config: &McpServerConfig,
) -> Option<Arc<dyn McpTransport>> {
    if config.transport_type != McpTransportType::STDIO {
        return None;
    }
    let mut command = config.command.clone();
    let mut args = config.args.clone();
    // Kotlin folds args into one command string for the bridge; native spawn
    // splits them back (first whitespace-separated token is the program).
    if !args.is_empty() {
        command = format!("{command} {}", args.join(" "));
    }
    let mut parts = command.split_whitespace();
    let program = parts.next()?.to_string();
    let rest = parts.map(str::to_string).collect();
    args = rest;
    Some(Arc::new(crate::transport::StdioTransport::new(
        program,
        args,
        config.env.clone(),
    )))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::McpServerConfig;
    use crate::transport::{McpTransport, IncomingHandler};
    use std::sync::Mutex as SyncMutex;

    /// Mock transport (port of McpTest's mock bridge): records sent lines
    /// and replays scripted responses through the handler.
    struct MockTransport {
        on_line: SyncMutex<Option<Arc<dyn IncomingHandler>>>,
        responses: Mutex<VecDeque<String>>,
        closed: std::sync::atomic::AtomicBool,
    }

    use std::collections::VecDeque;

    #[async_trait::async_trait]
    impl McpTransport for MockTransport {
        async fn start(&self, handler: Arc<dyn IncomingHandler>) -> bool {
            *self.on_line.lock().unwrap() = Some(handler);
            true
        }

        async fn send_line(&self, line: &str) -> bool {
            if self.closed.load(std::sync::atomic::Ordering::SeqCst) {
                return false;
            }
            let request: JsonRpcRequest = serde_json::from_str(line).unwrap();
            // Notifications get no response — mirrors real servers and keeps
            // the script aligned with requests.
            if request.id.is_none() {
                return true;
            }
            if let Some(mut response) = self.responses.lock().await.pop_front() {
                // Fill in the request id so the client can correlate.
                if let Ok(mut value) = serde_json::from_str::<serde_json::Value>(&response) {
                    value["id"] = serde_json::json!(request.id);
                    response = serde_json::to_string(&value).unwrap();
                }
                if let Some(handler) = self.on_line.lock().unwrap().as_ref() {
                    handler.on_line(response);
                }
            }
            true
        }

        async fn close(&self) {
            self.closed
                .store(true, std::sync::atomic::Ordering::SeqCst);
        }
    }

    fn mock_client(script: Vec<String>) -> McpClient {
        let config = McpServerConfig {
            id: "srv-1".into(),
            name: "mock".into(),
            transport_type: McpTransportType::STDIO,
            command: "mock".into(),
            ..Default::default()
        };
        McpClient::new(
            config,
            Arc::new(MockTransport {
                on_line: SyncMutex::new(None),
                responses: Mutex::new(VecDeque::from(script)),
                closed: std::sync::atomic::AtomicBool::new(false),
            }),
        )
    }

    #[tokio::test]
    async fn connect_handshake_and_tools_list() {
        let client = mock_client(vec![
            r#"{"jsonrpc":"2.0","result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"mock","version":"1.0"}}}"#.into(),
            r#"{"jsonrpc":"2.0","result":{"tools":[{"name":"echo","description":"Echoes","inputSchema":{"type":"object","properties":{"message":{"type":"string"}}}}]}}"#.into(),
        ]);
        assert!(client.connect().await);
        let tools = client.tools().await;
        assert_eq!(tools.len(), 1);
        assert_eq!(tools[0].name, "mock_echo");
        assert_eq!(tools[0].tool_name, "echo");
        assert!(tools[0].parameters_json.contains("\"type\":\"object\""));
    }

    #[tokio::test]
    async fn tool_call_joins_text_content_and_reports_errors() {
        let client = mock_client(vec![
            r#"{"jsonrpc":"2.0","result":{}}"#.into(), // initialize
            r#"{"jsonrpc":"2.0","result":{"tools":[]}}"#.into(), // tools/list
            r#"{"jsonrpc":"2.0","result":{"content":[{"type":"text","text":"hello"},{"type":"text","text":"world"}],"isError":false}}"#.into(),
            r#"{"jsonrpc":"2.0","error":{"code":-32601,"message":"method not found"}}"#.into(),
        ]);
        assert!(client.connect().await);

        let ok = client.call_tool("echo", r#"{"message":"hi"}"#).await;
        assert_eq!(ok.output, "hello\nworld");
        assert!(!ok.is_error);

        let err = client.call_tool("echo", "{}").await;
        assert!(err.is_error);
        assert!(err.output.contains("MCP error (-32601)"));
    }

    #[tokio::test]
    async fn disconnect_surfaces_error_results() {
        let client = mock_client(vec![
            r#"{"jsonrpc":"2.0","result":{}}"#.into(),
            r#"{"jsonrpc":"2.0","result":{"tools":[]}}"#.into(),
        ]);
        assert!(client.connect().await);
        // No scripted response for tools/call → the mock's pop returns None →
        // nothing arrives → 30s timeout... shorten by closing first.
        client.close().await;
        let result = client.call_tool("echo", "{}").await;
        assert!(result.is_error);
        assert!(result.output.contains("timeout or disconnected"));
    }
}
