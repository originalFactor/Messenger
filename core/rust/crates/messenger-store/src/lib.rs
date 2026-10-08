//! SQLite store for the Messenger core: the five entity tables (column names
//! kept identical to the legacy Room schema so the one-shot import is a
//! column-for-column copy), sync bookkeeping, a key-value mirror of the
//! DataStore preferences, and change notifications for reactive consumers.
//!
//! Data conventions inherited from the Kotlin app: booleans are INTEGER
//! 0/1, message roles/statuses are lowercase enum names, `partsJson` /
//! `toolsConfig` / `overrideToolsConfig` are opaque JSON strings shared
//! verbatim with the cloud documents.

// The legacy import copies a Room database off disk; there is no Room
// database (and no std::fs) in a browser, so the module is native-only.
#[cfg(not(target_arch = "wasm32"))]
pub mod import;
pub mod model;
pub mod schema;
#[cfg(test)]
mod tests;
pub mod store;
#[cfg(target_arch = "wasm32")]
pub mod wasm;

#[cfg(not(target_arch = "wasm32"))]
pub use import::{import_legacy, ImportSummary};
pub use model::{StoredAgent, StoredConversation, StoredMessage, StoredModel, StoredProvider};
pub use store::{EntityKind, Store, StoreEvent};

/// Wall-clock milliseconds since the Unix epoch.
///
/// `std::time::SystemTime::now()` traps on `wasm32-unknown-unknown` (there is
/// no OS clock to query), so every crate that needs a timestamp goes through
/// this helper: the browser reads `Date.now()` instead.
#[cfg(not(target_arch = "wasm32"))]
pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// wasm32: `Date.now()`, the only clock available in the browser.
#[cfg(target_arch = "wasm32")]
pub fn now_ms() -> i64 {
    js_sys::Date::now() as i64
}

/// wasm32 only: loads the persisted database image from the origin-private
/// file system into memory. Must be awaited before `Store::open`.
#[cfg(target_arch = "wasm32")]
pub async fn prepare_store(name: &str) -> Result<(), String> {
    wasm::prepare(name).await
}
