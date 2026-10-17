//! Wire-format selection for the LLM transport: OpenAI Chat Completions,
//! OpenAI Responses, Anthropic Messages, and Gemini Interactions.
//!
//! The client always speaks one protocol per provider (`Provider.api_format`);
//! `auto` in the store is resolved to `chat` before a turn is built. Request
//! bodies, non-streaming responses, and SSE payloads are encoded/decoded per
//! format here, so `api.rs` and the agent loop stay format-agnostic: they
//! consume the same [`ChatStreamEvent`](crate::events::ChatStreamEvent) and
//! [`Message`](crate::domain::Message) shapes as before.

use serde_json::{json, Map, Value};

use crate::domain::{ContentPart, Message, MessageRole};
use crate::dto::Usage;
use crate::events::{ChatStreamEvent, ToolCallData};
use crate::request::{ChatCompletionRequest, WireMessage};

/// Conversation API protocol used on the wire.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ApiFormat {
    /// OpenAI Chat Completions (`/chat/completions`).
    #[default]
    Chat,
    /// OpenAI Responses (`/responses`).
    Responses,
    /// Anthropic Messages (`/messages`).
    Messages,
    /// Gemini Interactions (`/interactions`).
    Interactions,
}

impl ApiFormat {
    /// Parse a stored provider value; unknown/absent values fall back to
    /// [`ApiFormat::Chat`] (the `auto` sentinel resolves to Chat on this side
    /// because the client is the one choosing the protocol).
    pub fn from_store(value: Option<&str>) -> Self {
        match value {
            Some("responses") => ApiFormat::Responses,
            Some("messages") => ApiFormat::Messages,
            Some("interactions") => ApiFormat::Interactions,
            _ => ApiFormat::Chat,
        }
    }

    /// Path appended to the provider base URL.
    pub fn path(self) -> &'static str {
        match self {
            ApiFormat::Chat => "/chat/completions",
            ApiFormat::Responses => "/responses",
            ApiFormat::Messages => "/messages",
            ApiFormat::Interactions => "/interactions",
        }
    }

    /// Extra auth headers this protocol expects, in addition to
    /// `Authorization: Bearer` (kept for gateways and the Messenger proxy).
    pub fn auth_headers(self, api_key: &str) -> Vec<(&'static str, String)> {
        match self {
            ApiFormat::Messages => vec![
                ("x-api-key", api_key.to_string()),
                ("anthropic-version", "2023-06-01".to_string()),
            ],
            ApiFormat::Interactions => vec![("x-goog-api-key", api_key.to_string())],
            _ => Vec::new(),
        }
    }
}

// ---------------------------------------------------------------------------
// Request encoding
// ---------------------------------------------------------------------------

/// Encode the request for [format]. The `ChatCompletionRequest` carries the
/// assembled messages/params; each protocol re-projects it.
pub fn encode_request(format: ApiFormat, request: &ChatCompletionRequest) -> Value {
    match format {
        ApiFormat::Responses => encode_responses_request(request),
        ApiFormat::Messages => encode_messages_request(request),
        ApiFormat::Interactions => encode_interactions_request(request),
        ApiFormat::Chat => {
            serde_json::to_value(request).unwrap_or_else(|_| Value::Object(Map::new()))
        }
    }
}

fn system_text(messages: &[WireMessage]) -> String {
    messages
        .iter()
        .filter(|m| m.role == "system")
        .map(|m| match &m.content {
            Value::String(text) => text.clone(),
            other => other.as_str().unwrap_or_default().to_string(),
        })
        .filter(|text| !text.is_empty())
        .collect::<Vec<_>>()
        .join("\n")
}

fn text_of(content: &Value) -> String {
    match content {
        Value::String(text) => text.clone(),
        Value::Array(parts) => parts
            .iter()
            .filter_map(Value::as_object)
            .filter_map(|part| part.get("text").and_then(Value::as_str).map(String::from))
            .collect(),
        Value::Null => String::new(),
        other => other.to_string(),
    }
}

fn images_of(content: &Value) -> Vec<String> {
    match content {
        Value::Array(parts) => parts
            .iter()
            .filter_map(Value::as_object)
            .filter(|part| part.get("type").and_then(Value::as_str) == Some("image_url"))
            .filter_map(|part| {
                part.get("image_url")
                    .and_then(Value::as_object)
                    .and_then(|image| image.get("url"))
                    .and_then(Value::as_str)
                    .map(String::from)
            })
            .collect(),
        _ => Vec::new(),
    }
}

/// Split a `data:` URI into (media type, base64 payload).
fn split_data_uri(uri: &str) -> Option<(String, String)> {
    let rest = uri.strip_prefix("data:")?;
    let (meta, data) = rest.split_once(',')?;
    let media_type = meta.split(';').next().unwrap_or("application/octet-stream");
    if meta.contains(";base64") {
        Some((media_type.to_string(), data.to_string()))
    } else {
        // Non-base64 data URIs are rare for images; forward the raw payload
        // (Anthropic/Gemini both expect base64, so percent-decoding here would
        // need a dependency we do not carry).
        Some((media_type.to_string(), data.to_string()))
    }
}

fn encode_responses_request(request: &ChatCompletionRequest) -> Value {
    let mut input: Vec<Value> = Vec::new();
    for message in &request.messages {
        if message.role == "system" {
            continue;
        }
        if message.role == "tool" {
            input.push(json!({
                "type": "function_call_output",
                "call_id": message.tool_call_id.clone().unwrap_or_default(),
                "output": text_of(&message.content),
            }));
            continue;
        }
        for call in message.tool_calls.iter().flatten() {
            input.push(json!({
                "type": "function_call",
                "call_id": call.id,
                "id": call.id,
                "name": call.function.name,
                "arguments": call.function.arguments,
            }));
        }
        let text = text_of(&message.content);
        let images = images_of(&message.content);
        if text.is_empty() && images.is_empty() {
            continue;
        }
        let part_type = if message.role == "assistant" {
            "output_text"
        } else {
            "input_text"
        };
        let mut content: Vec<Value> = Vec::new();
        if !text.is_empty() {
            content.push(json!({ "type": part_type, "text": text }));
        }
        for image in images {
            content.push(json!({ "type": "input_image", "image_url": image }));
        }
        input.push(json!({ "type": "message", "role": message.role, "content": content }));
    }

    let mut body = Map::new();
    body.insert("model".into(), json!(request.model));
    body.insert("input".into(), Value::Array(input));
    body.insert("stream".into(), json!(request.stream));
    let system = system_text(&request.messages);
    if !system.is_empty() {
        body.insert("instructions".into(), json!(system));
    }
    if let Some(temperature) = request.temperature {
        body.insert("temperature".into(), json!(temperature));
    }
    if let Some(top_p) = request.top_p {
        body.insert("top_p".into(), json!(top_p));
    }
    if let Some(max_tokens) = request.max_tokens {
        body.insert("max_output_tokens".into(), json!(max_tokens));
    }
    if let Some(effort) = request.reasoning_effort.as_deref() {
        if effort != "none" {
            body.insert("reasoning".into(), json!({ "effort": effort }));
        }
    }
    if let Some(tools) = request.tools.as_ref().filter(|tools| !tools.is_empty()) {
        body.insert(
            "tools".into(),
            Value::Array(
                tools
                    .iter()
                    .map(|tool| {
                        json!({
                            "type": "function",
                            "name": tool.function.name,
                            "description": tool.function.description,
                            "parameters": tool.function.parameters,
                        })
                    })
                    .collect(),
            ),
        );
    }
    Value::Object(body)
}

