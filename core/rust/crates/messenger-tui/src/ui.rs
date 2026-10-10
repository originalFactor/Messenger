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

//! Frame composition: the whole surface, assembled as plain lines.
//!
//! There is one surface and no view enum — the frame is always the chat, and
//! a popup is composited into it. [`compose`] returns the frame plus the cell
//! the hardware cursor belongs in; [`crate::screen`] turns that into escape
//! sequences. Nothing here touches the terminal, so the headless tests can
//! assert on a frame directly.
//!
//! Layout, top to bottom:
//! ```text
//! ────────────────────────────────────────────────   a user turn (transcript)
//!  what the user asked
//! ────────────────────────────────────────────────
//! Default Agent - DeepSeek V4.1 Flash (high)          turn header
//!   the agent's answer                                (transcript, scrolls)
//! Worked for 1s. Consumed 12.3k (9.1k cached) input / 431 output tokens.
//! ╭─ command ────────────────────────────────────╮    the `/` palette, when open
//! │▸ /model    switch the bound model            │    (it belongs to the input)
//! ╰──────────────────────────────────────────────╯
//! Chat, @mention, Ctrl-V to paste text or images, /command    the input row
//! - Default Agent - DeepSeek V4.1 Flash (high) - Example Project (~/ws - main) - 12.3k/1M context (1%) -
//! ────────────────────────────────────────────────   the bottom rule
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
//! Three details exist purely because a terminal has no widget tree:
//!
//! * **The input area is borderless.** Its three rows are: the palette (only
//!   when `/` or `@` owns the line), the text itself, and the status rail. A
//!   boxed input costs two cell rows on every frame to draw two horizontal
//!   rules that say nothing.
//! * **The palette is drawn above the input**, not centred: it belongs to the
//!   input line, and that is where the user's attention already is.
//! * **The status rail clips, never wraps.** It is one row by definition; the
//!   information on it is ordered by importance and the tail is dropped.

use unicode_width::UnicodeWidthStr;
use ratatui::layout::Rect;
use ratatui::widgets::{Paragraph, Widget};

use crate::app::{command_help_rows, App};
use crate::popup::{command_rows, Field, Popup};
use crate::text::{Color, Line, Modifier, Span, Style};

/// Rows the input text may grow to before it starts scrolling.
const MAX_EDITOR_ROWS: usize = 8;
/// The input always keeps at least one row: it is the only way to type.
const MIN_EDITOR_ROWS: usize = 1;
/// Rows a select or palette shows before it needs its own window.
const MAX_VISIBLE_ROWS: usize = 14;
/// Width of a bordered box's vertical borders (the modals still use boxes).
const BORDER: usize = 2;
/// Shown while the input is empty — it tells the user what the line is for
/// instead of leaving an empty row, and it is where the keybindings are
/// discoverable.
const PLACEHOLDER: &str = "Chat, @mention, Ctrl-V to paste text or images, /command";


/// The most rows the viewport ever takes: the tallest chrome the client can
/// produce (the `/` palette at its own maximum, a full input, the status rail
/// and the bottom rule).
///
/// [`crate::screen::Screen`] builds its inline viewport once, and rebuilding
/// it scrolls the history we just inserted off the top (see that module's
/// docs). So the viewport is FIXED for the life of the process, and the chrome
/// pads and clips itself to fit rather than the viewport resizing.
const MAX_VIEWPORT_ROWS: u16 = 14;

