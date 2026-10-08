//! Cloud sync (port of `data/cloud/*`): the SaaS protocol client, document
//! mappings, and the sync engine (paged pull with cursor checks, delta
//! application with builtin-exclusion and role guards, push of pending
//! upserts/deletes, builtin provider/title-agent seeding).

pub mod api;
pub mod avatar;
pub mod cards;
pub mod documents;
pub mod market;
pub mod models;
pub mod models_sync;
// Pull-side image rehydration writes files, so it is native-only; the
// browser keeps image parts as their `data:` URIs.
#[cfg(not(target_arch = "wasm32"))]
pub mod rehydrate;
pub mod settings;
#[cfg(test)]
mod sync_tests;
pub mod sync;

pub use api::{CloudApiClient, Session};
pub use avatar::{AvatarManager, MAX_AVATAR_BYTES};
pub use models::*;
pub use models::{BUILTIN_PROVIDER_ID, BUILTIN_PROVIDER_NAME, BUILTIN_TITLE_AGENT_ID, DEFAULT_CLOUD_SERVER_URL, ROLE_CHAT, ROLE_TITLE};
pub use sync::{SyncEngine, SyncResult, KV_SERVER_URL, KV_SESSION, KV_SESSION_HOST, KV_USER};
