//! The five workspace operations over the real filesystem.
//!
//! Behaviour (message strings, bounds, row shapes) mirrors
//! `WorkspaceTools.desktop.kt`. Absolute paths are used as-is and relative
//! paths resolve against the workspace, but nothing is path-checked: the
//! user process IS the sandbox, and the read-only/writable mode is enforced
//! at declaration time.
//!
//! One deliberate difference from the Kotlin desktop host: `grep` is
//! implemented in-process (walkdir + regex) instead of shelling out to
//! ripgrep, because the TUI does not bundle `rg`. The row contract
//! (`path:line:text`, forward slashes, `(results truncated at N)` suffix) is
//! identical.

use std::path::{Path, PathBuf};

use messenger_tools::tools::{
    CREATE, DEFAULT_LINES, DEFAULT_RESULTS, EDIT, GLOB, GREP, MAX_LINES, MAX_RESULTS, READ,
};
use messenger_tools::ToolExecutionResult;
use serde_json::Value;
use walkdir::WalkDir;

/// Per-file size cap for read/edit/create (4 MiB, matching the Kotlin host).
const MAX_BYTES: u64 = 4 * 1024 * 1024;
/// File-count cap for glob/read/create scans (Kotlin's `limit(20_001)`).
const MAX_SCAN_FILES: usize = 20_001;

/// Dispatch one workspace tool call.
pub fn execute(name: &str, arguments_json: &str, workspace: &Path) -> ToolExecutionResult {
    let Ok(json) = serde_json::from_str::<Value>(arguments_json) else {
        return invalid_arguments(name);
    };
    if !json.is_object() {
        return invalid_arguments(name);
    }
    match name {
        GLOB => {
            let Some(pattern) = string(&json, "pattern") else {
                return invalid_arguments(name);
            };
            let max_results = int(&json, "maxResults", DEFAULT_RESULTS as i64)
                .clamp(1, MAX_RESULTS as i64) as usize;
            glob(&pattern, max_results, workspace)
        }
        GREP => {
            let Some(pattern) = string(&json, "pattern") else {
                return invalid_arguments(name);
            };
            let path = string(&json, "path").unwrap_or_else(|| ".".to_string());
            let file_glob = string(&json, "fileGlob");
            // The Kotlin host defaults `caseSensitive` to true.
            let case_sensitive = boolean(&json, "caseSensitive", true);
            let fixed_string = boolean(&json, "fixedString", false);
            let max_results = int(&json, "maxResults", DEFAULT_RESULTS as i64)
                .clamp(1, MAX_RESULTS as i64) as usize;
            grep(
                &pattern,
                &path,
                file_glob.as_deref(),
                case_sensitive,
                fixed_string,
                max_results,
                workspace,
            )
        }
        READ => {
            let Some(path) = string(&json, "path") else {
                return invalid_arguments(name);
            };
            let start_line = int(&json, "startLine", 1).max(1) as usize;
            let max_lines = int(&json, "maxLines", DEFAULT_LINES as i64)
                .clamp(1, MAX_LINES as i64) as usize;
            read(&path, start_line, max_lines, workspace)
        }
        EDIT => {
            let (Some(path), Some(old_text), Some(new_text)) = (
                string(&json, "path"),
                string(&json, "oldText"),
                string(&json, "newText"),
            ) else {
                return invalid_arguments(name);
            };
            let replace_all = boolean(&json, "replaceAll", false);
            edit(&path, &old_text, &new_text, replace_all, workspace)
        }
        CREATE => {
            let (Some(path), Some(content)) = (string(&json, "path"), string(&json, "content"))
            else {
                return invalid_arguments(name);
            };
            let overwrite = boolean(&json, "overwrite", false);
            create(&path, &content, overwrite, workspace)
        }
        other => error(format!("Unknown workspace tool: {other}")),
    }
}

fn invalid_arguments(name: &str) -> ToolExecutionResult {
    error(format!("Invalid arguments for {name}."))
}