/// The viewport height for a terminal `screen_rows` tall.
///
/// It is never smaller than the smallest chrome that still shows a prompt: a
/// 4-row terminal gets the 3 rows (input, rail, rule), because the prompt is
/// the only way to type at all. It is never larger than the screen.
pub fn viewport_rows(screen_rows: u16) -> u16 {
    // The smallest chrome that still shows a prompt: the input row, the status
    // rail and the rule under it.
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

    let palette_open = matches!(app.popups.last(), Some(Popup::Commands { .. }))
        || matches!(app.popups.last(), Some(Popup::Mention { .. }));
    // Space is allocated from the bottom up. When the terminal is too short
    // for all of it the priority is: the input keeps one row (the only way to
    // type), then the status rail and the rule, then the palette. The prompt
    // must never be pushed off the screen.
    //
    // The palette and the input are ONE component: the picker filters what the
    // user is typing on the line below it, so the list belongs directly above
    // the input, not floating in a modal over the transcript.
    let palette_want = if palette_open {
        palette_body(app).len()
    } else {
        0
    };
    let editor_min = MIN_EDITOR_ROWS.min(height);
    // The input row, the rail and the rule are never given up.
    let reserved = editor_min + RAIL_ROWS + 1;
    let palette_rows = palette_want.min(height.saturating_sub(reserved));
    let editor_rows = editor_height(app, width)
        .clamp(editor_min, height.saturating_sub(palette_rows + RAIL_ROWS + 1).max(editor_min));

    let (editor, caret) = input_rows(app, width, editor_rows);

    let mut chrome: Vec<Line<'static>> = Vec::new();
    if palette_rows > 0 {
        // The picker's rows are clipped to the terminal width here: a command
        // list is a fixed-width layout and a narrow terminal must cut its tail
        // rather than wrap it into the rail.
        chrome.extend(
            palette_body(app)
                .into_iter()
                .take(palette_rows)
                .map(|line| clip_line(line, width)),
        );
    }
    chrome.extend(editor);
    chrome.push(status_rail(app, width));
    chrome.push(rule_line(width));

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
    // A SHORT transcript is padded at the TOP, not between the content and the
    // input. The conversation therefore sits directly on top of the input line
    // and grows upward out of it, which is what makes the frame read as one
    // continuous surface: padding below the transcript instead would open a
    // hole in the middle of the app, with a gap between the last message and
    // the line the user is typing into. Those top rows are also the first to be
    // consumed as the conversation grows, so they disappear before anything
    // real scrolls away.
    lines.resize_with(transcript_rows - visible, Line::default);
    lines.extend(transcript[start..start + visible].iter().cloned());
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

/// The conversation, from [`crate::transcript`]: turns, tool cards, notes and
/// the last error. The grouping lives there so the layout and the drawing can
/// be tested apart.
///
/// The live streaming tail is appended here rather than inside the transcript
/// renderer: it is the one row set that is NOT settled, and it belongs to the
/// viewport (it must never reach the scrollback).
fn transcript_lines(app: &mut App, width: usize) -> Vec<Line<'static>> {
    let notes = app.chat.transcript_notes();
    let error = app.chat.error.clone();
    let opts = app.opts();
    // The body cache is taken out of the chat state for the duration of the
    // render: `lines` needs it mutably while the messages are read-only, and
    // they both live in the same struct.
    let messages = std::mem::take(&mut app.chat.messages);
    let mut cache = std::mem::take(&mut app.chat.body_cache);
    let mut lines = crate::transcript::lines(
        &messages,
        width as u16,
        &opts,
        &app.turn_stats,
        &mut cache,
        &notes,
        error.as_deref(),
    );
    app.chat.messages = messages;
    app.chat.body_cache = cache;
    // The live content belongs to the turn in progress, so it sits at the same
    // indent the settled content will land at.
    let live = live_lines(app, width);
    if !live.is_empty() {
        if !lines.is_empty() {
            lines.push(Line::default());
        }
        lines.extend(live);
    } else if app.chat.is_generating {
        // Nothing streamed back yet: say so rather than showing a blank gap.
        lines.push(Line::from(vec![
            Span::raw(" ".repeat(crate::transcript::AGENT_INDENT)),
            Span::styled(
                "thinking…",
                Style::default().fg(CHROME).add_modifier(Modifier::ITALIC),
            ),
        ]));
    }
    lines
}

/// The live streaming tail, rendered from the document session.
fn live_lines(app: &App, width: usize) -> Vec<Line<'static>> {
    let Some(live) = app.live_lines(width as u16) else {
        return Vec::new();
    };
    // The live content belongs to the turn in progress, so it sits at the same
    // indent the settled content will land at.
    live.into_iter()
        .map(|line| {
            let mut spans = vec![Span::raw(" ".repeat(crate::transcript::AGENT_INDENT))];
            spans.extend(line.spans);
            Line::from(spans)
        })
        .collect()
}

// ---------------------------------------------------------------------------
// input line
// ---------------------------------------------------------------------------

/// Cells available for input text: the full width minus one column reserved
/// for the caret, so a caret at the end of a full row still has a cell to sit
/// in instead of landing outside the frame.
fn editor_text_width(width: usize) -> usize {
    width.saturating_sub(1).max(1)
}

/// Rows the input needs: its wrapped text, capped. There is no border to add.
fn editor_height(app: &App, width: usize) -> usize {
    let text = editor_text(app);
    if text.is_empty() {
        return 1;
    }
    wrap_editor_text(&text, editor_text_width(width))
        .0
        .len()
        .clamp(1, MAX_EDITOR_ROWS)
}

