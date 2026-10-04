//! SQLite store for the Messenger core: the five entity tables (column names
//! kept identical to the legacy Room schema so the one-shot import is a
//! column-for-column copy), sync bookkeeping, a key-value mirror of the
//! DataStore preferences, and change notifications for reactive consumers.
//!
//! Data conventions inherited from the Kotlin app: booleans are INTEGER
//! 0/1, message roles/statuses are lowercase enum names, `partsJson` /
//! `toolsConfig` / `overrideToolsConfig` are opaque JSON strings shared
//! verbatim with the cloud documents.

pub mod import;
pub mod model;
pub mod schema;
#[cfg(test)]
mod tests;
pub mod store;

pub use import::{import_legacy, ImportSummary};
pub use model::{StoredAgent, StoredConversation, StoredMessage, StoredModel, StoredProvider};
pub use store::{EntityKind, Store, StoreEvent};
