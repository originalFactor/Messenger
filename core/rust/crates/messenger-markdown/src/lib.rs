//! Incremental Markdown and Streaming Document Engine.
//!
//! Provides prefix-stable inline parsing, block state-machine scanning,
//! and 20–50 ms token batching windows for low-overhead document diff generation.

pub mod inlines;
pub mod parser;
pub mod session;

pub use inlines::parse_inlines;
pub use parser::IncrementalParser;
pub use session::StreamingSession;
