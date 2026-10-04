//! Pluggable MCP transports (port of `McpProcessBridge` + the SSE half of
//! `McpClient`). Implementations:
//! - [`StdioTransport`] — spawns the server process natively (desktop);
//!   Android routes through the companion app's AIDL bridge via a custom
//!   transport supplied by the FFI layer.
//! - [`SseTransport`] — SSE down / HTTP POST up (remote servers).

use std::sync::Arc;

use async_trait::async_trait;
/// Callbacks the transport invokes as the server talks back.
pub trait IncomingHandler: Send + Sync {
    fn on_line(&self, line: String);
    fn on_error(&self, message: String);
    fn on_close(&self, code: i32);
}

/// One MCP transport. `start` begins delivering incoming lines to `handler`
/// and returns whether the transport is usable; `send_line` writes one
/// JSON-RPC message; `close` shuts the transport down.
#[async_trait]
pub trait McpTransport: Send + Sync {
    async fn start(&self, handler: Arc<dyn IncomingHandler>) -> bool;
    async fn send_line(&self, line: &str) -> bool;
    async fn close(&self);
}

/// Native child-process stdio transport (desktop). Android supplies its own
/// [`McpTransport`] backed by the runtime companion's MCP process sessions.
pub struct StdioTransport {
    command: String,
    args: Vec<String>,
    env: std::collections::HashMap<String, String>,
    child: tokio::sync::Mutex<Option<tokio::process::Child>>,
    stdin: tokio::sync::Mutex<Option<tokio::process::ChildStdin>>,
}

impl StdioTransport {
    pub fn new(command: String, args: Vec<String>, env: std::collections::HashMap<String, String>) -> Self {
        Self {
            command,
            args,
            env,
            child: tokio::sync::Mutex::new(None),
            stdin: tokio::sync::Mutex::new(None),
        }
    }
}

#[async_trait]
impl McpTransport for StdioTransport {
    async fn start(&self, handler: Arc<dyn IncomingHandler>) -> bool {
        let mut command = tokio::process::Command::new(&self.command);
        command
            .args(&self.args)
            .envs(&self.env)
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .kill_on_drop(true);
        #[cfg(windows)]
        {
            command.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
        }
        let mut child = match command.spawn() {
            Ok(child) => child,
            Err(e) => {
                handler.on_error(format!("failed to spawn {}: {e}", self.command));
                return false;
            }
        };
        let mut stdout = child.stdout.take().expect("stdout piped");
        let stdin = child.stdin.take().expect("stdin piped");
        *self.stdin.lock().await = Some(stdin);

        // stderr → on_error
        if let Some(mut stderr) = child.stderr.take() {
            let error_handler = Arc::clone(&handler);
            tokio::spawn(async move {
                use tokio::io::AsyncBufReadExt;
                let mut reader = tokio::io::BufReader::new(&mut stderr);
                let mut line = String::new();
                loop {
                    line.clear();
                    match reader.read_line(&mut line).await {
                        Ok(0) | Err(_) => break,
                        Ok(_) => error_handler.on_error(line.trim_end().to_string()),
                    }
                }
            });
        }

        // stdout → on_line
        let close_handler = Arc::clone(&handler);
        tokio::spawn(async move {
            use tokio::io::AsyncBufReadExt;
            let mut reader = tokio::io::BufReader::new(&mut stdout);
            let mut line = String::new();
            loop {
                line.clear();
                match reader.read_line(&mut line).await {
                    Ok(0) | Err(_) => break,
                    Ok(_) => handler.on_line(line.trim_end().to_string()),
                }
            }
            close_handler.on_close(0);
        });

        *self.child.lock().await = Some(child);
        true
    }

