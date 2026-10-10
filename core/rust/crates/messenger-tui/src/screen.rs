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

//! The terminal surface: an **inline viewport** on the main screen, with
//! finalized transcript handed to the terminal's own scrollback.
//!
//! This is Codex's model (`codex-rs/tui`), which ratatui ships natively as
//! [`Viewport::Inline`] + [`Terminal::insert_before`]: finalized rows are
//! written *above* the viewport into the terminal's scrollback, which then
//! owns them. The user reaches history with the mouse wheel or Shift+PgUp at
//! zero cost to the application — there is no history buffer here and no manual
//! scroll arithmetic.
//!
//! ## The viewport is a fixed region at the bottom, built once
//!
//! Codex pins the viewport to a bottom region of a fixed height. Ratatui fixes
//! an inline viewport's height when the [`Terminal`] is built and exposes no
//! setter — and rebuilding is not merely awkward, it is destructive:
//! constructing an inline viewport calls `append_lines` to push the screen
//! down (ratatui's `compute_inline_size`), which scrolls rows just inserted
//! into history straight back off the top. Measured on a real terminal: with a
//! rebuild in the loop the scrollback held only blank rows.
//!
//! A viewport covering the WHOLE screen does not slide either — the frame then
//! paints over the rows `insert_before` just wrote, so history is lost the same
//! way. Both variants were built and measured; both lose history.
//!
//! So the viewport is a fixed bottom region, sized once at startup and never
//! rebuilt. The chrome's natural height is padded to fill it, so a short
//! conversation leaves blank rows inside the viewport rather than shrinking
//! it. Everything above the viewport is terminal scrollback.
//!
//! ## Entering and leaving: never clear, never touch a buffer we did not enter
//!
//! The client runs on the MAIN screen on purpose. Its output therefore stays
//! behind after it exits, and the shell prompt has to pick up from the line
//! below the last painted row — a full-screen app would instead wipe the
//! conversation the user may still want to read.
//!
//! So the enter/leave sequences are written here rather than delegated to
//! `ratatui::init`/`restore`:
//!
//! * **enter** enables raw mode, turns bracketed paste on (so a multi-line
//!   paste arrives as one event instead of one `Enter` per line) and installs
//!   a panic hook that restores the terminal before the panic message prints.
//! * **leave** turns bracketed paste off, disables raw mode, shows the cursor,
//!   and moves to column 0 and down one line so the shell prompt starts on a
//!   clean row.
//!
//! Both are written as byte sequences into a plain [`Write`], which is what
//! makes the contract testable: [`enter_sequence`] and [`leave_sequence`] are
//! asserted against a `Vec<u8>` in this module's tests, pinning the two
//! properties that are otherwise impossible to regression-test from inside a
//! real terminal — **no `Clear`** (the transcript must survive) and **no
//! alternate-screen switch** (we never entered one, and `?1049l` on some
//! terminals switches buffers or clears). `ratatui::restore` emits exactly
//! that `LeaveAlternateScreen`, which is why it is not used here.
//!
//! The cursor is deliberately restored to a *visible* state and a *fresh* row:
//! the viewport parks the caret in the editor, and a terminal left with it
//! hidden or mid-row leaves the user typing into an invisible position.

use std::io::{self, Write};

use crossterm::cursor::{MoveToColumn, Show};
use crossterm::event::{DisableBracketedPaste, EnableBracketedPaste};
use crossterm::execute;
use crossterm::terminal::{disable_raw_mode, enable_raw_mode};
use ratatui::Terminal;
use ratatui::backend::CrosstermBackend;
use ratatui::layout::Rect;
use ratatui::text::Line;
use ratatui::widgets::{Paragraph, Widget};
use ratatui::{TerminalOptions, Viewport};

/// Below this the prompt is unreachable, so a frame is never drawn.
pub const MIN_SCREEN_HEIGHT: u16 = 4;

/// Write the sequence that puts the terminal into the state the client paints
/// in: raw mode on (so keys arrive unbuffered), bracketed paste on (so a
/// multi-line paste is one event), cursor shown.
///
/// Separate from [`Screen::start`] so the bytes can be asserted directly.
pub fn enter_sequence(writer: &mut impl Write) -> io::Result<()> {
    enable_raw_mode()?;
    execute!(writer, EnableBracketedPaste, Show)
}

