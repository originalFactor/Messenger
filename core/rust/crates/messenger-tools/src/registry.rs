//! Request-time tool resolution (port of `ChatViewModel.toolsForRequest`):
//! the per-tool agent config (missing key = enabled) and the read-only/
//! writable mode switch. Arguments are never inspected here.

use std::collections::HashMap;

use crate::tools::BuiltinTool;

/// The tool list declared for one chat turn.
///
/// - `tools_enabled` is the effective master switch; off → empty list.
/// - `tools_config` only records explicitly disabled tools — an absent key
///   means enabled, so newly added tools turn on automatically.
/// - Read-only mode excludes `write_access` tools (edit/create) and keeps
///   the terminal's inspection-steering description; writable mode declares
///   everything with no path or argument restrictions. Enforcement lives in
///   the sandbox (companion runtime UID / desktop process), not in argument
///   checks.
pub fn resolve_request_tools(
    registry: &[BuiltinTool],
    tools_enabled: bool,
    tools_config: &HashMap<String, bool>,
    writable: bool,
) -> Vec<BuiltinTool> {
    if !tools_enabled {
        return Vec::new();
    }
    let filtered: Vec<BuiltinTool> = registry
        .iter()
        .filter(|tool| tools_config.get(&tool.name).copied().unwrap_or(true))
        .cloned()
        .collect();
    apply_writable_mode(filtered, writable)
}

/// Apply the read-only/writable Agent mode to an already per-tool-filtered
/// tool list (the FFI platform passes resolved names). Read-only drops
/// `write_access` tools and keeps the terminal's inspection-steering
/// description; writable re-declares write tools and swaps the terminal to
/// the unrestricted description. Execution is identical in both modes.
pub fn apply_writable_mode(tools: Vec<BuiltinTool>, writable: bool) -> Vec<BuiltinTool> {
    tools
        .into_iter()
        .filter(|tool| writable || !tool.write_access)
        .map(|tool| {
            if writable && tool.name == BuiltinTool::TERMINAL_NAME {
                BuiltinTool::terminal(false)
            } else {
                tool
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
    fn read_only_mode_excludes_write_tools_and_keeps_inspection_terminal() {
        let resolved = resolve_request_tools(&builtin_registry(), true, &HashMap::new(), false);
        let names: Vec<&str> = resolved.iter().map(|t| t.name.as_str()).collect();
        assert!(names.contains(&"terminal"));
        assert!(!names.contains(&EDIT));
        assert!(!names.contains(&CREATE));
        let terminal = resolved.iter().find(|t| t.name == "terminal").unwrap();
        assert!(terminal.read_only);
    }

    #[test]
    fn writable_mode_includes_write_tools_and_unrestricted_terminal() {
        let resolved = resolve_request_tools(&builtin_registry(), true, &HashMap::new(), true);
        let names: Vec<&str> = resolved.iter().map(|t| t.name.as_str()).collect();
        assert!(names.contains(&EDIT) && names.contains(&CREATE));
        let terminal = resolved.iter().find(|t| t.name == "terminal").unwrap();
        assert!(!terminal.read_only);
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

    #[test]
    fn apply_writable_mode_matches_resolve_in_both_modes() {
        for writable in [false, true] {
            let resolved = resolve_request_tools(&builtin_registry(), true, &HashMap::new(), writable);
            let applied = apply_writable_mode(builtin_registry(), writable);
            assert_eq!(resolved, applied, "writable={writable}");
        }
    }

    #[test]
    fn apply_read_only_drops_write_tools_and_keeps_inspection_terminal() {
        let applied = apply_writable_mode(builtin_registry(), false);
        let names: Vec<&str> = applied.iter().map(|t| t.name.as_str()).collect();
        assert!(!names.contains(&EDIT) && !names.contains(&CREATE));
        let terminal = applied.iter().find(|t| t.name == "terminal").unwrap();
        assert!(terminal.read_only);
    }

    #[test]
    fn apply_writable_swaps_terminal_description() {
        let applied = apply_writable_mode(builtin_registry(), true);
        let terminal = applied.iter().find(|t| t.name == "terminal").unwrap();
        assert!(!terminal.read_only);
    }
}