fn error(message: impl Into<String>) -> ToolExecutionResult {
    ToolExecutionResult {
        output: message.into(),
        is_error: true,
    }
}

fn ok(message: impl Into<String>) -> ToolExecutionResult {
    ToolExecutionResult {
        output: message.into(),
        is_error: false,
    }
}

fn string(json: &Value, key: &str) -> Option<String> {
    json.get(key)?.as_str().map(str::to_string)
}

fn int(json: &Value, key: &str, default: i64) -> i64 {
    match json.get(key).and_then(Value::as_i64) {
        Some(value) => value,
        None => default,
    }
}

fn boolean(json: &Value, key: &str, default: bool) -> bool {
    match json.get(key).and_then(Value::as_bool) {
        Some(value) => value,
        None => default,
    }
}

/// Resolve a path argument: absolute as-is, otherwise against the workspace.
fn resolve(raw: &str, workspace: &Path) -> PathBuf {
    let candidate = Path::new(raw);
    if candidate.is_absolute() {
        candidate.to_path_buf()
    } else {
        workspace.join(candidate)
    }
}

/// Forward-slashed path, relative to the workspace when it is inside it.
fn relative(path: &Path, workspace: &Path) -> String {
    let canonical = std::fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf());
    let root = std::fs::canonicalize(workspace).unwrap_or_else(|_| workspace.to_path_buf());
    canonical
        .strip_prefix(&root)
        .unwrap_or(&canonical)
        .to_string_lossy()
        .replace('\\', "/")
}

// ---------------------------------------------------------------------------
// glob
// ---------------------------------------------------------------------------

/// Kotlin `globRegex` ported verbatim: `**/` → optional directory prefix,
/// `**` → anything, `*`/`?` stay within one path segment, and every other
/// regex metacharacter is escaped (so `[abc]` is a literal bracket group,
/// unlike a real glob implementation).
fn glob_regex(glob: &str) -> Option<regex::Regex> {
    let mut pattern = String::from("^");
    let chars: Vec<char> = glob.chars().collect();
    let mut i = 0usize;
    while i < chars.len() {
        if chars[i..].starts_with(&['*', '*', '/']) {
            pattern.push_str("(?:.*/)?");
            i += 3;
        } else if chars[i..].starts_with(&['*', '*']) {
            pattern.push_str(".*");
            i += 2;
        } else if chars[i] == '*' {
            pattern.push_str("[^/]*");
            i += 1;
        } else if chars[i] == '?' {
            pattern.push_str("[^/]");
            i += 1;
        } else {
            let ch = chars[i];
            if ".()[]{}+$^|\\".contains(ch) {
                pattern.push('\\');
            }
            pattern.push(ch);
            i += 1;
        }
    }
    pattern.push('$');
    regex::Regex::new(&pattern).ok()
}

fn glob(pattern: &str, max_results: usize, workspace: &Path) -> ToolExecutionResult {
    let Some(matcher) = glob_regex(pattern) else {
        return error(format!("Invalid glob pattern: {pattern}"));
    };
    let mut matches: Vec<String> = Vec::new();
    for entry in walk_files(workspace) {
        let relative_path = relative(&entry, workspace);
        let absolute = entry.to_string_lossy().replace('\\', "/");
        if matcher.is_match(&relative_path) || matcher.is_match(&absolute) {
            matches.push(relative_path);
            if matches.len() > max_results {
                break;
            }
        }
    }
    let truncated = matches.len() > max_results;
    if truncated {
        matches.truncate(max_results);
    }
    if matches.is_empty() {
        return ok("No matching files.");
    }
    let mut body = matches.join("\n");
    if truncated {
        body.push_str(&format!("\n(results truncated at {max_results})"));
    }
    ok(body)
}

