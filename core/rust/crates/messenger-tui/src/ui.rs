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
//! │ > ask, or / for commands                    │
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
use ratatui::layout::Rect;
use ratatui::widgets::{Paragraph, Widget};

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
const PLACEHOLDER: &str = "> ask, or / for commands";
/// The prompt drawn at the head of a non-empty message.
const PROMPT: &str = "> ";

/// The most rows the viewport ever takes: the tallest chrome the client can
/// produce (a full editor, the `/` palette at its own maximum, the context
/// line and the footer).
///
/// [`crate::screen::Screen`] builds its inline viewport once, and rebuilding
/// it scrolls the history we just inserted off the top (see that module's
/// docs). So the viewport is FIXED for the life of the process, and the chrome
/// pads and clips itself to fit rather than the viewport resizing.
const MAX_VIEWPORT_ROWS: u16 = 12;

/// The viewport height for a terminal `screen_rows` tall.
///
/// It is never smaller than the smallest chrome that still shows a prompt: a
/// 4-row terminal gets the 5 rows the chrome needs, because the prompt is the
/// only way to type at all. It is never larger than the screen.
pub fn viewport_rows(screen_rows: u16) -> u16 {
    // The smallest chrome that still shows a prompt: the editor's three rows
    // plus the context line and the footer.
    let smallest = (MIN_EDITOR_ROWS + 2) as u16;
    let wanted = MAX_VIEWPORT_ROWS.max(smallest);
    // The prompt is the only way to type at all, so on a screen too short for
    // even that we still give it the rows — the cost is one row of scrollback.
    wanted.max(smallest).min(screen_rows.max(smallest))
}

/// Paint a composed frame into ratatui's buffer.
///
/// The frame's lines are already laid out and wrapped, so they go in as a
/// `Paragraph` without a wrapping style — ratatui's own layout is used only
/// to place them.
pub fn draw(frame: &mut ratatui::Frame<'_>, lines: &[Line<'static>]) {
    let area = frame.area();
    let height = usize::from(u16::try_from(lines.len()).unwrap_or(area.height).min(area.height));
    for (row, line) in lines.iter().take(height).enumerate() {
        let Ok(y) = u16::try_from(row) else { break };
        Paragraph::new(line.clone()).render(
            Rect {
                x: area.x,
                y: area.y + y,
                width: area.width,
                height: 1,
            },
            frame.buffer_mut(),
        );
    }
}

const ACCENT: Color = Color::Indexed(45);
const CHROME: Color = Color::DarkGray;
const RULE: Color = Color::Indexed(238);

/// One painted frame.
pub struct Frame {
    /// Exactly the rows to paint, viewport-height tall.
    pub lines: Vec<Line<'static>>,
    /// Where the hardware cursor belongs, in frame coordinates.
    pub cursor: Option<(u16, u16)>,
    /// How many leading transcript rows were left out of the viewport this
    /// frame, whether or not they are new. Rows before the history watermark
    /// are already in scrollback and are skipped; the rest are reported in
    /// [`Frame::history`].
    pub scrolled: u32,
    /// Transcript rows that have scrolled out of the viewport and are now
    /// FINAL. [`crate::screen::Screen::flush_history`] writes them into the
    /// terminal's scrollback, which owns them from then on — they are never
    /// drawn again, which is why only settled rows may appear here.
    pub history: Vec<Line<'static>>,
}


