//! OpenAI-compatible LLM layer: stream events, wire DTOs, and the streaming
//! chat parser. Ported from the Kotlin
//! `cc.ptoe.messenger.data.remote.{dto,sse}` packages — the parser semantics
//! are behavioral compatibility targets, guarded by ported unit tests.

pub mod api;
pub mod client;
pub mod domain;
pub mod dto;
pub mod events;
pub mod parser;
pub mod request;
pub mod think;

pub use api::{ChatTurnParams, ToolDeclaration, stream_chat_completion, create_chat_completion};
pub use client::{ApiError, OpenAiClient};
pub use events::{ChatStreamEvent, ToolCallData};
