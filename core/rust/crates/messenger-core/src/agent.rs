//! The event-driven agent loop (port of `ChatViewModel.launchChatTurn` +
//! `generateResponse`): stream a turn, persist each round, execute tool
//! calls through the platform ToolHost, keep the 80% context summarization
//! and title generation running, and report everything to the UI as
//! [`AgentEvent`]s. Cancellation finalizes partial rows instead of losing
//! them.

use std::time::{SystemTime, UNIX_EPOCH};

use async_trait::async_trait;
use messenger_llm::api::{
    create_chat_completion, stream_chat_completion, ChatTurnParams, ToolDeclaration,
};
use messenger_llm::client::OpenAiClient;
use messenger_llm::domain::{ContentPart, Message, MessageRole};
use messenger_llm::events::ChatStreamEvent;
use messenger_store::model::{StoredConversation, StoredMessage};
use messenger_store::Store;
use messenger_tools::ToolExecutionResult;
use tokio_util::sync::CancellationToken;

use crate::context::{
    build_api_context_messages, build_summary_transcript, estimate_context_tokens,
    estimate_tokens_message, estimate_tokens_text, summarize_threshold,
    ContextState, SUMMARY_KEEP_COUNT,
};
use crate::parts::{decode_parts, encode_parts};
use crate::title::{
    build_title_transcript, fallback_title, is_untitled_conversation, sanitize_generated_title,
};

/// Max tool rounds per turn before failing with `error_tool_rounds_exceeded`.
pub const MAX_TOOL_ROUNDS: u32 = 10;

/// Events the turn reports to the UI. Errors carry stable codes; the FFI
/// facade maps them to localized strings.
#[derive(Debug, Clone, PartialEq)]
pub enum AgentEvent {
    TurnStarted,
    TextDelta { round: u32, text: String },
    ReasoningFormatDetected { format: String },
    RoundPersisted { round: u32, message_id: String },
    FinalMessagePersisted { message_id: String, content: String },
    ToolCallStarted { call_id: String, name: String },
    ToolCallFinished { call_id: String, name: String, output: String, is_error: bool },
    UsageRecorded { prompt_tokens: i64, completion_tokens: i64 },
    TitleGenerated { title: String },
    TitleFailed { code: String },
    Finished { message_id: String },
    Cancelled,
    /// `message` is the provider/transport detail; `code` is stable.
    Error { code: String, message: String },
}

/// UI/event sink; synchronous on purpose — the loop never blocks on UI.
pub trait TurnSink: Send + Sync {
    fn on_event(&self, event: &AgentEvent);
}

/// Platform tool executor (terminal/workspace/MCP are routed here).
#[async_trait]
pub trait ToolHost: Send + Sync {
    async fn execute(&self, name: &str, arguments_json: &str) -> ToolExecutionResult;
}

/// Title-agent model binding resolved by the caller before the turn.
#[derive(Debug, Clone, Default)]
pub struct TitleConfig {
    pub agent_id: String,
    pub system_prompt: String,
    pub model_id: Option<String>,
    pub temperature: Option<f64>,
    pub top_p: Option<f64>,
    pub max_tokens: Option<i64>,
}

/// Everything one turn needs, resolved by the caller (model binding, tools,
/// per-conversation overrides). The loop does not re-resolve.
#[derive(Debug, Clone)]
pub struct TurnRequest {
    pub conversation_id: String,
    pub model_id: String,
    pub base_url: String,
    pub api_key: String,
    pub system_prompt: String,
    pub temperature: Option<f64>,
    pub top_p: Option<f64>,
    pub max_tokens: Option<i64>,
    pub reasoning_effort: Option<String>,
    /// Pre-resolved tool list (per-tool config + writable mode applied).
    pub tools: Vec<messenger_tools::BuiltinTool>,
    pub context_window: i64,
    /// Localized summarization prompt (`context_summarize_prompt`).
    pub summarize_prompt: String,
    pub title: Option<TitleConfig>,
}

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn new_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

