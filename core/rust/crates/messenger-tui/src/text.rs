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

//! The terminal's own text model: [`Line`], [`Span`], [`Style`], [`Color`].
//!
//! `render.rs` (Document AST → lines) and `highlight.rs` (syntect → lines)
//! speak only these four types, so they contain no terminal-framework code at
//! all: [`crate::screen`] turns them into crossterm escape sequences and
//! [`crate::ui`] composes them into a frame.
//!
//! The shapes deliberately mirror what a terminal needs rather than a widget
//! toolkit's: a line is a flat list of styled runs, a style is colours plus a
//! modifier set, and a colour is 4-bit, 256-colour or truecolour.

use std::borrow::Cow;
use std::ops::{BitOr, BitOrAssign};

/// A run of text sharing one style.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Span<'a> {
    pub content: Cow<'a, str>,
    pub style: Style,
}

impl<'a> Span<'a> {
    /// Unstyled text.
    pub fn raw(text: impl Into<Cow<'a, str>>) -> Self {
        Self {
            content: text.into(),
            style: Style::default(),
        }
    }

    /// Text with an explicit style.
    pub fn styled(text: impl Into<Cow<'a, str>>, style: Style) -> Self {
        Self {
            content: text.into(),
            style,
        }
    }

    /// Display width in terminal cells (CJK counts as two).
    pub fn width(&self) -> usize {
        use unicode_width::UnicodeWidthStr;
        self.content.width()
    }
}

/// One terminal line: styled runs laid out left to right.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Line<'a> {
    pub spans: Vec<Span<'a>>,
}

impl<'a> Line<'a> {
    /// A line holding a single unstyled run.
    pub fn raw(text: impl Into<Cow<'a, str>>) -> Self {
        Self::from(Span::raw(text))
    }

    /// Total display width of the line in terminal cells.
    pub fn width(&self) -> usize {
        self.spans.iter().map(Span::width).sum()
    }

    /// The line's text with every style dropped — what the tests assert on.
    pub fn to_plain_string(&self) -> String {
        self.spans
            .iter()
            .map(|span| span.content.as_ref())
            .collect()
    }

    /// The line's text as an owned `'static` `String`, so a cached render can
    /// be stored and compared across frames without borrowing its source.
    pub fn into_static(self) -> Line<'static> {
        Line {
            spans: self
                .spans
                .into_iter()
                .map(|span| Span {
                    content: Cow::Owned(span.content.into_owned()),
                    style: span.style,
                })
                .collect(),
        }
    }
}

impl<'a> From<Span<'a>> for Line<'a> {
    fn from(span: Span<'a>) -> Self {
        Self { spans: vec![span] }
    }
}

impl<'a> From<Vec<Span<'a>>> for Line<'a> {
    fn from(spans: Vec<Span<'a>>) -> Self {
        Self { spans }
    }
}

/// Foreground/background colour plus a modifier set.
///
/// `None` means "the terminal's default", which is what lets a highlighted
/// span sit on the reader's own background instead of forcing one.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Style {
    pub fg: Option<Color>,
    pub bg: Option<Color>,
    pub add_modifier: Modifier,
}

impl Style {
    pub fn fg(mut self, color: Color) -> Self {
        self.fg = Some(color);
        self
    }

    pub fn bg(mut self, color: Color) -> Self {
        self.bg = Some(color);
        self
    }

    pub fn add_modifier(mut self, modifier: Modifier) -> Self {
        self.add_modifier |= modifier;
        self
    }

    /// True when the style asks for reverse video (a selection pill).
    pub fn is_reversed(&self) -> bool {
        self.add_modifier.contains(Modifier::REVERSED)
    }

    /// The crossterm attributes this style turns on, in SGR order. An empty
    /// result means the style emits no attribute command at all — a colour-only
    /// style must not inherit the previous run's bold.
    pub(crate) fn attributes(&self) -> Vec<crossterm::style::Attribute> {
        self.add_modifier.attributes()
    }

    /// A compact, comparable encoding of this style for the screen's
    /// differential repaint. Two runs with the same key paint identically, so
    /// a row whose key is unchanged needs no repaint — and two that differ
    /// (a caret block that moved, say) always do.
    pub(crate) fn render_key(&self) -> String {
        let mut key = String::new();
        if let Some(color) = self.fg {
            key.push_str(&format!("f{color:?};"));
        }
        if let Some(color) = self.bg {
            key.push_str(&format!("b{color:?};"));
        }
        if !self.add_modifier.is_empty() {
            key.push_str(&format!("m{:?};", self.add_modifier));
        }
        key
    }
}

/// A set of text attributes. An empty set means no attributes, never
/// "inherit the previous run's".
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Modifier(u16);

impl Modifier {
    pub const NONE: Self = Self(0);
    pub const BOLD: Self = Self(1 << 0);
    pub const DIM: Self = Self(1 << 1);
    pub const ITALIC: Self = Self(1 << 2);
    pub const UNDERLINED: Self = Self(1 << 3);
    pub const CROSSED_OUT: Self = Self(1 << 4);
    pub const REVERSED: Self = Self(1 << 5);

    pub fn empty() -> Self {
        Self::NONE
    }

    pub fn contains(&self, other: Self) -> bool {
        self.0 & other.0 == other.0
    }

    pub fn is_empty(&self) -> bool {
        self.0 == 0
    }