/// Write the sequence that hands the terminal back.
///
/// Deliberately does NOT emit `Clear` or `LeaveAlternateScreen`: the client
/// never entered the alternate screen, and the conversation on the main screen
/// is the user's to keep. Column 0 plus a newline puts the shell prompt below
/// the last painted row instead of somewhere inside it.
pub fn leave_sequence(writer: &mut impl Write) -> io::Result<()> {
    execute!(writer, DisableBracketedPaste)?;
    disable_raw_mode()?;
    execute!(writer, Show, MoveToColumn(0), crossterm::style::Print("\r\n"))
}

/// Where the hardware caret belongs, in the ABSOLUTE screen position the
/// backend expects.
///
/// `cursor` is frame-relative; `origin_y` is the viewport's top row on the
/// screen. `Frame::set_cursor_position` reaches the backend verbatim and
/// crossterm's `MoveTo` is absolute (`CSI row;col H`), so an inline viewport
/// (which sits at the bottom of the screen, not at row 0) has to offset by its
/// origin or the caret lands one viewport-height too high — in the scrollback
/// rather than in the input line.
///
/// Split out as a pure function because it is the one piece of the render path
/// that both a real terminal and a `TestBackend` can get wrong identically:
/// asserted directly, and by `TestBackend` in the tests below.
fn absolute_cursor(cursor: Option<(u16, u16)>, origin_y: u16) -> Option<(u16, u16)> {
    cursor.map(|(x, y)| (x, y.saturating_add(origin_y)))
}

/// A terminal whose viewport is the bottom `height` rows of the screen;
/// everything above it is scrollback.
pub struct Screen {
    terminal: Terminal<CrosstermBackend<io::Stdout>>,
    /// The viewport height this terminal was built with. Fixed for the life of
    /// the process — see the module docs for why it must not change.
    height: u16,
    stopped: bool,
}

impl Screen {
    /// Enter raw mode and build the inline viewport.
    ///
    /// `height` is the viewport height in rows and is FINAL: the frame pads
    /// itself to fill it rather than the viewport resizing. Nothing may be
    /// written to the terminal before this, and every exit path (normal,
    /// error, or panic) must reach [`Screen::stop`] — which is why [`Drop`]
    /// calls it too.
    pub fn start(height: u16) -> io::Result<Self> {
        let height = height.max(MIN_SCREEN_HEIGHT);
        // The panic hook is installed by us rather than by `ratatui::init`,
        // because `init` also restores via `LeaveAlternateScreen`. Restoring
        // means the same thing here as it does on the way out.
        install_panic_hook();
        let mut stdout = io::stdout();
        enter_sequence(&mut stdout)?;
        // Built AFTER raw mode is on: `Viewport::Inline` positions itself
        // relative to the cursor, so the cursor has to be somewhere sane first.
        // Built exactly ONCE: constructing an inline viewport emits
        // `append_lines` (see the module docs), so a throwaway terminal here
        // would scroll the screen twice before the first frame.
        //
        // A failure here must undo the enter sequence: raw mode is already on,
        // and returning the error would leave the user's shell unusable.
        let terminal = match Terminal::with_options(
            CrosstermBackend::new(io::stdout()),
            TerminalOptions {
                viewport: Viewport::Inline(height),
            },
        ) {
            Ok(terminal) => terminal,
            Err(error) => {
                let _ = leave_sequence(&mut io::stdout());
                return Err(error);
            }
        };
        Ok(Self {
            terminal,
            height,
            stopped: false,
        })
    }

    /// The viewport height this screen was built with.
    pub fn height(&self) -> u16 {
        self.height
    }

    /// Leave raw mode, show the cursor, and put the shell prompt on its own
    /// row. Idempotent, so an explicit `stop` plus [`Drop`] is safe.
    pub fn stop(&mut self) {
        if self.stopped {
            return;
        }
        self.stopped = true;
        let mut stdout = io::stdout();
        // A failure here is worth reporting but must not mask the exit itself.
        if let Err(error) = leave_sequence(&mut stdout) {
            eprintln!("messenger-tui: could not restore the terminal: {error}");
        }
        let _ = stdout.flush();
    }

