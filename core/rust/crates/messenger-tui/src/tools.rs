//! `NativeToolHost`: the `ToolHost` the agent core calls for every tool the
//! model invokes. Dispatch is terminal → shell, the five workspace tools →
//! the filesystem, anything else → MCP (by tool name).

use std::path::PathBuf;
use std::sync::Arc;

use async_trait::async_trait;
use messenger_core::agent::ToolHost;
use messenger_mcp::McpClient;
use messenger_tools::ToolExecutionResult;
use tokio::sync::Mutex;
use tokio_util::sync::CancellationToken;

use crate::engine::UiMsg;
use crate::shell::{self, ShellConfig};
use crate::workspace;

pub struct NativeToolHost {
    pub workspace: PathBuf,
    pub shell: ShellConfig,
    /// Live MCP clients; the same inventory the Engine maintains.
    pub mcp: Arc<Mutex<Vec<Arc<McpClient>>>>,
    /// Cancellation token of the turn this host executes for, so a cancelled
    /// turn kills a running shell command instead of orphaning it.
    pub cancel: CancellationToken,
    pub log: tokio::sync::mpsc::UnboundedSender<UiMsg>,
}

impl NativeToolHost {
    fn log(&self, message: impl Into<String>) {
        let _ = self.log.send(UiMsg::ToolLog(message.into()));
    }
}

#[async_trait]
impl ToolHost for NativeToolHost {
    async fn execute(&self, name: &str, arguments_json: &str) -> ToolExecutionResult {
        match name {
            messenger_tools::BuiltinTool::TERMINAL_NAME => {
                let Some(command) = messenger_tools::BuiltinTool::parse_command(arguments_json)
                else {
                    return ToolExecutionResult {
                        output: format!("Invalid arguments for {name}."),
                        is_error: true,
                    };
                };
                shell::run(&command, &self.shell, &self.workspace, &self.cancel).await
            }
            messenger_tools::tools::GLOB
            | messenger_tools::tools::GREP
            | messenger_tools::tools::READ
            | messenger_tools::tools::EDIT
            | messenger_tools::tools::CREATE => {
                workspace::execute(name, arguments_json, &self.workspace)
            }
            other => self.execute_mcp(other, arguments_json).await,
        }
    }
}

impl NativeToolHost {
    async fn execute_mcp(&self, name: &str, arguments_json: &str) -> ToolExecutionResult {
        let clients: Vec<Arc<McpClient>> = self.mcp.lock().await.clone();
        for client in clients {
            for tool in client.tools().await {
                if tool.name != name {
                    continue;
                }
                let result = client.call_tool(&tool.tool_name, arguments_json).await;
                if result.is_error {
                    self.log(format!("MCP tool {name} failed: {}", result.output));
                }
                return ToolExecutionResult {
                    output: result.output,
                    is_error: result.is_error,
                };
            }
        }
        ToolExecutionResult {
            output: format!("Unknown tool: {name}"),
            is_error: true,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn host(dir: &std::path::Path) -> NativeToolHost {
        let (tx, _rx) = tokio::sync::mpsc::unbounded_channel();
        NativeToolHost {
            workspace: dir.to_path_buf(),
            shell: ShellConfig::default(),
            mcp: Arc::new(Mutex::new(Vec::new())),
            cancel: CancellationToken::new(),
            log: tx,
        }
    }

    #[tokio::test]
    async fn dispatches_workspace_tools_to_the_filesystem() {
        let dir = tempfile::tempdir().unwrap();
        let host = host(dir.path());
        let created = host
            .execute("create", r#"{"path":"a.txt","content":"hello"}"#)
            .await;
        assert!(!created.is_error, "{}", created.output);
        let read = host.execute("read", r#"{"path":"a.txt"}"#).await;
        assert_eq!(read.output, "1: hello");
    }

    #[tokio::test]
    async fn unknown_tool_returns_an_error_result() {
        let dir = tempfile::tempdir().unwrap();
        let host = host(dir.path());
        let result = host.execute("nope", "{}").await;
        assert!(result.is_error);
        assert_eq!(result.output, "Unknown tool: nope");
    }

    #[tokio::test]
    async fn terminal_rejects_malformed_arguments() {
        let dir = tempfile::tempdir().unwrap();
        let host = host(dir.path());
        let result = host.execute("terminal", "{}").await;
        assert!(result.is_error);
        assert_eq!(result.output, "Invalid arguments for terminal.");
    }
}
