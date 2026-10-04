//! Built-in tool layer: declarations the model sees, the read-only shell
//! command policy, and request-time tool resolution. Ported from
//! `domain/tool/{ChatTool,TerminalTool,WorkspaceTool,ShellCommandPolicy}.kt`.
//!
//! Tool EXECUTION stays platform-side (the `ToolHost` callback in the agent
//! core routes to the runtime companion / desktop implementations); this
//! crate owns everything the request path and the model-facing contract
//! need.

pub mod policy;
pub mod registry;
pub mod tools;

pub use policy::rejection_reason;
pub use registry::resolve_request_tools;
pub use tools::{BuiltinTool, ToolExecutionResult, workspace_tools};