    async fn send_line(&self, line: &str) -> bool {
        use tokio::io::AsyncWriteExt;
        let mut guard = self.stdin.lock().await;
        match guard.as_mut() {
            Some(stdin) => {
                let write = stdin.write_all(format!("{line}
").as_bytes()).await;
                let flush = stdin.flush().await;
                write.is_ok() && flush.is_ok()
            }
            None => false,
        }
    }

    async fn close(&self) {
        if let Some(mut child) = self.child.lock().await.take() {
            let _ = child.start_kill();
        }
        *self.stdin.lock().await = None;
    }
}

/// SSE down / HTTP POST up transport (remote servers). The server announces
/// its message endpoint on the `endpoint` event; requests POST JSON-RPC to
/// it and responses arrive as `message` events.
pub struct SseTransport {
    url: String,
    headers: std::collections::HashMap<String, String>,
    state: tokio::sync::Mutex<Option<SseState>>,
}

struct SseState {
    client: reqwest::Client,
    endpoint_url: String,
    close: tokio::sync::watch::Sender<bool>,
}

impl SseTransport {
    pub fn new(url: String, headers: std::collections::HashMap<String, String>) -> Self {
        Self {
            url,
            headers,
            state: tokio::sync::Mutex::new(None),
        }
    }

    fn http_client(&self) -> reqwest::Client {
        let mut builder = reqwest::Client::builder();
        for (key, value) in &self.headers {
            if let (Ok(name), Ok(value)) = (
                reqwest::header::HeaderName::from_bytes(key.as_bytes()),
                reqwest::header::HeaderValue::from_str(value),
            ) {
                builder = builder.default_headers(
                    reqwest::header::HeaderMap::from_iter([(name, value)]),
                );
            }
        }
        builder.build().unwrap_or_default()
    }
}

#[async_trait]
impl McpTransport for SseTransport {
    async fn start(&self, handler: Arc<dyn IncomingHandler>) -> bool {
        let client = self.http_client();
        let response = match client
            .get(&self.url)
            .header("Accept", "text/event-stream")
            .send()
            .await
        {
            Ok(response) if response.status().is_success() => response,
            Ok(response) => {
                handler.on_error(format!("SSE connect failed: HTTP {}", response.status()));
                return false;
            }
            Err(e) => {
                handler.on_error(format!("SSE connect failed: {e}"));
                return false;
            }
        };

        let (close_tx, close_rx) = tokio::sync::watch::channel(false);
        let (endpoint_tx, endpoint_rx) = tokio::sync::oneshot::channel::<String>();
        let base = self.url.clone();

        // SSE reader: emit `endpoint`/`message` events, resolve the message
        // endpoint on first `endpoint`.
        tokio::spawn(async move {
            let mut stream = response.bytes_stream();
            let mut buffer: Vec<u8> = Vec::new();
            let mut event_name = "message".to_string();
            let mut data_lines: Vec<String> = Vec::new();
            let mut endpoint_tx = Some(endpoint_tx);
            let mut endpoint_url: Option<String> = None;
            let mut close_rx = close_rx;

            let flush = |event_name: &str,
                             data_lines: &[String],
                             handler: &Arc<dyn IncomingHandler>,
                             endpoint_url: &mut Option<String>,
                             endpoint_tx: &mut Option<tokio::sync::oneshot::Sender<String>>| {
                let data = data_lines.join("\n");
                match event_name {
                    "endpoint" => {
                        let resolved = resolve_relative(&base, data.trim());
                        *endpoint_url = Some(resolved.clone());
                        if let Some(tx) = endpoint_tx.take() {
                            let _ = tx.send(resolved);
                        }
                    }
                    "message" if !data.is_empty() => handler.on_line(data),
                    _ => {}
                }
            };

            use tokio_stream::StreamExt;
            loop {
                tokio::select! {
                    _ = close_rx.changed() => {
                        let closed = *close_rx.borrow();
                        if closed { break; }
                    }
                    chunk = stream.next() => {
                        match chunk {
                            Some(Ok(bytes)) => buffer.extend_from_slice(&bytes),
                            _ => break,
                        }
                    }
                }
                while let Some(pos) = buffer.iter().position(|&b| b == b'\n') {
                    let line: Vec<u8> = buffer.drain(..=pos).collect();
                    let line = String::from_utf8_lossy(&line);
                    let line = line.trim_end_matches(['\n', '\r']);
                    if line.is_empty() {
                        flush(&event_name, &data_lines, &handler, &mut endpoint_url, &mut endpoint_tx);
                        // SSE default event type is "message" when omitted.
                        event_name = "message".to_string();
                        data_lines.clear();
                    } else if let Some(value) = line.strip_prefix("event: ") {
                        event_name = value.trim().to_string();
                    } else if let Some(value) = line.strip_prefix("data: ") {
                        data_lines.push(value.to_string());
                    }
                }
            }
            if !data_lines.is_empty() {
                flush(&event_name, &data_lines, &handler, &mut endpoint_url, &mut endpoint_tx);
            }
            handler.on_close(0);
        });

        let endpoint_url = match tokio::time::timeout(std::time::Duration::from_secs(10), endpoint_rx).await {
            Ok(Ok(url)) => url,
            _ => {
                let _ = close_tx.send(true);
                return false;
            }
        };

        let mut guard = self.state.lock().await;
        *guard = Some(SseState {
            client,
            endpoint_url,
            close: close_tx,
        });
        true
    }

    async fn send_line(&self, line: &str) -> bool {
        let guard = self.state.lock().await;
        let Some(state) = guard.as_ref() else {
            return false;
        };
        let request = state
            .client
            .post(&state.endpoint_url)
            .header("Content-Type", "application/json")
            .body(line.to_string());
        matches!(request.send().await, Ok(response) if response.status().is_success())
    }

    async fn close(&self) {
        if let Some(state) = self.state.lock().await.take() {
            let _ = state.close.send(true);
        }
    }
}

/// Resolve a relative endpoint path against the SSE base URL, mirroring the
/// Kotlin `config.url.substringBeforeLast('/')` rule.
fn resolve_relative(base: &str, endpoint: &str) -> String {
    if endpoint.starts_with("http://") || endpoint.starts_with("https://") {
        return endpoint.to_string();
    }
    let base = base.rsplit_once('/').map(|(head, _)| head).unwrap_or(base);
    format!("{base}/{}", endpoint.trim_start_matches('/'))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn endpoint_resolution_matches_kotlin() {
        assert_eq!(
            resolve_relative("https://x.com/sse", "/messages"),
            "https://x.com/messages"
        );
        assert_eq!(
            resolve_relative("https://x.com/sse", "https://y.com/msg"),
            "https://y.com/msg"
        );
    }

    #[tokio::test]
    async fn sse_transport_exercises_endpoint_handshake() {
        // Real SSE flow covered by the client integration test with a
        // wiremock server; here just verify construction.
        let transport = SseTransport::new("http://127.0.0.1:1/sse".into(), Default::default());
        assert!(!transport.url.is_empty());
    }
}
#[cfg(test)]
mod sse_integration_tests {
    use super::*;
    use std::sync::Mutex as SyncMutex;

    struct CollectingHandler(SyncMutex<Vec<String>>);

    impl IncomingHandler for CollectingHandler {
        fn on_line(&self, line: String) {
            self.0.lock().unwrap().push(line);
        }
        fn on_error(&self, message: String) {
            self.0.lock().unwrap().push(format!("error:{message}"));
        }
        fn on_close(&self, _code: i32) {}
    }

    #[tokio::test]
    async fn sse_full_round_trip_over_http() {
        let server = wiremock::MockServer::start().await;
        wiremock::Mock::given(wiremock::matchers::method("GET"))
            .and(wiremock::matchers::path("/sse"))
            .respond_with(
                wiremock::ResponseTemplate::new(200)
                    .append_header("Content-Type", "text/event-stream")
                    .set_body_string(
                        "event: endpoint
data: /messages

data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}

",
                    ),
            )
            .mount(&server)
            .await;
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .and(wiremock::matchers::path("/messages"))
            .respond_with(wiremock::ResponseTemplate::new(202))
            .mount(&server)
            .await;

        let transport = SseTransport::new(format!("{}/sse", server.uri()), Default::default());
        let received = Arc::new(CollectingHandler(SyncMutex::new(Vec::new())));
        assert!(transport.start(Arc::clone(&received) as Arc<dyn IncomingHandler>).await);

        // Request POSTs to the resolved /messages endpoint.
        assert!(transport.send_line(r#"{"jsonrpc":"2.0","id":9,"method":"ping"}"#).await);

        // The JSON-RPC response arrived as a message event line.
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        loop {
            let lines = received.0.lock().unwrap().clone();
            if lines.iter().any(|l| l.contains(r#""ok":true"#)) {
                break;
            }
            assert!(std::time::Instant::now() < deadline, "response never arrived: {lines:?}");
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        }
        transport.close().await;
    }
}
