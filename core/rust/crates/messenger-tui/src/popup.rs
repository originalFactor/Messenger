//! Modal surfaces: the form editor, the confirmation, and the generic
//! select list.
//!
//! The TUI has no permanent views. Every non-chat surface is a popup pushed
//! on a stack, so there is exactly one input path (`App::handle_key` routes
//! to the topmost popup, else to the chat editor) and one place a modal is
//! drawn.

use crate::commands::SLASH_COMMANDS;

// ---------------------------------------------------------------------------
// form fields
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
    RenameConversation(String),
    NewProvider,
    EditProvider(String),
    NewModel(String),
    EditModel {
        provider_id: String,
        model_id: String,
    },
    NewAgent,
    EditAgent(String),
    NewMcpServer,
    EditMcpServer(String),
    SignIn,
    RedeemCard,
    ChangePassword,
    DeleteAccountForm,
    EditServerUrl,
    EditWorkspace,
    NewProject,
    EditProject(String),
}

#[derive(Debug, Clone, PartialEq)]
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

    pub fn focused(&self) -> Option<&Field> {
        self.fields.get(self.focus)
    }

    pub fn focused_mut(&mut self) -> Option<&mut Field> {
        self.fields.get_mut(self.focus)
    }
}

/// Toggle a boolean/choice field in place.
pub fn toggle_field(form: &mut Form) {
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

// ---------------------------------------------------------------------------
// confirmation
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq)]
pub enum ConfirmPurpose {
    DeleteConversation(String),
    DeleteProvider(String),
    DeleteModel { provider_id: String, model_id: String },
    DeleteAgent(String),
    DeleteMcpServer(String),
    DeleteProject(String),
    RedeemCard(String),
    /// Account deletion carries the password the user typed in the form.
    DeleteAccount(String),
    SignOut,
}

#[derive(Debug, Clone, PartialEq)]
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
// select list
// ---------------------------------------------------------------------------

/// What picking an entry from a [`Popup::Select`] does.
#[derive(Debug, Clone, PartialEq)]
pub enum SelectPurpose {
    /// Switch the conversation's Agent.
    Agent,
    /// Open a conversation inside the picked project.
    Project,
    /// Open the picked conversation.
    Conversation,
    /// Pick a provider, then a model of that provider.
    Provider,
    /// Pick a model, then bind it on the open conversation.
    Model,
    /// Pick an MCP server, then toggle it on/off.
    McpServer,
    /// Run one local setting action.
    Setting,
}

/// One selectable row.
#[derive(Debug, Clone, PartialEq)]
pub struct SelectItem {
    pub label: String,
    pub detail: String,
    /// Opaque id handed back on pick.
    pub id: String,
    /// Draw as the current selection (a check marker rather than a dot).
    pub selected: bool,
}

impl SelectItem {
    pub fn new(label: impl Into<String>, id: impl Into<String>) -> Self {
        Self {
            label: label.into(),
            detail: String::new(),
            id: id.into(),
            selected: false,
        }
    }

    pub fn detail(mut self, detail: impl Into<String>) -> Self {
        self.detail = detail.into();
        self
    }

    pub fn selected(mut self, selected: bool) -> Self {
        self.selected = selected;
        self
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Select {
    pub title: String,
    pub items: Vec<SelectItem>,
    pub cursor: usize,
    pub purpose: SelectPurpose,
}

// ---------------------------------------------------------------------------
// the popup stack
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq)]
pub enum Popup {
    /// The `/` command menu: a filter buffer plus the matching commands.
    Commands { buffer: String, cursor: usize },
    Select(Select),
    Form(Form),
    Confirm(Confirm),
    /// The keys reference; `scroll` is its own viewport offset because the
    /// body is longer than a short terminal.
    Help { scroll: usize },
}

impl Popup {
    pub fn title(&self) -> &str {
        match self {
            Popup::Commands { .. } => "commands",
            Popup::Select(select) => &select.title,
            Popup::Form(form) => &form.title,
            Popup::Confirm(confirm) => &confirm.title,
            Popup::Help { .. } => "keys",
        }
    }
}

/// The commands matching a `/` buffer, as palette rows.
pub fn command_rows(buffer: &str) -> Vec<(String, String)> {
    let needle = buffer.trim_start_matches('/').to_lowercase();
    SLASH_COMMANDS
        .iter()
        .filter(|command| command.name.starts_with(&needle))
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
    fn command_rows_filter_by_prefix() {
        // `mo` is deliberately ambiguous (mode / model): Tab keeps typing and
        // lists them rather than picking one.
        let rows = command_rows("mo");
        let labels: Vec<&str> = rows.iter().map(|(label, _)| label.as_str()).collect();
        assert_eq!(labels, vec!["/model", "/mode [read-only|writable]"]);
        // A unique prefix narrows to exactly one row.
        assert_eq!(command_rows("proj").len(), 1);
        assert!(command_rows("").len() > 5);
    }
}