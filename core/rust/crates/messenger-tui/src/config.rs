//! `settings.toml` load/save plus the CLI/env precedence rules.
//!
//! Precedence (highest first): CLI flag → `MESSENGER_TUI_*` env var →
//! config file → built-in default. The env vars exist so headless tests and
//! scripted runs can pin a scratch store without writing a config file.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

/// The desktop Kotlin host's workspace (`ShellExecutor.desktop.kt` default);
/// keeping the same path means the TUI and the Desktop app share files.
pub const DEFAULT_WORKSPACE: &str = ".messenger/agent-runtime/workspace";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct TuiConfig {
    /// Directory the `terminal`/workspace tools operate in.
    pub workspace_dir: String,
    /// `"dark"` or `"light"`: selects the syntect theme and the palette.
    pub theme: String,
    /// Think blocks expanded by default?
    pub show_think: bool,
    /// Tool card bodies expanded by default?
    pub show_tool_details: bool,
    /// Snap the chat to the newest message while streaming?
    pub auto_scroll: bool,
}

impl Default for TuiConfig {
    fn default() -> Self {
        Self {
            workspace_dir: default_workspace_dir().to_string_lossy().to_string(),
            theme: "dark".into(),
            show_think: false,
            show_tool_details: false,
            auto_scroll: true,
        }
    }
}

impl TuiConfig {
    pub fn is_dark(&self) -> bool {
        !self.theme.eq_ignore_ascii_case("light")
    }

    pub fn workspace_path(&self) -> PathBuf {
        PathBuf::from(&self.workspace_dir)
    }

    /// Persist the current settings (creating parent directories).
    pub fn write(&self, path: &Path) -> std::io::Result<()> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let body = toml::to_string_pretty(self)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e.to_string()))?;
        std::fs::write(path, body)
    }
}

/// `~/.messenger/agent-runtime/workspace`.
pub fn default_workspace_dir() -> PathBuf {
    home_dir().join(DEFAULT_WORKSPACE)
}

/// `~/.messenger/tui/store.db`.
pub fn default_store_path() -> PathBuf {
    home_dir().join(".messenger").join("tui").join("store.db")
}

/// `~/.messenger/files/databases` — `DatabaseBuilder.desktop.kt`'s
/// `filesDir/databases` root.
pub fn default_desktop_db_dir() -> PathBuf {
    home_dir().join(".messenger").join("files").join("databases")
}

/// `~/.messenger/tui/settings.toml`.
pub fn default_config_path() -> PathBuf {
    home_dir().join(".messenger").join("tui").join("settings.toml")
}

/// `dirs::home_dir()` with a current-directory fallback so a stripped
/// environment never yields a relative-to-root surprise.
pub fn home_dir() -> PathBuf {
    dirs::home_dir().unwrap_or_else(|| PathBuf::from("."))
}

/// Load the config, writing the defaults out when the file is missing or
/// unreadable so the user has something to edit.
pub fn load(path: &Path) -> TuiConfig {
    let config = match std::fs::read_to_string(path) {
        Ok(text) => toml::from_str::<TuiConfig>(&text).unwrap_or_default(),
        Err(_) => {
            let defaults = TuiConfig::default();
            let _ = defaults.write(path);
            return defaults;
        }
    };
    config
}
