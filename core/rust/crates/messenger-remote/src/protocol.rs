//! Wire types for the cloud relay (`/api/remote/*`). The relay is an
//! account-scoped switchboard: every device of one account holds an SSE
//! connection, the relay publishes who is connected, and it forwards tool
//! calls/results/cancels between devices by `targetDeviceId`. It never
//! interprets a payload.

use serde::{Deserialize, Serialize};

/// SSE event names on `GET api/remote/events`.
pub const EVENT_PRESENCE: &str = "presence";
pub const EVENT_TOOL_CALL: &str = "toolCall";
pub const EVENT_TOOL_RESULT: &str = "toolResult";
pub const EVENT_TOOL_CANCEL: &str = "toolCancel";

/// Full set of devices of this account currently connected to the relay
/// (sent on connect and whenever the set changes; may include the receiver).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Presence {
    pub devices: Vec<String>,
}

/// A workspace tool call, relayed from the device running the chat turn to
/// the device that owns the project.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolCall {
    pub call_id: String,
    pub from_device_id: String,
    pub target_device_id: String,
    pub project_id: String,
    pub conversation_id: String,
    pub name: String,
    pub arguments_json: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolResult {
    pub call_id: String,
    /// The device that issued the call (where the result is delivered).
    pub target_device_id: String,
    pub output: String,
    #[serde(default)]
    pub is_error: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolCancel {
    pub call_id: String,
    /// The device that must stop the call (the project owner).
    pub target_device_id: String,
}

/// Incremental `text/event-stream` parser: feed raw chunks, get completed
/// `(event, data)` frames. Handles `\n` / `\r\n`, multi-line `data:`, comment
/// lines and frames split across chunk boundaries.
#[derive(Debug, Default)]
pub struct SseParser {
    buffer: String,
    event: Option<String>,
    data: Vec<String>,
}

impl SseParser {
    pub fn feed(&mut self, chunk: &[u8]) -> Vec<(String, String)> {
        self.buffer.push_str(&String::from_utf8_lossy(chunk));
        let mut frames = Vec::new();
        while let Some(newline) = self.buffer.find('\n') {
            let mut line: String = self.buffer.drain(..=newline).collect();
            line.pop();
            if line.ends_with('\r') {
                line.pop();
            }
            if line.is_empty() {
                if !self.data.is_empty() {
                    frames.push((
                        self.event.take().unwrap_or_else(|| "message".into()),
                        self.data.join("\n"),
                    ));
                }
                self.event = None;
                self.data.clear();
            } else if line.starts_with(':') {
                // comment / keep-alive
            } else if let Some(value) = line.strip_prefix("event:") {
                self.event = Some(value.trim_start().to_string());
            } else if let Some(value) = line.strip_prefix("data:") {
                self.data.push(value.strip_prefix(' ').unwrap_or(value).to_string());
            }
        }
        frames
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_frames_split_across_chunks_and_crlf() {
        let mut parser = SseParser::default();
        assert!(parser.feed(b": keep-alive\n\nevent: pres").is_empty());
        let frames = parser.feed(b"ence\r\ndata: {\"devices\":\r\ndata: []}\r\n\r\nevent: x\ndata: 1\n\n");
        assert_eq!(
            frames,
            vec![
                ("presence".to_string(), "{\"devices\":\n[]}".to_string()),
                ("x".to_string(), "1".to_string())
            ]
        );
    }

    #[test]
    fn a_frame_without_data_is_dropped_and_default_event_is_message() {
        let mut parser = SseParser::default();
        assert!(parser.feed(b"event: empty\n\n").is_empty());
        assert_eq!(parser.feed(b"data: hi\n\n"), vec![("message".to_string(), "hi".to_string())]);
    }
}
