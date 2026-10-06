//! Document Model and Diff Engine for Messenger.
//!
//! Exposes typed AST [`Block`]s with stable [`BlockId`]s and streaming diff events
//! for platform-native renderers (RecyclerView, Canvas, TUI, etc.).

pub mod diff;
pub mod document;
pub mod model;

pub use diff::{DiffBatch, DocumentDiff};
pub use document::Document;
pub use model::{Block, BlockId, BlockStatus, Inline, ListItem};
