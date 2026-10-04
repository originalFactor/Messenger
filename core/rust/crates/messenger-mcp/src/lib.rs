//! MCP (Model Context Protocol) client (port of `domain/mcp/*`): server
//! configs persisted verbatim to the DataStore `mcp_servers_json` shape,
//! JSON-RPC 2.0 over pluggable transports (stdio process / SSE+HTTP), and
//! the client lifecycle (initialize handshake → tools/list → tools/call).

pub mod client;
pub mod config;
pub mod protocol;
pub mod transport;

/// Result of one MCP tool call (same shape as the built-in
/// `messenger_tools::ToolExecutionResult`).
#[derive(Debug, Clone, PartialEq)]
pub struct ToolExecutionResultShape {
    pub output: String,
    pub is_error: bool,
}

pub use client::McpClient;
pub use config::{encode_server_list, decode_server_list, McpServerConfig, McpTransportType};
pub use transport::{IncomingHandler, McpTransport, StdioTransport, SseTransport};
