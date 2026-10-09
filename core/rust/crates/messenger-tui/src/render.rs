//! Document AST → terminal lines.
//!
//! Every `Block`/`Inline` variant gets a terminal mapping: paragraphs wrap
//! (CJK-aware via `unicode-width`), headings are bold/underlined and prefixed
//! with their `#` level, lists rebuild their markers (including GFM task
//! checkboxes), quotes get a `│` bar, tables draw a header rule, code blocks
//! are bordered and syntax-highlighted, think blocks collapse to a one-line
//! summary, tool calls become status-colored cards, math shows its source,
//! and dividers are a full-width rule.

use messenger_document::{Block, BlockStatus, Inline, ListItem};
use messenger_llm::domain::ContentPart;
use messenger_markdown::StreamingSession;
use messenger_store::model::StoredMessage;
use messenger_tools::BuiltinTool;
use crate::text::{Color, Line, Modifier, Span, Style};
use unicode_width::UnicodeWidthStr;

use crate::highlight;

/// Rendering switches (`settings.toml` mirrors).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RenderOpts {
    pub dark: bool,
    pub show_think: bool,
    pub show_tool_details: bool,
}

impl Default for RenderOpts {
    fn default() -> Self {
        Self {
            dark: true,
            show_think: false,
            show_tool_details: false,
        }
    }
}

const ACCENT: Color = Color::Cyan;
const BORDER: Color = Color::DarkGray;

// ---------------------------------------------------------------------------
// entry points
// ---------------------------------------------------------------------------

/// Render a block list at `width` cells.
pub fn blocks_to_lines(blocks: &[Block], width: u16, opts: &RenderOpts) -> Vec<Line<'static>> {
    let width = width.max(8) as usize;
    let mut lines: Vec<Line<'static>> = Vec::new();
    for block in blocks {
        if !lines.is_empty() {
            lines.push(Line::default());
        }
        lines.extend(block_lines(block, width, opts));
    }
    lines
}

/// Render one stored message (text parts through the markdown parser, tool
/// parts as cards).
pub fn message_lines(msg: &StoredMessage, width: u16, opts: &RenderOpts) -> Vec<Line<'static>> {
    let parts = messenger_core::parts::decode_parts(msg.parts_json.as_deref());
    if msg.role == "user" {
        return wrap_plain(&msg.content, width.max(8) as usize, Style::default());
    }
    round_lines(&parts, width, opts)
}

/// Render an assistant round: its text runs through the parser, its tool
/// calls (and results) become cards.
pub fn round_lines(parts: &[ContentPart], width: u16, opts: &RenderOpts) -> Vec<Line<'static>> {
    let width = width.max(8) as usize;
    let mut lines: Vec<Line<'static>> = Vec::new();
    for part in parts {
        match part {
            ContentPart::Text { text } => {
                if text.trim().is_empty() {
                    continue;
                }
                let blocks = parse_blocks(text);
                if !lines.is_empty() {
                    lines.push(Line::default());
                }
                lines.extend(blocks_to_lines(&blocks, width as u16, opts));
            }
            ContentPart::ToolCall {
                call_id,
                name,
                arguments,
            } => {
                if !lines.is_empty() {
                    lines.push(Line::default());
                }
                lines.extend(tool_card(
                    name,
                    arguments,
                    None,
                    false,
                    BlockStatus::Finalized,
                    width,
                    opts,
                ));
                let _ = call_id;
            }
            ContentPart::ToolResult {
                name,
                output,
                is_error,
                ..
            } => {
                if !lines.is_empty() {
                    lines.push(Line::default());
                }
                lines.extend(tool_result_card(name, output, *is_error, width, opts));
            }
            ContentPart::Image { .. } => {
                if !lines.is_empty() {
                    lines.push(Line::default());
                }
                lines.push(Line::from(Span::styled(
                    "[image attachment]",
                    Style::default().fg(ACCENT),
                )));
            }
        }
    }
    lines
}

/// Render the live streaming document (the parser's own block list).
pub fn diff_batch_lines(session: &StreamingSession, width: u16, opts: &RenderOpts) -> Vec<Line<'static>> {
    // The session's document IS the live block list, so the batch itself
    // needs no replay — the incremental diff only tells the platform which
    // rows to invalidate.
    let blocks: Vec<Block> = session.document().blocks().to_vec();
    blocks_to_lines(&blocks, width, opts)
}

