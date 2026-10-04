//! Repository-level chat API (port of the public half of
//! `ApiRepositoryImpl`): request assembly + the event stream contract the
//! agent loop consumes, including the mandatory `hasFinished` guard — a
//! stream that ends without Done must surface an Error event (engineering
//! convention 2).

use crate::client::{ApiError, ChatStream, OpenAiClient};
use crate::domain::{ContentPart, Message, MessageRole};
use crate::dto::ChatCompletionResponse;
use crate::events::ChatStreamEvent;
use crate::parser::ChatStreamParser;
use crate::request::{
    ChatCompletionRequest, WireMessage, build_reasoning_params, build_request_messages,
    build_tool_spec, extract_response_content,
};
/// Declaration of one tool the model may call this turn.
#[derive(Debug, Clone)]
pub struct ToolDeclaration {
    pub name: String,
    pub description: String,
    /// JSON Schema string for the arguments.
    pub parameters_json: String,
}

/// Sampling/request parameters for one turn.
#[derive(Debug, Clone, Default)]
pub struct ChatTurnParams {
    pub temperature: Option<f64>,
    pub top_p: Option<f64>,
    pub max_tokens: Option<i64>,
    pub reasoning_effort: Option<String>,
    /// `None` = legacy default; `think_tag` / `reasoning_content` /
    /// `reasoning_summary` change how assistant history is re-sent.
    pub reasoning_format: Option<String>,
    pub system_prompt: Option<String>,
    pub tools: Option<Vec<ToolDeclaration>>,
}

/// Sentinel the event stream emits when the transport ends without any
/// Done/Error event. The FFI facade maps it to a localized string.
pub const ERROR_STREAM_NO_DATA: &str = "error_stream_no_data";

/// Assemble the request for a chat turn (shared by stream and non-stream).
fn build_request(
    model_id: &str,
    messages: &[Message],
    params: &ChatTurnParams,
    stream: bool,
) -> ChatCompletionRequest {
    let request_messages: Vec<WireMessage> = build_request_messages(
        messages,
        params.system_prompt.as_deref(),
        params.reasoning_format.as_deref(),
    );
    let (effort, thinking) = build_reasoning_params(params.reasoning_effort.as_deref());
    let tools = params.tools.as_ref().map(|tools| {
        tools
            .iter()
            .map(|t| build_tool_spec(&t.name, &t.description, &t.parameters_json))
            .collect()
    });
    ChatCompletionRequest {
        model: model_id.to_string(),
        messages: request_messages,
        temperature: params.temperature,
        top_p: params.top_p,
        max_tokens: params.max_tokens,
        reasoning_effort: effort,
        thinking,
        tools,
        stream,
    }
}

/// Streaming chat turn: returns the event stream whose errors all arrive as
/// [`ChatStreamEvent::Error`] (never a transport-level failure), exactly
/// matching the Kotlin `Flow<ChatStreamEvent>` contract.
pub async fn stream_chat_completion(
    client: &OpenAiClient,
    model_id: &str,
    messages: &[Message],
    params: &ChatTurnParams,
) -> ChatEventStream {
    let request = build_request(model_id, messages, params, true);
    match client.stream_chat_completion(&request).await {
        Ok(stream) => ChatEventStream::live(stream),
        Err(e) => ChatEventStream::failed(api_error_message(e)),
    }
}

/// Non-streaming chat turn: returns the final assistant message with
/// reasoning wrapped into a leading think block and multipart replies
/// coerced to flat text (port of `createChatCompletion`).
pub async fn create_chat_completion(
    client: &OpenAiClient,
    model_id: &str,
    messages: &[Message],
    params: &ChatTurnParams,
) -> Result<Message, String> {
    let request = build_request(model_id, messages, params, false);
    let response = client
        .create_chat_completion(&request)
        .await
        .map_err(api_error_message)?;
    Ok(response_to_assistant_message(response))
}

/// Port of the non-streaming response coercion: first choice required,
/// `reasoning_content` preferred over `reasoning`, wrapped as
/// `<think>…</think>\n<body>`; multipart content flattens to text/markdown.
pub fn response_to_assistant_message(response: ChatCompletionResponse) -> Message {
    let choice = response.choices.into_iter().next().unwrap_or_default();
    let reasoning = choice
        .message
        .reasoning_content
        .filter(|s| !s.is_empty())
        .or_else(|| choice.message.reasoning.filter(|s| !s.is_empty()));
    let content_text = choice
        .message
        .content
        .as_ref()
        .map(extract_response_content)
        .unwrap_or_default();
    let final_content = match reasoning {
        Some(reasoning) => format!("<think>{reasoning}</think>\n{content_text}"),
        None => content_text,
    };
    Message {
        id: String::new(),
        role: MessageRole::Assistant,
        content: final_content.clone(),
        parts: vec![ContentPart::Text { text: final_content }],
    }
}

/// "No choices in response" guard, exposed for callers that need the raw
/// response (e.g. usage bookkeeping) before coercion.
pub fn response_needs_choice(response: &ChatCompletionResponse) -> bool {
    response.choices.is_empty()
}

fn api_error_message(e: ApiError) -> String {
    match e {
        // Network/transport failures keep their technical message; the UI
        // shows them verbatim like the Kotlin path did.
        ApiError::Network(m) => format!("network error: {m}"),
        ApiError::InvalidBody(m) => format!("failed to decode response: {m}"),
        ApiError::Http { message, .. } => message,
    }
}

/// Event stream over one streaming turn. Errors are events, never `Err`s;
/// the stream yields `None` after the terminal event. A transport that ends
/// without Done/Error emits the [`ERROR_STREAM_NO_DATA`] sentinel error.
pub struct ChatEventStream {
    inner: Option<ChatStream>,
    parser: ChatStreamParser,
    pending: std::collections::VecDeque<ChatStreamEvent>,
    has_finished: bool,
    startup_error: Option<String>,
}

