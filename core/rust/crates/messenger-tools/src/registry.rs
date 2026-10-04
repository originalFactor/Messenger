//! Request-time tool resolution (port of `ChatViewModel.toolsForRequest`):
//! the per-tool agent config (missing key = enabled), the read-only/writable
//! mode switch, and the terminal's policy lifting in writable mode.

use std::collections::HashMap;

use crate::tools::BuiltinTool;

/// The tool list declared for one chat turn.
///
/// - `tools_enabled` is the effective master switch; off → empty list.
/// - `tools_config` only records explicitly disabled tools — an absent key
///   means enabled, so newly added tools turn on automatically.
/// - Read-only mode excludes `write_access` tools and keeps the terminal's
///   read-only policy; writable mode re-declares them and swaps the terminal
///   to the policy-free variant.
pub fn resolve_request_tools(
    registry: &[BuiltinTool],
    tools_enabled: bool,
    tools_config: &HashMap<String, bool>,
    writable: bool,
) -> Vec<BuiltinTool> {
    if !tools_enabled {
        return Vec::new();
    }
    registry
        .iter()
        .filter(|tool| tools_config.get(&tool.name).copied().unwrap_or(true))
        .filter(|tool| writable || !tool.write_access)
        .map(|tool| {
            if writable && tool.name == BuiltinTool::TERMINAL_NAME {
                BuiltinTool::terminal(false)
            } else {
                tool.clone()
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tools::{builtin_registry, CREATE, EDIT};

    #[test]
    fn master_switch_off_yields_empty() {
        assert!(resolve_request_tools(&builtin_registry(), false, &HashMap::new(), true).is_empty());
    }

    #[test]
    fn read_only_mode_excludes_write_tools_and_keeps_policy() {
        let resolved = resolve_request_tools(&builtin_registry(), true, &HashMap::new(), false);
        let names: Vec<&str> = resolved.iter().map(|t| t.name.as_str()).collect();
        assert!(names.contains(&"terminal"));
        assert!(!names.contains(&EDIT));
        assert!(!names.contains(&CREATE));
        let terminal = resolved.iter().find(|t| t.name == "terminal").unwrap();
        assert!(terminal.enforce_read_only);
    }

    #[test]
    fn writable_mode_includes_write_tools_and_lifts_policy() {
        let resolved = resolve_request_tools(&builtin_registry(), true, &HashMap::new(), true);
        let names: Vec<&str> = resolved.iter().map(|t| t.name.as_str()).collect();
        assert!(names.contains(&EDIT) && names.contains(&CREATE));
        let terminal = resolved.iter().find(|t| t.name == "terminal").unwrap();
        assert!(!terminal.enforce_read_only);
    }

    #[test]
    fn per_tool_config_disables_explicitly_listed_tools_only() {
        let mut config = HashMap::new();
        config.insert("grep".to_string(), false);
        config.insert("terminal".to_string(), false);
        let resolved = resolve_request_tools(&builtin_registry(), true, &config, true);
        let names: Vec<&str> = resolved.iter().map(|t| t.name.as_str()).collect();
        assert!(!names.contains(&"grep"));
        assert!(!names.contains(&"terminal"));
        assert!(names.contains(&"read"));
    }
}
