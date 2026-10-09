//! The `/` command table.
//!
//! Commands are plain data: the palette filters it, `/help` prints it, and
//! `App::run_slash` dispatches on the name. Adding a command is one row plus
//! one match arm — there is no per-command key binding anywhere.

/// One `/` command.
#[derive(Debug, Clone, Copy)]
pub struct SlashCommand {
    pub name: &'static str,
    /// Free-form argument hint shown in the palette.
    pub args: &'static str,
    pub summary: &'static str,
    /// The popup this command opens, or `None` for commands that only
    /// mutate state and answer with a transcript note.
    pub opens: Option<Opens>,
}

/// Which picker a command opens.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Opens {
    Agent,
    Project,
    Provider,
    Model,
    Conversation,
    McpServer,
}

const fn cmd(
    name: &'static str,
    args: &'static str,
    summary: &'static str,
    opens: Option<Opens>,
) -> SlashCommand {
    SlashCommand {
        name,
        args,
        summary,
        opens,
    }
}

/// Every `/` command, in palette order — the menu is the reference for what
/// this session can do, exactly like pi's.
pub const SLASH_COMMANDS: &[SlashCommand] = &[
    cmd("help", "", "list the slash commands", None),
    cmd("new", "", "start a new conversation in this project", None),
    cmd(
        "agent",
        "",
        "switch the conversation's Agent",
        Some(Opens::Agent),
    ),
    cmd(
        "model",
        "",
        "pick the model this conversation uses",
        Some(Opens::Model),
    ),
    cmd(
        "provider",
        "",
        "pick a provider, then fetch its models",
        Some(Opens::Provider),
    ),
    cmd("mode", "[read-only|writable]", "set the Agent tool mode", None),
    cmd(
        "project",
        "[new|edit|<name>]",
        "show, create, edit or enter a project",
        Some(Opens::Project),
    ),
    cmd(
        "resume",
        "[n]",
        "open the n-th recent conversation",
        Some(Opens::Conversation),
    ),
    cmd("conversations", "", "browse every conversation", Some(Opens::Conversation)),
    cmd("rename", "[title]", "rename this conversation", None),
    cmd("delete", "", "delete this conversation", None),
    cmd("agent.new", "", "create an Agent", None),
    cmd("agent.edit", "[n]", "edit the n-th Agent", Some(Opens::Agent)),
    cmd("agent.delete", "[n]", "delete the n-th Agent", None),
    cmd(
        "mcp",
        "[new]",
        "manage MCP servers",
        Some(Opens::McpServer),
    ),
    cmd("settings", "", "settings, session and cloud", None),
    cmd("login", "", "sign in to a Messenger cloud account", None),
    cmd("logout", "", "sign out and drop the cloud provider", None),
    cmd("sync", "", "sync with the cloud now", None),
    cmd("card", "[code]", "redeem a card key", None),
    cmd("quit", "", "leave Messenger (the terminal is restored)", None),
];

/// Find a command by exact (case-insensitive) name.
pub fn find(name: &str) -> Option<&'static SlashCommand> {
    let needle = name.trim_start_matches('/').to_lowercase();
    SLASH_COMMANDS
        .iter()
        .find(|command| command.name == needle)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_command_name_is_unique_and_slash_free() {
        let mut names: Vec<&str> = SLASH_COMMANDS.iter().map(|c| c.name).collect();
        let count = names.len();
        names.sort_unstable();
        names.dedup();
        assert_eq!(names.len(), count, "duplicate command name");
        assert!(SLASH_COMMANDS.iter().all(|c| !c.name.starts_with('/')));
    }

    #[test]
    fn lookup_ignores_case_and_the_slash() {
        assert_eq!(find("MODE").unwrap().name, "mode");
        assert_eq!(find("/mode").unwrap().name, "mode");
        assert!(find("nope").is_none());
    }
}