    /// Current terminal size in `(columns, rows)`.
    pub fn size(&self) -> io::Result<(u16, u16)> {
        let size = self.terminal.size()?;
        Ok((size.width, size.height))
    }

    /// Hand finalized transcript rows to the terminal's scrollback.
    ///
    /// Only FINAL rows may be passed: an inserted row is never redrawn, so a
    /// still-streaming line would freeze mid-sentence in scrollback. Rows must
    /// already be wrapped to the terminal width — the transcript renderer wraps
    /// with `unicode-width` before we get here.
    pub fn flush_history(&mut self, lines: &[Line<'static>]) -> io::Result<()> {
        if lines.is_empty() {
            return Ok(());
        }
        let Ok(height) = u16::try_from(lines.len()) else {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "history batch exceeds u16 rows",
            ));
        };
        let lines = lines.to_vec();
        let terminal = &mut self.terminal;
        terminal.insert_before(height, |buf| {
            for (row, line) in lines.iter().enumerate() {
                let Ok(y) = u16::try_from(row) else { break };
                if y >= buf.area.height {
                    break;
                }
                Paragraph::new(line.clone()).render(
                    Rect {
                        x: buf.area.x,
                        y,
                        width: buf.area.width,
                        height: 1,
                    },
                    buf,
                );
            }
        })?;
        // The scroll moved every row on the terminal, but ratatui's
        // differential buffer is not told: its "previous frame" still holds
        // those rows at their OLD indices, so the next `draw` diffs against
        // stale content and repaints the shifted rows — the transcript visibly
        // duplicates itself above the viewport (measured: every note rendered
        // twice, the stale copy one viewport-height higher). Codex's fork fixes
        // this with `note_history_rows_inserted`.
        //
        // `Terminal::clear` would reset that buffer, but on an inline viewport
        // it also ERASES from the viewport origin down — wiping the rows
        // `insert_before` just wrote above it. So both buffers are reset in
        // memory instead: the next `draw` sees a full mismatch and repaints the
        // viewport, while the screen keeps the history.
        self.terminal.current_buffer_mut().reset();
        Ok(())
    }

    /// Draw one frame, leaving the hardware cursor at `cursor`.
    ///
    /// `cursor` is in FRAME coordinates (row 0 is the frame's top row), or
    /// `None` when the frame owns no caret (a modal is up).
    pub fn render(
        &mut self,
        cursor: Option<(u16, u16)>,
        draw: impl FnOnce(&mut ratatui::Frame<'_>),
    ) -> io::Result<()> {
        let terminal = &mut self.terminal;
        terminal.autoresize()?;
        terminal.draw(|frame| {
            // Read the viewport origin HERE, inside the render pass: it is the
            // only place that knows where the frame actually is.
            let origin_y = frame.area().y;
            draw(frame);
            // Leaving the position unset HIDES the cursor, which is what a
            // frame without a caret wants. Setting it shows the terminal's
            // caret at that cell.
            if let Some(position) = absolute_cursor(cursor, origin_y) {
                frame.set_cursor_position(position);
            }
        })?;
        Ok(())
    }
}

