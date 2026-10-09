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

//! Native Rust terminal client for the Messenger agent core (TARGET.md
//! §16/§22, Phase 6): a `Rust Core → Document Model → Terminal Renderer →
//! ANSI/VT` client that links the core crates directly — no UniFFI, no
//! Kotlin — and maps the Document AST onto terminal cells.
//!
//! The binary entry point is `src/main.rs`; everything in here is also a
//! library so the headless integration tests can drive the real app state
//! machine and assert on the frames `ui::compose` produces.
//!
//! Rendering is split three ways, with no widget framework in between:
//! [`text`] holds the terminal's own line/span/style types, [`ui`] composes
//! a frame out of them, and [`screen`] diffs that frame against what the
//! terminal already shows.

pub mod app;
pub mod commands;
pub mod config;
pub mod engine;
pub mod highlight;
pub mod popup;
pub mod render;
pub mod screen;
pub mod shell;
pub mod store_ops;
pub mod text;
pub mod tools;
pub mod ui;
pub mod workspace;