/// The text the input shows. While a picker (`/` or `@`) owns the line, the
/// input shows that picker's filter buffer — the user is typing into the
/// picker, so the line must echo what the picker is filtering on.
fn editor_text(app: &App) -> String {
    match app.popups.last() {
        Some(Popup::Commands { buffer, .. }) => format!("/{buffer}"),
        Some(Popup::Mention { query, .. }) => format!("@{query}"),
        _ if app.chat.palette_open => String::new(),
        _ => app.chat.input.clone(),
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

/// The input rows plus the caret's cell among them.
///
/// Borderless by design: the input is a row of text between the conversation
/// above it and the status rail below it, and drawing a box around it would
/// spend two rows of a terminal's scarce height on two horizontal rules that
/// carry no information.
fn input_rows(app: &App, width: usize, height: usize) -> (Vec<Line<'static>>, (u16, u16)) {
    let picker_open = app.chat.palette_open
        || matches!(app.popups.last(), Some(Popup::Commands { .. } | Popup::Mention { .. }));
    let empty = !picker_open && app.chat.input.is_empty();
    // An empty line tells the user nothing, so the placeholder doubles as the
    // key hint. It carries no caret block: that is reserved for the character
    // the user will actually be typing over.
    let shown = if empty {
        PLACEHOLDER.to_string()
    } else {
        editor_text(app)
    };
    let style = if empty {
        Style::default().fg(CHROME)
    } else if picker_open {
        Style::default().fg(ACCENT)
    } else {
        Style::default().fg(Color::White)
    };

    let body_rows = height.max(1);
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
    // count it either — counting it is what parked the cursor at the right
    // edge whenever spaces were added or deleted around a wrap.
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
    // not only when the input is one row tall: a terminal that hides or
    // misplaces the hardware cursor leaves the user with no caret at all as
    // soon as the text wraps.
    let draw_fake_caret = !empty;
    let mut lines = Vec::with_capacity(body_rows);
    for (index, line) in visible.iter().enumerate() {
        let caret = (draw_fake_caret && caret_visual == start + index).then_some(caret_in_row);
        lines.push(Line::from(body_spans(line, style, caret)));
    }
    // Pad to the budget so the rail below keeps its row even when the input
    // is a single line.
    lines.resize_with(body_rows, Line::default);

    // With the placeholder on screen the caret belongs where typing starts,
    // not at the end of the hint text.
    let visual_in_window = caret_visual.saturating_sub(start).min(body_rows.saturating_sub(1));
    let row = if empty { 0 } else { visual_in_window };
    let column = if empty { 0 } else { caret_in_row };
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
            other => column += other.width().unwrap_or(0),
        }
    }
    (line, column)
}

// ---------------------------------------------------------------------------
// context line + footer
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// status rail
// ---------------------------------------------------------------------------

/// Rows the status rail occupies. Fixed: it is a single line by definition.
const RAIL_ROWS: usize = 1;

/// The dash rule closing the frame, full width and dim.
///
/// The character comes from [`crate::transcript::RULE_CHAR`] so the frame's
/// rule and the user turns' rules are the same glyph.
fn rule_line(width: usize) -> Line<'static> {
    Line::from(Span::styled(
        crate::transcript::RULE_CHAR.to_string().repeat(width),
        Style::default().fg(RULE),
    ))
}

/// One chip on the status rail.
struct Chip {
    text: String,
    style: Style,
    /// May this chip be dropped when the rail is too narrow? The mode badge
    /// and the identity chips never are — see [`status_rail`].
    droppable: bool,
}

