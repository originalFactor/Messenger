//! The store: one SQLite connection behind a mutex, typed CRUD, change
//! notifications, and the sync/kv bookkeeping tables.

use std::path::Path;
use std::sync::Mutex;

use rusqlite::Connection;

use crate::model::{
    StoredAgent, StoredConversation, StoredMessage, StoredModel, StoredProvider,
};
use crate::schema::{CREATE_TABLES, SCHEMA_VERSION};

/// Which entity a change notification is about.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EntityKind {
    Provider,
    Model,
    Agent,
    Conversation,
    Message,
    Other,
}

/// A store mutation. `ids` lists the affected row ids (may be empty for
/// bulk operations).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoreEvent {
    pub kind: EntityKind,
    pub ids: Vec<String>,
}

type Listener = Box<dyn Fn(&StoreEvent) + Send + Sync>;

/// SQLite-backed store. Sync consumers (UI repositories over FFI) subscribe
/// to [`Store::subscribe`] and re-query on notifications.
pub struct Store {
    conn: Mutex<Connection>,
    listeners: Mutex<Vec<Listener>>,
}

impl Store {
    /// Open (creating or migrating in place) the store at `path`.
    pub fn open(path: &Path) -> Result<Self, rusqlite::Error> {
        let conn = Connection::open(path)?;
        Self::init(conn)
    }

    /// In-memory store for tests.
    pub fn open_memory() -> Result<Self, rusqlite::Error> {
        let conn = Connection::open_in_memory()?;
        Self::init(conn)
    }

