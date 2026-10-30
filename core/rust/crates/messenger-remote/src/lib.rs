//! Cross-device remote workspace tools.
//!
//! A project is owned by one device (`projects.deviceId`); its workspace only
//! exists there. The chat turn may run on any device of the account, but the
//! project's workspace-bound tools (terminal, glob, grep, read, edit, create)
//! always execute on the owner:
//!
//! - the initiating device calls [`RemoteHub::call_tool`], which relays the
//!   call through the cloud (`/api/remote/*`, see `docs/REMOTE.md` in
//!   `messenger-http`) and waits for the result;
//! - the owner's hub validates the call and hands it to a
//!   [`LocalToolExecutor`] (the local API surfaces it to the platform client,
//!   which runs the tool exactly like a local call);
//! - while the owner is not connected to the relay the project is
//!   *unavailable* ([`ProjectOwner::is_available`]).
//!
//! The hub keeps one outbound SSE connection per signed-in device and
//! reconnects with backoff; losing it marks every remote device offline and
//! fails the calls still waiting on one.

mod hub;
mod protocol;

pub use hub::{
    InboundToolCall, LocalToolExecutor, OutgoingToolCall, ProjectOwner, RemoteHub, RemoteStatus,
    RelayTiming,
};
pub use protocol::{
    Presence, SseParser, ToolCall, ToolCancel, ToolResult, EVENT_PRESENCE, EVENT_TOOL_CALL,
    EVENT_TOOL_CANCEL, EVENT_TOOL_RESULT,
};

#[cfg(test)]
mod tests;