/// The conversation's status, as a chip rail:
///
/// ```text
/// - Default Agent - DeepSeek V4.1 Flash (high) - Example Project (~/ws - main) - 12.3k/1M context (1%) - cost 4 - read-only -
/// ```
///
/// **Why chips and not the two stacked lines this replaced**: the rail is one
/// row, so the fixed height the inline viewport depends on no longer varies
/// with whether a project is set. It also survives narrow terminals by
/// dropping whole facts rather than by clipping text in the middle of a word.
///
/// **The mode badge is pinned.** It decides whether the agent may write to
/// disk, which is a safety fact, not decoration — so it is never dropped and
/// the identity chips to its left are what give way. Everything else
/// (project, context, cost, status) degrades in reverse order of importance.
fn status_rail(app: &mut App, width: usize) -> Line<'static> {
    let mut chips: Vec<Chip> = Vec::new();

    // The spinner replaces nothing: it is prepended so the working state is
    // the first thing read on the row.
    if app.chat.is_generating {
        let spin = ["⠋", "⠙", "⠹", "⠸"][app.spinner % 4];
        chips.push(Chip {
            text: format!(" {spin} working "),
            style: Style::default()
                .bg(Color::Yellow)
                .fg(Color::Black)
                .add_modifier(Modifier::BOLD),
            droppable: false,
        });
    }

    // Identity: which Agent, which model, at what reasoning effort. This is
    // the same triple the turn header shows, so the rail and the transcript
    // agree on what is answering.
    let agent = app
        .chat
        .conversation_id
        .as_deref()
        .and_then(|id| app.engine.store.get_conversation(id).ok().flatten())
        .and_then(|conversation| app.engine.store.get_agent(&conversation.agent_id).ok().flatten())
        .map(|agent| agent.name);
    if let Some(agent) = agent {
        chips.push(Chip {
            text: agent,
            style: Style::default().fg(Color::White).add_modifier(Modifier::BOLD),
            droppable: false,
        });
    }
    let effort = app
        .chat
        .conversation_id
        .as_deref()
        .and_then(|id| app.engine.store.get_conversation(id).ok().flatten())
        .and_then(|conversation| app.effective_effort(&conversation));
    let model = match (app.bound_model_label(), effort) {
        (Some(model), Some(effort)) if !effort.is_empty() => format!("{model} ({effort})"),
        (Some(model), _) => model,
        (None, _) => "no model".into(),
    };
    chips.push(Chip {
        text: model,
        style: Style::default().fg(Color::Gray),
        droppable: false,
    });

    // Where: project, its directory, and the checked-out branch. A project IS
    // a workspace, so the path is the agent's cwd and must not be ambiguous.
    if let Some(project) = app.chat_project() {
        let path = display_workspace(&project.workspace);
        let branch = crate::workspace_meta::git_branch(std::path::Path::new(&project.workspace));
        let text = match branch {
            Some(branch) => format!("{} ({path} - {branch})", project.name),
            None => format!("{} ({path})", project.name),
        };
        chips.push(Chip {
            text,
            style: Style::default().fg(CHROME),
            droppable: true,
        });
    }

    // How full the context is, and what the turn cost. Both are dropped
    // before the project: they are recoverable from the transcript's stats
    // line, the project is not.
    if let Some(meta) = &app.model_meta {
        if meta.context_window > 0 {
            let used = app.context_tokens.unwrap_or(0);
            let percent = ((used as f64 / meta.context_window as f64) * 100.0).round() as i64;
            chips.push(Chip {
                text: format!(
                    "{}/{} context ({}%)",
                    crate::turn_stats::format_tokens(used),
                    crate::turn_stats::format_tokens(meta.context_window),
                    percent
                ),
                style: Style::default().fg(CHROME),
                droppable: true,
            });
        }
    }
    if let (Some(meta), Some(usage)) = (&app.model_meta, app.chat.last_usage()) {
        if let Some(units) = crate::turn_stats::cost_units(
            usage.0,
            usage.1,
            meta.input_rate,
            meta.output_rate,
        ) {
            chips.push(Chip {
                text: format!("cost {}", crate::turn_stats::format_units(units)),
                style: Style::default().fg(CHROME),
                droppable: true,
            });
        }
    }
    if !app.status.is_empty() {
        chips.push(Chip {
            text: app.status.clone(),
            style: Style::default().fg(if app.chat.is_generating {
                CHROME
            } else {
                Color::White
            }),
            droppable: true,
        });
    }

    // The mode badge is pinned last so the rail always ends with it.
    let writable = app.conversation_writable();
    let mode = Chip {
        text: if writable {
            " writable ".to_string()
        } else {
            " read-only ".to_string()
        },
        style: if writable {
            Style::default()
                .bg(Color::Red)
                .fg(Color::Black)
                .add_modifier(Modifier::BOLD)
        } else {
            Style::default().bg(RULE).fg(Color::White)
        },
        droppable: false,
    };

    render_rail(chips, mode, width)
}

