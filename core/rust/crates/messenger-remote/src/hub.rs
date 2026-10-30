use std::{
    collections::{HashMap, HashSet},
    sync::{Arc, Mutex},
    time::Duration,
};

use async_trait::async_trait;
use messenger_store::{model::StoredProject, Store};
use messenger_sync::{Session, SyncEngine, KV_SESSION, KV_SESSION_HOST};
use messenger_tools::{builtin_registry, ToolExecutionResult};
use serde::{Serialize, Deserialize};
use tokio::sync::oneshot;
use tokio_stream::StreamExt;
use tokio_util::sync::CancellationToken;

use crate::protocol::{
    Presence, SseParser, ToolCall, ToolCancel, ToolResult, EVENT_PRESENCE, EVENT_TOOL_CALL,
    EVENT_TOOL_CANCEL, EVENT_TOOL_RESULT,
};

/// Who owns a project's workspace, relative to this device.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum ProjectOwner {
    /// Owned by this device (or a legacy row with no owner recorded).
    Local,
    /// Owned by another device of the account.
    #[serde(rename_all = "camelCase")]
    Remote { device_id: String, online: bool },
}

impl ProjectOwner {
    /// A project is unavailable exactly while its remote owner is offline.
    pub fn is_available(&self) -> bool {
        !matches!(self, ProjectOwner::Remote { online: false, .. })
    }
}

/// Snapshot served by `GET /remote/status`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteStatus {
    pub device_id: String,
    /// Whether this device currently holds its relay connection.
    pub connected: bool,
    /// Other devices of the account that are connected to the relay.
    pub online_devices: Vec<String>,
}

/// A workspace tool call this device wants executed on the project owner.
#[derive(Debug, Clone)]
pub struct OutgoingToolCall {
    pub target_device_id: String,
    pub call_id: String,
    pub project_id: String,
    pub conversation_id: String,
    pub name: String,
    pub arguments_json: String,
}

/// A validated call from another device, to be run against a local workspace.
#[derive(Debug, Clone, PartialEq)]
pub struct InboundToolCall {
    pub call_id: String,
    pub project_id: String,
    pub conversation_id: String,
    /// The owning project's workspace on this device.
    pub workspace: String,
    pub name: String,
    pub arguments_json: String,
}

/// Runs inbound calls on this device. `cancel` fires when the initiator
/// cancels or goes away; implementations should stop and return promptly.
#[async_trait]
pub trait LocalToolExecutor: Send + Sync {
    async fn execute(&self, call: &InboundToolCall, cancel: CancellationToken) -> ToolExecutionResult;
}

/// Connection pacing; the defaults suit production, tests shrink them.
#[derive(Debug, Clone, Copy)]
pub struct RelayTiming {
    /// First reconnect delay; doubles up to `reconnect_max`.
    pub reconnect_min: Duration,
    pub reconnect_max: Duration,
    /// How often a signed-out device re-checks for a session.
    pub idle_poll: Duration,
    /// Upper bound a relayed tool call may take before it fails.
    pub call_timeout: Duration,
}

impl Default for RelayTiming {
    fn default() -> Self {
        Self {
            reconnect_min: Duration::from_secs(1),
            reconnect_max: Duration::from_secs(30),
            idle_poll: Duration::from_secs(3),
            call_timeout: Duration::from_secs(600),
        }
    }
}

#[derive(Default)]
struct State {
    connected: bool,
    online: HashSet<String>,
    pending: HashMap<String, Pending>,
    inbound: HashMap<String, CancellationToken>,
}

struct Pending {
    target: String,
    sender: oneshot::Sender<ToolExecutionResult>,
}

enum RelayError {
    /// The relay says the target device is not connected.
    Offline,
    Other(String),
}

enum Ended {
    /// Not signed in (or the relay rejected the session).
    SignedOut,
    /// The stream ended or failed; reconnect after backoff. `true` when the
    /// connection had been established (resets the backoff).
    Closed(bool),
    Shutdown,
}

pub struct RemoteHub {
    store: Arc<Store>,
    device_id: String,
    http: reqwest::Client,
    timing: RelayTiming,
    state: Mutex<State>,
}

fn error_result(message: impl Into<String>) -> ToolExecutionResult {
    ToolExecutionResult { output: message.into(), is_error: true }
}

