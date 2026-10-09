//! `Engine`: spawns agent turns, forwards every `AgentEvent` to the UI over
//! an unbounded channel, and owns the MCP client lifecycle.
//!
//! The TUI runs one turn at a time (the chat view is single-conversation), so
//! the engine keeps exactly one cancellation token — mirroring
//! `CoreHandle::cancel_token`.

use std::sync::Arc;

use messenger_core::agent::{run_chat_turn, AgentEvent, TurnSink};
use messenger_mcp::client::McpChatTool;
use messenger_mcp::config::{decode_server_list, McpServerConfig, McpTransportType};
use messenger_mcp::McpClient;
use messenger_store::Store;
use tokio::sync::{mpsc, Mutex};
use tokio_util::sync::CancellationToken;

use crate::store_ops::ResolvedTurn;
use crate::tools::NativeToolHost;

/// Everything the worker tasks report back to the UI loop.
#[derive(Debug, Clone)]
pub enum UiMsg {
    /// One agent-loop event.
    Agent(AgentEvent),
    /// Non-fatal diagnostics (MCP connect failures, tool errors).
    ToolLog(String),
    /// Login/sync result text for the status line.
    CloudStatus(String),
    /// Card preview finished: `Ok` opens the redemption confirmation.
    CardPreview {
        code: String,
        preview: Result<Box<CardSnapshot>, String>,
    },
    /// A cloud mutation finished; the UI should reload conversations.
    SyncFinished,
}

/// The card fields the confirmation dialog shows.
#[derive(Debug, Clone)]
pub struct CardSnapshot {
    pub plan_name: String,
    pub quota_tokens: i64,
    pub validity_days: i64,
}

/// Sink that forwards agent events into the UI channel.
struct ChannelSink(mpsc::UnboundedSender<UiMsg>);

impl TurnSink for ChannelSink {
    fn on_event(&self, event: &AgentEvent) {
        let _ = self.0.send(UiMsg::Agent(event.clone()));
    }
}

pub struct Engine {
    pub store: Arc<Store>,
    pub tx: mpsc::UnboundedSender<UiMsg>,
    pub cancel: Arc<std::sync::Mutex<CancellationToken>>,
    pub mcp: Arc<Mutex<Vec<Arc<McpClient>>>>,
    /// Cached MCP tool list (the resolver is sync; `McpClient::tools()` is not).
    mcp_tools: Arc<std::sync::Mutex<Vec<McpChatTool>>>,
    workspace: std::path::PathBuf,
    /// The UI loop runs on the main thread, which is NOT inside a Tokio
    /// context, so background work must spawn through this handle.
    runtime: tokio::runtime::Handle,
}

impl Engine {
    pub fn new(
        store: Arc<Store>,
        tx: mpsc::UnboundedSender<UiMsg>,
        workspace: std::path::PathBuf,
        runtime: tokio::runtime::Handle,
    ) -> Self {
        Self {
            store,
            tx,
            cancel: Arc::new(std::sync::Mutex::new(CancellationToken::new())),
            mcp: Arc::new(Mutex::new(Vec::new())),
            mcp_tools: Arc::new(std::sync::Mutex::new(Vec::new())),
            workspace,
            runtime,
        }
    }

    /// An engine for rendering-only tests, which run on plain threads with no
    /// reactor. It owns a runtime so `Handle::current()` is never reached —
    /// rendering must not depend on the caller's async context.
    #[cfg(test)]
    pub fn headless(store: Store) -> Self {
        let (tx, _rx) = mpsc::unbounded_channel();
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("a current-thread runtime needs no reactor");
        let handle = runtime.handle().clone();
        // The handle outlives the engine, so the runtime has to as well. It is
        // never used for work — only held so spawns have somewhere to go.
        std::mem::forget(runtime);
        Self::new(
            Arc::new(store),
            tx,
            std::path::PathBuf::from("."),
            handle,
        )
    }

    /// A spawner for detached background work, decoupled from `self`.
    ///
    /// Never call `tokio::spawn` directly from UI code: key handling runs
    /// outside a runtime context and would panic with "there is no reactor
    /// running". The returned handle is a cheap clone, so the spawned task
    /// can still own the `Arc<Engine>` it was written against.
    pub fn spawner(&self) -> tokio::runtime::Handle {
        self.runtime.clone()
    }

    fn log(&self, message: impl Into<String>) {
        let _ = self.tx.send(UiMsg::ToolLog(message.into()));
    }

    /// Snapshot of the connected MCP tools for request resolution.
    pub fn mcp_tools(&self) -> Vec<McpChatTool> {
        self.mcp_tools.lock().unwrap().clone()
    }

    /// Cancel the running turn. Partial rows are preserved by the loop.
    pub fn cancel_turn(&self) {
        self.cancel.lock().unwrap().cancel();
    }