fn encode_messages_request(request: &ChatCompletionRequest) -> Value {
    let mut messages: Vec<Value> = Vec::new();
    let mut pending_results: Vec<Value> = Vec::new();

    let flush_results = |messages: &mut Vec<Value>, pending: &mut Vec<Value>| {
        if !pending.is_empty() {
            messages.push(json!({ "role": "user", "content": pending.clone() }));
            pending.clear();
        }
    };

    for message in &request.messages {
        if message.role == "system" {
            continue;
        }
        if message.role == "tool" {
            pending_results.push(json!({
                "type": "tool_result",
                "tool_use_id": message.tool_call_id.clone().unwrap_or_default(),
                "content": text_of(&message.content),
            }));
            continue;
        }
        flush_results(&mut messages, &mut pending_results);
        let mut content: Vec<Value> = Vec::new();
        let text = text_of(&message.content);
        if !text.is_empty() {
            content.push(json!({ "type": "text", "text": text }));
        }
        for image in images_of(&message.content) {
            match split_data_uri(&image) {
                Some((media_type, data)) => content.push(json!({
                    "type": "image",
                    "source": { "type": "base64", "media_type": media_type, "data": data },
                })),
                None => content.push(json!({
                    "type": "image",
                    "source": { "type": "url", "url": image },
                })),
            }
        }
        for call in message.tool_calls.iter().flatten() {
            content.push(json!({
                "type": "tool_use",
                "id": call.id,
                "name": call.function.name,
                "input": parse_arguments(&call.function.arguments),
            }));
        }
        if content.is_empty() {
            continue;
        }
        messages.push(json!({ "role": message.role, "content": content }));
    }
    flush_results(&mut messages, &mut pending_results);

    let mut body = Map::new();
    body.insert("model".into(), json!(request.model));
    body.insert("max_tokens".into(), json!(request.max_tokens.unwrap_or(4096)));
    body.insert("messages".into(), Value::Array(messages));
    body.insert("stream".into(), json!(request.stream));
    let system = system_text(&request.messages);
    if !system.is_empty() {
        body.insert("system".into(), json!(system));
    }
    if let Some(temperature) = request.temperature {
        body.insert("temperature".into(), json!(temperature));
    }
    if let Some(top_p) = request.top_p {
        body.insert("top_p".into(), json!(top_p));
    }
    if let Some(tools) = request.tools.as_ref().filter(|tools| !tools.is_empty()) {
        body.insert(
            "tools".into(),
            Value::Array(
                tools
                    .iter()
                    .map(|tool| {
                        json!({
                            "name": tool.function.name,
                            "description": tool.function.description,
                            "input_schema": tool.function.parameters,
                        })
                    })
                    .collect(),
            ),
        );
    }
    Value::Object(body)
}

fn encode_interactions_request(request: &ChatCompletionRequest) -> Value {
    let mut input: Vec<Value> = Vec::new();
    for message in &request.messages {
        if message.role == "system" {
            continue;
        }
        if message.role == "tool" {
            input.push(json!({
                "type": "function_result",
                "call_id": message.tool_call_id.clone().unwrap_or_default(),
                "result": text_of(&message.content),
            }));
            continue;
        }
        let mut content: Vec<Value> = Vec::new();
        let text = text_of(&message.content);
        if !text.is_empty() {
            content.push(json!({ "type": "text", "text": text }));
        }
        for image in images_of(&message.content) {
            match split_data_uri(&image) {
                Some((media_type, data)) => {
                    content.push(json!({ "type": "image", "data": data, "mime_type": media_type }))
                }
                None => content.push(json!({ "type": "image", "uri": image })),
            }
        }
        if !content.is_empty() {
            let step_type = if message.role == "assistant" {
                "model_output"
            } else {
                "user_input"
            };
            input.push(json!({ "type": step_type, "content": content }));
        }
        for call in message.tool_calls.iter().flatten() {
            input.push(json!({
                "type": "function_call",
                "id": call.id,
                "name": call.function.name,
                "arguments": parse_arguments(&call.function.arguments),
            }));
        }
    }

    let mut generation_config = Map::new();
    if let Some(max_tokens) = request.max_tokens {
        generation_config.insert("max_output_tokens".into(), json!(max_tokens));
    }
    if let Some(level) = request.reasoning_effort.as_deref().and_then(thinking_level) {
        generation_config.insert("thinking_level".into(), json!(level));
    }

    let mut body = Map::new();
    body.insert("model".into(), json!(request.model));
    body.insert("input".into(), Value::Array(input));
    body.insert("stream".into(), json!(request.stream));
    // Stateless: the whole history is replayed on every call.
    body.insert("store".into(), json!(false));
    let system = system_text(&request.messages);
    if !system.is_empty() {
        body.insert("system_instruction".into(), json!(system));
    }
    if !generation_config.is_empty() {
        body.insert("generation_config".into(), Value::Object(generation_config));
    }
    if let Some(tools) = request.tools.as_ref().filter(|tools| !tools.is_empty()) {
        body.insert(
            "tools".into(),
            Value::Array(
                tools
                    .iter()
                    .map(|tool| {
                        json!({
                            "type": "function",
                            "name": tool.function.name,
                            "description": tool.function.description,
                            "parameters": tool.function.parameters,
                        })
                    })
                    .collect(),
            ),
        );
    }
    Value::Object(body)
}

