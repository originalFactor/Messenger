//! JSON-RPC 2.0 wire types + MCP payload shapes (port of `McpProtocol.kt`).

use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JsonRpcRequest {
    pub jsonrpc: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<i64>,
    pub method: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub params: Option<Value>,
}

impl JsonRpcRequest {
    pub fn new(id: Option<i64>, method: impl Into<String>, params: Option<Value>) -> Self {
        Self {
            jsonrpc: "2.0".into(),
            id,
            method: method.into(),
            params,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct JsonRpcResponse {
    #[serde(default = "default_jsonrpc")]
    pub jsonrpc: String,
    pub id: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<JsonRpcError>,
}

fn default_jsonrpc() -> String {
    "2.0".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct JsonRpcError {
    pub code: i64,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McpInitializeParams {
    #[serde(rename = "protocolVersion")]
    pub protocol_version: String,
    pub capabilities: McpClientCapabilities,
    #[serde(rename = "clientInfo")]
    pub client_info: McpImplementation,
}

impl Default for McpInitializeParams {
    fn default() -> Self {
        Self {
            protocol_version: "2024-11-05".into(),
            capabilities: McpClientCapabilities::default(),
            client_info: McpImplementation {
                name: "Messenger".into(),
                version: "1.0.0".into(),
            },
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct McpClientCapabilities {
    pub roots: Option<Value>,
    pub sampling: Option<Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McpImplementation {
    pub name: String,
    pub version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McpToolDefinition {
    pub name: String,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(rename = "inputSchema")]
    pub input_schema: Value,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct McpToolsListResult {
    #[serde(default)]
    pub tools: Vec<McpToolDefinition>,
    #[serde(rename = "nextCursor", default)]
    pub next_cursor: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct McpCallToolResult {
    #[serde(default)]
    pub content: Vec<McpContentPart>,
    #[serde(rename = "isError", default)]
    pub is_error: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McpContentPart {
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(default)]
    pub text: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    // -- ported: McpTest.test JsonRpc parsing --

    #[test]
    fn json_rpc_round_trips() {
        let req = JsonRpcRequest::new(
            Some(1),
            "tools/call",
            Some(serde_json::json!({"name": "echo", "arguments": {"message": "hello"}})),
        );
        let text = serde_json::to_string(&req).unwrap();
        assert!(text.contains(r#""method":"tools/call""#));
        assert!(text.contains(r#""jsonrpc":"2.0""#));

        let resp: JsonRpcResponse =
            serde_json::from_str(r#"{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"hello"}]}}"#)
                .unwrap();
        assert_eq!(resp.id, Some(1));
        assert!(resp.error.is_none());
    }

    #[test]
    fn response_without_result_field_parses() {
        let resp: JsonRpcResponse = serde_json::from_str(r#"{"jsonrpc":"2.0","id":5}"#).unwrap();
        assert_eq!(resp.id, Some(5));
        assert!(resp.result.is_none());
    }
}