/// Every regular file under `base`, capped at [`MAX_SCAN_FILES`], following
/// the workspace root's canonical form.
fn walk_files(base: &Path) -> Vec<PathBuf> {
    let root = std::fs::canonicalize(base).unwrap_or_else(|_| base.to_path_buf());
    WalkDir::new(&root)
        .follow_links(false)
        .into_iter()
        .filter_map(Result::ok)
        .filter(|entry| entry.file_type().is_file())
        .take(MAX_SCAN_FILES)
        .map(|entry| entry.into_path())
        .collect()
}

/// Map a path to the workspace-relative forward-slash form when inside,
/// otherwise a forward-slashed absolute path.
fn display_path(path: &Path, workspace: &Path) -> String {
    let root = std::fs::canonicalize(workspace).unwrap_or_else(|_| workspace.to_path_buf());
    let canonical = std::fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf());
    if canonical.starts_with(&root) {
        canonical
            .strip_prefix(&root)
            .unwrap_or(&canonical)
            .to_string_lossy()
            .replace('\\', "/")
    } else {
        canonical.to_string_lossy().replace('\\', "/")
    }
}

// ---------------------------------------------------------------------------
// grep
// ---------------------------------------------------------------------------

#[allow(clippy::too_many_arguments)]
fn grep(
    pattern: &str,
    path: &str,
    file_glob: Option<&str>,
    case_sensitive: bool,
    fixed_string: bool,
    max_results: usize,
    workspace: &Path,
) -> ToolExecutionResult {
    if pattern.is_empty() {
        return error("Search pattern cannot be empty.");
    }
    let matcher = if fixed_string {
        None
    } else {
        let expression = if case_sensitive {
            pattern.to_string()
        } else {
            format!("(?i){pattern}")
        };
        match regex::Regex::new(&expression) {
            Ok(regex) => Some(regex),
            Err(e) => return error(format!("Invalid regular expression: {e}")),
        }
    };
    let file_filter = match file_glob {
        Some(glob) => match globset::Glob::new(glob) {
            Ok(compiled) => Some(compiled.compile_matcher()),
            Err(e) => return error(format!("Invalid fileGlob: {e}")),
        },
        None => None,
    };
    let needle = if case_sensitive {
        pattern.to_string()
    } else {
        pattern.to_lowercase()
    };

    let search_root = resolve(path, workspace);
    let resolved_root = std::fs::canonicalize(&search_root).unwrap_or_else(|_| search_root.clone());
    if !resolved_root.exists() {
        return error("Path does not exist.");
    }

    let candidates: Vec<PathBuf> = if resolved_root.is_file() {
        vec![resolved_root.clone()]
    } else {
        walk_files(&resolved_root)
    };

    let mut rows: Vec<String> = Vec::new();
    let mut truncated = false;
    'files: for file in candidates {
        if let Some(filter) = &file_filter {
            if !filter.is_match(&file) {
                continue;
            }
        }
        let Ok(metadata) = std::fs::metadata(&file) else {
            continue;
        };
        if metadata.len() > MAX_BYTES {
            continue;
        }
        let Ok(bytes) = std::fs::read(&file) else {
            continue;
        };
        // Binary files are skipped (ripgrep's default detection).
        if bytes.iter().take(8000).any(|b| *b == 0) {
            continue;
        }
        let text = String::from_utf8_lossy(&bytes);
        let display = display_path(&file, workspace);
        for (index, line) in text.lines().enumerate() {
            let hit = match &matcher {
                Some(regex) => regex.is_match(line),
                None => {
                    if case_sensitive {
                        line.contains(&needle)
                    } else {
                        line.to_lowercase().contains(&needle)
                    }
                }
            };
            if !hit {
                continue;
            }
            rows.push(format!("{display}:{}:{line}", index + 1));
            if rows.len() > max_results {
                truncated = true;
                break 'files;
            }
        }
    }

    if truncated {
        rows.truncate(max_results);
    }
    if rows.is_empty() {
        return ok("No matches.");
    }
    let mut body = rows.join("\n");
    if truncated {
        body.push_str(&format!("\n(results truncated at {max_results})"));
    }
    ok(body)
}