fn thinking_level(effort: &str) -> Option<&'static str> {
    match effort {
        "none" | "minimal" => Some("minimal"),
        "low" => Some("low"),
        "medium" => Some("medium"),
        "high" => Some("high"),
        _ => None,
    }
}

fn parse_arguments(arguments: &str) -> Value {
    serde_json::from_str(arguments).unwrap_or_else(|_| json!({}))
}

// ---------------------------------------------------------------------------
// Non-streaming response decoding
// ---------------------------------------------------------------------------

/// One decoded assistant reply, independent of the wire format.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct DecodedResponse {
    pub text: String,
    pub reasoning: Option<String>,
    pub tool_calls: Vec<ToolCallData>,
    pub usage: Option<Usage>,
    pub finish_reason: String,
}

/// Decode a non-streaming reply for [format].
pub fn decode_response(format: ApiFormat, payload: &Value) -> DecodedResponse {
    match format {
        ApiFormat::Responses => decode_responses_response(payload),
        ApiFormat::Messages => decode_messages_response(payload),
        ApiFormat::Interactions => decode_interactions_response(payload),
        ApiFormat::Chat => decode_chat_response(payload),
    }
}

fn usage_from(prompt: i64, completion: i64, total: Option<i64>, cached: Option<i64>) -> Usage {
    let prompt_tokens = prompt.max(0);
    let completion_tokens = completion.max(0);
    Usage {
        prompt_tokens,
        completion_tokens,
        total_tokens: total.unwrap_or(prompt_tokens + completion_tokens).max(0),
        prompt_tokens_details: cached.map(|cached_tokens| crate::dto::PromptTokensDetails {
            cached_tokens: cached_tokens.max(0),
        }),
    }
}

fn normalize_finish(reason: Option<&str>, has_tool_calls: bool) -> String {
    if has_tool_calls {
        return "tool_calls".to_string();
    }
    match reason {
        Some("max_tokens") | Some("length") => "length",
        Some("tool_use") | Some("tool_calls") | Some("function_call") => "tool_calls",
        Some("content_filter") | Some("safety") | Some("refusal") => "content_filter",
        Some("end_turn") | Some("stop") | Some("stop_sequence") | Some("completed") | None => "stop",
        Some(other) => other,
    }
    .to_string()
}

