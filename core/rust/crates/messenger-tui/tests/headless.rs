/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//! Headless end-to-end proof: render fixtures through `TestBackend` and
//! drive a full agent turn (streaming text + a real tool execution) against a
//! scripted wiremock provider, asserting on the rendered buffer.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use messenger_core::agent::AgentEvent;
use messenger_store::model::{
    StoredAgent, StoredConversation, StoredMessage, StoredModel, StoredProject, StoredProvider,
};
use messenger_store::Store;
use messenger_tui::app::{App, View};
use messenger_tui::config::TuiConfig;
use messenger_tui::engine::{Engine, UiMsg};
use messenger_tui::ui;
use ratatui::backend::TestBackend;
use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ratatui::Terminal;

/// Scripted provider: pops one SSE body per request.
struct Scripted {
    bodies: Mutex<VecDeque<String>>,
}

impl wiremock::Respond for Scripted {
    fn respond(&self, _request: &wiremock::Request) -> wiremock::ResponseTemplate {
        let body = self
            .bodies
            .lock()
            .unwrap()
            .pop_front()
            .unwrap_or_else(|| "data: [DONE]\n\n".to_string());
        wiremock::ResponseTemplate::new(200)
            .insert_header("content-type", "text/event-stream")
            .set_body_string(body)
    }
}

fn text_stream(text: &str) -> String {
    let chunk = serde_json::json!({
        "choices": [{"delta": {"content": text}, "finish_reason": "stop"}]
    });
    format!(
        "data: {}\n\ndata: [DONE]\n\n",
        serde_json::to_string(&chunk).unwrap()
    )
}

fn tool_call_stream(name: &str, call_id: &str, arguments: &str) -> String {
    let chunk = serde_json::json!({
        "choices": [{
            "delta": {
                "tool_calls": [{
                    "index": 0,
                    "id": call_id,
                    "type": "function",
                    "function": {"name": name, "arguments": arguments}
                }]
            },
            "finish_reason": "tool_calls"
        }]
    });
    format!(
        "data: {}\n\n",
        serde_json::to_string(&chunk).unwrap()
    )
}

