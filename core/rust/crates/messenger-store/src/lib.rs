//! SQLite store for the Messenger core: entity tables, sync bookkeeping,
//! key-value preferences and change notifications.

pub mod import;
pub mod model;
pub mod schema;
#[cfg(test)]
mod tests;
pub mod store;

pub use import::{import_legacy, ImportSummary};
pub use model::{StoredAgent, StoredConversation, StoredMessage, StoredModel, StoredProject, StoredProvider};
pub use store::{EntityKind, Store, StoreEvent, KV_DEVICE_ID};

/// Creation-only defaults; keep in sync with Kotlin Agent and Cloud seeds.
pub const DEFAULT_AGENT_DESCRIPTION: &str =
    "通用 AI 助手，帮助你解答问题、学习知识、撰写内容、编写代码，并在工具可用时协助完成任务。";
pub const DEFAULT_AGENT_PROMPT: &str = include_str!("default_agent_prompt.txt");

/// Wall-clock milliseconds since the Unix epoch.
pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}