/// Parse markdown into blocks with a throwaway session (historic messages go
/// through the same parser the live stream uses, so they render identically).
pub fn parse_blocks(markdown: &str) -> Vec<Block> {
    let mut session = StreamingSession::new();
    session.feed(markdown);
    session.finish();
    session.document().blocks().to_vec()
}

// ---------------------------------------------------------------------------
// block mapping
// ---------------------------------------------------------------------------

fn block_lines(block: &Block, width: usize, opts: &RenderOpts) -> Vec<Line<'static>> {
    match block {
        Block::Paragraph { inlines, .. } => wrap_inlines(inlines, width, Style::default()),
        Block::Heading { level, text, .. } => {
            let mut style = Style::default().add_modifier(Modifier::BOLD);
            if *level == 1 {
                style = style.add_modifier(Modifier::UNDERLINED);
            }
            if *level >= 4 {
                style = style.fg(Color::Gray);
            }
            let prefix = "#".repeat(*level as usize);
            wrap_plain(text, width, style)
                .into_iter()
                .enumerate()
                .map(|(index, line)| {
                    let mut spans = Vec::new();
                    if index == 0 {
                        spans.push(Span::styled(format!("{prefix} "), style));
                    } else {
                        spans.push(Span::styled(" ".repeat(prefix.len() + 1), style));
                    }
                    spans.extend(line.spans);
                    Line::from(spans)
                })
                .collect()
        }
        Block::List { items, .. } => list_lines(items, width, opts),
        Block::Quote { inlines, .. } => wrap_inlines(inlines, width.saturating_sub(2), Style::default())
            .into_iter()
            .map(|line| {
                let mut spans = vec![Span::styled("│ ", Style::default().fg(BORDER))];
                spans.extend(line.spans);
                Line::from(spans)
            })
            .collect(),
        Block::Table { head, rows, .. } => table_lines(head, rows, width),
        Block::CodeBlock { language, code, .. } => {
            code_block_lines(language.as_deref(), code, width, opts)
        }
        Block::Think { content, .. } => think_lines(content, width, opts),
        Block::ToolCall {
            name,
            arguments,
            output,
            is_error,
            status,
            ..
        } => tool_card(name, arguments, output.as_deref(), *is_error, *status, width, opts),
        Block::Math { formula, .. } => bordered_box(
            "math",
            vec![Line::from(Span::styled(
                formula.clone(),
                Style::default().fg(ACCENT),
            ))],
            width,
            BORDER,
        ),
        Block::Divider { .. } => vec![Line::from(Span::styled(
            "─".repeat(width),
            Style::default().fg(BORDER),
        ))],
    }
}

fn list_lines(items: &[ListItem], width: usize, _opts: &RenderOpts) -> Vec<Line<'static>> {
    let mut lines = Vec::new();
    for item in items {
        let indent = "  ".repeat(item.indent as usize);
        let marker = match item.task {
            Some(true) => "[x]".to_string(),
            Some(false) => "[ ]".to_string(),
            None if item.ordered => format!("{}.", item.number),
            None => "•".to_string(),
        };
        let marker_column = marker.len() + 1;
        let body = wrap_inlines(
            &item.inlines,
            width.saturating_sub(indent.len() + marker_column).max(8),
            Style::default(),
        );
        for (index, line) in body.into_iter().enumerate() {
            let mut spans = vec![Span::raw(indent.clone())];
            if index == 0 {
                let style = if item.task.is_some() {
                    Style::default().fg(Color::Green).add_modifier(Modifier::BOLD)
                } else {
                    Style::default().fg(ACCENT)
                };
                spans.push(Span::styled(marker.clone(), style));
                spans.push(Span::raw(" "));
            } else {
                spans.push(Span::raw(" ".repeat(marker_column)));
            }
            spans.extend(line.spans);
            lines.push(Line::from(spans));
        }
    }
    lines
}