fn seed_store(base_url: &str, workspace: &std::path::Path) -> Arc<Store> {
    let store = Arc::new(Store::open_memory().unwrap());
    store
        .upsert_provider(&StoredProvider {
            id: "p1".into(),
            name: "test".into(),
            base_url: base_url.to_string(),
            api_key: "sk".into(),
            created_at: 1,
            updated_at: 1,
        })
        .unwrap();
    store
        .upsert_model(&StoredModel {
            id: "p1:test-model".into(),
            provider_id: "p1".into(),
            model_id: "test-model".into(),
            display_name: "test-model".into(),
            is_enabled: true,
            context_window: 0,
            input_rate: None,
            output_rate: None,
            input_modalities: "text".into(),
            output_modalities: "text".into(),
            supports_tool_calling: true,
            supports_thinking: false,
            supports_json_output: false,
            supports_temperature: true,
            created_at: 1,
        })
        .unwrap();
    store
        .upsert_agent(&StoredAgent {
            id: "a1".into(),
            name: "agent".into(),
            avatar: None,
            system_prompt: "be helpful".into(),
            description: String::new(),
            default_model_id: Some("p1:test-model".into()),
            temperature: Some(0.7),
            top_p: Some(1.0),
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
            tools_config: String::new(),
            created_at: 1,
            updated_at: 1,
        })
        .unwrap();
    // The streaming turn below calls `terminal`, which is workspace-bound:
    // the conversation must belong to a project for the tool to exist.
    store
        .upsert_project(&StoredProject {
            id: "proj1".into(),
            name: "Test project".into(),
            workspace: workspace.to_string_lossy().to_string(),
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
            project_id: Some("proj1".into()),
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
    store.kv_set("current_agent_id", "a1").unwrap();
    let _ = workspace;
    store
}

fn app_with(
    store: Arc<Store>,
    workspace: &std::path::Path,
) -> (
    App,
    Arc<Engine>,
    tokio::sync::mpsc::UnboundedReceiver<UiMsg>,
    tokio::runtime::Runtime,
) {
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
    // The real binary owns a runtime and hands its handle to the Engine;
    // the app is driven from this thread, which has NO runtime context, so
    // any `tokio::spawn` reachable from a key press panics without this.
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap();
    let engine = Arc::new(Engine::new(
        store,
        tx,
        workspace.to_path_buf(),
        runtime.handle().clone(),
    ));
    let dir = tempfile::tempdir().unwrap();
    let app = App::new(
        Arc::clone(&engine),
        TuiConfig {
            workspace_dir: workspace.to_string_lossy().to_string(),
            ..TuiConfig::default()
        },
        dir.path().join("settings.toml"),
        dir.path().join("store.db"),
    );
    (app, engine, rx, runtime)
}

fn buffer_text(terminal: &Terminal<TestBackend>) -> String {
    let buffer = terminal.backend().buffer();
    let width = buffer.area.width as usize;
    let cells = buffer.content();
    cells
        .chunks(width)
        .map(|row| row.iter().map(|cell| cell.symbol()).collect::<String>())
        .collect::<Vec<_>>()
        .join("\n")
}

/// Drive the app until `predicate` holds or the deadline passes.
fn pump(
    terminal: &mut Terminal<TestBackend>,
    app: &mut App,
    rx: &mut tokio::sync::mpsc::UnboundedReceiver<UiMsg>,
    predicate: impl Fn(&App) -> bool,
    timeout: Duration,
) -> bool {
    let deadline = std::time::Instant::now() + timeout;
    while std::time::Instant::now() < deadline {
        terminal.draw(|frame| ui::draw(frame, app)).unwrap();
        while let Ok(msg) = rx.try_recv() {
            app.apply(msg);
        }
        app.tick();
        if predicate(app) {
            return true;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    terminal.draw(|frame| ui::draw(frame, app)).unwrap();
    false
}

#[test]
fn writable_mode_toggle_persists_per_conversation() {
    let store = Arc::new(Store::open_memory().unwrap());
    messenger_tui::store_ops::ensure_default_agent(&store).unwrap();
    let dir = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, dir.path());
    app.view = View::Chat;
    app.handle_key(KeyEvent::new(KeyCode::Char('n'), KeyModifiers::CONTROL));
    app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
    let conversation_id = app.chat.conversation_id.clone().expect("conversation created");

    assert!(!app.conversation_writable());
    app.handle_key(KeyEvent::new(KeyCode::Char('w'), KeyModifiers::CONTROL));
    assert!(app.conversation_writable(), "Ctrl+W must flip the mode on");
    assert!(app.status.contains("writable"), "{}", app.status);

    let row = app
        .engine
        .store
        .get_conversation(&conversation_id)
        .unwrap()
        .unwrap();
    assert!(row.writable, "the toggle must be persisted on the conversation row");

    app.handle_key(KeyEvent::new(KeyCode::Char('w'), KeyModifiers::CONTROL));
    assert!(!app.conversation_writable(), "Ctrl+W must flip the mode back");
    // A plain `w` must be typed into the message, not treated as a command.
    app.handle_key(KeyEvent::new(KeyCode::Char('w'), KeyModifiers::NONE));
    assert_eq!(app.chat.input, "w");
}

#[test]
fn writable_mode_changes_the_declared_tool_set() {
    // The mode gates declarations, not execution: read-only drops edit/create.
    let mut agent = StoredAgent {
        id: "a1".into(),
        name: "agent".into(),
        avatar: None,
        system_prompt: "sp".into(),
        description: String::new(),
        default_model_id: None,
        temperature: None,
        top_p: None,
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
        tools_config: String::new(),
        created_at: 1,
        updated_at: 1,
    };
    let mut conversation = StoredConversation {
        id: "c1".into(),
        agent_id: "a1".into(),
        writable: false,
        ..StoredConversation::default()
    };

    let read_only = messenger_tui::store_ops::resolve_tools(&agent, &conversation, &[], true);
    let names: Vec<&str> = read_only.iter().map(|tool| tool.name.as_str()).collect();
    assert!(names.contains(&"terminal") && names.contains(&"read"));
    assert!(!names.contains(&"edit") && !names.contains(&"create"));

    conversation.writable = true;
    let writable = messenger_tui::store_ops::resolve_tools(&agent, &conversation, &[], true);
    let names: Vec<&str> = writable.iter().map(|tool| tool.name.as_str()).collect();
    assert!(names.contains(&"edit") && names.contains(&"create"));

    agent.tools_enabled = false;
    assert!(messenger_tui::store_ops::resolve_tools(&agent, &conversation, &[], true).is_empty());

    // A conversation outside any project has no workspace, so none of the
    // workspace-bound tools are declared — the master switch being on does not
    // bring them back.
    agent.tools_enabled = true;
    let without_project = messenger_tui::store_ops::resolve_tools(&agent, &conversation, &[], false);
    assert!(
        without_project.is_empty(),
        "plain conversation leaked {:?}",
        without_project
    );
}

#[test]
fn model_fallback_order_and_follow_merge() {
    use messenger_tui::store_ops::{resolve_effective_agent, resolve_turn, TurnError};

    let store = Arc::new(Store::open_memory().unwrap());
    store
        .upsert_provider(&StoredProvider {
            id: "p1".into(),
            name: "p".into(),
            base_url: "https://example/v1".into(),
            api_key: "k".into(),
            created_at: 1,
            updated_at: 1,
        })
        .unwrap();
    store
        .upsert_model(&StoredModel {
            id: "p1:m".into(),
            provider_id: "p1".into(),
            model_id: "m".into(),
            display_name: "m".into(),
            is_enabled: true,
            context_window: 1000,
            input_rate: None,
            output_rate: None,
            input_modalities: "text".into(),
            output_modalities: "text".into(),
            supports_tool_calling: false,
            supports_thinking: false,
            supports_json_output: false,
            supports_temperature: true,
            created_at: 1,
        })
        .unwrap();
    let mut agent = StoredAgent {
        id: "a1".into(),
        name: "default".into(),
        avatar: None,
        system_prompt: "default prompt".into(),
        description: String::new(),
        default_model_id: Some("p1:m".into()),
        temperature: Some(0.2),
        top_p: None,
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
        tools_enabled: false,
        tools_follow_default: false,
        tools_config: String::new(),
        created_at: 1,
        updated_at: 1,
    };
    store.upsert_agent(&agent).unwrap();
    store
        .upsert_conversation(&StoredConversation {
            id: "c1".into(),
            title: "t".into(),
            provider_id: "p1".into(),
            agent_id: "a1".into(),
            ..StoredConversation::default()
        })
        .unwrap();

    // The Agent's own model wins.
    let resolved = resolve_turn(&store, "c1", &[], None).unwrap();
    assert_eq!(resolved.request.model_id, "m");
    assert_eq!(resolved.request.context_window, 1000);
    assert!(resolved.request.tools.is_empty(), "tools off by default");

    // A follower inherits the default Agent's fields.
    agent.id = "a2".into();
    agent.name = "follower".into();
    agent.is_default = false;
    agent.system_prompt = "own prompt".into();
    agent.default_model_id = None;
    agent.temperature = Some(0.9);
    agent.follow_default_system_prompt = true;
    agent.follow_default_model = true;
    agent.follow_default_temperature = true;
    agent.follow_default_max_tokens = true;
    store.upsert_agent(&agent).unwrap();
    let default = store.get_default_agent().unwrap().unwrap();
    let merged = resolve_effective_agent(agent.clone(), None, Some(&default));
    assert_eq!(merged.system_prompt, "default prompt");
    assert_eq!(merged.default_model_id.as_deref(), Some("p1:m"));
    assert_eq!(merged.temperature, Some(0.2));

    // A conversation override beats both.
    store
        .upsert_conversation(&StoredConversation {
            id: "c2".into(),
            title: "t".into(),
            provider_id: "p1".into(),
            agent_id: "a2".into(),
            override_temperature: Some(1.5),
            override_max_tokens: Some(64),
            ..StoredConversation::default()
        })
        .unwrap();
    let resolved = resolve_turn(&store, "c2", &[], None).unwrap();
    assert_eq!(resolved.request.temperature, Some(1.5));
    assert_eq!(resolved.request.max_tokens, Some(64));

    // No model anywhere → a stable, user-facing error.
    store.delete_model("p1:m").unwrap();
    assert_eq!(
        resolve_turn(&store, "c1", &[], None).unwrap_err(),
        TurnError::ModelNotConfigured
    );
}

#[test]
fn builtin_cloud_provider_falls_back_to_the_account_ai_key() {
    use messenger_tui::store_ops::resolve_turn;

    let store = Arc::new(Store::open_memory().unwrap());
    store
        .upsert_provider(&StoredProvider {
            id: messenger_sync::BUILTIN_PROVIDER_ID.into(),
            name: "Messenger Cloud AI".into(),
            base_url: "https://cloud/v1".into(),
            api_key: String::new(),
            created_at: 1,
            updated_at: 1,
        })
        .unwrap();
    store
        .upsert_model(&StoredModel {
            id: "m".into(),
            provider_id: messenger_sync::BUILTIN_PROVIDER_ID.into(),
            model_id: "cloud-model".into(),
            display_name: "cloud-model".into(),
            is_enabled: true,
            context_window: 0,
            input_rate: None,
            output_rate: None,
            input_modalities: "text".into(),
            output_modalities: "text".into(),
            supports_tool_calling: false,
            supports_thinking: false,
            supports_json_output: false,
            supports_temperature: false,
            created_at: 1,
        })
        .unwrap();
    store
        .upsert_agent(&StoredAgent {
            id: "a1".into(),
            name: "a".into(),
            avatar: None,
            system_prompt: "sp".into(),
            description: String::new(),
            default_model_id: Some("m".into()),
            temperature: None,
            top_p: None,
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
            tools_enabled: false,
            tools_follow_default: false,
            tools_config: String::new(),
            created_at: 1,
            updated_at: 1,
        })
        .unwrap();
    store
        .upsert_conversation(&StoredConversation {
            id: "c1".into(),
            agent_id: "a1".into(),
            ..StoredConversation::default()
        })
        .unwrap();

    let resolved = resolve_turn(&store, "c1", &[], Some("sk-account")).unwrap();
    assert_eq!(resolved.request.api_key, "sk-account");
}

#[test]
fn every_block_variant_renders_into_the_buffer() {
    let store = Arc::new(Store::open_memory().unwrap());
    let dir = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, dir.path());

    let markdown = "# Title\n\nHello **world**\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```rust\nfn main() {}\n```\n\n- [x] done\n\n> quote\n";
    let blocks = messenger_tui::render::parse_blocks(markdown);
    // Push the blocks through the chat transcript so the real ui draw path
    // renders them (not just the render helper).
    app.chat.conversation_id = Some("c1".into());
    app.chat.messages = vec![StoredMessage {
        id: "m1".into(),
        conversation_id: "c1".into(),
        role: "assistant".into(),
        content: markdown.into(),
        parts_json: messenger_core::parts::encode_parts(&[
            messenger_llm::domain::ContentPart::Text { text: markdown.into() },
        ]),
        timestamp: 1,
        status: "sent".into(),
        error_message: None,
    }];
    app.view = View::Chat;
    let _ = blocks;

    let backend = TestBackend::new(100, 30);
    let mut terminal = Terminal::new(backend).unwrap();
    terminal.draw(|frame| ui::draw(frame, &mut app)).unwrap();
    let text = buffer_text(&terminal);
    assert!(text.contains("Title"), "{text}");
    assert!(text.contains("world"), "{text}");
    assert!(text.contains("fn main()"), "{text}");
    assert!(text.contains("[x] done"), "{text}");
    assert!(text.contains("quote"), "{text}");
    assert!(text.contains("│ a │ b │"), "{text}");
}

#[test]
fn streaming_turn_puts_the_text_and_the_tool_card_in_the_buffer() {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap();
    let server = runtime.block_on(wiremock::MockServer::start());
    runtime.block_on(async {
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .respond_with(Scripted {
                bodies: Mutex::new(VecDeque::from(vec![
                    tool_call_stream("terminal", "call_1", "{\"command\":\"echo hi\"}"),
                    text_stream("All done."),
                ])),
            })
            .mount(&server)
            .await;
    });

    let _dir = tempfile::tempdir().unwrap();
    let workspace = tempfile::tempdir().unwrap();
    let store = seed_store(&server.uri(), workspace.path());
    let (mut app, _engine, mut rx, _app_runtime) = app_with(store, workspace.path());
    app.reload_all();
    app.open_conversation("c1");
    app.chat.input = "run echo".into();

    let backend = TestBackend::new(120, 40);
    let mut terminal = Terminal::new(backend).unwrap();

    // Drive the send through the real key path — deliberately WITHOUT entering
    // a runtime context, exactly like the real event loop.
    app.send_message();

    let finished = pump(
        &mut terminal,
        &mut app,
        &mut rx,
        |app| !app.chat.is_generating && app.status.contains("finished"),
        Duration::from_secs(20),
    );
    // Give the last events a moment to land, then re-render.
    std::thread::sleep(Duration::from_millis(200));
    while let Ok(msg) = rx.try_recv() {
        app.apply(msg);
    }
    terminal.draw(|frame| ui::draw(frame, &mut app)).unwrap();
    let text = buffer_text(&terminal);

    // The real NativeToolHost ran `echo hi`; the tool row must carry it.
    let messages = app
        .engine
        .store
        .list_messages_by_conversation("c1")
        .unwrap();
    let tool_row = messages
        .iter()
        .find(|message| message.role == "tool")
        .expect("a tool row must be persisted");
    let parts = messenger_core::parts::decode_parts(tool_row.parts_json.as_deref());
    let output = parts
        .iter()
        .find_map(|part| match part {
            messenger_llm::domain::ContentPart::ToolResult { output, .. } => Some(output.clone()),
            _ => None,
        })
        .expect("tool result part");
    assert!(output.contains("hi"), "tool output was {output:?}");

    let final_row = messages
        .iter()
        .find(|message| message.role == "assistant" && message.content.contains("All done."))
        .expect("final assistant row");
    assert_eq!(final_row.status, "sent");

    assert!(text.contains("All done."), "buffer did not show the reply:\n{text}");
    assert!(text.contains("terminal"), "buffer did not show the tool card:\n{text}");
    assert!(
        text.contains("run echo"),
        "buffer did not show the user message:\n{text}"
    );
    assert!(finished || app.chat.messages.len() >= 4, "turn did not settle: {}", app.status);
}

#[test]
fn key_path_switches_views_opens_help_and_toggles_think() {
    let store = Arc::new(Store::open_memory().unwrap());
    let dir = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, dir.path());

    app.handle_key(KeyEvent::new(KeyCode::F(6), KeyModifiers::NONE));
    assert_eq!(app.view, View::Settings);
    app.handle_key(KeyEvent::new(KeyCode::F(1), KeyModifiers::NONE));
    assert_eq!(app.view, View::Conversations);

    app.handle_key(KeyEvent::new(KeyCode::Char('?'), KeyModifiers::NONE));
    assert!(app.help);
    app.handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));
    assert!(!app.help);

    let before = app.config.show_think;
    app.handle_key(KeyEvent::new(KeyCode::Char('t'), KeyModifiers::CONTROL));
    assert_ne!(app.config.show_think, before);

    app.handle_key(KeyEvent::new(KeyCode::Char('c'), KeyModifiers::CONTROL));
    assert!(app.should_quit);
}

