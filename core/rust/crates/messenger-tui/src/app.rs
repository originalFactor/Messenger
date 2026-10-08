//! Application state, key handling, modals/forms, and view switching.
//!
//! The whole UI is one `match` over `(view, modal, key)`; while a form or a
//! confirmation is open it exclusively consumes input except `Esc` and
//! `Ctrl+C`. Everything the agent loop reports arrives as a [`UiMsg`] and is
//! applied by [`App::apply`], so the render side never touches async state.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use messenger_core::agent::AgentEvent;
use messenger_document::Block;
use messenger_llm::domain::ContentPart;
use messenger_mcp::config::{encode_server_list, McpServerConfig, McpTransportType};
use messenger_markdown::StreamingSession;
use messenger_store::model::{StoredAgent, StoredConversation, StoredMessage, StoredModel, StoredProvider};
use ratatui::text::Line;

use crate::config::TuiConfig;
use crate::engine::{load_mcp_servers, CardSnapshot, Engine, UiMsg};
use crate::render::{self, RenderOpts};
use crate::store_ops::{self, TurnError};

/// The six top-level views (F1–F6).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum View {
    Conversations,
    Chat,
    Agents,
    Providers,
    Mcp,
    Settings,
}

impl View {
    pub fn all() -> [View; 6] {
        [
            View::Conversations,
            View::Chat,
            View::Agents,
            View::Providers,
            View::Mcp,
            View::Settings,
        ]
    }

    pub fn label(&self) -> &'static str {
        match self {
            View::Conversations => "Conversations",
            View::Chat => "Chat",
            View::Agents => "Agents",
            View::Providers => "Providers",
            View::Mcp => "MCP",
            View::Settings => "Settings",
        }
    }

    pub fn index(&self) -> usize {
        match self {
            View::Conversations => 0,
            View::Chat => 1,
            View::Agents => 2,
            View::Providers => 3,
            View::Mcp => 4,
            View::Settings => 5,
        }
    }

    fn from_function_key(code: KeyCode) -> Option<View> {
        View::all()
            .get(match code {
                KeyCode::F(1) => 0,
                KeyCode::F(2) => 1,
                KeyCode::F(3) => 2,
                KeyCode::F(4) => 3,
                KeyCode::F(5) => 4,
                KeyCode::F(6) => 5,
                _ => return None,
            })
            .copied()
    }
}

// ---------------------------------------------------------------------------
// forms & modals
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq)]
pub enum Field {
    Text {
        label: String,
        value: String,
        multiline: bool,
        secret: bool,
    },
    Bool {
        label: String,
        value: bool,
    },
    Choice {
        label: String,
        options: Vec<String>,
        selected: usize,
    },
}

impl Field {
    pub fn text(label: &str, value: impl Into<String>) -> Self {
        Field::Text {
            label: label.to_string(),
            value: value.into(),
            multiline: false,
            secret: false,
        }
    }

    pub fn multiline(label: &str, value: impl Into<String>) -> Self {
        Field::Text {
            label: label.to_string(),
            value: value.into(),
            multiline: true,
            secret: false,
        }
    }

    pub fn secret(label: &str, value: impl Into<String>) -> Self {
        Field::Text {
            label: label.to_string(),
            value: value.into(),
            multiline: false,
            secret: true,
        }
    }

    pub fn boolean(label: &str, value: bool) -> Self {
        Field::Bool {
            label: label.to_string(),
            value,
        }
    }

    pub fn choice(label: &str, options: Vec<String>, selected: usize) -> Self {
        Field::Choice {
            label: label.to_string(),
            options,
            selected,
        }
    }

    pub fn label(&self) -> &str {
        match self {
            Field::Text { label, .. } | Field::Bool { label, .. } | Field::Choice { label, .. } => {
                label
            }
        }
    }

    fn as_text(&self) -> String {
        match self {
            Field::Text { value, .. } => value.clone(),
            Field::Bool { value, .. } => value.to_string(),
            Field::Choice { options, selected, .. } => {
                options.get(*selected).cloned().unwrap_or_default()
            }
        }
    }
}

/// What a submitted form does.
#[derive(Debug, Clone, PartialEq)]
pub enum FormPurpose {
    NewConversation,
    RenameConversation(String),
    NewProvider,
    EditProvider(String),
    NewModel(String),
    EditModel { provider_id: String, model_id: String },
    NewAgent,
    EditAgent(String),
    NewMcpServer,
    EditMcpServer(String),
    SignIn,
    RedeemCard,
    ChangePassword,
    DeleteAccountForm,
    EditServerUrl,
    FilterConversations,
    EditWorkspace,
}

#[derive(Debug, Clone)]
pub struct Form {
    pub title: String,
    pub purpose: FormPurpose,
    pub fields: Vec<Field>,
    pub focus: usize,
}

impl Form {
    pub fn new(title: &str, purpose: FormPurpose, fields: Vec<Field>) -> Self {
        Self {
            title: title.to_string(),
            purpose,
            fields,
            focus: 0,
        }
    }

    pub fn value(&self, label: &str) -> String {
        self.fields
            .iter()
            .find(|field| field.label() == label)
            .map(Field::as_text)
            .unwrap_or_default()
    }

    pub fn boolean(&self, label: &str) -> bool {
        self.fields
            .iter()
            .find_map(|field| match field {
                Field::Bool { label: name, value } if name == label => Some(*value),
                _ => None,
            })
            .unwrap_or(false)
    }

    pub fn index(&self, label: &str) -> Option<usize> {
        self.fields.iter().position(|field| {
            matches!(field, Field::Choice { label: name, .. } if name == label)
        })
        .and_then(|position| match &self.fields[position] {
            Field::Choice { selected, .. } => Some(*selected),
            _ => None,
        })
    }

    /// Parse a numeric field, treating blank as "unset".
    pub fn number(&self, label: &str) -> Option<f64> {
        let raw = self.value(label);
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            return None;
        }
        trimmed.parse::<f64>().ok()
    }

    fn focused(&self) -> Option<&Field> {
        self.fields.get(self.focus)
    }

    fn focused_mut(&mut self) -> Option<&mut Field> {
        self.fields.get_mut(self.focus)
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum ConfirmPurpose {
    DeleteConversation(String),
    DeleteProvider(String),
    DeleteModel { provider_id: String, model_id: String },
    DeleteAgent(String),
    DeleteMcpServer(String),
    RedeemCard(String),
    /// Account deletion carries the password the user typed in the form.
    DeleteAccount(String),
    SignOut,
}

#[derive(Debug, Clone)]
pub struct Confirm {
    pub title: String,
    pub message: String,
    pub purpose: ConfirmPurpose,
    /// When set, the user must type this text verbatim to confirm.
    pub requires_typing: Option<String>,
    pub typed: String,
}

impl Confirm {
    pub fn new(title: &str, message: impl Into<String>, purpose: ConfirmPurpose) -> Self {
        Self {
            title: title.to_string(),
            message: message.into(),
            purpose,
            requires_typing: None,
            typed: String::new(),
        }
    }

    pub fn requires(&self, text: &str) -> Self {
        let mut confirm = self.clone();
        confirm.requires_typing = Some(text.to_string());
        confirm
    }

    pub fn can_confirm(&self) -> bool {
        match &self.requires_typing {
            Some(expected) => self.typed.trim() == expected,
            None => true,
        }
    }
}

// ---------------------------------------------------------------------------
// chat state
// ---------------------------------------------------------------------------

struct LiveStream {
    message_id: String,
    session: StreamingSession,
    last_rendered: usize,
}

#[derive(Default)]
struct RenderedCache {
    entries: HashMap<String, (String, u16, (bool, bool), Vec<Line<'static>>)>,
}

impl RenderedCache {
    fn get_or_build(
        &mut self,
        key: &str,
        fingerprint: String,
        width: u16,
        opts: &RenderOpts,
        build: impl FnOnce() -> Vec<Line<'static>>,
    ) -> &Vec<Line<'static>> {
        let opts_key = (opts.show_think, opts.show_tool_details);
        let stale = match self.entries.get(key) {
            Some((cached_fingerprint, cached_width, cached_opts, _)) => {
                cached_fingerprint != &fingerprint
                    || *cached_width != width
                    || *cached_opts != opts_key
            }
            None => true,
        };
        if stale {
            self.entries
                .insert(key.to_string(), (fingerprint, width, opts_key, build()));
        }
        &self.entries.get(key).expect("just inserted").3
    }

    fn clear(&mut self) {
        self.entries.clear();
    }
}

#[derive(Default)]
pub struct ChatState {
    pub conversation_id: Option<String>,
    pub messages: Vec<StoredMessage>,
    pub input: String,
    pub input_lines: usize,
    pub scroll: u16,
    pub follow: bool,
    pub is_generating: bool,
    pub error: Option<String>,
    live: Option<LiveStream>,
    cache: RenderedCache,
}

impl ChatState {
    pub fn streaming_message_id(&self) -> Option<&str> {
        self.live.as_ref().map(|live| live.message_id.as_str())
    }
}

/// One setting row in F6.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SettingRow {
    Theme,
    ShowThink,
    ShowToolDetails,
    AutoScroll,
    Workspace,
    ServerUrl,
    Session,
}

impl SettingRow {
    pub fn all() -> [SettingRow; 7] {
        [
            SettingRow::Theme,
            SettingRow::ShowThink,
            SettingRow::ShowToolDetails,
            SettingRow::AutoScroll,
            SettingRow::Workspace,
            SettingRow::ServerUrl,
            SettingRow::Session,
        ]
    }
}

// ---------------------------------------------------------------------------
// app
// ---------------------------------------------------------------------------

pub struct App {
    pub engine: Arc<Engine>,
    pub config: TuiConfig,
    pub config_path: PathBuf,
    pub store_path: PathBuf,
    pub view: View,
    pub should_quit: bool,
    pub status: String,
    pub help: bool,
    pub spinner: usize,
    pub tick_count: u64,

    pub conversations: Vec<StoredConversation>,
    pub conversation_selection: usize,
    pub conversation_filter: Option<String>,

    pub chat: ChatState,

    pub agents: Vec<StoredAgent>,
    pub agent_selection: usize,

    pub providers: Vec<StoredProvider>,
    pub provider_selection: usize,
    pub models: Vec<StoredModel>,
    pub model_selection: usize,

