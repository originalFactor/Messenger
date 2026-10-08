//! Native Rust terminal client for the Messenger agent core (TARGET.md
//! §16/§22, Phase 6): a `Rust Core → Document Model → Terminal Renderer →
//! ANSI/VT` client that links the core crates directly — no UniFFI, no
//! Kotlin — and maps the Document AST onto terminal cells.
//!
//! The binary entry point is `src/main.rs`; everything in here is also a
//! library so the headless integration tests can drive the real app state
//! machine against a `TestBackend`.

pub mod app;
pub mod config;
pub mod engine;
pub mod highlight;
pub mod render;
pub mod shell;
pub mod store_ops;
pub mod tools;
pub mod ui;
pub mod workspace;