#[test]
fn chat_key_path_edits_and_sends_input() {
    let store = Arc::new(Store::open_memory().unwrap());
    // A conversation with an Agent but no model: sending must abort with the
    // model-setup message and leave the transcript untouched.
    store
        .upsert_agent(&StoredAgent {
            id: "a1".into(),
            name: "agent".into(),
            avatar: None,
            system_prompt: "be helpful".into(),
            description: String::new(),
            default_model_id: None,
            temperature: None,
            top_p: None,
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
            tools_enabled: false,
            tools_follow_default: false,
            tools_config: String::new(),
            created_at: 1,
            updated_at: 1,
        })
        .unwrap();
    store
        .upsert_conversation(&StoredConversation {
            id: "c1".into(),
            title: "t".into(),
            provider_id: String::new(),
            agent_id: "a1".into(),
            ..StoredConversation::default()
        })
        .unwrap();
    let dir = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, dir.path());
    app.view = View::Chat;
    app.chat.conversation_id = Some("c1".into());

    for ch in "hello".chars() {
        app.handle_key(KeyEvent::new(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    assert_eq!(app.chat.input, "hello");
    app.handle_key(KeyEvent::new(KeyCode::Backspace, KeyModifiers::NONE));
    assert_eq!(app.chat.input, "hell");

    app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
    assert_eq!(app.chat.input, "hell");
    assert!(
        app.status.contains("Set a model"),
        "unexpected status: {}",
        app.status
    );
    // The aborted send must not have inserted a user message.
    assert!(app
        .engine
        .store
        .list_messages_by_conversation("c1")
        .unwrap()
        .is_empty());

    // Alt+Enter inserts a newline instead of sending.
    app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::ALT));
    assert_eq!(app.chat.input, "hell\n");
}