// ---------------------------------------------------------------------------
// read / edit / create
// ---------------------------------------------------------------------------

fn read(path: &str, start_line: usize, max_lines: usize, workspace: &Path) -> ToolExecutionResult {
    if path.trim().is_empty() {
        return error("Path cannot be empty.");
    }
    let file = resolve(path, workspace);
    let Ok(metadata) = std::fs::metadata(&file) else {
        return error("Path does not exist.");
    };
    if !metadata.is_file() || metadata.len() > MAX_BYTES {
        return error("Path is not a regular text file or exceeds 4 MiB.");
    }
    let Ok(text) = std::fs::read_to_string(&file) else {
        return error("Path is not a regular text file or exceeds 4 MiB.");
    };
    let lines: Vec<&str> = text.lines().collect();
    let from = (start_line - 1).min(lines.len());
    let until = (from + max_lines).min(lines.len());
    let body = lines[from..until]
        .iter()
        .enumerate()
        .map(|(offset, line)| format!("{}: {line}", from + offset + 1))
        .collect::<Vec<_>>()
        .join("\n");
    let mut output = if body.is_empty() {
        "(empty file)".to_string()
    } else {
        body
    };
    if until < lines.len() {
        output.push_str(&format!("\n(output truncated; {} more lines)", lines.len() - until));
    }
    ok(output)
}

fn edit(
    path: &str,
    old_text: &str,
    new_text: &str,
    replace_all: bool,
    workspace: &Path,
) -> ToolExecutionResult {
    if old_text.is_empty() {
        return error("oldText cannot be empty.");
    }
    let file = resolve(path, workspace);
    if path.trim().is_empty() {
        return error("Path cannot be empty.");
    }
    let Ok(metadata) = std::fs::metadata(&file) else {
        return error("File is missing, not a regular file, or exceeds 4 MiB.");
    };
    if !metadata.is_file() || metadata.len() > MAX_BYTES {
        return error("File is missing, not a regular file, or exceeds 4 MiB.");
    }
    let Ok(source) = std::fs::read_to_string(&file) else {
        return error("File is missing, not a regular file, or exceeds 4 MiB.");
    };
    let count = match old_text.len() {
        0 => 0,
        length => source
            .as_bytes()
            .windows(length)
            .filter(|window| *window == old_text.as_bytes())
            .count(),
    };
    if count == 0 {
        return error("oldText was not found; no changes made.");
    }
    if !replace_all && count != 1 {
        return error(format!(
            "oldText matched {count} times; set replaceAll=true to replace all."
        ));
    }
    let updated = if replace_all {
        source.replace(old_text, new_text)
    } else {
        source.replacen(old_text, new_text, 1)
    };
    if updated.len() > MAX_BYTES as usize {
        return error("Edited file exceeds 4 MiB.");
    }
    if std::fs::write(&file, updated).is_err() {
        return error("Failed to write the edited file.");
    }
    ok(format!("Updated {}.", relative(&file, workspace)))
}