/// The bottom chrome: editor (with the `/` palette spliced inside it), the
/// context line, and the footer. Its height is a function of the CONTENT, not
/// of the viewport, so it can pad or clip itself to the fixed viewport (see
/// [`viewport_rows`]) without the transcript silently losing rows.
fn chrome_lines(
    app: &mut App,
    width: usize,
    max_rows: usize,
) -> (Vec<Line<'static>>, (u16, u16)) {
    let width = width.max(8);
    let height = max_rows.max(MIN_EDITOR_ROWS);

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
    // pi draws the `/` palette INSIDE the editor box (below the input, above
    // the bottom border — see its Editor.render autocomplete block), so the
    // two are one component: the palette rows are part of the editor's budget.
    let editor_min = MIN_EDITOR_ROWS.min(height);
    let palette_rows = if palette_open {
        palette_want.min(height.saturating_sub(editor_min + 2))
    } else {
        0
    };
    let editor_rows = editor_height(app, width)
        .clamp(editor_min, height.saturating_sub(palette_rows + 2).max(editor_min))
        .min(height.saturating_sub(palette_rows))
        + if palette_open { palette_rows } else { 0 };
    let editor_rows = editor_rows.min(height.saturating_sub(2).max(MIN_EDITOR_ROWS));

    let mut chrome: Vec<Line<'static>> = Vec::new();
    let (editor, caret) = if palette_open {
        let mut body = editor_box(app, width, editor_rows - palette_rows.max(2));
        // Append the palette rows INSIDE the box, above the bottom border.
        let inner_rows = palette_rows.saturating_sub(2).max(0);
        let mut palette_lines = box_lines(
            " command ",
            palette_body(app)
                .into_iter()
                .take(inner_rows)
                .collect(),
            width,
            ACCENT,
            palette_rows.max(2),
        );
        // Splice: keep the editor's top rows + body, then the palette box
        // without its top border, replacing the editor's bottom border.
        let editor_total = body.0.len();
        let keep = editor_total.saturating_sub(1);
        let mut combined: Vec<Line<'static>> = body.0.drain(..keep).collect();
        combined.extend(palette_lines.drain(1..));
        body.0 = combined;
        (body.0, body.1)
    } else {
        editor_box(app, width, editor_rows)
    };
    chrome.extend(editor);
    // The editor and the footer outrank the context line: on a terminal with
    // no room for all three, the context line is dropped rather than
    // squeezing the prompt.
    if chrome.len() + 2 <= height {
        chrome.push(context_line(app, width));
    }
    chrome.push(footer_line(app, width));

    (chrome, caret)
}

