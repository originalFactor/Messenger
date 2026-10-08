//! One-shot import from the legacy Room database.
//!
//! The legacy file is never touched: the database (plus WAL sidecar, if
//! present) is copied to a staging file next to the new store, the copy is
//! opened read-write (which replays any pending WAL into the copy), and rows
//! are copied column-for-column — the new schema deliberately keeps the
//! Room v20 column names. The `legacy_import_done` kv marker makes the
//! import idempotent.

use std::fs;
use std::path::{Path, PathBuf};

use rusqlite::Connection;

use crate::store::{EntityKind, Store, StoreEvent};
use crate::model::{
    StoredAgent, StoredConversation, StoredMessage, StoredModel, StoredProvider,
};

/// kv key guarding idempotence.
pub const IMPORT_MARKER: &str = "legacy_import_done";
/// Room's legacy database file name (no extension on Android).
pub const LEGACY_DB_FILE: &str = "messenger_database";
/// Room's file name on the JVM targets (`Room.databaseBuilder` appends
/// `.db`), i.e. the Desktop app's `filesDir/databases/messenger_database.db`.
pub const LEGACY_DB_FILE_JVM: &str = "messenger_database.db";

/// Per-table row counts copied by a successful import.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ImportSummary {
    pub providers: usize,
    pub models: usize,
    pub agents: usize,
    pub conversations: usize,
    pub messages: usize,
}

/// Import the legacy Room database at `legacy_dir` (the directory holding
/// `messenger_database` + WAL sidecars) into `store`. Returns `Ok(None)`
/// when the import already ran (marker present) or no legacy DB exists.
pub fn import_legacy(
    store: &Store,
    legacy_dir: &Path,
    now_ms: i64,
) -> Result<Option<ImportSummary>, Box<dyn std::error::Error>> {
    if store.kv_get(IMPORT_MARKER)?.is_some() {
        return Ok(None);
    }
    // Android keeps the extensionless Room file; the JVM targets (Desktop)
    // get `messenger_database.db` appended by `Room.databaseBuilder`.
    let legacy_db = [LEGACY_DB_FILE, LEGACY_DB_FILE_JVM]
        .iter()
        .map(|name| legacy_dir.join(name))
        .find(|path| path.exists());
    let Some(legacy_db) = legacy_db else {
        // Fresh installs: record the marker so we never rescan.
        store.kv_set(IMPORT_MARKER, &format!("none:{now_ms}"))?;
        return Ok(None);
    };

    let staging = staging_copy(&legacy_db, store)?;
    let summary = copy_rows(&staging, store)?;
    fs::remove_file(&staging).ok();
    store.kv_set(IMPORT_MARKER, &format!("v1:{now_ms}"))?;
    // One bulk notification so subscribers refresh everything at once.
    store.notify(StoreEvent { kind: EntityKind::Other, ids: vec!["legacy_import".into()] });
    Ok(Some(summary))
}

/// Copy the legacy DB (+ WAL) next to the store and return the staging path.
fn staging_copy(legacy_db: &Path, store: &Store) -> Result<PathBuf, Box<dyn std::error::Error>> {
    let parent = legacy_db
        .parent()
        .map(Path::to_path_buf)
        .unwrap_or_else(|| PathBuf::from("."));
    let staging = parent.join(format!(
        "messenger_import_staging_{}.db",
        std::process::id()
    ));
    fs::copy(legacy_db, &staging)?;
    for suffix in ["-wal", "-shm"] {
        let sidecar = PathBuf::from(format!("{}{suffix}", legacy_db.display()));
        if sidecar.exists() {
            fs::copy(
                &sidecar,
                PathBuf::from(format!("{}{suffix}", staging.display())),
            )?;
        }
    }
    // Silence the unused warning path when store location differs.
    let _ = store;
    Ok(staging)
}

fn copy_rows(staging: &Path, store: &Store) -> Result<ImportSummary, Box<dyn std::error::Error>> {
    let legacy = Connection::open(staging)?;
    let mut summary = ImportSummary::default();

    store.with_tx(|tx| {
        for p in read_providers(&legacy)? {
            tx.upsert_provider_n(&p)?;
            summary.providers += 1;
        }
        for m in read_models(&legacy)? {
            tx.upsert_model_n(&m)?;
            summary.models += 1;
        }
        for a in read_agents(&legacy)? {
            tx.upsert_agent_n(&a)?;
            summary.agents += 1;
        }
        for c in read_conversations(&legacy)? {
            tx.upsert_conversation_n(&c)?;
            summary.conversations += 1;
        }
        for m in read_messages(&legacy)? {
            tx.upsert_message_n(&m)?;
            summary.messages += 1;
        }
        Ok(())
    })?;
    Ok(summary)
}

// ---------------------------------------------------------------------------
// legacy readers (explicit v20 column lists)
// ---------------------------------------------------------------------------

fn read_providers(legacy: &Connection) -> Result<Vec<StoredProvider>, rusqlite::Error> {
    let mut stmt = legacy.prepare("SELECT id, name, baseUrl, apiKey, createdAt, updatedAt FROM providers")?;
    let rows = stmt.query_map([], |row| {
        Ok(StoredProvider {
            id: row.get(0)?,
            name: row.get(1)?,
            base_url: row.get(2)?,
            api_key: row.get(3)?,
            created_at: row.get(4)?,
            updated_at: row.get(5)?,
        })
    })?;
    rows.collect()
}

