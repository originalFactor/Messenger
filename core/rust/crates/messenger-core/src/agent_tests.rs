//! Agent-loop integration tests: a scripted wiremock provider drives a full
//! two-round tool turn (tool call → execution → final text) and a plain
//! text turn, verifying store persistence, event ordering, title fallback,
//! and cancellation finalization.

use std::sync::Mutex;

use messenger_llm::client::OpenAiClient;
use messenger_store::model::{StoredAgent, StoredConversation, StoredMessage, StoredProvider};
use messenger_store::Store;
use messenger_tools::ToolExecutionResult;

use crate::agent::{run_chat_turn, AgentEvent, ToolHost, TurnRequest, TurnSink};
use crate::parts::decode_parts;

/// Scripted provider: responds with the next body per request.
struct ScriptedResponder {
    bodies: Mutex<VecDeque<String>>,
}

impl wiremock::Respond for ScriptedResponder {
    fn respond(&self, _request: &wiremock::Request) -> wiremock::ResponseTemplate {
        let mut bodies = self.bodies.lock().unwrap();
        let body = bodies.pop_front().unwrap_or_else(|| "data: [DONE]\n\n".to_string());
        wiremock::ResponseTemplate::new(200).set_body_string(body)
    }
}

use std::collections::VecDeque;

struct RecordingSink(Mutex<Vec<AgentEvent>>);

impl TurnSink for RecordingSink {
    fn on_event(&self, event: &AgentEvent) {
        self.0.lock().unwrap().push(event.clone());
    }
}

struct StaticToolHost;

#[async_trait::async_trait]
impl ToolHost for StaticToolHost {
    async fn execute(&self, name: &str, _arguments: &str) -> ToolExecutionResult {
        if name == "terminal" {
            ToolExecutionResult {
                output: "file-a\nfile-b".into(),
                is_error: false,
            }
        } else {
            ToolExecutionResult {
                output: format!("Unknown tool: {name}"),
                is_error: true,
            }
        }
    }
}