#[test]
fn agents_and_settings_views_render_their_rows() {
    let store = Arc::new(Store::open_memory().unwrap());
    messenger_tui::store_ops::ensure_default_agent(&store).unwrap();
    let dir = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, dir.path());

    let backend = TestBackend::new(100, 24);
    let mut terminal = Terminal::new(backend).unwrap();

    app.view = View::Agents;
    terminal.draw(|frame| ui::draw(frame, &mut app)).unwrap();
    let text = buffer_text(&terminal);
    assert!(text.contains("Agents"), "{text}");
    // Wide CJK glyphs occupy two buffer cells (with an empty trailing cell),
    // so match the ASCII parts and the default badge.
    assert!(text.contains("Agent [default]"), "{text}");
    assert!(text.contains("tools:off"), "{text}");

    app.view = View::Settings;
    terminal.draw(|frame| ui::draw(frame, &mut app)).unwrap();
    let text = buffer_text(&terminal);
    assert!(text.contains("Settings"), "{text}");
    assert!(text.contains("Theme"), "{text}");
    assert!(text.contains("Workspace directory"), "{text}");
    assert!(text.contains("Signed out"), "{text}");

    app.view = View::Providers;
    terminal.draw(|frame| ui::draw(frame, &mut app)).unwrap();
    let text = buffer_text(&terminal);
    assert!(text.contains("Providers"), "{text}");
    assert!(text.contains("Models"), "{text}");
}