impl ChatEventStream {
    fn live(stream: ChatStream) -> Self {
        Self {
            inner: Some(stream),
            parser: ChatStreamParser::new(),
            pending: std::collections::VecDeque::new(),
            has_finished: false,
            startup_error: None,
        }
    }

    fn failed(message: String) -> Self {
        Self {
            inner: None,
            parser: ChatStreamParser::new(),
            pending: std::collections::VecDeque::new(),
            has_finished: true,
            startup_error: Some(message),
        }
    }

    /// Next event; `None` means the turn's stream is over.
    pub async fn next_event(&mut self) -> Option<ChatStreamEvent> {
        if let Some(message) = self.startup_error.take() {
            return Some(ChatStreamEvent::Error(message));
        }
        loop {
            if let Some(event) = self.pending.pop_front() {
                if matches!(event, ChatStreamEvent::Done { .. } | ChatStreamEvent::Error(_)) {
                    self.has_finished = true;
                }
                return Some(event);
            }
            let Some(stream) = self.inner.as_mut() else {
                return None;
            };
            match stream.next_payload().await {
                Ok(Some(payload)) => {
                    self.pending.extend(self.parser.feed(&payload));
                }
                Ok(None) => {
                    self.inner = None;
                    if !self.has_finished {
                        // Stream ended without Done or Error — emit the
                        // proactive error (engineering convention 2).
                        self.has_finished = true;
                        return Some(ChatStreamEvent::Error(ERROR_STREAM_NO_DATA.to_string()));
                    }
                    return None;
                }
                Err(e) => {
                    self.inner = None;
                    if !self.has_finished {
                        self.has_finished = true;
                        return Some(ChatStreamEvent::Error(api_error_message(e)));
                    }
                    return None;
                }
            }
        }
    }
}

/// Strip think blocks from a title/content candidate — re-exported here so
/// the agent core has one import surface for the request-side utils.
pub use crate::think::strip_think_block as strip_think;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn response_coercion_wraps_reasoning_into_think_block() {
        let response: ChatCompletionResponse = serde_json::from_value(serde_json::json!({
            "choices": [{
                "message": {"role": "assistant", "content": "answer", "reasoning_content": "because"},
                "finish_reason": "stop"
            }]
        }))
        .unwrap();
        let message = response_to_assistant_message(response);
        assert_eq!(message.content, "<think>because</think>\nanswer");
    }

    #[test]
    fn response_coercion_maps_multipart_content() {
        let response: ChatCompletionResponse = serde_json::from_value(serde_json::json!({
            "choices": [{
                "message": {"content": [
                    {"type": "text", "text": "hi"},
                    {"type": "image_url", "image_url": {"url": "https://x/y.png"}}
                ]}
            }]
        }))
        .unwrap();
        let message = response_to_assistant_message(response);
        assert_eq!(message.content, "hi\n![image](https://x/y.png)");
    }

    #[tokio::test]
    async fn event_stream_surfaces_missing_done_as_error() {
        let server = wiremock::MockServer::start().await;
        // Stream closes without any finish_reason or [DONE].
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .respond_with(
                wiremock::ResponseTemplate::new(200)
                    .set_body_string(r#"data: {"choices":[{"delta":{"content":"hi"}}]}"#),
            )
            .mount(&server)
            .await;

        let client = OpenAiClient::new(&server.uri(), "sk");
        let mut events = stream_chat_completion(
            &client,
            "m",
            &[Message {
                id: "1".into(),
                role: MessageRole::User,
                content: "hi".into(),
                parts: vec![],
            }],
            &ChatTurnParams::default(),
        )
        .await;
        let mut seen = Vec::new();
        while let Some(event) = events.next_event().await {
            seen.push(event);
        }
        assert!(matches!(seen.last(), Some(ChatStreamEvent::Error(m)) if m == ERROR_STREAM_NO_DATA));
    }

    #[tokio::test]
    async fn event_stream_yields_done_and_terminates() {
        let server = wiremock::MockServer::start().await;
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .respond_with(wiremock::ResponseTemplate::new(200).set_body_string(
                "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
            ))
            .mount(&server)
            .await;

        let client = OpenAiClient::new(&server.uri(), "sk");
        let mut events = stream_chat_completion(
            &client,
            "m",
            &[Message {
                id: "1".into(),
                role: MessageRole::User,
                content: "hi".into(),
                parts: vec![],
            }],
            &ChatTurnParams::default(),
        )
        .await;
        let mut saw_done = false;
        let mut count = 0;
        while let Some(event) = events.next_event().await {
            if matches!(event, ChatStreamEvent::Done { .. }) {
                saw_done = true;
            }
            count += 1;
        }
        assert!(saw_done);
        assert!(count >= 2); // content + Done (parser may also emit on [DONE])
    }

    #[tokio::test]
    async fn event_stream_reports_http_error_as_event() {
        let server = wiremock::MockServer::start().await;
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .respond_with(
                wiremock::ResponseTemplate::new(401)
                    .set_body_string(r#"{"error":{"message":"bad key"}}"#),
            )
            .mount(&server)
            .await;

        let client = OpenAiClient::new(&server.uri(), "sk");
        let mut events = stream_chat_completion(
            &client,
            "m",
            &[Message {
                id: "1".into(),
                role: MessageRole::User,
                content: "hi".into(),
                parts: vec![],
            }],
            &ChatTurnParams::default(),
        )
        .await;
        let event = events.next_event().await;
        assert!(matches!(event, Some(ChatStreamEvent::Error(m)) if m == "bad key"));
        assert!(events.next_event().await.is_none());
    }
}