    pub mcp_servers: Vec<McpServerConfig>,
    pub mcp_selection: usize,

    pub setting_selection: usize,

    pub form: Option<Form>,
    pub confirm: Option<Confirm>,
    /// Current cloud user, refreshed from the store on demand.
    pub cloud_user: Option<CloudUserInfo>,
    /// Last status-line diagnostic (token totals etc.).
    pub last_list_message: Option<String>,
    /// Agent ids backing the Agent switcher modal (its form indexes into this).
    pending_agent_picker: Option<Vec<String>>,
    /// Providers view: true when the model pane owns the selection.
    pub provider_focus_models: bool,
}

/// The subset of `CloudUser` the TUI shows.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct CloudUserInfo {
    pub email: String,
    pub role: String,
    pub quota_balance: Option<i64>,
    pub quota_expires_at: Option<i64>,
    pub entitlements: Vec<(String, i64, Option<i64>)>,
}

impl App {
    pub fn new(
        engine: Arc<Engine>,
        config: TuiConfig,
        config_path: PathBuf,
        store_path: PathBuf,
    ) -> Self {
        let mut app = Self {
            engine,
            config,
            config_path,
            store_path,
            view: View::Conversations,
            should_quit: false,
            status: String::new(),
            help: false,
            spinner: 0,
            tick_count: 0,
            conversations: Vec::new(),
            conversation_selection: 0,
            conversation_filter: None,
            chat: ChatState {
                follow: true,
                ..ChatState::default()
            },
            agents: Vec::new(),
            agent_selection: 0,
            providers: Vec::new(),
            provider_selection: 0,
            models: Vec::new(),
            model_selection: 0,
            mcp_servers: Vec::new(),
            mcp_selection: 0,
            setting_selection: 0,
            form: None,
            confirm: None,
            cloud_user: None,
            last_list_message: None,
            pending_agent_picker: None,
            provider_focus_models: false,
        };
        app.reload_all();
        app
    }

    pub fn opts(&self) -> RenderOpts {
        RenderOpts {
            dark: self.config.is_dark(),
            show_think: self.config.show_think,
            show_tool_details: self.config.show_tool_details,
        }
    }

    // ------------------------------------------------------------------
    // reloads
    // ------------------------------------------------------------------

    pub fn reload_all(&mut self) {
        self.reload_conversations();
        self.reload_agents();
        self.reload_providers();
        self.reload_mcp();
        self.reload_cloud_user();
    }

    pub fn reload_conversations(&mut self) {
        match store_ops::list_conversations(&self.engine.store) {
            Ok(mut conversations) => {
                if let Some(filter) = self
                    .conversation_filter
                    .as_deref()
                    .filter(|filter| !filter.trim().is_empty())
                {
                    let needle = filter.to_lowercase();
                    conversations.retain(|c| c.title.to_lowercase().contains(&needle));
                }
                self.conversations = conversations;
                self.conversation_selection = self
                    .conversation_selection
                    .min(self.conversations.len().saturating_sub(1));
            }
            Err(error) => self.status = error,
        }
    }

    pub fn reload_agents(&mut self) {
        match self.engine.store.list_agents() {
            Ok(agents) => {
                self.agents = agents;
                self.agent_selection = self.agent_selection.min(self.agents.len().saturating_sub(1));
            }
            Err(error) => self.status = error.to_string(),
        }
    }

    pub fn reload_providers(&mut self) {
        match self.engine.store.list_providers() {
            Ok(providers) => {
                self.providers = providers;
                self.provider_selection = self
                    .provider_selection
                    .min(self.providers.len().saturating_sub(1));
            }
            Err(error) => self.status = error.to_string(),
        }
        self.reload_models();
    }

    pub fn reload_models(&mut self) {
        let provider = self.providers.get(self.provider_selection);
        self.models = match provider {
            Some(provider) => self
                .engine
                .store
                .list_models_by_provider(&provider.id)
                .unwrap_or_default(),
            None => Vec::new(),
        };
        self.model_selection = self.model_selection.min(self.models.len().saturating_sub(1));
    }

    pub fn reload_mcp(&mut self) {
        self.mcp_servers = load_mcp_servers(&self.engine.store);
        self.mcp_selection = self.mcp_selection.min(self.mcp_servers.len().saturating_sub(1));
    }

    pub fn reload_cloud_user(&mut self) {
        let store = &self.engine.store;
        let user = store
            .kv_get(messenger_sync::KV_USER)
            .ok()
            .flatten()
            .and_then(|json| serde_json::from_str::<messenger_sync::CloudUser>(&json).ok());
        self.cloud_user = user.map(|user| CloudUserInfo {
            email: user.email,
            role: user.role.unwrap_or_else(|| "user".into()),
            quota_balance: user.quota_balance,
            quota_expires_at: user.quota_expires_at,
            entitlements: user
                .quota_entitlements
                .into_iter()
                .map(|entitlement| {
                    (
                        entitlement
                            .plan_name
                            .or(entitlement.source)
                            .unwrap_or_else(|| "quota".into()),
                        entitlement.balance,
                        entitlement.expires_at,
                    )
                })
                .collect(),
        });
    }

    /// Load the conversation's messages into the chat view.
    pub fn open_conversation(&mut self, conversation_id: &str) {
        match store_ops::load_messages(&self.engine.store, conversation_id) {
            Ok(messages) => {
                self.chat.messages = messages;
                self.chat.conversation_id = Some(conversation_id.to_string());
                self.chat.scroll = 0;
                self.chat.follow = true;
                self.chat.error = None;
                self.chat.cache.clear();
                self.view = View::Chat;
            }
            Err(error) => self.status = error,
        }
    }

    pub fn reload_chat_messages(&mut self) {
        let Some(conversation_id) = self.chat.conversation_id.clone() else {
            return;
        };
        match store_ops::load_messages(&self.engine.store, &conversation_id) {
            Ok(messages) => self.chat.messages = messages,
            Err(error) => self.status = error,
        }
    }

    pub fn save_config(&mut self) {
        if let Err(error) = self.config.write(&self.config_path) {
            self.status = format!("Failed to save settings: {error}");
        }
    }

    // ------------------------------------------------------------------
    // message plumbing
    // ------------------------------------------------------------------

    pub fn apply(&mut self, msg: UiMsg) {
        match msg {
            UiMsg::Agent(event) => self.apply_agent_event(event),
            UiMsg::ToolLog(message) => self.status = message,
            UiMsg::CloudStatus(message) => {
                self.status = message;
                self.reload_cloud_user();
            }
            UiMsg::CardPreview { code, preview } => match preview {
                Ok(card) => {
                    self.confirm = Some(Confirm::new(
                        "Redeem card",
                        format!(
                            "{} — {} tokens, {} days validity.\nConfirm redemption of {code}?",
                            card.plan_name, card.quota_tokens, card.validity_days
                        ),
                        ConfirmPurpose::RedeemCard(code),
                    ));
                }
                Err(error) => self.status = format!("Card preview failed: {error}"),
            },
            UiMsg::SyncFinished => {
                self.reload_all();
            }
        }
    }

    fn apply_agent_event(&mut self, event: AgentEvent) {
        match event {
            AgentEvent::TurnStarted => {
                self.chat.is_generating = true;
                self.chat.error = None;
            }
            AgentEvent::StreamingStarted { message_id } => {
                self.chat.live = Some(LiveStream {
                    message_id,
                    session: StreamingSession::new(),
                    last_rendered: 0,
                });
            }
            AgentEvent::TextDelta { text, .. } => {
                if let Some(live) = self.chat.live.as_mut() {
                    live.session.feed(&text);
                }
            }
            AgentEvent::RoundPersisted { .. } | AgentEvent::FinalMessagePersisted { .. } => {
                if let Some(mut live) = self.chat.live.take() {
                    live.session.finish();
                }
                self.reload_chat_messages();
                // A round changed the transcript: drop stale cache entries.
                self.chat.cache.clear();
            }
            AgentEvent::TitleGenerated { title } => {
                self.status = format!("Title: {title}");
                self.reload_conversations();
            }
            AgentEvent::TitleFailed { code } => {
                self.status = format!("Title generation failed ({code}).");
            }
            AgentEvent::UsageRecorded {
                prompt_tokens,
                completion_tokens,
            } => {
                let total = prompt_tokens + completion_tokens;
                self.last_list_message = Some(format!("{total} tokens"));
            }
            AgentEvent::ReasoningFormatDetected { format } => {
                self.status = format!("Reasoning format: {format}");
            }
            AgentEvent::Error { code, message } => {
                self.chat.error = Some(if message.is_empty() {
                    code
                } else {
                    format!("{code}: {message}")
                });
                self.chat.is_generating = false;
                self.reload_chat_messages();
            }
            AgentEvent::Cancelled => {
                self.chat.is_generating = false;
                self.chat.live = None;
                self.chat.cache.clear();
                self.status = "Turn cancelled.".into();
                self.reload_chat_messages();
            }
            AgentEvent::Finished { .. } => {
                self.chat.is_generating = false;
                self.reload_chat_messages();
                self.reload_conversations();
            }
            AgentEvent::ToolCallStarted { name, .. } => {
                self.chat.is_generating = true;
                self.status = format!("Running {name}…");
            }
            AgentEvent::ToolCallFinished { name, is_error, .. } => {
                self.status = if is_error {
                    format!("{name} failed.")
                } else {
                    format!("{name} finished.")
                };
                self.reload_chat_messages();
            }
        }
    }

    /// Drain the live streaming session's diffs (the document is the source
    /// of truth for the renderer) and advance the spinner.
    pub fn tick(&mut self) {
        self.tick_count = self.tick_count.wrapping_add(1);
        self.spinner = (self.spinner + 1) % 4;
        if self.chat.live.is_some() {
            let block_count = if let Some(live) = self.chat.live.as_mut() {
                let _ = live.session.drain_batch();
                live.session.document().blocks().len()
            } else {
                0
            };
            if let Some(live) = self.chat.live.as_mut() {
                live.last_rendered = block_count;
            }
            if self.chat.follow {
                self.chat.scroll = 0;
            }
        }
    }