    fn init(conn: Connection) -> Result<Self, rusqlite::Error> {
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "foreign_keys", "ON")?;
        for ddl in CREATE_TABLES {
            conn.execute_batch(ddl)?;
        }
        conn.pragma_update(None, "user_version", SCHEMA_VERSION)?;
        Ok(Self {
            conn: Mutex::new(conn),
            listeners: Mutex::new(Vec::new()),
        })
    }

    /// Register a change listener; the returned id can remove it later.
    pub fn subscribe(&self, listener: Listener) -> usize {
        let mut listeners = self.listeners.lock().unwrap();
        listeners.push(listener);
        listeners.len() - 1
    }

    pub fn unsubscribe(&self, id: usize) {
        let mut listeners = self.listeners.lock().unwrap();
        if id < listeners.len() {
            listeners[id] = Box::new(|_| {});
        }
    }

    fn emit(&self, event: StoreEvent) {
        for listener in self.listeners.lock().unwrap().iter() {
            listener(&event);
        }
    }

    fn with_conn<T>(
        &self,
        f: impl FnOnce(&Connection) -> Result<T, rusqlite::Error>,
    ) -> Result<T, rusqlite::Error> {
        let conn = self.conn.lock().unwrap();
        f(&conn)
    }

    // ------------------------------------------------------------------
    // providers
    // ------------------------------------------------------------------

    pub fn list_providers(&self) -> Result<Vec<StoredProvider>, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached("SELECT id, name, baseUrl, apiKey, createdAt, updatedAt FROM providers ORDER BY createdAt")?;
            let rows = stmt.query_map([], map_provider)?;
            rows.collect()
        })
    }

    pub fn get_provider(&self, id: &str) -> Result<Option<StoredProvider>, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached("SELECT id, name, baseUrl, apiKey, createdAt, updatedAt FROM providers WHERE id = ?1")?;
            let mut rows = stmt.query_map([id], map_provider)?;
            rows.next().transpose()
        })
    }

    pub fn upsert_provider(&self, p: &StoredProvider) -> Result<(), rusqlite::Error> {
        self.with_conn(|conn| upsert_provider_sql(conn, p))?;
        self.emit(StoreEvent { kind: EntityKind::Provider, ids: vec![p.id.clone()] });
        Ok(())
    }

    pub fn delete_provider(&self, id: &str) -> Result<(), rusqlite::Error> {
        let deleted = self.with_conn(|conn| conn.execute("DELETE FROM providers WHERE id = ?1", [id]))?;
        if deleted > 0 {
            // Models cascade on the FK; notify them too so UI refreshes.
            let mut ids = Vec::new();
            self.with_conn(|conn| {
                let mut stmt = conn.prepare_cached("SELECT id FROM models WHERE providerId = ?1")?;
                let rows = stmt.query_map([id], |r| r.get::<_, String>(0))?;
                ids.extend(rows.flatten());
                Ok(())
            })?;
            self.emit(StoreEvent { kind: EntityKind::Model, ids });
            self.emit(StoreEvent { kind: EntityKind::Provider, ids: vec![id.to_string()] });
        }
        Ok(())
    }

    // ------------------------------------------------------------------
    // models
    // ------------------------------------------------------------------

    pub fn list_models(&self) -> Result<Vec<StoredModel>, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached(&format!("SELECT {} FROM models ORDER BY createdAt", MODEL_COLUMNS))?;
            let rows = stmt.query_map([], map_model)?;
            rows.collect()
        })
    }

    pub fn list_models_by_provider(&self, provider_id: &str) -> Result<Vec<StoredModel>, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached(&format!(
                "SELECT {MODEL_COLUMNS} FROM models WHERE providerId = ?1 ORDER BY createdAt"
            ))?;
            let rows = stmt.query_map([provider_id], map_model)?;
            rows.collect()
        })
    }

    pub fn get_model(&self, id: &str) -> Result<Option<StoredModel>, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached(&format!("SELECT {MODEL_COLUMNS} FROM models WHERE id = ?1"))?;
            let mut rows = stmt.query_map([id], map_model)?;
            rows.next().transpose()
        })
    }

    pub fn upsert_model(&self, m: &StoredModel) -> Result<(), rusqlite::Error> {
        self.with_conn(|conn| upsert_model_sql(conn, m))?;
        self.emit(StoreEvent { kind: EntityKind::Model, ids: vec![m.id.clone()] });
        Ok(())
    }

    pub fn set_model_enabled(&self, id: &str, enabled: bool) -> Result<(), rusqlite::Error> {
        self.with_conn(|conn| {
            conn.execute("UPDATE models SET isEnabled = ?2 WHERE id = ?1", rusqlite::params![id, enabled as i64])?;
            Ok(())
        })?;
        self.emit(StoreEvent { kind: EntityKind::Model, ids: vec![id.to_string()] });
        Ok(())
    }

    pub fn delete_model(&self, id: &str) -> Result<(), rusqlite::Error> {
        self.with_conn(|conn| conn.execute("DELETE FROM models WHERE id = ?1", [id]))?;
        self.emit(StoreEvent { kind: EntityKind::Model, ids: vec![id.to_string()] });
        Ok(())
    }

    // ------------------------------------------------------------------
    // agents
    // ------------------------------------------------------------------

    pub fn list_agents(&self) -> Result<Vec<StoredAgent>, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached(&format!("SELECT {AGENT_COLUMNS} FROM agents ORDER BY createdAt"))?;
            let rows = stmt.query_map([], map_agent)?;
            rows.collect()
        })
    }

    pub fn get_agent(&self, id: &str) -> Result<Option<StoredAgent>, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached(&format!("SELECT {AGENT_COLUMNS} FROM agents WHERE id = ?1"))?;
            let mut rows = stmt.query_map([id], map_agent)?;
            rows.next().transpose()
        })
    }

    pub fn get_default_agent(&self) -> Result<Option<StoredAgent>, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached(&format!(
                "SELECT {AGENT_COLUMNS} FROM agents WHERE isDefault = 1 LIMIT 1"
            ))?;
            let mut rows = stmt.query_map([], map_agent)?;
            rows.next().transpose()
        })
    }

    pub fn get_title_agent(&self) -> Result<Option<StoredAgent>, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached(&format!(
                "SELECT {AGENT_COLUMNS} FROM agents WHERE role = 'title' LIMIT 1"
            ))?;
            let mut rows = stmt.query_map([], map_agent)?;
            rows.next().transpose()
        })
    }

    pub fn upsert_agent(&self, a: &StoredAgent) -> Result<(), rusqlite::Error> {
        self.with_conn(|conn| upsert_agent_sql(conn, a))?;
        self.emit(StoreEvent { kind: EntityKind::Agent, ids: vec![a.id.clone()] });
        Ok(())
    }

    pub fn delete_agent(&self, id: &str) -> Result<(), rusqlite::Error> {
        self.with_conn(|conn| conn.execute("DELETE FROM agents WHERE id = ?1", [id]))?;
        self.emit(StoreEvent { kind: EntityKind::Agent, ids: vec![id.to_string()] });
        Ok(())
    }

    // ------------------------------------------------------------------
    // conversations
    // ------------------------------------------------------------------

    pub fn list_conversations(&self) -> Result<Vec<StoredConversation>, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached(&format!(
                "SELECT {CONVERSATION_COLUMNS} FROM conversations ORDER BY updatedAt DESC"
            ))?;
            let rows = stmt.query_map([], map_conversation)?;
            rows.collect()
        })
    }

    pub fn list_conversations_by_agent(&self, agent_id: &str) -> Result<Vec<StoredConversation>, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached(&format!(
                "SELECT {CONVERSATION_COLUMNS} FROM conversations WHERE agentId = ?1 ORDER BY updatedAt DESC"
            ))?;
            let rows = stmt.query_map([agent_id], map_conversation)?;
            rows.collect()
        })
    }

    pub fn get_conversation(&self, id: &str) -> Result<Option<StoredConversation>, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached(&format!(
                "SELECT {CONVERSATION_COLUMNS} FROM conversations WHERE id = ?1"
            ))?;
            let mut rows = stmt.query_map([id], map_conversation)?;
            rows.next().transpose()
        })
    }

    pub fn upsert_conversation(&self, c: &StoredConversation) -> Result<(), rusqlite::Error> {
        self.with_conn(|conn| upsert_conversation_sql(conn, c))?;
        self.emit(StoreEvent { kind: EntityKind::Conversation, ids: vec![c.id.clone()] });
        Ok(())
    }

    pub fn update_conversation_last_message(&self, id: &str, last_message: Option<&str>, updated_at: i64) -> Result<(), rusqlite::Error> {
        self.with_conn(|conn| {
            conn.execute(
                "UPDATE conversations SET lastMessage = ?2, updatedAt = ?3 WHERE id = ?1",
                rusqlite::params![id, last_message, updated_at],
            )?;
            Ok(())
        })?;
        self.emit(StoreEvent { kind: EntityKind::Conversation, ids: vec![id.to_string()] });
        Ok(())
    }

    pub fn delete_conversation(&self, id: &str) -> Result<(), rusqlite::Error> {
        self.with_conn(|conn| conn.execute("DELETE FROM conversations WHERE id = ?1", [id]))?;
        self.emit(StoreEvent { kind: EntityKind::Conversation, ids: vec![id.to_string()] });
        Ok(())
    }

    // ------------------------------------------------------------------
    // messages
    // ------------------------------------------------------------------

    pub fn list_messages_by_conversation(&self, conversation_id: &str) -> Result<Vec<StoredMessage>, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached(&format!(
                "SELECT {MESSAGE_COLUMNS} FROM messages WHERE conversationId = ?1 ORDER BY timestamp ASC"
            ))?;
            let rows = stmt.query_map([conversation_id], map_message)?;
            rows.collect()
        })
    }

    pub fn get_message(&self, id: &str) -> Result<Option<StoredMessage>, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached(&format!("SELECT {MESSAGE_COLUMNS} FROM messages WHERE id = ?1"))?;
            let mut rows = stmt.query_map([id], map_message)?;
            rows.next().transpose()
        })
    }

    pub fn upsert_message(&self, m: &StoredMessage) -> Result<(), rusqlite::Error> {
        self.with_conn(|conn| upsert_message_sql(conn, m))?;
        self.emit(StoreEvent { kind: EntityKind::Message, ids: vec![m.id.clone()] });
        Ok(())
    }

    pub fn delete_message(&self, id: &str) -> Result<(), rusqlite::Error> {
        self.with_conn(|conn| conn.execute("DELETE FROM messages WHERE id = ?1", [id]))?;
        self.emit(StoreEvent { kind: EntityKind::Message, ids: vec![id.to_string()] });
        Ok(())
    }

    pub fn delete_messages_by_conversation(&self, conversation_id: &str) -> Result<(), rusqlite::Error> {
        self.with_conn(|conn| conn.execute("DELETE FROM messages WHERE conversationId = ?1", [conversation_id]))?;
        self.emit(StoreEvent { kind: EntityKind::Message, ids: vec![conversation_id.to_string()] });
        Ok(())
    }

    /// Max timestamp among a conversation's messages (for the
    /// strictly-increasing timestamp cursor).
    pub fn max_message_timestamp(&self, conversation_id: &str) -> Result<Option<i64>, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached(
                "SELECT MAX(timestamp) FROM messages WHERE conversationId = ?1",
            )?;
            stmt.query_row([conversation_id], |row| row.get::<_, Option<i64>>(0))
        })
    }

    // ------------------------------------------------------------------
    // kv + sync meta
    // ------------------------------------------------------------------

    pub fn kv_get(&self, key: &str) -> Result<Option<String>, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached("SELECT value FROM kv WHERE key = ?1")?;
            let mut rows = stmt.query_map([key], |r| r.get::<_, String>(0))?;
            rows.next().transpose()
        })
    }

    pub fn kv_set(&self, key: &str, value: &str) -> Result<(), rusqlite::Error> {
        self.with_conn(|conn| {
            conn.execute(
                "INSERT INTO kv (key, value) VALUES (?1, ?2)
                 ON CONFLICT(key) DO UPDATE SET value = ?2",
                [key, value],
            )?;
            Ok(())
        })?;
        self.emit(StoreEvent { kind: EntityKind::Other, ids: vec![key.to_string()] });
        Ok(())
    }

    pub fn kv_delete(&self, key: &str) -> Result<(), rusqlite::Error> {
        self.with_conn(|conn| conn.execute("DELETE FROM kv WHERE key = ?1", [key]))?;
        self.emit(StoreEvent { kind: EntityKind::Other, ids: vec![key.to_string()] });
        Ok(())
    }

    pub fn sync_cursor(&self, account_id: &str) -> Result<i64, rusqlite::Error> {
        self.with_conn(|conn| {
            let mut stmt = conn.prepare_cached("SELECT cursor FROM sync_meta WHERE accountId = ?1")?;
            let mut rows = stmt.query_map([account_id], |r| r.get::<_, i64>(0))?;
            Ok(rows.next().transpose()?.unwrap_or(0))
        })
    }

    pub fn set_sync_meta(&self, account_id: &str, cursor: i64, pending_upserts: &str, pending_deletes: &str) -> Result<(), rusqlite::Error> {
        self.with_conn(|conn| {
            conn.execute(
                "INSERT INTO sync_meta (accountId, cursor, pendingUpserts, pendingDeletes) VALUES (?1, ?2, ?3, ?4)
                 ON CONFLICT(accountId) DO UPDATE SET cursor=?2, pendingUpserts=?3, pendingDeletes=?4",
                rusqlite::params![account_id, cursor, pending_upserts, pending_deletes],
            )?;
            Ok(())
        })?;
        Ok(())
    }
    /// Bulk notification for import-style operations.
    pub fn notify(&self, event: StoreEvent) {
        self.emit(event);
    }

    /// Run `f` inside one SQLite transaction with non-notifying upserts;
    /// the transaction commits automatically when `f` returns `Ok`.
    pub fn with_tx<T>(
        &self,
        f: impl FnOnce(&StoreTx<'_, '_>) -> Result<T, rusqlite::Error>,
    ) -> Result<T, rusqlite::Error> {
        let mut conn = self.conn.lock().unwrap();
        let mut tx = conn.transaction()?;
        let result = {
            let guard = StoreTx { tx: &mut tx };
            f(&guard)?
        };
        tx.commit()?;
        Ok(result)
    }
}

