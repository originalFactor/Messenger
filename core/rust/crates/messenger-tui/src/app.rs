//! Application state, key routing, slash-command dispatch, and the popup
//! stack.
//!
//! There is exactly ONE surface: the chat. Everything else — picking an
//! Agent, editing a provider, confirming a delete, reading the keys — is a
//! [`Popup`] pushed on a stack. `handle_key` therefore has two branches: the
//! topmost popup consumes input, otherwise the chat editor does. No view
//! enum, no per-view key handlers, no mode flags.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use messenger_core::agent::AgentEvent;
use messenger_document::Block;
use messenger_llm::domain::ContentPart;
use messenger_mcp::config::{encode_server_list, McpServerConfig, McpTransportType};
use messenger_markdown::StreamingSession;
use messenger_store::model::{
    StoredAgent, StoredConversation, StoredMessage, StoredModel, StoredProvider,
};
use messenger_store::StoredProject;

use crate::commands::{self, SLASH_COMMANDS};
use crate::config::{self, TuiConfig};
use crate::engine::{load_mcp_servers, CardSnapshot, Engine, UiMsg};
use crate::popup::{
    command_rows, Confirm, ConfirmPurpose, Field, Form, FormPurpose, Popup, Select, SelectItem,
    SelectPurpose,
};
use crate::render::{self, RenderOpts};
use crate::store_ops::{self, TurnError};
use crate::text::Line;

// ---------------------------------------------------------------------------
// chat state
// ---------------------------------------------------------------------------

struct LiveStream {
    session: StreamingSession,
    last_rendered: usize,
}

/// A local, non-persisted transcript note: the visible outcome of a slash
/// command, a tool failure, or any other event the agent loop never sees.
#[derive(Debug, Clone, PartialEq)]
pub struct ChatNote {
    pub text: String,
    pub kind: NoteKind,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NoteKind {
    Info,
    Warn,
    Error,
}

impl ChatNote {
    pub fn info(text: impl Into<String>) -> Self {
        Self {
            text: text.into(),
            kind: NoteKind::Info,
        }
    }

    pub fn warn(text: impl Into<String>) -> Self {
        Self {
            text: text.into(),
            kind: NoteKind::Warn,
        }
    }

    pub fn error(text: impl Into<String>) -> Self {
        Self {
            text: text.into(),
            kind: NoteKind::Error,
        }
    }
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
    pub scroll: u16,
    pub follow: bool,
    pub is_generating: bool,
    pub error: Option<String>,
    /// Local notes rendered inline in the transcript (slash-command
    /// outcomes, tool failures). Never persisted: they belong to this
    /// session, not to the conversation.
    pub notes: Vec<ChatNote>,
    /// True while the `/` palette owns the input line.
    pub palette_open: bool,

    live: Option<LiveStream>,
    cache: RenderedCache,
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

// ---------------------------------------------------------------------------
// app
// ---------------------------------------------------------------------------

pub struct App {
    pub engine: Arc<Engine>,
    pub config: TuiConfig,
    pub config_path: PathBuf,
    pub store_path: PathBuf,
    pub should_quit: bool,
    pub status: String,
    pub spinner: usize,
    pub tick_count: u64,

    /// The whole non-chat UI. The topmost entry owns the keyboard.
    pub popups: Vec<Popup>,

    pub conversations: Vec<StoredConversation>,
    pub conversation_filter: Option<String>,
    pub projects: Vec<StoredProject>,
    pub agents: Vec<StoredAgent>,
    pub providers: Vec<StoredProvider>,
    pub models: Vec<StoredModel>,
    pub mcp_servers: Vec<McpServerConfig>,

    pub chat: ChatState,
    /// Current cloud user, refreshed from the store on demand.
    pub cloud_user: Option<CloudUserInfo>,
    /// Last status-line diagnostic (token totals etc.).
    pub last_list_message: Option<String>,
    /// The directory the process was launched in: the session's default
    /// project workspace.
    pub cwd: PathBuf,
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
            should_quit: false,
            status: String::new(),
            spinner: 0,
            tick_count: 0,
            popups: Vec::new(),
            conversations: Vec::new(),
            conversation_filter: None,
            projects: Vec::new(),
            agents: Vec::new(),
            providers: Vec::new(),
            models: Vec::new(),
            mcp_servers: Vec::new(),
            chat: ChatState {
                follow: true,
                ..ChatState::default()
            },
            cloud_user: None,
            last_list_message: None,
            cwd: std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")),
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
    // popups
    // ------------------------------------------------------------------

    pub fn push(&mut self, popup: Popup) {
        self.popups.push(popup);
    }

    /// Close the topmost popup. Returns false when the stack is already
    /// empty, so callers can leave the chat untouched.
    pub fn close_popup(&mut self) -> bool {
        self.popups.pop().is_some()
    }

    /// Close every popup (Esc from a nested stack, or a command that
    /// supersedes what was open).
    pub fn close_all_popups(&mut self) {
        self.popups.clear();
    }

    fn push_form(&mut self, form: Form) {
        self.push(Popup::Form(form));
    }

    fn push_confirm(&mut self, confirm: Confirm) {
        self.push(Popup::Confirm(confirm));
    }

    /// Submit a form from inside the stack: on validation failure the form is
    /// pushed back so the user keeps what they typed.
    fn submit_form(&mut self, form: Form) {
        let purpose = form.purpose.clone();
        // Validation failures return the form to the top of the stack.
        if let Some(reason) = self.reject_form(&form) {
            self.note(ChatNote::warn(reason));
            self.push_form(form);
            return;
        }
        // Resolve the row being edited BEFORE the match destructures the
        // purpose (a by-value binding would partially move it).
        let existing_model = match &purpose {
            FormPurpose::EditModel { model_id, .. } => self.engine.store.get_model(model_id).ok().flatten(),
            _ => None,
        };
        let existing_provider = match &purpose {
            FormPurpose::EditProvider(id) => self.engine.store.get_provider(id).ok().flatten(),
            _ => None,
        };
        let existing_agent = match &purpose {
            FormPurpose::EditAgent(id) => self.engine.store.get_agent(id).ok().flatten(),
            _ => None,
        };
        match purpose {
            FormPurpose::RenameConversation(id) => {
                let title = form.value("Title");
                if let Ok(Some(mut conversation)) = self.engine.store.get_conversation(&id) {
                    conversation.title = title.clone();
                    conversation.updated_at = messenger_store::now_ms();
                    let _ = self.engine.store.upsert_conversation(&conversation);
                    self.reload_conversations();
                }
                self.note_info(format!("Renamed to “{title}”."));
            }
            FormPurpose::NewProvider | FormPurpose::EditProvider(_) => {
                let existing = existing_provider;
                let now = messenger_store::now_ms();
                let provider = StoredProvider {
                    id: existing
                        .as_ref()
                        .map(|provider| provider.id.clone())
                        .unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
                    name: form.value("Name"),
                    base_url: form.value("Base URL"),
                    api_key: form.value("API key"),
                    created_at: existing.as_ref().map(|p| p.created_at).unwrap_or(now),
                    updated_at: now,
                };
                let name = provider.name.clone();
                match self.engine.store.upsert_provider(&provider) {
                    Ok(()) => {
                        self.reload_providers();
                        self.note_info(format!("Saved provider {name}."));
                    }
                    Err(error) => self.note(ChatNote::error(error.to_string())),
                }
            }
            FormPurpose::NewModel(provider_id) | FormPurpose::EditModel { provider_id, .. } => {
                let provider_id = provider_id.clone();
                let existing = existing_model;
                let model_id = form.value("Model ID");
                let now = messenger_store::now_ms();
                let display_name = match form.value("Display name").trim() {
                    "" => model_id.clone(),
                    raw => raw.to_string(),
                };
                let model = StoredModel {
                    id: existing
                        .as_ref()
                        .map(|model| model.id.clone())
                        .unwrap_or_else(|| format!("{provider_id}:{model_id}")),
                    provider_id: provider_id.clone(),
                    model_id,
                    display_name: display_name.clone(),
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
                        self.reload_models();
                        self.note_info(format!("Saved model {display_name}."));
                    }
                    Err(error) => self.note(ChatNote::error(error.to_string())),
                }
            }
            FormPurpose::NewAgent | FormPurpose::EditAgent(_) => {
                self.submit_agent_form(form, existing_agent)
            }
            FormPurpose::NewMcpServer | FormPurpose::EditMcpServer(_) => {
                self.submit_mcp_form(form)
            }
            FormPurpose::SignIn => {
                let server = form.value("Server URL");
                self.login(
                    form.value("Email"),
                    form.value("Password"),
                    Some(server),
                );
            }
            FormPurpose::RedeemCard => {
                self.status = "Previewing card…".into();
                self.redeem_card(form.value("Card code"));
            }
            FormPurpose::DeleteAccountForm => {
                self.push_confirm(
                    Confirm::new(
                        "Delete account",
                        "This permanently deletes the cloud account and all synced data.",
                        ConfirmPurpose::DeleteAccount(form.value("Current password")),
                    )
                    .requires("DELETE"),
                );
            }
            FormPurpose::ChangePassword => {
                self.change_password(form.value("Current password"), form.value("New password"));
            }
            FormPurpose::EditServerUrl => self.set_server_url(form.value("Server URL")),
            FormPurpose::EditWorkspace => {
                self.config.workspace_dir = form.value("Workspace directory");
                self.save_config();
                self.note_info(
                    "Fallback workspace updated (project conversations use the project's own).",
                );
            }
            FormPurpose::NewProject | FormPurpose::EditProject(_) => {
                // An empty workspace defaults to the current directory: in a
                // terminal client the working directory is the one the user
                // launched from, which is exactly what they mean by "this
                // project".
                let workspace = match form.value("Workspace directory").trim() {
                    "" => config::current_dir_string(),
                    typed => typed.to_string(),
                };
                let existing = match &purpose {
                    FormPurpose::EditProject(id) => self.engine.store.get_project(id).ok().flatten(),
                    _ => None,
                };
                // A blank name means "name it after the directory", so the
                // session's own project needs no typing at all.
                let name = match form.value("Project name").trim() {
                    typed if !typed.is_empty() => typed.to_string(),
                    _ => PathBuf::from(&workspace)
                        .file_name()
                        .map(|name| name.to_string_lossy().to_string())
                        .filter(|name| !name.is_empty())
                        .unwrap_or_else(|| "project".into()),
                };
                let now = messenger_store::now_ms();
                let project = StoredProject {
                    id: existing
                        .as_ref()
                        .map(|project| project.id.clone())
                        .unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
                    name: name.clone(),
                    workspace,
                    created_at: existing.as_ref().map(|p| p.created_at).unwrap_or(now),
                    updated_at: now,
                };
                match self.engine.store.upsert_project(&project) {
                    Ok(()) => {
                        self.reload_projects();
                        self.note_info(format!("Saved project “{name}”."));
                    }
                    Err(error) => self.note(ChatNote::error(error.to_string())),
                }
            }
        }
    }