/// Lay the chips out between `- ` separators, dropping droppable chips until
/// the row fits.
///
/// Two rules, in this order:
///
/// 1. **The mode badge's cells are reserved first.** It is the only
///    indication of whether the agent may write to disk — a safety fact — so
///    it is never the thing that gives way. Reserving it up front is what
///    makes that true even on a terminal barely wider than the badge.
/// 2. **Droppable chips are dropped whole, from the end.** Chips are pushed in
///    descending order of importance, so the last droppable one is the least
///    important thing on the row. Whole chips are dropped rather than clipped
///    because half a path is a fact that is wrong, not one that is partial.
///
/// A PINNED chip that does not fit is clipped instead of dropped: knowing
/// which Agent is answering matters even when the row is too narrow to spell
/// it out.
fn render_rail(chips: Vec<Chip>, mode: Chip, width: usize) -> Line<'static> {
    let separator = " - ";
    let separator_style = Style::default().fg(RULE);
    // The framing is `- ` at the head and ` -` at the tail. On a terminal too
    // narrow for the badge plus its framing, the FRAMING gives way and the
    // badge stays: the dashes are decoration, the badge is not.
    let framing = "- ".len() + " -".len();
    let framed = width >= mode.text.width() + framing;
    let (lead, tail, gap) = if framed {
        ("- ", " -", separator.len())
    } else {
        ("", "", 0)
    };

    // The identity chips share what the badge does not need. When the rail is
    // unframed there is no separator between chips either — the width does not
    // exist for punctuation, only for facts.
    let limit = width.saturating_sub(mode.text.width().min(width) + gap + tail.len() + lead.len());
    let mut body: Vec<Span<'static>> = Vec::new();
    let mut used = 0usize;
    for chip in chips {
        if used >= limit {
            break;
        }
        let cost = if body.is_empty() { 0 } else { gap } + chip.text.width();
        let text = if used + cost <= limit {
            chip.text.clone()
        } else if chip.droppable {
            continue;
        } else {
            // Pinned but too wide: take whatever room is left.
            let room = limit.saturating_sub(used + if body.is_empty() { 0 } else { gap });
            clip_cells(&chip.text, room)
        };
        if text.is_empty() {
            continue;
        }
        if !body.is_empty() {
            body.push(Span::styled(separator.to_string(), separator_style));
            used += gap;
        }
        used += text.width();
        body.push(Span::styled(text, chip.style));
    }

    let mut spans: Vec<Span<'static>> = Vec::new();
    spans.push(Span::styled(lead.to_string(), separator_style));
    let has_body = !body.is_empty();
    spans.extend(body);
    let taken: usize = spans.iter().map(Span::width).sum();
    let junction = if has_body { gap } else { 0 };
    let badge_room = width.saturating_sub(taken + junction + tail.len());
    let badge = clip_cells(&mode.text, badge_room);
    if !badge.is_empty() {
        if has_body && junction > 0 {
            spans.push(Span::styled(separator.to_string(), separator_style));
        }
        spans.push(Span::styled(badge, mode.style));
    }
    spans.push(Span::styled(tail.to_string(), separator_style));

    let line = Line::from(spans);
    if line.width() > width {
        clip_spans(line.spans, width)
    } else {
        line
    }
}

/// The workspace path as the user reads it: `~` for the home directory.
///
/// A project's path is absolute and device-specific; on screen the home
/// directory is the one prefix worth shortening, because it is on almost every
/// path and says nothing.
fn display_workspace(path: &str) -> String {
    let home = crate::config::home_dir();
    let Some(rest) = path.strip_prefix(&home.to_string_lossy().to_string()) else {
        return path.to_string();
    };
    if rest.is_empty() {
        return "~".into();
    }
    let rest = rest.trim_start_matches(['/', '\\']).replace('\\', "/");
    format!("~/{rest}")
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

/// The same, for a whole line: a no-op when it already fits.
fn clip_line(line: Line<'static>, cells: usize) -> Line<'static> {
    if line.width() <= cells {
        return line;
    }
    clip_spans(line.spans, cells)
}

// ---------------------------------------------------------------------------
// popups
// ---------------------------------------------------------------------------

/// The open picker's body: its filtered rows plus the key hint. It is drawn
/// directly above the input row it filters.
fn palette_body(app: &App) -> Vec<Line<'static>> {
    let (rows, hint) = match app.popups.last() {
        Some(Popup::Commands { buffer, cursor }) => (
            command_lines(buffer, *cursor),
            "  ↑/↓ move · Enter run · Tab complete · Esc cancel",
        ),
        Some(Popup::Mention { query, cursor, items }) => (
            mention_lines(query, *cursor, items),
            "  ↑/↓ move · Enter insert · Tab complete · Esc cancel",
        ),
        _ => (Vec::new(), "  Esc cancel"),
    };
    let mut lines = rows;
    lines.push(Line::from(Span::styled(hint, Style::default().fg(CHROME))));
    lines
}

/// The `@` picker's rows: the matching workspace paths, best first.
fn mention_lines(
    query: &str,
    cursor: usize,
    items: &[crate::mention::MentionItem],
) -> Vec<Line<'static>> {
    if items.is_empty() {
        return vec![Line::from(Span::styled(
            if query.is_empty() {
                "  no files in this workspace".to_string()
            } else {
                format!("  no file matches @{query}")
            },
            Style::default().fg(CHROME),
        ))];
    }
    windowed(
        items
            .iter()
            .enumerate()
            .map(|(index, item)| {
                let active = index == cursor;
                let (marker, style) = if active {
                    ("▸ ", Style::default().add_modifier(Modifier::BOLD))
                } else {
                    ("  ", Style::default())
                };
                Line::from(vec![
                    Span::styled(format!("{marker}@{}", item.path), style.fg(Color::Cyan)),
                    Span::styled(
                        format!("  {}", item.name),
                        Style::default().fg(CHROME),
                    ),
                ])
            })
            .collect(),
        cursor,
    )
}