    /// Lines for the live streaming message (from the session's document).
    pub fn live_lines(&self, width: u16) -> Option<Vec<Line<'static>>> {
        let live = self.chat.live.as_ref()?;
        let blocks: Vec<Block> = live.session.document().blocks().to_vec();
        if blocks.is_empty() {
            return None;
        }
        Some(render::blocks_to_lines(&blocks, width, &self.opts()))
    }

    pub fn cache(
        &mut self,
    ) -> &mut HashMap<String, (String, u16, (bool, bool), Vec<Line<'static>>)> {
        // Only used by the ui module through `rendered_message`.
        &mut self.chat.cache.entries
    }

    /// Render one stored message with caching keyed on its content fingerprint.
    pub fn rendered_message(&mut self, index: usize, width: u16) -> Vec<Line<'static>> {
        let opts = self.opts();
        let Some(message) = self.chat.messages.get(index) else {
            return Vec::new();
        };
        let fingerprint = format!(
            "{}|{}|{}|{}|{}",
            message.content,
            message.parts_json.as_deref().unwrap_or(""),
            message.status,
            message.error_message.as_deref().unwrap_or(""),
            message.role
        );
        self.chat
            .cache
            .get_or_build(&message.id, fingerprint, width, &opts, || {
                render::message_lines(message, width, &opts)
            })
            .clone()
    }

    // ------------------------------------------------------------------
    // actions
    // ------------------------------------------------------------------

    fn create_conversation(&mut self) -> Option<String> {
        let Ok(Some(agent)) = store_ops::current_agent(&self.engine.store) else {
            self.status = "No Agent available.".into();
            return None;
        };
        // A brand-new conversation has no model binding yet, so the provider
        // is taken from the effective Agent's model when one is set.
        let provider_id = self
            .effective_provider_id(&agent)
            .unwrap_or_default();
        match store_ops::create_conversation(&self.engine.store, &agent, &provider_id) {
            Ok(conversation) => {
                self.reload_conversations();
                self.open_conversation(&conversation.id);
                Some(conversation.id)
            }
            Err(error) => {
                self.status = error;
                None
            }
        }
    }

    fn effective_provider_id(&self, agent: &StoredAgent) -> Option<String> {
        let default = self.engine.store.get_default_agent().ok().flatten();
        let effective = store_ops::resolve_effective_agent(agent.clone(), None, default.as_ref());
        let model_id = effective.default_model_id?;
        self.engine
            .store
            .get_model(&model_id)
            .ok()
            .flatten()
            .map(|model| model.provider_id)
    }

    fn cloud_api_key(&self) -> Option<String> {
        self.engine
            .store
            .kv_get(messenger_sync::KV_USER)
            .ok()
            .flatten()
            .and_then(|json| serde_json::from_str::<messenger_sync::CloudUser>(&json).ok())
            .and_then(|user| user.ai_api_key)
    }

    /// Send the chat input as a new turn.
    pub fn send_message(&mut self) {
        if self.chat.is_generating {
            self.status = "A turn is already running (Esc to cancel).".into();
            return;
        }
        let text = self.chat.input.trim_end().to_string();
        if text.trim().is_empty() {
            return;
        }
        let conversation_id = match self.chat.conversation_id.clone() {
            Some(id) => id,
            None => match self.create_conversation() {
                Some(id) => id,
                None => return,
            },
        };

        // Resolve BEFORE persisting: a missing model must abort the send
        // without touching the transcript.
        let resolved = match store_ops::resolve_turn(
            &self.engine.store,
            &conversation_id,
            &self.engine.mcp_tools(),
            self.cloud_api_key().as_deref(),
        ) {
            Ok(resolved) => resolved,
            Err(TurnError::ModelNotConfigured) | Err(TurnError::NoEnabledModel) => {
                self.status = TurnError::ModelNotConfigured.message().into();
                return;
            }
            Err(error) => {
                self.status = error.message().into();
                return;
            }
        };

        let timestamp = self
            .engine
            .store
            .max_message_timestamp(&conversation_id)
            .ok()
            .flatten()
            .unwrap_or(0)
            + 1;
        let message = StoredMessage {
            id: uuid::Uuid::new_v4().to_string(),
            conversation_id: conversation_id.clone(),
            role: "user".into(),
            content: text.clone(),
            parts_json: messenger_core::parts::encode_parts(&[ContentPart::Text { text: text.clone() }]),
            timestamp,
            status: "sent".into(),
            error_message: None,
        };
        if let Err(error) = self.engine.store.upsert_message(&message) {
            self.status = error.to_string();
            return;
        }
        if let Ok(Some(mut conversation)) = self.engine.store.get_conversation(&conversation_id) {
            conversation.last_message = Some(
                text.chars()
                    .take(80)
                    .collect::<String>(),
            );
            conversation.updated_at = messenger_store::now_ms();
            let _ = self.engine.store.upsert_conversation(&conversation);
        }

        self.chat.input.clear();
        self.chat.input_lines = 1;
        self.chat.scroll = 0;
        self.chat.follow = true;
        self.chat.error = None;
        self.chat.cache.clear();
        self.reload_chat_messages();
        self.engine.start_turn(resolved);
    }

    pub fn toggle_think(&mut self) {
        self.config.show_think = !self.config.show_think;
        self.chat.cache.clear();
        self.status = format!("Think blocks {}", if self.config.show_think { "expanded" } else { "collapsed" });
        self.save_config();
    }

    pub fn toggle_tool_details(&mut self) {
        self.config.show_tool_details = !self.config.show_tool_details;
        self.chat.cache.clear();
        self.status = format!(
            "Tool details {}",
            if self.config.show_tool_details { "expanded" } else { "collapsed" }
        );
        self.save_config();
    }

    pub fn switch_agent(&mut self, agent_id: &str) {
        if let Err(error) = self.engine.store.kv_set(store_ops::CURRENT_AGENT_KEY, agent_id) {
            self.status = error.to_string();
            return;
        }
        if let Some(conversation_id) = self.chat.conversation_id.clone() {
            if let Ok(Some(mut conversation)) =
                self.engine.store.get_conversation(&conversation_id)
            {
                conversation.agent_id = agent_id.to_string();
                conversation.updated_at = messenger_store::now_ms();
                let _ = self.engine.store.upsert_conversation(&conversation);
            }
        }
        self.status = "Agent switched.".into();
    }

    pub fn toggle_writable(&mut self) {
        let Some(conversation_id) = self.chat.conversation_id.clone() else {
            self.status = "Open a conversation first.".into();
            return;
        };
        if let Ok(Some(mut conversation)) = self.engine.store.get_conversation(&conversation_id) {
            conversation.writable = !conversation.writable;
            let writable = conversation.writable;
            let _ = self.engine.store.upsert_conversation(&conversation);
            self.status = format!(
                "Agent mode: {}",
                if writable { "writable" } else { "read-only" }
            );
        }
    }

    pub fn conversation_writable(&self) -> bool {
        self.chat
            .conversation_id
            .as_deref()
            .and_then(|id| self.engine.store.get_conversation(id).ok().flatten())
            .map(|conversation| conversation.writable)
            .unwrap_or(false)
    }

    fn delete_conversation(&mut self, id: &str) {
        if let Err(error) = self.engine.store.delete_messages_by_conversation(id) {
            self.status = error.to_string();
            return;
        }
        if let Err(error) = self.engine.store.delete_conversation(id) {
            self.status = error.to_string();
            return;
        }
        if self.chat.conversation_id.as_deref() == Some(id) {
            self.chat.conversation_id = None;
            self.chat.messages.clear();
            self.chat.live = None;
            self.chat.cache.clear();
        }
        self.status = "Conversation deleted.".into();
        self.reload_conversations();
    }

    fn delete_agent(&mut self, id: &str) {
        let agents = self.engine.store.list_agents().unwrap_or_default();
        if let Some(agent) = agents.iter().find(|agent| agent.id == id) {
            if agent.is_default {
                self.status = "The default Agent cannot be deleted.".into();
                return;
            }
            if agent.role == messenger_sync::ROLE_TITLE {
                self.status = "The title generator cannot be deleted (transfer the role first).".into();
                return;
            }
            if agent.id == messenger_sync::BUILTIN_TITLE_AGENT_ID {
                self.status = "The built-in title generator cannot be deleted.".into();
                return;
            }
        }
        if let Err(error) = self.engine.store.delete_agent(id) {
            self.status = error.to_string();
            return;
        }
        self.status = "Agent deleted.".into();
        self.reload_agents();
    }

    fn delete_provider(&mut self, id: &str) {
        if id == messenger_sync::BUILTIN_PROVIDER_ID {
            self.status = "The built-in cloud provider is managed by sign-in.".into();
            return;
        }
        match self.engine.store.delete_provider(id) {
            Ok(()) => {
                self.status = "Provider deleted.".into();
                self.reload_providers();
            }
            Err(error) => self.status = error.to_string(),
        }
    }

    fn persist_mcp_servers(&mut self) {
        let json = encode_server_list(&self.mcp_servers);
        if let Err(error) = self.engine.store.kv_set(store_ops::MCP_SERVERS_KEY, &json) {
            self.status = error.to_string();
            return;
        }
        let engine = Arc::clone(&self.engine);
        let servers = self.mcp_servers.clone();
        tokio::spawn(async move { engine.connect_mcp(servers).await });
    }

    // ------------------------------------------------------------------
    // cloud actions
    // ------------------------------------------------------------------

    fn login(&mut self, email: String, password: String, server_url: Option<String>) {
        let engine = Arc::clone(&self.engine);
        tokio::spawn(async move {
            if let Some(url) = server_url.filter(|url| !url.trim().is_empty()) {
                if let Err(error) = engine
                    .store
                    .kv_set(messenger_sync::KV_SERVER_URL, url.trim_end_matches('/'))
                {
                    let _ = engine.tx.send(UiMsg::CloudStatus(error.to_string()));
                    return;
                }
            }
            let session = store_ops::session_from_kv(&engine.store);
            let sync = messenger_sync::SyncEngine::new(&engine.store, session);
            match sync.login(&email, &password).await {
                Ok(user) => {
                    let _ = engine
                        .tx
                        .send(UiMsg::CloudStatus(format!("Signed in as {}", user.email)));
                    if let Err(error) = sync.ensure_builtin_provider(&user) {
                        let _ = engine.tx.send(UiMsg::ToolLog(error));
                    }
                    let _ = sync.sync_builtin_provider_models(false).await;
                    match sync.sync_internal(None, false, None).await {
                        Ok(result) => {
                            let _ = engine.tx.send(UiMsg::CloudStatus(format!(
                                "Synced: {} agents, {} conversations, {} providers",
                                result.agents, result.conversations, result.providers
                            )));
                        }
                        Err(error) => {
                            let _ = engine.tx.send(UiMsg::CloudStatus(format!("Sync failed: {error}")));
                        }
                    }
                    let _ = engine.tx.send(UiMsg::SyncFinished);
                }
                Err(error) => {
                    let _ = engine.tx.send(UiMsg::CloudStatus(format!("Sign-in failed: {error}")));
                }
            }
        });
    }

    fn logout(&mut self) {
        let engine = Arc::clone(&self.engine);
        tokio::spawn(async move {
            {
                let session = store_ops::session_from_kv(&engine.store);
                let sync = messenger_sync::SyncEngine::new(&engine.store, session);
                let _ = sync.logout().await;
                let _ = sync.remove_builtin_provider();
            }
            let _ = engine.tx.send(UiMsg::CloudStatus("Signed out.".into()));
            let _ = engine.tx.send(UiMsg::SyncFinished);
        });
    }

    fn sync_now(&mut self) {
        let engine = Arc::clone(&self.engine);
        tokio::spawn(async move {
            let session = store_ops::session_from_kv(&engine.store);
            let sync = messenger_sync::SyncEngine::new(&engine.store, session);
            if let Err(error) = sync.refresh_user().await {
                let _ = engine
                    .tx
                    .send(UiMsg::CloudStatus(format!("Not signed in: {error}")));
                return;
            }
            match sync.push_pending_changes().await {
                Ok(result) => {
                    let _ = engine.tx.send(UiMsg::CloudStatus(format!(
                        "Synced: {} agents, {} conversations, {} providers",
                        result.agents, result.conversations, result.providers
                    )));
                }
                Err(error) => {
                    let _ = engine.tx.send(UiMsg::CloudStatus(format!("Sync failed: {error}")));
                }
            }
            let _ = engine.tx.send(UiMsg::SyncFinished);
        });
    }

    fn redeem_card(&mut self, code: String) {
        let engine = Arc::clone(&self.engine);
        tokio::spawn(async move {
            let session = store_ops::session_from_kv(&engine.store);
            let sync = messenger_sync::SyncEngine::new(&engine.store, session);
            let preview = match sync.preview_redeem_card(&code).await {
                Ok(card) => Ok(Box::new(CardSnapshot {
                    plan_name: card.plan_name,
                    quota_tokens: card.quota_tokens,
                    validity_days: card.validity_days,
                })),
                Err(error) => Err(error.to_string()),
            };
            let _ = engine
                .tx
                .send(UiMsg::CardPreview { code, preview });
        });
    }

    fn confirm_redeem(&mut self, code: String) {
        let engine = Arc::clone(&self.engine);
        tokio::spawn(async move {
            let session = store_ops::session_from_kv(&engine.store);
            let sync = messenger_sync::SyncEngine::new(&engine.store, session);
            match sync.redeem_card(&code).await {
                Ok(response) => {
                    let _ = engine.tx.send(UiMsg::CloudStatus(format!(
                        "Redeemed {} ({}).",
                        response.redemption.plan_name, response.redemption.card_code
                    )));
                }
                Err(error) => {
                    let _ = engine
                        .tx
                        .send(UiMsg::CloudStatus(format!("Redemption failed: {error}")));
                }
            }
            let _ = engine.tx.send(UiMsg::SyncFinished);
        });
    }

    fn change_password(&mut self, current: String, new: String) {
        let engine = Arc::clone(&self.engine);
        tokio::spawn(async move {
            let session = store_ops::session_from_kv(&engine.store);
            let sync = messenger_sync::SyncEngine::new(&engine.store, session);
            match sync.change_password(&current, &new).await {
                Ok(()) => {
                    let _ = engine.tx.send(UiMsg::CloudStatus("Password changed.".into()));
                }
                Err(error) => {
                    let _ = engine
                        .tx
                        .send(UiMsg::CloudStatus(format!("Password change failed: {error}")));
                }
            }
        });
    }

    fn delete_account(&mut self, password: String) {
        let engine = Arc::clone(&self.engine);
        tokio::spawn(async move {
            let session = store_ops::session_from_kv(&engine.store);
            let sync = messenger_sync::SyncEngine::new(&engine.store, session);
            match sync.delete_account(&password).await {
                Ok(()) => {
                    let _ = engine.tx.send(UiMsg::CloudStatus("Account deleted.".into()));
                    let _ = engine.tx.send(UiMsg::SyncFinished);
                }
                Err(error) => {
                    let _ = engine
                        .tx
                        .send(UiMsg::CloudStatus(format!("Account deletion failed: {error}")));
                }
            }
        });
    }

    fn set_server_url(&mut self, url: String) {
        let engine = Arc::clone(&self.engine);
        tokio::spawn(async move {
            {
                let sync = messenger_sync::SyncEngine::new(
                    &engine.store,
                    store_ops::session_from_kv(&engine.store),
                );
                let _ = sync.clear_session();
                let _ = sync.remove_builtin_provider();
                let _ = engine.store.kv_delete("current_agent_id");
                match sync.set_server_url(&url) {
                    Ok(()) => {
                        let _ = engine
                            .tx
                            .send(UiMsg::CloudStatus(format!("Cloud server set to {url}.")));
                    }
                    Err(error) => {
                        let _ = engine.tx.send(UiMsg::ToolLog(error));
                    }
                }
            }
            let _ = engine.tx.send(UiMsg::SyncFinished);
        });
    }

    // ------------------------------------------------------------------
    // form submission
    // ------------------------------------------------------------------

    fn submit_form(&mut self) {
        let Some(form) = self.form.take() else {
            return;
        };
        match form.purpose.clone() {
            FormPurpose::NewConversation => {
                if form.value("Agent").is_empty() {
                    self.create_conversation();
                } else {
                    // The Agent switcher reuses the NewConversation purpose;
                    // its single Choice field selects an id from the picker.
                    let index = form.index("Agent").unwrap_or(0);
                    let picked = self
                        .pending_agent_picker
                        .take()
                        .and_then(|ids| ids.get(index).cloned());
                    match picked {
                        Some(id) => self.switch_agent(&id),
                        None => self.status = "No Agent selected.".into(),
                    }
                }
            }
            FormPurpose::RenameConversation(id) => {
                let title = form.value("Title");
                if title.trim().is_empty() {
                    self.status = "Title cannot be empty.".into();
                    self.form = Some(form);
                    return;
                }
                if let Ok(Some(mut conversation)) = self.engine.store.get_conversation(&id) {
                    conversation.title = title;
                    conversation.updated_at = messenger_store::now_ms();
                    let _ = self.engine.store.upsert_conversation(&conversation);
                    self.reload_conversations();
                }
            }
            FormPurpose::NewProvider | FormPurpose::EditProvider(_) => {
                let name = form.value("Name");
                let base_url = form.value("Base URL");
                let api_key = form.value("API key");
                if name.trim().is_empty() {
                    self.status = "Provider name cannot be empty.".into();
                    self.form = Some(form);
                    return;
                }
                if !(base_url.starts_with("http://") || base_url.starts_with("https://")) {
                    self.status = "Base URL must start with http:// or https://".into();
                    self.form = Some(form);
                    return;
                }
                let existing = match &form.purpose {
                    FormPurpose::EditProvider(id) => {
                        self.engine.store.get_provider(id).ok().flatten()
                    }
                    _ => None,
                };
                let now = messenger_store::now_ms();
                let provider = StoredProvider {
                    id: existing
                        .as_ref()
                        .map(|provider| provider.id.clone())
                        .unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
                    name,
                    base_url,
                    api_key,
                    created_at: existing.as_ref().map(|p| p.created_at).unwrap_or(now),
                    updated_at: now,
                };
                match self.engine.store.upsert_provider(&provider) {
                    Ok(()) => {
                        self.status = format!("Saved provider {}.", provider.name);
                        self.reload_providers();
                    }
                    Err(error) => self.status = error.to_string(),
                }
            }
            FormPurpose::NewModel(provider_id) | FormPurpose::EditModel { provider_id, .. } => {
                let model_id = form.value("Model ID");
                if model_id.trim().is_empty() {
                    self.status = "Model ID cannot be empty.".into();
                    self.form = Some(form);
                    return;
                }
                let display_name = {
                    let raw = form.value("Display name");
                    if raw.trim().is_empty() {
                        model_id.clone()
                    } else {
                        raw
                    }
                };
                let existing = match &form.purpose {
                    FormPurpose::EditModel { model_id, .. } => {
                        self.engine.store.get_model(model_id).ok().flatten()
                    }
                    _ => None,
                };
                let now = messenger_store::now_ms();
                let model = StoredModel {
                    id: existing
                        .as_ref()
                        .map(|model| model.id.clone())
                        .unwrap_or_else(|| format!("{provider_id}:{model_id}")),
                    provider_id: provider_id.clone(),
                    model_id,
                    display_name,
                    is_enabled: form.boolean("Enabled"),
                    context_window: form
                        .number("Context window")
                        .map(|value| value as i64)
                        .unwrap_or(0),
                    input_rate: existing.as_ref().and_then(|m| m.input_rate),
                    output_rate: existing.as_ref().and_then(|m| m.output_rate),
                    input_modalities: existing
                        .as_ref()
                        .map(|m| m.input_modalities.clone())
                        .unwrap_or_else(|| "text".into()),
                    output_modalities: existing
                        .as_ref()
                        .map(|m| m.output_modalities.clone())
                        .unwrap_or_else(|| "text".into()),
                    supports_tool_calling: existing.as_ref().is_some_and(|m| m.supports_tool_calling),
                    supports_thinking: existing.as_ref().is_some_and(|m| m.supports_thinking),
                    supports_json_output: existing.as_ref().is_some_and(|m| m.supports_json_output),
                    supports_temperature: existing.as_ref().is_some_and(|m| m.supports_temperature),
                    created_at: existing.as_ref().map(|m| m.created_at).unwrap_or(now),
                };
                match self.engine.store.upsert_model(&model) {
                    Ok(()) => {
                        self.status = format!("Saved model {}.", model.model_id);
                        self.reload_models();
                    }
                    Err(error) => self.status = error.to_string(),
                }
            }
            FormPurpose::NewAgent | FormPurpose::EditAgent(_) => {
                self.submit_agent_form(form);
            }
            FormPurpose::NewMcpServer | FormPurpose::EditMcpServer(_) => {
                self.submit_mcp_form(form);
            }
            FormPurpose::SignIn => {
                let email = form.value("Email");
                let password = form.value("Password");
                if email.trim().is_empty() {
                    self.status = "Email cannot be empty.".into();
                    self.form = Some(form);
                    return;
                }
                let server = form.value("Server URL");
                self.login(email, password, Some(server));
            }
            FormPurpose::RedeemCard => {
                let code = form.value("Card code");
                if code.trim().is_empty() {
                    self.status = "Card code cannot be empty.".into();
                    self.form = Some(form);
                    return;
                }
                self.status = "Previewing card…".into();
                self.redeem_card(code);
            }
            FormPurpose::DeleteAccountForm => {
                let password = form.value("Current password");
                if password.trim().is_empty() {
                    self.status = "Password cannot be empty.".into();
                    self.form = Some(form);
                    return;
                }
                self.confirm = Some(
                    Confirm::new(
                        "Delete account",
                        "This permanently deletes the cloud account and all synced data.",
                        ConfirmPurpose::DeleteAccount(password),
                    )
                    .requires("DELETE"),
                );
            }
            FormPurpose::ChangePassword => {
                let current = form.value("Current password");
                let new = form.value("New password");
                if new.trim().is_empty() {
                    self.status = "New password cannot be empty.".into();
                    self.form = Some(form);
                    return;
                }
                self.change_password(current, new);
            }
            FormPurpose::EditServerUrl => {
                let url = form.value("Server URL");
                if url.trim().is_empty() {
                    self.status = "Server URL cannot be empty.".into();
                    self.form = Some(form);
                    return;
                }
                self.set_server_url(url);
            }
            FormPurpose::FilterConversations => {
                let filter = form.value("Filter");
                self.conversation_filter = if filter.trim().is_empty() {
                    None
                } else {
                    Some(filter)
                };
                self.reload_conversations();
            }
            FormPurpose::EditWorkspace => {
                let workspace = form.value("Workspace directory");
                if workspace.trim().is_empty() {
                    self.status = "Workspace directory cannot be empty.".into();
                    self.form = Some(form);
                    return;
                }
                self.config.workspace_dir = workspace;
                self.save_config();
                self.status = "Workspace updated (restart to apply to the tool host).".into();
            }
        }
    }

    fn submit_agent_form(&mut self, form: Form) {
        let name = form.value("Name");
        if name.trim().is_empty() {
            self.status = "Agent name cannot be empty.".into();
            self.form = Some(form);
            return;
        }
        let existing = match &form.purpose {
            FormPurpose::EditAgent(id) => self.engine.store.get_agent(id).ok().flatten(),
            _ => None,
        };
        let now = messenger_store::now_ms();
        let model_index = form.index("Model").unwrap_or(0);
        let model_id = if model_index == 0 {
            None
        } else {
            self.engine
                .store
                .list_models()
                .unwrap_or_default()
                .get(model_index - 1)
                .map(|model| model.id.clone())
        };
        let role_choice = form.index("Role").unwrap_or(0);
        let (is_default, role) = match role_choice {
            1 => (true, "chat".to_string()),
            2 => (false, "title".to_string()),
            _ => (false, "chat".to_string()),
        };
        let tools_enabled = form.boolean("Tools enabled");
        let tools_follow_default = form.boolean("Follow default Agent tools");

        // Single-holder roles: the previous holder falls back to a regular
        // Agent (mirrors `AgentEditViewModel.save`).
        let others: Vec<StoredAgent> = self
            .engine
            .store
            .list_agents()
            .unwrap_or_default()
            .into_iter()
            .filter(|agent| Some(agent.id.clone()) != existing.as_ref().map(|a| a.id.clone()))
            .collect();
        for mut other in others {
            let mut changed = false;
            if is_default && other.is_default {
                other.is_default = false;
                changed = true;
            }
            if role == "title" && other.role == "title" {
                other.role = "chat".into();
                changed = true;
            }
            if changed {
                let _ = self.engine.store.upsert_agent(&other);
            }
        }

        let agent = StoredAgent {
            id: existing
                .as_ref()
                .map(|agent| agent.id.clone())
                .unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
            name,
            avatar: existing.as_ref().and_then(|agent| agent.avatar.clone()),
            system_prompt: form.value("System prompt"),
            description: form.value("Description"),
            default_model_id: model_id,
            temperature: form.number("Temperature"),
            top_p: form.number("Top P"),
            max_tokens: form.number("Max tokens").map(|value| value as i64),
            reasoning_effort: {
                let raw = form.value("Reasoning effort");
                if raw.trim().is_empty() {
                    None
                } else {
                    Some(raw)
                }
            },
            is_default,
            follow_default_system_prompt: form.boolean("Follow default system prompt"),
            follow_default_model: form.boolean("Follow default model"),
            follow_default_temperature: form.boolean("Follow default temperature"),
            follow_default_top_p: form.boolean("Follow default Top P"),
            follow_default_max_tokens: form.boolean("Follow default max tokens"),
            follow_default_reasoning_effort: form.boolean("Follow default reasoning effort"),
            market_agent_id: existing.as_ref().and_then(|agent| agent.market_agent_id.clone()),
            market_agent_version: existing.as_ref().and_then(|agent| agent.market_agent_version),
            market_agent_role: existing.as_ref().and_then(|agent| agent.market_agent_role.clone()),
            role,
            tools_enabled,
            tools_follow_default,
            tools_config: {
                let mut config = std::collections::HashMap::new();
                for tool in messenger_tools::builtin_registry() {
                    let label = format!("tool:{}", tool.name);
                    if !form.boolean(&label) {
                        config.insert(tool.name.clone(), false);
                    }
                }
                serde_json::to_string(&config).unwrap_or_else(|_| String::new())
            },
            created_at: existing.as_ref().map(|agent| agent.created_at).unwrap_or(now),
            updated_at: now,
        };
        match self.engine.store.upsert_agent(&agent) {
            Ok(()) => {
                self.status = format!("Saved Agent {}.", agent.name);
                self.reload_agents();
            }
            Err(error) => self.status = error.to_string(),
        }
    }

    fn submit_mcp_form(&mut self, form: Form) {
        let name = form.value("Name");
        if name.trim().is_empty() {
            self.status = "Server name cannot be empty.".into();
            self.form = Some(form);
            return;
        }
        let transport_index = form.index("Transport").unwrap_or(0);
        let transport_type = if transport_index == 1 {
            McpTransportType::SSE
        } else {
            McpTransportType::STDIO
        };
        let existing = match &form.purpose {
            FormPurpose::EditMcpServer(id) => {
                self.mcp_servers.iter().find(|server| &server.id == id).cloned()
            }
            _ => None,
        };
        let id = existing
            .as_ref()
            .map(|server| server.id.clone())
            .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
        let headers: std::collections::HashMap<String, String> = form
            .value("Headers")
            .split(',')
            .filter_map(|pair| pair.split_once('='))
            .map(|(key, value)| (key.trim().to_string(), value.trim().to_string()))
            .filter(|(key, _)| !key.is_empty())
            .collect();
        let server = McpServerConfig {
            id: id.clone(),
            name,
            transport_type,
            is_enabled: form.boolean("Enabled"),
            command: form.value("Command"),
            args: form
                .value("Arguments")
                .split_whitespace()
                .map(str::to_string)
                .collect(),
            env: existing.as_ref().map(|s| s.env.clone()).unwrap_or_default(),
            url: form.value("URL"),
            headers,
        };
        match self.mcp_servers.iter_mut().find(|entry| entry.id == id) {
            Some(entry) => *entry = server,
            None => self.mcp_servers.push(server),
        }
        self.persist_mcp_servers();
        self.status = "MCP servers updated.".into();
    }

    // ------------------------------------------------------------------
    // form construction helpers
    // ------------------------------------------------------------------

    fn open_agent_form(&mut self, agent_id: Option<&str>) {
        let models = self.engine.store.list_models().unwrap_or_default();
        let mut model_options = vec!["(none)".to_string()];
        model_options.extend(models.iter().map(|model| model.display_name.clone()));
        let existing = agent_id
            .and_then(|id| self.engine.store.get_agent(id).ok().flatten());
        let model_selected = existing
            .as_ref()
            .and_then(|agent| agent.default_model_id.as_ref())
            .and_then(|id| models.iter().position(|model| &model.id == id))
            .map(|index| index + 1)
            .unwrap_or(0);
        let role_selected = match existing.as_ref() {
            Some(agent) if agent.is_default => 1,
            Some(agent) if agent.role == messenger_sync::ROLE_TITLE => 2,
            _ => 0,
        };
        let tools_config = store_ops::tools_config_map(
            existing
                .as_ref()
                .map(|agent| agent.tools_config.as_str())
                .unwrap_or(""),
        );
        let mut fields = vec![
            Field::text("Name", existing.as_ref().map(|a| a.name.clone()).unwrap_or_default()),
            Field::text(
                "Description",
                existing.as_ref().map(|a| a.description.clone()).unwrap_or_default(),
            ),
            Field::multiline(
                "System prompt",
                existing
                    .as_ref()
                    .map(|a| a.system_prompt.clone())
                    .unwrap_or_else(|| "You are a helpful assistant.".into()),
            ),
            Field::choice("Model", model_options, model_selected),
            Field::choice(
                "Role",
                vec!["Regular".into(), "Default".into(), "Title Generator".into()],
                role_selected,
            ),
            Field::text(
                "Temperature",
                existing
                    .as_ref()
                    .and_then(|a| a.temperature)
                    .map(|value| value.to_string())
                    .unwrap_or_default(),
            ),
            Field::text(
                "Top P",
                existing
                    .as_ref()
                    .and_then(|a| a.top_p)
                    .map(|value| value.to_string())
                    .unwrap_or_default(),
            ),
            Field::text(
                "Max tokens",
                existing
                    .as_ref()
                    .and_then(|a| a.max_tokens)
                    .map(|value| value.to_string())
                    .unwrap_or_default(),
            ),
            Field::text(
                "Reasoning effort",
                existing
                    .as_ref()
                    .and_then(|a| a.reasoning_effort.clone())
                    .unwrap_or_default(),
            ),
            Field::boolean(
                "Follow default system prompt",
                existing.as_ref().is_some_and(|a| a.follow_default_system_prompt),
            ),
            Field::boolean(
                "Follow default model",
                existing.as_ref().is_some_and(|a| a.follow_default_model),
            ),
            Field::boolean(
                "Follow default temperature",
                existing.as_ref().is_some_and(|a| a.follow_default_temperature),
            ),
            Field::boolean(
                "Follow default Top P",
                existing.as_ref().is_some_and(|a| a.follow_default_top_p),
            ),
            Field::boolean(
                "Follow default max tokens",
                existing.as_ref().is_some_and(|a| a.follow_default_max_tokens),
            ),
            Field::boolean(
                "Follow default reasoning effort",
                existing.as_ref().is_some_and(|a| a.follow_default_reasoning_effort),
            ),
            Field::boolean(
                "Tools enabled",
                existing.as_ref().is_some_and(|a| a.tools_enabled),
            ),
            Field::boolean(
                "Follow default Agent tools",
                existing.as_ref().is_some_and(|a| a.tools_follow_default),
            ),
        ];
        for tool in messenger_tools::builtin_registry() {
            fields.push(Field::boolean(
                &format!("tool:{}", tool.name),
                tools_config.get(&tool.name).copied().unwrap_or(true),
            ));
        }
        self.form = Some(Form::new(
            "Agent",
            match agent_id {
                Some(id) => FormPurpose::EditAgent(id.to_string()),
                None => FormPurpose::NewAgent,
            },
            fields,
        ));
    }

    fn open_provider_form(&mut self, provider_id: Option<&str>) {
        let existing = provider_id.and_then(|id| self.engine.store.get_provider(id).ok().flatten());
        let fields = vec![
            Field::text("Name", existing.as_ref().map(|p| p.name.clone()).unwrap_or_default()),
            Field::text(
                "Base URL",
                existing
                    .as_ref()
                    .map(|p| p.base_url.clone())
                    .unwrap_or_default(),
            ),
            Field::secret(
                "API key",
                existing.as_ref().map(|p| p.api_key.clone()).unwrap_or_default(),
            ),
        ];
        self.form = Some(Form::new(
            "Provider",
            match provider_id {
                Some(id) => FormPurpose::EditProvider(id.to_string()),
                None => FormPurpose::NewProvider,
            },
            fields,
        ));
    }

    fn open_model_form(&mut self, provider_id: &str, model_id: Option<&str>) {
        let existing = model_id.and_then(|id| self.engine.store.get_model(id).ok().flatten());
        let fields = vec![
            Field::text(
                "Model ID",
                existing
                    .as_ref()
                    .map(|m| m.model_id.clone())
                    .unwrap_or_default(),
            ),
            Field::text(
                "Display name",
                existing
                    .as_ref()
                    .map(|m| m.display_name.clone())
                    .unwrap_or_default(),
            ),
            Field::text(
                "Context window",
                existing
                    .as_ref()
                    .filter(|m| m.context_window > 0)
                    .map(|m| m.context_window.to_string())
                    .unwrap_or_default(),
            ),
            Field::boolean("Enabled", existing.as_ref().is_some_and(|m| m.is_enabled)),
        ];
        self.form = Some(Form::new(
            "Model",
            match model_id {
                Some(model_id) => FormPurpose::EditModel {
                    provider_id: provider_id.to_string(),
                    model_id: model_id.to_string(),
                },
                None => FormPurpose::NewModel(provider_id.to_string()),
            },
            fields,
        ));
    }

    fn open_mcp_form(&mut self, server_id: Option<&str>) {
        let existing = server_id.and_then(|id| {
            self.mcp_servers
                .iter()
                .find(|server| server.id == id)
                .cloned()
        });
        let fields = vec![
            Field::text("Name", existing.as_ref().map(|s| s.name.clone()).unwrap_or_default()),
            Field::choice(
                "Transport",
                vec!["Command (stdio)".into(), "SSE / HTTP".into()],
                match existing.as_ref().map(|s| s.transport_type) {
                    Some(McpTransportType::SSE) => 1,
                    _ => 0,
                },
            ),
            Field::text(
                "Command",
                existing.as_ref().map(|s| s.command.clone()).unwrap_or_default(),
            ),
            Field::text(
                "Arguments",
                existing
                    .as_ref()
                    .map(|s| s.args.join(" "))
                    .unwrap_or_default(),
            ),
            Field::text("URL", existing.as_ref().map(|s| s.url.clone()).unwrap_or_default()),
            Field::text(
                "Headers",
                existing
                    .as_ref()
                    .map(|s| {
                        s.headers
                            .iter()
                            .map(|(key, value)| format!("{key}={value}"))
                            .collect::<Vec<_>>()
                            .join(",")
                    })
                    .unwrap_or_default(),
            ),
            Field::boolean("Enabled", existing.as_ref().is_none_or(|s| s.is_enabled)),
        ];
        self.form = Some(Form::new(
            "MCP server",
            match server_id {
                Some(id) => FormPurpose::EditMcpServer(id.to_string()),
                None => FormPurpose::NewMcpServer,
            },
            fields,
        ));
    }

    // ------------------------------------------------------------------
    // keys
    // ------------------------------------------------------------------

    pub fn handle_key(&mut self, key: KeyEvent) {
        // Ctrl+C always quits and always restores the terminal.
        if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
            self.should_quit = true;
            return;
        }
        if let Some(confirm) = self.confirm.clone() {
            self.handle_confirm_key(key, confirm);
            return;
        }
        if let Some(form) = self.form.take() {
            self.handle_form_key(key, form);
            return;
        }
        if self.help {
            self.help = false;
            return;
        }
        if let Some(view) = View::from_function_key(key.code) {
            self.view = view;
            return;
        }
        match key.code {
            KeyCode::Char('?') => self.help = true,
            KeyCode::Char('t') if key.modifiers.contains(KeyModifiers::CONTROL) => {
                self.toggle_think()
            }
            KeyCode::Char('o') if key.modifiers.contains(KeyModifiers::CONTROL) => {
                self.toggle_tool_details()
            }
            _ => match self.view {
                View::Conversations => self.handle_conversations_key(key),
                View::Chat => self.handle_chat_key(key),
                View::Agents => self.handle_agents_key(key),
                View::Providers => self.handle_providers_key(key),
                View::Mcp => self.handle_mcp_key(key),
                View::Settings => self.handle_settings_key(key),
            },
        }
    }

    fn handle_confirm_key(&mut self, key: KeyEvent, mut confirm: Confirm) {
        match key.code {
            KeyCode::Esc => {
                self.confirm = None;
                return;
            }
            KeyCode::Backspace => {
                confirm.typed.pop();
            }
            KeyCode::Char(ch) if !key.modifiers.contains(KeyModifiers::CONTROL) => {
                if confirm.requires_typing.is_some() {
                    confirm.typed.push(ch);
                }
            }
            KeyCode::Enter => {
                if !confirm.can_confirm() {
                    self.status = format!(
                        "Type {} to confirm.",
                        confirm.requires_typing.clone().unwrap_or_default()
                    );
                    self.confirm = Some(confirm);
                    return;
                }
                self.confirm = None;
                let purpose = confirm.purpose.clone();
                match purpose {
                    ConfirmPurpose::DeleteConversation(id) => self.delete_conversation(&id),
                    ConfirmPurpose::DeleteProvider(id) => self.delete_provider(&id),
                    ConfirmPurpose::DeleteModel {
                        provider_id,
                        model_id,
                    } => {
                        let _ = provider_id;
                        if let Err(error) = self.engine.store.delete_model(&model_id) {
                            self.status = error.to_string();
                        }
                        self.reload_models();
                    }
                    ConfirmPurpose::DeleteAgent(id) => self.delete_agent(&id),
                    ConfirmPurpose::DeleteMcpServer(id) => {
                        self.mcp_servers.retain(|server| server.id != id);
                        self.persist_mcp_servers();
                        self.status = "MCP server removed.".into();
                    }
                    ConfirmPurpose::RedeemCard(code) => self.confirm_redeem(code),
                    ConfirmPurpose::DeleteAccount(password) => self.delete_account(password),
                    ConfirmPurpose::SignOut => self.logout(),
                }
                return;
            }
            _ => {}
        }
        self.confirm = Some(confirm);
    }

    fn handle_form_key(&mut self, key: KeyEvent, mut form: Form) {
        let control = key.modifiers.contains(KeyModifiers::CONTROL);
        let alt = key.modifiers.contains(KeyModifiers::ALT);
        match key.code {
            KeyCode::Esc => return,
            KeyCode::Tab => {
                form.focus = (form.focus + 1) % form.fields.len().max(1);
            }
            KeyCode::BackTab => {
                form.focus = form
                    .focus
                    .checked_sub(1)
                    .unwrap_or(form.fields.len().saturating_sub(1));
            }
            KeyCode::Down => {
                form.focus = (form.focus + 1) % form.fields.len().max(1);
            }
            KeyCode::Up => {
                form.focus = form
                    .focus
                    .checked_sub(1)
                    .unwrap_or(form.fields.len().saturating_sub(1));
            }
            KeyCode::Char('s') if control => {
                self.form = Some(form);
                self.submit_form();
                return;
            }
            KeyCode::Enter if alt => {
                if let Some(Field::Text {
                    value,
                    multiline: true,
                    ..
                }) = form.focused_mut()
                {
                    value.push('\n');
                }
            }
            KeyCode::Enter => {
                // A multiline field inserts a newline; Ctrl+S submits from
                // anywhere, and the last field's Enter also submits.
                match form.focused() {
                    Some(Field::Text { multiline: true, .. }) => {
                        if let Some(Field::Text { value, .. }) = form.focused_mut() {
                            value.push('\n');
                        }
                    }
                    Some(Field::Bool { .. }) | Some(Field::Choice { .. }) => {
                        toggle_field(&mut form);
                    }
                    _ => {
                        if form.focus + 1 >= form.fields.len() {
                            self.form = Some(form);
                            self.submit_form();
                            return;
                        }
                        form.focus += 1;
                    }
                }
            }
            KeyCode::Char('j') if control => {
                if let Some(Field::Text {
                    value,
                    multiline: true,
                    ..
                }) = form.focused_mut()
                {
                    value.push('\n');
                }
            }
            KeyCode::Char(' ') => match form.focused() {
                Some(Field::Bool { .. }) | Some(Field::Choice { .. }) => toggle_field(&mut form),
                _ => {
                    if let Some(Field::Text { value, .. }) = form.focused_mut() {
                        value.push(' ');
                    }
                }
            },
            KeyCode::Char(ch) if !control => {
                if let Some(Field::Text { value, .. }) = form.focused_mut() {
                    value.push(ch);
                }
            }
            KeyCode::Backspace => {
                if let Some(Field::Text { value, .. }) = form.focused_mut() {
                    value.pop();
                }
            }
            _ => {}
        }
        self.form = Some(form);
    }

    fn handle_conversations_key(&mut self, key: KeyEvent) {
        match key.code {
            KeyCode::Char('q') => self.should_quit = true,
            KeyCode::Down | KeyCode::Char('j') => {
                if !self.conversations.is_empty() {
                    self.conversation_selection =
                        (self.conversation_selection + 1).min(self.conversations.len() - 1);
                }
            }
            KeyCode::Up | KeyCode::Char('k') => {
                self.conversation_selection = self.conversation_selection.saturating_sub(1);
            }
            KeyCode::Enter => {
                if let Some(conversation) = self.conversations.get(self.conversation_selection) {
                    let id = conversation.id.clone();
                    self.open_conversation(&id);
                }
            }
            KeyCode::Char('n') => {
                self.form = Some(Form::new(
                    "New conversation",
                    FormPurpose::NewConversation,
                    vec![Field::text("Title hint", "")],
                ));
            }
            KeyCode::Char('r') => {
                if let Some(conversation) = self.conversations.get(self.conversation_selection) {
                    self.form = Some(Form::new(
                        "Rename conversation",
                        FormPurpose::RenameConversation(conversation.id.clone()),
                        vec![Field::text("Title", conversation.title.clone())],
                    ));
                }
            }
            KeyCode::Char('d') => {
                if let Some(conversation) = self.conversations.get(self.conversation_selection) {
                    self.confirm = Some(Confirm::new(
                        "Delete conversation",
                        format!("Delete “{}” and all its messages?", conversation.title),
                        ConfirmPurpose::DeleteConversation(conversation.id.clone()),
                    ));
                }
            }
            KeyCode::Char('/') => {
                self.form = Some(Form::new(
                    "Filter conversations",
                    FormPurpose::FilterConversations,
                    vec![Field::text(
                        "Filter",
                        self.conversation_filter.clone().unwrap_or_default(),
                    )],
                ));
            }
            _ => {}
        }
    }

    fn handle_chat_key(&mut self, key: KeyEvent) {
        let alt = key.modifiers.contains(KeyModifiers::ALT);
        let control = key.modifiers.contains(KeyModifiers::CONTROL);
        match key.code {
            KeyCode::Esc => {
                if self.chat.is_generating {
                    self.engine.cancel_turn();
                    self.status = "Cancelling…".into();
                }
            }
            KeyCode::Enter if !alt && !control => self.send_message(),
            KeyCode::Enter => {
                // Alt+Enter (and the terminal's Ctrl+J, which arrives as a
                // plain '\n' char) inserts a newline instead of sending.
                self.chat.input.push('\n');
                self.chat.input_lines = self.chat.input.lines().count().clamp(1, 8);
            }
            // Chat commands are all Ctrl-modified: every unmodified printable
            // key belongs to the message input, otherwise the user could not
            // type "n", "a", "w", "q"… in a message.
            KeyCode::Char('n') if control => {
                self.create_conversation();
            }
            KeyCode::Char('a') if control => self.open_agent_picker(),
            KeyCode::Char('w') if control => self.toggle_writable(),
            KeyCode::Char('g') if control => {
                self.chat.scroll = u16::MAX;
                self.chat.follow = false;
            }
            KeyCode::Char('u') if control => {
                self.chat.scroll = self.chat.scroll.saturating_add(10);
                self.chat.follow = false;
            }
            KeyCode::Char('d') if control => {
                self.chat.scroll = self.chat.scroll.saturating_sub(10);
            }
            KeyCode::PageUp => {
                self.chat.scroll = self.chat.scroll.saturating_add(10);
                self.chat.follow = false;
            }
            KeyCode::PageDown => {
                self.chat.scroll = self.chat.scroll.saturating_sub(10);
            }
            KeyCode::Home => {
                self.chat.scroll = u16::MAX;
                self.chat.follow = false;
            }
            KeyCode::End => {
                self.chat.scroll = 0;
                self.chat.follow = true;
            }
            KeyCode::Up => {
                self.chat.scroll = self.chat.scroll.saturating_add(1);
                self.chat.follow = false;
            }
            KeyCode::Down => {
                self.chat.scroll = self.chat.scroll.saturating_sub(1);
            }
            KeyCode::Backspace => {
                self.chat.input.pop();
                self.chat.input_lines = self.chat.input.lines().count().clamp(1, 8);
            }
            KeyCode::Tab => {
                self.chat.input.push('\t');
            }
            KeyCode::Char(ch) if !control => {
                self.chat.input.push(ch);
                self.chat.input_lines = self.chat.input.lines().count().clamp(1, 8);
            }
            _ => {}
        }
    }

    fn open_agent_picker(&mut self) {
        // The Agent switcher is implemented as a choice form so it reuses the
        // modal keyboard handling (no separate modal type).
        let agents: Vec<StoredAgent> = self
            .agents
            .iter()
            .filter(|agent| agent.role != messenger_sync::ROLE_TITLE)
            .cloned()
            .collect();
        if agents.is_empty() {
            self.status = "No selectable Agent.".into();
            return;
        }
        let current = store_ops::current_agent(&self.engine.store).ok().flatten();
        let selected = current
            .and_then(|agent| agents.iter().position(|candidate| candidate.id == agent.id))
            .unwrap_or(0);
        let options: Vec<String> = agents
            .iter()
            .map(|agent| format!("{} ({})", agent.name, agent.id))
            .collect();
        self.pending_agent_picker = Some(agents.iter().map(|agent| agent.id.clone()).collect());
        self.form = Some(Form::new(
            "Switch Agent",
            FormPurpose::NewConversation,
            vec![Field::choice("Agent", options, selected)],
        ));
    }

    fn handle_agents_key(&mut self, key: KeyEvent) {
        match key.code {
            KeyCode::Char('q') => self.should_quit = true,
            KeyCode::Down | KeyCode::Char('j') => {
                if !self.agents.is_empty() {
                    self.agent_selection = (self.agent_selection + 1).min(self.agents.len() - 1);
                }
            }
            KeyCode::Up | KeyCode::Char('k') => {
                self.agent_selection = self.agent_selection.saturating_sub(1);
            }
            KeyCode::Enter | KeyCode::Char('a') => {
                let id = self.agents.get(self.agent_selection).map(|agent| agent.id.clone());
                self.open_agent_form(id.as_deref());
            }
            KeyCode::Char('n') => self.open_agent_form(None),
            KeyCode::Char('d') => {
                if let Some(agent) = self.agents.get(self.agent_selection) {
                    self.confirm = Some(Confirm::new(
                        "Delete Agent",
                        format!("Delete Agent “{}”?", agent.name),
                        ConfirmPurpose::DeleteAgent(agent.id.clone()),
                    ));
                }
            }
            KeyCode::Char('s') => {
                if let Some(agent) = self.agents.get(self.agent_selection) {
                    let id = agent.id.clone();
                    if let Err(error) = self.engine.store.kv_set(store_ops::CURRENT_AGENT_KEY, &id)
                    {
                        self.status = error.to_string();
                    } else {
                        self.status = format!("Selected Agent {}.", agent.name);
                    }
                }
            }
            _ => {}
        }
    }

    fn handle_providers_key(&mut self, key: KeyEvent) {
        match key.code {
            KeyCode::Char('q') => self.should_quit = true,
            KeyCode::Tab => {
                // Switch focus between the list and the model pane.
                self.provider_focus_models = !self.provider_focus_models;
            }
            KeyCode::Down | KeyCode::Char('j') => {
                if self.provider_focus_models {
                    if !self.models.is_empty() {
                        self.model_selection = (self.model_selection + 1).min(self.models.len() - 1);
                    }
                } else if !self.providers.is_empty() {
                    self.provider_selection =
                        (self.provider_selection + 1).min(self.providers.len() - 1);
                    self.reload_models();
                }
            }
            KeyCode::Up | KeyCode::Char('k') => {
                if self.provider_focus_models {
                    self.model_selection = self.model_selection.saturating_sub(1);
                } else {
                    self.provider_selection = self.provider_selection.saturating_sub(1);
                    self.reload_models();
                }
            }
            KeyCode::Char(' ') => {
                if self.provider_focus_models {
                    if let Some(model) = self.models.get(self.model_selection) {
                        let enabled = !model.is_enabled;
                        if let Err(error) = self.engine.store.set_model_enabled(&model.id, enabled) {
                            self.status = error.to_string();
                        } else {
                            self.reload_models();
                        }
                    }
                }
            }
            KeyCode::Enter => {
                if !self.provider_focus_models {
                    let id = self
                        .providers
                        .get(self.provider_selection)
                        .map(|provider| provider.id.clone());
                    self.open_provider_form(id.as_deref());
                } else {
                    let provider_id = self
                        .providers
                        .get(self.provider_selection)
                        .map(|provider| provider.id.clone());
                    let model_id = self.models.get(self.model_selection).map(|m| m.id.clone());
                    if let (Some(provider_id), Some(model_id)) = (provider_id, model_id) {
                        self.open_model_form(&provider_id, Some(&model_id));
                    }
                }
            }
            KeyCode::Char('n') => self.open_provider_form(None),
            KeyCode::Char('a') => {
                if let Some(provider) = self.providers.get(self.provider_selection) {
                    let id = provider.id.clone();
                    self.open_model_form(&id, None);
                }
            }
            KeyCode::Char('s') => self.fetch_models(),
            KeyCode::Char('d') => {
                if self.provider_focus_models {
                    if let (Some(provider), Some(model)) = (
                        self.providers.get(self.provider_selection),
                        self.models.get(self.model_selection),
                    ) {
                        self.confirm = Some(Confirm::new(
                            "Delete model",
                            format!("Delete model “{}”?", model.display_name),
                            ConfirmPurpose::DeleteModel {
                                provider_id: provider.id.clone(),
                                model_id: model.id.clone(),
                            },
                        ));
                    }
                } else if let Some(provider) = self.providers.get(self.provider_selection) {
                    self.confirm = Some(Confirm::new(
                        "Delete provider",
                        format!("Delete provider “{}” and its models?", provider.name),
                        ConfirmPurpose::DeleteProvider(provider.id.clone()),
                    ));
                }
            }
            _ => {}
        }
    }

    /// Fetch the provider's `GET /models` and store every entry as a
    /// currently-disabled model (mirrors `ProviderDetailViewModel.syncModels`).
    fn fetch_models(&mut self) {
        let Some(provider) = self.providers.get(self.provider_selection).cloned() else {
            return;
        };
        let engine = Arc::clone(&self.engine);
        tokio::spawn(async move {
            let client = messenger_llm::client::OpenAiClient::new(&provider.base_url, &provider.api_key);
            match client.get_models().await {
                Ok(response) => {
                    let now = messenger_store::now_ms();
                    let existing = engine
                        .store
                        .list_models_by_provider(&provider.id)
                        .unwrap_or_default();
                    let mut added = 0usize;
                    for entry in response.data {
                        let id = format!("{}:{}", provider.id, entry.id);
                        let previous = existing.iter().find(|model| model.id == id);
                        let model = StoredModel {
                            id: id.clone(),
                            provider_id: provider.id.clone(),
                            model_id: entry.id.clone(),
                            display_name: entry.id.clone(),
                            is_enabled: previous.map(|m| m.is_enabled).unwrap_or(false),
                            context_window: entry.context_window.unwrap_or_else(|| {
                                previous.map(|m| m.context_window).unwrap_or(0)
                            }),
                            input_rate: entry.input_rate.or_else(|| previous.and_then(|m| m.input_rate)),
                            output_rate: entry
                                .output_rate
                                .or_else(|| previous.and_then(|m| m.output_rate)),
                            input_modalities: previous
                                .map(|m| m.input_modalities.clone())
                                .unwrap_or_else(|| "text".into()),
                            output_modalities: previous
                                .map(|m| m.output_modalities.clone())
                                .unwrap_or_else(|| "text".into()),
                            supports_tool_calling: previous.is_some_and(|m| m.supports_tool_calling),
                            supports_thinking: previous.is_some_and(|m| m.supports_thinking),
                            supports_json_output: previous.is_some_and(|m| m.supports_json_output),
                            supports_temperature: previous.is_some_and(|m| m.supports_temperature),
                            created_at: previous.map(|m| m.created_at).unwrap_or(now),
                        };
                        if engine.store.upsert_model(&model).is_ok() {
                            added += 1;
                        }
                    }
                    let _ = engine
                        .tx
                        .send(UiMsg::CloudStatus(format!("{added} models fetched.")));
                    let _ = engine.tx.send(UiMsg::SyncFinished);
                }
                Err(error) => {
                    let _ = engine
                        .tx
                        .send(UiMsg::CloudStatus(format!("Model fetch failed: {error}")));
                }
            }
        });
        self.status = "Fetching models…".into();
    }

    fn handle_mcp_key(&mut self, key: KeyEvent) {
        match key.code {
            KeyCode::Char('q') => self.should_quit = true,
            KeyCode::Down | KeyCode::Char('j') => {
                if !self.mcp_servers.is_empty() {
                    self.mcp_selection = (self.mcp_selection + 1).min(self.mcp_servers.len() - 1);
                }
            }
            KeyCode::Up | KeyCode::Char('k') => {
                self.mcp_selection = self.mcp_selection.saturating_sub(1);
            }
            KeyCode::Enter | KeyCode::Char('a') => {
                let id = self
                    .mcp_servers
                    .get(self.mcp_selection)
                    .map(|server| server.id.clone());
                self.open_mcp_form(id.as_deref());
            }
            KeyCode::Char('n') => self.open_mcp_form(None),
            KeyCode::Char(' ') => {
                if let Some(server) = self.mcp_servers.get_mut(self.mcp_selection) {
                    server.is_enabled = !server.is_enabled;
                    self.persist_mcp_servers();
                }
            }
            KeyCode::Char('d') => {
                if let Some(server) = self.mcp_servers.get(self.mcp_selection) {
                    self.confirm = Some(Confirm::new(
                        "Delete MCP server",
                        format!("Remove MCP server “{}”?", server.name),
                        ConfirmPurpose::DeleteMcpServer(server.id.clone()),
                    ));
                }
            }
            _ => {}
        }
    }

    fn handle_settings_key(&mut self, key: KeyEvent) {
        let rows = SettingRow::all();
        match key.code {
            KeyCode::Char('q') => self.should_quit = true,
            KeyCode::Down | KeyCode::Char('j') => {
                self.setting_selection = (self.setting_selection + 1).min(rows.len() - 1);
            }
            KeyCode::Up | KeyCode::Char('k') => {
                self.setting_selection = self.setting_selection.saturating_sub(1);
            }
            KeyCode::Char(' ') | KeyCode::Enter => match rows[self.setting_selection] {
                SettingRow::Theme => {
                    self.config.theme = if self.config.is_dark() {
                        "light".into()
                    } else {
                        "dark".into()
                    };
                    self.save_config();
                }
                SettingRow::ShowThink => self.toggle_think(),
                SettingRow::ShowToolDetails => self.toggle_tool_details(),
                SettingRow::AutoScroll => {
                    self.config.auto_scroll = !self.config.auto_scroll;
                    self.save_config();
                }
                SettingRow::Workspace => {
                    self.form = Some(Form::new(
                        "Workspace directory",
                        FormPurpose::EditWorkspace,
                        vec![Field::text("Workspace directory", self.config.workspace_dir.clone())],
                    ));
                }
                SettingRow::ServerUrl => {
                    let current = self
                        .engine
                        .store
                        .kv_get(messenger_sync::KV_SERVER_URL)
                        .ok()
                        .flatten()
                        .unwrap_or_else(|| messenger_sync::DEFAULT_CLOUD_SERVER_URL.to_string());
                    self.form = Some(Form::new(
                        "Cloud server URL",
                        FormPurpose::EditServerUrl,
                        vec![Field::text("Server URL", current)],
                    ));
                }
                SettingRow::Session => {}
            },
            KeyCode::Char('l') => {
                if self.cloud_user.is_some() {
                    self.confirm = Some(Confirm::new(
                        "Sign out",
                        "Sign out and remove the built-in cloud provider?",
                        ConfirmPurpose::SignOut,
                    ));
                } else {
                    self.open_sign_in_form();
                }
            }
            KeyCode::Char('s') => {
                if self.cloud_user.is_some() {
                    self.sync_now();
                } else {
                    self.open_sign_in_form();
                }
            }
            KeyCode::Char('r') => {
                self.form = Some(Form::new(
                    "Redeem card",
                    FormPurpose::RedeemCard,
                    vec![Field::text("Card code", "")],
                ));
            }
            KeyCode::Char('p') => {
                self.form = Some(Form::new(
                    "Change password",
                    FormPurpose::ChangePassword,
                    vec![
                        Field::secret("Current password", ""),
                        Field::secret("New password", ""),
                    ],
                ));
            }
            KeyCode::Char('x') => {
                self.form = Some(Form::new(
                    "Delete account",
                    FormPurpose::DeleteAccountForm,
                    vec![Field::secret("Current password", "")],
                ));
            }
            _ => {}
        }
    }

    fn open_sign_in_form(&mut self) {
        let url = self
            .engine
            .store
            .kv_get(messenger_sync::KV_SERVER_URL)
            .ok()
            .flatten()
            .unwrap_or_else(|| messenger_sync::DEFAULT_CLOUD_SERVER_URL.to_string());
        self.form = Some(Form::new(
            "Sign in",
            FormPurpose::SignIn,
            vec![
                Field::text("Email", ""),
                Field::secret("Password", ""),
                Field::text("Server URL", url),
            ],
        ));
    }
}