    /// Spawn one full agent turn.
    ///
    /// The tool host runs in the resolved turn's project workspace, falling
    /// back to the configured default directory for a conversation that
    /// belongs to no project (MCP tools stay available everywhere).
    pub fn start_turn(self: &Arc<Self>, resolved: ResolvedTurn) {
        let cancel = {
            let mut guard = self.cancel.lock().unwrap();
            *guard = CancellationToken::new();
            guard.clone()
        };
        let store = Arc::clone(&self.store);
        let tx = self.tx.clone();
        let mcp = Arc::clone(&self.mcp);
        let host = Arc::new(NativeToolHost {
            workspace: resolved
                .workspace
                .unwrap_or_else(|| self.workspace.clone()),
            shell: crate::shell::ShellConfig::default(),
            mcp,
            cancel: cancel.clone(),
            log: tx.clone(),
        });
        let request = resolved.request;
        self.runtime.spawn(async move {
            let sink = ChannelSink(tx.clone());
            if let Err(error) = run_chat_turn(&store, host.as_ref(), &sink, &request, cancel).await {
                let _ = tx.send(UiMsg::ToolLog(format!("Turn failed: {error}")));
            }
        });
    }

    // ------------------------------------------------------------------
    // MCP lifecycle
    // ------------------------------------------------------------------

    /// (Re)connect every enabled server that is not already connected and
    /// drop the ones that were disabled or removed. Idempotent.
    pub async fn connect_mcp(self: &Arc<Self>, servers: Vec<McpServerConfig>) {
        let mut clients = self.mcp.lock().await;
        let enabled: Vec<McpServerConfig> = servers
            .into_iter()
            .filter(|server| server.is_enabled && !server.id.is_empty())
            .collect();

        // Drop clients whose server vanished or was disabled.
        let mut kept: Vec<Arc<McpClient>> = Vec::new();
        let mut dropped: Vec<Arc<McpClient>> = Vec::new();
        for client in clients.drain(..) {
            if enabled.iter().any(|server| server.id == client.config_id()) {
                kept.push(client);
            } else {
                dropped.push(client);
            }
        }
        for client in dropped {
            client.close().await;
        }

        for server in enabled {
            if kept.iter().any(|client| client.config_id() == server.id) {
                continue;
            }
            let transport = match server.transport_type {
                McpTransportType::STDIO => {
                    messenger_mcp::client::native_stdio_transport(&server)
                }
                McpTransportType::SSE => Some(Arc::new(messenger_mcp::SseTransport::new(
                    server.url.clone(),
                    server.headers.clone(),
                )) as Arc<dyn messenger_mcp::McpTransport>),
            };
            let Some(transport) = transport else {
                self.log(format!("MCP server {} has no usable transport.", server.name));
                continue;
            };
            let client = Arc::new(McpClient::new(server.clone(), transport));
            if client.connect().await {
                self.log(format!(
                    "MCP server {} connected ({} tools).",
                    server.name,
                    client.tools().await.len()
                ));
                kept.push(client);
            } else {
                self.log(format!("MCP server {} failed to connect.", server.name));
            }
        }
        *clients = kept;
        drop(clients);
        self.refresh_mcp_tools().await;
    }

    async fn refresh_mcp_tools(&self) {
        let clients = self.mcp.lock().await.clone();
        let mut tools = Vec::new();
        for client in clients {
            tools.extend(client.tools().await);
        }
        *self.mcp_tools.lock().unwrap() = tools;
    }

    pub async fn disconnect_mcp(&self) {
        let clients: Vec<Arc<McpClient>> = self.mcp.lock().await.drain(..).collect();
        for client in clients {
            client.close().await;
        }
        self.mcp_tools.lock().unwrap().clear();
    }
}

/// Read the persisted MCP server list from the store.
pub fn load_mcp_servers(store: &Store) -> Vec<McpServerConfig> {
    store
        .kv_get(crate::store_ops::MCP_SERVERS_KEY)
        .ok()
        .flatten()
        .map(|json| decode_server_list(&json))
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use messenger_store::Store;

    /// Engine under a real runtime, mirroring how `main` wires it.
    fn engine_for(store: Arc<Store>) -> (Engine, tokio::runtime::Runtime) {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .unwrap();
        let (tx, _rx) = mpsc::unbounded_channel();
        let engine = Engine::new(
            store,
            tx,
            std::path::PathBuf::from("."),
            runtime.handle().clone(),
        );
        (engine, runtime)
    }

    #[test]
    fn engine_exposes_initially_empty_mcp_state() {
        let store = Arc::new(Store::open_memory().unwrap());
        let (engine, _runtime) = engine_for(store);
        assert!(engine.mcp_tools().is_empty());
    }

    #[test]
    fn loading_mcp_servers_from_an_empty_store_yields_nothing() {
        let store = Store::open_memory().unwrap();
        assert!(load_mcp_servers(&store).is_empty());
    }

    #[test]
    fn loading_mcp_servers_decodes_the_persisted_list() {
        let store = Store::open_memory().unwrap();
        store
            .kv_set(
                crate::store_ops::MCP_SERVERS_KEY,
                r#"[{"id":"s1","name":"Local","transportType":"STDIO","isEnabled":true,"command":"npx","args":[]}]"#,
            )
            .unwrap();
        let servers = load_mcp_servers(&store);
        assert_eq!(servers.len(), 1);
        assert_eq!(servers[0].command, "npx");
    }
}