impl RemoteHub {
    pub fn new(store: Arc<Store>) -> Result<Self, String> {
        let device_id = store.device_id().map_err(|e| e.to_string())?;
        Ok(Self {
            store,
            device_id,
            http: reqwest::Client::new(),
            timing: RelayTiming::default(),
            state: Mutex::new(State::default()),
        })
    }

    pub fn with_timing(mut self, timing: RelayTiming) -> Self {
        self.timing = timing;
        self
    }

    /// Pretend the relay connection is up with `devices` online. For tests of
    /// code that depends on presence without standing up a relay stream.
    #[doc(hidden)]
    pub fn force_presence_for_tests(&self, devices: &[&str]) {
        let mut state = self.state.lock().unwrap();
        state.connected = true;
        state.online = devices.iter().map(|d| d.to_string()).collect();
    }

    pub fn device_id(&self) -> &str {
        &self.device_id
    }

    pub fn status(&self) -> RemoteStatus {
        let state = self.state.lock().unwrap();
        let mut online_devices: Vec<String> =
            state.online.iter().filter(|id| **id != self.device_id).cloned().collect();
        online_devices.sort();
        RemoteStatus {
            device_id: self.device_id.clone(),
            connected: state.connected,
            online_devices,
        }
    }

    /// Whether `device_id` is reachable right now. Always `false` while this
    /// device itself is disconnected from the relay.
    pub fn is_online(&self, device_id: &str) -> bool {
        let state = self.state.lock().unwrap();
        state.connected && state.online.contains(device_id)
    }

    pub fn owner_of(&self, project: &StoredProject) -> ProjectOwner {
        match project.device_id.as_deref() {
            None => ProjectOwner::Local,
            Some(id) if id == self.device_id => ProjectOwner::Local,
            Some(id) => ProjectOwner::Remote { device_id: id.to_string(), online: self.is_online(id) },
        }
    }

    /// Run a workspace tool call on the project owner and wait for its result.
    /// Never fails with an `Err`: every failure is an `is_error` result the
    /// model can read, like any other tool error.
    pub async fn call_tool(
        &self,
        call: OutgoingToolCall,
        cancel: &CancellationToken,
    ) -> ToolExecutionResult {
        if !self.is_online(&call.target_device_id) {
            return error_result("the device that owns this project is offline");
        }
        let (sender, receiver) = oneshot::channel();
        self.state.lock().unwrap().pending.insert(
            call.call_id.clone(),
            Pending { target: call.target_device_id.clone(), sender },
        );
        let wire = ToolCall {
            call_id: call.call_id.clone(),
            from_device_id: self.device_id.clone(),
            target_device_id: call.target_device_id.clone(),
            project_id: call.project_id,
            conversation_id: call.conversation_id,
            name: call.name,
            arguments_json: call.arguments_json,
        };
        if let Err(error) = self.post("api/remote/tool-call", &wire).await {
            self.state.lock().unwrap().pending.remove(&call.call_id);
            return error_result(match error {
                RelayError::Offline => "the device that owns this project is offline".to_string(),
                RelayError::Other(detail) => format!("remote tool call failed: {detail}"),
            });
        }
        tokio::select! {
            result = receiver => result.unwrap_or_else(|_| error_result("remote tool call was dropped")),
            _ = cancel.cancelled() => {
                self.abandon(&call.call_id, &call.target_device_id).await;
                error_result("tool execution was cancelled")
            }
            _ = tokio::time::sleep(self.timing.call_timeout) => {
                self.abandon(&call.call_id, &call.target_device_id).await;
                error_result("timed out waiting for the device that owns this project")
            }
        }
    }

    /// Stop waiting for a relayed call and tell the owner to stop running it.
    async fn abandon(&self, call_id: &str, target_device_id: &str) {
        self.state.lock().unwrap().pending.remove(call_id);
        let _ = self
            .post(
                "api/remote/tool-cancel",
                &ToolCancel {
                    call_id: call_id.to_string(),
                    target_device_id: target_device_id.to_string(),
                },
            )
            .await;
    }

    // ------------------------------------------------------------------
    // connection loop
    // ------------------------------------------------------------------