#[test]
fn providers_fetch_models_spawns_without_a_runtime_context() {
    // Regression: `s` in the Providers view used to call `tokio::spawn`
    // straight from the UI loop, which has no reactor context — the binary
    // panicked with "there is no reactor running".
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap();
    let server = runtime.block_on(wiremock::MockServer::start());
    runtime.block_on(async {
        wiremock::Mock::given(wiremock::matchers::method("GET"))
            .respond_with(
                wiremock::ResponseTemplate::new(200).set_body_json(serde_json::json!({
                    "data": [
                        {"id": "m-alpha", "context_window": 128000},
                        {"id": "m-beta"}
                    ]
                })),
            )
            .mount(&server)
            .await;
    });

    let workspace = tempfile::tempdir().unwrap();
    let store = seed_store(&server.uri(), workspace.path());
    let (mut app, _engine, mut rx, _app_runtime) = app_with(store, workspace.path());
    app.reload_all();
    app.view = View::Providers;

    // No `runtime.enter()`: exactly the state the real event loop is in.
    app.handle_key(KeyEvent::new(KeyCode::Char('s'), KeyModifiers::NONE));
    assert!(app.status.contains("Fetching"), "{}", app.status);

    let done = pump(
        &mut Terminal::new(TestBackend::new(100, 24)).unwrap(),
        &mut app,
        &mut rx,
        |app| app.status.contains("models fetched") || app.status.contains("fetch failed"),
        Duration::from_secs(20),
    );
    assert!(done, "the fetch never reported back: {}", app.status);

    let models = app
        .engine
        .store
        .list_models_by_provider("p1")
        .unwrap();
    // The pre-existing local row is kept (the fetch upserts, it does not
    // prune), and the two reported models land as disabled rows.
    assert_eq!(models.len(), 3, "{models:?}");
    // Existing rows keep their enabled flag; new rows default to disabled.
    let alpha = models.iter().find(|m| m.model_id == "m-alpha").unwrap();
    assert!(!alpha.is_enabled);
    assert_eq!(alpha.context_window, 128000);
    let pre_existing = models.iter().find(|m| m.model_id == "test-model").unwrap();
    assert!(pre_existing.is_enabled, "the local model row must survive the sync");
}