/// Transaction-scoped write access with silent (non-notifying) upserts;
/// used by the legacy import so one bulk event can follow the commit.
pub struct StoreTx<'a, 'conn> {
    tx: &'a mut rusqlite::Transaction<'conn>,
}

impl StoreTx<'_, '_> {
    pub fn upsert_provider_n(&self, p: &StoredProvider) -> Result<(), rusqlite::Error> {
        upsert_provider_sql(self.tx, p)
    }
    pub fn upsert_model_n(&self, m: &StoredModel) -> Result<(), rusqlite::Error> {
        upsert_model_sql(self.tx, m)
    }
    pub fn upsert_agent_n(&self, a: &StoredAgent) -> Result<(), rusqlite::Error> {
        upsert_agent_sql(self.tx, a)
    }
    pub fn upsert_conversation_n(&self, c: &StoredConversation) -> Result<(), rusqlite::Error> {
        upsert_conversation_sql(self.tx, c)
    }
    pub fn upsert_message_n(&self, m: &StoredMessage) -> Result<(), rusqlite::Error> {
        upsert_message_sql(self.tx, m)
    }
}

// ---------------------------------------------------------------------------
// column lists & helpers
// ---------------------------------------------------------------------------

const MODEL_COLUMNS: &str = "id, providerId, modelId, displayName, isEnabled, contextWindow, inputRate, outputRate, inputModalities, outputModalities, supportsToolCalling, supportsThinking, supportsJsonOutput, supportsTemperature, createdAt";

