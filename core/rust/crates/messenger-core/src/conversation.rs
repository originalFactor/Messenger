//! Empty-conversation sweep: the domain rule for conversations that were
//! created but never used.
//!
//! Tapping "new chat" inserts a conversation row before any message exists,
//! so backing out without sending leaves an empty row behind — one per tap.
//! This module owns the rule for which of those may be deleted, so every
//! platform (Android/Web through the FFI/wasm boundary, the TUI by linking
//! this crate) shares the exact same policy instead of re-implementing it in
//! its UI layer.
//!
//! Deletion itself stays with the caller: the core has no cloud session, and
//! a delete must also record a tombstone. [`sweep_empty_conversations`] takes
//! a callback so each boundary can hook its own change bookkeeping.

use messenger_store::model::StoredConversation;
use messenger_store::Store;

use crate::title::is_untitled_conversation;

/// Grace period: a conversation younger than this is never swept, however
/// empty it looks. A freshly created chat is still being set up — the user is
/// picking an agent, a model, images — and deleting it from under them would
/// be wrong.
pub const DEFAULT_GRACE_PERIOD_MS: i64 = 5 * 60 * 1000;

/// Whether this conversation may be swept: no messages, still carrying its
/// placeholder title, and older than the grace period.
///
/// A renamed conversation is kept even when empty — renaming is an explicit
/// statement of intent to come back to it. The same untitled test the title
/// generator uses decides that, so the two can never disagree about what
/// "still untitled" means.
///
/// `has_messages` is a closure rather than a `bool` so the caller's message
/// lookup only runs for conversations that already passed the free checks:
/// most rows in a busy list are excluded by their age or title, and none of
/// those should cost a query.
pub fn is_sweepable_empty_conversation(
    conversation: &StoredConversation,
    now_ms: i64,
    grace_period_ms: i64,
    has_messages: impl FnOnce() -> bool,
) -> bool {
    if conversation.updated_at > now_ms - grace_period_ms {
        return false;
    }
    if !is_untitled_conversation(&conversation.title) {
        return false;
    }
    !has_messages()
}

