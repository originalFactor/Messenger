//! OpenAI-compatible LLM layer: stream events, wire DTOs, and the streaming
//! chat parser. Ported from the Kotlin
//! `cc.ptoe.messenger.data.remote.{dto,sse}` packages — the parser semantics
//! are behavioral compatibility targets, guarded by ported unit tests.

pub mod domain;
pub mod dto;
pub mod events;
pub mod parser;
pub mod request;
pub mod think;

pub use events::{ChatStreamEvent, ToolCallData};
