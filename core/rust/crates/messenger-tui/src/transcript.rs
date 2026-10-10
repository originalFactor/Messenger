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

//! The conversation view: stored messages grouped into TURNS.
//!
//! A turn is one user message plus everything the agent did in response —
//! its tool rounds, the results, and the final text. Grouping is what makes the
//! transcript readable in a terminal: without it, a turn that called eight
//! tools is a wall of cards with no visible beginning or end.
//!
//! ```text
//! ────────────────────────────────────────────────  a user turn, ruled off
//!  what the user asked, indented, wrapped
//! ────────────────────────────────────────────────
//!
//! Default Agent - DeepSeek V4.1 Flash (high)        the turn header (dim)
//!
//!   what the agent answered, indented two cells
//!   ╭─ ⚙ terminal ──────────╮                       tool cards, as rendered
//!   │ ls                    │
//!   ╰───────────────────────╯
//!
//! Worked for 1s. Consumed 12.3k (9.1k cached) input / 431 output tokens.
//! ```
//!
//! ## What is deliberately NOT drawn
//!
//! The old layout stamped every row with a `you │` / `agent │` gutter. The
//! rules above and below the user's text already say who is speaking, and a
//! gutter costs five cells of every line in a terminal that has none to spare.
//!
//! ## Rows that change after they were written
//!
//! Rows that leave the viewport are handed to the terminal's scrollback and
//! never redrawn (see [`crate::screen`]). So nothing here may depend on state
//! that arrives later: a turn header is only emitted once the turn's stats are
//! on record, and the streaming tail lives in the viewport, never in history.
//! The one deliberate exception is the assistant placeholder row — the agent
//! loop persists it BEFORE the turn runs (so a cancelled turn keeps its row
//! position), and its content arrives later. An empty placeholder is skipped
//! here precisely because drawing it would put a blank, never-updated block
//! into the scrollback.

use messenger_llm::domain::ContentPart;
use messenger_store::model::StoredMessage;

use crate::render::{self, RenderOpts};
use crate::text::{plain_text, Color, Line, Modifier, Span, Style};
use crate::turn_stats::{TurnStats, TurnStatsLedger};

/// How far the agent's own content is indented, in cells.
pub const AGENT_INDENT: usize = 2;
/// How far the user's text is indented inside its rules, in cells.
pub const USER_INDENT: usize = 1;
/// The box-drawing dash every rule is drawn with — the user turn's rules and
/// the frame's closing rule alike. One character, because a mixed set of dash
/// glyphs reads as a rendering bug rather than a design.
pub const RULE_CHAR: char = '─';
/// The dim colour for rules, headers and the stats line.
const DIM: Color = Color::DarkGray;
const RULE: Color = Color::Indexed(238);

/// One cached body: what it was built from, and the rows themselves.
struct CachedBody {
    fingerprint: String,
    width: u16,
    /// `(show_think, show_tool_details)` — toggling either re-renders the row.
    opts: (bool, bool),
    lines: Vec<Line<'static>>,
}

/// Cached renderings of agent-turn bodies, keyed by message id.
///
/// [`lines`] renders the WHOLE conversation every frame — it needs the full row
/// count to decide what is in view and what has scrolled off — so without a
/// cache every markdown parse and syntect pass over the transcript would repeat
/// on every tick (33 times a second). Only the settled body rows are cached:
/// the turn header and the stats line depend on the ledger, which is cheap, and
/// the live tail is not a stored message at all.
#[derive(Default)]
pub struct BodyCache {
    entries: std::collections::HashMap<String, CachedBody>,
}

impl BodyCache {
    /// Forget everything: the rendered rows are no longer valid (a different
    /// conversation, or the display options changed).
    pub fn clear(&mut self) {
        self.entries.clear();
    }

