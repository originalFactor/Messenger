//! Model-facing tool declarations (port of `TerminalTool.kt` +
//! `WorkspaceTool.kt` declaration halves). Execution is routed by the agent
//! core through the platform ToolHost.

use serde_json::Value;

/// Result of executing one tool call. Tools never throw for invalid
/// arguments — they return `is_error = true` so the model can recover.
#[derive(Debug, Clone, PartialEq)]
pub struct ToolExecutionResult {
    pub output: String,
    pub is_error: bool,
}

/// One declarable tool: everything the request `tools` entry and the agent
/// loop need. MCP tools are represented with the same shape (`write_access:
/// false` — their write nature is not classified, matching the Kotlin
/// behavior).
#[derive(Debug, Clone, PartialEq)]
pub struct BuiltinTool {
    /// Unique function name sent to the model.
    pub name: String,
    /// Natural-language description the model uses to decide when to call.
    pub description: String,
    /// True when the tool can modify files or system state (workspace
    /// edit/create); such tools are only offered in writable Agent mode.
    pub write_access: bool,
    /// JSON Schema (as a JSON string) describing accepted arguments.
    pub parameters_json: String,
    /// Terminal-only: false lifts the read-only command policy (writable mode).
    pub enforce_read_only: bool,
}

impl BuiltinTool {
    pub const TERMINAL_NAME: &'static str = "terminal";
    pub const DEFAULT_TIMEOUT_MS: u64 = 60_000;
    pub const MAX_OUTPUT_CHARS: usize = 10_000;

    pub fn terminal(enforce_read_only: bool) -> Self {
        let (description, schema) = if enforce_read_only {
            (
                "Run one read-only inspection command in Messenger's isolated app-private workspace and return its combined output and exit code. \
                 Do not use shell operators, interpreters, redirection, or commands that modify files. File changes use the workspace edit/create tools.",
                r#"{"type":"object","properties":{"command":{"type":"string","description":"One read-only inspection command to execute"}},"required":["command"]}"#,
            )
        } else {
            (
                "Run one command in Messenger's isolated app-private workspace and return its combined output and exit code. \
                 The command can create, modify, or delete files inside the workspace.",
                r#"{"type":"object","properties":{"command":{"type":"string","description":"One command to execute"}},"required":["command"]}"#,
            )
        };
        Self {
            name: Self::TERMINAL_NAME.to_string(),
            description: description.to_string(),
            write_access: false,
            parameters_json: schema.to_string(),
            enforce_read_only,
        }
    }

    /// Parse the model's arguments JSON and extract the `command` string
    /// field; `None` for malformed or non-string payloads.
    pub fn parse_command(arguments_json: &str) -> Option<String> {
        let value: Value = serde_json::from_str(arguments_json).ok()?;
        value.get("command")?.as_str().map(str::to_string)
    }

    /// Overlong output keeps only the tail (errors usually live there) with
    /// a truncation marker prepended.
    pub fn truncate_output(output: &str, max_chars: usize) -> String {
        if output.chars().count() <= max_chars {
            return if output.is_empty() {
                "(no output)".to_string()
            } else {
                output.to_string()
            };
        }
        let tail: String = output.chars().rev().take(max_chars).collect::<Vec<_>>().into_iter().rev().collect();
        format!("(output truncated, showing last {max_chars} characters)\n{tail}")
    }
}

pub const GLOB: &str = "glob";
pub const GREP: &str = "grep";
pub const READ: &str = "read";
pub const EDIT: &str = "edit";
pub const CREATE: &str = "create";
pub const DEFAULT_RESULTS: u32 = 200;
pub const MAX_RESULTS: u32 = 1000;
pub const DEFAULT_LINES: u32 = 200;
pub const MAX_LINES: u32 = 2000;