/// Run one full agent turn. Returns `Err` only for caller bugs (store
/// failures surface as `Error` events too... store failures abort with Err).
pub async fn run_chat_turn(
    store: &Store,
    tool_host: &dyn ToolHost,
    sink: &dyn TurnSink,
    request: &TurnRequest,
    cancel: CancellationToken,
) -> Result<(), String> {
    sink.on_event(&AgentEvent::TurnStarted);

    let mut conversation = match store.get_conversation(&request.conversation_id) {
        Ok(Some(c)) => c,
        Ok(None) => {
            sink.on_event(&AgentEvent::Error {
                code: "conversation_missing".into(),
                message: String::new(),
            });
            return Ok(());
        }
        Err(e) => return Err(e.to_string()),
    };

    // ---- 80% context auto-summarization (never blocks the send) ----
    if let Err(summary_err) =
        maybe_summarize_context(store, &mut conversation, request, &cancel).await
    {
        if summary_err.is_cancel {
            sink.on_event(&AgentEvent::Cancelled);
            return Ok(());
        }
        sink.on_event(&AgentEvent::Error {
            code: "context_summarize_failed".into(),
            message: summary_err.message,
        });
    }

    // ---- placeholder row for the final text round ----
    let placeholder_id = new_id();
    let placeholder = StoredMessage {
        id: placeholder_id.clone(),
        conversation_id: request.conversation_id.clone(),
        role: "assistant".into(),
        content: String::new(),
        parts_json: None,
        timestamp: next_timestamp(store, &request.conversation_id)?,
        status: "sending".into(),
        error_message: None,
    };
    store.upsert_message(&placeholder).map_err(|e| e.to_string())?;

    let client = OpenAiClient::new(&request.base_url, &request.api_key);
    let mut current_content = String::new();
    let mut round: u32 = 0;

    while round < MAX_TOOL_ROUNDS {
        if cancel.is_cancelled() {
            return finalize_cancellation(store, sink, &placeholder_id).await;
        }
        round += 1;

        let conversation = match store.get_conversation(&request.conversation_id) {
            Ok(Some(c)) => c,
            Ok(None) => return Ok(()),
            Err(e) => return Err(e.to_string()),
        };
        let sent = sent_messages(store, &request.conversation_id)?;
        let context_state = ContextState {
            context_summary: conversation.context_summary.clone(),
            context_summary_until: conversation.context_summary_until,
            context_tokens: conversation.context_tokens,
            context_tokens_at: conversation.context_tokens_at,
        };
        let context_messages = build_api_context_messages(&context_state, &request.conversation_id, &sent);
        let sent_context_estimate = estimate_context_tokens(&context_state, &sent, Some(&request.system_prompt));

        let params = ChatTurnParams {
            temperature: request.temperature,
            top_p: request.top_p,
            max_tokens: request.max_tokens,
            reasoning_effort: request.reasoning_effort.clone(),
            reasoning_format: conversation.reasoning_format.clone(),
            system_prompt: Some(request.system_prompt.clone()),
            tools: Some(
                request
                    .tools
                    .iter()
                    .map(|t| ToolDeclaration {
                        name: t.name.clone(),
                        description: t.description.clone(),
                        parameters_json: t.parameters_json.clone(),
                    })
                    .collect(),
            ),
        };

        let mut events = stream_chat_completion(&client, &request.model_id, &context_messages, &params).await;
        let mut done: Option<(Option<String>, Option<messenger_llm::dto::Usage>, Vec<messenger_llm::ToolCallData>)> = None;
        loop {
            let event = tokio::select! {
                _ = cancel.cancelled() => {
                    // Cancelled mid-stream: keep partial content, finalize rows.
                    if !current_content.is_empty() {
                        promote_partial(store, &placeholder_id, &current_content)?;
                    }
                    return finalize_cancellation(store, sink, &placeholder_id).await;
                }
                event = events.next_event() => event,
            };
            match event {
                Some(ChatStreamEvent::Content(text)) => {
                    current_content.push_str(&text);
                    sink.on_event(&AgentEvent::TextDelta { round, text });
                }
                Some(ChatStreamEvent::ReasoningDetected { format }) => {
                    // Re-read before writing: the summary flow may have
                    // updated the row concurrently.
                    if let Ok(Some(latest)) = store.get_conversation(&request.conversation_id) {
                        let mut updated = latest;
                        if updated.reasoning_format.as_deref() != Some(format.as_str()) {
                            updated.reasoning_format = Some(format.clone());
                            let _ = store.upsert_conversation(&updated);
                        }
                    }
                    sink.on_event(&AgentEvent::ReasoningFormatDetected { format });
                }
                Some(ChatStreamEvent::Done { finish_reason, usage, tool_calls }) => {
                    done = Some((finish_reason, usage, tool_calls));
                }
                Some(ChatStreamEvent::Error(message)) => {
                    if cancel.is_cancelled() {
                        if !current_content.is_empty() {
                            promote_partial(store, &placeholder_id, &current_content)?;
                        }
                        return finalize_cancellation(store, sink, &placeholder_id).await;
                    }
                    // Persist partial content + error on the placeholder row.
                    let mut failed = placeholder.clone();
                    failed.content = current_content.clone();
                    failed.status = "error".into();
                    failed.error_message = Some(message.clone());
                    let _ = store.upsert_message(&failed);
                    sink.on_event(&AgentEvent::Error { code: "api".into(), message });
                    return Ok(());
                }
                None => break,
            }
        }

        let Some((_, usage, tool_calls)) = done else {
            // ChatEventStream guarantees an Error event before None when the
            // stream never finished, so this is unreachable — treat as error.
            sink.on_event(&AgentEvent::Error {
                code: "api".into(),
                message: "stream ended unexpectedly".into(),
            });
            return Ok(());
        };

        if tool_calls.is_empty() {
            // Final text round: land in the placeholder row.
            let content = current_content.clone();
            let mut final_row = placeholder.clone();
            final_row.content = content.clone();
            final_row.parts_json = encode_parts(&[ContentPart::Text { text: content.clone() }]);
            final_row.status = "sent".into();
            final_row.timestamp = next_timestamp(store, &request.conversation_id)?;
            store.upsert_message(&final_row).map_err(|e| e.to_string())?;
            sink.on_event(&AgentEvent::FinalMessagePersisted {
                message_id: final_row.id.clone(),
                content: content.clone(),
            });

            // Usage bookkeeping: exact when reported, estimate otherwise.
            let tokens = usage
                .map(|u| u.prompt_tokens + u.completion_tokens)
                .filter(|t| *t > 0)
                .unwrap_or(sent_context_estimate + estimate_tokens_text(&content));
            record_usage(store, &request.conversation_id, tokens)?;
            sink.on_event(&AgentEvent::UsageRecorded {
                prompt_tokens: 0,
                completion_tokens: tokens,
            });

            // Title generation (still-untitled conversations only).
            generate_title_if_needed(store, sink, &client, request).await;

            sink.on_event(&AgentEvent::Finished {
                message_id: final_row.id.clone(),
            });
            return Ok(());
        }

        // ---- tool round: persist the round, then execute each call ----
        let round_id = new_id();
        let mut parts: Vec<ContentPart> = Vec::new();
        if !current_content.is_empty() {
            parts.push(ContentPart::Text {
                text: current_content.clone(),
            });
        }
        for call in &tool_calls {
            parts.push(ContentPart::ToolCall {
                call_id: call.call_id.clone(),
                name: call.name.clone(),
                arguments: call.arguments.clone(),
            });
        }
        let round_row = StoredMessage {
            id: round_id.clone(),
            conversation_id: request.conversation_id.clone(),
            role: "assistant".into(),
            content: current_content.clone(),
            parts_json: encode_parts(&parts),
            timestamp: next_timestamp(store, &request.conversation_id)?,
            status: "sent".into(),
            error_message: None,
        };
        store.upsert_message(&round_row).map_err(|e| e.to_string())?;
        sink.on_event(&AgentEvent::RoundPersisted {
            round,
            message_id: round_id.clone(),
        });
        current_content.clear();

        for call in &tool_calls {
            if cancel.is_cancelled() {
                return finalize_cancellation(store, sink, &placeholder_id).await;
            }
            sink.on_event(&AgentEvent::ToolCallStarted {
                call_id: call.call_id.clone(),
                name: call.name.clone(),
            });
            // Persist the running result first so the UI shows a live card.
            let tool_row_id = new_id();
            let running = StoredMessage {
                id: tool_row_id.clone(),
                conversation_id: request.conversation_id.clone(),
                role: "tool".into(),
                content: String::new(),
                parts_json: encode_parts(&[ContentPart::ToolResult {
                    call_id: call.call_id.clone(),
                    name: call.name.clone(),
                    output: String::new(),
                    is_error: false,
                }]),
                timestamp: next_timestamp(store, &request.conversation_id)?,
                status: "sending".into(),
                error_message: None,
            };
            store.upsert_message(&running).map_err(|e| e.to_string())?;

            let result = if request.tools.iter().any(|t| t.name == call.name) {
                tool_host.execute(&call.name, &call.arguments).await
            } else {
                // Unknown names return an error result for self-correction.
                ToolExecutionResult {
                    output: format!("Unknown tool: {}", call.name),
                    is_error: true,
                }
            };
            // Persist the result even when cancelled mid-run.
            let finished = StoredMessage {
                id: tool_row_id.clone(),
                conversation_id: request.conversation_id.clone(),
                role: "tool".into(),
                content: result.output.clone(),
                parts_json: encode_parts(&[ContentPart::ToolResult {
                    call_id: call.call_id.clone(),
                    name: call.name.clone(),
                    output: result.output.clone(),
                    is_error: result.is_error,
                }]),
                timestamp: running.timestamp,
                status: "sent".into(),
                error_message: None,
            };
            store.upsert_message(&finished).map_err(|e| e.to_string())?;
            sink.on_event(&AgentEvent::ToolCallFinished {
                call_id: call.call_id.clone(),
                name: call.name.clone(),
                output: result.output.clone(),
                is_error: result.is_error,
            });
        }
    }

    // Round limit exhausted: fail the turn.
    let mut failed = placeholder.clone();
    failed.status = "error".into();
    failed.error_message = Some("error_tool_rounds_exceeded".into());
    let _ = store.upsert_message(&failed);
    sink.on_event(&AgentEvent::Error {
        code: "error_tool_rounds_exceeded".into(),
        message: String::new(),
    });
    Ok(())
}

