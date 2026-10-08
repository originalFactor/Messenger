//! syntect spans → ratatui `Span`s.
//!
//! `messenger-highlight` reports byte ranges plus a packed `0xAARRGGBB`
//! colour for the whole source; a terminal renderer needs per-line spans, so
//! the source is split into lines and each highlight run is clipped at the
//! line boundaries.

use ratatui::style::{Color, Modifier, Style};
use ratatui::text::Span;

/// One packed color (`0xAARRGGBB`) as a ratatui color; the alpha byte is
/// ignored because terminals have no alpha.
pub fn color_of(packed: u32) -> Color {
    Color::Rgb(
        ((packed >> 16) & 0xFF) as u8,
        ((packed >> 8) & 0xFF) as u8,
        (packed & 0xFF) as u8,
    )
}

/// Parse `messenger_highlight::highlight_code_json` into `(start, end,
/// color)` byte ranges; any failure yields an empty list (the crate's own
/// `"[]"` degradation contract).
pub fn highlight_json(code: &str, language: &str, dark: bool) -> Vec<(usize, usize, u32)> {
    let json = messenger_highlight::highlight_code_json(code, language, dark);
    let parsed: Vec<messenger_highlight::HighlightSpan> =
        serde_json::from_str(&json).unwrap_or_default();
    parsed
        .into_iter()
        .filter(|span| span.start < span.end)
        .map(|span| (span.start, span.end, span.color))
        .collect()
}

/// Split highlighted source into per-line span lists.
///
/// `line_start` is the byte offset of `line` within the full source; each
/// highlight run is intersected with `[line_start, line_start + line.len())`
/// so styles never bleed across lines.
pub fn spans_for_line(
    spans: &[(usize, usize, u32)],
    line_start: usize,
    line: &str,
) -> Vec<Span<'static>> {
    let line_end = line_start + line.len();
    let mut result: Vec<Span<'static>> = Vec::new();
    let mut cursor = line_start;

    for (start, end, color) in spans {
        let (start, end) = (*start, *end);
        if end <= line_start || start >= line_end {
            continue;
        }
        let clipped_start = start.max(line_start);
        let clipped_end = end.min(line_end);
        if clipped_start > cursor {
            // Unstyled gap between highlight runs.
            if let Some(text) = slice(line, cursor, clipped_start, line_start) {
                result.push(Span::raw(text.to_string()));
            }
        }
        if let Some(text) = slice(line, clipped_start, clipped_end, line_start) {
            result.push(Span::styled(
                text.to_string(),
                Style::default().fg(color_of(*color)),
            ));
        }
        cursor = clipped_end;
    }
    if cursor < line_end {
        if let Some(text) = slice(line, cursor, line_end, line_start) {
            result.push(Span::raw(text.to_string()));
        }
    }
    if result.is_empty() {
        // No highlight for this line (or an empty line) — keep plain text so
        // layout code never has to special-case an empty span list.
        return vec![Span::raw(line.to_string())];
    }
    result
}

/// Byte-range slice relative to a line, guarded against non-char boundaries.
fn slice<'a>(line: &'a str, start: usize, end: usize, line_start: usize) -> Option<&'a str> {
    let from = start.saturating_sub(line_start);
    let to = end.saturating_sub(line_start).min(line.len());
    if from >= to || !line.is_char_boundary(from) || !line.is_char_boundary(to) {
        return None;
    }
    Some(&line[from..to])
}

/// Highlight every line of a code block at once.
pub fn highlighted_lines(code: &str, language: &str, dark: bool) -> Vec<Vec<Span<'static>>> {
    let spans = highlight_json(code, language, dark);
    let mut result = Vec::new();
    let mut offset = 0usize;
    for line in code.split('\n') {
        result.push(spans_for_line(&spans, offset, line));
        offset += line.len() + 1; // + the '\n' that `split` consumed
    }
    result
}

/// A dim style used for chrome (rules, hints, collapsed summaries).
pub fn chrome_style() -> Style {
    Style::default()
        .fg(Color::DarkGray)
        .add_modifier(Modifier::DIM)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ratatui::style::Color;

    #[test]
    fn color_maps_argb_to_rgb() {
        assert_eq!(color_of(0xFF12_3456), Color::Rgb(0x12, 0x34, 0x56));
    }

    #[test]
    fn spans_are_clipped_at_line_boundaries() {
        // Source "ab\ncd": one span over the first three bytes.
        let spans = vec![(0usize, 3usize, 0xFF11_2233u32)];
        let first = spans_for_line(&spans, 0, "ab");
        assert_eq!(first.len(), 1);
        assert_eq!(first[0].content, "ab");

        // Second line starts at byte 3; the span must not leak into it.
        let second = spans_for_line(&spans, 3, "cd");
        assert_eq!(second.len(), 1);
        assert_eq!(second[0].content, "cd");
        assert_eq!(second[0].style.fg, None);
    }

    #[test]
    fn splits_a_line_into_styled_and_plain_runs() {
        let spans = vec![(2usize, 4usize, 0xFFFF_0000u32)];
        let line = spans_for_line(&spans, 0, "abcdef");
        let text: String = line.iter().map(|s| s.content.as_ref()).collect();
        assert_eq!(text, "abcdef");
        assert_eq!(line.len(), 3);
        assert_eq!(line[1].content, "cd");
        assert_eq!(line[1].style.fg, Some(Color::Rgb(255, 0, 0)));
    }

    #[test]
    fn unhighlighted_lines_stay_plain() {
        let line = spans_for_line(&[], 0, "plain");
        assert_eq!(line.len(), 1);
        assert_eq!(line[0].content, "plain");
    }

    #[test]
    fn highlighted_lines_cover_the_whole_source() {
        let lines = highlighted_lines("fn main() {\n    let x = 1;\n}\n", "rust", true);
        assert_eq!(lines.len(), 4);
        let joined: String = lines
            .iter()
            .map(|spans| spans.iter().map(|s| s.content.as_ref()).collect::<String>())
            .collect::<Vec<_>>()
            .join("\n");
        assert_eq!(joined, "fn main() {\n    let x = 1;\n}\n");
    }

    #[test]
    fn unknown_language_degrades_to_plain_text() {
        let lines = highlighted_lines("just text", "not-a-language", false);
        assert_eq!(lines.len(), 1);
        let text: String = lines[0].iter().map(|s| s.content.as_ref()).collect();
        assert_eq!(text, "just text");
    }
}