    /// The crossterm attributes this set turns on, in SGR order.
    pub(crate) fn attributes(&self) -> Vec<crossterm::style::Attribute> {
        use crossterm::style::Attribute;
        let mut attributes = Vec::new();
        for (bit, attribute) in [
            (Self::BOLD, Attribute::Bold),
            (Self::DIM, Attribute::Dim),
            (Self::ITALIC, Attribute::Italic),
            (Self::UNDERLINED, Attribute::Underlined),
            (Self::CROSSED_OUT, Attribute::CrossedOut),
            (Self::REVERSED, Attribute::Reverse),
        ] {
            if self.contains(bit) {
                attributes.push(attribute);
            }
        }
        attributes
    }
}

impl BitOr for Modifier {
    type Output = Self;

    fn bitor(self, rhs: Self) -> Self {
        Self(self.0 | rhs.0)
    }
}

impl BitOrAssign for Modifier {
    fn bitor_assign(&mut self, rhs: Self) {
        self.0 |= rhs.0;
    }
}

/// A terminal colour: 4-bit, 256-colour palette, or 24-bit RGB.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Color {
    Black,
    Red,
    Green,
    Yellow,
    Blue,
    Magenta,
    Cyan,
    Gray,
    DarkGray,
    LightGray,
    White,
    DarkRed,
    DarkGreen,
    DarkYellow,
    DarkBlue,
    DarkMagenta,
    DarkCyan,
    /// A palette index (0-255).
    Indexed(u8),
    Rgb(u8, u8, u8),
}

impl Color {
    pub(crate) fn into_crossterm(self) -> crossterm::style::Color {
        use crossterm::style::Color as Crossterm;
        match self {
            Color::Black => Crossterm::Black,
            Color::Red => Crossterm::Red,
            Color::Green => Crossterm::Green,
            Color::Yellow => Crossterm::Yellow,
            Color::Blue => Crossterm::Blue,
            Color::Magenta => Crossterm::Magenta,
            Color::Cyan => Crossterm::Cyan,
            Color::Gray => Crossterm::Grey,
            Color::DarkGray => Crossterm::DarkGrey,
            Color::LightGray => Crossterm::White,
            Color::White => Crossterm::White,
            Color::DarkRed => Crossterm::DarkRed,
            Color::DarkGreen => Crossterm::DarkGreen,
            Color::DarkYellow => Crossterm::DarkYellow,
            Color::DarkBlue => Crossterm::DarkBlue,
            Color::DarkMagenta => Crossterm::DarkMagenta,
            Color::DarkCyan => Crossterm::DarkCyan,
            Color::Indexed(index) => Crossterm::AnsiValue(index),
            Color::Rgb(r, g, b) => Crossterm::Rgb { r, g, b },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn line_from_a_single_span_holds_that_span() {
        let line = Line::from(Span::raw("hi"));
        assert_eq!(line.to_plain_string(), "hi");
        assert_eq!(line.width(), 2);
    }

    #[test]
    fn line_from_spans_keeps_every_run() {
        let line = Line::from(vec![
            Span::raw("a"),
            Span::styled("b", Style::default().fg(Color::Red)),
        ]);
        assert_eq!(line.to_plain_string(), "ab");
    }

    #[test]
    fn width_counts_cjk_as_two_cells() {
        assert_eq!(Line::raw("中文").width(), 4);
        assert_eq!(Line::raw("ab").width(), 2);
    }

    #[test]
    fn modifiers_accumulate_instead_of_replacing() {
        let style = Style::default()
            .add_modifier(Modifier::BOLD)
            .add_modifier(Modifier::UNDERLINED);
        assert!(style.add_modifier.contains(Modifier::BOLD));
        assert!(style.add_modifier.contains(Modifier::UNDERLINED));
        assert!(!style.add_modifier.contains(Modifier::DIM));
    }

    #[test]
    fn style_fields_and_builders_agree() {
        let style = Style::default().fg(Color::Cyan).bg(Color::Black);
        assert_eq!(style.fg, Some(Color::Cyan));
        assert_eq!(style.bg, Some(Color::Black));
        assert!(!style.is_reversed());
        assert!(style.add_modifier(Modifier::REVERSED).is_reversed());
    }

    #[test]
    fn into_static_detaches_the_borrowed_text() {
        let line = Line::from(Span::raw(String::from("borrowed")));
        let owned: Line<'static> = line.into_static();
        assert_eq!(owned.to_plain_string(), "borrowed");
    }

    #[test]
    fn colors_map_onto_crossterm_variants() {
        assert_eq!(
            Color::Indexed(45).into_crossterm(),
            crossterm::style::Color::AnsiValue(45)
        );
        assert_eq!(
            Color::Rgb(1, 2, 3).into_crossterm(),
            crossterm::style::Color::Rgb { r: 1, g: 2, b: 3 }
        );
        assert_eq!(Color::Gray.into_crossterm(), crossterm::style::Color::Grey);
        assert_eq!(
            Color::DarkGray.into_crossterm(),
            crossterm::style::Color::DarkGrey
        );
    }

    #[test]
    fn modifiers_map_onto_crossterm_attributes() {
        use crossterm::style::Attribute;
        let attributes = Style::default()
            .add_modifier(Modifier::BOLD)
            .add_modifier(Modifier::CROSSED_OUT)
            .attributes();
        assert_eq!(attributes, vec![Attribute::Bold, Attribute::CrossedOut]);
        assert!(Style::default().attributes().is_empty());
    }
}