const AGENT_COLUMNS: &str = "id, name, avatar, systemPrompt, description, defaultModelId, temperature, topP, maxTokens, reasoningEffort, isDefault, followDefaultSystemPrompt, followDefaultModel, followDefaultTemperature, followDefaultTopP, followDefaultMaxTokens, followDefaultReasoningEffort, marketAgentId, marketAgentVersion, marketAgentRole, role, toolsEnabled, toolsFollowDefault, toolsConfig, createdAt, updatedAt";
const AGENT_PLACEHOLDERS: &str = "?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20, ?21, ?22, ?23, ?24, ?25, ?26";
const AGENT_UPDATES: &str = "name=?2, avatar=?3, systemPrompt=?4, description=?5, defaultModelId=?6, temperature=?7, topP=?8, maxTokens=?9, reasoningEffort=?10, isDefault=?11, followDefaultSystemPrompt=?12, followDefaultModel=?13, followDefaultTemperature=?14, followDefaultTopP=?15, followDefaultMaxTokens=?16, followDefaultReasoningEffort=?17, marketAgentId=?18, marketAgentVersion=?19, marketAgentRole=?20, role=?21, toolsEnabled=?22, toolsFollowDefault=?23, toolsConfig=?24, createdAt=?25, updatedAt=?26";