    /// The reason a form cannot be submitted, or `None` when it is valid.
    fn reject_form(&self, form: &Form) -> Option<String> {
        match &form.purpose {
            FormPurpose::RenameConversation(_) if form.value("Title").trim().is_empty() => {
                Some("Title cannot be empty.".into())
            }
            FormPurpose::NewProvider | FormPurpose::EditProvider(_) => {
                if form.value("Name").trim().is_empty() {
                    return Some("Provider name cannot be empty.".into());
                }
                let base_url = form.value("Base URL");
                if !(base_url.starts_with("http://") || base_url.starts_with("https://")) {
                    return Some("Base URL must start with http:// or https://".into());
                }
                None
            }
            FormPurpose::NewModel(_) | FormPurpose::EditModel { .. }
                if form.value("Model ID").trim().is_empty() =>
            {
                Some("Model ID cannot be empty.".into())
            }
            FormPurpose::NewAgent | FormPurpose::EditAgent(_)
                if form.value("Name").trim().is_empty() =>
            {
                Some("Agent name cannot be empty.".into())
            }
            FormPurpose::NewMcpServer | FormPurpose::EditMcpServer(_)
                if form.value("Name").trim().is_empty() =>
            {
                Some("Server name cannot be empty.".into())
            }
            FormPurpose::SignIn if form.value("Email").trim().is_empty() => {
                Some("Email cannot be empty.".into())
            }
            FormPurpose::RedeemCard if form.value("Card code").trim().is_empty() => {
                Some("Card code cannot be empty.".into())
            }
            FormPurpose::DeleteAccountForm if form.value("Current password").trim().is_empty() => {
                Some("Password cannot be empty.".into())
            }
            FormPurpose::ChangePassword if form.value("New password").trim().is_empty() => {
                Some("New password cannot be empty.".into())
            }
            FormPurpose::EditServerUrl if form.value("Server URL").trim().is_empty() => {
                Some("Server URL cannot be empty.".into())
            }
            FormPurpose::EditWorkspace if form.value("Workspace directory").trim().is_empty() => {
                Some("Workspace directory cannot be empty.".into())
            }
            _ => None,
        }
    }

    // ------------------------------------------------------------------
    // reloads
    // ------------------------------------------------------------------

