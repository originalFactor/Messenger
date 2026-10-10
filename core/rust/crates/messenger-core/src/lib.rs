//! Messenger agent core: persistence codecs, context-window management, and
//! the event-driven agent loop that ties the LLM client, tools, and store
//! together. Everything user-visible stays out of here — errors carry codes,
//! and localized strings are the platform's job.

pub mod agent;
#[cfg(test)]
mod agent_tests;
pub mod context;
pub mod parts;
pub mod title;
pub mod version;