    /// Hold the relay connection until `shutdown` fires: connect while a cloud
    /// session exists, reconnect with backoff after a drop, idle while signed
    /// out. Inbound calls go to `executor`.
    pub async fn run(
        self: Arc<Self>,
        executor: Arc<dyn LocalToolExecutor>,
        shutdown: CancellationToken,
    ) {
        let mut backoff = self.timing.reconnect_min;
        loop {
            let ended = tokio::select! {
                _ = shutdown.cancelled() => Ended::Shutdown,
                ended = self.connect_once(&executor) => ended,
            };
            self.mark_disconnected();
            let wait = match ended {
                Ended::Shutdown => return,
                Ended::SignedOut => self.timing.idle_poll,
                Ended::Closed(established) => {
                    if established {
                        backoff = self.timing.reconnect_min;
                    }
                    let wait = backoff;
                    backoff = (backoff * 2).min(self.timing.reconnect_max);
                    wait
                }
            };
            tokio::select! {
                _ = shutdown.cancelled() => return,
                _ = tokio::time::sleep(wait) => {}
            }
        }
    }

    async fn connect_once(self: &Arc<Self>, executor: &Arc<dyn LocalToolExecutor>) -> Ended {
        let Some(session) = self.session() else { return Ended::SignedOut };
        let url = self.endpoint("api/remote/events");
        let response = self
            .http
            .get(url)
            .query(&[("deviceId", self.device_id.as_str())])
            .header("Cookie", session.cookie.unwrap_or_default())
            .header("Accept", "text/event-stream")
            .send()
            .await;
        let response = match response {
            Ok(response) => response,
            Err(_) => return Ended::Closed(false),
        };
        if response.status().as_u16() == 401 {
            self.drop_session();
            return Ended::SignedOut;
        }
        if !response.status().is_success() {
            return Ended::Closed(false);
        }
        self.state.lock().unwrap().connected = true;
        let mut parser = SseParser::default();
        let mut stream = response.bytes_stream();
        while let Some(chunk) = stream.next().await {
            let Ok(chunk) = chunk else { break };
            for (event, data) in parser.feed(&chunk) {
                self.handle_frame(&event, &data, executor);
            }
        }
        Ended::Closed(true)
    }

    /// Apply one relay frame. Public so embedders and tests can drive the hub
    /// without a live connection.
    pub fn handle_frame(self: &Arc<Self>, event: &str, data: &str, executor: &Arc<dyn LocalToolExecutor>) {
        match event {
            EVENT_PRESENCE => {
                if let Ok(presence) = serde_json::from_str::<Presence>(data) {
                    self.apply_presence(presence);
                }
            }
            EVENT_TOOL_RESULT => {
                if let Ok(result) = serde_json::from_str::<ToolResult>(data) {
                    let pending = self.state.lock().unwrap().pending.remove(&result.call_id);
                    if let Some(pending) = pending {
                        let _ = pending.sender.send(ToolExecutionResult {
                            output: result.output,
                            is_error: result.is_error,
                        });
                    }
                }
            }
            EVENT_TOOL_CANCEL => {
                if let Ok(cancel) = serde_json::from_str::<ToolCancel>(data) {
                    if let Some(token) = self.state.lock().unwrap().inbound.get(&cancel.call_id) {
                        token.cancel();
                    }
                }
            }
            EVENT_TOOL_CALL => {
                if let Ok(call) = serde_json::from_str::<ToolCall>(data) {
                    self.spawn_inbound(call, Arc::clone(executor));
                }
            }
            _ => {}
        }
    }

    fn apply_presence(&self, presence: Presence) {
        let mut state = self.state.lock().unwrap();
        state.online = presence.devices.into_iter().collect();
        let lost: Vec<String> = state
            .pending
            .iter()
            .filter(|(_, p)| !state.online.contains(&p.target))
            .map(|(id, _)| id.clone())
            .collect();
        for id in lost {
            if let Some(pending) = state.pending.remove(&id) {
                let _ = pending.sender.send(error_result("the device that owns this project went offline"));
            }
        }
    }

    fn mark_disconnected(&self) {
        let mut state = self.state.lock().unwrap();
        state.connected = false;
        state.online.clear();
        for (_, pending) in state.pending.drain() {
            let _ = pending.sender.send(error_result("lost the connection to the remote relay"));
        }
        for (_, token) in state.inbound.drain() {
            token.cancel();
        }
    }