fn create(path: &str, content: &str, overwrite: bool, workspace: &Path) -> ToolExecutionResult {
    if path.trim().is_empty() {
        return error("Path cannot be empty.");
    }
    if content.len() > MAX_BYTES as usize {
        return error("Content exceeds 4 MiB.");
    }
    let file = resolve(path, workspace);
    if file.exists() && !overwrite {
        return error(format!(
            "{} already exists; set overwrite=true to replace it.",
            display_path(&file, workspace)
        ));
    }
    if let Some(parent) = file.parent() {
        if std::fs::create_dir_all(parent).is_err() {
            return error("Failed to create the parent directory.");
        }
    }
    if std::fs::write(&file, content).is_err() {
        return error("Failed to write the file.");
    }
    ok(format!("Created {}.", relative(&file, workspace)))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn workspace() -> tempfile::TempDir {
        tempfile::tempdir().unwrap()
    }

    #[test]
    fn glob_returns_relative_paths_and_truncation_suffix() {
        let dir = workspace();
        std::fs::create_dir_all(dir.path().join("sub")).unwrap();
        std::fs::write(dir.path().join("a.kt"), "a").unwrap();
        std::fs::write(dir.path().join("sub/b.kt"), "b").unwrap();
        let result = execute(GLOB, r#"{"pattern":"**/*.kt"}"#, dir.path());
        assert!(!result.is_error, "{}", result.output);
        assert!(result.output.contains("a.kt"));
        assert!(result.output.contains("sub/b.kt"));

        let truncated = execute(GLOB, r#"{"pattern":"**/*.kt","maxResults":1}"#, dir.path());
        assert!(truncated.output.contains("(results truncated at 1)"), "{}", truncated.output);
    }

    #[test]
    fn glob_reports_no_matches() {
        let dir = workspace();
        let result = execute(GLOB, r#"{"pattern":"**/*.rs"}"#, dir.path());
        assert_eq!(result.output, "No matching files.");
    }

    #[test]
    fn grep_returns_path_line_text_rows() {
        let dir = workspace();
        std::fs::write(dir.path().join("a.txt"), "alpha\nbeta\n").unwrap();
        let result = execute(GREP, r#"{"pattern":"beta"}"#, dir.path());
        assert!(!result.is_error, "{}", result.output);
        assert_eq!(result.output, "a.txt:2:beta");
    }

    #[test]
    fn grep_preserves_colons_inside_matched_text() {
        let dir = workspace();
        std::fs::write(dir.path().join("a.txt"), "key: value\n").unwrap();
        let result = execute(GREP, r#"{"pattern":"key"}"#, dir.path());
        assert_eq!(result.output, "a.txt:1:key: value");
    }

    #[test]
    fn grep_is_case_insensitive_by_default_only_when_asked() {
        let dir = workspace();
        std::fs::write(dir.path().join("a.txt"), "Alpha\n").unwrap();
        assert_eq!(execute(GREP, r#"{"pattern":"alpha"}"#, dir.path()).output, "No matches.");
        assert_eq!(
            execute(GREP, r#"{"pattern":"alpha","caseSensitive":false}"#, dir.path()).output,
            "a.txt:1:Alpha"
        );
    }

    #[test]
    fn grep_fixed_string_escapes_regex_metacharacters() {
        let dir = workspace();
        std::fs::write(dir.path().join("a.txt"), "a.b\naxb\n").unwrap();
        let result = execute(GREP, r#"{"pattern":"a.b","fixedString":true}"#, dir.path());
        assert_eq!(result.output, "a.txt:1:a.b");
    }

    #[test]
    fn grep_truncates_at_max_results() {
        let dir = workspace();
        std::fs::write(dir.path().join("a.txt"), "x\nx\nx\n").unwrap();
        let result = execute(GREP, r#"{"pattern":"x","maxResults":2}"#, dir.path());
        assert_eq!(result.output, "a.txt:1:x\na.txt:2:x\n(results truncated at 2)");
    }

    #[test]
    fn grep_scopes_to_a_single_file_and_honours_file_glob() {
        let dir = workspace();
        std::fs::write(dir.path().join("a.txt"), "hit\n").unwrap();
        std::fs::write(dir.path().join("b.md"), "hit\n").unwrap();
        assert_eq!(execute(GREP, r#"{"pattern":"hit","path":"a.txt"}"#, dir.path()).output, "a.txt:1:hit");
        assert_eq!(
            execute(GREP, r#"{"pattern":"hit","fileGlob":"*.md"}"#, dir.path()).output,
            "b.md:1:hit"
        );
    }

    #[test]
    fn read_numbers_lines_and_reports_truncation() {
        let dir = workspace();
        std::fs::write(dir.path().join("a.txt"), "one\ntwo\nthree\n").unwrap();
        assert_eq!(
            execute(READ, r#"{"path":"a.txt"}"#, dir.path()).output,
            "1: one\n2: two\n3: three"
        );
        assert_eq!(
            execute(READ, r#"{"path":"a.txt","startLine":2,"maxLines":1}"#, dir.path()).output,
            "2: two\n(output truncated; 1 more lines)"
        );
    }

    #[test]
    fn read_reports_empty_and_missing_files() {
        let dir = workspace();
        std::fs::write(dir.path().join("empty.txt"), "").unwrap();
        assert_eq!(execute(READ, r#"{"path":"empty.txt"}"#, dir.path()).output, "(empty file)");
        let missing = execute(READ, r#"{"path":"nope.txt"}"#, dir.path());
        assert!(missing.is_error);
        assert_eq!(missing.output, "Path does not exist.");
    }

    #[test]
    fn edit_replaces_once_and_refuses_ambiguous_matches() {
        let dir = workspace();
        std::fs::write(dir.path().join("a.txt"), "aaa\n").unwrap();
        let ambiguous = execute(
            EDIT,
            r#"{"path":"a.txt","oldText":"a","newText":"b"}"#,
            dir.path(),
        );
        assert!(ambiguous.is_error);
        assert_eq!(ambiguous.output, "oldText matched 3 times; set replaceAll=true to replace all.");

        let replaced = execute(
            EDIT,
            r#"{"path":"a.txt","oldText":"a","newText":"b","replaceAll":true}"#,
            dir.path(),
        );
        assert_eq!(replaced.output, "Updated a.txt.");
        assert_eq!(std::fs::read_to_string(dir.path().join("a.txt")).unwrap(), "bbb\n");
    }

    #[test]
    fn edit_reports_missing_and_empty_old_text() {
        let dir = workspace();
        std::fs::write(dir.path().join("a.txt"), "hi\n").unwrap();
        assert_eq!(
            execute(EDIT, r#"{"path":"a.txt","oldText":"","newText":"x"}"#, dir.path()).output,
            "oldText cannot be empty."
        );
        assert_eq!(
            execute(EDIT, r#"{"path":"a.txt","oldText":"zzz","newText":"x"}"#, dir.path()).output,
            "oldText was not found; no changes made."
        );
    }

    #[test]
    fn create_writes_and_refuses_overwrite() {
        let dir = workspace();
        let created = execute(CREATE, r#"{"path":"deep/a.txt","content":"hi"}"#, dir.path());
        assert_eq!(created.output, "Created deep/a.txt.");
        assert_eq!(std::fs::read_to_string(dir.path().join("deep/a.txt")).unwrap(), "hi");

        let refused = execute(CREATE, r#"{"path":"deep/a.txt","content":"no"}"#, dir.path());
        assert!(refused.is_error);
        assert!(refused.output.contains("already exists"), "{}", refused.output);

        let overwritten = execute(
            CREATE,
            r#"{"path":"deep/a.txt","content":"yes","overwrite":true}"#,
            dir.path(),
        );
        assert!(!overwritten.is_error);
        assert_eq!(std::fs::read_to_string(dir.path().join("deep/a.txt")).unwrap(), "yes");
    }

    #[test]
    fn unknown_tool_and_malformed_arguments_are_errors() {
        let dir = workspace();
        assert!(execute("nope", "{}", dir.path()).is_error);
        assert!(execute(GLOB, "not json", dir.path()).is_error);
        assert_eq!(
            execute(GLOB, "{}", dir.path()).output,
            "Invalid arguments for glob."
        );
    }

    #[test]
    fn absolute_paths_are_used_as_is() {
        let dir = workspace();
        let other = workspace();
        std::fs::write(other.path().join("x.txt"), "outside\n").unwrap();
        let absolute = other.path().join("x.txt");
        let result = execute(
            READ,
            &format!(r#"{{"path":{}}}"#, serde_json::to_string(&absolute.to_string_lossy()).unwrap()),
            dir.path(),
        );
        assert_eq!(result.output, "1: outside");
    }
}
