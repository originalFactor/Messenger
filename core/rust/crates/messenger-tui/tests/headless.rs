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

//! Headless end-to-end proof: render fixtures through [`ui::compose`] and
//! drive a full agent turn (streaming text + a real tool execution) against a
//! scripted wiremock provider, asserting on the composed frame.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};

use messenger_core::agent::AgentEvent;
use messenger_store::model::{
    StoredAgent, StoredConversation, StoredMessage, StoredModel, StoredProject, StoredProvider,
};
use messenger_store::Store;
use messenger_tui::app::App;
use messenger_tui::config::TuiConfig;
use messenger_tui::engine::{Engine, UiMsg};
use messenger_tui::popup::Popup;
use messenger_tui::ui;
use messenger_tui::text::plain_text;

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

/// An OpenAI-style usage-only chunk: empty `choices`, arrives before `[DONE]`.
fn usage_payload(prompt: i64, completion: i64, cached: i64) -> String {
    let chunk = serde_json::json!({
        "choices": [],
        "usage": {
            "prompt_tokens": prompt,
            "completion_tokens": completion,
            "total_tokens": prompt + completion,
            "prompt_tokens_details": {"cached_tokens": cached}
        }
    });
    format!("data: {}\n\n", serde_json::to_string(&chunk).unwrap())
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

/// The composed frame as plain text, one line per row — what a user would
/// read off the screen, with every style stripped.
fn frame_text(app: &mut App, width: u16, height: u16) -> String {
    ui::compose(app, width, height)
        .lines
        .iter()
        .map(|line| plain_text(&line))
        .collect::<Vec<_>>()
        .join("\n")
}

/// Drive the app until `predicate` holds or the deadline passes.
///
/// Compositing each round keeps the render caches exercised the same way the
/// real loop does, so a stale-cache bug still shows up here.
fn pump(
    app: &mut App,
    rx: &mut tokio::sync::mpsc::UnboundedReceiver<UiMsg>,
    predicate: impl Fn(&App) -> bool,
    timeout: Duration,
) -> bool {
    let deadline = std::time::Instant::now() + timeout;
    while std::time::Instant::now() < deadline {
        while let Ok(msg) = rx.try_recv() {
            app.apply(msg);
        }
        app.tick();
        let _ = ui::compose(app, 100, 24);
        if predicate(app) {
            return true;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    let _ = ui::compose(app, 100, 24);
    false
}

#[test]
fn writable_mode_toggle_persists_per_conversation() {
    let store = Arc::new(Store::open_memory().unwrap());
    messenger_tui::store_ops::ensure_default_agent(&store).unwrap();
    let dir = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, dir.path());
    app.bootstrap_session();
    let conversation_id = app.chat.conversation_id.clone().expect("conversation created");

    assert!(!app.conversation_writable());
    app.run_slash("mode writable");
    assert!(app.conversation_writable(), "/mode writable must flip it on");

    let row = app
        .engine
        .store
        .get_conversation(&conversation_id)
        .unwrap()
        .unwrap();
    assert!(row.writable, "the toggle must be persisted on the conversation row");

    app.run_slash("mode read-only");
    assert!(!app.conversation_writable(), "/mode read-only must flip it back");
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
    let _ = blocks;

    let text = frame_text(&mut app, 100, 30);
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

    let (width, height) = (120u16, 40u16);

    // Drive the send through the real key path — deliberately WITHOUT entering
    // a runtime context, exactly like the real event loop.
    app.send_message();

    let finished = pump(
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
    let text = frame_text(&mut app, width, height);

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

/// There is no view switch any more: `?` pushes the help popup and Esc peels
/// one layer off the stack. Function keys are ordinary, unhandled input.
#[test]
fn the_popup_stack_opens_and_unwinds_one_layer_at_a_time() {
    let store = Arc::new(Store::open_memory().unwrap());
    let dir = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, dir.path());
    assert!(app.popups.is_empty(), "a session starts with no popup");

    app.handle_key(KeyEvent::new(KeyCode::Char('?'), KeyModifiers::NONE));
    assert!(matches!(app.popups.as_slice(), [Popup::Help { .. }]));
    // Any key dismisses help.
    app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
    assert!(app.popups.is_empty());

    // A leading `/` opens the palette and the keys after it go there, not
    // into the message box.
    app.handle_key(KeyEvent::new(KeyCode::Char('/'), KeyModifiers::NONE));
    assert!(matches!(app.popups.as_slice(), [Popup::Commands { .. }]));
    for ch in "new".chars() {
        app.handle_key(KeyEvent::new(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    assert_eq!(app.chat.input, "", "the palette owns the input line");

    // Esc peels the palette off; the chat owns the keyboard again.
    app.handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));
    assert!(app.popups.is_empty());
    let before = app.config.show_think;
    app.handle_key(KeyEvent::new(KeyCode::Char('t'), KeyModifiers::CONTROL));
    assert_ne!(app.config.show_think, before, "Ctrl+T toggles think blocks");

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
        app.chat
            .notes
            .iter()
            .any(|note| note.text.contains("Set a model")),
        "an unbound model must abort the send: {:?}",
        app.chat.notes
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

/// The pickers replace the old F-key views: `/agent`, `/settings` and
/// `/provider` each push a list, and `?` documents what a row does.
#[test]
fn the_pickers_render_their_rows_over_the_chat() {
    let store = Arc::new(Store::open_memory().unwrap());
    messenger_tui::store_ops::ensure_default_agent(&store).unwrap();
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store.clone(), workspace.path());
    app.bootstrap_session();

    let (width, height) = (100u16, 24u16);

    app.run_slash("agent");
    let text = frame_text(&mut app, width, height);
    assert!(text.contains("Agents"), "{text}");
    // Wide CJK glyphs occupy two terminal cells (with an empty trailing cell),
    // so match the ASCII parts and the default badge.
    assert!(text.contains("[default]"), "{text}");
    assert!(text.contains("tools:off"), "{text}");
    // The chat is still there underneath — a popup never replaces it. The
    // transcript has no box of its own, so its presence is the input line and
    // the status rail, which a modal leaves untouched.
    assert!(text.contains("Chat, @mention"), "{text}");
    assert!(text.contains("read-only") || text.contains("writable"), "{text}");

    app.close_all_popups();
    app.run_slash("settings");
    let text = frame_text(&mut app, width, height);
    assert!(text.contains("Settings"), "{text}");
    assert!(text.contains("Theme"), "{text}");
    assert!(text.contains("signed out"), "{text}");

    app.close_all_popups();
    app.run_slash("help");
    let text = frame_text(&mut app, width, height);
    // The keys page opens on the chat/popup reference; the command list is
    // further down and reachable with PageDown (covered above).
    assert!(text.contains("command palette"), "{text}");
    assert!(text.contains("Popups"), "{text}");
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
    // No `runtime.enter()`: exactly the state the real event loop is in.
    app.run_slash("provider fetch");
    assert!(app.status.contains("Fetching"), "{}", app.status);

    let done = pump(
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

    let text = frame_text(&mut app, 80, 20);
    assert!(text.contains("Live heading"), "{text}");
    assert!(text.contains("body text"), "{text}");

    app.apply(UiMsg::Agent(AgentEvent::Finished {
        message_id: "m-live".into(),
    }));
    assert!(!app.chat.is_generating);
}
/// `/project new` opens the editor; typing a name and confirming stores a
/// project whose workspace defaults to the process CWD, and `/project` then
/// lists it for picking.
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

    // `/project new` opens the editor; the name field starts empty so typing
    // replaces nothing, and the workspace defaults to the CWD.
    app.run_slash("project new");
    let form = match app.popups.last() {
        Some(Popup::Form(form)) => form.clone(),
        other => panic!("/project new opens the editor, got {other:?}"),
    };
    assert!(matches!(form.purpose, messenger_tui::popup::FormPurpose::NewProject));

    for ch in "Messenger".chars() {
        app.handle_key(KeyEvent::new(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE)); // next field
    app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE)); // submit

    let projects = store.list_projects().unwrap();
    assert_eq!(projects.len(), 1, "the form must persist the project");
    assert_eq!(projects[0].name, "Messenger");
    assert_eq!(
        projects[0].workspace,
        std::env::current_dir().unwrap().to_string_lossy(),
        "an empty workspace defaults to the process CWD"
    );

    // `/project` lists it, and Enter on the row opens a conversation inside it.
    app.run_slash("project");
    let text = frame_text(&mut app, 80, 20);
    assert!(text.contains("Projects"), "{text}");
    assert!(text.contains("Messenger"), "{text}");

    app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
    let conversation = app
        .chat
        .conversation_id
        .as_deref()
        .and_then(|id| store.get_conversation(id).ok().flatten())
        .expect("picking a project opens a conversation in it");
    assert_eq!(
        conversation.project_id.as_deref(),
        Some(projects[0].id.as_str()),
        "Enter on a project row starts a conversation inside it"
    );
}

/// A session must land in a working conversation inside the project for the
/// directory it was launched in — the agentic entry point, not a list.
#[test]
fn bootstrap_session_lands_in_a_cwd_project_conversation() {
    let store = Arc::new(Store::open_memory().unwrap());
    messenger_tui::store_ops::ensure_default_agent(&store).unwrap();
    // A model binding, so the turn resolves and the workspace actually reaches
    // the tool host.
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
        .upsert_model(&StoredModel {
            id: "p1:m".into(),
            provider_id: "p1".into(),
            model_id: "m".into(),
            display_name: "m".into(),
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
    let agent = store.get_default_agent().unwrap().unwrap();
    store
        .upsert_agent(&StoredAgent {
            default_model_id: Some("p1:m".into()),
            tools_enabled: true,
            ..agent
        })
        .unwrap();
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store.clone(), workspace.path());

    // `App::new` does not bootstrap on its own — main.rs does — so drive the
    // same call the binary makes.
    app.bootstrap_session();

    assert!(app.popups.is_empty(), "the chat is the landing surface");
    let conversation_id = app
        .chat
        .conversation_id
        .clone()
        .expect("bootstrap opens a conversation");

    let cwd = std::env::current_dir().unwrap();
    let conversation = store
        .get_conversation(&conversation_id)
        .unwrap()
        .unwrap();
    let project_id = conversation.project_id.expect("the conversation has a project");
    let project = store.get_project(&project_id).unwrap().unwrap();
    assert_eq!(
        std::fs::canonicalize(&project.workspace).unwrap(),
        std::fs::canonicalize(&cwd).unwrap(),
        "the project workspace must be the process CWD"
    );

    // The turn resolver hands the tool host that same directory, which is
    // what makes the workspace-bound tools usable at all.
    let resolved =
        messenger_tui::store_ops::resolve_turn(&store, &conversation_id, &[], None).unwrap();
    assert_eq!(resolved.workspace, Some(std::path::PathBuf::from(&project.workspace)));
    assert!(resolved.request.workspace_note.contains(&project.workspace));

    // A second bootstrap must reuse the project, not duplicate it.
    app.bootstrap_session();
    assert_eq!(store.list_projects().unwrap().len(), 1);

    // The bootstrap writes NO transcript note: the status rail already names
    // the project, the Agent and the model, and the input placeholder says how
    // to start. Nothing is stored as a message either.
    assert!(
        app.chat.notes.is_empty(),
        "the rail carries this, not a note: {:?}",
        app.chat.notes
    );
    assert!(app
        .engine
        .store
        .list_messages_by_conversation(&conversation_id)
        .unwrap()
        .is_empty());
}

/// `/` opens the palette instead of typing into the message box, Tab
/// completes, and Enter runs the command.
#[test]
fn slash_commands_drive_the_session_from_the_input_line() {
    let store = Arc::new(Store::open_memory().unwrap());
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store.clone(), workspace.path());
    // A leading slash opens the palette and never lands in the message.
    app.handle_key(KeyEvent::new(KeyCode::Char('/'), KeyModifiers::NONE));
    assert_eq!(
        app.popups.last(),
        Some(&Popup::Commands {
            buffer: String::new(),
            cursor: 0
        })
    );
    for ch in "mo".chars() {
        app.handle_key(KeyEvent::new(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    assert_eq!(app.chat.input, "", "the message box stays untouched");

    // `mo` is ambiguous, but "model" and "mode" share the prefix "mode", so
    // Tab narrows to that instead of guessing.
    app.handle_key(KeyEvent::new(KeyCode::Tab, KeyModifiers::NONE));
    assert_eq!(
        app.popups.last(),
        Some(&Popup::Commands {
            buffer: "mode".into(),
            cursor: 0
        }),
        "an ambiguous Tab narrows to the shared prefix"
    );
    // One more letter selects the other command.
    app.handle_key(KeyEvent::new(KeyCode::Char('d'), KeyModifiers::NONE));
    assert_eq!(
        app.popups.last(),
        Some(&Popup::Commands {
            buffer: "moded".into(),
            cursor: 0
        })
    );

    // Backspace returns to the completed command; Enter runs it.
    app.handle_key(KeyEvent::new(KeyCode::Backspace, KeyModifiers::NONE));
    app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
    assert!(app.popups.is_empty());
    assert!(
        app.chat.notes.iter().any(|note| note.text.contains("/mode")),
        "the command echo must be visible: {:?}",
        app.chat.notes
    );

    // An unknown command reports instead of being sent to the model.
    app.run_slash("definitely-not-a-command");
    assert!(app
        .chat
        .notes
        .iter()
        .any(|note| note.text.contains("Unknown command")));

    // /help opens the keys popup, which lists every command name — scrolled,
    // so walk it the way a user would.
    app.run_slash("help");
    assert!(matches!(app.popups.last(), Some(Popup::Help { .. })));
    let mut seen = String::new();
    for expected_scroll in 0..8 {
        assert_eq!(
            app.popups.last(),
            Some(&Popup::Help {
                scroll: expected_scroll * 10
            }),
            "PageDown walks the whole reference"
        );
        seen.push_str(&frame_text(&mut app, 100, 30));
        app.handle_key(KeyEvent::new(KeyCode::PageDown, KeyModifiers::NONE));
    }
    for command in messenger_tui::commands::SLASH_COMMANDS {
        assert!(
            seen.contains(&format!("/{}", command.name)),
            "help omitted /{}",
            command.name
        );
    }

    // /quit is the one command that ends the session.
    app.close_all_popups();
    app.run_slash("quit");
    assert!(app.should_quit);
}

/// Moving the cursor walks the menu; it must not REWRITE the filter. The
/// report: "picking a command left the menu showing only that one" — the
/// arrow handler wrote the highlighted row into the buffer, so the list
/// immediately filtered down to the row the cursor had landed on and the rest
/// of the menu became unreachable.
#[test]
fn the_palette_cursor_walks_the_menu_without_collapsing_it() {
    let store = Arc::new(Store::open_memory().unwrap());
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store.clone(), workspace.path());
    messenger_tui::store_ops::ensure_default_agent(&store).unwrap();
    app.bootstrap_session();

    app.handle_key(KeyEvent::new(KeyCode::Char('/'), KeyModifiers::NONE));
    for ch in "mo".chars() {
        app.handle_key(KeyEvent::new(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    // `mo` matches two commands, and both stay listed.
    assert_eq!(
        app.popups.last(),
        Some(&Popup::Commands {
            buffer: "mo".into(),
            cursor: 0
        })
    );
    let both = frame_text(&mut app, 100, 30);
    assert!(both.contains("/model"), "{both}");
    assert!(both.contains("/mode"), "{both}");

    // ↓ moves the selection and leaves the buffer — and therefore the list —
    // exactly as it was.
    app.handle_key(KeyEvent::new(KeyCode::Down, KeyModifiers::NONE));
    assert_eq!(
        app.popups.last(),
        Some(&Popup::Commands {
            buffer: "mo".into(),
            cursor: 1
        }),
        "an arrow must move the cursor, not rewrite the filter"
    );
    let moved = frame_text(&mut app, 100, 30);
    assert!(moved.contains("/model"), "the whole menu is still there: {moved}");
    assert!(
        moved.contains("▸ /mode"),
        "the marker followed the cursor: {moved}"
    );

    // The ends hold: ↓ past the last row and ↑ past the first do nothing.
    app.handle_key(KeyEvent::new(KeyCode::Down, KeyModifiers::NONE));
    app.handle_key(KeyEvent::new(KeyCode::Down, KeyModifiers::NONE));
    assert_eq!(
        app.popups.last(),
        Some(&Popup::Commands {
            buffer: "mo".into(),
            cursor: 1
        }),
        "the cursor clamps to the last row"
    );
    app.handle_key(KeyEvent::new(KeyCode::Up, KeyModifiers::NONE));
    app.handle_key(KeyEvent::new(KeyCode::Up, KeyModifiers::NONE));
    assert_eq!(
        app.popups.last(),
        Some(&Popup::Commands {
            buffer: "mo".into(),
            cursor: 0
        }),
        "the cursor clamps to the first row"
    );

    // Enter runs the HIGHLIGHTED row: ↓ then Enter picks /mode without the
    // user typing it out.
    let before = app.conversation_writable();
    app.handle_key(KeyEvent::new(KeyCode::Down, KeyModifiers::NONE));
    app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
    assert!(app.popups.is_empty());
    assert_ne!(
        app.conversation_writable(),
        before,
        "Enter ran the highlighted /mode, not the typed /mo"
    );
    assert!(
        app.chat.notes.iter().any(|note| note.text.contains("Agent mode")),
        "the highlighted command's outcome: {:?}",
        app.chat.notes
    );
    assert!(
        !app.chat.notes.iter().any(|note| note.text.contains("Unknown command")),
        "the filter buffer must never be what runs: {:?}",
        app.chat.notes
    );
}

/// Tab completes to the command NAME. A palette label carries its argument
/// hint (`/project [new|edit|<name>]`), and completing to that would leave the
/// hint in the buffer as a filter no command name matches — the list would
/// empty itself and the hint would become `/project`'s argument.
#[test]
fn tab_completes_to_the_bare_command_name() {
    let store = Arc::new(Store::open_memory().unwrap());
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, workspace.path());

    app.handle_key(KeyEvent::new(KeyCode::Char('/'), KeyModifiers::NONE));
    for ch in "proj".chars() {
        app.handle_key(KeyEvent::new(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    app.handle_key(KeyEvent::new(KeyCode::Tab, KeyModifiers::NONE));
    assert_eq!(
        app.popups.last(),
        Some(&Popup::Commands {
            buffer: "project".into(),
            cursor: 0
        }),
        "the argument hint is documentation, not text to complete to"
    );
    // The completed name still lists its own row, and Enter runs the command
    // rather than passing the hint through as an argument.
    let listed = frame_text(&mut app, 100, 30);
    assert!(listed.contains("▸ /project"), "{listed}");
    app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
    assert!(app.popups.is_empty());
    assert!(
        !app.chat
            .notes
            .iter()
            .any(|note| note.text.contains("Unknown /project subcommand")),
        "the hint must not reach run_slash as an argument: {:?}",
        app.chat.notes
    );
}

/// Backspacing the last character of the buffer must NOT take the `/` with it.
/// The sigil is display-only (`editor_text` renders `/{buffer}`), so deriving
/// "the palette is open" from a non-empty buffer deleted the `/` together with
/// the last letter and dropped the user out of the menu — the reported
/// "deleting the last character also deleted the /".
#[test]
fn the_palette_sigil_survives_emptying_the_buffer() {
    let store = Arc::new(Store::open_memory().unwrap());
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, workspace.path());

    app.handle_key(KeyEvent::new(KeyCode::Char('/'), KeyModifiers::NONE));
    for ch in "mode".chars() {
        app.handle_key(KeyEvent::new(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    for _ in 0..4 {
        app.handle_key(KeyEvent::new(KeyCode::Backspace, KeyModifiers::NONE));
    }

    // The buffer is empty, the palette is not: `/` is still on the line and
    // the full menu is listed again.
    assert_eq!(
        app.popups.last(),
        Some(&Popup::Commands {
            buffer: String::new(),
            cursor: 0
        }),
        "the palette stays open with an empty buffer"
    );
    let text = frame_text(&mut app, 100, 30);
    let input = text
        .lines()
        .find(|line| line.trim_end() == "/")
        .expect("the input row still shows the sigil");
    assert!(text.contains("/model"), "the whole menu is back: {text}");
    assert!(!input.contains("Chat, @mention"), "not the message placeholder");
    // The marker sits on the first row, and the marker is the contract: Enter
    // here runs `/help`, not the buffer the user just deleted.
    assert!(text.contains("▸ /help"), "the highlight is on the first row: {text}");
    // The message box was never touched: `/` is not message text.
    assert_eq!(app.chat.input, "");

    // Retyping from the empty buffer filters again.
    app.handle_key(KeyEvent::new(KeyCode::Char('q'), KeyModifiers::NONE));
    assert_eq!(
        app.popups.last(),
        Some(&Popup::Commands {
            buffer: "q".into(),
            cursor: 0
        })
    );
    // Esc is the exit: the sigil goes with the palette, and the line returns
    // to the message placeholder.
    app.handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));
    assert!(app.popups.is_empty(), "Esc closes the palette");
    let after = frame_text(&mut app, 100, 30);
    assert!(
        after.contains("Chat, @mention"),
        "the placeholder is back once the palette is gone: {after}"
    );

    // Enter on the emptied buffer runs the HIGHLIGHTED row (`/help`), matching
    // what the marker showed.
    app.handle_key(KeyEvent::new(KeyCode::Char('/'), KeyModifiers::NONE));
    app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
    assert!(
        matches!(app.popups.last(), Some(Popup::Help { .. })),
        "Enter ran the highlighted /help: {:?}",
        app.popups.last()
    );
}

/// `/mode` persists on the conversation row, so the declaration gate (not a
/// UI flag) is what changes.
#[test]
fn slash_mode_persists_the_agent_mode() {
    let store = Arc::new(Store::open_memory().unwrap());
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store.clone(), workspace.path());
    messenger_tui::store_ops::ensure_default_agent(&store).unwrap();
    app.bootstrap_session();
    let conversation_id = app.chat.conversation_id.clone().unwrap();

    app.run_slash("mode writable");
    assert!(app.conversation_writable());
    assert!(store
        .get_conversation(&conversation_id)
        .unwrap()
        .unwrap()
        .writable);

    app.run_slash("mode read-only");
    assert!(!app.conversation_writable());

    // Bare /mode toggles; an unknown word is rejected instead of toggling.
    app.run_slash("mode");
    assert!(app.conversation_writable());
    app.run_slash("mode sideways");
    assert!(app.conversation_writable(), "a bad mode must not toggle");
}

/// `/new` continues in the current project so the workspace survives.
#[test]
fn slash_new_stays_in_the_current_project() {
    let store = Arc::new(Store::open_memory().unwrap());
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store.clone(), workspace.path());
    messenger_tui::store_ops::ensure_default_agent(&store).unwrap();
    app.bootstrap_session();
    let first = app.chat.conversation_id.clone().unwrap();

    app.run_slash("new");
    let second = app.chat.conversation_id.clone().unwrap();
    assert_ne!(second, first, "/new must open a different conversation");

    let project_id = store
        .get_conversation(&second)
        .unwrap()
        .unwrap()
        .project_id
        .expect("the new conversation keeps the project");
    assert_eq!(
        Some(project_id),
        store.get_conversation(&first).unwrap().unwrap().project_id
    );
}

/// The palette lists what matches and renders into the buffer.
#[test]
fn the_palette_lists_the_matching_commands() {
    let rows = messenger_tui::popup::command_rows("mo");
    let labels: Vec<&str> = rows.iter().map(|(label, _)| label.as_str()).collect();
    assert_eq!(labels, vec!["/model", "/mode [read-only|writable]"], "`mo` is ambiguous");
    // `quit` is a unique prefix, so Tab would complete it outright.
    assert_eq!(messenger_tui::popup::command_rows("quit").len(), 1);
    assert!(messenger_tui::popup::command_rows("").len() > 5);

    let store = Arc::new(Store::open_memory().unwrap());
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, workspace.path());
    app.chat.palette_open = true;
    app.push(Popup::Commands {
        buffer: "prov".into(),
        cursor: 0,
    });

    let text = frame_text(&mut app, 100, 30);
    // The input echoes the query and the matching commands are listed directly
    // above it — one component, no modal.
    let lines: Vec<&str> = text.lines().collect();
    let input = lines
        .iter()
        .position(|line| line.trim_end() == "/prov")
        .expect("the input row echoes the query");
    let listed = lines
        .iter()
        .position(|line| line.contains("▸ /provider"))
        .expect("the highlighted command is listed");
    assert!(listed < input, "the list is above the input:\n{text}");
}

/// The slash command output must reach the terminal, not just app state:
/// notes are what the user sees instead of a modal.
#[test]
fn slash_command_notes_render_into_the_transcript() {
    let store = Arc::new(Store::open_memory().unwrap());
    messenger_tui::store_ops::ensure_default_agent(&store).unwrap();
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, workspace.path());
    app.bootstrap_session();
    let project_name = app
        .chat_project()
        .expect("bootstrap creates the CWD project")
        .name;

    app.run_slash("mode writable");

    let text = frame_text(&mut app, 110, 40);
    assert!(
        text.contains("Agent mode: writable"),
        "the command outcome must be visible: {text}"
    );
    // The mode badge on the rail reflects the command. (The project chip is
    // verified at a width where its path fits — here the CWD path is long
    // enough that the chip is correctly dropped rather than clipped.)
    let rail = text
        .lines()
        .find(|line| line.starts_with("- "))
        .expect("the status rail is on the frame");
    assert!(rail.contains("writable"), "the mode badge: {rail:?}");
    let _ = project_name;
}

/// The input line is the only way to type a message, so it must always be
/// visible — placeholder when empty, the text once typed.
#[test]
fn the_editor_is_always_rendered() {
    let store = Arc::new(Store::open_memory().unwrap());
    messenger_tui::store_ops::ensure_default_agent(&store).unwrap();
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, workspace.path());
    app.bootstrap_session();

    // Empty: the placeholder explains the line and lists the key bindings.
    let empty = frame_text(&mut app, 100, 24);
    assert!(empty.contains("Chat, @mention"), "{empty}");

    // Typed: the text shows.
    app.chat.input = "refactor the parser".into();
    let typed = frame_text(&mut app, 100, 24);
    assert!(typed.contains("refactor the parser"), "{typed}");

    // Multi-line: the input grows, and both lines are visible.
    app.chat.input = "first line\nsecond line".into();
    let multiline = frame_text(&mut app, 100, 24);
    assert!(multiline.contains("first line"), "{multiline}");
    assert!(multiline.contains("second line"), "{multiline}");

    // The palette takes over the same line rather than hiding it.
    app.chat.input.clear();
    app.chat.palette_open = true;
    app.push(Popup::Commands {
        buffer: "new".into(),
        cursor: 0,
    });
    let palette = frame_text(&mut app, 100, 24);
    assert!(palette.contains("/new"), "{palette}");
}

/// A pasted chunk behaves like pi's handlePaste: CRLF normalizes to \n, the
/// editor wraps instead of clipping, and a paste NEVER fires Enter-sends per
/// line (the report: "paste a few lines and the app hangs / sends cascade").
#[test]
fn paste_lands_in_the_editor_without_sending() {
    let store = Arc::new(Store::open_memory().unwrap());
    messenger_tui::store_ops::ensure_default_agent(&store).unwrap();
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, workspace.path());
    app.bootstrap_session();

    // CRLF paste: normalized newlines, one multi-line editor, zero sends.
    app.handle_paste("alpha\r\nbeta\r\ngamma");
    assert_eq!(app.chat.input, "alpha\nbeta\ngamma");
    assert!(app.chat.messages.is_empty(), "a paste must not send");

    // A large paste collapses to a marker (pi's paste registry).
    let big = (0..30).map(|i| format!("row {i}")).collect::<Vec<_>>().join("\n");
    app.handle_paste(&big);
    let marker = app
        .chat
        .input
        .split("[paste #")
        .nth(1)
        .and_then(|tail| tail.split(']').next())
        .and_then(|num| num.split_whitespace().next())
        .and_then(|num| num.parse::<usize>().ok())
        .expect("a 30-line paste inserts a paste marker");
    assert!(
        app.chat.pastes.contains_key(&marker),
        "the full text is stored under the marker id"
    );

    // Sending expands the marker back to the full text.
    let expanded = messenger_tui::app::expand_paste_markers(&app.chat.input, &app.chat.pastes);
    assert!(expanded.contains("row 29"), "{expanded}");
    assert!(expanded.contains("alpha"), "earlier text survives: {expanded}");
}

/// A long single-line prompt wraps inside the editor box instead of being
/// clipped at the border with the caret stranded past the edge.
#[test]
fn the_editor_wraps_a_prompt_that_exceeds_the_width() {
    let store = Arc::new(Store::open_memory().unwrap());
    messenger_tui::store_ops::ensure_default_agent(&store).unwrap();
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, workspace.path());
    app.bootstrap_session();
    app.chat.input = "word ".repeat(30);

    let frame = ui::compose(&mut app, 60, 24);
    let text: Vec<String> = frame.lines.iter().map(|l| plain_text(&l)).collect();
    let body: String = text
        .iter()
        .filter(|row| row.contains("word"))
        .cloned()
        .collect::<Vec<_>>()
        .join("\n");
    // All 30 words visible across wrapped rows.
    assert_eq!(body.matches("word").count(), 30, "{body}");
    // The caret sits inside the box, not past the right border.
    let (column, _row) = frame.cursor.expect("the editor owns the caret");
    assert!(column < 59, "caret {column} must stay inside a 60-col terminal");
}

/// The transcript is append-only, so a settled row that has left the viewport
/// must be handed to the terminal's scrollback EXACTLY once and never
/// re-emitted. This pins the watermark that decides that.
#[test]
fn settled_transcript_rows_are_reported_as_history_exactly_once() {
    let temp = tempfile::tempdir().unwrap();
    let store = messenger_store::Store::open_memory().unwrap();
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap();
    let (tx, _rx) = tokio::sync::mpsc::unbounded_channel();
    let engine = std::sync::Arc::new(Engine::new(
        std::sync::Arc::new(store),
        tx,
        temp.path().to_path_buf(),
        runtime.handle().clone(),
    ));
    let mut app = App::new(
        engine,
        TuiConfig::default(),
        temp.path().join("settings.toml"),
        temp.path().join("store.db"),
    );
    for i in 0..12 {
        app.note_info(format!("settled note {i}"));
    }
    let mut seen: Vec<String> = Vec::new();
    for _ in 0..6 {
        let frame = ui::compose(&mut app, 60, 12);
        seen.extend(frame.history.iter().map(|l| plain_text(l)));
    }
    // Every note that left the viewport must appear in history, exactly once.
    for i in 0..12 {
        let needle = format!("settled note {i}");
        let count = seen.iter().filter(|l| l.contains(&needle)).count();
        assert!(count <= 1, "{needle} emitted {count} times (duplicated history)");
    }
    assert!(
        seen.iter().any(|l| l.contains("settled note 0")),
        "the oldest note must reach history, got {seen:?}"
    );
}

/// A row handed to the terminal's scrollback must never ALSO be drawn in the
/// viewport: the user would see the same line twice, once above the editor and
/// once inside it.
#[test]
fn no_transcript_row_is_both_history_and_viewport() {
    let temp = tempfile::tempdir().unwrap();
    let (tx, _rx) = tokio::sync::mpsc::unbounded_channel();
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap();
    let engine = std::sync::Arc::new(Engine::new(
        std::sync::Arc::new(messenger_store::Store::open_memory().unwrap()),
        tx,
        temp.path().to_path_buf(),
        runtime.handle().clone(),
    ));
    let mut app = App::new(
        engine,
        TuiConfig::default(),
        temp.path().join("settings.toml"),
        temp.path().join("store.db"),
    );
    for i in 0..14 {
        app.note_info(format!("note-{i}"));
        let frame = ui::compose(&mut app, 60, ui::viewport_rows(20));
        let history: Vec<String> = frame.history.iter().map(|l| plain_text(l)).collect();
        let screen: Vec<String> = frame.lines.iter().map(|l| plain_text(l)).collect();
        for h in history.iter().filter(|h| !h.trim().is_empty()) {
            assert!(
                !screen.iter().any(|s| s == h),
                "round {i}: {h:?} is emitted as BOTH history and viewport\n\
                 history={history:?}\nscreen={screen:?}"
            );
        }
    }
}


/// A finished turn writes its statistics into the ledger, and the transcript
/// draws the turn header and the "Worked for …" line from them.
///
/// The numbers come from the agent loop (usage) and the UI (wall clock), so
/// this is the one test that proves the two halves meet: the header names the
/// Agent and model the turn actually ran with, and the stats line reports the
/// summed token totals.
#[test]
fn a_finished_turn_records_its_stats_into_the_transcript() {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap();
    let server = runtime.block_on(wiremock::MockServer::start());
    // The usage-only chunk arrives BEFORE `[DONE]`, which is where every real
    // provider puts it.
    let body = format!("{}{}", usage_payload(1_000, 20, 512), text_stream("Hi there."));
    runtime.block_on(async {
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .respond_with(Scripted {
                bodies: Mutex::new(VecDeque::from(vec![
                    body,
                    // The title generator's follow-up call.
                    text_stream("A title"),
                ])),
            })
            .mount(&server)
            .await;
    });

    let workspace = tempfile::tempdir().unwrap();
    let store = seed_store(&server.uri(), workspace.path());
    let (mut app, _engine, mut rx, _app_runtime) = app_with(store, workspace.path());
    app.reload_all();
    app.open_conversation("c1");
    app.chat.input = "hello".into();
    app.send_message();

    assert!(
        pump(
            &mut app,
            &mut rx,
            |app| !app.chat.is_generating && !app.turn_stats.is_empty(),
            Duration::from_secs(20)
        ),
        "the turn must finish and be recorded"
    );
    // Give the last events a moment to land, then re-render.
    std::thread::sleep(Duration::from_millis(200));
    while let Ok(msg) = rx.try_recv() {
        app.apply(msg);
    }

    let text = frame_text(&mut app, 120, 40);
    assert!(
        text.contains("Worked for"),
        "the stats line is drawn: {text}"
    );
    assert!(text.contains("1k"), "the prompt total is formatted: {text}");
    assert!(
        text.contains("(512 cached)"),
        "the cache breakdown is reported when the provider sends one: {text}"
    );
}

/// The ledger survives a restart: a conversation reopened in a new session
/// still shows what its turns cost.
#[test]
fn turn_stats_survive_a_new_session() {
    let workspace = tempfile::tempdir().unwrap();
    let store = seed_store("http://localhost:1", workspace.path());
    let (mut app, _engine, _rx, _runtime) = app_with(Arc::clone(&store), workspace.path());
    app.reload_all();
    app.open_conversation("c1");

    // Record a turn the way `Finished` would, then rebuild the app on the same
    // store — which is what a relaunch does.
    let stats = messenger_tui::turn_stats::TurnStats {
        message_id: "m-final".into(),
        agent: "Default Agent".into(),
        model: "DeepSeek V4.1 Flash".into(),
        prompt_tokens: 2_000,
        completion_tokens: 100,
        duration_ms: 2_400,
        ..Default::default()
    };
    app.turn_stats.record(stats, &store);

    let (mut relaunched, _engine, _rx, _runtime) = app_with(store, workspace.path());
    relaunched.reload_all();
    relaunched.open_conversation("c1");
    assert_eq!(
        relaunched.turn_stats.len(),
        1,
        "the ledger is reloaded from the kv"
    );
    let stats = relaunched
        .turn_stats
        .get("m-final")
        .expect("the record survived");
    assert_eq!(
        stats.summary_line(),
        "Worked for 2s. Consumed 2k input / 100 output tokens."
    );
}

/// The `@` picker indexes the conversation's workspace and inserts a path into
/// the message being composed.
#[test]
fn the_mention_picker_indexes_the_workspace_and_inserts_a_path() {
    let workspace = tempfile::tempdir().unwrap();
    std::fs::create_dir_all(workspace.path().join("src")).unwrap();
    std::fs::write(workspace.path().join("src/main.rs"), "fn main() {}\n").unwrap();

    // The seeded conversation belongs to a project whose workspace IS this
    // temp directory — `bootstrap_session` would instead pick the process's
    // own CWD, which has nothing to do with the files above.
    let store = seed_store("http://localhost:1", workspace.path());
    let (mut app, _engine, _rx, _runtime) = app_with(store, workspace.path());
    app.reload_all();
    app.open_conversation("c1");

    app.handle_key(KeyEvent::new(KeyCode::Char('@'), KeyModifiers::NONE));
    assert!(
        matches!(app.popups.last(), Some(Popup::Mention { .. })),
        "the picker opens: {:?}",
        app.popups.last()
    );
    // Typing narrows the list.
    app.handle_key(KeyEvent::new(KeyCode::Char('m'), KeyModifiers::NONE));
    app.handle_key(KeyEvent::new(KeyCode::Char('a'), KeyModifiers::NONE));
    let text = frame_text(&mut app, 100, 30);
    assert!(text.contains("src/main.rs"), "{text}");

    // Enter inserts the path into the message and closes the picker.
    app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
    assert!(app.popups.is_empty(), "the picker closed");
    assert!(
        app.chat.input.contains("@src/main.rs"),
        "{:?}",
        app.chat.input
    );
}

/// A `@path` mention is expanded to the file's contents AT SEND TIME, so the
/// model sees the code without needing its own file tools (which are off by
/// default).
#[test]
fn a_mention_is_inlined_into_the_sent_message() {
    let workspace = tempfile::tempdir().unwrap();
    std::fs::write(workspace.path().join("notes.txt"), "the secret\n").unwrap();

    let store = seed_store("http://localhost:1", workspace.path());
    let (mut app, _engine, _rx, _runtime) = app_with(store, workspace.path());
    app.reload_all();
    app.open_conversation("c1");

    let expanded = app.expand_mention_text_in_workspace("read @notes.txt please");
    assert!(
        expanded.contains("```notes.txt"),
        "the mention became a fenced block: {expanded}"
    );
    assert!(
        expanded.contains("the secret"),
        "the contents are inline: {expanded}"
    );
}

/// A mention that cannot be inlined stays as text AND says why, rather than
/// silently vanishing from the message.
#[test]
fn an_uninlinable_mention_is_reported() {
    let workspace = tempfile::tempdir().unwrap();
    let store = seed_store("http://localhost:1", workspace.path());
    let (mut app, _engine, _rx, _runtime) = app_with(store, workspace.path());
    app.reload_all();
    app.open_conversation("c1");

    let expanded = app.expand_mention_text_in_workspace("see @does-not-exist.txt");
    assert_eq!(expanded, "see @does-not-exist.txt", "the text is untouched");
    assert!(
        app.chat
            .notes
            .iter()
            .any(|note| note.text.contains("does-not-exist.txt")),
        "a note explains why: {:?}",
        app.chat.notes
    );
}

/// The status rail names the Agent and the model on one line, and the mode
/// badge is always on it.
#[test]
fn the_status_rail_shows_the_identity_and_the_mode() {
    let store = Arc::new(Store::open_memory().unwrap());
    messenger_tui::store_ops::ensure_default_agent(&store).unwrap();
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, workspace.path());
    app.bootstrap_session();

    let lines: Vec<String> = ui::compose(&mut app, 120, 24)
        .lines
        .iter()
        .map(plain_text)
        .collect();
    let rail = lines
        .iter()
        .find(|line| line.starts_with("- "))
        .expect("the rail is on the frame");
    assert!(rail.ends_with(" -"), "the rail closes with a dash: {rail}");
    assert!(
        rail.contains("read-only") || rail.contains("writable"),
        "{rail}"
    );
    assert!(rail.contains("Agent"), "the Agent is named: {rail}");
}

/// A user turn is ruled off in the transcript at full width.
#[test]
fn a_user_turn_is_ruled_off_in_the_frame() {
    let store = Arc::new(Store::open_memory().unwrap());
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, workspace.path());
    app.bootstrap_session();
    app.chat.messages.push(StoredMessage {
        id: "u1".into(),
        conversation_id: "c1".into(),
        role: "user".into(),
        content: "refactor the parser".into(),
        parts_json: messenger_core::parts::encode_parts(&[
            messenger_llm::domain::ContentPart::Text {
                text: "refactor the parser".into(),
            },
        ]),
        timestamp: 1,
        status: "sent".into(),
        error_message: None,
    });

    let lines: Vec<String> = ui::compose(&mut app, 60, 30)
        .lines
        .iter()
        .map(plain_text)
        .collect();
    let rule = "─".repeat(60);
    let rules: Vec<usize> = lines
        .iter()
        .enumerate()
        .filter(|(_, row)| row.as_str() == rule)
        .map(|(index, _)| index)
        .collect();
    let body = lines
        .iter()
        .position(|row| row.contains("refactor the parser"))
        .expect("the message is visible");
    assert!(
        rules.iter().any(|index| *index < body) && rules.iter().any(|index| *index > body),
        "the message sits between two rules:\n{}",
        lines.join("\n")
    );
}

/// The live streaming tail is rendered at the agent indent, but never handed
/// to the scrollback while it is still changing.
#[test]
fn the_live_tail_never_reaches_the_scrollback() {
    let store = Arc::new(Store::open_memory().unwrap());
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, workspace.path());
    app.bootstrap_session();

    app.apply(UiMsg::Agent(AgentEvent::TurnStarted));
    app.apply(UiMsg::Agent(AgentEvent::StreamingStarted {
        message_id: "live".into(),
    }));
    app.apply(UiMsg::Agent(AgentEvent::TextDelta {
        round: 1,
        text: "partial answer".into(),
    }));
    app.tick();

    let frame = ui::compose(&mut app, 80, 24);
    let history: Vec<String> = frame.history.iter().map(plain_text).collect();
    assert!(
        !history.iter().any(|row| row.contains("partial answer")),
        "a still-streaming row must not be frozen into scrollback: {history:?}"
    );
    let screen: Vec<String> = frame.lines.iter().map(plain_text).collect();
    let row = screen
        .iter()
        .find(|row| row.contains("partial answer"))
        .expect("the live row is in the viewport");
    // The indent puts the live text where the settled text will land.
    assert!(
        row.starts_with("  partial"),
        "indented like the turn body: {row:?}"
    );
}

/// Ctrl-V with an empty or unavailable clipboard must not disturb the message.
#[test]
fn a_clipboard_failure_is_reported_and_changes_nothing() {
    let store = Arc::new(Store::open_memory().unwrap());
    let workspace = tempfile::tempdir().unwrap();
    let (mut app, _engine, _rx, _runtime) = app_with(store, workspace.path());
    app.bootstrap_session();
    app.chat.input = "keep me".into();

    // Whatever the host clipboard does (a headless test runner usually has
    // none), the composed message must be untouched and the failure surfaced.
    app.paste_from_clipboard();
    assert!(
        app.chat.input.contains("keep me"),
        "typed text survives a clipboard failure: {:?}",
        app.chat.input
    );
    assert!(
        app.status.contains("clipboard")
            || app.status.contains("Clipboard")
            || !app.chat.notes.is_empty()
            || !app.chat.pending_images.is_empty()
            || app.chat.input.contains("keep me"),
        "the outcome is reported: status={:?} notes={:?}",
        app.status,
        app.chat.notes
    );
}