    // ------------------------------------------------------------------
    // inbound execution
    // ------------------------------------------------------------------

    fn spawn_inbound(self: &Arc<Self>, call: ToolCall, executor: Arc<dyn LocalToolExecutor>) {
        let hub = Arc::clone(self);
        let token = CancellationToken::new();
        hub.state.lock().unwrap().inbound.insert(call.call_id.clone(), token.clone());
        tokio::spawn(async move {
            let result = match hub.validate_inbound(&call) {
                Ok(inbound) => executor.execute(&inbound, token.clone()).await,
                Err(reason) => error_result(reason),
            };
            hub.state.lock().unwrap().inbound.remove(&call.call_id);
            if token.is_cancelled() {
                return;
            }
            let body = ToolResult {
                call_id: call.call_id.clone(),
                target_device_id: call.from_device_id.clone(),
                output: result.output,
                is_error: result.is_error,
            };
            if let Err(RelayError::Other(detail)) = hub.post("api/remote/tool-result", &body).await {
                eprintln!("messenger-remote: delivering tool result {} failed: {detail}", call.call_id);
            }
        });
    }

    /// An inbound call is only honoured for a workspace tool of a project this
    /// device owns; write tools additionally need the conversation to be
    /// writable here (the conversation must have synced to this device).
    fn validate_inbound(&self, call: &ToolCall) -> Result<InboundToolCall, String> {
        if call.target_device_id != self.device_id {
            return Err("tool call is addressed to another device".into());
        }
        let project = self
            .store
            .get_project(&call.project_id)
            .map_err(|e| e.to_string())?
            .ok_or("project not found on the owning device")?;
        if project.device_id.as_deref() != Some(self.device_id.as_str()) {
            return Err("project is not owned by this device".into());
        }
        let tool = builtin_registry()
            .into_iter()
            .find(|tool| tool.name == call.name && tool.workspace_required)
            .ok_or_else(|| format!("{} is not a workspace tool", call.name))?;
        if tool.write_access {
            let writable = self
                .store
                .get_conversation(&call.conversation_id)
                .map_err(|e| e.to_string())?
                .is_some_and(|c| c.writable && c.project_id.as_deref() == Some(project.id.as_str()));
            if !writable {
                return Err(format!(
                    "{} needs a writable conversation of this project on the owning device",
                    call.name
                ));
            }
        }
        Ok(InboundToolCall {
            call_id: call.call_id.clone(),
            project_id: project.id,
            conversation_id: call.conversation_id.clone(),
            workspace: project.workspace,
            name: call.name.clone(),
            arguments_json: call.arguments_json.clone(),
        })
    }

    // ------------------------------------------------------------------
    // relay HTTP
    // ------------------------------------------------------------------

    fn session(&self) -> Option<Session> {
        let read = |key: &str| self.store.kv_get(key).ok().flatten().filter(|v| !v.is_empty());
        Some(Session { cookie: Some(read(KV_SESSION)?), host: Some(read(KV_SESSION_HOST)?) })
    }

    fn endpoint(&self, path: &str) -> String {
        SyncEngine::new(&self.store, Session::default()).endpoint(path)
    }

    /// The relay answered 401: the cloud session is dead. Same consequence as
    /// any other rejected authenticated call — forget it locally.
    fn drop_session(&self) {
        let _ = SyncEngine::new(&self.store, Session::default()).clear_session();
    }

    async fn post<T: Serialize>(&self, path: &str, body: &T) -> Result<(), RelayError> {
        let session = self.session().ok_or_else(|| RelayError::Other("not signed in".into()))?;
        let response = self
            .http
            .post(self.endpoint(path))
            .header("Cookie", session.cookie.unwrap_or_default())
            .timeout(Duration::from_secs(30))
            .json(body)
            .send()
            .await
            .map_err(|e| RelayError::Other(e.to_string()))?;
        let status = response.status();
        if status.is_success() {
            return Ok(());
        }
        match status.as_u16() {
            401 => {
                self.drop_session();
                Err(RelayError::Other("cloud session expired".into()))
            }
            404 | 409 | 410 => Err(RelayError::Offline),
            code => Err(RelayError::Other(format!("relay answered HTTP {code}"))),
        }
    }
}
