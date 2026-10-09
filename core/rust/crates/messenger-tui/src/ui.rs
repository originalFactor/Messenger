/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//! Frame composition: the whole pi-shaped surface, assembled as plain lines.
//!
//! There is one surface and no view enum — the frame is always the chat, and
//! a popup is composited into it. [`compose`] returns the frame plus the cell
//! the hardware cursor belongs in; [`crate::screen`] turns that into escape
//! sequences. Nothing here touches the terminal, so the headless tests can
//! assert on a frame directly.
//!
//! Layout, top to bottom:
//! ```text
//! │ you │ refactor the parser                     transcript — scrolls
//! │ agent │ done — see the diff above
//! ╭─ commands ────────────────────────────────╮   the `/` palette, when open
//! │▸ /model    switch the bound model           │   (it belongs to the editor)
//! ╰─ message ─────────────────────────────────╯   editor, grows with input
//! │ › ask, or / for commands                    │
//!  conv · project · agent · read-only            context line
//!  ready  model · status              1.2k · me  footer
//! ```
//!
//! The **chrome is pinned to the bottom** and the transcript fills everything
//! above it. That split is what makes native scrollback work: the transcript
//! is append-only, so its oldest rows are simply the ones that scroll off the
//! top of the terminal, and the user reaches them with the mouse wheel. A
//! header pinned to row 0 would instead scroll away on its own.
//!
//! Because of that, [`compose`] never returns more rows than the viewport: it
//! clips the transcript and reports how many rows it dropped in
//! [`Frame::scrolled`], and the screen performs exactly that scroll. The
//! transcript's own scroll position ([`ChatState::scroll`](crate::app::ChatState::scroll))
//! only chooses *which* rows of the transcript are in view — it never moves
//! the terminal.
//!
//! Two details exist purely because a terminal has no widget tree:
//!
//! * **A box owns its rows.** The editor's border rows are part of its
//!   height, never drawn over its content — the editor grows upward by
//!   pushing the transcript, not by eating a content row.
//! * **The palette is drawn above the editor**, not centred: it belongs to
//!   the editor, and the editor is where the user's attention already is.

use unicode_width::UnicodeWidthStr;

use crate::app::{command_help_rows, App, NoteKind};
use crate::popup::{command_rows, Field, Popup};
use crate::text::{Color, Line, Modifier, Span, Style};

/// Rows the editor's text may grow to before it starts scrolling.
const MAX_EDITOR_ROWS: usize = 8;
/// The smallest editor that is still a box: a border, one row of text, a
/// border. Below that it would draw a broken frame.
const MIN_EDITOR_ROWS: usize = 3;
/// Rows a select or palette shows before it needs its own window.
const MAX_VISIBLE_ROWS: usize = 14;
/// Width of a bordered box's vertical borders.
const BORDER: usize = 2;
/// Shown while the editor is empty — it tells the user what the box is for
/// instead of leaving an empty rectangle.
const PLACEHOLDER: &str = "› ask, or / for commands";
/// The prompt drawn at the head of a non-empty message.
const PROMPT: &str = "› ";

const ACCENT: Color = Color::Indexed(45);
const CHROME: Color = Color::DarkGray;
const RULE: Color = Color::Indexed(238);

/// One painted frame.
pub struct Frame {
    /// Exactly the rows to paint, viewport-height tall.
    pub lines: Vec<Line<'static>>,
    /// Where the hardware cursor belongs, in frame coordinates.
    pub cursor: Option<(u16, u16)>,
    /// Transcript rows that scrolled off the top. [`crate::screen`] issues
    /// the matching scroll so history lands in the terminal's scrollback.
    pub scrolled: u32,
}

/// Compose the whole frame.
///
/// The result is always `height` rows, so the editor, context line, and
/// footer stay at the bottom of the terminal no matter how short the
/// transcript is; the gap in between is blank.
pub fn compose(app: &mut App, width: u16, height: u16) -> Frame {
    let width = width.max(8) as usize;
    let height = height.max(4) as usize;

    let palette_open = matches!(app.popups.last(), Some(Popup::Commands { .. }));
    let palette_want = if palette_open {
        palette_body(app).len() + 2
    } else {
        0
    };
    // Space is allocated from the bottom up. When the terminal is too short
    // for all of it the priority is: the editor keeps a whole box (a border,
    // a row of text, a border), then the footer and context line, then the
    // palette, and the transcript gives way. The prompt must never be pushed
    // off the screen — it is the only way to type at all.
    let editor_min = MIN_EDITOR_ROWS.min(height);
    let palette_rows = palette_want.min(height.saturating_sub(editor_min + 2));
    let editor_rows = editor_height(app)
        .clamp(editor_min, height.saturating_sub(palette_rows + 2).max(editor_min))
        .min(height.saturating_sub(palette_rows));

    // The chrome is built FIRST so the transcript gets whatever rows are
    // genuinely left. Budgeting arithmetically and then drawing to that
    // budget is how a box ends up a row taller than the space it was given.
    let mut chrome: Vec<Line<'static>> = Vec::new();
    if palette_open {
        chrome.extend(palette_box(app, width, palette_rows));
    }
    let palette_actual = chrome.len();
    let (editor, caret) = editor_box(app, width, editor_rows);
    chrome.extend(editor);
    // The editor and the footer outrank the context line: on a terminal with
    // no room for all three, the context line is dropped rather than
    // squeezing the prompt.
    if chrome.len() + 2 <= height {
        chrome.push(context_line(app, width));
    }
    chrome.push(footer_line(app, width));

    let transcript_rows = height.saturating_sub(chrome.len());
    let transcript = transcript_lines(app, width);
    let visible = transcript_rows.min(transcript.len());
    // Show the tail by default; `scroll` walks back from it.
    let back = if app.chat.follow {
        0
    } else {
        (app.chat.scroll as usize).min(transcript.len().saturating_sub(visible))
    };
    let start = transcript.len() - back - visible;
    let dropped = start as u32;

    let mut lines: Vec<Line<'static>> = Vec::with_capacity(height);
    lines.extend(transcript[start..start + visible].iter().cloned());
    // A short transcript leaves the gap blank so the chrome keeps its place;
    // those rows are what scroll away first.
    lines.resize_with(lines.len() + (transcript_rows - visible), Line::default);
    lines.extend(chrome);

    // A modal covers the middle of the frame and takes the caret with it.
    if let Some(modal) = overlay(app, width, height) {
        composite(&mut lines, modal, width);
    }
    // The caret is box-relative; the terminal wants a frame row.
    let editor_start = transcript_rows + palette_actual;
    let cursor = if app.popups.is_empty() {
        Some((
            caret.0.min(width.saturating_sub(1) as u16),
            (editor_start + caret.1 as usize).min(lines.len().saturating_sub(1)) as u16,
        ))
    } else {
        None
    };
    debug_assert_eq!(lines.len(), height, "frame must fill the viewport");
    Frame {
        lines,
        cursor,
        scrolled: dropped,
    }
}