    fn get_or_build(
        &mut self,
        key: &str,
        fingerprint: String,
        width: u16,
        opts: &RenderOpts,
        build: impl FnOnce() -> Vec<Line<'static>>,
    ) -> &Vec<Line<'static>> {
        let opts_key = (opts.show_think, opts.show_tool_details);
        let stale = match self.entries.get(key) {
            Some(cached) => {
                cached.fingerprint != fingerprint
                    || cached.width != width
                    || cached.opts != opts_key
            }
            None => true,
        };
        if stale {
            self.entries.insert(
                key.to_string(),
                CachedBody {
                    fingerprint,
                    width,
                    opts: opts_key,
                    lines: build(),
                },
            );
        }
        &self.entries.get(key).expect("just inserted").lines
    }
}

/// What a cached row depends on. Any change re-renders it.
///
/// The live tail streams by REPLACING rows, so the placeholder row's content
/// and status are part of the fingerprint: a jump from `sending` to `sent` is
/// exactly the moment the row's rendering must be redone.
fn body_fingerprint(message: &StoredMessage) -> String {
    format!(
        "{}|{}|{}|{}|{}",
        message.content,
        message.parts_json.as_deref().unwrap_or(""),
        message.status,
        message.error_message.as_deref().unwrap_or(""),
        message.role
    )
}

/// One entry of the transcript, in row order.
///
/// Built once per frame and then rendered: the grouping decision (which rows
/// form a turn) is separate from the drawing, so both are testable on their
/// own.
#[derive(Debug, Clone, PartialEq)]
pub enum Block {
    /// A user message, wrapped in full-width rules.
    UserTurn { text: String },
    /// ONE agent turn: every assistant round and tool result it contains, with
    /// the header above and the stats below. The rows are kept in arrival
    /// order, and the header/stats are drawn from the ledger entry of
    /// [`AgentTurn::final_message_id`].
    AgentTurn {
        /// The turn's rows, in order: text rounds and tool results alike.
        rows: Vec<TurnRow>,
        /// The id of the row the ledger's stats belong to (the final text
        /// round).
        final_message_id: Option<String>,
    },
    /// A session-local note: a slash-command outcome, a warning, an error.
    Note { text: String, kind: NoteKind },
    /// The last error, shown at the tail of the transcript.
    Error { text: String },
}