/// Unknown-name handling lives in the loop, matching Kotlin
/// (`tools.firstOrNull { it.name == name }`).

/// 80% context summarization; returns Ok(()) when nothing to do.
async fn maybe_summarize_context(
    store: &Store,
    conversation: &mut StoredConversation,
    request: &TurnRequest,
    cancel: &CancellationToken,
) -> Result<(), SummarizeError> {
    if request.context_window <= 0 {
        return Ok(());
    }
    if cancel.is_cancelled() {
        return Err(SummarizeError::cancel());
    }
    let threshold = summarize_threshold(request.context_window);
    let sent = sent_messages(store, &conversation.id)
        .map_err(|e| SummarizeError::failed(e.to_string()))?;
    let state = ContextState {
        context_summary: conversation.context_summary.clone(),
        context_summary_until: conversation.context_summary_until,
        context_tokens: conversation.context_tokens,
        context_tokens_at: conversation.context_tokens_at,
    };
    if estimate_context_tokens(&state, &sent, Some(&request.system_prompt)) < threshold {
        return Ok(());
    }
    if sent.is_empty() {
        return Ok(());
    }
    let keep_count = SUMMARY_KEEP_COUNT.min(sent.len());
    let first_kept = &sent[sent.len() - keep_count];
    let to_summarize: Vec<Message> = sent
        .iter()
        .filter(|m| m.timestamp < first_kept.timestamp)
        .filter(|m| m.id != "context-summary")
        .cloned()
        .collect();
    if to_summarize.is_empty() {
        return Ok(());
    }

    let transcript = build_summary_transcript(
        conversation.context_summary.as_deref(),
        conversation.context_summary_until,
        &to_summarize,
    );

    let client = OpenAiClient::new(&request.base_url, &request.api_key);
    let params = ChatTurnParams {
        temperature: Some(0.3),
        top_p: Some(1.0),
        max_tokens: None,
        reasoning_effort: None,
        reasoning_format: None,
        system_prompt: Some(request.summarize_prompt.clone()),
        tools: None,
    };
    let prompt_message = Message {
        id: new_id(),
        conversation_id: String::new(),
        role: MessageRole::User,
        content: transcript.clone(),
        parts: vec![ContentPart::Text { text: transcript }],
        timestamp: now_ms(),
    };
    let result = create_chat_completion(&client, &request.model_id, &[prompt_message], &params).await;
    let summary = match result {
        Ok(message) => messenger_llm::think::strip_think_block(&message.content)
            .trim()
            .to_string(),
        Err(detail) => return Err(SummarizeError::failed(detail)),
    };
    if summary.is_empty() {
        return Ok(());
    }

    // Re-read before writing: other writers may have updated the row.
    let Some(mut latest) = store.get_conversation(&conversation.id).map_err(|e| SummarizeError::failed(e.to_string()))? else {
        return Ok(());
    };
    let kept_estimates: i64 = sent
        .iter()
        .filter(|m| m.timestamp >= first_kept.timestamp)
        .map(estimate_tokens_message)
        .sum();
    latest.context_summary = Some(summary.clone());
    latest.context_summary_until = first_kept.timestamp;
    latest.context_tokens = estimate_tokens_text(&request.system_prompt)
        + estimate_tokens_text(&summary)
        + kept_estimates;
    latest.context_tokens_at = now_ms();
    store.upsert_conversation(&latest).map_err(|e| SummarizeError::failed(e.to_string()))?;
    *conversation = latest;
    Ok(())
}

