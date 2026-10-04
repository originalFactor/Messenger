//! Cloud sync (port of `data/cloud/*`): the SaaS protocol client, document
//! mappings, and the sync engine (paged pull with cursor checks, delta
//! application with builtin-exclusion and role guards, push of pending
//! upserts/deletes, builtin provider/title-agent seeding).

pub mod api;
pub mod documents;
pub mod models;
pub mod rehydrate;
#[cfg(test)]
mod sync_tests;
pub mod sync;

pub use api::CloudApiClient;
pub use models::*;
pub use models::{BUILTIN_PROVIDER_ID, BUILTIN_PROVIDER_NAME, BUILTIN_TITLE_AGENT_ID, DEFAULT_CLOUD_SERVER_URL, ROLE_CHAT, ROLE_TITLE};
pub use sync::SyncEngine;