/// Compose the whole frame for a viewport `height` rows tall.
///
/// Transcript rows that no longer fit are reported in [`Frame::history`] rather
/// than dropped: the terminal keeps them in its scrollback, which is where the
/// user reads history from. See [`viewport_height`] for how the caller learns
/// how tall the viewport should be.
pub fn compose(app: &mut App, width: u16, height: u16) -> Frame {
    let mut history: Vec<Line<'static>> = Vec::new();
    let width = width.max(8) as usize;
    let height = (height.max(MIN_EDITOR_ROWS as u16 + 2)) as usize;

    let (chrome, caret) = chrome_lines(app, width, height);
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
    let dropped = start;

    // Rows before `start` have left the viewport. Those already written to
    // scrollback are skipped; the ones past the watermark are new and FINAL (a
    // row that left the viewport cannot still be streaming — the live message
    // is always in the tail), so they go to the terminal's history.
    //
    // The watermark only means anything while the transcript is a stable
    // prefix, which it is while the user reads the tail (`follow`) and the
    // width has not changed. Scrolling BACK (`chat.scroll`) re-shows rows that
    // may already be in scrollback, so the watermark is dropped and the
    // viewport simply redraws them — the terminal's own scrollback stays
    // correct either way.
    if app.chat.follow {
        let watermark = app.history_rows();
        if dropped > watermark {
            history.extend_from_slice(&transcript[watermark..dropped]);
        }
        app.set_history_rows(dropped);
    } else {
        app.reset_history();
    }

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
    // The caret is box-relative; the terminal wants a frame row. The editor
    // starts right after the transcript rows (the palette lives inside it).
    let editor_start = transcript_rows;
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
        scrolled: dropped as u32,
        history,
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

/// Cells available for editor text on any row: the inner width minus the
/// prompt (which rides on the first row) minus one column reserved for the
/// caret — pi's `layoutWidth = contentWidth - 1`, so a caret at the end of a
/// full row still has a cell to sit in instead of landing on the border.
fn editor_text_width(width: usize) -> usize {
    width.saturating_sub(BORDER + PROMPT.width() + 1).max(1)
}

/// Rows the editor box needs: two border rows plus its wrapped text, capped.
/// The height estimate must wrap at a REAL row width: pass the width the box
/// will actually draw at (pi wraps its layout at the same width it renders).
fn editor_height(app: &App, width: usize) -> usize {
    let text = editor_text(app);
    let empty = text.is_empty();
    let rows = if empty {
        1
    } else {
        wrap_editor_text(&text, editor_text_width(width))
            .0
            .len()
            .clamp(1, MAX_EDITOR_ROWS)
    };
    rows + 2
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

/// Word-wrap the editor text into visual rows: splits per logical line with a
/// greedy word wrap (CJK cells count double, a word longer than the row
/// hard-breaks). Returns the rows plus, for each, the logical line it came
/// from — the caret maps through that to a visual row.
/// At `usize::MAX` width every logical line stays a single row (the height
/// estimate); pi does the same with its `layoutWidth`.
fn wrap_editor_text(text: &str, width: usize) -> (Vec<String>, Vec<usize>) {
    let mut rows: Vec<String> = Vec::new();
    let mut sources: Vec<usize> = Vec::new();
    for (logical, line) in text.split('\n').enumerate() {
        if width == usize::MAX || line.is_empty() {
            rows.push(line.to_string());
            sources.push(logical);
            continue;
        }
        let mut current = String::new();
        let mut used = 0usize;
        for token in tokenize_words(line) {
            let token_width = token.width();
            if used + token_width > width && used > 0 {
                rows.push(std::mem::take(&mut current));
                sources.push(logical);

                // The space that overflowed moves to the next row with the
                // text it separates — dropping it (the old behaviour) deleted
                // a character the user typed, so the caret then had nothing
                // to advance across and froze on the row's left edge while
                // more spaces were added. It stays invisible (it is padding
                // against the border) but it still counts toward the column.
                current.push_str(&token);
                used = token_width;
                continue;
            }
            if token_width > width {
                // Hard-break a word that cannot fit a whole row.
                for ch in token.chars() {
                    let ch_width = ch.to_string().width();
                    if used + ch_width > width && used > 0 {
                        rows.push(std::mem::take(&mut current));
                        sources.push(logical);
                        used = 0;
                    }
                    current.push(ch);
                    used += ch_width;
                }
                continue;
            }
            current.push_str(&token);
            used += token_width;
        }
        rows.push(current);
        sources.push(logical);
    }
    (rows, sources)
}

/// Split into runs of whitespace and non-whitespace so the wrap keeps whole
/// words together and spaces survive mid-row.
fn tokenize_words(text: &str) -> Vec<String> {
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

    let body_rows = height.saturating_sub(2);
    // Wrap to the row budget; the caret's visual row is kept in view the way
    // pi keeps its cursor visible: scroll as little as possible, never past
    // the end. The head of a tall prompt scrolls away, the tail — where the
    // user is typing — never does.
    let text_width = editor_text_width(width);
    let (all_rows, sources) = wrap_editor_text(&shown, text_width);
    let (caret_line, _absolute_column) = caret_position(&shown);
    // The caret always sits at the END of the input: its visual row is the
    // last row of its logical line, and its column is that row's VISIBLE
    // width. A space that wrapped away is never drawn, so the caret must not
    // count it either — counting it is what parked the cursor at the box's
    // right border whenever spaces were added or deleted around a wrap.
    let caret_visual = sources
        .iter()
        .rposition(|source| *source == caret_line)
        .unwrap_or(0);
    let caret_in_row = all_rows
        .get(caret_visual)
        .map(|row| row.width())
        .unwrap_or(0)
        .min(text_width);
    let start = if all_rows.len() <= body_rows {
        0
    } else {
        caret_visual
            .saturating_sub(body_rows.saturating_sub(1))
            .min(all_rows.len() - body_rows)
    };
    let visible = &all_rows[start..(start + body_rows).min(all_rows.len())];

    // The inverse block is drawn on the caret's row whenever it is on screen,
    // not only when the editor is one row tall: a terminal that hides or
    // misplaces the hardware cursor left the user with no caret at all as
    // soon as the prompt wrapped. pi draws the same reverse-video cell.
    let draw_fake_caret = !empty;
    let mut body = Vec::with_capacity(body_rows);
    for (index, line) in visible.iter().enumerate() {
        let mut spans = Vec::new();
        if start + index == 0 && !empty {
            spans.push(Span::styled(PROMPT, Style::default().fg(ACCENT)));
        }
        let caret = (draw_fake_caret && caret_visual == start + index).then_some(caret_in_row);
        spans.extend(body_spans(line, style, caret));
        body.push(Line::from(spans));
    }

    let lines = box_lines(title, body, width, border, height);
    // With a placeholder on screen the caret belongs where typing starts, not
    // at the end of the placeholder text.
    let visual_in_window = caret_visual.saturating_sub(start).min(body_rows.saturating_sub(1));
    let row = 1 + if empty { 0 } else { visual_in_window };
    // Column 0 is the box's left border, so the content starts at column 1.
    // The prompt rides only on the first text row — and on the placeholder,
    // which carries its own.
    let column = 1
        + if empty || caret_visual == 0 { PROMPT.width() } else { 0 }
        + if empty { 0 } else { caret_in_row };
    (lines, (column as u16, row as u16))
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

/// The `/` palette body: filtered commands plus the key hint. pi renders
/// these INSIDE the editor box, under the input row; [`compose`] splices
/// them in there.
fn palette_body(app: &App) -> Vec<Line<'static>> {
    let mut lines = match app.popups.last() {
        Some(Popup::Commands { buffer, cursor }) => command_lines(buffer, *cursor),
        _ => Vec::new(),
    };
    lines.push(Line::from(Span::styled(
        "  ↑/↓ move · Enter run · Tab complete · Esc cancel",
        Style::default().fg(CHROME),
    )));
    lines
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
    // pi's overlay default width is `min(80, available)` — not a percentage.
    let modal_width = width.min(80).max(20).min(width);
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
    let overlay_width = overlay.first().map(|line| line.width()).unwrap_or(0);
    let left = width.saturating_sub(overlay_width) / 2;
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
    // The input row (`/buffer`) already shows in the editor body above the
    // palette, so the list here carries ONLY the filtered commands.
    let rows = command_rows(buffer);
    let mut lines: Vec<Line<'static>> = Vec::new();
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
    use crate::text::plain_text;

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
        assert!(plain_text(&boxed[0]).starts_with('╭'));
        assert!(plain_text(&boxed[5]).starts_with('╰'));
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
        assert_eq!(plain_text(&boxed[3]), format!("│{}│", " ".repeat(18)));
    }

    #[test]
    fn box_lines_clip_an_overlong_body_to_the_inner_width() {
        let boxed = box_lines("t", vec![Line::raw("x".repeat(50))], 20, CHROME, 3);
        let row = plain_text(&boxed[1]);
        assert_eq!(row.chars().count(), 20, "{row}");
        assert!(row.starts_with('│') && row.ends_with('│'));
    }

    #[test]
    fn box_rows_are_exactly_the_terminal_width() {
        let boxed = box_lines("message", vec![Line::raw("hi")], 30, CHROME, 3);
        for line in boxed {
            assert_eq!(line.width(), 30, "{:?}", plain_text(&line));
        }
    }

    #[test]
    fn a_single_line_editor_shows_its_text_and_a_caret_block() {
        let mut app = app();
        app.chat.input = "hi".into();
        let (lines, cursor) = editor_box(&app, 20, 3);
        assert_eq!(lines.len(), 3);
        let body = plain_text(&lines[1]);
        assert!(body.contains("hi"), "{body}");
        let has_block = lines[1]
            .spans
            .iter()
            .any(|span| span.style.add_modifier.contains(Modifier::REVERSED));
        assert!(has_block, "the caret must be drawn: {body}");
        // Left border + the `> ` prompt + two cells of text.
        assert_eq!(cursor, (1 + 2 + 2, 1));
    }

    #[test]
    fn an_empty_editor_shows_the_placeholder_without_a_caret_block() {
        let app = app();
        let (lines, _) = editor_box(&app, 40, 3);
        assert!(plain_text(&lines[1]).contains(PLACEHOLDER));
        assert!(!lines[1]
            .spans
            .iter()
            .any(|span| span.style.add_modifier.contains(Modifier::REVERSED)));
    }

    #[test]
    fn a_multiline_editor_grows_and_moves_the_caret_down() {
        let mut app = app();
        app.chat.input = "one\ntwo".into();
        assert_eq!(editor_height(&app, 40), 4);
        let (lines, cursor) = editor_box(&app, 40, 4);
        assert_eq!(lines.len(), 4);
        assert!(plain_text(&lines[1]).contains("one"));
        assert!(plain_text(&lines[2]).contains("two"));
        assert_eq!(cursor.1, 2, "the caret follows the second line");
    }

    #[test]
    fn the_editor_caps_its_growth() {
        let mut app = app();
        app.chat.input = (0..40).map(|i| format!("{i}\n")).collect::<String>();
        assert_eq!(editor_height(&app, 80), MAX_EDITOR_ROWS + 2);
    }

    /// A prompt longer than one row WRAPS — the box grows to fit (pi's
    /// layoutText) instead of clipping the tail at the border.
    #[test]
    fn a_long_single_line_prompt_wraps_instead_of_clipping() {
        let mut app = app();
        app.chat.input = "w".repeat(100);
        // 100 cells at a 44-col box wraps to ~3 rows.
        let height = editor_height(&app, 44);
        assert!(height > 3, "100 chars must need more than one row: {height}");
        let (lines, caret) = editor_box(&app, 44, height);
        let text: Vec<String> = lines.iter().map(|l| plain_text(&l)).collect();
        let joined = text.join("\n");
        assert!(joined.contains("www"), "the input is visible:\n{text:?}");
        // The tail row must be present — the caret's row is the LAST row.
        let last_body = &text[text.len() - 2];
        assert!(last_body.contains('w'), "tail row shows input: {last_body:?}");
        assert_eq!(caret.1 as usize, height - 2, "the caret is on the last body row");
    }

    /// The reported bug: adding or deleting a SPACE around a wrap boundary
    /// left the cursor behind. Three causes, all pinned here: the caret
    /// column was the absolute column in the text rather than the column
    /// within its visual row (so it parked on the box's right border); the
    /// overflowing space was deleted from the model instead of carried to
    /// the next row (so the caret had nothing to advance across); and the
    /// screen diffed rows by characters alone, so a caret moving across
    /// trailing spaces repainted nothing. At a 20-col box rows wrap at 15
    /// cells (inner 18 − prompt 2 − caret 1).
    #[test]
    fn the_caret_tracks_the_text_across_wrap_boundaries() {
        let mut app = app();
        let (width, rows) = (20usize, 5usize);
        app.chat.input = "w".repeat(15);
        let (_, caret) = editor_box(&app, width, rows);
        assert_eq!(caret, (18, 1), "a full row puts the caret in the reserved cell");

        // The space no longer fits on the first row, so it wraps WITH the
        // text: the caret follows onto the next row, one cell past the
        // space, instead of sitting on the border.
        app.chat.input.push(' ');
        let (_, caret) = editor_box(&app, width, rows);
        assert_eq!(caret, (2, 2), "1 (left border) + 1 cell of space");

        // Further spaces keep advancing the caret one cell each — the
        // wrapped space is present, so it is not inert.
        app.chat.input.push(' ');
        let (_, caret) = editor_box(&app, width, rows);
        assert_eq!(caret, (3, 2));

        // The next character lands on that row, right after them.
        app.chat.input.push('x');
        let (_, caret) = editor_box(&app, width, rows);
        assert_eq!(caret, (4, 2), "1 + 2 spaces + 1 cell of text");

        // Deleting back down walks the caret back and returns it to the end
        // of the full first row.
        app.chat.input.pop();
        app.chat.input.pop();
        app.chat.input.pop();
        let (_, caret) = editor_box(&app, width, rows);
        assert_eq!(caret, (18, 1));
    }

    /// A caret on a wrapped row is measured from THAT row's left edge, not
    /// from the start of the logical line.
    #[test]
    fn the_caret_column_is_relative_to_its_visual_row() {
        let mut app = app();
        app.chat.input = format!("{}tail", "w".repeat(15));
        let (_, caret) = editor_box(&app, 20, 5);
        assert_eq!(caret, (5, 2), "row 2 holds 'tail' (4 cells) after the border");
    }

    /// Single-line input: the caret sits right after the last character
    /// (left border + prompt + text), never past it.
    #[test]
    fn the_caret_sits_immediately_after_the_text() {
        let mut app = app();
        app.chat.input = "abc".into();
        let (_, caret) = editor_box(&app, 20, 5);
        assert_eq!(caret, (6, 1), "1 + 2 (prompt) + 3 chars");
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
        assert_eq!(plain_text(&window[0]), "13");
    }

    #[test]
    fn composite_replaces_the_rows_it_covers() {
        let mut frame = vec![Line::raw("top"), Line::raw("mid"), Line::raw("bot")];
        composite(&mut frame, vec![Line::raw("XX")], 10);
        assert_eq!(plain_text(&frame[0]), "top");
        assert!(plain_text(&frame[1]).contains("XX"), "{}", plain_text(&frame[1]));
        assert!(!plain_text(&frame[1]).contains("mid"));
        assert_eq!(plain_text(&frame[2]), "bot");
        assert_eq!(frame[1].width(), 10);
    }

    /// The frame must be exactly the viewport height at EVERY size — a taller
    /// frame pushes the prompt off a small terminal, a shorter one lets stale
    /// rows linger. A 20-row terminal with the palette open is the case that
    /// actually broke: the chrome budgeted 1 row for a box that needs 2.
    /// `compose` fills the viewport it is given, but never below the minimum
    /// that still shows a prompt: a 4-row request is answered with the 5 rows
    /// the chrome needs, and the caller must not draw a viewport smaller than
    /// that (see [`viewport_height`], which is the only thing that sizes one).
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
                    // The viewport the client would actually build for a
                    // terminal this tall — the frame must fill exactly that,
                    // because the viewport is fixed and the chrome pads.
                    let wanted = viewport_rows(height);
                    let frame = compose(&mut app, width, wanted);
                    assert_eq!(
                        frame.lines.len(),
                        usize::from(wanted),
                        "frame must fill {width}x{wanted} (palette: {palette})"
                    );
                    for line in &frame.lines {
                        assert!(
                            line.width() <= width as usize,
                            "row too wide at {width}: {:?}",
                            plain_text(&line)
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
            .map(|line| plain_text(&line))
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
            .filter(|line| plain_text(&line).trim().is_empty())
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
        let editor_row = plain_text(&frame.lines[row as usize]);
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
                    plain_text(&line)
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
                let text = plain_text(&line);
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
                .any(|line| plain_text(&line).contains("note 199")),
            "the newest note stays visible"
        );
    }

    /// The palette belongs to the editor: pi renders the autocomplete INSIDE
    /// the editor box — the input row first, then the filtered commands, all
    /// between one pair of borders (titled ` command `).
    #[test]
    fn the_palette_renders_inside_the_editor_box() {
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
            .map(|line| plain_text(&line))
            .collect();
        let box_top = text.iter().position(|row| row.contains('╭')).unwrap();
        let box_bottom = text.iter().position(|row| row.contains('╰')).unwrap();
        let input = text.iter().position(|row| row.contains("/model")).unwrap();
        assert!(
            box_top < input && input < box_bottom,
            "input + its command list share ONE box:\n{text:?}"
        );
        // The input row comes before the palette rows inside that box.
        let list_row = text
            .iter()
            .position(|row| row.contains("▸ /model"))
            .expect("the highlighted command is listed");
        assert!(input < list_row, "input first, list below");
    }
}