/// Every other popup is a centred modal over the frame, drawn at 70% of the
/// terminal width (pi's default) and padded out to the full row.
fn overlay(app: &mut App, width: usize, height: usize) -> Option<Vec<Line<'static>>> {
    let (lines, border) = match app.popups.last()? {
        // The `/` and `@` pickers are drawn inline above the input row they
        // filter, not as modals: the list belongs to the line being typed.
        Popup::Commands { .. } | Popup::Mention { .. } => return None,
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
    // The input row (`/buffer`) already shows in the input line below the
    // list, so the list here carries ONLY the filtered commands.
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
    fn a_single_line_input_shows_its_text_and_a_caret_block() {
        let mut app = app();
        app.chat.input = "hi".into();
        let (lines, cursor) = input_rows(&app, 20, 1);
        assert_eq!(lines.len(), 1);
        let body = plain_text(&lines[0]);
        assert!(body.contains("hi"), "{body}");
        let has_block = lines[0]
            .spans
            .iter()
            .any(|span| span.style.add_modifier.contains(Modifier::REVERSED));
        assert!(has_block, "the caret must be drawn: {body}");
        // Borderless: the text starts at column 0 and the caret sits after it.
        assert_eq!(cursor, (2, 0));
    }

    #[test]
    fn an_empty_input_shows_the_placeholder_without_a_caret_block() {
        let app = app();
        let (lines, cursor) = input_rows(&app, 60, 1);
        assert!(plain_text(&lines[0]).contains(PLACEHOLDER));
        assert!(!lines[0]
            .spans
            .iter()
            .any(|span| span.style.add_modifier.contains(Modifier::REVERSED)));
        // The caret belongs where typing starts, not at the end of the hint.
        assert_eq!(cursor, (0, 0));
    }

    #[test]
    fn a_multiline_input_grows_and_moves_the_caret_down() {
        let mut app = app();
        app.chat.input = "one\ntwo".into();
        assert_eq!(editor_height(&app, 40), 2);
        let (lines, cursor) = input_rows(&app, 40, 2);
        assert_eq!(lines.len(), 2);
        assert!(plain_text(&lines[0]).contains("one"));
        assert!(plain_text(&lines[1]).contains("two"));
        assert_eq!(cursor.1, 1, "the caret follows the second line");
    }

    #[test]
    fn the_input_caps_its_growth() {
        let mut app = app();
        app.chat.input = (0..40).map(|i| format!("{i}\n")).collect::<String>();
        assert_eq!(editor_height(&app, 80), MAX_EDITOR_ROWS);
    }

    /// A long single line WRAPS instead of clipping its tail.
    #[test]
    fn a_long_single_line_input_wraps_instead_of_clipping() {
        let mut app = app();
        app.chat.input = "w".repeat(100);
        // 100 cells at a 44-col input wraps to ~3 rows.
        let height = editor_height(&app, 44);
        assert!(height > 1, "100 chars must need more than one row: {height}");
        let (lines, caret) = input_rows(&app, 44, height);
        let text: Vec<String> = lines.iter().map(|l| plain_text(&l)).collect();
        let joined = text.join("\n");
        assert!(joined.contains("www"), "the input is visible:\n{text:?}");
        // The caret's row is the LAST row: the tail is where the user types.
        let last_body = &text[text.len() - 1];
        assert!(last_body.contains('w'), "tail row shows input: {last_body:?}");
        assert_eq!(caret.1 as usize, height - 1, "the caret is on the last row");
    }

    /// The reported bug: adding or deleting a SPACE around a wrap boundary
    /// left the cursor behind. Three causes, all pinned here: the caret
    /// column was the absolute column in the text rather than the column
    /// within its visual row; the overflowing space was deleted from the
    /// model instead of carried to the next row (so the caret had nothing to
    /// advance across); and the screen diffed rows by characters alone, so a
    /// caret moving across trailing spaces repainted nothing.
    ///
    /// At a 20-col input rows wrap at 19 cells (width − the caret's reserved
    /// cell), and there is no left border to offset by any more.
    #[test]
    fn the_caret_tracks_the_text_across_wrap_boundaries() {
        let mut app = app();
        let (width, rows) = (20usize, 6usize);
        app.chat.input = "w".repeat(19);
        let (_, caret) = input_rows(&app, width, rows);
        assert_eq!(caret, (19, 0), "a full row puts the caret in the reserved cell");

        // The space no longer fits on the first row, so it wraps WITH the
        // text: the caret follows onto the next row, one cell past the space.
        app.chat.input.push(' ');
        let (_, caret) = input_rows(&app, width, rows);
        assert_eq!(caret, (1, 1), "the wrapped space is present");

        // Further spaces keep advancing the caret one cell each.
        app.chat.input.push(' ');
        let (_, caret) = input_rows(&app, width, rows);
        assert_eq!(caret, (2, 1));

        // The next character lands on that row, right after them.
        app.chat.input.push('x');
        let (_, caret) = input_rows(&app, width, rows);
        assert_eq!(caret, (3, 1), "2 spaces + 1 cell of text");

        // Deleting back down walks the caret back to the end of the first row.
        app.chat.input.pop();
        app.chat.input.pop();
        app.chat.input.pop();
        let (_, caret) = input_rows(&app, width, rows);
        assert_eq!(caret, (19, 0));
    }

    /// A caret on a wrapped row is measured from THAT row's left edge, not
    /// from the start of the logical line.
    #[test]
    fn the_caret_column_is_relative_to_its_visual_row() {
        let mut app = app();
        app.chat.input = format!("{}tail", "w".repeat(19));
        let (_, caret) = input_rows(&app, 20, 5);
        assert_eq!(caret, (4, 1), "row 1 holds 'tail' (4 cells) from column 0");
    }

    /// Single-line input: the caret sits right after the last character,
    /// never past it.
    #[test]
    fn the_caret_sits_immediately_after_the_text() {
        let mut app = app();
        app.chat.input = "abc".into();
        let (_, caret) = input_rows(&app, 20, 5);
        assert_eq!(caret, (3, 0), "3 chars from column 0 — there is no border");
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
    /// rows linger. `compose` fills the viewport it is given, but never below
    /// the minimum that still shows a prompt: a 4-row request is answered with
    /// the rows the chrome needs, and the caller must not draw a viewport
    /// smaller than that (see [`viewport_rows`], the only thing that sizes one).
    #[test]
    fn the_frame_is_exactly_the_viewport_height_at_every_size() {
        for height in 4u16..30 {
            for width in [20u16, 40, 100] {
                // `""` is the widest palette there is (all commands match) and
                // reachable by backspacing the filter, so it is swept too.
                for buffer in ["", "model"] {
                    let mut app = app();
                    app.chat.palette_open = true;
                    app.push(crate::popup::Popup::Commands {
                        buffer: buffer.into(),
                        cursor: 0,
                    });
                    // The viewport the client would actually build for a
                    // terminal this tall — the frame must fill exactly that,
                    // because the viewport is fixed and the chrome pads.
                    let wanted = viewport_rows(height);
                    let frame = compose(&mut app, width, wanted);
                    assert_eq!(
                        frame.lines.len(),
                        usize::from(wanted),
                        "frame must fill {width}x{wanted} (palette {buffer:?})"
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

    /// The input row and the rail under it have to sit at the BOTTOM of the
    /// frame — that is what makes appending transcript rows scroll history
    /// into the terminal's scrollback instead of pushing the prompt away.
    #[test]
    fn the_input_and_the_rail_are_pinned_to_the_bottom() {
        let mut app = app();
        let frame = compose(&mut app, 60, 20);
        let text: Vec<String> = frame
            .lines
            .iter()
            .map(|line| plain_text(&line))
            .collect();
        // Bottom-up: the rule, the rail, then the input.
        assert_eq!(text[19], "─".repeat(60), "the closing rule");
        assert!(text[18].starts_with("- "), "the rail: {:?}", text[18]);
        assert!(
            text[18].trim_end().ends_with(" -"),
            "the rail closes with its own dash: {:?}",
            text[18]
        );
        assert!(text[17].contains(PLACEHOLDER), "the input: {:?}", text[17]);
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
            .filter(|line| plain_text(line).trim().is_empty())
            .count();
        assert!(blank >= 15, "an empty transcript pads the gap: {blank} blank rows");
    }

    /// The caret must address the input's row, and only then: a popup owns the
    /// keyboard, so the terminal parks the caret at the bottom.
    #[test]
    fn the_caret_sits_in_the_input_and_yields_to_a_popup() {
        let mut app = app();
        app.chat.input = "abc".into();
        let frame = compose(&mut app, 60, 20);
        let (column, row) = frame.cursor.expect("the chat owns the caret");
        let input_row = plain_text(&frame.lines[row as usize]);
        assert!(input_row.contains("abc"), "caret row: {input_row:?}");
        assert_eq!(column, 3, "no border to offset the column any more");
        // The caret is two rows above the frame's bottom (rail + rule).
        assert_eq!(row, 17);

        app.push(crate::popup::Popup::Help { scroll: 0 });
        assert_eq!(
            compose(&mut app, 60, 20).cursor,
            None,
            "a modal owns the caret"
        );
    }

    /// The frame must never be wider than the terminal, or the renderer
    /// wraps a row and every column below it shifts. A long palette buffer
    /// overflows most easily: it is measured in cells, not characters.
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

    /// The status rail is a single row and must never overflow it, whatever
    /// long paths and status messages are on it. The mode badge is the anchor
    /// that has to survive.
    #[test]
    fn the_status_rail_never_exceeds_the_terminal_width() {
        for width in [10u16, 20, 40, 80, 200] {
            let mut app = app();
            app.status = "a very long status message that will not fit anywhere".into();
            let frame = compose(&mut app, width, 20);
            // The rail is the second-to-last row.
            let rail = plain_text(&frame.lines[frame.lines.len() - 2]);
            assert!(
                rail.width() <= width as usize,
                "rail is {} cells at width {width}: {rail:?}",
                rail.width()
            );
        }
    }

    /// The mode badge decides whether the agent may write to disk, so it is
    /// the one chip the rail may never drop.
    #[test]
    fn the_status_rail_never_drops_the_mode_badge() {
        for width in [12u16, 20, 40, 120] {
            let mut app = app();
            app.status = "a long status".into();
            let frame = compose(&mut app, width, 20);
            let rail = plain_text(&frame.lines[frame.lines.len() - 2]);
            assert!(
                rail.contains("read-only") || rail.contains("writ"),
                "the mode badge is missing at width {width}: {rail:?}"
            );
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
                .any(|line| plain_text(line).contains("note 199")),
            "the newest note stays visible"
        );
    }

    /// A picker belongs to the input line: its rows sit directly ABOVE the
    /// input, and the input echoes the query the picker is filtering on.
    #[test]
    fn the_palette_renders_above_the_input_row() {
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
        let input = text
            .iter()
            .position(|row| row.trim_end() == "/model")
            .expect("the input echoes the query");
        let list_row = text
            .iter()
            .position(|row| row.contains("▸ /model"))
            .expect("the highlighted command is listed");
        assert!(list_row < input, "the list is above the input:\n{text:?}");
        // The rail and the rule are still the last two rows.
        assert!(text[22].starts_with("- "), "{:?}", text[22]);
        assert_eq!(text[23], "─".repeat(80));
    }

    /// The `@` picker is the command palette's twin, and the input echoes its
    /// query as `@…`.
    #[test]
    fn the_mention_picker_renders_above_the_input_row() {
        let mut app = app();
        app.push(crate::popup::Popup::Mention {
            query: "main".into(),
            cursor: 0,
            items: vec![crate::mention::MentionItem {
                path: "src/main.rs".into(),
                name: "main.rs".into(),
            }],
        });
        let frame = compose(&mut app, 80, 24);
        let text: Vec<String> = frame
            .lines
            .iter()
            .map(|line| plain_text(&line))
            .collect();
        let input = text.iter().position(|row| row.contains("@main")).unwrap();
        let row = text
            .iter()
            .position(|row| row.contains("src/main.rs"))
            .expect("the match is listed");
        assert!(row < input, "the list is above the input:\n{text:?}");
    }

    /// A user turn is bounded by full-width rules in the transcript.
    #[test]
    fn a_user_turn_is_ruled_off_full_width() {
        let mut app = app();
        app.chat.messages.push(messenger_store::model::StoredMessage {
            id: "u1".into(),
            conversation_id: "c1".into(),
            role: "user".into(),
            content: "refactor the parser".into(),
            parts_json: messenger_core::parts::encode_parts(&[
                messenger_llm::domain::ContentPart::Text {
                    text: "refactor the parser".into(),
                },
            ]),
            timestamp: 1,
            status: "sent".into(),
            error_message: None,
        });
        let frame = compose(&mut app, 60, 24);
        let text: Vec<String> = frame
            .lines
            .iter()
            .map(|line| plain_text(&line))
            .collect();
        let rules: Vec<usize> = text
            .iter()
            .enumerate()
            .filter(|(_, row)| row.as_str() == "─".repeat(60))
            .map(|(index, _)| index)
            .collect();
        let body = text
            .iter()
            .position(|row| row.contains("refactor the parser"))
            .expect("the message is visible");
        assert!(
            rules.iter().any(|rule| *rule < body) && rules.iter().any(|rule| *rule > body),
            "the message sits between two rules:\n{text:?}"
        );
    }
}