// ---------------------------------------------------------------------------
// transcript
// ---------------------------------------------------------------------------

/// The messages, the live stream, the session notes, and the last error.
///
/// Each block carries its speaker in a gutter rather than a bubble, so the
/// transcript needs no border of its own.
fn transcript_lines(app: &mut App, width: usize) -> Vec<Line<'static>> {
    let inner_width = width as u16;
    let mut lines: Vec<Line<'static>> = Vec::new();
    for index in 0..app.chat.messages.len() {
        let message_lines = app.rendered_message(index, inner_width);
        if !lines.is_empty() {
            lines.push(Line::default());
        }
        let role = app.chat.messages[index].role.clone();
        lines.extend(prefix_role(&role, message_lines));
    }
    if let Some(live) = app.live_lines(inner_width) {
        if !lines.is_empty() {
            lines.push(Line::default());
        }
        lines.extend(prefix_role("assistant", live));
    } else if app.chat.is_generating {
        lines.push(Line::from(Span::styled(
            "agent is thinking…",
            Style::default().fg(CHROME).add_modifier(Modifier::ITALIC),
        )));
    }
    for note in &app.chat.notes.clone() {
        if !lines.is_empty() {
            lines.push(Line::default());
        }
        lines.extend(prefix_role(
            "note",
            vec![Line::from(Span::styled(
                note.text.clone(),
                Style::default().fg(match note.kind {
                    NoteKind::Info => CHROME,
                    NoteKind::Warn => Color::Yellow,
                    NoteKind::Error => Color::Red,
                }),
            ))],
        ));
    }
    if let Some(error) = app.chat.error.clone() {
        if !lines.is_empty() {
            lines.push(Line::default());
        }
        lines.push(Line::from(Span::styled(
            format!("⚠ {error}"),
            Style::default().fg(Color::Red),
        )));
    }
    lines
}