/// One row inside an agent turn.
#[derive(Debug, Clone, PartialEq)]
pub struct TurnRow {
    /// The stored row's id, for the body cache.
    pub message_id: String,
    pub parts: Vec<ContentPart>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NoteKind {
    Info,
    Warn,
    Error,
}

/// Group the conversation's rows into blocks.
///
/// A TURN is a user message plus everything the agent did in reply: every
/// assistant round and every tool result, up to (but not including) the next
/// user message. Emitting one block per ROW instead would put a blank line
/// between a round and the tool result it caused and between that result and
/// the text that followed — which is what made a multi-tool turn read as a
/// column of disconnected fragments with the frame's height wasted on gaps.
///
/// The turn's header and stats attach to the LAST assistant row that is not
/// empty, which is the final text round the ledger is keyed by. A turn still in
/// flight has no ledger entry, and one whose final round has not landed yet has
/// none either — both simply render without them.
pub fn group(messages: &[StoredMessage]) -> Vec<Block> {
    let mut blocks: Vec<Block> = Vec::new();
    for message in messages {
        let parts = messenger_core::parts::decode_parts(message.parts_json.as_deref());
        match message.role.as_str() {
            "user" => blocks.push(Block::UserTurn {
                text: message.content.clone(),
            }),
            "assistant" => {
                // The placeholder row is written before the turn runs and is
                // filled in later; while it is empty there is nothing to draw
                // (the live tail covers the streaming case).
                if message.content.trim().is_empty() && parts.is_empty() {
                    continue;
                }
                let row = TurnRow {
                    message_id: message.id.clone(),
                    parts,
                };
                // Append to the open turn when one is already collecting;
                // otherwise start one.
                match blocks.last_mut() {
                    Some(Block::AgentTurn {
                        rows,
                        final_message_id,
                    }) => {
                        rows.push(row);
                        *final_message_id = Some(message.id.clone());
                    }
                    _ => blocks.push(Block::AgentTurn {
                        rows: vec![row],
                        final_message_id: Some(message.id.clone()),
                    }),
                }
            }
            "tool" => {
                let row = TurnRow {
                    message_id: message.id.clone(),
                    parts,
                };
                match blocks.last_mut() {
                    // The result of a call the round above made: same turn.
                    Some(Block::AgentTurn { rows, .. }) => rows.push(row),
                    // An orphan (an interrupted turn, or a row written by an
                    // older client): it still needs a home, and it belongs to
                    // nobody — so it opens a turn of its own.
                    _ => blocks.push(Block::AgentTurn {
                        rows: vec![row],
                        final_message_id: None,
                    }),
                }
            }
            _ => {}
        }
    }
    blocks
}

/// Render the grouped blocks into rows, `width` cells wide.
///
/// `ledger` supplies the per-turn header and stats; a turn with no entry is
/// drawn without them rather than with invented zeros.
///
/// `cache` holds the rendered body rows: this function is called EVERY FRAME
/// (the row count decides what is in view), so re-parsing the markdown and
/// re-running the syntax highlighter per frame would be the whole transcript
/// times thirty-three a second.
pub fn lines(
    messages: &[StoredMessage],
    width: u16,
    opts: &RenderOpts,
    ledger: &TurnStatsLedger,
    cache: &mut BodyCache,
    notes: &[(String, NoteKind)],
    error: Option<&str>,
) -> Vec<Line<'static>> {
    let width = width.max(8) as usize;
    let mut out: Vec<Line<'static>> = Vec::new();
    for (index, block) in group(messages).into_iter().enumerate() {
        let _ = index;
        push_block(&mut out, &block, messages, width, opts, ledger, cache);
    }
    for (text, kind) in notes {
        push_block(
            &mut out,
            &Block::Note {
                text: text.clone(),
                kind: *kind,
            },
            messages,
            width,
            opts,
            ledger,
            cache,
        );
    }
    if let Some(error) = error {
        push_block(
            &mut out,
            &Block::Error {
                text: error.to_string(),
            },
            messages,
            width,
            opts,
            ledger,
            cache,
        );
    }
    out
}

fn push_block(
    out: &mut Vec<Line<'static>>,
    block: &Block,
    messages: &[StoredMessage],
    width: usize,
    opts: &RenderOpts,
    ledger: &TurnStatsLedger,
    cache: &mut BodyCache,
) {
    // Blocks are separated by a blank row — EXCEPT right after a rule, which
    // already is one. A user turn both opens and closes with one, so its text
    // sits tight between the two rules and the turn that answers it starts
    // directly under the closing rule, with no orphan gap in between.
    if !out.is_empty() && !out.last().is_some_and(is_rule) {
        out.push(Line::default());
    }
    match block {
        Block::UserTurn { text } => out.extend(user_turn_lines(text, width)),
        Block::AgentTurn {
            rows,
            final_message_id,
        } => out.extend(agent_turn_lines(
            rows,
            final_message_id.as_deref(),
            messages,
            width,
            opts,
            ledger,
            cache,
        )),
        Block::Note { text, kind } => {
            out.extend(indent(
                render::wrap_text(text, width.saturating_sub(AGENT_INDENT), note_style(*kind)),
            ));
        }
        Block::Error { text } => {
            out.extend(indent(render::wrap_text(
                &format!("⚠ {text}"),
                width.saturating_sub(AGENT_INDENT),
                Style::default().fg(Color::Red),
            )));
        }
    }
}

/// Is this row one of the full-width rules that bound a user turn?
fn is_rule(line: &Line<'static>) -> bool {
    let text = plain_text(line);
    !text.is_empty() && text.chars().all(|ch| ch == RULE_CHAR)
}

