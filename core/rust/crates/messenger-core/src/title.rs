//! Conversation title helpers (port of the pure half of
//! `ConversationTitleGenerator.kt`): untitled detection, generated-title
//! sanitization, and the fallback truncation of the first user message.

use messenger_llm::think::strip_think_block;

/// Transcript cap for the title prompt (per message).
pub const TRANSCRIPT_MAX_CHARS: usize = 2000;
/// Generated titles cap at 30 characters before the ellipsis.
pub const TITLE_MAX_LENGTH: usize = 30;

/// Blank / 新对话 / New Chat all count as still-untitled.
pub fn is_untitled_conversation(title: &str) -> bool {
    title.trim().is_empty() || title == "新对话" || title == "New Chat"
}

/// Sanitize a model-generated title: strip think blocks, unwrap wrapping
/// quotes, collapse to the first non-blank line, cap at 30 chars + "...".
pub fn sanitize_generated_title(raw: &str, max_length: usize) -> String {
    let stripped = strip_think_block(raw);
    let unwrapped = stripped
        .trim()
        .trim_matches(|c: char| {
            matches!(c, '"' | '\u{201C}' | '\u{201D}' | '「' | '」' | '『' | '』' | '\'' | '《' | '》')
        })
        .trim();
    let single_line = unwrapped.lines().find(|l| !l.trim().is_empty()).unwrap_or("");
    if single_line.chars().count() <= max_length {
        single_line.to_string()
    } else {
        let head: String = single_line.chars().take(max_length).collect();
        format!("{head}...")
    }
}

/// Fallback title when generation fails: the sanitized first user message.
pub fn fallback_title(first_user_content: &str) -> String {
    sanitize_generated_title(first_user_content, TITLE_MAX_LENGTH)
}

/// Build the two-line transcript the title prompt sees.
pub fn build_title_transcript(first_user: &str, first_assistant: &str) -> String {
    format!(
        "User: {}\nAssistant: {}\n",
        clip(first_user),
        clip(first_assistant)
    )
}

fn clip(text: &str) -> String {
    text.chars().take(TRANSCRIPT_MAX_CHARS).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn untitled_detection() {
        assert!(is_untitled_conversation(""));
        assert!(is_untitled_conversation("   "));
        assert!(is_untitled_conversation("新对话"));
        assert!(is_untitled_conversation("New Chat"));
        assert!(!is_untitled_conversation(" renamed "));
        assert!(!is_untitled_conversation("new chat"));
    }

    #[test]
    fn sanitizes_quotes_and_caps_length() {
        assert_eq!(sanitize_generated_title(r#""我的标题""#, 30), "我的标题");
        assert_eq!(sanitize_generated_title("「标题」", 30), "标题");
        assert_eq!(sanitize_generated_title("<think>x</think>real", 30), "real");
        let long = "x".repeat(50);
        assert_eq!(sanitize_generated_title(&long, 30), format!("{}...", "x".repeat(30)));
        assert_eq!(sanitize_generated_title("line1\nline2", 30), "line1");
    }

    #[test]
    fn fallback_title_truncates_user_message() {
        assert_eq!(fallback_title("hello there"), "hello there");
        assert_eq!(
            fallback_title(&"问".repeat(40)),
            format!("{}...", "问".repeat(30))
        );
    }

    #[test]
    fn transcript_clips_long_messages() {
        let transcript = build_title_transcript(&"u".repeat(3000), &"a".repeat(3000));
        assert!(transcript.contains("User: "));
        assert!(transcript.chars().count() < 4200);
    }
}