struct SummarizeError {
    message: String,
    is_cancel: bool,
}

impl SummarizeError {
    fn failed(message: impl Into<String>) -> Self {
        Self { message: message.into(), is_cancel: false }
    }
    fn cancel() -> Self {
        Self { message: String::new(), is_cancel: true }
    }
}

fn sent_messages(store: &Store, conversation_id: &str) -> Result<Vec<Message>, String> {
    let rows = store
        .list_messages_by_conversation(conversation_id)
        .map_err(|e| e.to_string())?;
    Ok(rows.into_iter().filter(|m| m.status == "sent").map(message_from_row).collect())
}

/// Project a stored row into the LLM-facing domain message (decodes
/// `partsJson`).
pub fn message_from_row(m: StoredMessage) -> Message {
    Message {
        id: m.id,
        conversation_id: m.conversation_id,
        role: match m.role.as_str() {
            "user" => MessageRole::User,
            "assistant" => MessageRole::Assistant,
            "system" => MessageRole::System,
            _ => MessageRole::Tool,
        },
        content: m.content,
        parts: decode_parts(m.parts_json.as_deref()),
        timestamp: m.timestamp,
    }
}

fn next_timestamp(store: &Store, conversation_id: &str) -> Result<i64, String> {
    match store.max_message_timestamp(conversation_id).map_err(|e| e.to_string())? {
        Some(max) => Ok(max + 1),
        None => Ok(now_ms()),
    }
}

