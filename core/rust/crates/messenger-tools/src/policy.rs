//! Conservative policy for the shell-backed terminal tool (port of
//! `ShellCommandPolicy.kt`).
//!
//! File mutation belongs to the explicit workspace `edit`/`create` tools.
//! The terminal therefore accepts only one command made from a small set of
//! inspection commands, with shell composition, redirection, interpreters,
//! and path escapes rejected before a process is started.

const READ_ONLY_COMMANDS: &[&str] = &[
    "cat",
    "cut",
    "date",
    "df",
    "dir",
    "du",
    "echo",
    "file",
    "find",
    "findstr",
    "get-childitem",
    "get-content",
    "get-item",
    "get-location",
    "grep",
    "head",
    "id",
    "ls",
    "measure-object",
    "printenv",
    "printf",
    "pwd",
    "rg",
    "select-string",
    "sort",
    "stat",
    "tail",
    "test",
    "tr",
    "type",
    "uname",
    "uniq",
    "wc",
    "where",
    "where-object",
    "whoami",
];

fn forbidden_options(command: &str) -> &'static [&'static str] {
    match command {
        "sort" | "uniq" => &["-o", "--output"],
        _ => &[],
    }
}

const FORBIDDEN_FIND_PREDICATES: &[&str] = &[
    "-delete",
    "-exec",
    "-execdir",
    "-fls",
    "-fprint",
    "-fprint0",
    "-fprintf",
    "-ok",
    "-okdir",
];

const SHELL_CONTROL_CHARACTERS: &[char] = &[';', '|', '&', '<', '>', '$', '`', '\\', '(', ')'];

/// Returns a user/model-visible rejection reason when `command` is not
/// read-only; `None` means the command may run.
pub fn rejection_reason(command: &str) -> Option<String> {
    if command.trim().is_empty() {
        return Some("command is empty".to_string());
    }
    if command.chars().any(|c| c == '\u{0000}' || c == '\n' || c == '\r') {
        return Some("multiline commands are not allowed".to_string());
    }
    if command.chars().any(|c| SHELL_CONTROL_CHARACTERS.contains(&c)) {
        return Some("shell operators, substitutions, and escapes are not allowed".to_string());
    }

    let Some(tokens) = tokenize(command) else {
        return Some("unterminated or malformed quoting".to_string());
    };
    let Some(executable) = tokens.first().map(|t| t.to_lowercase()) else {
        return Some("command is empty".to_string());
    };
    if !READ_ONLY_COMMANDS.contains(&executable.as_str()) {
        return Some(format!("'{executable}' is not an approved read-only command"));
    }
    if tokens.iter().any(|t| contains_path_escape(t)) {
        return Some("absolute paths and paths outside the workspace are not allowed".to_string());
    }
    let option_of = |token: &str| -> String {
        token
            .to_lowercase()
            .split('=')
            .next()
            .unwrap_or(token)
            .to_string()
    };
    if executable == "find"
        && tokens[1..]
            .iter()
            .any(|token| FORBIDDEN_FIND_PREDICATES.contains(&option_of(token).as_str()))
    {
        return Some("find write actions are not allowed".to_string());
    }
    let forbidden = forbidden_options(&executable);
    if !forbidden.is_empty()
        && tokens[1..]
            .iter()
            .any(|token| forbidden.contains(&option_of(token).as_str()))
    {
        return Some("output-file options are not allowed".to_string());
    }
    None
}

/// Quote-aware tokenizer: quoted spans keep whitespace as literal content;
/// unterminated quoting yields `None` (malformed).
fn tokenize(command: &str) -> Option<Vec<String>> {
    let mut tokens = Vec::new();
    let mut token = String::new();
    let mut quote: Option<char> = None;
    let mut started = false;

    for character in command.chars() {
        match quote {
            Some(q) => {
                if character == q {
                    quote = None;
                } else {
                    token.push(character);
                }
                started = true;
            }
            None => {
                if character == '\'' || character == '"' {
                    quote = Some(character);
                    started = true;
                } else if character.is_whitespace() {
                    if started {
                        tokens.push(std::mem::take(&mut token));
                        started = false;
                    }
                } else {
                    token.push(character);
                    started = true;
                }
            }
        }
    }
    if quote.is_some() {
        return None;
    }
    if started {
        tokens.push(token);
    }
    Some(tokens)
}

fn contains_path_escape(token: &str) -> bool {
    let normalized = token.replace('\\', "/");
    let absolute_windows_drive = normalized.len() >= 3
        && normalized.as_bytes()[0].is_ascii_alphabetic()
        && normalized.as_bytes()[1] == b':'
        && normalized.as_bytes()[2] == b'/';
    normalized.starts_with('/')
        || normalized.starts_with("~/")
        || absolute_windows_drive
        || normalized.split('/').any(|segment| segment == "..")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn allows(command: &str) {
        assert!(rejection_reason(command).is_none(), "expected allowed: {command}");
    }

    fn rejects(command: &str) {
        assert!(rejection_reason(command).is_some(), "expected rejected: {command}");
    }

    // -- ported: ShellCommandPolicyTest --

    #[test]
    fn allows_bounded_inspection_commands() {
        allows("ls -la");
        allows("grep -n 'TODO' src/Main.kt");
        allows("Get-Content 'notes with spaces.txt'");
        allows("find . -type f");
    }

    #[test]
    fn rejects_shell_composition_and_redirection() {
        rejects("cat notes.txt > copy.txt");
        rejects("cat notes.txt; rm notes.txt");
        rejects("cat $(pwd)/notes.txt");
        rejects("cat notes.txt | head");
    }

    #[test]
    fn rejects_interpreters_write_commands_and_path_escapes() {
        rejects("python -c 'open(\"x\", \"w\")'");
        rejects("find . -delete");
        rejects("sed -i s/old/new/ notes.txt");
        rejects("cat ../private.txt");
        rejects("cat /etc/passwd");
    }

    #[test]
    fn rejects_commands_that_write_through_output_options() {
        rejects("sort -o result.txt input.txt");
        rejects("uniq --output=result.txt input.txt");
    }

    #[test]
    fn rejects_malformed_quoting() {
        rejects("cat 'notes.txt");
        rejects("");
    }

    // -- additional behavior guards --

    #[test]
    fn rejects_whitespace_only_and_multiline() {
        rejects("   ");
        rejects("ls\n-la");
    }

    #[test]
    fn rejects_unknown_and_backslash_paths() {
        rejects("rm -rf src");
        rejects("dir 'C:\\temp'");
    }

    #[test]
    fn find_ok_predicate_is_allowed() {
        allows("find . -maxdepth=2 -type f");
    }
}
