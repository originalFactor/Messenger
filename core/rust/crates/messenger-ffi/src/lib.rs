//! Messenger Rust Core — UniFFI boundary.
//!
//! `core` exposes the real store/agent surface; `lib.rs` keeps the M0
//! echo/ping demo used by the walking-skeleton probe.

mod core;
pub use core::{
    CoreError, CoreHandle, ConversationSummary, MessageRow, PlatformToolHost, StoreChangeEvent,
    StoreChangeListener, ToolResultFfi, TurnConfig, TurnEventSink,
};

use std::time::Duration;

uniffi::setup_scaffolding!();

/// Liveness probe for the bindings loader.
#[uniffi::export]
pub fn core_ping() -> String {
    "pong".to_string()
}

/// Version of the Rust core, reported to the app shell for diagnostics.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Events the Agent Runtime emits. M0 carries a placeholder subset; the full
/// set (tool calls, reasoning, usage, errors) arrives with M1.
#[derive(uniffi::Enum)]
pub enum AgentEvent {
    TextDelta { round: u32, text: String },
    Finished,
}

/// Foreign sink for [`AgentEvent`]s. Must be `Send + Sync` so the runtime can
/// emit from worker threads once the async runtime lands in M1.
#[uniffi::export(callback_interface)]
pub trait AgentEventSink: Send + Sync {
    fn on_event(&self, event: AgentEvent);
}

/// M0 demo: emit `count` synthetic deltas 30 ms apart, then `Finished`.
/// Synchronous on purpose — it proves cross-FFI upcalls work; the batched
/// async runtime replaces this in M1.
#[uniffi::export]
pub fn echo_events(count: u32, sink: Box<dyn AgentEventSink>) {
    for i in 0..count {
        sink.on_event(AgentEvent::TextDelta {
            round: 0,
            text: format!("token-{i} "),
        });
        std::thread::sleep(Duration::from_millis(30));
    }
    sink.on_event(AgentEvent::Finished);
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    #[test]
    fn ping_round_trips() {
        assert_eq!(core_ping(), "pong");
    }

    #[test]
    fn echo_emits_expected_events() {
        let events: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
        echo_events(3, Box::new(RecordingSink(Arc::clone(&events))));
        let recorded = events.lock().unwrap().clone();
        assert_eq!(recorded.len(), 4);
        assert_eq!(recorded.last().unwrap(), "finished");
    }

    struct RecordingSink(Arc<Mutex<Vec<String>>>);

    impl AgentEventSink for RecordingSink {
        fn on_event(&self, event: AgentEvent) {
            let label = match event {
                AgentEvent::TextDelta { text, .. } => text,
                AgentEvent::Finished => "finished".to_string(),
            };
            self.0.lock().unwrap().push(label);
        }
    }
}