/// Sweeps unused conversations from the store, returning the deleted ids.
///
/// `delete` performs the actual removal (and its side effects — a cloud
/// tombstone marker, an image reap). A failing delete is skipped rather than
/// aborting the sweep: one unwritable row must not strand the rest, and the
/// caller reports only what actually went away.
///
/// `exclude` protects specific ids — the dual-pane layout passes the
/// conversation currently open in the detail pane, which would otherwise be
/// deleted from under the user.
pub fn sweep_empty_conversations(
    store: &Store,
    now_ms: i64,
    grace_period_ms: i64,
    exclude: &[String],
    mut delete: impl FnMut(&str) -> Result<(), String>,
) -> Result<Vec<String>, String> {
    let conversations = store.list_conversations().map_err(|e| e.to_string())?;

    let mut deleted = Vec::new();
    for conversation in &conversations {
        if exclude.iter().any(|id| id == &conversation.id) {
            continue;
        }
        let sweepable = is_sweepable_empty_conversation(conversation, now_ms, grace_period_ms, || {
            // `lastMessage` is written when a message is sent, so a non-blank
            // preview settles the question without touching the messages
            // table. Image-only messages persist an empty preview, hence the
            // fallback.
            match conversation.last_message.as_deref() {
                Some(preview) if !preview.trim().is_empty() => true,
                _ => store
                    .conversation_has_messages(&conversation.id)
                    .unwrap_or(true),
            }
        });
        if !sweepable {
            continue;
        }
        match delete(&conversation.id) {
            Ok(()) => deleted.push(conversation.id.clone()),
            Err(detail) => {
                eprintln!(
                    "messenger-core: skipping empty conversation {}: {detail}",
                    conversation.id
                );
            }
        }
    }
    Ok(deleted)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn conversation(id: &str, title: &str, updated_at: i64, last_message: Option<&str>) -> StoredConversation {
        StoredConversation {
            id: id.into(),
            title: title.into(),
            provider_id: "p1".into(),
            agent_id: "a1".into(),
            last_message: last_message.map(str::to_string),
            updated_at,
            ..Default::default()
        }
    }

    /// In-memory store with the agent row `conversation()` references —
    /// `conversations.agentId` is a foreign key, so the parent must exist.
    fn store_with(rows: &[StoredConversation]) -> Store {
        let store = Store::open_memory().unwrap();
        store
            .upsert_agent(&messenger_store::model::StoredAgent {
                id: "a1".into(),
                name: "agent".into(),
                avatar: None,
                system_prompt: "be helpful".into(),
                description: String::new(),
                default_model_id: None,
                temperature: None,
                top_p: None,
                max_tokens: None,
                reasoning_effort: None,
                is_default: true,
                follow_default_system_prompt: false,
                follow_default_model: false,
                follow_default_temperature: false,
                follow_default_top_p: false,
                follow_default_max_tokens: false,
                follow_default_reasoning_effort: false,
                market_agent_id: None,
                market_agent_version: None,
                market_agent_role: None,
                role: "chat".into(),
                tools_enabled: false,
                tools_follow_default: false,
                tools_config: String::new(),
                created_at: 1,
                updated_at: 1,
            })
            .unwrap();
        for row in rows {
            store.upsert_conversation(row).unwrap();
        }
        store
    }

    const NOW: i64 = 10_000_000;
    /// Comfortably outside the grace period.
    const OLD: i64 = NOW - DEFAULT_GRACE_PERIOD_MS - 1;

    /// The predicate under test, with the message lookup stubbed.
    fn sweepable(title: &str, updated_at: i64, has_messages: bool) -> bool {
        is_sweepable_empty_conversation(
            &conversation("c1", title, updated_at, None),
            NOW,
            DEFAULT_GRACE_PERIOD_MS,
            || has_messages,
        )
    }

    #[test]
    fn sweeps_old_untitled_conversation_without_messages() {
        assert!(sweepable("新对话", OLD, false));
    }

    #[test]
    fn keeps_conversation_inside_grace_period() {
        // A brand-new chat is still being set up; it is empty by definition.
        assert!(!sweepable("新对话", NOW - DEFAULT_GRACE_PERIOD_MS + 1, false));
    }

    #[test]
    fn keeps_conversation_that_has_messages() {
        assert!(!sweepable("新对话", OLD, true));
    }

    #[test]
    fn keeps_renamed_conversation_without_messages() {
        // Renaming states the intent to come back, empty or not.
        assert!(!sweepable("稍后再聊", OLD, false));
    }

    #[test]
    fn keeps_both_untitled_placeholders() {
        for title in ["", "   ", "新对话", "New Chat"] {
            assert!(sweepable(title, OLD, false), "expected {title:?} to be sweepable");
        }
        // Near-misses stay: a real title that merely contains the placeholder.
        for title in ["new chat", "新对话的讨论", "New Chat about Rust"] {
            assert!(!sweepable(title, OLD, false), "expected {title:?} to be kept");
        }
    }

    #[test]
    fn the_message_lookup_only_runs_for_conversations_past_the_cheap_checks() {
        use std::cell::Cell;

        // A fresh chat and a renamed one are both excluded without ever
        // consulting the messages table — that is why the predicate takes a
        // closure instead of a bool.
        let lookups = Cell::new(0);
        let mut count_lookup = || {
            lookups.set(lookups.get() + 1);
            false
        };

        let fresh = conversation("fresh", "新对话", NOW - 1, None);
        let renamed = conversation("renamed", "旅行计划", OLD, None);
        for row in [&fresh, &renamed] {
            assert!(!is_sweepable_empty_conversation(
                row,
                NOW,
                DEFAULT_GRACE_PERIOD_MS,
                &mut count_lookup
            ));
        }
        assert_eq!(lookups.get(), 0, "neither row reached the message lookup");

        // The one row that does pass the cheap checks pays for the lookup.
        let candidate = conversation("c1", "新对话", OLD, None);
        assert!(is_sweepable_empty_conversation(
            &candidate,
            NOW,
            DEFAULT_GRACE_PERIOD_MS,
            &mut count_lookup
        ));
        assert_eq!(lookups.get(), 1);
    }

    #[test]
    fn last_message_preview_short_circuits_the_message_lookup() {
        // c1's preview is non-blank (a text message was sent), c2's is empty
        // (image-only), so c2 forces the sweep to consult the messages table.
        let store = store_with(&[
            conversation("c1", "新对话", OLD, Some("你好")),
            conversation("c2", "新对话", OLD, None),
        ]);
        store
            .upsert_message(&messenger_store::model::StoredMessage {
                id: "m1".into(),
                conversation_id: "c2".into(),
                role: "user".into(),
                content: "hi".into(),
                parts_json: None,
                timestamp: 1,
                status: "sent".into(),
                error_message: None,
            })
            .unwrap();

        let deleted = sweep_empty_conversations(&store, NOW, DEFAULT_GRACE_PERIOD_MS, &[], |id| {
            store.delete_conversation(id).map_err(|e| e.to_string())
        })
        .unwrap();

        assert!(deleted.is_empty(), "both conversations had messages: {deleted:?}");
        assert_eq!(store.list_conversations().unwrap().len(), 2);
    }

    #[test]
    fn sweep_deletes_only_the_unused_ones() {
        let store = store_with(&[
            conversation("stale", "新对话", OLD, None),
            conversation("fresh", "新对话", NOW - 1, None),
            conversation("renamed", "旅行计划", OLD, None),
        ]);

        let deleted = sweep_empty_conversations(&store, NOW, DEFAULT_GRACE_PERIOD_MS, &[], |id| {
            store.delete_conversation(id).map_err(|e| e.to_string())
        })
        .unwrap();

        assert_eq!(deleted, vec!["stale".to_string()]);
        let remaining: Vec<String> = store
            .list_conversations()
            .unwrap()
            .into_iter()
            .map(|c| c.id)
            .collect();
        assert_eq!(remaining.len(), 2);
        assert!(remaining.contains(&"fresh".to_string()));
        assert!(remaining.contains(&"renamed".to_string()));
    }

    #[test]
    fn sweep_honours_the_exclude_list() {
        let store = store_with(&[
            conversation("open", "新对话", OLD, None),
            conversation("stale", "新对话", OLD, None),
        ]);

        let deleted = sweep_empty_conversations(
            &store,
            NOW,
            DEFAULT_GRACE_PERIOD_MS,
            &["open".to_string()],
            |id| store.delete_conversation(id).map_err(|e| e.to_string()),
        )
        .unwrap();

        assert_eq!(deleted, vec!["stale".to_string()]);
        assert!(store.get_conversation("open").unwrap().is_some());
    }

    #[test]
    fn a_failing_delete_does_not_abort_the_sweep() {
        let store = store_with(&[
            conversation("bad", "新对话", OLD, None),
            conversation("good", "新对话", OLD, None),
        ]);

        let mut attempts = Vec::new();
        let deleted = sweep_empty_conversations(&store, NOW, DEFAULT_GRACE_PERIOD_MS, &[], |id| {
            attempts.push(id.to_string());
            if id == "bad" {
                Err("disk on fire".into())
            } else {
                store.delete_conversation(id).map_err(|e| e.to_string())
            }
        })
        .unwrap();

        assert_eq!(attempts.len(), 2);
        assert_eq!(deleted, vec!["good".to_string()]);
        assert!(store.get_conversation("bad").unwrap().is_some());
        assert!(store.get_conversation("good").unwrap().is_none());
    }
}