    pub fn reload_all(&mut self) {
        self.reload_conversations();
        self.reload_projects();
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
            }
            Err(error) => self.status = error,
        }
    }

    pub fn reload_projects(&mut self) {
        match self.engine.store.list_projects() {
            Ok(projects) => self.projects = projects,
            Err(error) => self.status = error.to_string(),
        }
    }

    pub fn reload_agents(&mut self) {
        match self.engine.store.list_agents() {
            Ok(agents) => self.agents = agents,
            Err(error) => self.status = error.to_string(),
        }
    }

    pub fn reload_providers(&mut self) {
        match self.engine.store.list_providers() {
            Ok(providers) => {
                self.providers = providers;
                self.reload_models();
            }
            Err(error) => self.status = error.to_string(),
        }
    }

    /// Every model in the store — `/model` lists them all, so there is no
    /// "selected provider" concept any more.
    pub fn reload_models(&mut self) {
        self.models = self.engine.store.list_models().unwrap_or_default();
    }

    pub fn reload_mcp(&mut self) {
        self.mcp_servers = load_mcp_servers(&self.engine.store);
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

    // ------------------------------------------------------------------
    // transcript
    // ------------------------------------------------------------------

    /// Load a conversation's messages into the chat view.
    pub fn open_conversation(&mut self, conversation_id: &str) {
        match store_ops::load_messages(&self.engine.store, conversation_id) {
            Ok(messages) => {
                self.chat.messages = messages;
                self.chat.conversation_id = Some(conversation_id.to_string());
                self.chat.notes.clear();
                self.chat.scroll = 0;
                self.chat.follow = true;
                self.chat.error = None;
                self.chat.cache.clear();
            }
            Err(error) => self.status = error,
        }
    }

    /// Append a session-local transcript note and pin the view to the tail
    /// so the user actually sees it.
    pub fn note(&mut self, note: ChatNote) {
        self.chat.notes.push(note);
        self.chat.follow = true;
        self.chat.scroll = 0;
    }

    pub fn note_info(&mut self, text: impl Into<String>) {
        self.note(ChatNote::info(text));
    }

    /// The project the open conversation belongs to, if any.
    pub fn chat_project(&self) -> Option<StoredProject> {
        let project_id = self
            .chat
            .conversation_id
            .as_deref()
            .and_then(|id| self.engine.store.get_conversation(id).ok().flatten())
            .and_then(|conversation| conversation.project_id)?;
        self.engine.store.get_project(&project_id).ok().flatten()
    }

    /// Open (or create) the project for the directory the process was
    /// launched in and start a fresh conversation in it with the default
    /// Agent — the state every session starts from.
    ///
    /// A project IS a workspace, so this is what gives the agent's terminal
    /// and workspace tools a real working directory instead of the desktop
    /// client's shared `~/.messenger` default.
    pub fn bootstrap_session(&mut self) {
        self.reload_all();
        let agent_name = store_ops::current_agent(&self.engine.store)
            .ok()
            .flatten()
            .map(|agent| agent.name);
        let Some(agent) = store_ops::current_agent(&self.engine.store).ok().flatten() else {
            self.note(ChatNote::error(
                "No Agent available — configure a provider and model first.",
            ));
            return;
        };
        let project = match store_ops::ensure_cwd_project(&self.engine.store) {
            Ok(project) => project,
            Err(error) => {
                self.note(ChatNote::error(format!(
                    "Cannot use the current directory as a project: {error}"
                )));
                return;
            }
        };
        let provider_id = self.effective_provider_id(&agent).unwrap_or_default();
        match store_ops::create_conversation(
            &self.engine.store,
            &agent,
            &provider_id,
            Some(&project.id),
        ) {
            Ok(conversation) => {
                self.reload_all();
                self.open_conversation(&conversation.id);
                let agent_label = agent_name.unwrap_or_else(|| agent.name.clone());
                self.note(ChatNote::info(format!(
                    "Project “{}” · agent {} · type a request, or / for commands.",
                    project.name, agent_label
                )));
            }
            Err(error) => self.note(ChatNote::error(error)),
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
            UiMsg::ToolLog(message) => {
                self.status = message.clone();
                self.note(ChatNote::warn(message));
            }
            UiMsg::CloudStatus(message) => {
                self.status = message.clone();
                self.note_info(message);
                self.reload_cloud_user();
            }
            UiMsg::CardPreview { code, preview } => match preview {
                Ok(card) => {
                    self.push_confirm(Confirm::new(
                        "Redeem card",
                        format!(
                            "{} — {} tokens, {} days validity.\nConfirm redemption of {code}?",
                            card.plan_name, card.quota_tokens, card.validity_days
                        ),
                        ConfirmPurpose::RedeemCard(code),
                    ));
                }
                Err(error) => self.note(ChatNote::error(format!("Card preview failed: {error}"))),
            },
            UiMsg::SyncFinished => self.reload_all(),
        }
    }

    fn apply_agent_event(&mut self, event: AgentEvent) {
        match event {
            AgentEvent::TurnStarted => {
                self.chat.is_generating = true;
                self.chat.error = None;
            }
            AgentEvent::StreamingStarted { .. } => {
                self.chat.live = Some(LiveStream {
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
                self.note(ChatNote::info(format!("Title: {title}")));
                self.reload_conversations();
            }
            AgentEvent::TitleFailed { code } => {
                self.status = format!("Title generation failed ({code}).");
                self.note(ChatNote::warn(format!("Title generation failed ({code}).")));
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
                if is_error {
                    self.note(ChatNote::error(format!("Tool {name} failed.")));
                }
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
            if let Some(live) = self.chat.live.as_mut() {
                let _ = live.session.drain_batch();
                live.last_rendered = live.session.document().blocks().len();
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
        self.create_conversation_for(None, true)
    }

    /// Create a conversation inside [project_id] and open it — the path taken
    /// when a project is picked from the picker.
    fn create_conversation_in(&mut self, project_id: &str) -> Option<String> {
        self.create_conversation_for(Some(project_id), false)
    }

    /// Create a conversation, optionally inside a project (which supplies the
    /// workspace the terminal/workspace tools run in). [open] is false when
    /// the caller opens the conversation itself.
    fn create_conversation_for(&mut self, project_id: Option<&str>, open: bool) -> Option<String> {
        let Ok(Some(agent)) = store_ops::current_agent(&self.engine.store) else {
            self.note(ChatNote::error("No Agent available."));
            return None;
        };
        // A brand-new conversation has no model binding yet, so the provider
        // is taken from the effective Agent's model when one is set.
        let provider_id = self.effective_provider_id(&agent).unwrap_or_default();
        match store_ops::create_conversation(&self.engine.store, &agent, &provider_id, project_id) {
            Ok(conversation) => {
                self.reload_conversations();
                if open {
                    self.open_conversation(&conversation.id);
                }
                Some(conversation.id)
            }
            Err(error) => {
                self.note(ChatNote::error(error));
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
            Err(error @ (TurnError::ModelNotConfigured | TurnError::NoEnabledModel)) => {
                self.note(ChatNote::error(error.message()));
                return;
            }
            Err(error) => {
                self.note(ChatNote::error(error.message()));
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
            self.note(ChatNote::error(error.to_string()));
            return;
        }
        if let Ok(Some(mut conversation)) = self.engine.store.get_conversation(&conversation_id) {
            conversation.last_message = Some(text.chars().take(80).collect::<String>());
            conversation.updated_at = messenger_store::now_ms();
            let _ = self.engine.store.upsert_conversation(&conversation);
        }

        self.chat.input.clear();
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
        let label = if self.config.show_think { "expanded" } else { "collapsed" };
        self.note_info(format!("Think blocks {label}"));
        self.save_config();
    }

    pub fn toggle_tool_details(&mut self) {
        self.config.show_tool_details = !self.config.show_tool_details;
        self.chat.cache.clear();
        let label = if self.config.show_tool_details {
            "expanded"
        } else {
            "collapsed"
        };
        self.note_info(format!("Tool details {label}"));
        self.save_config();
    }

    pub fn switch_agent(&mut self, agent_id: &str) {
        if let Err(error) = self.engine.store.kv_set(store_ops::CURRENT_AGENT_KEY, agent_id) {
            self.note(ChatNote::error(error.to_string()));
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
        self.reload_agents();
        let name = self
            .agents
            .iter()
            .find(|agent| agent.id == agent_id)
            .map(|agent| agent.name.clone())
            .unwrap_or_else(|| agent_id.to_string());
        self.note_info(format!("Agent: {name}"));
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
            self.note(ChatNote::error(error.to_string()));
            return;
        }
        if let Err(error) = self.engine.store.delete_conversation(id) {
            self.note(ChatNote::error(error.to_string()));
            return;
        }
        if self.chat.conversation_id.as_deref() == Some(id) {
            self.chat.conversation_id = None;
            self.chat.messages.clear();
            self.chat.live = None;
            self.chat.cache.clear();
        }
        self.note_info("Conversation deleted.");
        self.reload_conversations();
    }

    fn delete_agent(&mut self, id: &str) {
        if let Some(agent) = self.engine.store.list_agents().unwrap_or_default().into_iter().find(|a| a.id == id) {
            if agent.is_default {
                self.note(ChatNote::warn("The default Agent cannot be deleted."));
                return;
            }
            if agent.role == messenger_sync::ROLE_TITLE {
                self.note(ChatNote::warn(
                    "The title generator cannot be deleted (transfer the role first).",
                ));
                return;
            }
            if agent.id == messenger_sync::BUILTIN_TITLE_AGENT_ID {
                self.note(ChatNote::warn(
                    "The built-in title generator cannot be deleted.",
                ));
                return;
            }
        }
        if let Err(error) = self.engine.store.delete_agent(id) {
            self.note(ChatNote::error(error.to_string()));
            return;
        }
        self.note_info("Agent deleted.");
        self.reload_agents();
    }

    fn delete_provider(&mut self, id: &str) {
        if id == messenger_sync::BUILTIN_PROVIDER_ID {
            self.note(ChatNote::warn(
                "The built-in cloud provider is managed by sign-in.",
            ));
            return;
        }
        match self.engine.store.delete_provider(id) {
            Ok(()) => {
                self.note_info("Provider deleted.");
                self.reload_providers();
            }
            Err(error) => self.note(ChatNote::error(error.to_string())),
        }
    }

    fn persist_mcp_servers(&mut self) {
        let json = encode_server_list(&self.mcp_servers);
        if let Err(error) = self.engine.store.kv_set(store_ops::MCP_SERVERS_KEY, &json) {
            self.note(ChatNote::error(error.to_string()));
            return;
        }
        let engine = Arc::clone(&self.engine);
        let servers = self.mcp_servers.clone();
        let spawner = engine.spawner();
        spawner.spawn(async move { engine.connect_mcp(servers).await });
    }

    // ------------------------------------------------------------------
    // cloud actions
    // ------------------------------------------------------------------

    fn login(&mut self, email: String, password: String, server_url: Option<String>) {
        let engine = Arc::clone(&self.engine);
        let spawner = engine.spawner();
        spawner.spawn(async move {
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
                            let _ = engine
                                .tx
                                .send(UiMsg::CloudStatus(format!("Sync failed: {error}")));
                        }
                    }
                    let _ = engine.tx.send(UiMsg::SyncFinished);
                }
                Err(error) => {
                    let _ = engine
                        .tx
                        .send(UiMsg::CloudStatus(format!("Sign-in failed: {error}")));
                }
            }
        });
    }

    fn logout(&mut self) {
        let engine = Arc::clone(&self.engine);
        let spawner = engine.spawner();
        spawner.spawn(async move {
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
        let spawner = engine.spawner();
        spawner.spawn(async move {
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
                    let _ = engine
                        .tx
                        .send(UiMsg::CloudStatus(format!("Sync failed: {error}")));
                }
            }
            let _ = engine.tx.send(UiMsg::SyncFinished);
        });
    }

    fn redeem_card(&mut self, code: String) {
        let engine = Arc::clone(&self.engine);
        let spawner = engine.spawner();
        spawner.spawn(async move {
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
            let _ = engine.tx.send(UiMsg::CardPreview { code, preview });
        });
    }

    fn confirm_redeem(&mut self, code: String) {
        let engine = Arc::clone(&self.engine);
        let spawner = engine.spawner();
        spawner.spawn(async move {
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
                    let _ = engine.tx.send(UiMsg::CloudStatus(format!(
                        "Redemption failed: {error}"
                    )));
                }
            }
            let _ = engine.tx.send(UiMsg::SyncFinished);
        });
    }

    fn change_password(&mut self, current: String, new: String) {
        let engine = Arc::clone(&self.engine);
        let spawner = engine.spawner();
        spawner.spawn(async move {
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
        let spawner = engine.spawner();
        spawner.spawn(async move {
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
        let spawner = engine.spawner();
        spawner.spawn(async move {
            {
                let sync = messenger_sync::SyncEngine::new(
                    &engine.store,
                    store_ops::session_from_kv(&engine.store),
                );
                let _ = sync.clear_session();
                let _ = sync.remove_builtin_provider();
                let _ = engine.store.kv_delete(store_ops::CURRENT_AGENT_KEY);
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

    fn submit_agent_form(&mut self, form: Form, existing: Option<StoredAgent>) {
        let name = form.value("Name");
        let now = messenger_store::now_ms();
        let model_id = form.index("Model").and_then(|index| {
            self.engine
                .store
                .list_models()
                .unwrap_or_default()
                .get(index.saturating_sub(1))
                .filter(|_| index > 0)
                .map(|model| model.id.clone())
        });
        let (is_default, role) = match form.index("Role") {
            Some(1) => (true, "chat".to_string()),
            Some(2) => (false, messenger_sync::ROLE_TITLE.to_string()),
            _ => (false, "chat".to_string()),
        };

        // Single-holder roles: the previous holder falls back to a regular
        // Agent (mirrors `AgentEditViewModel.save`).
        let existing_id = existing.as_ref().map(|agent| agent.id.clone());
        for mut other in self.engine.store.list_agents().unwrap_or_default() {
            let mut changed = false;
            if Some(other.id.clone()) == existing_id {
                continue;
            }
            if is_default && other.is_default {
                other.is_default = false;
                changed = true;
            }
            if role == messenger_sync::ROLE_TITLE && other.role == messenger_sync::ROLE_TITLE {
                other.role = "chat".into();
                changed = true;
            }
            if changed {
                let _ = self.engine.store.upsert_agent(&other);
            }
        }

        let tools_config: HashMap<String, bool> = messenger_tools::builtin_registry()
            .iter()
            .filter(|tool| !form.boolean(&format!("tool:{}", tool.name)))
            .map(|tool| (tool.name.clone(), false))
            .collect();
        let agent = StoredAgent {
            id: existing
                .as_ref()
                .map(|agent| agent.id.clone())
                .unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
            name: name.clone(),
            avatar: existing.as_ref().and_then(|agent| agent.avatar.clone()),
            system_prompt: form.value("System prompt"),
            description: form.value("Description"),
            default_model_id: model_id,
            temperature: form.number("Temperature"),
            top_p: form.number("Top P"),
            max_tokens: form.number("Max tokens").map(|value| value as i64),
            reasoning_effort: match form.value("Reasoning effort").trim() {
                "" => None,
                raw => Some(raw.to_string()),
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
            tools_enabled: form.boolean("Tools enabled"),
            tools_follow_default: form.boolean("Follow default Agent tools"),
            tools_config: serde_json::to_string(&tools_config).unwrap_or_default(),
            created_at: existing.as_ref().map(|agent| agent.created_at).unwrap_or(now),
            updated_at: now,
        };
        match self.engine.store.upsert_agent(&agent) {
            Ok(()) => {
                self.reload_agents();
                self.note_info(format!("Saved Agent “{name}”."));
            }
            Err(error) => self.note(ChatNote::error(error.to_string())),
        }
    }

    fn submit_mcp_form(&mut self, form: Form) {
        let name = form.value("Name");
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
        let server = McpServerConfig {
            id: id.clone(),
            name,
            transport_type: if form.index("Transport") == Some(1) {
                McpTransportType::SSE
            } else {
                McpTransportType::STDIO
            },
            is_enabled: form.boolean("Enabled"),
            command: form.value("Command"),
            args: form
                .value("Arguments")
                .split_whitespace()
                .map(str::to_string)
                .collect(),
            env: existing.as_ref().map(|s| s.env.clone()).unwrap_or_default(),
            url: form.value("URL"),
            headers: form
                .value("Headers")
                .split(',')
                .filter_map(|pair| pair.split_once('='))
                .map(|(key, value)| (key.trim().to_string(), value.trim().to_string()))
                .filter(|(key, _)| !key.is_empty())
                .collect(),
        };
        match self.mcp_servers.iter_mut().find(|entry| entry.id == id) {
            Some(entry) => *entry = server,
            None => self.mcp_servers.push(server),
        }
        self.persist_mcp_servers();
        self.note_info("MCP servers updated.");
    }

    // ------------------------------------------------------------------
    // select popups
    // ------------------------------------------------------------------

    fn open_agent_picker(&mut self) {
        let current = store_ops::current_agent(&self.engine.store)
            .ok()
            .flatten()
            .map(|agent| agent.id);
        let agents: Vec<StoredAgent> = self
            .agents
            .iter()
            .filter(|agent| agent.role != messenger_sync::ROLE_TITLE)
            .cloned()
            .collect();
        if agents.is_empty() {
            self.note(ChatNote::warn("No selectable Agent."));
            return;
        }
        let cursor = agents
            .iter()
            .position(|agent| Some(&agent.id) == current.as_ref())
            .unwrap_or(0);
        let items = agents
            .iter()
            .map(|agent| {
                let badge = if agent.is_default { " [default]" } else { "" };
                let detail = agent
                    .description
                    .lines()
                    .next()
                    .unwrap_or_default()
                    .trim()
                    .to_string();
                let detail = if detail.is_empty() {
                    format!("tools:{}", if agent.tools_enabled { "on" } else { "off" })
                } else {
                    detail
                };
                SelectItem::new(format!("{}{badge}", agent.name), agent.id.clone())
                    .detail(detail)
                    .selected(Some(&agent.id) == current.as_ref())
            })
            .collect();
        self.push(Popup::Select(Select {
            title: "Agents".into(),
            items,
            cursor,
            purpose: SelectPurpose::Agent,
        }));
    }

    fn open_project_picker(&mut self) {
        if self.projects.is_empty() {
            self.note(ChatNote::warn("No projects yet — /project new."));
            return;
        }
        let current = self.chat_project().map(|project| project.id);
        let items: Vec<SelectItem> = self
            .projects
            .iter()
            .map(|project| {
                SelectItem::new(project.name.clone(), project.id.clone())
                    .detail(project.workspace.clone())
                    .selected(Some(&project.id) == current.as_ref())
            })
            .collect();
        let cursor = items
            .iter()
            .position(|item| Some(&item.id) == current.as_ref())
            .unwrap_or(0);
        self.push(Popup::Select(Select {
            title: "Projects".into(),
            items,
            cursor,
            purpose: SelectPurpose::Project,
        }));
    }

    fn open_conversation_picker(&mut self) {
        if self.conversations.is_empty() {
            self.note(ChatNote::warn("No conversations yet."));
            return;
        }
        let current = self.chat.conversation_id.clone();
        let items = self
            .conversations
            .iter()
            .map(|conversation| {
                SelectItem::new(conversation.title.clone(), conversation.id.clone())
                    .detail(conversation.last_message.clone().unwrap_or_default())
                    .selected(Some(&conversation.id) == current.as_ref())
            })
            .collect();
        self.push(Popup::Select(Select {
            title: "Conversations".into(),
            items,
            cursor: 0,
            purpose: SelectPurpose::Conversation,
        }));
    }

    fn open_model_picker(&mut self, provider_id: Option<&str>) {
        self.reload_models();
        let models: Vec<&StoredModel> = self
            .models
            .iter()
            .filter(|model| provider_id.is_none_or(|id| model.provider_id == id))
            .collect();
        if models.is_empty() {
            self.note(ChatNote::warn("No models — /provider new, then fetch."));
            return;
        }
        let bound = self.bound_model_id();
        let items: Vec<SelectItem> = models
            .iter()
            .map(|model| {
                let provider = self
                    .providers
                    .iter()
                    .find(|provider| provider.id == model.provider_id)
                    .map(|provider| provider.name.clone())
                    .unwrap_or_else(|| model.provider_id.clone());
                let detail = if model.is_enabled {
                    provider
                } else {
                    format!("{provider} (disabled)")
                };
                SelectItem::new(model.display_name.clone(), model.id.clone())
                    .detail(detail)
                    .selected(Some(&model.id) == bound.as_ref())
            })
            .collect();
        let cursor = items
            .iter()
            .position(|item| Some(&item.id) == bound.as_ref())
            .unwrap_or(0);
        self.push(Popup::Select(Select {
            title: match provider_id {
                Some(provider) => format!(
                    "Models · {}",
                    self.providers
                        .iter()
                        .find(|p| p.id == provider)
                        .map(|p| p.name.clone())
                        .unwrap_or_else(|| provider.to_string())
                ),
                None => "Models".into(),
            },
            items,
            cursor,
            purpose: SelectPurpose::Model,
        }));
    }

    fn open_provider_picker(&mut self) {
        if self.providers.is_empty() {
            self.note(ChatNote::warn("No providers — /provider new."));
            return;
        }
        let items = self
            .providers
            .iter()
            .map(|provider| {
                let count = self
                    .models
                    .iter()
                    .filter(|model| model.provider_id == provider.id)
                    .count();
                SelectItem::new(provider.name.clone(), provider.id.clone())
                    .detail(format!("{} · {count} models", provider.base_url))
            })
            .collect();
        self.push(Popup::Select(Select {
            title: "Providers".into(),
            items,
            cursor: 0,
            purpose: SelectPurpose::Provider,
        }));
    }

    fn open_mcp_picker(&mut self) {
        if self.mcp_servers.is_empty() {
            self.note(ChatNote::warn("No MCP servers — /mcp new."));
            return;
        }
        let connected = self.engine.mcp_tools().len();
        let items = self
            .mcp_servers
            .iter()
            .map(|server| {
                let transport = match server.transport_type {
                    McpTransportType::STDIO => {
                        format!("stdio {}", server.command)
                    }
                    McpTransportType::SSE => format!("sse {}", server.url),
                };
                SelectItem::new(server.name.clone(), server.id.clone())
                    .detail(format!(
                        "{} · {}",
                        if server.is_enabled { "on" } else { "off" },
                        transport.trim()
                    ))
            })
            .collect();
        self.push(Popup::Select(Select {
            title: format!("MCP servers ({connected} tools)"),
            items,
            cursor: 0,
            purpose: SelectPurpose::McpServer,
        }));
    }

    /// The model the open conversation actually resolves to (its override,
    /// else the Agent's).
    fn bound_model_id(&self) -> Option<String> {
        let conversation = self
            .chat
            .conversation_id
            .as_deref()
            .and_then(|id| self.engine.store.get_conversation(id).ok().flatten())?;
        conversation
            .override_model_id
            .clone()
            .or_else(|| store_ops::current_agent(&self.engine.store).ok().flatten()?.default_model_id)
    }

    /// The model this session would actually call, for the footer.
    pub fn bound_model_label(&self) -> Option<String> {
        let id = self.bound_model_id()?;
        self.models
            .iter()
            .find(|model| model.id == id)
            .map(|model| model.display_name.clone())
    }

    /// React to a pick from a `Popup::Select`.
    fn on_select(&mut self, purpose: &SelectPurpose, id: String) {
        match purpose {
            SelectPurpose::Agent => self.switch_agent(&id),
            SelectPurpose::Project => match self.create_conversation_in(&id) {
                Some(conversation_id) => self.open_conversation(&conversation_id),
                None => self.note(ChatNote::error("Could not create a conversation.")),
            },
            SelectPurpose::Conversation => self.open_conversation(&id),
            SelectPurpose::Provider => {
                // A provider pick is a drill-down: its models are the next
                // question, and fetching refreshes the list.
                self.fetch_models(&id);
                self.open_model_picker(Some(&id));
            }
            SelectPurpose::Model => {
                let Some(conversation_id) = self.chat.conversation_id.clone() else {
                    self.note(ChatNote::warn("No conversation open."));
                    return;
                };
                let Ok(Some(mut conversation)) = self.engine.store.get_conversation(&conversation_id)
                else {
                    self.note(ChatNote::error("Conversation not found."));
                    return;
                };
                conversation.override_model_id = Some(id.clone());
                conversation.updated_at = messenger_store::now_ms();
                match self.engine.store.upsert_conversation(&conversation) {
                    Ok(()) => {
                        let name = self
                            .models
                            .iter()
                            .find(|model| model.id == id)
                            .map(|model| model.display_name.clone())
                            .unwrap_or(id);
                        self.reload_models();
                        self.note_info(format!("Model: {name}"));
                    }
                    Err(error) => self.note(ChatNote::error(error.to_string())),
                }
            }
            SelectPurpose::McpServer => {
                if let Some(server) = self.mcp_servers.iter_mut().find(|s| s.id == id) {
                    server.is_enabled = !server.is_enabled;
                    let enabled = server.is_enabled;
                    self.persist_mcp_servers();
                    self.note_info(format!("MCP server {}", if enabled { "enabled" } else { "disabled" }));
                }
            }
            SelectPurpose::Setting => self.run_setting(&id),
        }
    }

    /// Fetch a provider's `GET /models` and store every entry as a
    /// currently-disabled model (mirrors `ProviderDetailViewModel.syncModels`).
    fn fetch_models(&mut self, provider_id: &str) {
        let Some(provider) = self
            .providers
            .iter()
            .find(|provider| provider.id == provider_id)
            .cloned()
        else {
            return;
        };
        let engine = Arc::clone(&self.engine);
        let spawner = engine.spawner();
        spawner.spawn(async move {
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

    // ------------------------------------------------------------------
    // form builders
    // ------------------------------------------------------------------

    fn open_agent_form(&mut self, agent_id: Option<&str>) {
        let models = self.engine.store.list_models().unwrap_or_default();
        let mut model_options = vec!["(none)".to_string()];
        model_options.extend(models.iter().map(|model| model.display_name.clone()));
        let existing = agent_id.and_then(|id| self.engine.store.get_agent(id).ok().flatten());
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
        self.push_form(Form::new(
            if agent_id.is_some() { "Edit Agent" } else { "New Agent" },
            match agent_id {
                Some(id) => FormPurpose::EditAgent(id.to_string()),
                None => FormPurpose::NewAgent,
            },
            fields,
        ));
    }

    /// The project editor.
    ///
    /// A new project prefills only the workspace — the session's own
    /// directory. The name stays empty so typing replaces nothing; blank
    /// still means "name it after the directory" on submit.
    fn open_project_form(&mut self, project_id: Option<&str>, name_override: Option<&str>) {
        let existing = project_id.and_then(|id| self.engine.store.get_project(id).ok().flatten());
        let fields = vec![
            Field::text(
                "Project name",
                name_override
                    .map(str::to_string)
                    .or_else(|| existing.as_ref().map(|project| project.name.clone()))
                    .unwrap_or_default(),
            ),
            Field::text(
                "Workspace directory",
                existing
                    .as_ref()
                    .map(|project| project.workspace.clone())
                    .unwrap_or_else(|| self.cwd.to_string_lossy().to_string()),
            ),
        ];
        self.push_form(Form::new(
            if existing.is_some() {
                "Edit project"
            } else {
                "New project"
            },
            match project_id {
                Some(id) => FormPurpose::EditProject(id.to_string()),
                None => FormPurpose::NewProject,
            },
            fields,
        ));
    }

    fn open_provider_form(&mut self, provider_id: Option<&str>) {
        let existing = provider_id.and_then(|id| self.engine.store.get_provider(id).ok().flatten());
        self.push_form(Form::new(
            if provider_id.is_some() { "Edit provider" } else { "New provider" },
            match provider_id {
                Some(id) => FormPurpose::EditProvider(id.to_string()),
                None => FormPurpose::NewProvider,
            },
            vec![
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
            ],
        ));
    }

    fn open_model_form(&mut self, provider_id: &str, model_id: Option<&str>) {
        let existing = model_id.and_then(|id| self.engine.store.get_model(id).ok().flatten());
        self.push_form(Form::new(
            if model_id.is_some() { "Edit model" } else { "New model" },
            match model_id {
                Some(model_id) => FormPurpose::EditModel {
                    provider_id: provider_id.to_string(),
                    model_id: model_id.to_string(),
                },
                None => FormPurpose::NewModel(provider_id.to_string()),
            },
            vec![
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
            ],
        ));
    }

    fn open_mcp_form(&mut self, server_id: Option<&str>) {
        let existing = server_id.and_then(|id| {
            self.mcp_servers
                .iter()
                .find(|server| &server.id == id)
                .cloned()
        });
        self.push_form(Form::new(
            if server_id.is_some() {
                "Edit MCP server"
            } else {
                "New MCP server"
            },
            match server_id {
                Some(id) => FormPurpose::EditMcpServer(id.to_string()),
                None => FormPurpose::NewMcpServer,
            },
            vec![
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
            ],
        ));
    }

    fn open_sign_in_form(&mut self) {
        let url = self
            .engine
            .store
            .kv_get(messenger_sync::KV_SERVER_URL)
            .ok()
            .flatten()
            .unwrap_or_else(|| messenger_sync::DEFAULT_CLOUD_SERVER_URL.to_string());
        self.push_form(Form::new(
            "Sign in",
            FormPurpose::SignIn,
            vec![
                Field::text("Email", ""),
                Field::secret("Password", ""),
                Field::text("Server URL", url),
            ],
        ));
    }

    /// `/settings` is a list of actions, not a form: each row performs
    /// immediately, so toggling a flag is one Enter away.
    fn open_settings_picker(&mut self) {
        let on_off = |value: bool| if value { "on" } else { "off" };
        let rows = [
            ("Theme", format!("currently {}", self.config.theme)),
            (
                "Show think blocks",
                on_off(self.config.show_think).to_string(),
            ),
            (
                "Show tool details",
                on_off(self.config.show_tool_details).to_string(),
            ),
            ("Auto-scroll", on_off(self.config.auto_scroll).to_string()),
            (
                "Cloud server URL",
                self.engine
                    .store
                    .kv_get(messenger_sync::KV_SERVER_URL)
                    .ok()
                    .flatten()
                    .unwrap_or_else(|| messenger_sync::DEFAULT_CLOUD_SERVER_URL.to_string()),
            ),
            (
                "Fallback workspace",
                self.config.workspace_dir.clone(),
            ),
            ("Change password", String::new()),
            ("Delete account", "irreversible".to_string()),
            ("Account", self.cloud_account()),
        ];
        self.push(Popup::Select(Select {
            title: "Settings".into(),
            items: rows
                .into_iter()
                .map(|(label, detail)| {
                    SelectItem::new(label, label).detail(detail)
                })
                .collect(),
            cursor: 0,
            purpose: SelectPurpose::Setting,
        }));
    }

    /// Run the setting action behind a `SelectPurpose::Setting` row.
    fn run_setting(&mut self, setting: &str) {
        match setting {
            "Theme" => {
                self.config.theme = if self.config.is_dark() {
                    "light".into()
                } else {
                    "dark".into()
                };
                self.save_config();
                let theme = self.config.theme.clone();
                self.note_info(format!("Theme: {theme}"));
            }
            "Show think blocks" => self.toggle_think(),
            "Show tool details" => self.toggle_tool_details(),
            "Auto-scroll" => {
                self.config.auto_scroll = !self.config.auto_scroll;
                self.save_config();
                self.note_info(format!(
                    "Auto-scroll {}",
                    if self.config.auto_scroll { "on" } else { "off" }
                ));
            }
            "Cloud server URL" => {
                let current = self
                    .engine
                    .store
                    .kv_get(messenger_sync::KV_SERVER_URL)
                    .ok()
                    .flatten()
                    .unwrap_or_else(|| messenger_sync::DEFAULT_CLOUD_SERVER_URL.to_string());
                self.push_form(Form::new(
                    "Cloud server URL",
                    FormPurpose::EditServerUrl,
                    vec![Field::text("Server URL", current)],
                ));
            }
            "Fallback workspace" => self.push_form(Form::new(
                "Fallback workspace",
                FormPurpose::EditWorkspace,
                vec![Field::text(
                    "Workspace directory",
                    self.config.workspace_dir.clone(),
                )],
            )),
            "Change password" => self.push_form(Form::new(
                "Change password",
                FormPurpose::ChangePassword,
                vec![
                    Field::secret("Current password", ""),
                    Field::secret("New password", ""),
                ],
            )),
            "Delete account" => self.push_form(Form::new(
                "Delete account",
                FormPurpose::DeleteAccountForm,
                vec![Field::secret("Current password", "")],
            )),
            "Account" => self.note_info(self.cloud_account()),
            _ => {}
        }
    }

    pub fn cloud_account(&self) -> String {
        match &self.cloud_user {
            Some(user) => match user.quota_balance {
                Some(balance) => format!("{} · {balance} tokens", user.email),
                None => user.email.clone(),
            },
            None => "signed out".into(),
        }
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
        // A modal takes the keyboard; Esc always peels one layer off.
        if let Some(popup) = self.popups.pop() {
            self.handle_popup_key(popup, key);
            return;
        }
        self.handle_chat_key(key);
    }

    fn handle_popup_key(&mut self, popup: Popup, key: KeyEvent) {
        let control = key.modifiers.contains(KeyModifiers::CONTROL);
        match popup {
            Popup::Help { scroll } => match key.code {
                KeyCode::Down | KeyCode::Char('j') => {
                    self.push(Popup::Help {
                        scroll: scroll.saturating_add(1),
                    })
                }
                KeyCode::Up | KeyCode::Char('k') => {
                    self.push(Popup::Help {
                        scroll: scroll.saturating_sub(1),
                    })
                }
                KeyCode::PageDown => self.push(Popup::Help {
                    scroll: scroll.saturating_add(10),
                }),
                KeyCode::PageUp => self.push(Popup::Help {
                    scroll: scroll.saturating_sub(10),
                }),
                // Anything else dismisses it.
                _ => {}
            },
            Popup::Commands { buffer, cursor } => self.handle_commands_key(buffer, cursor, key),
            Popup::Select(mut select) => {
                match key.code {
                    KeyCode::Down | KeyCode::Char('j') if !control => {
                        if !select.items.is_empty() {
                            select.cursor = (select.cursor + 1).min(select.items.len() - 1);
                        }
                    }
                    KeyCode::Up | KeyCode::Char('k') if !control => {
                        select.cursor = select.cursor.saturating_sub(1);
                    }
                    KeyCode::Enter => {
                        if let Some(item) = select.items.get(select.cursor).cloned() {
                            let purpose = select.purpose.clone();
                            self.on_select(&purpose, item.id);
                        }
                        return;
                    }
                    // Editing a row in place, the way pi's selectors work.
                    KeyCode::Char('e') => {
                        let id = select.items.get(select.cursor).map(|item| item.id.clone());
                        match (select.purpose.clone(), id) {
                            (SelectPurpose::Agent, Some(id)) => self.open_agent_form(Some(&id)),
                            (SelectPurpose::Project, Some(id)) => {
                                self.open_project_form(Some(&id), None)
                            }
                            (SelectPurpose::McpServer, Some(id)) => self.open_mcp_form(Some(&id)),
                            _ => self.note(ChatNote::warn("This row has no editor.")),
                        }
                        return;
                    }
                    KeyCode::Char('d') => {
                        let id = select.items.get(select.cursor).map(|item| item.id.clone());
                        let label = select
                            .items
                            .get(select.cursor)
                            .map(|item| item.label.clone())
                            .unwrap_or_default();
                        match (select.purpose.clone(), id) {
                            (SelectPurpose::Agent, Some(id)) => self.push_confirm(
                                Confirm::new(
                                    "Delete Agent",
                                    format!("Delete Agent “{label}”?"),
                                    ConfirmPurpose::DeleteAgent(id),
                                ),
                            ),
                            (SelectPurpose::Provider, Some(id)) => self.push_confirm(
                                Confirm::new(
                                    "Delete provider",
                                    format!("Delete provider “{label}” and its models?"),
                                    ConfirmPurpose::DeleteProvider(id),
                                ),
                            ),
                            (SelectPurpose::McpServer, Some(id)) => self.push_confirm(
                                Confirm::new(
                                    "Delete MCP server",
                                    format!("Remove MCP server “{label}”?"),
                                    ConfirmPurpose::DeleteMcpServer(id),
                                ),
                            ),
                            (SelectPurpose::Conversation, Some(id)) => self.push_confirm(
                                Confirm::new(
                                    "Delete conversation",
                                    format!("Delete “{label}” and all its messages?"),
                                    ConfirmPurpose::DeleteConversation(id),
                                ),
                            ),
                            _ => self.note(ChatNote::warn("This row cannot be deleted here.")),
                        }
                        return;
                    }
                    KeyCode::Char('n') => match select.purpose {
                        SelectPurpose::Agent => self.open_agent_form(None),
                        SelectPurpose::Provider => self.open_provider_form(None),
                        SelectPurpose::McpServer => self.open_mcp_form(None),
                        SelectPurpose::Model => {
                            let provider = self
                                .providers
                                .first()
                                .map(|provider| provider.id.clone())
                                .unwrap_or_default();
                            if provider.is_empty() {
                                self.note(ChatNote::warn("No providers yet — /provider new."));
                            } else {
                                self.open_model_form(&provider, None);
                            }
                        }
                        SelectPurpose::Project => self.open_project_form(None, None),
                        SelectPurpose::Conversation => {
                            if let Some(id) = self.create_conversation() {
                                self.open_conversation(&id);
                            }
                        }
                        SelectPurpose::Setting => {
                            self.note(ChatNote::warn("Settings have no entries to add."))
                        }
                    },
                    _ => {}
                }
                self.push(Popup::Select(select));
            }
            Popup::Form(mut form) => {
                match key.code {
                KeyCode::Tab | KeyCode::Down => {
                    form.focus = (form.focus + 1) % form.fields.len().max(1);
                }
                KeyCode::BackTab | KeyCode::Up => {
                    form.focus = form.focus.checked_sub(1).unwrap_or(form.fields.len().saturating_sub(1));
                }
                KeyCode::Char('s') if control => {
                    self.submit_form(form);
                    return;
                }
                KeyCode::Enter if key.modifiers.contains(KeyModifiers::ALT) => {
                    if let Some(Field::Text { value, multiline: true, .. }) = form.focused_mut() {
                        value.push('\n');
                    }
                }
                KeyCode::Enter => {
                    // A multiline field inserts a newline; a bool/choice
                    // toggles; the last field submits.
                    match form.focused() {
                        Some(Field::Text { multiline: true, .. }) => {
                            if let Some(Field::Text { value, .. }) = form.focused_mut() {
                                value.push('\n');
                            }
                        }
                        Some(Field::Bool { .. }) | Some(Field::Choice { .. }) => {
                            crate::popup::toggle_field(&mut form);
                        }
                        _ => {
                            if form.focus + 1 >= form.fields.len() {
                                self.submit_form(form);
                                return;
                            }
                            form.focus += 1;
                        }
                    }
                }
                KeyCode::Char(' ') => match form.focused() {
                    Some(Field::Bool { .. }) | Some(Field::Choice { .. }) => {
                        crate::popup::toggle_field(&mut form)
                    }
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
                // The form survives every key except an explicit submit,
                // Esc, or the `return`s above.
                self.push(Popup::Form(form));
            }
            Popup::Confirm(mut confirm) => {
                match key.code {
                KeyCode::Backspace => {
                    confirm.typed.pop();
                }
                KeyCode::Char(ch) if !control => {
                    if confirm.requires_typing.is_some() {
                        confirm.typed.push(ch);
                    }
                }
                KeyCode::Enter => {
                    if !confirm.can_confirm() {
                        let expected = confirm.requires_typing.clone().unwrap_or_default();
                        self.note(ChatNote::warn(format!("Type {expected} to confirm.")));
                        self.push(Popup::Confirm(confirm));
                        return;
                    }
                    let purpose = confirm.purpose.clone();
                    self.run_confirm(purpose);
                    return;
                }
                    _ => {}
                }
                self.push(Popup::Confirm(confirm));
            }
        }
    }

    fn run_confirm(&mut self, purpose: ConfirmPurpose) {
        match purpose {
            ConfirmPurpose::DeleteConversation(id) => self.delete_conversation(&id),
            ConfirmPurpose::DeleteProvider(id) => self.delete_provider(&id),
            ConfirmPurpose::DeleteModel { model_id, .. } => {
                if let Err(error) = self.engine.store.delete_model(&model_id) {
                    self.note(ChatNote::error(error.to_string()));
                }
                self.reload_models();
            }
            ConfirmPurpose::DeleteAgent(id) => self.delete_agent(&id),
            ConfirmPurpose::DeleteProject(id) => {
                if let Err(error) = self.engine.store.delete_project(&id) {
                    self.note(ChatNote::error(error.to_string()));
                }
                self.reload_projects();
            }
            ConfirmPurpose::DeleteMcpServer(id) => {
                self.mcp_servers.retain(|server| server.id != id);
                self.persist_mcp_servers();
                self.note_info("MCP server removed.");
            }
            ConfirmPurpose::RedeemCard(code) => self.confirm_redeem(code),
            ConfirmPurpose::DeleteAccount(password) => self.delete_account(password),
            ConfirmPurpose::SignOut => self.logout(),
        }
    }

    /// The `/` palette: a filter buffer with Tab completion. Everything is a
    /// prefix of `SLASH_COMMANDS`, so completion can never fail to suggest.
    fn handle_commands_key(&mut self, mut buffer: String, mut cursor: usize, key: KeyEvent) {
        let control = key.modifiers.contains(KeyModifiers::CONTROL);
        match key.code {
            // Esc abandons the palette without running anything.
            KeyCode::Esc => {
                self.chat.palette_open = false;
                return;
            }
            KeyCode::Enter => {
                let line = buffer.clone();
                self.chat.palette_open = false;
                self.run_slash(&line);
                return;
            }
            KeyCode::Tab => {
                let rows = command_rows(&buffer);
                match rows.as_slice() {
                    [] => self.note(ChatNote::warn(format!("Unknown command /{buffer}"))),
                    [(label, _)] => buffer = label.trim_start_matches('/').to_string(),
                    many => {
                        let names: Vec<String> = many
                            .iter()
                            .map(|(label, _)| label.trim_start_matches('/').to_string())
                            .collect();
                        self.note(ChatNote::info(format!("Commands: /{}", names.join("  /"))));
                        buffer = shared_prefix(&names);
                    }
                }
                cursor = 0;
            }
            KeyCode::Down | KeyCode::Up => {
                let count = command_rows(&buffer).len();
                if count > 0 {
                    cursor = if key.code == KeyCode::Down {
                        (cursor + 1).min(count - 1)
                    } else {
                        cursor.saturating_sub(1)
                    };
                    // The highlighted row is the command that runs.
                    if let Some((label, _)) = command_rows(&buffer).get(cursor) {
                        buffer = label.trim_start_matches('/').to_string();
                    }
                }
            }
            KeyCode::Backspace => {
                buffer.pop();
                cursor = 0;
            }
            KeyCode::Char(ch) if !control && ch != ' ' => {
                buffer.push(ch);
                cursor = 0;
            }
            _ => {}
        }
        // An emptied palette returns to plain message typing.
        self.chat.palette_open = !buffer.is_empty();
        if !buffer.is_empty() {
            self.push(Popup::Commands { buffer, cursor });
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
            // A leading `/` opens the command palette instead of the message
            // box, so the interactive surface reads like a shell prompt.
            KeyCode::Char('/') if !alt && !control && self.chat.input.is_empty() => {
                self.chat.palette_open = true;
                self.push(Popup::Commands {
                    buffer: String::new(),
                    cursor: 0,
                });
            }
            KeyCode::Enter if !alt && !control => self.send_message(),
            KeyCode::Enter => {
                // Alt+Enter (and the terminal's Ctrl+J, which arrives as a
                // plain '\n' char) inserts a newline instead of sending.
                self.chat.input.push('\n');
            }
            KeyCode::Char('?') if self.chat.input.is_empty() => self.push(Popup::Help { scroll: 0 }),
            KeyCode::Char('t') if control => self.toggle_think(),
            KeyCode::Char('o') if control => self.toggle_tool_details(),
            KeyCode::Char('g') if control => {
                self.chat.scroll = u16::MAX;
                self.chat.follow = false;
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
            }
            KeyCode::Tab => self.chat.input.push('\t'),
            KeyCode::Char(ch) if !control && !alt => self.chat.input.push(ch),
            _ => {}
        }
    }

    // ------------------------------------------------------------------
    // slash commands
    // ------------------------------------------------------------------

    /// Execute one `/` command line. Anything after the command name is its
    /// free-form argument.
    pub fn run_slash(&mut self, line: &str) {
        let line = line.trim();
        let (name, args) = line
            .split_once(char::is_whitespace)
            .map(|(name, args)| (name.trim_start_matches('/'), args.trim()))
            .unwrap_or((line.trim_start_matches('/'), ""));
        let Some(command) = commands::find(name) else {
            self.note(ChatNote::warn(format!(
                "Unknown command /{name} — /help lists them all"
            )));
            return;
        };
        self.note_info(format!("/{command}", command = command.name));
        match command.name {
            "help" => self.push(Popup::Help { scroll: 0 }),
            "new" => self.slash_new(),
            "agent" => self.open_agent_picker(),
            "model" => self.open_model_picker(None),
            "provider" => match args {
                "" | "list" => self.open_provider_picker(),
                "new" => self.open_provider_form(None),
                "fetch" => match self.providers.first().map(|p| p.id.clone()) {
                    Some(id) => self.fetch_models(&id),
                    None => self.note(ChatNote::warn("No providers yet — /provider new.")),
                },
                other => match self
                    .providers
                    .iter()
                    .find(|provider| provider.name.eq_ignore_ascii_case(other))
                    .map(|provider| provider.id.clone())
                {
                    Some(id) => self.fetch_models(&id),
                    None => self.note(ChatNote::warn(format!("No provider named “{other}”."))),
                },
            },
            "mode" => self.slash_mode(args),
            "project" => self.slash_project(args),
            "resume" | "conversations" => match args {
                "" => self.open_conversation_picker(),
                n => self.slash_resume(n),
            },
            "rename" => self.slash_rename(args),
            "delete" => self.slash_delete(),
            "agent.new" => self.open_agent_form(None),
            "agent.edit" => self.slash_nth_agent(args, false),
            "agent.delete" => self.slash_nth_agent(args, true),
            "mcp" => match args {
                "" => self.open_mcp_picker(),
                "new" => self.open_mcp_form(None),
                other => self.note(ChatNote::warn(format!(
                    "Unknown /mcp subcommand “{other}” — use /mcp new."
                ))),
            },
            "settings" => self.open_settings_picker(),
            "login" => self.open_sign_in_form(),
            "logout" => self.push_confirm(Confirm::new(
                "Sign out",
                "Sign out and remove the built-in cloud provider?",
                ConfirmPurpose::SignOut,
            )),
            "sync" => {
                if self.cloud_user.is_some() {
                    self.sync_now();
                } else {
                    self.note(ChatNote::warn("Not signed in — /login first."));
                }
            }
            "card" => match args.is_empty() {
                true => self.push_form(Form::new(
                    "Redeem card",
                    FormPurpose::RedeemCard,
                    vec![Field::text("Card code", "")],
                )),
                false => self.redeem_card(args.to_string()),
            },
            "quit" => self.should_quit = true,
            _ => {}
        }
    }

    /// `/new` continues in the CURRENT project when there is one, so a
    /// follow-up request keeps its workspace.
    fn slash_new(&mut self) {
        let project_id = self.chat_project().map(|project| project.id);
        let created = match &project_id {
            Some(project_id) => self.create_conversation_in(project_id),
            None => self.create_conversation(),
        };
        match created {
            Some(id) => self.open_conversation(&id),
            None => self.note(ChatNote::error("Could not create a conversation.")),
        }
    }

    fn slash_mode(&mut self, args: &str) {
        let Some(conversation_id) = self.chat.conversation_id.clone() else {
            self.note(ChatNote::warn("No conversation open."));
            return;
        };
        let Ok(Some(mut conversation)) = self.engine.store.get_conversation(&conversation_id) else {
            self.note(ChatNote::error("Conversation not found."));
            return;
        };
        match args.to_lowercase().as_str() {
            "" => conversation.writable = !conversation.writable,
            "read-only" | "readonly" | "ro" => conversation.writable = false,
            "writable" | "write" | "rw" => conversation.writable = true,
            other => {
                self.note(ChatNote::warn(format!(
                    "Unknown mode “{other}” — use read-only or writable."
                )));
                return;
            }
        }
        let writable = conversation.writable;
        if let Err(error) = self.engine.store.upsert_conversation(&conversation) {
            self.note(ChatNote::error(error.to_string()));
            return;
        }
        self.note_info(format!(
            "Agent mode: {}",
            if writable { "writable" } else { "read-only" }
        ));
    }

    fn slash_project(&mut self, args: &str) {
        let current = self.chat_project();
        if args.is_empty() {
            self.open_project_picker();
            return;
        }
        let (verb, rest) = args
            .split_once(char::is_whitespace)
            .unwrap_or((args, ""));
        match verb {
            "show" => match &current {
                Some(project) => self.note_info(format!(
                    "Project “{}” · workspace {}",
                    project.name, project.workspace
                )),
                None => self.note_info("This conversation belongs to no project."),
            },
            // Defaults to the session's own directory and name; both fields
            // stay editable before the project is saved.
            "new" => self.open_project_form(None, (!rest.is_empty()).then_some(rest)),
            "edit" => match &current {
                Some(project) => {
                    let id = project.id.clone();
                    self.open_project_form(Some(&id), None);
                }
                None => self.note(ChatNote::warn("This conversation belongs to no project.")),
            },
            "delete" => match &current {
                Some(project) => self.push_confirm(Confirm::new(
                    "Delete project",
                    format!(
                        "Delete project “{}”? Its conversations stay, without a workspace.",
                        project.name
                    ),
                    ConfirmPurpose::DeleteProject(project.id.clone()),
                )),
                None => self.note(ChatNote::warn("This conversation belongs to no project.")),
            },
            name => self.note(ChatNote::warn(format!(
                "Unknown /project subcommand “{name}” — use new, edit, delete, or /project to pick."
            ))),
        }
    }

    fn slash_resume(&mut self, args: &str) {
        let index: usize = match args.trim().parse::<usize>() {
            Ok(value) if value >= 1 => value - 1,
            Ok(_) => {
                self.note(ChatNote::warn("/resume takes a 1-based index."));
                return;
            }
            Err(_) => 0,
        };
        match self.conversations.get(index) {
            Some(conversation) => {
                let id = conversation.id.clone();
                self.open_conversation(&id);
            }
            None => self.note(ChatNote::warn("No such conversation.")),
        }
    }

    /// `/agent.edit n` / `/agent.delete n` address the n-th selectable Agent
    /// (1-based) without opening the picker.
    fn slash_nth_agent(&mut self, args: &str, delete: bool) {
        let agents: Vec<&StoredAgent> = self
            .agents
            .iter()
            .filter(|agent| agent.role != messenger_sync::ROLE_TITLE)
            .collect();
        let index: usize = match args.trim().parse::<usize>() {
            Ok(value) if value >= 1 => value - 1,
            _ => 0,
        };
        match agents.get(index) {
            Some(agent) if delete => {
                let id = agent.id.clone();
                let name = agent.name.clone();
                self.push_confirm(Confirm::new(
                    "Delete Agent",
                    format!("Delete Agent “{name}”?"),
                    ConfirmPurpose::DeleteAgent(id),
                ));
            }
            Some(agent) => self.open_agent_form(Some(&agent.id.clone())),
            None => self.note(ChatNote::warn("No such Agent.")),
        }
    }

    fn slash_rename(&mut self, args: &str) {
        let Some(conversation_id) = self.chat.conversation_id.clone() else {
            self.note(ChatNote::warn("No conversation open."));
            return;
        };
        if args.is_empty() {
            let title = self
                .chat
                .conversation_id
                .as_deref()
                .and_then(|id| self.engine.store.get_conversation(id).ok().flatten())
                .map(|conversation| conversation.title)
                .unwrap_or_default();
            self.push_form(Form::new(
                "Rename conversation",
                FormPurpose::RenameConversation(conversation_id),
                vec![Field::text("Title", title)],
            ));
            return;
        }
        if let Ok(Some(mut conversation)) = self.engine.store.get_conversation(&conversation_id) {
            conversation.title = args.to_string();
            conversation.updated_at = messenger_store::now_ms();
            let title = conversation.title.clone();
            let _ = self.engine.store.upsert_conversation(&conversation);
            self.reload_conversations();
            self.note_info(format!("Renamed to “{title}”."));
        }
    }

    fn slash_delete(&mut self) {
        let Some(conversation) = self
            .chat
            .conversation_id
            .as_deref()
            .and_then(|id| self.engine.store.get_conversation(id).ok().flatten())
        else {
            self.note(ChatNote::warn("No conversation open."));
            return;
        };
        let title = conversation.title.clone();
        self.push_confirm(Confirm::new(
            "Delete conversation",
            format!("Delete “{title}” and all its messages?"),
            ConfirmPurpose::DeleteConversation(conversation.id.clone()),
        ));
    }
}

/// The longest common prefix of the given names, so an ambiguous Tab keeps
/// typing `/pro` instead of discarding the buffer.
fn shared_prefix(names: &[String]) -> String {
    let Some(first) = names.first() else {
        return String::new();
    };
    let mut prefix = first.clone();
    for name in &names[1..] {
        while !name.starts_with(&prefix) {
            prefix.pop();
            if prefix.is_empty() {
                return prefix;
            }
        }
    }
    prefix
}

/// Every command as `(label, summary)`, for the help popup.
pub fn command_help_rows() -> Vec<(String, String)> {
    SLASH_COMMANDS
        .iter()
        .map(|command| {
            let label = if command.args.is_empty() {
                format!("/{}", command.name)
            } else {
                format!("/{} {}", command.name, command.args)
            };
            (label, command.summary.to_string())
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shared_prefix_narrows_an_ambiguous_completion() {
        assert_eq!(shared_prefix(&["agent".into(), "agents".into()]), "agent");
        assert_eq!(shared_prefix(&["mode".into()]), "mode");
        assert_eq!(shared_prefix(&[]), "");
    }
}