fn table_lines(head: &[String], rows: &[Vec<String>], width: usize) -> Vec<Line<'static>> {
    let columns = head.len().max(1);
    let mut widths = vec![0usize; columns];
    for (index, cell) in head.iter().enumerate() {
        widths[index] = widths[index].max(cell.width());
    }
    for row in rows {
        for (index, cell) in row.iter().enumerate() {
            if index < columns {
                widths[index] = widths[index].max(cell.width());
            }
        }
    }
    // Fit the natural table into the available width; overflow is truncated
    // column by column from the right.
    let padding = 3 * columns + 1;
    let natural: usize = widths.iter().sum::<usize>() + padding;
    if natural > width {
        let mut excess = natural - width;
        for index in (0..columns).rev() {
            if excess == 0 {
                break;
            }
            let shrinkable = widths[index].saturating_sub(3);
            let shrink = shrinkable.min(excess);
            widths[index] -= shrink;
            excess -= shrink;
        }
    }

    let rule = |left: &str, mid: &str, right: &str| {
        let mut line = String::from(left);
        for (index, width) in widths.iter().enumerate() {
            line.push_str(&"─".repeat(width + 2));
            line.push_str(if index + 1 == columns { right } else { mid });
        }
        Line::from(Span::styled(line, Style::default().fg(BORDER)))
    };

    let mut lines = vec![rule("┌", "┬", "┐")];
    lines.push(table_row(head, &widths, true));
    lines.push(rule("├", "┼", "┤"));
    for row in rows {
        lines.push(table_row(row, &widths, false));
    }
    lines.push(rule("└", "┴", "┘"));
    lines
}

fn table_row(cells: &[String], widths: &[usize], header: bool) -> Line<'static> {
    let mut spans = vec![Span::styled("│", Style::default().fg(BORDER))];
    for (index, width) in widths.iter().enumerate() {
        let cell = cells.get(index).map(String::as_str).unwrap_or("");
        let clipped = clip_cells(cell, *width);
        let padded = format!(" {clipped}{} ", " ".repeat(width.saturating_sub(clipped.width())));
        let style = if header {
            Style::default().add_modifier(Modifier::BOLD)
        } else {
            Style::default()
        };
        spans.push(Span::styled(padded, style));
        spans.push(Span::styled("│", Style::default().fg(BORDER)));
    }
    Line::from(spans)
}

/// Truncate a cell to `width` display columns with an ellipsis.
fn clip_cells(text: &str, width: usize) -> String {
    if text.width() <= width {
        return text.to_string();
    }
    let mut out = String::new();
    let mut used = 0usize;
    for ch in text.chars() {
        let ch_width = ch.to_string().width();
        if used + ch_width > width.saturating_sub(1) {
            break;
        }
        out.push(ch);
        used += ch_width;
    }
    out.push('…');
    out
}

fn code_block_lines(
    language: Option<&str>,
    code: &str,
    width: usize,
    opts: &RenderOpts,
) -> Vec<Line<'static>> {
    let title = language.unwrap_or("");
    let code = code.strip_suffix('\n').unwrap_or(code);
    let highlighted = highlight::highlighted_lines(code, title, opts.dark);
    let body: Vec<Line<'static>> = highlighted
        .into_iter()
        .map(|spans| Line::from(spans.into_iter().collect::<Vec<_>>()))
        .collect();
    bordered_box(title, body, width, BORDER)
}

fn think_lines(content: &str, width: usize, opts: &RenderOpts) -> Vec<Line<'static>> {
    if !opts.show_think {
        let count = content.lines().count();
        return vec![Line::from(Span::styled(
            format!("… think ({count} lines)"),
            highlight::chrome_style(),
        ))];
    }
    let body: Vec<Line<'static>> = content
        .lines()
        .map(|line| {
            Line::from(Span::styled(
                line.to_string(),
                Style::default()
                    .fg(Color::Gray)
                    .add_modifier(Modifier::ITALIC),
            ))
        })
        .collect();
    bordered_box("think", body, width, BORDER)
}