const CONVERSATION_COLUMNS: &str = "id, title, providerId, agentId, overrideModelId, overrideTemperature, overrideTopP, overrideMaxTokens, overrideReasoningEffort, overrideToolsEnabled, overrideToolsConfig, writable, createdAt, updatedAt, lastMessage, reasoningFormat, contextSummary, contextSummaryUntil, contextTokens, contextTokensAt";
const CONVERSATION_PLACEHOLDERS: &str = "?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20";
const CONVERSATION_UPDATES: &str = "title=?2, providerId=?3, agentId=?4, overrideModelId=?5, overrideTemperature=?6, overrideTopP=?7, overrideMaxTokens=?8, overrideReasoningEffort=?9, overrideToolsEnabled=?10, overrideToolsConfig=?11, writable=?12, createdAt=?13, updatedAt=?14, lastMessage=?15, reasoningFormat=?16, contextSummary=?17, contextSummaryUntil=?18, contextTokens=?19, contextTokensAt=?20";

const MESSAGE_COLUMNS: &str = "id, conversationId, role, content, partsJson, timestamp, status, errorMessage";
const MESSAGE_PLACEHOLDERS: &str = "?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8";
const MESSAGE_UPDATES: &str = "conversationId=?2, role=?3, content=?4, partsJson=?5, timestamp=?6, status=?7, errorMessage=?8";

fn map_provider(row: &rusqlite::Row<'_>) -> Result<StoredProvider, rusqlite::Error> {
    Ok(StoredProvider {
        id: row.get(0)?,
        name: row.get(1)?,
        base_url: row.get(2)?,
        api_key: row.get(3)?,
        created_at: row.get(4)?,
        updated_at: row.get(5)?,
    })
}

fn map_model(row: &rusqlite::Row<'_>) -> Result<StoredModel, rusqlite::Error> {
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
}

fn map_agent(row: &rusqlite::Row<'_>) -> Result<StoredAgent, rusqlite::Error> {
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
}

fn agent_params(a: &StoredAgent) -> Result<Vec<&dyn rusqlite::ToSql>, rusqlite::Error> {
    Ok(vec![
        &a.id, &a.name, &a.avatar, &a.system_prompt, &a.description, &a.default_model_id,
        &a.temperature, &a.top_p, &a.max_tokens, &a.reasoning_effort, &a.is_default,
        &a.follow_default_system_prompt, &a.follow_default_model, &a.follow_default_temperature,
        &a.follow_default_top_p, &a.follow_default_max_tokens, &a.follow_default_reasoning_effort,
        &a.market_agent_id, &a.market_agent_version, &a.market_agent_role, &a.role,
        &a.tools_enabled, &a.tools_follow_default, &a.tools_config, &a.created_at, &a.updated_at,
    ])
}