#[test]
fn headless_app_render_of_the_smoke_seed_contains_the_agent_loop_events() {
    // A pure-render check that an `AgentEvent` sequence lands in the view:
    // streaming deltas feed the Document session, and the rendered live tail
    // shows the parsed blocks.
    let store = Arc::new(Store::open_memory().unwrap());
    let dir = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, dir.path());
    app.view = View::Chat;
    app.chat.conversation_id = Some("c1".into());

    app.apply(UiMsg::Agent(AgentEvent::TurnStarted));
    app.apply(UiMsg::Agent(AgentEvent::StreamingStarted {
        message_id: "m-live".into(),
    }));
    app.apply(UiMsg::Agent(AgentEvent::TextDelta {
        round: 1,
        text: "# Live heading\n\nstreaming ".into(),
    }));
    app.apply(UiMsg::Agent(AgentEvent::TextDelta {
        round: 1,
        text: "body text\n".into(),
    }));

    let backend = TestBackend::new(80, 20);
    let mut terminal = Terminal::new(backend).unwrap();
    terminal.draw(|frame| ui::draw(frame, &mut app)).unwrap();
    let text = buffer_text(&terminal);
    assert!(text.contains("Live heading"), "{text}");
    assert!(text.contains("body text"), "{text}");

    app.apply(UiMsg::Agent(AgentEvent::Finished {
        message_id: "m-live".into(),
    }));
    assert!(!app.chat.is_generating);
}
/// Drives the real F1 key path: `p` opens the project form, typing a name and
/// confirming stores a project whose workspace defaults to the process CWD,
/// and it shows up in the rendered Projects section. This is the whole TUI
/// project flow exercised through the same input a user drives.
#[test]
fn the_project_form_stores_a_project_with_the_cwd_as_its_workspace() {
    let workspace = tempfile::tempdir().unwrap();
    let store = Arc::new(Store::open_memory().unwrap());
    let (mut app, _engine, _rx, _runtime) = app_with(store.clone(), workspace.path());

    // Tab crosses the projects/conversations boundary, so the fixture needs a
    // plain conversation (one with no project) as the other block.
    seed_store("http://localhost:1", workspace.path());
    store
        .upsert_provider(&StoredProvider {
            id: "p1".into(),
            name: "test".into(),
            base_url: "http://localhost:1".into(),
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
            system_prompt: "sp".into(),
            description: String::new(),
            default_model_id: None,
            temperature: None,
            top_p: None,
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
            tools_config: String::new(),
            created_at: 1,
            updated_at: 1,
        })
        .unwrap();
    store
        .upsert_conversation(&StoredConversation {
            id: "plain".into(),
            title: "Plain chat".into(),
            provider_id: "p1".into(),
            agent_id: "a1".into(),
            project_id: None,
            created_at: 1,
            updated_at: 1,
            last_message: None,
            ..StoredConversation::default()
        })
        .unwrap();
    store.kv_set("current_agent_id", "a1").unwrap();
    app.reload_all();

    // F1 (the app starts there), then `p` opens the New project form.
    app.handle_key(KeyEvent::new(KeyCode::F(1), KeyModifiers::NONE));
    app.handle_key(KeyEvent::new(KeyCode::Char('p'), KeyModifiers::NONE));
    let form = app.form.clone().expect("`p` opens the new-project form");
    assert!(matches!(form.purpose, messenger_tui::app::FormPurpose::NewProject));

    // Type a name, tab to the workspace field (left empty on purpose), confirm.
    for ch in "Messenger".chars() {
        app.handle_key(KeyEvent::new(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE)); // next field
    app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE)); // submit (workspace empty)

    let projects = store.list_projects().unwrap();
    assert_eq!(projects.len(), 1, "the form must persist the project");
    assert_eq!(projects[0].name, "Messenger");
    assert_eq!(
        projects[0].workspace,
        std::env::current_dir().unwrap().to_string_lossy(),
        "an empty workspace defaults to the process CWD"
    );

    // The row is listed under a Projects header in the rendered list.
    let backend = TestBackend::new(80, 20);
    let mut terminal = Terminal::new(backend).unwrap();
    terminal.draw(|frame| ui::draw(frame, &mut app)).unwrap();
    let text = buffer_text(&terminal);
    assert!(text.contains("Projects"), "{text}");
    assert!(text.contains("Messenger"), "{text}");

    // Tab moves the selection into the projects block, and Enter on a project
    // opens the Agent picker with that project staged (the conversation is
    // created once an Agent is picked).
    app.handle_key(KeyEvent::new(KeyCode::Tab, KeyModifiers::NONE));
    assert!(app.list_showing_projects);
    app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
    assert_eq!(
        app.pending_project.as_deref(),
        Some(projects[0].id.as_str()),
        "Enter on a project row stages it for the picker"
    );
}