/// Label each block with its role so the speaker stays readable in a terminal
/// that has no bubbles.
fn prefix_role(role: &str, lines: Vec<Line<'static>>) -> Vec<Line<'static>> {
    let (label, color) = match role {
        "user" => ("you", ACCENT),
        "assistant" => ("agent", Color::Green),
        "tool" => ("tool", Color::Yellow),
        "note" => ("note", CHROME),
        other => (other, CHROME),
    };
    lines
        .into_iter()
        .map(|line| {
            let mut spans = vec![Span::styled(
                format!("{label:>5} │ "),
                Style::default().fg(color),
            )];
            spans.extend(line.spans);
            Line::from(spans)
        })
        .collect()
}

// ---------------------------------------------------------------------------
// editor
// ---------------------------------------------------------------------------

/// Rows the editor box needs: two border rows plus its text, capped.
fn editor_height(app: &App) -> usize {
    editor_text(app).lines().count().clamp(1, MAX_EDITOR_ROWS) + 2
}

/// The text the editor shows. While the `/` palette is open it owns the line,
/// so the editor shows the command buffer — the two are never edited at once.
fn editor_text(app: &App) -> String {
    if app.chat.palette_open {
        match app.popups.last() {
            Some(Popup::Commands { buffer, .. }) => format!("/{buffer}"),
            _ => String::new(),
        }
    } else {
        app.chat.input.clone()
    }
}

/// The editor's box plus the caret's cell inside it.
fn editor_box(app: &App, width: usize, height: usize) -> (Vec<Line<'static>>, (u16, u16)) {
    let empty = !app.chat.palette_open && app.chat.input.is_empty();
    // An empty box tells the user nothing, so the prompt doubles as a
    // placeholder. It carries no caret: the inverse block is reserved for the
    // character the user will actually be typing over.
    let shown = if empty {
        PLACEHOLDER.to_string()
    } else {
        editor_text(app)
    };
    let style = if empty {
        Style::default().fg(CHROME)
    } else if app.chat.palette_open {
        Style::default().fg(ACCENT)
    } else {
        Style::default().fg(Color::White)
    };
    let title = if app.chat.palette_open {
        " command "
    } else {
        " message "
    };
    let border = if app.chat.palette_open {
        ACCENT
    } else {
        RULE
    };

    // With a single-line prompt the caret is ALSO drawn as an inverse block:
    // terminals hide the hardware caret while the app is busy streaming, and
    // the user needs a focus marker then. A multi-line prompt is left to the
    // hardware caret alone.
    let (caret_line, caret_column) = caret_position(&shown);
    let body_rows = height.saturating_sub(2);
    let draw_fake_caret = !empty && !shown.contains('\n');
    let mut body = Vec::with_capacity(body_rows);
    for (index, line) in shown.lines().enumerate() {
        let mut spans = Vec::new();
        if index == 0 && !empty {
            spans.push(Span::styled(PROMPT, Style::default().fg(ACCENT)));
        }
        let caret = draw_fake_caret
            .then(|| (index == caret_line).then_some(caret_column))
            .flatten();
        spans.extend(body_spans(line, style, caret));
        body.push(Line::from(spans));
    }

    let lines = box_lines(title, body, width, border, height);
    // With a placeholder on screen the caret belongs at the head of the line,
    // not at the end of the placeholder text.
    let row = 1 + if empty { 0 } else { caret_line.min(body_rows.saturating_sub(1)) };
    let column = BORDER + prefix_width(&shown, caret_line) + if empty { 0 } else { caret_column };
    (lines, (column as u16, row as u16))
}

/// The prefix width (`› `) that precedes the caret's logical line.
fn prefix_width(text: &str, line: usize) -> usize {
    if line == 0 && !text.is_empty() {
        PROMPT.width()
    } else {
        0
    }
}

/// Split one editor row into styled runs, reversing the character under the
/// caret (or appending an inverse space when the caret sits past the last
/// one).
fn body_spans(text: &str, style: Style, caret: Option<usize>) -> Vec<Span<'static>> {
    let width = text.width();
    let reversed = style.add_modifier(Modifier::REVERSED);
    match caret {
        Some(column) if column < width => {
            let mut spans = Vec::new();
            let mut before = 0usize;
            for ch in text.chars() {
                let start = before;
                before += ch.to_string().width();
                // The glyph the caret starts on is drawn reversed in full, so
                // the block is never half a wide character wide.
                let span_style = if start == column { reversed } else { style };
                spans.push(Span::styled(ch.to_string(), span_style));
            }
            spans
        }
        Some(_) => vec![
            Span::styled(text.to_string(), style),
            Span::styled(" ", reversed),
        ],
        None => vec![Span::styled(text.to_string(), style)],
    }
}

/// The caret's logical line and column, in terminal cells.
fn caret_position(text: &str) -> (usize, usize) {
    use unicode_width::UnicodeWidthChar;
    let mut line = 0usize;
    let mut column = 0usize;
    for ch in text.chars() {
        match ch {
            '\n' => {
                line += 1;
                column = 0;
            }
            other => column += usize::from(other.width().unwrap_or(0)),
        }
    }
    (line, column)
}

// ---------------------------------------------------------------------------
// context line + footer
// ---------------------------------------------------------------------------

/// The session's context: what you are talking to, where, and how.
///
/// A project IS a workspace, so its directory is the agent's cwd — it must
/// never be ambiguous. It sits with the footer rather than at the top of the
/// screen so that appending transcript rows does not scroll it away.
fn context_line(app: &mut App, width: usize) -> Line<'static> {
    let title = app
        .chat
        .conversation_id
        .as_deref()
        .and_then(|id| app.engine.store.get_conversation(id).ok().flatten())
        .map(|conversation| conversation.title)
        .unwrap_or_else(|| "(no conversation)".into());
    let project = app.chat_project();
    let agent = crate::store_ops::current_agent(&app.engine.store)
        .ok()
        .flatten()
        .map(|agent| agent.name)
        .unwrap_or_else(|| "-".into());
    let writable = app.conversation_writable();
    let mode_style = if writable {
        Style::default()
            .bg(Color::Red)
            .fg(Color::Black)
            .add_modifier(Modifier::BOLD)
    } else {
        Style::default().bg(RULE).fg(Color::White)
    };
    let mode = Span::styled(
        if writable {
            " writable ".to_string()
        } else {
            " read-only ".to_string()
        },
        mode_style,
    );
    let workspace = project
        .as_ref()
        .map(|project| project.workspace.clone())
        .unwrap_or_default();
    let details = vec![
        Span::styled(
            format!(" {title} "),
            Style::default().add_modifier(Modifier::BOLD),
        ),
        Span::styled(
            match &project {
                Some(project) => format!("  · {} · ", project.name),
                None => "  · no project · ".to_string(),
            },
            Style::default().fg(CHROME),
        ),
        Span::styled(format!("{workspace}  "), Style::default().fg(CHROME)),
        Span::styled(
            format!("agent: {agent}  "),
            Style::default().fg(Color::Gray),
        ),
    ];
    // The badge is pinned and the details degrade: on a narrow terminal the
    // workspace path and Agent name go first. Clipping the mode would hide
    // the one thing that says whether the agent may write at all.
    let room = width.saturating_sub(mode.width());
    let details = clip_spans(details, room);
    let mut spans = details.spans;
    spans.push(mode);
    Line::from(spans)
}

