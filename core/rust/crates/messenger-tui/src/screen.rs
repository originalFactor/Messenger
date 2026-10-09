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

//! The terminal surface: raw mode plus a **per-line differential** renderer.
//!
//! The client is an interactive shell, not a full-screen application, so it
//! draws on the main screen and never enters the alternate screen. A frame is
//! a list of lines that fills the viewport (see [`crate::ui::compose`]), and
//! [`Screen::render`] rewrites only the lines whose content changed.
//!
//! Longer frames are not clipped: the lines above the viewport are pushed
//! into the terminal's own scrollback, where the user reaches them with the
//! mouse wheel or Shift+PgUp at zero cost to the application. That is why
//! [`Screen`] tracks how far the frame has scrolled and issues the scroll
//! itself — it is the only thing that decides what leaves the screen.
//!
//! The consequences of that model are load-bearing:
//!
//! * no alternate screen and no per-frame [`ClearType::All`] — a full clear
//!   would destroy the scrollback and repaint every line on every keystroke;
//! * every run is terminated with a style reset, or the next run inherits the
//!   previous one's colour;
//! * the recorded "previous frame" is plain text per row, which is all a
//!   differential repaint needs to compare.
//!
//! A resize invalidates that record (the columns moved), which is the one
//! place a full repaint is legitimate.

use std::io::{self, Stdout, Write};

use crossterm::cursor::{Hide, MoveTo, Show};
use crossterm::event::{DisableBracketedPaste, EnableBracketedPaste};
use crossterm::style::{
    Attribute, Print, ResetColor, SetAttribute, SetBackgroundColor, SetForegroundColor,
};
use crossterm::terminal::{self, Clear, ClearType};
use crossterm::QueueableCommand;
use unicode_width::UnicodeWidthStr;

use crate::text::Line;

/// A diffable terminal surface.
pub struct Screen {
    stdout: Stdout,
    /// Styled text of the rows currently on screen, or `None` to force the next
    /// frame to repaint everything (first frame, or after a resize). The
    /// STYLE is part of the key, not just the characters: the editor's caret
    /// is a reversed run, so a caret that moves across trailing spaces
    /// repaints nothing in the text yet changes every pixel of the row.
    previous: Option<Vec<String>>,
    /// How many leading frame lines have been pushed out of the viewport.
    scrolled: usize,
    /// The size the previous frame was written at.
    size: (u16, u16),
    started: bool,
    stopped: bool,
}

impl Screen {
    /// Enter raw mode, hide the cursor, and enable bracketed paste.
    ///
    /// Nothing may be written to the terminal before this, and every exit path
    /// (normal, error, or panic) must reach [`Screen::stop`] — which is why
    /// [`Drop`] calls it too.
    pub fn start() -> io::Result<Self> {
        let mut stdout = io::stdout();
        terminal::enable_raw_mode()?;
        stdout.queue(Hide)?;
        stdout.queue(EnableBracketedPaste)?;
        stdout.flush()?;
        Ok(Self {
            stdout,
            previous: None,
            scrolled: 0,
            size: (0, 0),
            started: true,
            stopped: false,
        })
    }

    /// Leave raw mode, show the cursor, and turn bracketed paste back off.
    /// Idempotent, so an explicit `stop` plus [`Drop`] is safe.
    pub fn stop(&mut self) {
        if self.stopped || !self.started {
            return;
        }
        self.stopped = true;
        let _ = self.stdout.queue(DisableBracketedPaste);
        let _ = self.stdout.queue(SetAttribute(Attribute::Reset));
        let _ = self.stdout.queue(ResetColor);
        let _ = self.stdout.queue(Show);
        let _ = self.stdout.flush();
        let _ = terminal::disable_raw_mode();
    }

