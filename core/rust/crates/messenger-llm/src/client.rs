//! OpenAI-compatible HTTP client (port of `OpenAiClient.kt` +
//! `NetworkClient.kt` + `SSEParser.kt`).
//!
//! Transport errors surface as [`ApiError::Http`] with the provider's
//! `error.message` extracted, or [`ApiError::Network`]. Streaming disables
//! the overall request timeout (reasoning models can think for minutes
//! before the first token), matching the Kotlin `requestTimeoutMillis =
//! Long.MAX_VALUE`.

use reqwest::header::AUTHORIZATION;
use reqwest::{Client, Method, StatusCode};
use serde_json::Value;

use crate::dto::{ChatCompletionResponse, ModelsResponse};
use crate::request::ChatCompletionRequest;
use crate::request::extract_http_error_message;

/// Errors from the LLM transport. `Http` carries the provider-extracted
/// message so the UI can surface it verbatim (engineering convention: API
/// errors are never silently retried or swallowed).
#[derive(Debug, thiserror::Error)]
pub enum ApiError {
    #[error("HTTP {status}: {message}")]
    Http { status: u16, message: String },
    #[error("network error: {0}")]
    Network(String),
    #[error("failed to decode response: {0}")]
    InvalidBody(String),
}

/// OpenAI-compatible client bound to one (base URL, API key) pair.
pub struct OpenAiClient {
    http: Client,
    root: String,
    api_key: String,
}

impl OpenAiClient {
    pub fn new(base_url: &str, api_key: &str) -> Self {
        Self {
            http: Client::new(),
            root: base_url.trim_end_matches('/').to_string(),
            api_key: api_key.to_string(),
        }
    }

    pub async fn get_models(&self) -> Result<ModelsResponse, ApiError> {
        let response = self.request(Method::GET, "/models", None).await?;
        response
            .json::<ModelsResponse>()
            .await
            .map_err(|e| ApiError::InvalidBody(e.to_string()))
    }

    pub async fn create_chat_completion(
        &self,
        request: &ChatCompletionRequest,
    ) -> Result<ChatCompletionResponse, ApiError> {
        let body = serde_json::to_value(request)
            .map_err(|e| ApiError::InvalidBody(e.to_string()))?;
        let response = self
            .request(Method::POST, "/chat/completions", Some(body))
            .await?;
        response
            .json::<ChatCompletionResponse>()
            .await
            .map_err(|e| ApiError::InvalidBody(e.to_string()))
    }

    /// Stream `chat/completions`; returns the raw SSE payload stream.
    pub async fn stream_chat_completion(
        &self,
        request: &ChatCompletionRequest,
    ) -> Result<ChatStream, ApiError> {
        let body = serde_json::to_value(request)
            .map_err(|e| ApiError::InvalidBody(e.to_string()))?;
        let response = self
            .request(Method::POST, "/chat/completions", Some(body))
            .await?;
        Ok(ChatStream::new(response))
    }

    async fn request(
        &self,
        method: Method,
        path: &str,
        json_body: Option<Value>,
    ) -> Result<reqwest::Response, ApiError> {
        let mut builder = self.http.request(method, format!("{}{}", self.root, path));
        if !self.api_key.is_empty() {
            builder = builder.header(AUTHORIZATION, format!("Bearer {}", self.api_key));
        }
        if json_body.is_some() {
            builder = builder.json(&json_body);
        }
        let response = builder
            .send()
            .await
            .map_err(|e| ApiError::Network(e.to_string()))?;
        let status = response.status();
        if status != StatusCode::OK {
            let raw_body = response.text().await.unwrap_or_default();
            let message = if raw_body.is_empty() {
                format!("HTTP {status}")
            } else {
                extract_http_error_message(&raw_body)
            };
            return Err(ApiError::Http {
                status: status.as_u16(),
                message,
            });
        }
        Ok(response)
    }
}

