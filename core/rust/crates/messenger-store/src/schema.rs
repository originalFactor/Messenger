//! DDL for schema version 1. Column names intentionally mirror the legacy
//! Room v20 schema so the import can copy rows verbatim.

pub const SCHEMA_VERSION: i64 = 1;

pub const CREATE_TABLES: &[&str] = &[
    "CREATE TABLE IF NOT EXISTS providers (
        id TEXT PRIMARY KEY NOT NULL,
        name TEXT NOT NULL,
        baseUrl TEXT NOT NULL,
        apiKey TEXT NOT NULL,
        createdAt INTEGER NOT NULL,
        updatedAt INTEGER NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS models (
        id TEXT PRIMARY KEY NOT NULL,
        providerId TEXT NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
        modelId TEXT NOT NULL,
        displayName TEXT NOT NULL,
        isEnabled INTEGER NOT NULL DEFAULT 1,
        contextWindow INTEGER NOT NULL DEFAULT 0,
        inputRate REAL,
        outputRate REAL,
        inputModalities TEXT NOT NULL DEFAULT 'text',
        outputModalities TEXT NOT NULL DEFAULT 'text',
        supportsToolCalling INTEGER NOT NULL DEFAULT 0,
        supportsThinking INTEGER NOT NULL DEFAULT 0,
        supportsJsonOutput INTEGER NOT NULL DEFAULT 0,
        supportsTemperature INTEGER NOT NULL DEFAULT 0,
        createdAt INTEGER NOT NULL
    )",
    "CREATE INDEX IF NOT EXISTS index_models_providerId ON models(providerId)",
    "CREATE TABLE IF NOT EXISTS agents (
        id TEXT PRIMARY KEY NOT NULL,
        name TEXT NOT NULL,
        avatar TEXT,
        systemPrompt TEXT NOT NULL,
        description TEXT NOT NULL DEFAULT '',
        defaultModelId TEXT,
        temperature REAL,
        topP REAL,
        maxTokens INTEGER,
        reasoningEffort TEXT,
        isDefault INTEGER NOT NULL DEFAULT 0,
        followDefaultSystemPrompt INTEGER NOT NULL DEFAULT 0,
        followDefaultModel INTEGER NOT NULL DEFAULT 0,
        followDefaultTemperature INTEGER NOT NULL DEFAULT 0,
        followDefaultTopP INTEGER NOT NULL DEFAULT 0,
        followDefaultMaxTokens INTEGER NOT NULL DEFAULT 0,
        followDefaultReasoningEffort INTEGER NOT NULL DEFAULT 0,
        marketAgentId TEXT,
        marketAgentVersion INTEGER,
        marketAgentRole TEXT,
        role TEXT NOT NULL DEFAULT 'chat',
        toolsEnabled INTEGER NOT NULL DEFAULT 0,
        toolsFollowDefault INTEGER NOT NULL DEFAULT 0,
        toolsConfig TEXT NOT NULL DEFAULT '',
        createdAt INTEGER NOT NULL,
        updatedAt INTEGER NOT NULL
    )",
    "CREATE TABLE IF NOT EXISTS conversations (
        id TEXT PRIMARY KEY NOT NULL,
        title TEXT NOT NULL,
        providerId TEXT NOT NULL,
        agentId TEXT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
        overrideModelId TEXT,
        overrideTemperature REAL,
        overrideTopP REAL,
        overrideMaxTokens INTEGER,
        overrideReasoningEffort TEXT,
        overrideToolsEnabled INTEGER,
        overrideToolsConfig TEXT,
        writable INTEGER NOT NULL DEFAULT 0,
        createdAt INTEGER NOT NULL,
        updatedAt INTEGER NOT NULL,
        lastMessage TEXT,
        reasoningFormat TEXT,
        contextSummary TEXT,
        contextSummaryUntil INTEGER NOT NULL DEFAULT 0,
        contextTokens INTEGER NOT NULL DEFAULT 0,
        contextTokensAt INTEGER NOT NULL DEFAULT 0
    )",
    "CREATE INDEX IF NOT EXISTS index_conversations_agentId ON conversations(agentId)",
    "CREATE TABLE IF NOT EXISTS messages (
        id TEXT PRIMARY KEY NOT NULL,
        conversationId TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
        role TEXT NOT NULL,
        content TEXT NOT NULL,
        partsJson TEXT,
        timestamp INTEGER NOT NULL,
        status TEXT NOT NULL,
        errorMessage TEXT
    )",
    "CREATE INDEX IF NOT EXISTS index_messages_conversationId ON messages(conversationId)",
    "CREATE TABLE IF NOT EXISTS sync_meta (
        accountId TEXT PRIMARY KEY NOT NULL,
        cursor INTEGER NOT NULL DEFAULT 0,
        pendingUpserts TEXT NOT NULL DEFAULT '',
        pendingDeletes TEXT NOT NULL DEFAULT ''
    )",
    "CREATE TABLE IF NOT EXISTS kv (
        key TEXT PRIMARY KEY NOT NULL,
        value TEXT NOT NULL
    )",
];
