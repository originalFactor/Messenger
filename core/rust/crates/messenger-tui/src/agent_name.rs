//! Display names for the auto-created builtin Agents.
//!
//! The seeded rows keep their literal names in the store (`默认 Agent` for the
//! default Agent, `标题生成` for the builtin title generator) — rows are
//! synced / baseline-compared by identity, never by name. The TUI is a
//! single-language client, so it renders those seed literals in the terminal's
//! own language: Chinese when the environment says Chinese, English otherwise.
//! A name the user typed is always shown verbatim.

use std::sync::LazyLock;

use messenger_store::model::StoredAgent;
use messenger_sync::{
    BUILTIN_TITLE_AGENT_ID, BUILTIN_TITLE_AGENT_NAME, DEFAULT_AGENT_NAME, ROLE_TITLE,
};

/// Seed literals of the default Agent: this crate's / the Kotlin clients' seed
/// value plus the historical English one.
const DEFAULT_AGENT_LITERALS: [&str; 2] = [DEFAULT_AGENT_NAME, "Default Agent"];

/// Seed literals of the builtin title generator (this crate's sync engine and
/// the Kotlin clients), plus the historical English one.
const TITLE_AGENT_LITERALS: [&str; 2] = [BUILTIN_TITLE_AGENT_NAME, "Title Generator"];

/// Is the terminal's locale Chinese? Reads the POSIX locale variables the
/// terminal inherits; anything unset or unparsable means "not Chinese".
fn locale_is_chinese() -> bool {
    ["LC_ALL", "LC_MESSAGES", "LANG", "LANGUAGE"]
        .iter()
        .filter_map(|key| std::env::var(key).ok())
        .find(|value| !value.trim().is_empty())
        .map(|value| {
            let value = value.to_ascii_lowercase();
            value.starts_with("zh") || value.starts_with("chinese")
        })
        .unwrap_or(false)
}

/// The locale lookup is cached: this runs for every painted frame.
static CHINESE_LOCALE: LazyLock<bool> = LazyLock::new(locale_is_chinese);

fn chinese_locale() -> bool {
    *CHINESE_LOCALE
}

/// Is this row an unrenamed builtin? (Identity + seed literal, so transferring
/// the title role to a user Agent or renaming a builtin opts out.)
fn is_unrenamed_builtin(agent: &StoredAgent) -> bool {
    let is_title = agent.id == BUILTIN_TITLE_AGENT_ID || agent.role == ROLE_TITLE;
    (agent.is_default && DEFAULT_AGENT_LITERALS.contains(&agent.name.as_str()))
        || (is_title && TITLE_AGENT_LITERALS.contains(&agent.name.as_str()))
}

/// [`display_name`] with the language decided by the caller (testable).
pub fn display_name_for(agent: &StoredAgent, chinese: bool) -> String {
    if !is_unrenamed_builtin(agent) {
        return agent.name.clone();
    }
    let (zh, en) = if agent.is_default {
        ("默认 Agent", "Default Agent")
    } else {
        ("标题生成", "Title Generator")
    };
    if chinese { zh } else { en }.to_string()
}

/// The name to show for an Agent: the localized builtin label when the row is
/// an unrenamed builtin, otherwise the stored name.
pub fn display_name(agent: &StoredAgent) -> String {
    display_name_for(agent, chinese_locale())
}

/// Map a submitted form name back onto the stored one when the user did not
/// actually change it: the Agent form shows the localized label, so saving it
/// untouched must not rewrite the seed literal (a rewrite would read as a
/// rename and drop the localization).
pub fn canonical_name(existing: Option<&StoredAgent>, submitted: &str) -> String {
    let Some(existing) = existing else {
        return submitted.to_string();
    };
    if submitted.trim() == display_name(existing) {
        existing.name.clone()
    } else {
        submitted.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn agent(id: &str, name: &str, role: &str, is_default: bool) -> StoredAgent {
        StoredAgent {
            id: id.into(),
            name: name.into(),
            avatar: None,
            system_prompt: String::new(),
            description: String::new(),
            default_model_id: None,
            temperature: None,
            top_p: None,
            max_tokens: None,
            reasoning_effort: None,
            is_default,
            follow_default_system_prompt: false,
            follow_default_model: false,
            follow_default_temperature: false,
            follow_default_top_p: false,
            follow_default_max_tokens: false,
            follow_default_reasoning_effort: false,
            market_agent_id: None,
            market_agent_version: None,
            market_agent_role: None,
            role: role.into(),
            tools_enabled: false,
            tools_follow_default: false,
            tools_config: String::new(),
            created_at: 0,
            updated_at: 0,
        }
    }

    #[test]
    fn seeded_builtins_follow_the_locale() {
        let default = agent("a1", "默认 Agent", "chat", true);
        assert_eq!(display_name_for(&default, true), "默认 Agent");
        assert_eq!(display_name_for(&default, false), "Default Agent");

        let title = agent(BUILTIN_TITLE_AGENT_ID, "标题生成", ROLE_TITLE, false);
        assert_eq!(display_name_for(&title, true), "标题生成");
        assert_eq!(display_name_for(&title, false), "Title Generator");
    }

    #[test]
    fn transferred_title_role_is_not_localized() {
        // The role moved to a user Agent whose name happens to be the literal.
        let user = agent("user-1", "标题生成", ROLE_TITLE, false);
        assert_eq!(display_name_for(&user, false), "Title Generator");
        // ...and a regular Agent named like the seed stays verbatim.
        let other = agent("user-2", "标题生成", "chat", false);
        assert_eq!(display_name_for(&other, false), "标题生成");
    }

    #[test]
    fn renamed_builtins_are_shown_verbatim() {
        let renamed = agent(BUILTIN_TITLE_AGENT_ID, "My Titler", ROLE_TITLE, false);
        assert_eq!(display_name_for(&renamed, true), "My Titler");
    }

    #[test]
    fn saving_the_localized_label_keeps_the_seed_literal() {
        let title = agent(BUILTIN_TITLE_AGENT_ID, "标题生成", ROLE_TITLE, false);
        let shown = display_name(&title);
        assert_eq!(canonical_name(Some(&title), &shown), "标题生成");
        assert_eq!(canonical_name(Some(&title), "Custom"), "Custom");
        assert_eq!(canonical_name(None, "Brand New"), "Brand New");
    }
}