fn decode_chat_response(payload: &Value) -> DecodedResponse {
    let choice = payload
        .get("choices")
        .and_then(Value::as_array)
        .and_then(|choices| choices.first())
        .cloned()
        .unwrap_or_else(|| json!({}));
    let message = choice.get("message").cloned().unwrap_or_else(|| json!({}));
    let text = text_of(message.get("content").unwrap_or(&Value::Null));
    let reasoning = message
        .get("reasoning_content")
        .and_then(Value::as_str)
        .or_else(|| message.get("reasoning").and_then(Value::as_str))
        .filter(|text| !text.is_empty())
        .map(String::from);
    let tool_calls: Vec<ToolCallData> = message
        .get("tool_calls")
        .and_then(Value::as_array)
        .map(|calls| {
            calls
                .iter()
                .filter_map(Value::as_object)
                .filter_map(|call| {
                    let function = call.get("function")?.as_object()?;
                    let name = function.get("name")?.as_str()?;
                    Some(ToolCallData {
                        call_id: call
                            .get("id")
                            .and_then(Value::as_str)
                            .unwrap_or(name)
                            .to_string(),
                        name: name.to_string(),
                        arguments: function
                            .get("arguments")
                            .and_then(Value::as_str)
                            .unwrap_or("{}")
                            .to_string(),
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    let usage = payload.get("usage").and_then(Value::as_object).map(|usage| {
        let details = usage.get("prompt_tokens_details").and_then(Value::as_object);
        usage_from(
            usage.get("prompt_tokens").and_then(Value::as_i64).unwrap_or(0),
            usage.get("completion_tokens").and_then(Value::as_i64).unwrap_or(0),
            usage.get("total_tokens").and_then(Value::as_i64),
            details
                .and_then(|details| details.get("cached_tokens"))
                .and_then(Value::as_i64),
        )
    });
    let finish_reason = normalize_finish(
        choice.get("finish_reason").and_then(Value::as_str),
        !tool_calls.is_empty(),
    );
    DecodedResponse {
        text,
        reasoning,
        tool_calls,
        usage,
        finish_reason,
    }
}

fn decode_responses_response(payload: &Value) -> DecodedResponse {
    let mut text = String::new();
    let mut reasoning = String::new();
    let mut tool_calls = Vec::new();
    for item in payload.get("output").and_then(Value::as_array).into_iter().flatten() {
        match item.get("type").and_then(Value::as_str) {
            Some("function_call") => {
                let Some(name) = item.get("name").and_then(Value::as_str) else {
                    continue;
                };
                let arguments = match item.get("arguments") {
                    Some(Value::String(raw)) => raw.clone(),
                    Some(other) => other.to_string(),
                    None => "{}".to_string(),
                };
                tool_calls.push(ToolCallData {
                    call_id: item
                        .get("call_id")
                        .and_then(Value::as_str)
                        .or_else(|| item.get("id").and_then(Value::as_str))
                        .unwrap_or(name)
                        .to_string(),
                    name: name.to_string(),
                    arguments,
                });
            }
            Some("reasoning") => {
                for summary in item.get("summary").and_then(Value::as_array).into_iter().flatten() {
                    reasoning.push_str(summary.get("text").and_then(Value::as_str).unwrap_or(""));
                }
            }
            Some("message") => {
                for part in item.get("content").and_then(Value::as_array).into_iter().flatten() {
                    if part.get("type").and_then(Value::as_str) == Some("output_text") {
                        text.push_str(part.get("text").and_then(Value::as_str).unwrap_or(""));
                    }
                }
            }
            _ => {}
        }
    }
    let usage = payload.get("usage").and_then(Value::as_object).map(|usage| {
        let details = usage.get("input_tokens_details").and_then(Value::as_object);
        usage_from(
            usage.get("input_tokens").and_then(Value::as_i64).unwrap_or(0),
            usage.get("output_tokens").and_then(Value::as_i64).unwrap_or(0),
            usage.get("total_tokens").and_then(Value::as_i64),
            details
                .and_then(|details| details.get("cached_tokens"))
                .and_then(Value::as_i64),
        )
    });
    let finish_reason = if !tool_calls.is_empty() {
        "tool_calls".to_string()
    } else if payload.get("status").and_then(Value::as_str) == Some("incomplete") {
        "length".to_string()
    } else {
        "stop".to_string()
    };
    DecodedResponse {
        text,
        reasoning: Some(reasoning).filter(|text| !text.is_empty()),
        tool_calls,
        usage,
        finish_reason,
    }
}

fn decode_messages_response(payload: &Value) -> DecodedResponse {
    let mut text = String::new();
    let mut reasoning = String::new();
    let mut tool_calls = Vec::new();
    for block in payload.get("content").and_then(Value::as_array).into_iter().flatten() {
        match block.get("type").and_then(Value::as_str) {
            Some("text") => text.push_str(block.get("text").and_then(Value::as_str).unwrap_or("")),
            Some("thinking") => {
                reasoning.push_str(block.get("thinking").and_then(Value::as_str).unwrap_or(""))
            }
            Some("tool_use") => {
                let Some(name) = block.get("name").and_then(Value::as_str) else {
                    continue;
                };
                tool_calls.push(ToolCallData {
                    call_id: block
                        .get("id")
                        .and_then(Value::as_str)
                        .unwrap_or(name)
                        .to_string(),
                    name: name.to_string(),
                    arguments: block
                        .get("input")
                        .map(|input| input.to_string())
                        .unwrap_or_else(|| "{}".to_string()),
                });
            }
            _ => {}
        }
    }
    let usage = payload.get("usage").and_then(Value::as_object).map(|usage| {
        usage_from(
            usage.get("input_tokens").and_then(Value::as_i64).unwrap_or(0),
            usage.get("output_tokens").and_then(Value::as_i64).unwrap_or(0),
            None,
            usage
                .get("cache_read_input_tokens")
                .and_then(Value::as_i64),
        )
    });
    let finish_reason = normalize_finish(
        payload.get("stop_reason").and_then(Value::as_str),
        !tool_calls.is_empty(),
    );
    DecodedResponse {
        text,
        reasoning: Some(reasoning).filter(|text| !text.is_empty()),
        tool_calls,
        usage,
        finish_reason,
    }
}

fn decode_interactions_response(payload: &Value) -> DecodedResponse {
    let mut text = String::new();
    let mut reasoning = String::new();
    let mut tool_calls = Vec::new();
    for step in payload.get("steps").and_then(Value::as_array).into_iter().flatten() {
        match step.get("type").and_then(Value::as_str) {
            Some("function_call") => {
                let Some(name) = step.get("name").and_then(Value::as_str) else {
                    continue;
                };
                tool_calls.push(ToolCallData {
                    call_id: step
                        .get("id")
                        .and_then(Value::as_str)
                        .unwrap_or(name)
                        .to_string(),
                    name: name.to_string(),
                    arguments: step
                        .get("arguments")
                        .map(|arguments| arguments.to_string())
                        .unwrap_or_else(|| "{}".to_string()),
                });
            }
            Some("thought") => {
                for summary in step.get("summary").and_then(Value::as_array).into_iter().flatten() {
                    reasoning.push_str(summary.get("text").and_then(Value::as_str).unwrap_or(""));
                }
            }
            Some("model_output") => {
                for part in step.get("content").and_then(Value::as_array).into_iter().flatten() {
                    if part.get("type").and_then(Value::as_str) == Some("text") {
                        text.push_str(part.get("text").and_then(Value::as_str).unwrap_or(""));
                    }
                }
            }
            _ => {}
        }
    }
    let usage = payload.get("usage").and_then(Value::as_object).map(|usage| {
        usage_from(
            usage
                .get("total_input_tokens")
                .and_then(Value::as_i64)
                .unwrap_or(0),
            usage
                .get("total_output_tokens")
                .and_then(Value::as_i64)
                .unwrap_or(0),
            usage.get("total_tokens").and_then(Value::as_i64),
            usage
                .get("total_cached_tokens")
                .and_then(Value::as_i64),
        )
    });
    let finish_reason = if tool_calls.is_empty() {
        "stop".to_string()
    } else {
        "tool_calls".to_string()
    };
    DecodedResponse {
        text,
        reasoning: Some(reasoning).filter(|text| !text.is_empty()),
        tool_calls,
        usage,
        finish_reason,
    }
}

// ---------------------------------------------------------------------------
// Streaming decoding
// ---------------------------------------------------------------------------

/// Stateful SSE decoder: feed one `data:` payload at a time (the terminal
/// `[DONE]` included) and drain the emitted events. Semantics match the Chat
/// parser: reasoning is wrapped in think tags by the *consumer* (the parser
/// emits `ReasoningDetected` + raw content), `Done` may be emitted twice, and
/// tool calls are complete when `Done` fires.
#[derive(Default)]
pub struct FormatStreamDecoder {
    format: ApiFormat,
    chat: Option<crate::parser::ChatStreamParser>,
    partial: std::collections::BTreeMap<i64, (String, String, String)>,
    usage: Option<Usage>,
    reasoning_emitted: bool,
    in_think_block: bool,
    finished: bool,
}

impl FormatStreamDecoder {
    pub fn new(format: ApiFormat) -> Self {
        Self {
            format,
            chat: (format == ApiFormat::Chat).then(crate::parser::ChatStreamParser::new),
            ..Self::default()
        }
    }

    /// Feed one SSE payload. Returns the events it produced, in order.
    pub fn feed(&mut self, payload: &str) -> Vec<ChatStreamEvent> {
        if self.format == ApiFormat::Chat {
            let events = self
                .chat
                .get_or_insert_with(crate::parser::ChatStreamParser::new)
                .feed(payload);
            if events
                .iter()
                .any(|event| matches!(event, ChatStreamEvent::Done { .. }))
            {
                self.finished = true;
            }
            return events;
        }
        if self.finished {
            return Vec::new();
        }
        if payload == "[DONE]" {
            return self.finish();
        }
        let Ok(record) = serde_json::from_str::<Value>(payload) else {
            return Vec::new();
        };
        match self.format {
            ApiFormat::Responses => self.feed_responses(&record),
            ApiFormat::Messages => self.feed_messages(&record),
            ApiFormat::Interactions => self.feed_interactions(&record),
            ApiFormat::Chat => Vec::new(),
        }
    }

    /// Transport closed without a protocol terminal event. Emits nothing:
    /// the caller turns this into the `ERROR_STREAM_NO_DATA` sentinel, the
    /// same contract the Chat parser has always had.
    pub fn finish_on_eof(&mut self) -> Vec<ChatStreamEvent> {
        self.finished = true;
        Vec::new()
    }

    /// Flush after the `[DONE]` sentinel (or an explicit end-of-stream call
    /// that should still produce a terminal `Done`).
    pub fn finish(&mut self) -> Vec<ChatStreamEvent> {
        if self.finished {
            return Vec::new();
        }
        self.finished = true;
        if self.format == ApiFormat::Chat {
            return self
                .chat
                .get_or_insert_with(crate::parser::ChatStreamParser::new)
                .feed("[DONE]");
        }
        let tool_calls = self.complete_tool_calls();
        let mut events = self.close_think_block();
        events.push(ChatStreamEvent::Done {
            finish_reason: Some(if tool_calls.is_empty() { "stop" } else { "tool_calls" }.to_string()),
            usage: self.usage,
            tool_calls,
        });
        events
    }

    /// Reasoning text, wrapped in `<think>` tags exactly like the Chat parser
    /// so downstream rendering and history replay stay format-agnostic.
    fn reasoning(&mut self, text: &str, events: &mut Vec<ChatStreamEvent>) {
        if text.is_empty() {
            return;
        }
        if !self.reasoning_emitted {
            self.reasoning_emitted = true;
            events.push(ChatStreamEvent::ReasoningDetected {
                format: "reasoning_summary".to_string(),
            });
        }
        if !self.in_think_block {
            events.push(ChatStreamEvent::Content("<think>".to_string()));
            self.in_think_block = true;
        }
        events.push(ChatStreamEvent::Content(text.to_string()));
    }

    /// Visible text: closes an open think block first. Blank deltas inside a
    /// think block are dropped (multi-segment reasoning would otherwise be
    /// torn into separate blocks).
    fn text(&mut self, text: &str, events: &mut Vec<ChatStreamEvent>) {
        if text.is_empty() {
            return;
        }
        if text.trim().is_empty() && self.in_think_block {
            return;
        }
        events.extend(self.close_think_block());
        events.push(ChatStreamEvent::Content(text.to_string()));
    }

    fn close_think_block(&mut self) -> Vec<ChatStreamEvent> {
        if !self.in_think_block {
            return Vec::new();
        }
        self.in_think_block = false;
        vec![ChatStreamEvent::Content("</think>\n".to_string())]
    }

    fn feed_responses(&mut self, record: &Value) -> Vec<ChatStreamEvent> {
        let mut events = Vec::new();
        match record.get("type").and_then(Value::as_str) {
            Some("response.output_text.delta") => {
                if let Some(text) = record.get("delta").and_then(Value::as_str) {
                    self.text(text, &mut events);
                }
            }
            Some("response.reasoning_summary_text.delta")
            | Some("response.reasoning_text.delta") => {
                if let Some(text) = record.get("delta").and_then(Value::as_str) {
                    self.reasoning(text, &mut events);
                }
            }
            Some("response.output_item.added") => {
                let item = record.get("item").unwrap_or(&Value::Null);
                if item.get("type").and_then(Value::as_str) == Some("function_call") {
                    let index = record
                        .get("output_index")
                        .and_then(Value::as_i64)
                        .unwrap_or(self.partial.len() as i64);
                    let id = item
                        .get("call_id")
                        .and_then(Value::as_str)
                        .or_else(|| item.get("id").and_then(Value::as_str))
                        .unwrap_or_default()
                        .to_string();
                    let name = item
                        .get("name")
                        .and_then(Value::as_str)
                        .unwrap_or_default()
                        .to_string();
                    self.partial.insert(index, (id, name, String::new()));
                }
            }
            Some("response.function_call_arguments.delta") => {
                let index = record
                    .get("output_index")
                    .and_then(Value::as_i64)
                    .unwrap_or(0);
                let delta = record.get("delta").and_then(Value::as_str).unwrap_or("");
                let entry = self.partial.entry(index).or_default();
                entry.2.push_str(delta);
            }
            Some("response.completed") | Some("response.incomplete") => {
                let response = record.get("response").unwrap_or(record);
                if let Some(usage) = response.get("usage").and_then(Value::as_object) {
                    let details = usage.get("input_tokens_details").and_then(Value::as_object);
                    self.usage = Some(usage_from(
                        usage.get("input_tokens").and_then(Value::as_i64).unwrap_or(0),
                        usage.get("output_tokens").and_then(Value::as_i64).unwrap_or(0),
                        usage.get("total_tokens").and_then(Value::as_i64),
                        details
                            .and_then(|details| details.get("cached_tokens"))
                            .and_then(Value::as_i64),
                    ));
                }
                for item in response.get("output").and_then(Value::as_array).into_iter().flatten() {
                    if item.get("type").and_then(Value::as_str) != Some("function_call") {
                        continue;
                    }
                    let Some(name) = item.get("name").and_then(Value::as_str) else {
                        continue;
                    };
                    let arguments = match item.get("arguments") {
                        Some(Value::String(raw)) => raw.clone(),
                        Some(other) => other.to_string(),
                        None => "{}".to_string(),
                    };
                    let call_id = item
                        .get("call_id")
                        .and_then(Value::as_str)
                        .or_else(|| item.get("id").and_then(Value::as_str))
                        .unwrap_or(name)
                        .to_string();
                    // Prefer the streamed accumulation; fall back to the final item.
                    let index = record
                        .get("output_index")
                        .and_then(Value::as_i64)
                        .unwrap_or(0);
                    let entry = self.partial.entry(index).or_default();
                    if entry.1.is_empty() {
                        entry.1 = name.to_string();
                    }
                    if entry.2.is_empty() {
                        entry.2 = arguments;
                    }
                    if entry.0.is_empty() {
                        entry.0 = call_id;
                    }
                }
                events.extend(self.finish());
            }
            Some("response.failed") | Some("error") => {
                let message = record
                    .get("error")
                    .and_then(|error| error.get("message"))
                    .and_then(Value::as_str)
                    .unwrap_or("Upstream stream failed.");
                events.push(ChatStreamEvent::Error(message.to_string()));
                self.finished = true;
            }
            _ => {}
        }
        events
    }

    fn feed_messages(&mut self, record: &Value) -> Vec<ChatStreamEvent> {
        let mut events = Vec::new();
        match record.get("type").and_then(Value::as_str) {
            Some("message_start") => {
                if let Some(usage) = record
                    .get("message")
                    .and_then(|message| message.get("usage"))
                    .and_then(Value::as_object)
                {
                    self.usage = Some(usage_from(
                        usage.get("input_tokens").and_then(Value::as_i64).unwrap_or(0),
                        usage.get("output_tokens").and_then(Value::as_i64).unwrap_or(0),
                        None,
                        usage
                            .get("cache_read_input_tokens")
                            .and_then(Value::as_i64),
                    ));
                }
            }
            Some("content_block_start") => {
                let block = record.get("content_block").unwrap_or(&Value::Null);
                if block.get("type").and_then(Value::as_str) == Some("tool_use") {
                    let index = record.get("index").and_then(Value::as_i64).unwrap_or(0);
                    self.partial.insert(
                        index,
                        (
                            block
                                .get("id")
                                .and_then(Value::as_str)
                                .unwrap_or_default()
                                .to_string(),
                            block
                                .get("name")
                                .and_then(Value::as_str)
                                .unwrap_or_default()
                                .to_string(),
                            String::new(),
                        ),
                    );
                }
            }
            Some("content_block_delta") => {
                let delta = record.get("delta").unwrap_or(&Value::Null);
                match delta.get("type").and_then(Value::as_str) {
                    Some("text_delta") => {
                        if let Some(text) = delta.get("text").and_then(Value::as_str) {
                            self.text(text, &mut events);
                        }
                    }
                    Some("thinking_delta") => {
                        if let Some(text) = delta.get("thinking").and_then(Value::as_str) {
                            self.reasoning(text, &mut events);
                        }
                    }
                    Some("input_json_delta") => {
                        let index = record.get("index").and_then(Value::as_i64).unwrap_or(0);
                        let partial = delta
                            .get("partial_json")
                            .and_then(Value::as_str)
                            .unwrap_or("");
                        self.partial.entry(index).or_default().2.push_str(partial);
                    }
                    _ => {}
                }
            }
            Some("message_delta") => {
                if let Some(usage) = record.get("usage").and_then(Value::as_object) {
                    let completion = usage
                        .get("output_tokens")
                        .and_then(Value::as_i64)
                        .unwrap_or(0);
                    let prompt = self.usage.map(|usage| usage.prompt_tokens).unwrap_or(0);
                    self.usage = Some(usage_from(prompt, completion, None, None));
                }
                if let Some(reason) = record
                    .get("delta")
                    .and_then(|delta| delta.get("stop_reason"))
                    .and_then(Value::as_str)
                {
                    let tool_calls = self.complete_tool_calls();
                    let finish = normalize_finish(Some(reason), !tool_calls.is_empty());
                    events.extend(self.close_think_block());
                    events.push(ChatStreamEvent::Done {
                        finish_reason: Some(finish),
                        usage: self.usage,
                        tool_calls,
                    });
                    self.finished = true;
                }
            }
            Some("message_stop") => {
                events.extend(self.finish());
            }
            Some("error") => {
                let message = record
                    .get("error")
                    .and_then(|error| error.get("message"))
                    .and_then(Value::as_str)
                    .unwrap_or("Upstream stream failed.");
                events.push(ChatStreamEvent::Error(message.to_string()));
                self.finished = true;
            }
            _ => {}
        }
        events
    }

    fn feed_interactions(&mut self, record: &Value) -> Vec<ChatStreamEvent> {
        let mut events = Vec::new();
        let event_type = record
            .get("event_type")
            .and_then(Value::as_str)
            .or_else(|| record.get("type").and_then(Value::as_str));
        match event_type {
            Some("step.start") => {
                let step = record.get("step").unwrap_or(&Value::Null);
                if step.get("type").and_then(Value::as_str) == Some("function_call") {
                    let index = record.get("index").and_then(Value::as_i64).unwrap_or(0);
                    self.partial.insert(
                        index,
                        (
                            step.get("id")
                                .and_then(Value::as_str)
                                .unwrap_or_default()
                                .to_string(),
                            step.get("name")
                                .and_then(Value::as_str)
                                .unwrap_or_default()
                                .to_string(),
                            String::new(),
                        ),
                    );
                }
            }
            Some("step.delta") => {
                let delta = record.get("delta").unwrap_or(&Value::Null);
                match delta.get("type").and_then(Value::as_str) {
                    Some("text") => {
                        if let Some(text) = delta.get("text").and_then(Value::as_str) {
                            self.text(text, &mut events);
                        }
                    }
                    Some("thought_summary") => {
                        let text = delta
                            .get("content")
                            .and_then(|content| content.get("text"))
                            .and_then(Value::as_str);
                        if let Some(text) = text {
                            self.reasoning(text, &mut events);
                        }
                    }
                    Some("arguments_delta") => {
                        let index = record.get("index").and_then(Value::as_i64).unwrap_or(0);
                        let partial = delta
                            .get("arguments")
                            .and_then(Value::as_str)
                            .unwrap_or("");
                        self.partial.entry(index).or_default().2.push_str(partial);
                    }
                    _ => {}
                }
            }
            Some("interaction.completed") => {
                let interaction = record.get("interaction").unwrap_or(record);
                if let Some(usage) = interaction.get("usage").and_then(Value::as_object) {
                    self.usage = Some(usage_from(
                        usage
                            .get("total_input_tokens")
                            .and_then(Value::as_i64)
                            .unwrap_or(0),
                        usage
                            .get("total_output_tokens")
                            .and_then(Value::as_i64)
                            .unwrap_or(0),
                        usage.get("total_tokens").and_then(Value::as_i64),
                        usage
                            .get("total_cached_tokens")
                            .and_then(Value::as_i64),
                    ));
                }
                events.extend(self.finish());
            }
            Some("error") => {
                let message = record
                    .get("error")
                    .and_then(|error| error.get("message"))
                    .and_then(Value::as_str)
                    .unwrap_or("Upstream stream failed.");
                events.push(ChatStreamEvent::Error(message.to_string()));
                self.finished = true;
            }
            _ => {}
        }
        events
    }

    fn complete_tool_calls(&mut self) -> Vec<ToolCallData> {
        self
            .partial
            .iter()
            .filter(|(_, (_, name, _))| !name.is_empty())
            .map(|(index, (id, name, arguments))| ToolCallData {
                call_id: if id.is_empty() {
                    format!("call_{index}")
                } else {
                    id.clone()
                },
                name: name.clone(),
                arguments: if arguments.is_empty() {
                    "{}".to_string()
                } else {
                    arguments.clone()
                },
            })
            .collect()
    }
}

// ---------------------------------------------------------------------------
// Helpers reused by the agent loop
// ---------------------------------------------------------------------------

/// Coerce a decoded reply into the assistant [`Message`] shape the rest of
/// the app consumes (reasoning wrapped in a leading think block).
pub fn decoded_to_message(decoded: DecodedResponse) -> Message {
    let final_content = match decoded.reasoning {
        Some(reasoning) => format!("<think>{reasoning}</think>\n{}", decoded.text),
        None => decoded.text,
    };
    Message {
        id: String::new(),
        conversation_id: String::new(),
        role: MessageRole::Assistant,
        content: final_content.clone(),
        parts: vec![ContentPart::Text { text: final_content }],
        timestamp: 0,
    }
}


#[cfg(test)]
mod tests {
    use super::*;
    use crate::request::{WireToolCall, WireToolCallFunction};

    fn request() -> ChatCompletionRequest {
        ChatCompletionRequest {
            model: "m1".into(),
            messages: vec![
                WireMessage::text("system", "be nice"),
                WireMessage::text("user", "hi"),
                WireMessage {
                    role: "assistant".into(),
                    content: Value::Null,
                    reasoning_content: None,
                    tool_calls: Some(vec![WireToolCall {
                        id: "c1".into(),
                        kind: "function".into(),
                        function: WireToolCallFunction {
                            name: "terminal".into(),
                            arguments: r#"{"command":"ls"}"#.into(),
                        },
                    }]),
                    tool_call_id: None,
                },
                WireMessage {
                    role: "tool".into(),
                    content: Value::String("a.txt".into()),
                    reasoning_content: None,
                    tool_calls: None,
                    tool_call_id: Some("c1".into()),
                },
            ],
            temperature: Some(0.2),
            top_p: None,
            max_tokens: Some(100),
            reasoning_effort: None,
            thinking: None,
            tools: Some(vec![crate::request::build_tool_spec(
                "terminal",
                "run",
                r#"{"type":"object"}"#,
            )]),
            stream: true,
        }
    }

    #[test]
    fn messages_request_moves_system_and_pairs_tool_blocks() {
        let body = encode_request(ApiFormat::Messages, &request());
        assert_eq!(body["system"], json!("be nice"));
        assert_eq!(body["max_tokens"], json!(100));
        let messages = body["messages"].as_array().unwrap();
        // user, assistant tool_use, then a user turn carrying tool_result.
        assert_eq!(messages.len(), 3);
        assert_eq!(messages[0]["role"], json!("user"));
        assert_eq!(messages[1]["content"][0]["type"], json!("tool_use"));
        assert_eq!(messages[1]["content"][0]["input"], json!({"command": "ls"}));
        assert_eq!(messages[2]["content"][0]["type"], json!("tool_result"));
        assert_eq!(messages[2]["content"][0]["tool_use_id"], json!("c1"));
        assert_eq!(body["tools"][0]["input_schema"], json!({"type": "object"}));
    }

    #[test]
    fn interactions_request_is_stateless_and_maps_generation_config() {
        let body = encode_request(ApiFormat::Interactions, &request());
        assert_eq!(body["store"], json!(false));
        assert_eq!(body["system_instruction"], json!("be nice"));
        assert_eq!(body["generation_config"]["max_output_tokens"], json!(100));
        let input = body["input"].as_array().unwrap();
        assert_eq!(input[0]["type"], json!("user_input"));
        assert_eq!(input[1]["type"], json!("function_call"));
        assert_eq!(input[1]["arguments"], json!({"command": "ls"}));
        assert_eq!(input[2]["type"], json!("function_result"));
        assert_eq!(input[2]["call_id"], json!("c1"));
        assert_eq!(body["tools"][0]["type"], json!("function"));
    }

    #[test]
    fn responses_request_uses_instructions_and_function_items() {
        let body = encode_request(ApiFormat::Responses, &request());
        assert_eq!(body["instructions"], json!("be nice"));
        assert_eq!(body["max_output_tokens"], json!(100));
        let input = body["input"].as_array().unwrap();
        assert_eq!(input[0]["type"], json!("message"));
        assert_eq!(input[0]["content"][0]["type"], json!("input_text"));
        assert_eq!(input[1]["type"], json!("function_call"));
        assert_eq!(input[1]["call_id"], json!("c1"));
        assert_eq!(input[2]["type"], json!("function_call_output"));
    }

    #[test]
    fn chat_request_stays_openai_shaped() {
        let body = encode_request(ApiFormat::Chat, &request());
        assert_eq!(body["messages"][0]["role"], json!("system"));
        assert_eq!(body["messages"][2]["tool_calls"][0]["id"], json!("c1"));
        assert_eq!(body["messages"][3]["tool_call_id"], json!("c1"));
        assert_eq!(body["tools"][0]["type"], json!("function"));
    }

    #[test]
    fn decodes_each_non_streaming_reply_into_one_shape() {
        let chat = decode_response(
            ApiFormat::Chat,
            &json!({
                "choices": [{
                    "message": {
                        "content": "hello",
                        "reasoning_content": "think",
                        "tool_calls": [{ "id": "c9", "function": { "name": "glob", "arguments": "{\"p\":\"*\"}" } }]
                    },
                    "finish_reason": "tool_calls"
                }],
                "usage": { "prompt_tokens": 10, "completion_tokens": 4, "total_tokens": 14 }
            }),
        );
        let messages = decode_response(
            ApiFormat::Messages,
            &json!({
                "content": [
                    { "type": "thinking", "thinking": "think" },
                    { "type": "text", "text": "hello" },
                    { "type": "tool_use", "id": "c9", "name": "glob", "input": { "p": "*" } }
                ],
                "stop_reason": "tool_use",
                "usage": { "input_tokens": 10, "output_tokens": 4 }
            }),
        );
        let interactions = decode_response(
            ApiFormat::Interactions,
            &json!({
                "steps": [
                    { "type": "thought", "summary": [{ "type": "text", "text": "think" }] },
                    { "type": "model_output", "content": [{ "type": "text", "text": "hello" }] },
                    { "type": "function_call", "id": "c9", "name": "glob", "arguments": { "p": "*" } }
                ],
                "usage": { "total_input_tokens": 10, "total_output_tokens": 4, "total_tokens": 14 }
            }),
        );
        let responses = decode_response(
            ApiFormat::Responses,
            &json!({
                "status": "completed",
                "output": [
                    { "type": "reasoning", "summary": [{ "type": "summary_text", "text": "think" }] },
                    { "type": "message", "content": [{ "type": "output_text", "text": "hello" }] },
                    { "type": "function_call", "id": "fc_1", "call_id": "c9", "name": "glob", "arguments": "{\"p\":\"*\"}" }
                ],
                "usage": { "input_tokens": 10, "output_tokens": 4, "total_tokens": 14 }
            }),
        );
        for decoded in [chat, messages, interactions, responses] {
            assert_eq!(decoded.text, "hello");
            assert_eq!(decoded.reasoning.as_deref(), Some("think"));
            assert_eq!(decoded.tool_calls.len(), 1);
            assert_eq!(decoded.tool_calls[0].call_id, "c9");
            assert_eq!(decoded.tool_calls[0].name, "glob");
            assert_eq!(decoded.finish_reason, "tool_calls");
            let usage = decoded.usage.expect("usage");
            assert_eq!((usage.prompt_tokens, usage.completion_tokens), (10, 4));
        }
    }

    fn feed(decoder: &mut FormatStreamDecoder, payloads: &[&str]) -> Vec<ChatStreamEvent> {
        payloads.iter().flat_map(|payload| decoder.feed(payload)).collect()
    }

    fn contents(events: &[ChatStreamEvent]) -> Vec<String> {
        events
            .iter()
            .filter_map(|event| match event {
                ChatStreamEvent::Content(text) => Some(text.clone()),
                _ => None,
            })
            .collect()
    }

    fn done_calls(events: &[ChatStreamEvent]) -> Vec<ToolCallData> {
        events
            .iter()
            .filter_map(|event| match event {
                ChatStreamEvent::Done { tool_calls, .. } => Some(tool_calls.clone()),
                _ => None,
            })
            .flatten()
            .collect()
    }

    #[test]
    fn messages_stream_decodes_text_reasoning_and_tool_use() {
        let mut decoder = FormatStreamDecoder::new(ApiFormat::Messages);
        let events = feed(
            &mut decoder,
            &[
                r#"{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0}}}"#,
                r#"{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"think"}}"#,
                r#"{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"hello"}}"#,
                r#"{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"c9","name":"glob"}}"#,
                r#"{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"p\":\"*\"}"}}"#,
                r#"{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":4}}"#,
                r#"{"type":"message_stop"}"#,
            ],
        );
        assert_eq!(
            contents(&events),
            ["<think>", "think", "</think>\n", "hello"]
        );
        assert!(matches!(
            events.first(),
            Some(ChatStreamEvent::ReasoningDetected { format }) if format == "reasoning_summary"
        ));
        let calls = done_calls(&events);
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].call_id, "c9");
        assert_eq!(calls[0].arguments, r#"{"p":"*"}"#);
        let usage = events.iter().find_map(|event| match event {
            ChatStreamEvent::Done { usage, .. } => *usage,
            _ => None,
        });
        assert_eq!(usage.unwrap().completion_tokens, 4);
    }

    #[test]
    fn interactions_stream_decodes_steps() {
        let mut decoder = FormatStreamDecoder::new(ApiFormat::Interactions);
        let events = feed(
            &mut decoder,
            &[
                r#"{"event_type":"step.delta","index":0,"delta":{"type":"text","text":"hello"}}"#,
                r#"{"event_type":"step.start","index":1,"step":{"type":"function_call","id":"c9","name":"glob"}}"#,
                r#"{"event_type":"step.delta","index":1,"delta":{"type":"arguments_delta","arguments":"{\"p\":\"*\"}"}}"#,
                r#"{"event_type":"interaction.completed","interaction":{"steps":[],"usage":{"total_input_tokens":10,"total_output_tokens":4,"total_tokens":14}}}"#,
            ],
        );
        assert_eq!(contents(&events), ["hello"]);
        let calls = done_calls(&events);
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].name, "glob");
    }

    #[test]
    fn responses_stream_decodes_deltas_and_completion() {
        let mut decoder = FormatStreamDecoder::new(ApiFormat::Responses);
        let events = feed(
            &mut decoder,
            &[
                r#"{"type":"response.output_text.delta","delta":"hello"}"#,
                r#"{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"c9","name":"glob"}}"#,
                r#"{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\"p\":\"*\"}"}"#,
                r#"{"type":"response.completed","response":{"status":"completed","output":[],"usage":{"input_tokens":10,"output_tokens":4,"total_tokens":14}}}"#,
            ],
        );
        assert_eq!(contents(&events), ["hello"]);
        let calls = done_calls(&events);
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].call_id, "c9");
        assert_eq!(calls[0].arguments, r#"{"p":"*"}"#);
    }

    #[test]
    fn decoder_is_idempotent_after_finish() {
        let mut decoder = FormatStreamDecoder::new(ApiFormat::Messages);
        feed(
            &mut decoder,
            &[
                r#"{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}"#,
                r#"{"type":"message_stop"}"#,
            ],
        );
        assert!(decoder.feed(r#"{"type":"message_stop"}"#).is_empty());
        assert!(decoder.finish().is_empty());
    }

    #[test]
    fn format_paths_and_auth_headers() {
        assert_eq!(ApiFormat::Chat.path(), "/chat/completions");
        assert_eq!(ApiFormat::Responses.path(), "/responses");
        assert_eq!(ApiFormat::Messages.path(), "/messages");
        assert_eq!(ApiFormat::Interactions.path(), "/interactions");
        assert_eq!(ApiFormat::from_store(Some("auto")), ApiFormat::Chat);
        assert_eq!(ApiFormat::from_store(Some("messages")), ApiFormat::Messages);
        let headers = ApiFormat::Messages.auth_headers("sk-1");
        assert!(headers.contains(&("x-api-key", "sk-1".to_string())));
        assert!(headers.contains(&("anthropic-version", "2023-06-01".to_string())));
        assert_eq!(
            ApiFormat::Interactions.auth_headers("sk-2"),
            vec![("x-goog-api-key", "sk-2".to_string())]
        );
    }
}