/// The one-line status strip, pi-style: state · model · the last thing that
/// happened · context usage. Right-aligned items hug the edge.
fn footer_line(app: &App, width: usize) -> Line<'static> {
    let spinner = ["⠋", "⠙", "⠹", "⠸"][app.spinner % 4];
    let (state, state_style) = if app.chat.is_generating {
        (
            format!(" {spinner} working "),
            Style::default()
                .bg(Color::Yellow)
                .fg(Color::Black)
                .add_modifier(Modifier::BOLD),
        )
    } else {
        (
            " ready ".to_string(),
            Style::default()
                .bg(RULE)
                .fg(Color::Green)
                .add_modifier(Modifier::BOLD),
        )
    };

    let mut left = vec![
        Span::styled(state, state_style),
        Span::styled(
            format!(
                " {}",
                app.bound_model_label().unwrap_or_else(|| "no model".into())
            ),
            Style::default().fg(Color::Gray),
        ),
    ];
    if !app.status.is_empty() {
        left.push(Span::styled(
            format!("  {}", app.status),
            Style::default().fg(if app.chat.is_generating {
                CHROME
            } else {
                Color::White
            }),
        ));
    }

    // Right side: tokens + cloud account, dropped whole if it would collide
    // with the status text.
    let mut right: Vec<Span<'static>> = Vec::new();
    if let Some(tokens) = &app.last_list_message {
        right.push(Span::styled(
            format!("{tokens}  "),
            Style::default().fg(CHROME),
        ));
    }
    let account = app.cloud_account();
    if !account.is_empty() {
        right.push(Span::styled(account, Style::default().fg(CHROME)));
    }

    let used = spans_width(&left);
    let right_width = spans_width(&right);
    let right = if used + right_width + 2 > width {
        Vec::new()
    } else {
        right
    };
    let right_width = spans_width(&right);
    left.push(Span::raw(" ".repeat(width.saturating_sub(used + right_width))));
    left.extend(right);
    Line::from(left)
}

fn spans_width(spans: &[Span<'static>]) -> usize {
    spans.iter().map(Span::width).sum()
}

/// Keep at most `cells` columns of `spans`, dropping the tail and clipping the
/// last run that straddles the edge.
fn clip_spans(spans: Vec<Span<'static>>, cells: usize) -> Line<'static> {
    let mut kept: Vec<Span<'static>> = Vec::with_capacity(spans.len());
    let mut used = 0usize;
    for span in spans {
        if used >= cells {
            break;
        }
        let clipped = clip_cells(&span.content, cells - used);
        used += clipped.width();
        if !clipped.is_empty() {
            kept.push(Span::styled(clipped, span.style));
        }
    }
    Line::from(kept)
}

// ---------------------------------------------------------------------------
// popups
// ---------------------------------------------------------------------------

/// The `/` palette, boxed and sitting directly above the editor.
fn palette_box(app: &App, width: usize, height: usize) -> Vec<Line<'static>> {
    let body = palette_body(app);
    let hint = Line::from(Span::styled(
        "  ↑/↓ move · Enter run · Tab complete · Esc cancel",
        Style::default().fg(CHROME),
    ));
    let mut body = body;
    body.push(hint);
    box_lines("commands", body, width, ACCENT, height)
}

fn palette_body(app: &App) -> Vec<Line<'static>> {
    match app.popups.last() {
        Some(Popup::Commands { buffer, cursor }) => command_lines(buffer, *cursor),
        _ => Vec::new(),
    }
}

/// Every other popup is a centred modal over the frame, drawn at 70% of the
/// terminal width (pi's default) and padded out to the full row.
fn overlay(app: &mut App, width: usize, height: usize) -> Option<Vec<Line<'static>>> {
    let (lines, border) = match app.popups.last()? {
        Popup::Commands { .. } => return None,
        Popup::Select(select) => (select_lines(select), ACCENT),
        Popup::Form(form) => (form_lines(form), ACCENT),
        Popup::Confirm(confirm) => (confirm_lines(confirm), Color::Yellow),
        Popup::Help { scroll } => (help_lines(app, *scroll), ACCENT),
    };
    let title = app.popups.last()?.title().to_string();
    let modal_width = (width * 70 / 100).max(20).min(width);
    // The modal is bounded by the frame it covers, borders included.
    let height = (lines.len() + 2).min(height);
    Some(box_lines(&title, lines, modal_width, border, height))
}

/// Write `overlay` over the middle rows of `frame`, replacing each row it
/// touches so the modal is never mixed with the content beneath it.
fn composite(frame: &mut [Line<'static>], overlay: Vec<Line<'static>>, width: usize) {
    let height = overlay.len();
    if height == 0 || frame.len() < height {
        return;
    }
    let top = (frame.len() - height) / 2;
    let left = (width.saturating_sub(width * 70 / 100)) / 2;
    for (offset, row) in overlay.into_iter().enumerate() {
        let target = top + offset;
        let used = row.width();
        let mut spans = vec![Span::raw(" ".repeat(left))];
        spans.extend(row.spans);
        spans.push(Span::raw(" ".repeat(width.saturating_sub(left + used))));
        frame[target] = Line::from(spans);
    }
}

// ---------------------------------------------------------------------------
// box drawing
// ---------------------------------------------------------------------------

/// A bordered box around `body`, clipped to `height` rows in total.
///
/// `height` is the whole box, borders included: a caller that budgeted 3 rows
/// gets 3 rows back, not 3 rows plus two borders. Below 2 there is no room
/// for a frame at all, so the content is returned unboxed rather than
/// dropped.
fn box_lines(
    title: &str,
    body: Vec<Line<'static>>,
    width: usize,
    border: Color,
    height: usize,
) -> Vec<Line<'static>> {
    let width = width.max(6);
    let inner = width - BORDER;
    let style = Style::default().fg(border);

    let content_height = height.saturating_sub(2);
    let mut lines: Vec<Line<'static>> = Vec::with_capacity(height);
    if height < 2 {
        return body.into_iter().take(height).collect();
    }
    lines.push(Line::from(vec![
        Span::styled("╭─", style),
        Span::styled(
            format!(" {title} "),
            Style::default().fg(border).add_modifier(Modifier::BOLD),
        ),
        Span::styled(
            // `╭─` already spends two cells and the title carries two spaces
            // of its own, so the dashes cover the rest exactly.
            "─".repeat(inner.saturating_sub(title.width() + 3)) + "╮",
            style,
        ),
    ]));

    for index in 0..content_height {
        let mut spans = vec![Span::styled("│", style)];
        let mut used = 0usize;
        if let Some(line) = body.get(index) {
            for span in &line.spans {
                if used >= inner {
                    break;
                }
                let clipped = clip_cells(&span.content, inner - used);
                if clipped.is_empty() {
                    continue;
                }
                used += clipped.width();
                spans.push(Span::styled(clipped, span.style));
            }
        }
        spans.push(Span::raw(" ".repeat(inner.saturating_sub(used))));
        spans.push(Span::styled("│", style));
        lines.push(Line::from(spans));
    }
    lines.push(Line::from(Span::styled(
        format!("╰{}╯", "─".repeat(inner)),
        style,
    )));
    lines.truncate(height);
    lines
}