fn tool_card(
    name: &str,
    arguments: &str,
    output: Option<&str>,
    is_error: bool,
    status: BlockStatus,
    width: usize,
    opts: &RenderOpts,
) -> Vec<Line<'static>> {
    let border = match status {
        BlockStatus::Streaming => Color::Yellow,
        BlockStatus::Finalized if is_error => Color::Red,
        BlockStatus::Finalized => Color::Green,
    };
    let mut body: Vec<Line<'static>> = Vec::new();
    let summary = if name == BuiltinTool::TERMINAL_NAME {
        BuiltinTool::parse_command(arguments).unwrap_or_else(|| arguments.to_string())
    } else {
        arguments.to_string()
    };
    let first_line = summary.lines().next().unwrap_or("").to_string();
    let clipped = clip_cells(&first_line, width.saturating_sub(6));
    body.push(Line::from(Span::styled(clipped, Style::default().fg(Color::Yellow))));
    if opts.show_tool_details {
        if summary.lines().count() > 1 || summary != first_line {
            for line in summary.lines().skip(1) {
                body.push(Line::from(Span::styled(
                    clip_cells(line, width.saturating_sub(6)),
                    Style::default().fg(Color::Gray),
                )));
            }
        }
        if let Some(output) = output {
            let truncated = BuiltinTool::truncate_output(output, BuiltinTool::MAX_OUTPUT_CHARS);
            for line in truncated.lines() {
                let style = if is_error {
                    Style::default().fg(Color::Red)
                } else {
                    Style::default().fg(Color::Gray)
                };
                body.push(Line::from(Span::styled(
                    clip_cells(line, width.saturating_sub(6)),
                    style,
                )));
            }
        }
    } else if output.is_some() {
        let marker = if is_error { "✕ failed" } else { "✓ done" };
        let style = if is_error {
            Style::default().fg(Color::Red)
        } else {
            highlight::chrome_style()
        };
        body.push(Line::from(Span::styled(marker.to_string(), style)));
    } else if status == BlockStatus::Streaming {
        body.push(Line::from(Span::styled(
            "running…".to_string(),
            highlight::chrome_style(),
        )));
    }
    bordered_box(&format!("⚙ {name}"), body, width, border)
}

/// A standalone TOOL row (orphan or paired) rendered as a result card.
fn tool_result_card(
    name: &str,
    output: &str,
    is_error: bool,
    width: usize,
    opts: &RenderOpts,
) -> Vec<Line<'static>> {
    let border = if is_error { Color::Red } else { Color::Green };
    let mut body = Vec::new();
    if opts.show_tool_details {
        let truncated = BuiltinTool::truncate_output(output, BuiltinTool::MAX_OUTPUT_CHARS);
        for line in truncated.lines() {
            body.push(Line::from(Span::styled(
                clip_cells(line, width.saturating_sub(6)),
                Style::default().fg(Color::Gray),
            )));
        }
    } else {
        let first = output.lines().next().unwrap_or("").to_string();
        let style = if is_error {
            Style::default().fg(Color::Red)
        } else {
            highlight::chrome_style()
        };
        body.push(Line::from(Span::styled(
            clip_cells(&first, width.saturating_sub(6)),
            style,
        )));
    }
    bordered_box(&format!("⚙ {name} result"), body, width, border)
}

/// Draw a rounded single-border box around `body`, padding/clipping each line
/// to the inner width.
fn bordered_box(
    title: &str,
    body: Vec<Line<'static>>,
    width: usize,
    border: Color,
) -> Vec<Line<'static>> {
    let width = width.max(6);
    let inner = width - 4;
    let style = Style::default().fg(border);
    let title_span = if title.is_empty() {
        Vec::new()
    } else {
        let clipped = clip_cells(title, inner.saturating_sub(3));
        vec![
            Span::styled(format!(" {clipped} "), style.add_modifier(Modifier::BOLD)),
        ]
    };

    let mut head: Vec<Span<'static>> = vec![Span::styled("╭─", style)];
    let title_width: usize = title_span.iter().map(|s| s.content.width()).sum();
    head.extend(title_span);
    head.extend(vec![Span::styled(
        "─".repeat(inner.saturating_sub(title_width) + 1) + "╮",
        style,
    )]);
    let mut lines = vec![Line::from(head)];

    for line in body {
        let mut spans = vec![Span::styled("│ ", style)];
        let mut used = 0usize;
        for span in line.spans {
            for ch in span.content.chars() {
                let ch_width = ch.to_string().width();
                if used + ch_width > inner {
                    break;
                }
                spans.push(Span::styled(ch.to_string(), span.style));
                used += ch_width;
            }
            if used >= inner {
                break;
            }
        }
        spans.push(Span::raw(" ".repeat(inner.saturating_sub(used))));
        spans.push(Span::styled(" │", style));
        lines.push(Line::from(spans));
    }
    lines.push(Line::from(Span::styled(
        format!("╰{}╯", "─".repeat(inner + 2)),
        style,
    )));
    lines
}