fn tool_call_stream(name: &str, call_id: &str) -> String {
    let chunk = serde_json::json!({
        "choices": [{
            "delta": {
                "tool_calls": [{
                    "index": 0,
                    "id": call_id,
                    "type": "function",
                    "function": {"name": name, "arguments": "{\"command\":\"ls\"}"}
                }]
            },
            "finish_reason": "tool_calls"
        }]
    });
    format!("data: {}

", serde_json::to_string(&chunk).unwrap())
}

fn text_stream(text: &str) -> String {
    let chunk = serde_json::json!({
        "choices": [{
            "delta": {"content": text},
            "finish_reason": "stop"
        }]
    });
    format!("data: {}

data: [DONE]

", serde_json::to_string(&chunk).unwrap())
}

fn seeded_store() -> Store {
    let store = Store::open_memory().unwrap();
    store
        .upsert_provider(&StoredProvider {
            id: "p1".into(),
            name: "test".into(),
            base_url: "unused".into(),
            api_key: "sk".into(),
            created_at: 1,
            updated_at: 1,
        })
        .unwrap();
    store
        .upsert_agent(&StoredAgent {
            id: "a1".into(),
            name: "agent".into(),
            avatar: None,
            system_prompt: "be helpful".into(),
            description: String::new(),
            default_model_id: None,
            temperature: Some(0.7),
            top_p: Some(0.9),
            max_tokens: None,
            reasoning_effort: None,
            is_default: true,
            follow_default_system_prompt: false,
            follow_default_model: false,
            follow_default_temperature: false,
            follow_default_top_p: false,
            follow_default_max_tokens: false,
            follow_default_reasoning_effort: false,
            market_agent_id: None,
            market_agent_version: None,
            market_agent_role: None,
            role: "chat".into(),
            tools_enabled: true,
            tools_follow_default: false,
            tools_config: "".into(),
            created_at: 1,
            updated_at: 1,
        })
        .unwrap();
    store
        .upsert_conversation(&StoredConversation {
            id: "c1".into(),
            title: "新对话".into(),
            provider_id: "p1".into(),
            agent_id: "a1".into(),
            override_model_id: None,
            override_temperature: None,
            override_top_p: None,
            override_max_tokens: None,
            override_reasoning_effort: None,
            override_tools_enabled: None,
            override_tools_config: None,
            writable: true,
            created_at: 1,
            updated_at: 1,
            last_message: None,
            reasoning_format: None,
            context_summary: None,
            context_summary_until: 0,
            context_tokens: 0,
            context_tokens_at: 0,
        })
        .unwrap();
    store
        .upsert_message(&StoredMessage {
            id: "u1".into(),
            conversation_id: "c1".into(),
            role: "user".into(),
            content: "list the files".into(),
            parts_json: None,
            timestamp: 100,
            status: "sent".into(),
            error_message: None,
        })
        .unwrap();
    store
}

fn turn_request(base_url: &str) -> TurnRequest {
    TurnRequest {
        conversation_id: "c1".into(),
        model_id: "test-model".into(),
        base_url: base_url.to_string(),
        api_key: "sk".into(),
        system_prompt: "be helpful".into(),
        temperature: Some(0.7),
        top_p: Some(0.9),
        max_tokens: None,
        reasoning_effort: None,
        tools: messenger_tools::builtin_registry(),
        context_window: 0,
        summarize_prompt: "summarize".into(),
        title: None,
    }
}

#[tokio::test]
async fn full_tool_round_persists_rows_and_finishes() {
    let server = wiremock::MockServer::start().await;
    wiremock::Mock::given(wiremock::matchers::method("POST"))
                .respond_with(ScriptedResponder {
                    bodies: Mutex::new(VecDeque::from(vec![
                        tool_call_stream("terminal", "call_1"),
                        text_stream("Here are the files."),
                    ])),
                })
        .mount(&server)
        .await;

    let store = seeded_store();
    let sink = RecordingSink(Mutex::new(Vec::new()));
    let result = run_chat_turn(
        &store,
        &StaticToolHost,
        &sink,
        &turn_request(&server.uri()),
        tokio_util::sync::CancellationToken::new(),
    )
    .await;
    if let Err(e) = &result {
        eprintln!("RUN ERROR: {e}");
    }
    result.unwrap();

    let messages = store.list_messages_by_conversation("c1").unwrap();
    eprintln!("ROWS: {messages:?}");
    // user + assistant tool-call round + tool result + final assistant
    assert_eq!(messages.len(), 4);

    let round_row = &messages[1];
    assert_eq!(round_row.role, "assistant");
    let parts = decode_parts(round_row.parts_json.as_deref());
    assert!(matches!(parts[0], messenger_llm::domain::ContentPart::ToolCall { .. }));

    let tool_row = &messages[2];
    assert_eq!(tool_row.role, "tool");
    assert_eq!(tool_row.status, "sent");
    let tool_parts = decode_parts(tool_row.parts_json.as_deref());
    match &tool_parts[0] {
        messenger_llm::domain::ContentPart::ToolResult { call_id, output, is_error, .. } => {
            assert_eq!(call_id, "call_1");
            assert_eq!(output, "file-a\nfile-b");
            assert!(!is_error);
        }
        other => panic!("expected tool result part, got {other:?}"),
    }

    let final_row = &messages[3];
    assert_eq!(final_row.role, "assistant");
    assert_eq!(final_row.status, "sent");
    assert_eq!(final_row.content, "Here are the files.");

    let events = sink.0.lock().unwrap();
    eprintln!("EVENTS: {events:?}");
    let messages_dbg: Vec<_> = store.list_messages_by_conversation("c1").unwrap();
    eprintln!("ROWS: {messages_dbg:?}");
    assert!(matches!(events.first(), Some(AgentEvent::TurnStarted)));
    assert!(matches!(
        events.last(),
        Some(AgentEvent::Finished { .. })
    ));
    assert!(events
        .iter()
        .any(|e| matches!(e, AgentEvent::ToolCallFinished { call_id, output, is_error, .. }
            if call_id == "call_1" && output == "file-a\nfile-b" && !is_error)));
}

#[tokio::test]
async fn plain_text_turn_sets_fallback_title() {
    let server = wiremock::MockServer::start().await;
    wiremock::Mock::given(wiremock::matchers::method("POST"))
                .respond_with(ScriptedResponder {
                    bodies: Mutex::new(VecDeque::from(vec![
                        // Title agent call (before the chat stream? no: title runs after Done).
                        text_stream("The answer is 42."),
                    ])),
                })
        .mount(&server)
        .await;

    let store = seeded_store();
    let mut request = turn_request(&server.uri());
    // Title agent configured but its model binding missing → falls back to
    // the turn's model; generation "fails" (next scripted body is empty DONE)
    // → fallback title from the first user message.
    request.title = Some(crate::agent::TitleConfig::default());

    // Append a second scripted response for the title call: a text reply.
    // The responder pops bodies in order; the chat stream consumed the only
    // body, so the title call gets the default "[DONE]" — empty result →
    // fallback title. That's exactly the failure path we want to verify.

    let sink = RecordingSink(Mutex::new(Vec::new()));
    run_chat_turn(
        &store,
        &StaticToolHost,
        &sink,
        &request,
        tokio_util::sync::CancellationToken::new(),
    )
    .await
    .unwrap();

    let conversation = store.get_conversation("c1").unwrap().unwrap();
    assert_eq!(conversation.title, "list the files");
    let events = sink.0.lock().unwrap();
    assert!(events
        .iter()
        .any(|e| matches!(e, AgentEvent::TitleGenerated { title } if title == "list the files")));
}

#[tokio::test]
async fn cancellation_finalizes_partial_rows() {
    let server = wiremock::MockServer::start().await;
    // Slow stream: never finishes; the test cancels mid-stream.
    wiremock::Mock::given(wiremock::matchers::method("POST"))
                .respond_with(
                    wiremock::ResponseTemplate::new(200)
                        .set_body_string(r#"data: {"choices":[{"delta":{"content":"partial"}}]}"#)
                        .set_delay(std::time::Duration::from_millis(1000)),
                )
        .mount(&server)
        .await;

    let store = seeded_store();
    let sink = RecordingSink(Mutex::new(Vec::new()));
    let cancel = tokio_util::sync::CancellationToken::new();
    let cancel_clone = cancel.clone();
    tokio::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        cancel_clone.cancel();
    });

    run_chat_turn(&store, &StaticToolHost, &sink, &turn_request(&server.uri()), cancel)
        .await
        .unwrap();

    let events = sink.0.lock().unwrap();
    eprintln!("EVENTS: {events:?}");
    assert!(matches!(events.last(), Some(AgentEvent::Cancelled)));
    // No error bubble row was written by the turn itself.
    let error_rows: Vec<_> = store
        .list_messages_by_conversation("c1")
        .unwrap()
        .into_iter()
        .filter(|m| m.status == "error")
        .collect();
    assert!(error_rows.is_empty());
}

/// The LLM client handles plain HTTP; smoke-check OpenAiClient construction
/// to keep the import used in non-test builds.
#[allow(dead_code)]
fn client_constructs() -> OpenAiClient {
    OpenAiClient::new("http://localhost", "sk")
}