fn record_usage(store: &Store, conversation_id: &str, tokens: i64) -> Result<(), String> {
    let Some(conv) = store.get_conversation(conversation_id).map_err(|e| e.to_string())? else {
        return Ok(());
    };
    let mut updated = conv;
    updated.context_tokens = tokens;
    updated.context_tokens_at = now_ms();
    store.upsert_conversation(&updated).map_err(|e| e.to_string())
}

/// Preserve the turn's placeholder row and a latest persisted timestamp.
fn promote_partial(store: &Store, placeholder_id: &str, content: &str) -> Result<(), String> {
    let Some(row) = store.get_message(placeholder_id).map_err(|e| e.to_string())? else {
        return Ok(());
    };
    let mut row = row;
    row.content = content.to_string();
    store.upsert_message(&row).map_err(|e| e.to_string())
}

/// Cancellation: SENDING assistant rows keep their partial content as SENT
/// (future context), SENDING tool rows finalize as interrupted.
async fn finalize_cancellation(
    store: &Store,
    sink: &dyn TurnSink,
    placeholder_id: &str,
) -> Result<(), String> {
    if let Some(row) = store.get_message(placeholder_id).map_err(|e| e.to_string())? {
        if row.status == "sending" && !row.content.is_empty() {
            let mut row = row;
            row.status = "sent".into();
            row.parts_json = encode_parts(&[ContentPart::Text { text: row.content.clone() }]);
            store.upsert_message(&row).map_err(|e| e.to_string())?;
        }
    }
    sink.on_event(&AgentEvent::Cancelled);
    Ok(())
}