// ---------------------------------------------------------------------------
// inline + wrapping
// ---------------------------------------------------------------------------

fn wrap_inlines(inlines: &[Inline], width: usize, base_style: Style) -> Vec<Line<'static>> {
    let mut spans: Vec<Span<'static>> = Vec::new();
    for inline in inlines {
        spans.extend(inline_spans(inline, base_style));
    }
    wrap_spans(spans, width)
}

fn inline_spans(inline: &Inline, base: Style) -> Vec<Span<'static>> {
    match inline {
        Inline::Text { text } => vec![Span::styled(text.clone(), base)],
        Inline::Bold { text } => vec![Span::styled(text.clone(), base.add_modifier(Modifier::BOLD))],
        Inline::Italic { text } => {
            vec![Span::styled(text.clone(), base.add_modifier(Modifier::ITALIC))]
        }
        Inline::Strikethrough { text } => vec![Span::styled(
            text.clone(),
            base.add_modifier(Modifier::CROSSED_OUT),
        )],
        Inline::Code { code } => vec![Span::styled(
            code.clone(),
            base.fg(Color::Yellow).add_modifier(Modifier::DIM),
        )],
        Inline::Math { formula } => vec![Span::styled(
            format!("${formula}$"),
            base.fg(ACCENT).add_modifier(Modifier::ITALIC),
        )],
        Inline::Link { text, url } => {
            let mut spans = vec![Span::styled(
                text.clone(),
                base.fg(Color::Blue).add_modifier(Modifier::UNDERLINED),
            )];
            if url != text && !url.is_empty() {
                spans.push(Span::styled(
                    format!(" ({url})"),
                    base.fg(Color::Blue),
                ));
            }
            spans
        }
    }
}

/// Wrap a plain string to `width` display cells.
fn wrap_plain(text: &str, width: usize, style: Style) -> Vec<Line<'static>> {
    wrap_spans(vec![Span::styled(text.to_string(), style)], width)
}