/// Incremental SSE line splitter: feed raw bytes (possibly split mid-UTF-8
/// character or mid-line), get complete `data:` payloads. The terminal
/// `[DONE]` sentinel passes through so the caller can distinguish a clean
/// end-of-stream from an error (engineering convention: a stream that ends
/// without Done must surface an error).
#[derive(Debug, Default)]
pub struct SseLineBuffer {
    bytes: Vec<u8>,
}

impl SseLineBuffer {
    pub fn feed(&mut self, bytes: &[u8]) -> Vec<String> {
        self.bytes.extend_from_slice(bytes);
        let mut payloads = Vec::new();
        while let Some(pos) = self.bytes.iter().position(|&b| b == b'\n') {
            let line: Vec<u8> = self.bytes.drain(..=pos).collect();
            if let Some(payload) = data_payload_of_line(&line) {
                payloads.push(payload);
            }
        }
        payloads
    }

    /// Flush a trailing line that never saw a newline before EOF.
    pub fn finish(&mut self) -> Vec<String> {
        if self.bytes.is_empty() {
            return Vec::new();
        }
        let line = std::mem::take(&mut self.bytes);
        data_payload_of_line(&line).into_iter().collect()
    }
}

fn data_payload_of_line(line: &[u8]) -> Option<String> {
    let text = String::from_utf8_lossy(line);
    let text = text.trim_end_matches(['\n', '\r']);
    text.strip_prefix("data: ").map(str::to_string)
}

/// Streaming response handle: yields each SSE `data:` payload in order,
/// terminating after the `[DONE]` sentinel or stream end.
#[derive(Debug)]
pub struct ChatStream {
    response: reqwest::Response,
    buffer: SseLineBuffer,
    pending: std::collections::VecDeque<String>,
    done: bool,
}

impl ChatStream {
    fn new(response: reqwest::Response) -> Self {
        Self {
            response,
            buffer: SseLineBuffer::default(),
            pending: std::collections::VecDeque::new(),
            done: false,
        }
    }

