//! Think-block utilities (port of `presentation/utils/ThinkBlockUtils.kt`).
//! Pure helpers shared by request building and title sanitization.

/// Remove closed think blocks plus a trailing unclosed block and trim.
/// Accepts both `<think>` and `<thinking>` spellings, case-insensitively.
pub fn strip_think_block(content: &str) -> String {
    static CLOSED: std::sync::LazyLock<regex::Regex> = std::sync::LazyLock::new(|| {
        regex::Regex::new(r"(?i)<think(?:ing)?(?:\s[^>]*)?>[\s\S]*?</think(?:ing)?>").unwrap()
    });
    static UNCLOSED_TAIL: std::sync::LazyLock<regex::Regex> = std::sync::LazyLock::new(|| {
        regex::Regex::new(r"(?i)<think(?:ing)?(?:\s[^>]*)?>[\s\S]*$").unwrap()
    });
    UNCLOSED_TAIL
        .replace_all(&CLOSED.replace_all(content, ""), "")
        .trim()
        .to_string()
}

/// Split a leading think block off the content: `(reasoning, remaining body)`.
/// Only a block at the very start counts; case-sensitive, matching Kotlin.
pub fn extract_think_content(content: &str) -> (Option<String>, String) {
    static LEADING: std::sync::LazyLock<regex::Regex> =
        std::sync::LazyLock::new(|| regex::Regex::new(r"^<think(?:ing)?>([\s\S]*?)</think(?:ing)?>\s*").unwrap());
    match LEADING.captures(content) {
        Some(caps) => {
            let reasoning = caps.get(1).map(|m| m.as_str().to_string());
            let remaining = content[caps.get(0).unwrap().end()..]
                .trim_start()
                .to_string();
            (reasoning, remaining)
        }
        None => (None, content.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips_closed_and_unclosed_blocks() {
        assert_eq!(strip_think_block("<think>a</think>body"), "body");
        assert_eq!(strip_think_block("pre<think>a</think>post"), "prepost");
        assert_eq!(strip_think_block("<think>partial"), "");
        assert_eq!(strip_think_block("<THINKING>x</THINKING>y"), "y");
        assert_eq!(strip_think_block("clean"), "clean");
    }

    #[test]
    fn extracts_leading_block_only() {
        let (reasoning, rest) = extract_think_content("<think>why</think> answer");
        assert_eq!(reasoning.as_deref(), Some("why"));
        assert_eq!(rest, "answer");
        let (none, rest) = extract_think_content("mid<think>x</think>");
        assert!(none.is_none());
        assert_eq!(rest, "mid<think>x</think>");
    }
}