/// Greedy word wrap over spans, splitting on whitespace and hard-breaking
/// words that exceed the width. Width is measured with `unicode-width`, so a
/// CJK paragraph at width 20 never exceeds 20 cells.
fn wrap_spans(spans: Vec<Span<'static>>, width: usize) -> Vec<Line<'static>> {
    let width = width.max(1);
    let mut lines: Vec<Line<'static>> = Vec::new();
    let mut current: Vec<Span<'static>> = Vec::new();
    let mut used = 0usize;

    let push_current = |lines: &mut Vec<Line<'static>>, current: &mut Vec<Span<'static>>| {
        lines.push(Line::from(std::mem::take(current)));
    };

    for span in spans {
        let style = span.style;
        // Keep explicit newlines: a paragraph's hard breaks must survive.
        for (chunk_index, chunk) in span.content.split('\n').enumerate() {
            if chunk_index > 0 {
                push_current(&mut lines, &mut current);
                used = 0;
            }
            for token in tokenize(chunk) {
                let token_width = token.width();
                let is_space = token.chars().all(char::is_whitespace);
                if used + token_width > width && used > 0 {
                    push_current(&mut lines, &mut current);
                    used = 0;
                    if is_space {
                        continue; // drop the space that caused the break
                    }
                }
                if token_width > width {
                    // Hard-break an over-long token (a long URL, CJK run…).
                    for ch in token.chars() {
                        let ch_width = ch.to_string().width();
                        if used + ch_width > width && used > 0 {
                            push_current(&mut lines, &mut current);
                            used = 0;
                        }
                        current.push(Span::styled(ch.to_string(), style));
                        used += ch_width;
                    }
                    continue;
                }
                current.push(Span::styled(token, style));
                used += token_width;
            }
        }
    }
    if !current.is_empty() || lines.is_empty() {
        lines.push(Line::from(current));
    }
    lines
}

/// Split into whitespace runs and word runs, keeping the whitespace so
/// inter-word spacing survives when the line does not wrap.
fn tokenize(text: &str) -> Vec<String> {
    let mut tokens = Vec::new();
    let mut current = String::new();
    let mut current_space: Option<bool> = None;
    for ch in text.chars() {
        let is_space = ch.is_whitespace();
        match current_space {
            Some(kind) if kind != is_space => {
                tokens.push(std::mem::take(&mut current));
                current.push(ch);
                current_space = Some(is_space);
            }
            _ => {
                current.push(ch);
                current_space = Some(is_space);
            }
        }
    }
    if !current.is_empty() {
        tokens.push(current);
    }
    tokens
}

#[cfg(test)]
mod tests {
    use super::*;
    use messenger_document::ListItem;

    fn text_of(lines: &[Line<'static>]) -> String {
        lines
            .iter()
            .map(|line| {
                line.spans
                    .iter()
                    .map(|span| span.content.as_ref())
                    .collect::<String>()
            })
            .collect::<Vec<_>>()
            .join("\n")
    }

    fn width_of(width: usize, line: &Line<'static>) -> usize {
        line.spans.iter().map(|s| s.content.width()).sum::<usize>().max(0)
            * usize::from(width > 0)
    }

    const SAMPLE: &str = "# Title\n\nHello **world**\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```rust\nfn main() {}\n```\n\n- [x] done\n\n> quote\n\n$$x^2$$\n";

    #[test]
    fn every_block_variant_reaches_the_terminal() {
        let blocks = parse_blocks(SAMPLE);
        let lines = blocks_to_lines(&blocks, 60, &RenderOpts::default());
        let text = text_of(&lines);
        assert!(text.contains("# Title"), "{text}");
        assert!(text.contains("world"), "{text}");
        assert!(text.contains("a") && text.contains("1"), "{text}");
        assert!(text.contains("fn main()"), "{text}");
        assert!(text.contains("[x] done"), "{text}");
        assert!(text.contains("│ quote"), "{text}");
        assert!(text.contains("math"), "{text}");
        assert!(text.contains("╭"), "{text}");
        assert!(text.contains("─"), "{text}");
    }

    #[test]
    fn bold_inline_carries_the_bold_modifier() {
        let blocks = parse_blocks("Hello **world**\n");
        let lines = blocks_to_lines(&blocks, 60, &RenderOpts::default());
        let bold = lines
            .iter()
            .flat_map(|line| line.spans.iter())
            .find(|span| span.content.as_ref() == "world")
            .expect("bold span");
        assert!(bold.style.add_modifier.contains(Modifier::BOLD));
    }

    #[test]
    fn table_has_a_rule_between_header_and_body() {
        let blocks = parse_blocks("| a | b |\n|---|---|\n| 1 | 2 |\n");
        let lines = blocks_to_lines(&blocks, 60, &RenderOpts::default());
        let text = text_of(&lines);
        assert!(text.contains("┌"), "{text}");
        assert!(text.contains("├"), "{text}");
        assert!(text.contains("└"), "{text}");
        assert!(text.contains("│ a │ b │"), "{text}");
    }

    #[test]
    fn cjk_paragraph_never_exceeds_the_requested_width() {
        let blocks = parse_blocks("这是一段很长的中文段落用来验证换行宽度是否正确处理。\n");
        let width = 20u16;
        let lines = blocks_to_lines(&blocks, width, &RenderOpts::default());
        for line in &lines {
            let text: String = line.spans.iter().map(|s| s.content.as_ref()).collect();
            assert!(
                text.width() <= width as usize,
                "line {:?} is {} cells wide",
                text,
                text.width()
            );
        }
        let _ = width_of;
    }

    #[test]
    fn think_collapses_and_expands() {
        let blocks = parse_blocks("<think>secret reasoning</think>\n\nvisible\n");
        let collapsed = blocks_to_lines(&blocks, 60, &RenderOpts::default());
        let collapsed_text = text_of(&collapsed);
        assert!(collapsed_text.contains("think ("), "{collapsed_text}");
        assert!(!collapsed_text.contains("secret reasoning"), "{collapsed_text}");
        assert!(collapsed_text.contains("visible"));

        let expanded = blocks_to_lines(
            &blocks,
            60,
            &RenderOpts {
                show_think: true,
                ..RenderOpts::default()
            },
        );
        assert!(text_of(&expanded).contains("secret reasoning"));
    }

    #[test]
    fn tool_call_card_shows_the_command_and_its_status() {
        let lines = tool_card(
            "terminal",
            r#"{"command":"echo hi"}"#,
            Some("hi\n"),
            false,
            BlockStatus::Finalized,
            60,
            &RenderOpts::default(),
        );
        let text = text_of(&lines);
        assert!(text.contains("⚙ terminal"), "{text}");
        assert!(text.contains("echo hi"), "{text}");
        assert!(text.contains("✓ done"), "{text}");
    }

    #[test]
    fn tool_call_details_expand_with_the_option() {
        let lines = tool_card(
            "terminal",
            r#"{"command":"echo hi"}"#,
            Some("hi\nthere"),
            false,
            BlockStatus::Finalized,
            60,
            &RenderOpts {
                show_tool_details: true,
                ..RenderOpts::default()
            },
        );
        let text = text_of(&lines);
        assert!(text.contains("there"), "{text}");
    }

    #[test]
    fn failed_tool_cards_are_red_and_marked() {
        let lines = tool_card(
            "terminal",
            r#"{"command":"boom"}"#,
            Some("error"),
            true,
            BlockStatus::Finalized,
            60,
            &RenderOpts::default(),
        );
        let text = text_of(&lines);
        assert!(text.contains("✕ failed"), "{text}");
    }

    #[test]
    fn lists_rebuild_markers_ordinals_and_checkboxes() {
        let items = vec![
            ListItem {
                indent: 0,
                ordered: false,
                number: 0,
                task: None,
                inlines: vec![Inline::Text { text: "bullet".into() }],
            },
            ListItem {
                indent: 1,
                ordered: true,
                number: 2,
                task: None,
                inlines: vec![Inline::Text { text: "nested".into() }],
            },
            ListItem {
                indent: 0,
                ordered: false,
                number: 0,
                task: Some(false),
                inlines: vec![Inline::Text { text: "open".into() }],
            },
        ];
        let lines = list_lines(&items, 60, &RenderOpts::default());
        let text = text_of(&lines);
        assert!(text.contains("• bullet"), "{text}");
        assert!(text.contains("  2. nested"), "{text}");
        assert!(text.contains("[ ] open"), "{text}");
    }

    #[test]
    fn links_append_a_differing_url() {
        let inlines = vec![Inline::Link {
            text: "site".into(),
            url: "https://example.com".into(),
        }];
        let text = text_of(&wrap_inlines(&inlines, 80, Style::default()));
        assert_eq!(text, "site (https://example.com)");
    }

    #[test]
    fn message_lines_decodes_parts_for_every_role() {
        let user = StoredMessage {
            id: "m1".into(),
            conversation_id: "c1".into(),
            role: "user".into(),
            content: "hello".into(),
            parts_json: None,
            timestamp: 1,
            status: "sent".into(),
            error_message: None,
        };
        assert_eq!(text_of(&message_lines(&user, 40, &RenderOpts::default())), "hello");

        let parts = vec![
            ContentPart::Text {
                text: "answer".into(),
            },
            ContentPart::ToolCall {
                call_id: "c".into(),
                name: "terminal".into(),
                arguments: r#"{"command":"ls"}"#.into(),
            },
        ];
        let text = text_of(&round_lines(&parts, 40, &RenderOpts::default()));
        assert!(text.contains("answer"), "{text}");
        assert!(text.contains("⚙ terminal"), "{text}");
    }

    #[test]
    fn streaming_session_lines_track_the_live_document() {
        let mut session = StreamingSession::new();
        session.feed("# Heading\n\n");
        let lines = diff_batch_lines(&session, 40, &RenderOpts::default());
        assert!(text_of(&lines).contains("# Heading"));
        session.feed("body text\n");
        let lines = diff_batch_lines(&session, 40, &RenderOpts::default());
        assert!(text_of(&lines).contains("body text"));
    }

    #[test]
    fn long_words_hard_break_instead_of_overflowing() {
        let lines = wrap_plain(&"x".repeat(50), 10, Style::default());
        for line in &lines {
            let text: String = line.spans.iter().map(|s| s.content.as_ref()).collect();
            assert!(text.width() <= 10, "{text}");
        }
        assert!(lines.len() >= 5);
    }

    #[test]
    fn bare_newlines_split_paragraph_lines() {
        let lines = wrap_plain("one\ntwo", 40, Style::default());
        assert_eq!(lines.len(), 2);
    }
}