/// Title generation on the first non-blank stream Done. Falls back to
/// truncating the first user message on failure and still reports the error.
async fn generate_title_if_needed(
    store: &Store,
    sink: &dyn TurnSink,
    client: &OpenAiClient,
    request: &TurnRequest,
) {
    let Some(title_config) = &request.title else {
        return;
    };
    let Ok(Some(conversation)) = store.get_conversation(&request.conversation_id) else {
        return;
    };
    if !is_untitled_conversation(&conversation.title) {
        return;
    }
    let sent = match sent_messages(store, &request.conversation_id) {
        Ok(list) => list,
        Err(_) => return,
    };
    let Some(first_user) = sent.iter().find(|m| m.role == MessageRole::User) else {
        return;
    };
    let Some(first_assistant) = sent
        .iter()
        .find(|m| m.role == MessageRole::Assistant && !m.content.trim().is_empty())
    else {
        return;
    };

    // Title agent's own model binding wins; the turn's model is the fallback.
    let Some(model_id) = title_config.model_id.clone().or_else(|| Some(request.model_id.clone())) else {
        return;
    };

    let transcript = build_title_transcript(&first_user.content, &first_assistant.content);
    let params = ChatTurnParams {
        temperature: title_config.temperature,
        top_p: title_config.top_p,
        max_tokens: title_config.max_tokens,
        reasoning_effort: None,
        reasoning_format: None,
        system_prompt: Some(title_config.system_prompt.clone()),
        tools: None,
    };
    let prompt_message = Message {
        id: new_id(),
        conversation_id: String::new(),
        role: MessageRole::User,
        content: transcript,
        parts: Vec::new(),
        timestamp: now_ms(),
    };

    let generated = create_chat_completion(client, &model_id, &[prompt_message], &params).await;
    let title = match &generated {
        Ok(message) if !message.content.trim().is_empty() => {
            sanitize_generated_title(&message.content, 30)
        }
        Ok(_) => fallback_title(&first_user.content),
        Err(detail) => {
            sink.on_event(&AgentEvent::TitleFailed { code: format!("error_title_generate_failed:{detail}") });
            fallback_title(&first_user.content)
        }
    };
    if title.trim().is_empty() {
        return;
    }
    // Re-read before writing: a manual rename must never be clobbered.
    if let Ok(Some(latest)) = store.get_conversation(&request.conversation_id) {
        if is_untitled_conversation(&latest.title) {
            let mut updated = latest;
            updated.title = title.clone();
            let _ = store.upsert_conversation(&updated);
            sink.on_event(&AgentEvent::TitleGenerated { title });
        }
    }
}

