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
use std::io;

use ratatui::Terminal;
use ratatui::backend::CrosstermBackend;
use ratatui::layout::Rect;
use ratatui::text::Line;
use ratatui::widgets::{Paragraph, Widget};
use ratatui::{TerminalOptions, Viewport};

/// Below this the prompt is unreachable, so a frame is never drawn.
pub const MIN_SCREEN_HEIGHT: u16 = 4;

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
        ratatui::try_init_with_options(TerminalOptions {
            viewport: Viewport::Inline(height),
        })?;
        // Built AFTER raw mode is on: `Viewport::Inline` positions itself
        // relative to the cursor, so the cursor has to be somewhere sane first.
        let terminal = Terminal::with_options(
            CrosstermBackend::new(io::stdout()),
            TerminalOptions {
                viewport: Viewport::Inline(height),
            },
        )?;
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

    /// Leave raw mode and show the cursor. Idempotent, so an explicit `stop`
    /// plus [`Drop`] is safe.
    pub fn stop(&mut self) {
        if self.stopped {
            return;
        }
        self.stopped = true;
        ratatui::restore();
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
    /// `cursor` is in frame coordinates, or `None` when the frame owns no caret
    /// (a modal is up).
    pub fn render(
        &mut self,
        cursor: Option<(u16, u16)>,
        draw: impl FnOnce(&mut ratatui::Frame<'_>),
    ) -> io::Result<()> {
        let terminal = &mut self.terminal;
        terminal.autoresize()?;
        terminal.draw(|frame| {
            draw(frame);
            // Leaving the position unset HIDES the cursor, which is what a
            // frame without a caret wants. Setting it shows the terminal's
            // caret at that cell.
            if let Some((x, y)) = cursor {
                frame.set_cursor_position((x, y));
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