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

//! The text model: ratatui's [`Line`]/[`Span`]/[`Style`]/[`Color`], re-exported
//! so the rest of the crate has one import path.
//!
//! This module used to define its own copies of all four types. That was the
//! wrong call twice over: the copies were incompatible with ratatui's widgets
//! (a `Paragraph` would not accept our `Line`, so every render needed a
//! conversion), and maintaining them meant owning the wrapping, clipping and
//! cursor rules that a widget library already gets right.
//!
//! `render.rs` (Document AST → lines) and `highlight.rs` (syntect → lines)
//! speak only these types, so they keep working unchanged — they just import
//! them from here.

pub use ratatui::style::{Color, Modifier, Style};
pub use ratatui::text::{Line, Span};

/// The line's text with every style dropped — what the tests assert on.
///
/// This is exactly [`Line`]'s `Display`, spelled as a free function so call
/// sites read as `plain(&line)` and do not allocate through `to_string()`.
pub fn plain_text(line: &Line<'_>) -> String {
    line.spans
        .iter()
        .map(|span| span.content.as_ref())
        .collect()
}