fn map_conversation(row: &rusqlite::Row<'_>) -> Result<StoredConversation, rusqlite::Error> {
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
}

fn conversation_params(c: &StoredConversation) -> Result<Vec<&dyn rusqlite::ToSql>, rusqlite::Error> {
    Ok(vec![
        &c.id, &c.title, &c.provider_id, &c.agent_id, &c.override_model_id, &c.override_temperature,
        &c.override_top_p, &c.override_max_tokens, &c.override_reasoning_effort,
        &c.override_tools_enabled, &c.override_tools_config, &c.writable, &c.created_at,
        &c.updated_at, &c.last_message, &c.reasoning_format, &c.context_summary,
        &c.context_summary_until, &c.context_tokens, &c.context_tokens_at,
    ])
}

fn map_message(row: &rusqlite::Row<'_>) -> Result<StoredMessage, rusqlite::Error> {
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
}

fn message_params(m: &StoredMessage) -> Result<Vec<&dyn rusqlite::ToSql>, rusqlite::Error> {
    Ok(vec![
        &m.id, &m.conversation_id, &m.role, &m.content, &m.parts_json, &m.timestamp, &m.status,
        &m.error_message,
    ])
}

// ---------------------------------------------------------------------------
// raw upsert SQL (shared by the Store methods and the transactional import)
// ---------------------------------------------------------------------------

fn upsert_provider_sql(conn: &Connection, p: &StoredProvider) -> Result<(), rusqlite::Error> {
    conn.execute(
        "INSERT INTO providers (id, name, baseUrl, apiKey, createdAt, updatedAt)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)
         ON CONFLICT(id) DO UPDATE SET name=?2, baseUrl=?3, apiKey=?4, updatedAt=?6",
        rusqlite::params![p.id, p.name, p.base_url, p.api_key, p.created_at, p.updated_at],
    )?;
    Ok(())
}

fn upsert_model_sql(conn: &Connection, m: &StoredModel) -> Result<(), rusqlite::Error> {
    conn.execute(
        "INSERT INTO models (id, providerId, modelId, displayName, isEnabled, contextWindow, inputRate, outputRate, inputModalities, outputModalities, supportsToolCalling, supportsThinking, supportsJsonOutput, supportsTemperature, createdAt)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)
         ON CONFLICT(id) DO UPDATE SET providerId=?2, modelId=?3, displayName=?4, isEnabled=?5, contextWindow=?6, inputRate=?7, outputRate=?8, inputModalities=?9, outputModalities=?10, supportsToolCalling=?11, supportsThinking=?12, supportsJsonOutput=?13, supportsTemperature=?14",
        rusqlite::params![
            m.id, m.provider_id, m.model_id, m.display_name, m.is_enabled as i64,
            m.context_window, m.input_rate, m.output_rate, m.input_modalities,
            m.output_modalities, m.supports_tool_calling as i64, m.supports_thinking as i64,
            m.supports_json_output as i64, m.supports_temperature as i64, m.created_at
        ],
    )?;
    Ok(())
}

fn upsert_agent_sql(conn: &Connection, a: &StoredAgent) -> Result<(), rusqlite::Error> {
    conn.execute(
        &format!(
            "INSERT INTO agents ({AGENT_COLUMNS}) VALUES ({AGENT_PLACEHOLDERS})
             ON CONFLICT(id) DO UPDATE SET {AGENT_UPDATES}"
        ),
        agent_params(a)?.as_slice(),
    )?;
    Ok(())
}

fn upsert_conversation_sql(conn: &Connection, c: &StoredConversation) -> Result<(), rusqlite::Error> {
    conn.execute(
        &format!(
            "INSERT INTO conversations ({CONVERSATION_COLUMNS}) VALUES ({CONVERSATION_PLACEHOLDERS})
             ON CONFLICT(id) DO UPDATE SET {CONVERSATION_UPDATES}"
        ),
        conversation_params(c)?.as_slice(),
    )?;
    Ok(())
}

fn upsert_message_sql(conn: &Connection, m: &StoredMessage) -> Result<(), rusqlite::Error> {
    conn.execute(
        &format!(
            "INSERT INTO messages ({MESSAGE_COLUMNS}) VALUES ({MESSAGE_PLACEHOLDERS})
             ON CONFLICT(id) DO UPDATE SET {MESSAGE_UPDATES}"
        ),
        message_params(m)?.as_slice(),
    )?;
    Ok(())
}