/// Truncate `text` to `cells` display columns, never splitting a wide glyph.
fn clip_cells(text: &str, cells: usize) -> String {
    use unicode_width::UnicodeWidthStr;
    if text.width() <= cells {
        return text.to_string();
    }
    let mut out = String::new();
    let mut used = 0usize;
    for ch in text.chars() {
        let width = ch.to_string().width();
        if used + width > cells {
            break;
        }
        out.push(ch);
        used += width;
    }
    out
}

// ---------------------------------------------------------------------------
// popup bodies
// ---------------------------------------------------------------------------

/// Window a list of rows around its cursor.
fn windowed(rows: Vec<Line<'static>>, cursor: usize) -> Vec<Line<'static>> {
    if rows.len() <= MAX_VISIBLE_ROWS {
        return rows;
    }
    let start = cursor
        .saturating_sub(MAX_VISIBLE_ROWS / 2)
        .min(rows.len() - MAX_VISIBLE_ROWS);
    rows.into_iter().skip(start).take(MAX_VISIBLE_ROWS).collect()
}

fn command_lines(buffer: &str, cursor: usize) -> Vec<Line<'static>> {
    let rows = command_rows(buffer);
    let mut lines = vec![Line::from(vec![
        Span::styled("/ ", Style::default().fg(ACCENT)),
        Span::styled(
            buffer.to_string(),
            Style::default().add_modifier(Modifier::BOLD),
        ),
    ])];
    if rows.is_empty() {
        lines.push(Line::from(Span::styled(
            format!("  no command matches /{buffer}"),
            Style::default().fg(CHROME),
        )));
        return lines;
    }
    lines.extend(windowed(
        rows.iter()
            .enumerate()
            .map(|(index, (label, summary))| {
                let (marker, style) = if index == cursor {
                    ("▸ ", Style::default().add_modifier(Modifier::BOLD))
                } else {
                    ("  ", Style::default())
                };
                Line::from(vec![
                    Span::styled(format!("{marker}{label:<24}"), style.fg(Color::Cyan)),
                    Span::styled(summary.clone(), Style::default().fg(Color::Gray)),
                ])
            })
            .collect(),
        cursor,
    ));
    lines
}

fn select_lines(select: &crate::popup::Select) -> Vec<Line<'static>> {
    if select.items.is_empty() {
        return vec![Line::from(Span::styled(
            "  (nothing to pick)",
            Style::default().fg(CHROME),
        ))];
    }
    let rows = windowed(
        select
            .items
            .iter()
            .enumerate()
            .map(|(index, item)| {
                let active = index == select.cursor;
                let marker = if active { "▸ " } else { "  " };
                let check = if item.selected { "●" } else { " " };
                let style = if active {
                    Style::default().fg(Color::Black).bg(ACCENT)
                } else {
                    Style::default()
                };
                Line::from(vec![
                    Span::styled(format!("{marker}{check} "), style),
                    Span::styled(item.label.clone(), style.add_modifier(Modifier::BOLD)),
                    Span::styled(
                        if item.detail.is_empty() {
                            String::new()
                        } else {
                            format!("  {}", item.detail)
                        },
                        if active {
                            style
                        } else {
                            Style::default().fg(CHROME)
                        },
                    ),
                ])
            })
            .collect(),
        select.cursor,
    );
    let mut lines = rows;
    lines.push(Line::from(Span::styled(
        "  Enter pick · e edit · n new · d delete · Esc cancel",
        Style::default().fg(CHROME),
    )));
    lines
}