    /// Current terminal size in `(columns, rows)`.
    ///
    /// A size change invalidates the recorded frame, so the next
    /// [`Screen::render`] repaints in full; the scroll counter restarts so
    /// the delta model sees a fresh baseline.
    pub fn size(&mut self) -> io::Result<(u16, u16)> {
        let (columns, rows) = terminal::size()?;
        if self.size != (columns, rows) {
            self.previous = None;
            self.scrolled = 0;
            self.size = (columns, rows);
        }
        Ok((columns, rows))
    }

    /// Draw a frame and leave the hardware cursor at `cursor`.
    ///
    /// `scrolled` is how many rows `compose` dropped from the top of THIS
    /// frame; the DELTA against the previous frame's count is issued as a
    /// real terminal scroll, which is what pushes history into the
    /// terminal's scrollback. Only rows whose text changed are rewritten.
    ///
    /// Returns the position the cursor actually took.
    pub fn render(
        &mut self,
        lines: &[Line<'_>],
        cursor: Option<(u16, u16)>,
        scrolled: u32,
    ) -> io::Result<(u16, u16)> {
        let (columns, rows) = self.size()?;

        // The terminal scrolled by `scrolled - previous_scrolled` since the
        // last frame. Scrolling is monotonic within a session (the frame
        // only ever drops MORE rows), and a resize resets both counts.
        let wanted = scrolled as usize;
        let delta = wanted.saturating_sub(self.scrolled);
        if delta > 0 {
            for _ in 0..delta.min(rows as usize) {
                self.scroll_up(rows)?;
            }
            self.scrolled = wanted;
            // Every row moved, so the recorded frame is no longer a valid
            // base for a differential repaint.
            self.previous = None;
        }

        let repaint_all = self.previous.is_none();
        let previous = self.previous.take().unwrap_or_default();
        let current: Vec<String> = lines.iter().map(styled_key).collect();

        for (row, line) in lines.iter().enumerate().take(rows as usize) {
            let text = &current[row];
            let changed = repaint_all
                || previous.get(row).map(String::as_str) != Some(text.as_str());
            if !changed {
                continue;
            }
            self.stdout.queue(MoveTo(0, row as u16))?;
            self.stdout.queue(Clear(ClearType::CurrentLine))?;
            self.write_line(line, columns)?;
        }
        // Rows the frame no longer occupies have to be blanked, or the
        // previous frame's tail stays on screen.
        for row in current.len()..previous.len().min(rows as usize) {
            self.stdout.queue(MoveTo(0, row as u16))?;
            self.stdout.queue(Clear(ClearType::CurrentLine))?;
        }

        let position = self.place_cursor(cursor, rows)?;
        self.stdout.flush()?;
        self.previous = Some(current);
        Ok(position)
    }

    /// Scroll the viewport up by one row. A line feed on the last row is the
    /// one portable way to do it: the terminal shifts everything up and feeds
    /// a blank line at the bottom.
    fn scroll_up(&mut self, rows: u16) -> io::Result<()> {
        if rows == 0 {
            return Ok(());
        }
        self.stdout.queue(MoveTo(0, rows - 1))?;
        self.stdout.queue(Print("\n"))?;
        Ok(())
    }

    /// Park the hardware cursor, clamped to a row that exists.
    fn place_cursor(&mut self, cursor: Option<(u16, u16)>, rows: u16) -> io::Result<(u16, u16)> {
        let last = rows.saturating_sub(1);
        let (column, row) = match cursor {
            Some((column, row)) => (column, row.min(last)),
            None => (0, last),
        };
        self.stdout.queue(MoveTo(column, row))?;
        Ok((column, row))
    }

    /// Write one line's runs, resetting the style after each so a colour never
    /// bleeds into the run that follows it.
    fn write_line(&mut self, line: &Line<'_>, columns: u16) -> io::Result<()> {
        let mut used = 0usize;
        for span in &line.spans {
            if used >= columns as usize {
                break;
            }
            let text = clip_to_cells(&span.content, columns as usize - used);
            if text.is_empty() {
                continue;
            }
            used += text.width();
            if let Some(fg) = span.style.fg {
                self.stdout.queue(SetForegroundColor(fg.into_crossterm()))?;
            }
            if let Some(bg) = span.style.bg {
                self.stdout.queue(SetBackgroundColor(bg.into_crossterm()))?;
            }
            for attribute in span.style.attributes() {
                self.stdout.queue(SetAttribute(attribute))?;
            }
            self.stdout.queue(Print(text))?;
            self.stdout.queue(SetAttribute(Attribute::Reset))?;
            self.stdout.queue(ResetColor)?;
        }
        Ok(())
    }
}

impl Drop for Screen {
    fn drop(&mut self) {
        self.stop();
    }
}


/// A row's identity for the differential repaint: its characters AND each
/// run's style. Comparing characters alone cannot see a moved caret — the
/// editor's cursor is a reversed run, so "hello world" and "hello world "
/// are the same text while looking completely different (the caret sits in a
/// different cell), and the row would be skipped, stranding the cursor's
/// block one keystroke behind. This is the same rule pi's renderer follows:
/// it diffs the emitted SGR string, not the plain text.
fn styled_key(line: &Line<'_>) -> String {
    let mut key = String::new();
    for span in &line.spans {
        key.push_str(&span.style.render_key());
        key.push_str(&span.content);
        key.push('\u{1}');
    }
    key
}

/// Truncate `text` to at most `cells` display columns, never splitting a wide
/// character in half.
fn clip_to_cells(text: &str, cells: usize) -> String {
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clip_keeps_short_text_untouched() {
        assert_eq!(clip_to_cells("abc", 10), "abc");
    }

    #[test]
    fn clip_truncates_ascii_to_the_cell_budget() {
        assert_eq!(clip_to_cells("abcdefgh", 3), "abc");
    }

    #[test]
    fn clip_never_splits_a_wide_character() {
        // Two cells each: only one fits in a three-cell budget.
        let clipped = clip_to_cells("中文", 3);
        assert_eq!(clipped, "中");
        assert_eq!(clipped.width(), 2);
    }


    /// The reported bug: the editor's caret is a REVERSED run, so adding or
    /// deleting a space leaves the row's characters unchanged while moving
    /// the highlighted cell. Diffing on characters alone decided the row was
    /// identical and skipped it, stranding the caret's block one keystroke
    /// behind the hardware cursor.
    #[test]
    fn a_row_key_covers_styles_not_only_characters() {
        use crate::text::{Line, Modifier, Span, Style};

        let plain = Line::from(Span::raw("hello world "));
        let caret_at_end = Line::from(vec![
            Span::raw("hello world"),
            Span::styled(
                " ",
                Style::default().add_modifier(Modifier::REVERSED),
            ),
        ]);
        let caret_one_earlier = Line::from(vec![
            Span::raw("hello worl"),
            Span::styled(
                " ",
                Style::default().add_modifier(Modifier::REVERSED),
            ),
        ]);

        // The characters are identical, so a text-only key would call these
        // one row; the styled key sees the caret move.
        assert_eq!(
            plain.to_plain_string(),
            caret_at_end.to_plain_string(),
            "the characters really are identical"
        );
        assert_ne!(styled_key(&plain), styled_key(&caret_at_end));
        assert_ne!(styled_key(&caret_at_end), styled_key(&caret_one_earlier));
        assert_eq!(styled_key(&plain), styled_key(&plain.clone()));
    }
    #[test]
    fn clip_of_an_empty_budget_is_empty() {
        assert_eq!(clip_to_cells("abc", 0), "");
    }

    #[test]
    fn rows_below_a_shrinking_frame_are_reported_as_leftovers() {
        // The invariant `render` relies on: the previous frame's rows past the
        // current one are exactly the rows that must be blanked.
        let previous = vec!["a".to_string(), "b".to_string(), "c".to_string()];
        let current = vec!["a".to_string()];
        let leftovers: Vec<usize> = (current.len()..previous.len()).collect();
        assert_eq!(leftovers, vec![1, 2]);
    }
}