/// Toggle a boolean/choice field in place.
fn toggle_field(form: &mut Form) {
    match form.focused_mut() {
        Some(Field::Bool { value, .. }) => *value = !*value,
        Some(Field::Choice {
            options, selected, ..
        }) => {
            if !options.is_empty() {
                *selected = (*selected + 1) % options.len();
            }
        }
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn form_helpers_read_typed_values() {
        let form = Form::new(
            "t",
            FormPurpose::NewProvider,
            vec![
                Field::text("Name", "acme"),
                Field::text("Temperature", "0.5"),
                Field::boolean("Enabled", true),
                Field::choice("Role", vec!["a".into(), "b".into()], 1),
            ],
        );
        assert_eq!(form.value("Name"), "acme");
        assert_eq!(form.number("Temperature"), Some(0.5));
        assert_eq!(form.number("Missing"), None);
        assert!(form.boolean("Enabled"));
        assert_eq!(form.index("Role"), Some(1));
    }

    #[test]
    fn confirm_requires_typed_text_when_configured() {
        let confirm =
            Confirm::new("t", "m", ConfirmPurpose::DeleteAccount("pw".into())).requires("DELETE");
        assert!(!confirm.can_confirm());
        let mut confirm = confirm;
        confirm.typed = "DELETE".into();
        assert!(confirm.can_confirm());
    }

    #[test]
    fn function_keys_map_to_views() {
        assert_eq!(View::from_function_key(KeyCode::F(1)), Some(View::Conversations));
        assert_eq!(View::from_function_key(KeyCode::F(6)), Some(View::Settings));
        assert_eq!(View::from_function_key(KeyCode::F(7)), None);
    }
}
