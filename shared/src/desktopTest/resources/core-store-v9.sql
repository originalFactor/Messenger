-- Snapshot of Core schema v9, keep as a historical migration fixture.
CREATE TABLE IF NOT EXISTS providers (
        id TEXT PRIMARY KEY NOT NULL,
        name TEXT NOT NULL,
        baseUrl TEXT NOT NULL,
        apiKey TEXT NOT NULL,
        apiFormat TEXT NOT NULL DEFAULT 'chat',
        nativeWebSearch INTEGER NOT NULL DEFAULT 0,
        createdAt INTEGER NOT NULL,
        updatedAt INTEGER NOT NULL
    );
CREATE TABLE IF NOT EXISTS models (
        id TEXT PRIMARY KEY NOT NULL,
        providerId TEXT NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
        modelId TEXT NOT NULL,
        displayName TEXT NOT NULL,
        isEnabled INTEGER NOT NULL DEFAULT 1,
        contextWindow INTEGER NOT NULL DEFAULT 0,
        inputRate REAL,
        outputRate REAL,
        cacheReadRate REAL,
        cacheWriteRate REAL,
        inputModalities TEXT NOT NULL DEFAULT 'text',
        outputModalities TEXT NOT NULL DEFAULT 'text',
        supportsToolCalling INTEGER NOT NULL DEFAULT 0,
        supportsThinking INTEGER NOT NULL DEFAULT 0,
        supportsJsonOutput INTEGER NOT NULL DEFAULT 0,
        supportsTemperature INTEGER NOT NULL DEFAULT 0,
        createdAt INTEGER NOT NULL,
        metadata TEXT,
        metadataOverrides TEXT
    );
CREATE INDEX IF NOT EXISTS index_models_providerId ON models(providerId);
CREATE TABLE IF NOT EXISTS agents (
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
    );
CREATE TABLE IF NOT EXISTS projects (
        id TEXT PRIMARY KEY NOT NULL,
        name TEXT NOT NULL,
        workspace TEXT NOT NULL,
        deviceId TEXT,
        createdAt INTEGER NOT NULL,
        updatedAt INTEGER NOT NULL
    );
CREATE TABLE IF NOT EXISTS conversations (
        id TEXT PRIMARY KEY NOT NULL,
        title TEXT NOT NULL,
        providerId TEXT NOT NULL,
        agentId TEXT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
        projectId TEXT REFERENCES projects(id) ON DELETE SET NULL,
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
    );
CREATE INDEX IF NOT EXISTS index_conversations_agentId ON conversations(agentId);
CREATE TABLE IF NOT EXISTS messages (
        id TEXT PRIMARY KEY NOT NULL,
        conversationId TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
        role TEXT NOT NULL,
        content TEXT NOT NULL,
        partsJson TEXT,
        timestamp INTEGER NOT NULL,
        status TEXT NOT NULL,
        errorMessage TEXT
    );
CREATE INDEX IF NOT EXISTS index_messages_conversationId ON messages(conversationId);
CREATE INDEX IF NOT EXISTS index_messages_conversation_timestamp ON messages(conversationId, timestamp);
CREATE TABLE IF NOT EXISTS sync_meta (
        accountId TEXT PRIMARY KEY NOT NULL,
        cursor INTEGER NOT NULL DEFAULT 0,
        pendingUpserts TEXT NOT NULL DEFAULT '',
        pendingDeletes TEXT NOT NULL DEFAULT ''
    );
CREATE TABLE IF NOT EXISTS kv (
        key TEXT PRIMARY KEY NOT NULL,
        value TEXT NOT NULL
    );

CREATE TABLE IF NOT EXISTS learning_records (
 id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, version INTEGER NOT NULL,
 course_id TEXT, title TEXT NOT NULL, archived INTEGER NOT NULL, updated_at INTEGER NOT NULL, body TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS learning_kind_course ON learning_records(kind, course_id, updated_at);
CREATE TABLE IF NOT EXISTS learning_revisions (
 item_id TEXT NOT NULL, version INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(item_id, version)
);
CREATE TABLE IF NOT EXISTS learning_review_states (
 card_id TEXT PRIMARY KEY NOT NULL REFERENCES learning_records(id) ON DELETE CASCADE, body TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS learning_review_attempts (
 submission_id TEXT PRIMARY KEY NOT NULL, card_id TEXT NOT NULL,
 created_at INTEGER NOT NULL, body TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS learning_attempt_card ON learning_review_attempts(card_id, created_at);

PRAGMA user_version = 9;