const WORKSPACE_SCHEMAS: [(&str, &str); 5] = [
    (GLOB, r#"{"type":"object","properties":{"pattern":{"type":"string"},"maxResults":{"type":"integer","minimum":1,"maximum":1000}},"required":["pattern"]}"#),
    (GREP, r#"{"type":"object","properties":{"pattern":{"type":"string"},"path":{"type":"string"},"fileGlob":{"type":"string"},"caseSensitive":{"type":"boolean"},"maxResults":{"type":"integer","minimum":1,"maximum":1000}},"required":["pattern"]}"#),
    (READ, r#"{"type":"object","properties":{"path":{"type":"string"},"startLine":{"type":"integer","minimum":1},"maxLines":{"type":"integer","minimum":1,"maximum":2000}},"required":["path"]}"#),
    (EDIT, r#"{"type":"object","properties":{"path":{"type":"string"},"oldText":{"type":"string"},"newText":{"type":"string"},"replaceAll":{"type":"boolean"}},"required":["path","oldText","newText"]}"#),
    (CREATE, r#"{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"overwrite":{"type":"boolean"}},"required":["path","content"]}"#),
];

/// All five workspace tools in canonical order (glob/grep/read/edit/create).
pub fn workspace_tools() -> Vec<BuiltinTool> {
    [
        (GLOB, "Find workspace files matching a glob such as **/*.kt.", false),
        (
            GREP,
            "Search workspace text files for a literal substring or regular expression.",
            false,
        ),
        (READ, "Read a line range from a workspace text file.", false),
        (
            EDIT,
            "Replace exact text in one workspace file; replacement must match exactly once unless replaceAll is true.",
            true,
        ),
        (
            CREATE,
            "Create a text file in the workspace. Existing files are not overwritten unless overwrite is true.",
            true,
        ),
    ]
    .into_iter()
    .map(|(name, description, write_access)| {
        let parameters_json = WORKSPACE_SCHEMAS
            .iter()
            .find(|(key, _)| *key == name)
            .map(|(_, schema)| schema.to_string())
            .unwrap_or_default();
        BuiltinTool {
            name: name.to_string(),
            description: description.to_string(),
            write_access,
            parameters_json,
            enforce_read_only: false,
        }
    })
    .collect()
}

/// The full built-in registry: read-only terminal + all workspace tools.
pub fn builtin_registry() -> Vec<BuiltinTool> {
    let mut tools = vec![BuiltinTool::terminal(true)];
    tools.extend(workspace_tools());
    tools
}

#[cfg(test)]
mod tests {
    use super::*;

    // -- ported: TerminalToolTest --

    #[test]
    fn parses_command_from_valid_arguments_json() {
        assert_eq!(
            BuiltinTool::parse_command(r#"{"command":"dir"}"#),
            Some("dir".to_string())
        );
        assert_eq!(
            BuiltinTool::parse_command(r#"{"command":"ls -la","cwd":"/tmp"}"#),
            Some("ls -la".to_string())
        );
    }

    #[test]
    fn returns_none_for_malformed_arguments() {
        assert_eq!(BuiltinTool::parse_command("not json"), None);
        assert_eq!(BuiltinTool::parse_command("[1,2,3]"), None);
        assert_eq!(BuiltinTool::parse_command(r#"{"nope":1}"#), None);
        assert_eq!(BuiltinTool::parse_command(r#"{"command":123}"#), None);
    }

    #[test]
    fn truncate_keeps_short_output_untouched() {
        assert_eq!(BuiltinTool::truncate_output("hello", 10_000), "hello");
        assert_eq!(BuiltinTool::truncate_output("", 10_000), "(no output)");
    }

    #[test]
    fn truncate_keeps_tail_of_long_output_with_marker() {
        let long = format!("{}TAIL", "x".repeat(500));
        let truncated = BuiltinTool::truncate_output(&long, 100);
        assert!(truncated.starts_with("(output truncated"));
        assert!(truncated.ends_with("TAIL"));
        assert!(truncated.chars().count() < 160);
    }

    #[test]
    fn tool_schema_is_well_formed_json() {
        let tool = BuiltinTool::terminal(true);
        assert_eq!(tool.name, "terminal");
        let parsed: Value = serde_json::from_str(&tool.parameters_json).unwrap();
        assert!(parsed.is_object());
    }

    #[test]
    fn terminal_variants_differ_in_description_and_schema() {
        let ro = BuiltinTool::terminal(true);
        let rw = BuiltinTool::terminal(false);
        assert_ne!(ro.description, rw.description);
        assert_ne!(ro.parameters_json, rw.parameters_json);
        assert!(ro.enforce_read_only);
        assert!(!rw.enforce_read_only);
    }

    #[test]
    fn workspace_tools_cover_all_five_with_write_flags() {
        let tools = workspace_tools();
        let names: Vec<&str> = tools.iter().map(|t| t.name.as_str()).collect();
        assert_eq!(names, vec![GLOB, GREP, READ, EDIT, CREATE]);
        for tool in &tools {
            let parsed: Value = serde_json::from_str(&tool.parameters_json).unwrap();
            assert!(parsed.is_object(), "schema must parse for {}", tool.name);
        }
        let write_tools: Vec<&str> = tools
            .iter()
            .filter(|t| t.write_access)
            .map(|t| t.name.as_str())
            .collect();
        assert_eq!(write_tools, vec![EDIT, CREATE]);
    }
}