impl Drop for Screen {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Restore the terminal before a panic message is printed, so the message
/// lands on a sane screen instead of inside the raw-mode viewport.
fn install_panic_hook() {
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let mut stdout = io::stdout();
        let _ = leave_sequence(&mut stdout);
        let _ = stdout.flush();
        previous(info);
    }));
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The two properties the exit contract is made of. Asserted on the BYTES
    /// because there is no other way to regression-test "did not wipe the
    /// user's screen": inside a real terminal the visible result is the same
    /// as a correct exit until the transcript is gone.
    #[test]
    fn leaving_never_clears_the_screen_or_switches_buffers() {
        let mut bytes: Vec<u8> = Vec::new();
        leave_sequence(&mut bytes).unwrap();
        let text = String::from_utf8_lossy(&bytes);

        // `?1049l` leaves the alternate screen. We never entered it, and on
        // some terminals that switch clears the visible buffer.
        assert!(!text.contains("?1049"), "must not leave an alternate screen: {text:?}");
        // `Clear` (2J) and `EraseDisplay` wipe the transcript above us.
        assert!(!text.contains("[2J"), "must not clear the screen: {text:?}");
        assert!(!text.contains("[3J"), "must not erase the scrollback: {text:?}");
    }

    /// The cursor must come back visible and on a fresh row: the viewport
    /// parks it in the editor, which is the wrong place for a shell prompt.
    #[test]
    fn leaving_shows_the_cursor_and_opens_a_fresh_row() {
        let mut bytes: Vec<u8> = Vec::new();
        leave_sequence(&mut bytes).unwrap();
        let text = String::from_utf8_lossy(&bytes);

        assert!(text.contains("[?25h"), "the cursor must be shown: {text:?}");
        assert!(text.contains("[0G") || text.contains("\r"), "column 0: {text:?}");
        assert!(text.contains("\r\n") || text.contains("\n"), "a fresh row: {text:?}");
        // Bracketed paste must be off: leaving it on changes what the shell
        // sees for pasted text.
        assert!(text.contains("[?2004l"), "bracketed paste off: {text:?}");
    }

    #[test]
    fn entering_never_switches_to_the_alternate_screen() {
        let mut bytes: Vec<u8> = Vec::new();
        enter_sequence(&mut bytes).unwrap();
        let text = String::from_utf8_lossy(&bytes);

        assert!(!text.contains("?1049"), "the conversation stays on the main screen: {text:?}");
        assert!(text.contains("[?2004h"), "bracketed paste on: {text:?}");
    }

    /// The caret is offset by the viewport's top row.
    ///
    /// This is the reported bug: the real cursor and the drawn caret were in
    /// different places, because the drawn caret is positioned by `draw` (which
    /// adds the origin to every row) while the hardware cursor was handed a
    /// frame-relative row — and the backend treats it as an absolute screen
    /// position. On a 30-row terminal with a 12-row viewport that put the
    /// caret 18 rows above the input line.
    #[test]
    fn the_caret_is_offset_by_the_viewport_origin() {
        assert_eq!(absolute_cursor(Some((3, 0)), 18), Some((3, 18)));
        assert_eq!(absolute_cursor(Some((3, 2)), 18), Some((3, 20)));
        // A full-screen viewport starts at row 0 and must not shift at all.
        assert_eq!(absolute_cursor(Some((3, 2)), 0), Some((3, 2)));
        // No caret stays no caret (a modal owns the keyboard).
        assert_eq!(absolute_cursor(None, 18), None);
    }

    /// The offset actually reaches the backend, checked against the same
    /// `TestBackend` the terminal uses — a pure-function assertion alone would
    /// not catch the offset being dropped on the way to `draw`.
    #[test]
    fn the_backend_receives_an_absolute_caret() {
        use ratatui::Terminal;
        use ratatui::backend::TestBackend;
        use ratatui::{TerminalOptions, Viewport};

        let backend = TestBackend::new(40, 30);
        let mut terminal = Terminal::with_options(
            backend,
            TerminalOptions {
                viewport: Viewport::Inline(6),
            },
        )
        .unwrap();
        // Push the viewport off row 0, exactly as the banner and any history
        // insert do.
        terminal
            .insert_before(20, |buffer| {
                for row in 0..20u16 {
                    buffer[(0, row)].set_symbol("#");
                }
            })
            .unwrap();

        let mut origin = 0u16;
        terminal
            .draw(|frame| {
                origin = frame.area().y;
                // The input line is the second-to-last frame row.
                let frame_row = frame.area().height - 2;
                if let Some(position) = absolute_cursor(Some((4, frame_row)), origin) {
                    frame.set_cursor_position(position);
                }
            })
            .unwrap();

        assert!(origin > 0, "the viewport must have been pushed down");
        let placed = terminal.get_cursor_position().unwrap();
        assert_eq!(
            placed.y,
            origin + 4,
            "the backend must receive the absolute row (origin {origin})"
        );
        assert_eq!(placed.x, 4);
    }
}