    /// Next payload: `Some("[DONE]")` on the sentinel, then `None` forever.
    pub async fn next_payload(&mut self) -> Result<Option<String>, ApiError> {
        if let Some(payload) = self.pending.pop_front() {
            if payload == "[DONE]" {
                self.done = true;
            }
            return Ok(Some(payload));
        }
        if self.done {
            return Ok(None);
        }
        loop {
            match self.response.chunk().await {
                Ok(Some(bytes)) => {
                    let mut payloads = self.buffer.feed(&bytes).into_iter();
                    if let Some(first) = payloads.next() {
                        if first == "[DONE]" {
                            self.done = true;
                            return Ok(Some(first));
                        }
                        self.pending.extend(payloads);
                        return Ok(Some(first));
                    }
                }
                Ok(None) => {
                    let mut trailing = self.buffer.finish().into_iter();
                    if let Some(first) = trailing.next() {
                        if first == "[DONE]" {
                            self.done = true;
                        }
                        self.pending.extend(trailing);
                        return Ok(Some(first));
                    }
                    return Ok(None);
                }
                Err(e) => return Err(ApiError::Network(e.to_string())),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn line_buffer_handles_multibyte_split_across_feeds() {
        let mut buffer = SseLineBuffer::default();
        // "data: 你好\n" split mid-character (你好 = 6 bytes).
        let full = format!("data: 你好\n\ndata: [DONE]\n\n").into_bytes();
        let (a, b) = full.split_at(9); // splits inside 你 (3 bytes: e4 bd a0)
        let mut payloads = buffer.feed(a);
        payloads.extend(buffer.feed(b));
        payloads.extend(buffer.finish());
        assert_eq!(payloads, vec!["你好".to_string(), "[DONE]".to_string()]);
    }

    #[test]
    fn line_buffer_ignores_non_data_lines_and_flushes_trailing() {
        let mut buffer = SseLineBuffer::default();
        let mut payloads = buffer.feed(b": keep-alive\n\ndata: {\"x\":1}\r\n");
        assert_eq!(payloads, vec![r#"{"x":1}"#.to_string()]);
        payloads = buffer.feed(b"data: no-newline-at-eof");
        assert!(payloads.is_empty());
        payloads = buffer.finish();
        assert_eq!(payloads, vec!["no-newline-at-eof".to_string()]);
    }

    #[tokio::test]
    async fn stream_yields_payloads_then_terminates_after_done() {
        let server = wiremock::MockServer::start().await;
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .and(wiremock::matchers::path("/v1/chat/completions"))
            .respond_with(
                wiremock::ResponseTemplate::new(200).set_body_string(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n",
                ),
            )
            .mount(&server)
            .await;

        let client = OpenAiClient::new(&format!("{}/v1", server.uri()), "sk-test");
        let mut stream = client
            .stream_chat_completion(&sample_request())
            .await
            .unwrap();
        let first = stream.next_payload().await.unwrap().unwrap();
        assert_eq!(first, r#"{"choices":[{"delta":{"content":"hi"}}]}"#);
        assert_eq!(stream.next_payload().await.unwrap().unwrap(), "[DONE]");
        assert_eq!(stream.next_payload().await.unwrap(), None);
        assert_eq!(stream.next_payload().await.unwrap(), None);
    }

    #[tokio::test]
    async fn http_error_carries_provider_message() {
        let server = wiremock::MockServer::start().await;
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .and(wiremock::matchers::path("/v1/chat/completions"))
            .respond_with(
                wiremock::ResponseTemplate::new(402).set_body_string(
                    r#"{"error":{"message":"insufficient quota"}}"#,
                ),
            )
            .mount(&server)
            .await;

        let client = OpenAiClient::new(&format!("{}/v1", server.uri()), "sk-test");
        let err = client
            .stream_chat_completion(&sample_request())
            .await
            .unwrap_err();
        match err {
            ApiError::Http { status, message } => {
                assert_eq!(status, 402);
                assert_eq!(message, "insufficient quota");
            }
            other => panic!("expected Http error, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn models_and_non_streaming_happy_paths() {
        let server = wiremock::MockServer::start().await;
        wiremock::Mock::given(wiremock::matchers::method("GET"))
            .and(wiremock::matchers::path("/v1/models"))
            .respond_with(wiremock::ResponseTemplate::new(200).set_body_json(
                serde_json::json!({"data": [{"id": "m1", "context_window": 128000}]}),
            ))
            .mount(&server)
            .await;
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .and(wiremock::matchers::path("/v1/chat/completions"))
            .respond_with(wiremock::ResponseTemplate::new(200).set_body_json(
                serde_json::json!({
                    "id": "cmpl-1",
                    "choices": [{
                        "index": 0,
                        "message": {"role": "assistant", "content": "answer", "reasoning_content": "because"},
                        "finish_reason": "stop"
                    }],
                    "usage": {"prompt_tokens": 3, "completion_tokens": 5, "total_tokens": 8}
                }),
            ))
            .mount(&server)
            .await;

        let client = OpenAiClient::new(&format!("{}/v1", server.uri()), "sk-test");
        let models = client.get_models().await.unwrap();
        assert_eq!(models.data[0].id, "m1");
        assert_eq!(models.data[0].context_window, Some(128000));

        let mut request = sample_request();
        request.stream = false;
        let response = client.create_chat_completion(&request).await.unwrap();
        assert_eq!(response.choices[0].message.reasoning_content.as_deref(), Some("because"));
        assert_eq!(response.usage.unwrap().total_tokens, 8);
    }

    fn sample_request() -> ChatCompletionRequest {
        ChatCompletionRequest {
            model: "test-model".into(),
            messages: vec![crate::request::WireMessage::text("user", "hi")],
            temperature: None,
            top_p: None,
            max_tokens: None,
            reasoning_effort: None,
            thinking: None,
            tools: None,
            stream: true,
        }
    }
}