/// A user message inside full-width rules.
///
/// The rules are the turn separator, so they span the whole terminal: a short
/// message must still be visibly bounded, which a same-width underline would
/// not achieve. Nothing separates the rules from the text — the block is the
/// message, and padding it would put empty rows in the middle of the
/// conversation.
fn user_turn_lines(text: &str, width: usize) -> Vec<Line<'static>> {
    let mut lines = vec![rule(width)];
    let body = render::wrap_text(
        text,
        width.saturating_sub(USER_INDENT),
        Style::default().fg(Color::White),
    );
    if !body.is_empty() {
        lines.extend(indent_with(body, USER_INDENT));
    }
    lines.push(rule(width));
    lines
}

/// One agent turn: the optional header, every row it produced, the optional
/// stats line.
///
/// The rows are drawn CONTIGUOUSLY — a round, the tool result it caused, and
/// the text that followed are one flow, not three blocks. A blank line between
/// them (which is what one-block-per-message gave) spent the frame's scarce
/// height on gaps and made a single turn read as several unrelated fragments.
#[allow(clippy::too_many_arguments)]
fn agent_turn_lines(
    rows: &[TurnRow],
    final_message_id: Option<&str>,
    messages: &[StoredMessage],
    width: usize,
    opts: &RenderOpts,
    ledger: &TurnStatsLedger,
    cache: &mut BodyCache,
) -> Vec<Line<'static>> {
    let stats: Option<&TurnStats> = final_message_id.and_then(|id| ledger.get(id));
    let mut lines: Vec<Line<'static>> = Vec::new();
    if let Some(header) = stats.map(TurnStats::header).filter(|text| !text.is_empty()) {
        // The header names are user data of unbounded length, so they are
        // wrapped like any other text rather than clipped: a truncated Agent
        // name would be a wrong name.
        lines.extend(render::wrap_text(
            &header,
            width,
            Style::default().fg(DIM).add_modifier(Modifier::BOLD),
        ));
        lines.push(Line::default());
    }
    for row in rows {
        let body = body_lines(&row.parts, &row.message_id, messages, width, opts, cache);
        lines.extend(indent(body));
    }
    if let Some(stats) = stats {
        lines.push(Line::default());
        lines.extend(indent(render::wrap_text(
            &stats.summary_line(),
            width.saturating_sub(AGENT_INDENT),
            Style::default().fg(DIM),
        )));
    }
    lines
}

/// One row's body, served from the cache when it has not changed.
fn body_lines(
    parts: &[ContentPart],
    message_id: &str,
    messages: &[StoredMessage],
    width: usize,
    opts: &RenderOpts,
    cache: &mut BodyCache,
) -> Vec<Line<'static>> {
    let inner = width.saturating_sub(AGENT_INDENT) as u16;
    let fingerprint = messages
        .iter()
        .find(|message| message.id == message_id)
        .map(body_fingerprint)
        // A row with no stored message (the live tail's case) cannot be cached:
        // its key would collide with the next round's.
        .unwrap_or_default();
    cache
        .get_or_build(message_id, fingerprint, inner, opts, || {
            render::round_lines(parts, inner, opts)
        })
        .clone()
}

/// A full-width dim rule.
fn rule(width: usize) -> Line<'static> {
    Line::from(Span::styled(
        "─".repeat(width),
        Style::default().fg(RULE),
    ))
}

fn note_style(kind: NoteKind) -> Style {
    match kind {
        NoteKind::Info => Style::default().fg(DIM),
        NoteKind::Warn => Style::default().fg(Color::Yellow),
        NoteKind::Error => Style::default().fg(Color::Red),
    }
}

