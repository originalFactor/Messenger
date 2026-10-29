//! Minimal UniFFI lifecycle bridge for Android and Desktop shell startup.

pub mod core;
pub use core::{CoreError, CoreHandle, HttpServerHandle};

uniffi::setup_scaffolding!();
