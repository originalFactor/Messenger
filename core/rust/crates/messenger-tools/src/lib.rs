//! Built-in tool layer: declarations the model sees and request-time tool
//! resolution. Ported from
//! `domain/tool/{ChatTool,TerminalTool,WorkspaceTool}.kt`.
//!
//! Tool arguments are never pre-screened — no shell-operator allowlist, no
//! workspace path confinement. Restriction happens where operations actually
//! run: the sandbox (Android: the companion runtime's own UID; desktop: the
//! user process) bounds what an execution can touch, and the read-only
//! mode gates the `write_access` tools at declaration time. Reads are always
//! unrestricted.
//!
//! Tool EXECUTION stays platform-side (the `ToolHost` callback in the agent
//! core routes to the runtime companion / desktop implementations); this
//! crate owns everything the request path and the model-facing contract
//! need.

pub mod registry;
pub mod tools;

pub use registry::{apply_writable_mode, resolve_request_tools};
pub use tools::{BuiltinTool, ToolExecutionResult, builtin_registry, workspace_tools};
