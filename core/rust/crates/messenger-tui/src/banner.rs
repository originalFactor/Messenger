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

//! The startup banner: the wordmark printed once, above the inline viewport.
//!
//! It is written to the terminal BEFORE [`crate::screen::Screen::start`], as
//! ordinary output, so it lands in the terminal's own scrollback and the
//! viewport — which is positioned relative to the cursor — anchors below it.
//! Printing it is therefore not part of the render loop at all: the banner is
//! never redrawn, never scrolled by us, and scrolling back to it is the
//! terminal's job like any other history row.
//!
//! The art is a fixed 67-cell block. A narrower terminal gets the compact
//! one-line form instead: a wrapped or clipped wordmark is worse than no
//! wordmark, and the banner must never be the reason a row overflows.

use crate::text::{Color, Line, Span, Style};

/// The banner, every row padded to the same width by the art itself.
const ART: &[&str] = &[
    r" __  __                                            _____ _   _ ___ ",
    r"|  \/  | ___  ___ ___  ___ _ __   __ _  ___ _ __  |_   _| | | |_ _|",
    r"| |\/| |/ _ \/ __/ __|/ _ \ '_ \ / _` |/ _ \ '__|   | | | | | || | ",
    r"| |  | |  __/\__ \__ \  __/ | | | (_| |  __/ |      | | | |_| || | ",
    r"|_|  |_|\___||___/___/\___|_| |_|\__, |\___|_|      |_|  \___/|___|",
    r"                                 |___/",
];

/// Cells the widest art row needs (the shorter rows are padded to it when
/// drawn, so the block reads as a rectangle).
pub fn art_width() -> usize {
    ART.iter().map(|row| row.len()).max().unwrap_or(0)
}

/// The version caption printed under the wordmark: the shared project version
/// (semantic name + commit count), not this crate's internal Cargo version.
fn version_caption() -> String {
    format!("v{}", messenger_core::version::full())
}

/// The banner rows for a terminal `width` cells wide, or `None` when it does
/// not fit — the caller then prints the compact form instead.
///
/// A terminal wide enough for the art also gets the version caption as a final
/// row: the TUI has no Settings screen, so the wordmark is the only place a
/// user can read which build they are running.
pub fn banner_lines(width: u16) -> Vec<Line<'static>> {
    let width = usize::from(width);
    if width < art_width() {
        return vec![compact_line(width)];
    }
    let mut lines: Vec<Line<'static>> = ART
        .iter()
        .map(|row| {
            // Padded to the block width: a ragged right edge would make the
            // shorter rows look like a mistake rather than part of the art.
            let padded = format!("{row:<width$}", width = art_width());
            Line::from(Span::styled(padded, Style::default().fg(Color::Cyan)))
        })
        .collect();
    lines.push(
        Span::styled(version_caption(), Style::default().fg(Color::DarkGray)).into(),
    );
    lines
}

/// The fallback for a terminal too narrow for the art: one line, truncated to
/// whatever fits so it can never wrap.
fn compact_line(width: usize) -> Line<'static> {
    let spans = vec![
        Span::styled(
            "Messenger TUI",
            Style::default().fg(Color::Cyan).add_modifier(ratatui::style::Modifier::BOLD),
        ),
        Span::styled(
            format!("  {}", version_caption()),
            Style::default().fg(Color::DarkGray),
        ),
    ];
    let mut kept: Vec<Span<'static>> = Vec::new();
    let mut used = 0usize;
    for span in spans {
        if used >= width {
            break;
        }
        let mut text = String::new();
        for ch in span.content.chars() {
            let char_width = unicode_width::UnicodeWidthChar::width(ch).unwrap_or(0);
            if used + char_width > width {
                break;
            }
            text.push(ch);
            used += char_width;
        }
        if !text.is_empty() {
            kept.push(Span::styled(text, span.style));
        }
    }
    Line::from(kept)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::text::plain_text;

    #[test]
    fn the_art_is_a_rectangle() {
        // The renderer pads the shorter rows; the art itself must not be
        // wider than the block it is padded to.
        let widest = art_width();
        for row in ART {
            assert!(row.len() <= widest, "row is wider than the block: {row:?}");
        }
        assert!(widest > 0);
    }

    #[test]
    fn a_wide_terminal_gets_the_wordmark() {
        let lines = banner_lines(art_width() as u16);
        // The art, plus the version caption underneath it.
        assert_eq!(lines.len(), ART.len() + 1);
        assert!(plain_text(&lines[0]).contains("__  __"));
        assert!(plain_text(&lines[2]).contains('/'), "the art is drawn");
        for line in &lines[..ART.len()] {
            assert_eq!(line.width(), art_width(), "the block is squared off");
        }
        let caption = plain_text(&lines[ART.len()]);
        assert_eq!(caption, format!("v{}", messenger_core::version::full()));
    }

    #[test]
    fn a_narrow_terminal_gets_the_compact_line() {
        let lines = banner_lines(40);
        assert_eq!(lines.len(), 1, "the art must not wrap");
        let text = plain_text(&lines[0]);
        assert!(text.contains("Messenger TUI"), "{text}");
        assert!(text.contains("v"), "the version rides along: {text}");
    }

    #[test]
    fn every_banner_row_fits_the_terminal() {
        for width in 1u16..120 {
            let lines = banner_lines(width);
            // Below the art width there is only the compact line; at or above
            // it, the wordmark plus the version caption.
            let floor = if width < art_width() as u16 {
                1
            } else {
                ART.len() + 1
            };
            assert_eq!(lines.len(), floor, "width {width}");
            for line in lines {
                assert!(
                    line.width() <= usize::from(width),
                    "a banner row is wider than {width}: {:?}",
                    plain_text(&line)
                );
            }
        }
    }
}