/// Indent every row by [`AGENT_INDENT`] cells.
fn indent(lines: Vec<Line<'static>>) -> Vec<Line<'static>> {
    indent_with(lines, AGENT_INDENT)
}

/// Prefix every row with `cells` spaces.
///
/// The pad is prepended to the row's FIRST span rather than added as a span of
/// its own: a row that is otherwise empty must still measure as `cells` wide
/// when a caller clips it, and a background style on the following span should
/// not start before its text.
fn indent_with(lines: Vec<Line<'static>>, cells: usize) -> Vec<Line<'static>> {
    if cells == 0 {
        return lines;
    }
    let pad = " ".repeat(cells);
    lines
        .into_iter()
        .map(|line| {
            if line.spans.is_empty() {
                return Line::from(Span::raw(pad.clone()));
            }
            let mut spans: Vec<Span<'static>> = Vec::with_capacity(line.spans.len() + 1);
            spans.push(Span::raw(pad.clone()));
            spans.extend(line.spans);
            Line::from(spans)
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::text::plain_text;
    use messenger_core::parts::encode_parts;

    fn user(id: &str, text: &str) -> StoredMessage {
        StoredMessage {
            id: id.into(),
            conversation_id: "c1".into(),
            role: "user".into(),
            content: text.into(),
            parts_json: encode_parts(&[ContentPart::Text { text: text.into() }]),
            timestamp: 1,
            status: "sent".into(),
            error_message: None,
        }
    }

    fn assistant(id: &str, text: &str) -> StoredMessage {
        StoredMessage {
            id: id.into(),
            conversation_id: "c1".into(),
            role: "assistant".into(),
            content: text.into(),
            parts_json: encode_parts(&[ContentPart::Text { text: text.into() }]),
            timestamp: 2,
            status: "sent".into(),
            error_message: None,
        }
    }

    fn tool(id: &str, output: &str) -> StoredMessage {
        StoredMessage {
            id: id.into(),
            conversation_id: "c1".into(),
            role: "tool".into(),
            content: output.into(),
            parts_json: encode_parts(&[ContentPart::ToolResult {
                call_id: "call_1".into(),
                name: "terminal".into(),
                output: output.into(),
                is_error: false,
            }]),
            timestamp: 3,
            status: "sent".into(),
            error_message: None,
        }
    }

    fn ledger_for(message_id: &str) -> TurnStatsLedger {
        let store = messenger_store::Store::open_memory().unwrap();
        let mut ledger = TurnStatsLedger::load(&store);
        ledger.record(
            TurnStats {
                message_id: message_id.into(),
                agent: "Default Agent".into(),
                model: "DeepSeek V4.1 Flash".into(),
                effort: Some("high".into()),
                prompt_tokens: 12_300,
                completion_tokens: 431,
                cached_tokens: 9_100,
                duration_ms: 1_000,
                cost_units: None,
            },
            &store,
        );
        ledger
    }

    fn render(messages: &[StoredMessage], ledger: &TurnStatsLedger, width: u16) -> Vec<String> {
        let mut cache = BodyCache::default();
        lines(
            messages,
            width,
            &RenderOpts::default(),
            ledger,
            &mut cache,
            &[],
            None,
        )
        .iter()
        .map(plain_text)
        .collect()
    }

    #[test]
    fn a_user_turn_is_wrapped_in_full_width_rules() {
        let rows = render(&[user("u1", "hello there")], &TurnStatsLedger::default(), 30);
        // The block is exactly rule / text / rule: nothing separates the rules
        // from the message, because the rules ARE the separator.
        assert_eq!(
            rows,
            vec![
                "─".repeat(30),
                " hello there".to_string(),
                "─".repeat(30),
            ],
            "a user turn is a tight block"
        );
    }

    /// The turn that answers a user message starts directly under the closing
    /// rule. A blank row there would be a gap in the middle of the
    /// conversation, which is what made the transcript read as half-empty
    /// before.
    #[test]
    fn nothing_opens_a_gap_after_a_rule() {
        let ledger = ledger_for("a1");
        // Wide enough that the header fits one row, so the assertion is about
        // the spacing and not about a wrap.
        let rows = render(&[user("u1", "hi"), assistant("a1", "Hello!")], &ledger, 80);
        let closing = rows
            .iter()
            .rposition(|row| !row.is_empty() && row.chars().all(|ch| ch == '─'))
            .expect("the user turn has a closing rule");
        assert_eq!(
            rows[closing + 1],
            "Default Agent - DeepSeek V4.1 Flash (high)",
            "the header follows the rule immediately:\n{}",
            rows.join("\n")
        );
    }

    /// The header is wrapped, not clipped: an Agent name is user data and a
    /// truncated name would be a wrong name.
    #[test]
    fn a_header_too_wide_for_the_row_wraps() {
        let ledger = ledger_for("a1");
        let rows = render(&[assistant("a1", "Hello!")], &ledger, 24);
        let joined = rows.join("\n");
        assert!(joined.contains("(high)"), "the whole header is present: {joined}");
        for row in &rows {
            assert!(row.chars().count() <= 24, "row too wide: {row:?}");
        }
    }

    #[test]
    fn a_long_user_message_wraps_inside_the_rules() {
        let text = "word ".repeat(20);
        let rows = render(&[user("u1", text.trim())], &TurnStatsLedger::default(), 30);
        for row in &rows {
            assert!(row.chars().count() <= 30, "row too wide: {row:?}");
        }
        // The rules are still the first and last rows.
        assert_eq!(rows[0], "─".repeat(30));
        assert_eq!(rows[rows.len() - 1], "─".repeat(30));
    }

    #[test]
    fn an_agent_turn_carries_the_header_and_the_stats_line() {
        let ledger = ledger_for("a1");
        let rows = render(&[user("u1", "hi"), assistant("a1", "Hello!")], &ledger, 80);
        let joined = rows.join("\n");
        assert!(joined.contains("Default Agent - DeepSeek V4.1 Flash (high)"), "{joined}");
        assert!(joined.contains("  Hello!"), "the reply is indented: {joined}");
        assert!(
            joined.contains("Worked for 1s. Consumed 12.3k (9.1k cached) input / 431 output tokens."),
            "{joined}"
        );
    }

    #[test]
    fn a_turn_without_a_ledger_entry_still_renders_its_content_only() {
        let rows = render(&[assistant("a1", "Hello!")], &TurnStatsLedger::default(), 80);
        let joined = rows.join("\n");
        assert!(joined.contains("Hello!"), "{joined}");
        assert!(!joined.contains("Worked for"), "no records, no invented stats: {joined}");
        assert!(!joined.contains("Default Agent"), "{joined}");
    }

    #[test]
    fn an_empty_placeholder_row_is_not_drawn() {
        // The agent loop persists the final-text row before the turn runs; an
        // empty one must not become a blank block in the scrollback.
        let mut placeholder = assistant("a1", "");
        placeholder.parts_json = None;
        let rows = render(&[user("u1", "hi"), placeholder], &TurnStatsLedger::default(), 40);
        let joined = rows.join("\n");
        assert!(joined.contains("hi"), "{joined}");
        // Only the user block: two rules, a body and the separating blanks.
        assert_eq!(rows.iter().filter(|row| row.contains('─')).count(), 2, "{rows:?}");
    }

    #[test]
    fn a_running_tool_row_is_still_drawn() {
        // A tool row with parts but no text yet is real content (the "running"
        // card), unlike the empty placeholder.
        let mut running = assistant("a1", "");
        running.parts_json = encode_parts(&[ContentPart::ToolCall {
            call_id: "call_1".into(),
            name: "terminal".into(),
            arguments: "{\"command\":\"ls\"}".into(),
        }]);
        let rows = render(&[running], &TurnStatsLedger::default(), 60);
        let joined = rows.join("\n");
        assert!(joined.contains("terminal"), "{joined}");
    }

    /// A turn's rows are CONTIGUOUS: a round, the tool result it caused and the
    /// text that followed are one flow. A blank line between them (what
    /// one-block-per-message gave) spent the frame's height on gaps and made a
    /// single turn read as several unrelated fragments.
    #[test]
    fn a_multi_row_turn_has_no_internal_gaps() {
        let ledger = ledger_for("a1");
        let rows = render(
            &[
                user("u1", "run it"),
                assistant("r1", "working"),
                tool("t1", "file-a"),
                assistant("a1", "Done."),
            ],
            &ledger,
            60,
        );
        let joined = rows.join("
");
        for needle in ["working", "file-a", "Done."] {
            assert!(joined.contains(needle), "{needle} missing:
{joined}");
        }
        // Nothing between the round and the answer: no blank row inside the
        // turn. The rows between them are the tool card's own lines, which are
        // content — an EMPTY row is what would mean the turn broke apart.
        let index = |needle: &str| {
            rows.iter()
                .position(|row| row.contains(needle))
                .unwrap_or_else(|| panic!("{needle} not found in {rows:?}"))
        };
        let round = index("working");
        let answer = index("Done.");
        let between: Vec<&String> = rows[round + 1..answer].iter().collect();
        assert!(
            !between.is_empty(),
            "the tool card is drawn between them:
{joined}"
        );
        assert!(
            between.iter().all(|row| !row.trim().is_empty()),
            "no blank row inside the turn:
{joined}"
        );
    }

    /// The turn header sits ABOVE the whole turn, not above its last row: the
    /// header names who answered, and everything the turn produced is under it.
    #[test]
    fn the_header_opens_the_turn() {
        let ledger = ledger_for("a1");
        let rows = render(
            &[assistant("r1", "working"), tool("t1", "file-a"), assistant("a1", "Done.")],
            &ledger,
            80,
        );
        assert_eq!(
            rows[0],
            "Default Agent - DeepSeek V4.1 Flash (high)",
            "the header is the turn's first row:
{}",
            rows.join("
")
        );
        let stats = rows
            .iter()
            .position(|row| row.contains("Worked for"))
            .expect("the stats line is drawn");
        let answer = rows
            .iter()
            .position(|row| row.contains("Done."))
            .expect("the answer is drawn");
        assert!(stats > answer, "the stats close the turn:
{}", rows.join("
"));
    }

    /// A second user message opens a new turn: the header and stats belong to
    /// the turn they resolve, not to the conversation.
    #[test]
    fn each_turn_is_grouped_separately() {
        let ledger = ledger_for("a2");
        let rows = render(
            &[
                user("u1", "first"),
                assistant("a1", "one"),
                user("u2", "second"),
                assistant("a2", "two"),
            ],
            &ledger,
            80,
        );
        // The first turn has no ledger entry, so no header; the second does.
        let headers = rows
            .iter()
            .filter(|row| row.contains("Default Agent"))
            .count();
        assert_eq!(headers, 1, "only the recorded turn has a header:
{}", rows.join("
"));
        let rules = rows
            .iter()
            .filter(|row| !row.is_empty() && row.chars().all(|ch| ch == '─'))
            .count();
        assert_eq!(rules, 4, "two user turns, two rules each:
{}", rows.join("
"));
    }

    #[test]
    fn a_tool_result_row_follows_its_round() {
        let rows = render(
            &[user("u1", "run it"), assistant("a1", "working"), tool("t1", "file-a")],
            &TurnStatsLedger::default(),
            60,
        );
        let joined = rows.join("\n");
        assert!(joined.contains("working"), "{joined}");
        assert!(joined.contains("file-a"), "{joined}");
    }

    /// A tool card is indented like the rest of the agent's work, whether it
    /// arrived inside a round or as its own row: a card flush against the left
    /// edge reads as a new speaker rather than as part of the turn.
    #[test]
    fn tool_cards_are_indented_under_the_turn() {
        let rows = render(
            &[assistant("a1", "working"), tool("t1", "file-a")],
            &TurnStatsLedger::default(),
            60,
        );
        let cards: Vec<&String> = rows
            .iter()
            .filter(|row| row.contains('╭') || row.contains('│') || row.contains('╰'))
            .collect();
        assert!(!cards.is_empty(), "the tool card is drawn: {rows:?}");
        for card in cards {
            assert!(
                card.starts_with("  "),
                "a card row is not indented: {card:?}"
            );
        }
    }

    #[test]
    fn notes_and_errors_are_indented_and_coloured() {
        let store = messenger_store::Store::open_memory().unwrap();
        let mut cache = BodyCache::default();
        let rows: Vec<Line<'static>> = lines(
            &[],
            40,
            &RenderOpts::default(),
            &TurnStatsLedger::load(&store),
            &mut cache,
            &[("a note".to_string(), NoteKind::Warn)],
            Some("it broke"),
        );
        let text: Vec<String> = rows.iter().map(plain_text).collect();
        let joined = text.join("\n");
        assert!(joined.contains("  a note"), "{joined}");
        assert!(joined.contains("  ⚠ it broke"), "{joined}");
        let warn_row = rows
            .iter()
            .find(|line| plain_text(line).contains("a note"))
            .unwrap();
        assert_eq!(warn_row.spans.last().unwrap().style.fg, Some(Color::Yellow));
    }

    #[test]
    fn no_row_ever_exceeds_the_width() {
        let ledger = ledger_for("a1");
        let messages = vec![
            user("u1", &"long ".repeat(40)),
            assistant("a1", "# Heading\n\nsome *body* text that runs on and on and on"),
            tool("t1", &"out ".repeat(50)),
        ];
        for width in [20u16, 33, 80, 120] {
            let mut cache = BodyCache::default();
            for row in lines(
                &messages,
                width,
                &RenderOpts::default(),
                &ledger,
                &mut cache,
                &[],
                None,
            ) {
                assert!(
                    row.width() <= width as usize,
                    "row is {} cells at width {width}: {:?}",
                    row.width(),
                    plain_text(&row)
                );
            }
        }
    }

    /// The transcript is re-rendered every frame, so an unchanged message must
    /// come back from the cache instead of being re-parsed and re-highlighted.
    ///
    /// Driven through `get_or_build` directly, because its build closure is the
    /// only place a rebuild is observable: a cache that never hit would still
    /// pass every layout assertion.
    #[test]
    fn the_body_cache_rebuilds_only_when_something_changed() {
        let messages = [assistant("a1", "## Heading\n\ntext")];
        let opts = RenderOpts::default();
        let parts = vec![ContentPart::Text {
            text: "## Heading\n\ntext".into(),
        }];
        let fingerprint = body_fingerprint(&messages[0]);
        let mut cache = BodyCache::default();
        let mut builds = 0;

        // Build four times through the same closure so the count is the number
        // of real renders.
        for (print, width) in [
            (&fingerprint, 40u16),
            (&fingerprint, 40u16),
            (&fingerprint, 30u16),
            (&fingerprint, 30u16),
        ] {
            let _ = cache.get_or_build("a1", print.clone(), width, &opts, || {
                builds += 1;
                render::round_lines(&parts, width, &opts)
            });
        }
        assert_eq!(builds, 2, "one build per distinct width, then cache hits");

        // A content change — the placeholder landing its streamed text — must
        // rebuild, or the row would keep showing what it looked like mid-stream.
        let changed = format!("{fingerprint}|sent");
        let _ = cache.get_or_build("a1", changed, 30, &opts, || {
            builds += 1;
            render::round_lines(&parts, 30, &opts)
        });
        assert_eq!(builds, 3, "changed content must rebuild");
    }

    /// The fingerprint covers everything the rendered rows depend on, so a
    /// streaming row can never be served from a stale entry.
    #[test]
    fn the_body_fingerprint_tracks_the_streamed_fields() {
        let base = assistant("a1", "partial");
        let fingerprint = body_fingerprint(&base);

        let mut landed = base.clone();
        landed.content = "partial answer".into();
        assert_ne!(fingerprint, body_fingerprint(&landed), "the content");

        let mut finished = base.clone();
        finished.status = "error".into();
        assert_ne!(fingerprint, body_fingerprint(&finished), "the status");

        let mut failed = base.clone();
        failed.error_message = Some("it broke".into());
        assert_ne!(fingerprint, body_fingerprint(&failed), "the error detail");

        let mut with_tool = base.clone();
        with_tool.parts_json = encode_parts(&[ContentPart::ToolCall {
            call_id: "call_1".into(),
            name: "terminal".into(),
            arguments: "{}".into(),
        }]);
        assert_ne!(fingerprint, body_fingerprint(&with_tool), "the parts");
    }
}
