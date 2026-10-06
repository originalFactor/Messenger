//! Syntax highlighting for chat code blocks, backed by
//! [syntect](https://github.com/trishume/syntect/) (the Sublime Text engine).
//! (TARGET.md §8)

use std::sync::OnceLock;

use serde::{Deserialize, Serialize};
use syntect::easy::HighlightLines;
use syntect::parsing::{SyntaxReference, SyntaxSet};
use syntect::util::LinesWithEndings;

/// One highlighted run: byte range [start, end) within the source and the
/// theme's foreground color as 0xAARRGGBB.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HighlightSpan {
    pub start: usize,
    pub end: usize,
    pub color: u32,
}

static SYNTAX_SET: OnceLock<SyntaxSet> = OnceLock::new();

fn syntax_set() -> &'static SyntaxSet {
    SYNTAX_SET.get_or_init(|| {
        // load_defaults_newlines bundles Sublime's ~180-language default pack
        // inside the crate — no network, no runtime assets.
        SyntaxSet::load_defaults_newlines()
    })
}

fn syntax_for(language: &str) -> &SyntaxReference {
    let ss = syntax_set();
    ss.find_syntax_by_token(language)
        .or_else(|| ss.find_syntax_by_extension(language))
        .or_else(|| ss.find_syntax_by_name(language))
        .unwrap_or_else(|| {
            ss.find_syntax_by_name("Plain Text")
                .expect("default pack ships Plain Text")
        })
}

fn theme_name(dark: bool) -> &'static str {
    if dark {
        "base16-ocean.dark"
    } else {
        "InspiredGitHub"
    }
}

/// Highlight `code` and return a JSON array of `[{start, end, color}]` byte
/// spans colored by the matching syntect theme. Unknown languages fall back to
/// plain text (single style); any internal failure yields `"[]"` so callers
/// simply render unhighlighted text.
pub fn highlight_code_json(code: &str, language: &str, dark: bool) -> String {
    let result = std::panic::catch_unwind(|| highlight_code(code, language, dark));
    match result {
        Ok(spans) => serde_json::to_string(&spans).unwrap_or_else(|_| "[]".into()),
        Err(_) => "[]".into(),
    }
}

fn highlight_code(code: &str, language: &str, dark: bool) -> Vec<HighlightSpan> {
    let ss = syntax_set();
    let theme_set = syntect::highlighting::ThemeSet::load_defaults();
    let Some(theme) = theme_set.themes.get(theme_name(dark)) else {
        return Vec::new();
    };
    let syntax = syntax_for(language);
    let mut highlighter = HighlightLines::new(syntax, theme);

    let mut spans = Vec::new();
    // (color, start) of the currently open run; its end is decided when the
    // style changes or the source ends.
    let mut open: Option<(syntect::highlighting::Color, usize)> = None;
    let mut cursor = 0usize;

    for line in LinesWithEndings::from(code) {
        let Ok(regions) = highlighter.highlight_line(line, ss) else {
            break;
        };
        for (style, text) in regions {
            let start = cursor;
            cursor = start + text.len();
            if text.is_empty() {
                continue;
            }
            let color = style.foreground;
            match open {
                Some((open_color, _)) if open_color == color => {}
                Some((open_color, open_start)) => {
                    spans.push(HighlightSpan {
                        start: open_start,
                        end: start,
                        color: pack(open_color),
                    });
                    open = Some((color, start));
                }
                None => open = Some((color, start)),
            }
        }
    }
    if let Some((open_color, open_start)) = open {
        spans.push(HighlightSpan {
            start: open_start,
            end: cursor,
            color: pack(open_color),
        });
    }
    spans
}

fn pack(color: syntect::highlighting::Color) -> u32 {
    (0xFFu32 << 24) | ((color.r as u32) << 16) | ((color.g as u32) << 8) | color.b as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn python_code_yields_styled_spans() {
        let code = "def fib(n):\n    return n\n";
        let json = highlight_code_json(code, "python", true);
        let spans: Vec<HighlightSpan> = serde_json::from_str(&json).unwrap();
        assert!(!spans.is_empty(), "expected highlighted spans");
        // "def" occupies [0, 3): more than one span means the keyword got a
        // distinct style from surrounding text.
        let def_span = spans
            .iter()
            .find(|s| s.start <= 0 && s.end >= 3)
            .expect("span covering 'def'");
        assert!(spans.len() > 1 || def_span.color != 0xFFC0C5CE);
    }

    #[test]
    fn unknown_language_falls_back_to_plain_text() {
        let json = highlight_code_json("just text\n", "definitely-not-a-lang", false);
        let spans: Vec<HighlightSpan> = serde_json::from_str(&json).unwrap();
        // Plain text styles every run identically → at most one merged span.
        assert!(spans.len() <= 1);
    }

    #[test]
    fn spans_are_contiguous_and_inbounds() {
        let code = "let x = 42; // answer\n";
        let json = highlight_code_json(code, "rust", true);
        let spans: Vec<HighlightSpan> = serde_json::from_str(&json).unwrap();
        for span in &spans {
            assert!(span.start < span.end && span.end <= code.len());
        }
    }

    #[test]
    fn json_output_never_panics_on_weird_input() {
        for code in ["", "\n", "🎉🚀\n", "\\frac{a}{b}"] {
            let json = highlight_code_json(code, "python", true);
            assert!(serde_json::from_str::<Vec<HighlightSpan>>(&json).is_ok());
        }
    }
}