fn read_models(legacy: &Connection) -> Result<Vec<StoredModel>, rusqlite::Error> {
    let mut stmt = legacy.prepare(
        "SELECT id, providerId, modelId, displayName, isEnabled, contextWindow, inputRate, outputRate,
                inputModalities, outputModalities, supportsToolCalling, supportsThinking,
                supportsJsonOutput, supportsTemperature, createdAt
         FROM models",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok(StoredModel {
            id: row.get(0)?,
            provider_id: row.get(1)?,
            model_id: row.get(2)?,
            display_name: row.get(3)?,
            is_enabled: row.get::<_, i64>(4)? != 0,
            context_window: row.get(5)?,
            input_rate: row.get(6)?,
            output_rate: row.get(7)?,
            input_modalities: row.get(8)?,
            output_modalities: row.get(9)?,
            supports_tool_calling: row.get::<_, i64>(10)? != 0,
            supports_thinking: row.get::<_, i64>(11)? != 0,
            supports_json_output: row.get::<_, i64>(12)? != 0,
            supports_temperature: row.get::<_, i64>(13)? != 0,
            created_at: row.get(14)?,
        })
    })?;
    rows.collect()
}

fn read_agents(legacy: &Connection) -> Result<Vec<StoredAgent>, rusqlite::Error> {
    let mut stmt = legacy.prepare(
        "SELECT id, name, avatar, systemPrompt, description, defaultModelId, temperature, topP,
                maxTokens, reasoningEffort, isDefault, followDefaultSystemPrompt, followDefaultModel,
                followDefaultTemperature, followDefaultTopP, followDefaultMaxTokens,
                followDefaultReasoningEffort, marketAgentId, marketAgentVersion, marketAgentRole,
                role, toolsEnabled, toolsFollowDefault, toolsConfig, createdAt, updatedAt
         FROM agents",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok(StoredAgent {
            id: row.get(0)?,
            name: row.get(1)?,
            avatar: row.get(2)?,
            system_prompt: row.get(3)?,
            description: row.get(4)?,
            default_model_id: row.get(5)?,
            temperature: row.get(6)?,
            top_p: row.get(7)?,
            max_tokens: row.get(8)?,
            reasoning_effort: row.get(9)?,
            is_default: row.get::<_, i64>(10)? != 0,
            follow_default_system_prompt: row.get::<_, i64>(11)? != 0,
            follow_default_model: row.get::<_, i64>(12)? != 0,
            follow_default_temperature: row.get::<_, i64>(13)? != 0,
            follow_default_top_p: row.get::<_, i64>(14)? != 0,
            follow_default_max_tokens: row.get::<_, i64>(15)? != 0,
            follow_default_reasoning_effort: row.get::<_, i64>(16)? != 0,
            market_agent_id: row.get(17)?,
            market_agent_version: row.get(18)?,
            market_agent_role: row.get(19)?,
            role: row.get(20)?,
            tools_enabled: row.get::<_, i64>(21)? != 0,
            tools_follow_default: row.get::<_, i64>(22)? != 0,
            tools_config: row.get(23)?,
            created_at: row.get(24)?,
            updated_at: row.get(25)?,
        })
    })?;
    rows.collect()
}

fn read_conversations(legacy: &Connection) -> Result<Vec<StoredConversation>, rusqlite::Error> {
    let mut stmt = legacy.prepare(
        "SELECT id, title, providerId, agentId, overrideModelId, overrideTemperature, overrideTopP,
                overrideMaxTokens, overrideReasoningEffort, overrideToolsEnabled, overrideToolsConfig,
                writable, createdAt, updatedAt, lastMessage, reasoningFormat, contextSummary,
                contextSummaryUntil, contextTokens, contextTokensAt
         FROM conversations",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok(StoredConversation {
            id: row.get(0)?,
            title: row.get(1)?,
            provider_id: row.get(2)?,
            agent_id: row.get(3)?,
            override_model_id: row.get(4)?,
            override_temperature: row.get(5)?,
            override_top_p: row.get(6)?,
            override_max_tokens: row.get(7)?,
            override_reasoning_effort: row.get(8)?,
            override_tools_enabled: row.get::<_, Option<i64>>(9)?.map(|v| v != 0),
            override_tools_config: row.get(10)?,
            writable: row.get::<_, i64>(11)? != 0,
            created_at: row.get(12)?,
            updated_at: row.get(13)?,
            last_message: row.get(14)?,
            reasoning_format: row.get(15)?,
            context_summary: row.get(16)?,
            context_summary_until: row.get(17)?,
            context_tokens: row.get(18)?,
            context_tokens_at: row.get(19)?,
        })
    })?;
    rows.collect()
}

fn read_messages(legacy: &Connection) -> Result<Vec<StoredMessage>, rusqlite::Error> {
    let mut stmt = legacy.prepare(
        "SELECT id, conversationId, role, content, partsJson, timestamp, status, errorMessage
         FROM messages",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok(StoredMessage {
            id: row.get(0)?,
            conversation_id: row.get(1)?,
            role: row.get(2)?,
            content: row.get(3)?,
            parts_json: row.get(4)?,
            timestamp: row.get(5)?,
            status: row.get(6)?,
            error_message: row.get(7)?,
        })
    })?;
    rows.collect()
}