fn form_lines(form: &crate::popup::Form) -> Vec<Line<'static>> {
    let mut lines: Vec<Line<'static>> = Vec::new();
    for (index, field) in form.fields.iter().enumerate() {
        let focused = index == form.focus;
        let label_style = if focused {
            Style::default().fg(ACCENT).add_modifier(Modifier::BOLD)
        } else {
            Style::default().fg(Color::Gray)
        };
        lines.push(Line::from(vec![
            Span::styled(
                format!("{} ", if focused { "▸" } else { " " }),
                label_style,
            ),
            Span::styled(field.label().to_string(), label_style),
        ]));
        let value = match field {
            Field::Text {
                value,
                secret,
                multiline,
                ..
            } => {
                let shown = if *secret {
                    "•".repeat(value.chars().count())
                } else {
                    value.clone()
                };
                if *multiline {
                    shown.replace('\n', "⏎ ")
                } else {
                    shown
                }
            }
            Field::Bool { value, .. } => {
                if *value {
                    "[x]".to_string()
                } else {
                    "[ ]".to_string()
                }
            }
            Field::Choice {
                options, selected, ..
            } => format!(
                "◂ {} ▸",
                options.get(*selected).cloned().unwrap_or_default()
            ),
        };
        let value_style = if focused {
            Style::default().bg(RULE).fg(Color::White)
        } else {
            Style::default().fg(Color::White)
        };
        lines.push(Line::from(Span::styled(
            format!("   {value}"),
            value_style,
        )));
    }
    lines.push(Line::raw(""));
    lines.push(Line::from(Span::styled(
        "Tab next · space toggle · Enter submit/advance · Ctrl+S submit · Esc cancel",
        Style::default().fg(CHROME),
    )));
    lines
}

fn confirm_lines(confirm: &crate::popup::Confirm) -> Vec<Line<'static>> {
    let mut lines = vec![Line::raw(confirm.message.clone()), Line::raw("")];
    if let Some(expected) = &confirm.requires_typing {
        lines.push(Line::from(vec![
            Span::raw("Type "),
            Span::styled(
                expected.clone(),
                Style::default().add_modifier(Modifier::BOLD),
            ),
            Span::raw(" to confirm: "),
            Span::styled(confirm.typed.clone(), Style::default().fg(Color::Yellow)),
        ]));
    }
    lines.push(Line::from(Span::styled(
        "Enter confirm · Esc cancel",
        Style::default().fg(CHROME),
    )));
    lines
}

