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
pub use store::{EntityKind, Store, StoreEvent};

/// Wall-clock milliseconds since the Unix epoch.
pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}