fn help_lines(app: &App, scroll: usize) -> Vec<Line<'static>> {
    let mut lines = vec![
        Line::from(Span::styled(
            "Messenger — a session opens the project for this directory",
            Style::default().add_modifier(Modifier::BOLD),
        )),
        Line::raw(""),
        Line::from(Span::styled("Chat", Style::default().fg(ACCENT))),
        Line::raw("  Enter            send"),
        Line::raw("  Alt+Enter        newline"),
        Line::raw("  /                command palette (Tab completes)"),
        Line::raw("  Esc              cancel the running turn"),
        Line::raw("  ?                this help (only on an empty input)"),
        Line::raw("  Ctrl+T / Ctrl+O  think blocks / tool card bodies"),
        Line::raw("  ↑↓ PgUp PgDn     scroll · Home/End top/bottom"),
        Line::raw("  Ctrl+C           quit (restores the terminal)"),
        Line::raw(""),
        Line::from(Span::styled("Popups", Style::default().fg(ACCENT))),
        Line::raw("  ↑/↓ or j/k       move the cursor"),
        Line::raw("  Enter            pick / submit / confirm"),
        Line::raw("  e · n · d        edit · new · delete the selected row"),
        Line::raw("  Tab              next field (form) · complete (palette)"),
        Line::raw("  Esc              close one layer"),
        Line::raw(""),
    ];
    lines.push(Line::from(Span::styled(
        format!(
            "Store {}   Config {}",
            app.store_path.display(),
            app.config_path.display()
        ),
        Style::default().fg(CHROME),
    )));
    lines.push(Line::from(Span::styled(
        format!(
            "Account {}   Workspace {}",
            app.cloud_account(),
            app.chat_project()
                .map(|project| project.workspace)
                .unwrap_or_else(|| app.config.workspace_dir.clone())
        ),
        Style::default().fg(CHROME),
    )));
    lines.push(Line::from(Span::styled(
        "Math renders as source — a terminal cannot stack fractions.",
        Style::default().fg(CHROME),
    )));
    lines.push(Line::raw(""));
    lines.push(Line::from(Span::styled("Commands", Style::default().fg(ACCENT))));
    for (label, summary) in command_help_rows() {
        lines.push(Line::from(vec![
            Span::styled(format!("  {label:<26}"), Style::default().fg(Color::Cyan)),
            Span::styled(summary, Style::default().fg(Color::Gray)),
        ]));
    }
    lines.push(Line::raw(""));
    lines.push(Line::from(Span::styled(
        "↑/↓ PgUp/PgDn scroll · Esc or any other key closes",
        Style::default().fg(CHROME),
    )));
    // The help is taller than a short terminal. `scroll` is a top-edge offset
    // (a whole row per step) so paging always reaches the last command, which
    // `windowed` cannot guarantee for a cursor that stops at the end.
    lines.into_iter().skip(scroll).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// An app with no runtime attached — the editor and frame paths never
    /// spawn anything, so rendering must not need a reactor.
    fn app() -> App {
        App::new(
            std::sync::Arc::new(crate::engine::Engine::headless(
                messenger_store::Store::open_memory().unwrap(),
            )),
            crate::config::TuiConfig::default(),
            "config.toml".into(),
            "store.db".into(),
        )
    }

    #[test]
    fn box_lines_return_exactly_the_budgeted_rows() {
        let body = vec![Line::raw("a"), Line::raw("b")];
        let boxed = box_lines("t", body, 20, CHROME, 6);
        assert_eq!(boxed.len(), 6, "the budget is the WHOLE box, borders included");
        assert!(boxed[0].to_plain_string().starts_with('╭'));
        assert!(boxed[5].to_plain_string().starts_with('╰'));
    }

    #[test]
    fn box_lines_never_exceed_their_budget() {
        for height in 0..12 {
            let boxed = box_lines("t", vec![Line::raw("a"), Line::raw("b")], 20, CHROME, height);
            assert!(
                boxed.len() <= height,
                "height {height} produced {} rows",
                boxed.len()
            );
        }
    }

    #[test]
    fn box_lines_pad_a_short_body_to_the_budget() {
        let boxed = box_lines("t", vec![Line::raw("a")], 20, CHROME, 5);
        assert_eq!(boxed.len(), 5);
        // A row past the body is a border, padding, border.
        assert_eq!(boxed[3].to_plain_string(), format!("│{}│", " ".repeat(18)));
    }

    #[test]
    fn box_lines_clip_an_overlong_body_to_the_inner_width() {
        let boxed = box_lines("t", vec![Line::raw("x".repeat(50))], 20, CHROME, 3);
        let row = boxed[1].to_plain_string();
        assert_eq!(row.chars().count(), 20, "{row}");
        assert!(row.starts_with('│') && row.ends_with('│'));
    }

    #[test]
    fn box_rows_are_exactly_the_terminal_width() {
        let boxed = box_lines("message", vec![Line::raw("hi")], 30, CHROME, 3);
        for line in boxed {
            assert_eq!(line.width(), 30, "{:?}", line.to_plain_string());
        }
    }

    #[test]
    fn a_single_line_editor_shows_its_text_and_a_caret_block() {
        let mut app = app();
        app.chat.input = "hi".into();
        let (lines, cursor) = editor_box(&app, 20, 3);
        assert_eq!(lines.len(), 3);
        let body = lines[1].to_plain_string();
        assert!(body.contains("hi"), "{body}");
        let has_block = lines[1]
            .spans
            .iter()
            .any(|span| span.style.is_reversed());
        assert!(has_block, "the caret must be drawn: {body}");
        // Two borders + the `› ` prompt + two cells of text.
        assert_eq!(cursor, (2 + 2 + 2, 1));
    }

    #[test]
    fn an_empty_editor_shows_the_placeholder_without_a_caret_block() {
        let app = app();
        let (lines, _) = editor_box(&app, 40, 3);
        assert!(lines[1].to_plain_string().contains(PLACEHOLDER));
        assert!(!lines[1]
            .spans
            .iter()
            .any(|span| span.style.is_reversed()));
    }

    #[test]
    fn a_multiline_editor_grows_and_moves_the_caret_down() {
        let mut app = app();
        app.chat.input = "one\ntwo".into();
        assert_eq!(editor_height(&app), 4);
        let (lines, cursor) = editor_box(&app, 40, 4);
        assert_eq!(lines.len(), 4);
        assert!(lines[1].to_plain_string().contains("one"));
        assert!(lines[2].to_plain_string().contains("two"));
        assert_eq!(cursor.1, 2, "the caret follows the second line");
    }

    #[test]
    fn the_editor_caps_its_growth() {
        let mut app = app();
        app.chat.input = (0..40).map(|i| format!("{i}\n")).collect::<String>();
        assert_eq!(editor_height(&app), MAX_EDITOR_ROWS + 2);
    }

    #[test]
    fn caret_position_clamps_to_the_end_of_the_input() {
        assert_eq!(caret_position(""), (0, 0));
        assert_eq!(caret_position("abc"), (0, 3));
        assert_eq!(caret_position("a\nbc"), (1, 2));
    }

    #[test]
    fn windowed_keeps_short_lists_whole() {
        let rows: Vec<Line<'static>> = (0..3).map(|i| Line::raw(i.to_string())).collect();
        assert_eq!(windowed(rows, 0).len(), 3);
    }

    #[test]
    fn windowed_centres_a_long_list_on_its_cursor() {
        let rows: Vec<Line<'static>> = (0..40).map(|i| Line::raw(i.to_string())).collect();
        let window = windowed(rows, 20);
        assert_eq!(window.len(), MAX_VISIBLE_ROWS);
        assert_eq!(window[0].to_plain_string(), "13");
    }

    #[test]
    fn composite_replaces_the_rows_it_covers() {
        let mut frame = vec![Line::raw("top"), Line::raw("mid"), Line::raw("bot")];
        composite(&mut frame, vec![Line::raw("XX")], 10);
        assert_eq!(frame[0].to_plain_string(), "top");
        assert!(frame[1].to_plain_string().contains("XX"), "{}", frame[1].to_plain_string());
        assert!(!frame[1].to_plain_string().contains("mid"));
        assert_eq!(frame[2].to_plain_string(), "bot");
        assert_eq!(frame[1].width(), 10);
    }

    /// The frame must be exactly the viewport height at EVERY size — a taller
    /// frame pushes the prompt off a small terminal, a shorter one lets stale
    /// rows linger. A 20-row terminal with the palette open is the case that
    /// actually broke: the chrome budgeted 1 row for a box that needs 2.
    #[test]
    fn the_frame_is_exactly_the_viewport_height_at_every_size() {
        for height in 4u16..30 {
            for width in [20u16, 40, 100] {
                for palette in [false, true] {
                    let mut app = app();
                    if palette {
                        app.chat.palette_open = true;
                        app.push(crate::popup::Popup::Commands {
                            buffer: "model".into(),
                            cursor: 0,
                        });
                    }
                    let frame = compose(&mut app, width, height);
                    assert_eq!(
                        frame.lines.len(),
                        height as usize,
                        "frame must fill {width}x{height} (palette: {palette})"
                    );
                    for line in &frame.lines {
                        assert!(
                            line.width() <= width as usize,
                            "row too wide at {width}: {:?}",
                            line.to_plain_string()
                        );
                    }
                }
            }
        }
    }

    /// The editor box has to sit at the BOTTOM of the frame — that is what
    /// makes appending transcript rows scroll history into the terminal's
    /// scrollback instead of pushing the prompt away.
    #[test]
    fn the_editor_and_footer_are_pinned_to_the_bottom() {
        let mut app = app();
        let frame = compose(&mut app, 60, 20);
        let text: Vec<String> = frame
            .lines
            .iter()
            .map(|line| line.to_plain_string())
            .collect();
        let editor = text.iter().position(|row| row.contains("message")).unwrap();
        // Three editor rows (top border, content, bottom border), then the
        // context line and the footer.
        assert_eq!(editor, 20 - 3 - 2, "editor sits above the context+footer");
        assert!(text[editor].starts_with('╭'), "{:?}", text[editor]);
        assert!(text[17].starts_with('╰'), "editor bottom border: {:?}", text[17]);
        assert!(text[19].contains("ready") || text[19].contains("working"), "{:?}", text[19]);
    }

    /// A short transcript is padded with blank rows so the chrome keeps its
    /// place. Those rows are the ones that will scroll away first.
    #[test]
    fn a_short_transcript_leaves_the_chrome_at_the_bottom() {
        let mut app = app();
        let frame = compose(&mut app, 60, 20);
        let blank = frame
            .lines
            .iter()
            .filter(|line| line.to_plain_string().trim().is_empty())
            .count();
        assert!(blank >= 15, "an empty transcript pads the gap: {blank} blank rows");
    }

    /// The caret must address the editor's content row, and only then: a
    /// popup owns the keyboard, so the terminal parks the caret at the bottom.
    #[test]
    fn the_caret_sits_in_the_editor_and_yields_to_a_popup() {
        let mut app = app();
        app.chat.input = "abc".into();
        let frame = compose(&mut app, 60, 20);
        let (column, row) = frame.cursor.expect("the chat owns the caret");
        let editor_row = frame.lines[row as usize].to_plain_string();
        assert!(editor_row.contains("abc"), "caret row: {editor_row:?}");
        assert!(editor_row.starts_with('│'), "caret row: {editor_row:?}");
        let _ = column;

        app.push(crate::popup::Popup::Help { scroll: 0 });
        assert_eq!(
            compose(&mut app, 60, 20).cursor,
            None,
            "a modal owns the caret"
        );
    }

    /// The frame must never be wider than the terminal, or the renderer
    /// wraps a row and every column below it shifts. A long palette buffer
    /// overflows most easily: it is measured in cells, not characters, and
    /// the box cannot grow.
    #[test]
    fn no_frame_row_exceeds_the_terminal_width() {
        let mut app = app();
        app.chat.palette_open = true;
        app.push(crate::popup::Popup::Commands {
            buffer: "x".repeat(400),
            cursor: 0,
        });
        for width in [20u16, 30, 60, 100] {
            let frame = compose(&mut app, width, 24);
            for (index, line) in frame.lines.iter().enumerate() {
                assert!(
                    line.width() <= width as usize,
                    "row {index} is {} cells at width {width}: {:?}",
                    line.width(),
                    line.to_plain_string()
                );
            }
        }
    }

    /// The editor and the palette are hand-laid boxes: every row they emit
    /// must be exactly the terminal width, borders included, or the terminal
    /// wraps it and the closing border lands on the next row.
    #[test]
    fn hand_built_boxes_are_exactly_the_terminal_wide() {
        let mut app = app();
        app.chat.palette_open = true;
        app.push(crate::popup::Popup::Commands {
            buffer: "help".into(),
            cursor: 0,
        });
        for width in [20u16, 45, 100] {
            for line in compose(&mut app, width, 30).lines {
                let text = line.to_plain_string();
                if text.starts_with('╭') || text.starts_with('╰') || text.contains('│') {
                    assert_eq!(
                        line.width(),
                        width as usize,
                        "box row is not {width} wide: {text:?}"
                    );
                }
            }
        }
    }

    /// Growing the transcript must push rows into the scrollback rather than
    /// silently dropping them: `scrolled` is what the screen turns into a
    /// terminal scroll.
    #[test]
    fn an_overlong_transcript_reports_what_scrolled_away() {
        let mut app = app();
        for index in 0..200 {
            app.chat.notes.push(crate::app::ChatNote::info(format!(
                "note {index}"
            )));
        }
        let frame = compose(&mut app, 60, 20);
        assert!(
            frame.scrolled > 0,
            "an overlong transcript must scroll, not truncate silently"
        );
        assert!(
            frame
                .lines
                .iter()
                .any(|line| line.to_plain_string().contains("note 199")),
            "the newest note stays visible"
        );
    }

    /// The palette belongs to the editor: it renders directly above it
    /// instead of floating over the transcript the way a modal does.
    #[test]
    fn the_palette_sits_directly_above_the_editor() {
        let mut app = app();
        app.chat.palette_open = true;
        app.push(crate::popup::Popup::Commands {
            buffer: "model".into(),
            cursor: 0,
        });
        let frame = compose(&mut app, 80, 24);
        let text: Vec<String> = frame
            .lines
            .iter()
            .map(|line| line.to_plain_string())
            .collect();
        let palette = text.iter().position(|row| row.contains("commands")).unwrap();
        let editor = text.iter().position(|row| row.contains("command ")).unwrap();
        assert!(palette < editor, "the palette is above the editor");
        assert!(
            text.iter().any(|row| row.contains("/model")),
            "the buffer's command is listed:\n{text:?}"
        );
    }
}
