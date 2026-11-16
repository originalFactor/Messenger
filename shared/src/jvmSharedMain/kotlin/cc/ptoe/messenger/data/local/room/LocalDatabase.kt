/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0. See https://www.apache.org/licenses/LICENSE-2.0
 */
package cc.ptoe.messenger.data.local.room

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** New database for Kotlin ownership. Import from a consistent Core snapshot before use. */
@Database(entities = [ProviderRow::class, ModelRow::class, AgentRow::class, ProjectRow::class,
    ConversationRow::class, MessageRow::class, SyncMetaRow::class, KeyValueRow::class,
    LearningRecordRow::class, LearningRevisionRow::class, LearningReviewStateRow::class,
    LearningReviewAttemptRow::class], version = 1, exportSchema = false)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun rows(): LocalRowsDao
    internal abstract fun cloudSync(): CloudSyncDao
    internal abstract fun learningReview(): LearningReviewDao
    internal abstract fun assetMetadata(): CoreAssetMetadataDao
    internal abstract fun learningBundle(): LearningBundleDao
    internal abstract fun imageJobs(): ImageJobDao
}

/** Low-level rows for migration and future repositories; business invariants belong above this DAO. */
@Dao
interface LocalRowsDao {
    @Query("SELECT * FROM providers ORDER BY createdAt, id") fun observeProviders(): Flow<List<ProviderRow>>
    @Query("SELECT * FROM agents ORDER BY createdAt, id") fun observeAgents(): Flow<List<AgentRow>>
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC, id") fun observeProjects(): Flow<List<ProjectRow>>
    @Query("SELECT * FROM agents WHERE role != 'title' ORDER BY createdAt, id LIMIT 100") suspend fun controlAgents(): List<AgentRow>
    @Query("SELECT * FROM agents WHERE role != 'title' ORDER BY createdAt, id") suspend fun delegatableAgents(): List<AgentRow>
    @Query("SELECT * FROM projects WHERE deviceId IS NULL OR deviceId = :deviceId ORDER BY updatedAt DESC, id LIMIT 100") suspend fun controlProjects(deviceId: String): List<ProjectRow>
    @Query("SELECT conversations.* FROM conversations INNER JOIN projects ON conversations.projectId = projects.id WHERE projects.deviceId IS NULL OR projects.deviceId = :deviceId ORDER BY conversations.updatedAt DESC, conversations.id LIMIT 100")
    suspend fun controlConversations(deviceId: String): List<ConversationRow>
    @Query("SELECT * FROM conversations WHERE agentId = :agentId ORDER BY updatedAt DESC, id") fun observeConversations(agentId: String): Flow<List<ConversationRow>>
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp, rowid") fun observeMessages(conversationId: String): Flow<List<MessageRow>>
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId") fun observeMessageCount(conversationId: String): Flow<Int>
    @Query("SELECT * FROM agents WHERE id = :id") fun observeAgent(id: String): Flow<AgentRow?>
    @Query("SELECT * FROM agents WHERE isDefault = 1 LIMIT 1") fun observeDefaultAgent(): Flow<AgentRow?>
    @Query("SELECT * FROM agents WHERE id = :id") suspend fun agent(id: String): AgentRow?
    @Query("SELECT * FROM agents WHERE isDefault = 1 ORDER BY rowid") suspend fun defaultAgents(): List<AgentRow>
    @Query("UPDATE agents SET isDefault = 0 WHERE isDefault = 1") suspend fun clearDefaults()
    @Query("UPDATE agents SET isDefault = 1 WHERE id = :id") suspend fun markDefault(id: String)
    @Query("DELETE FROM agents WHERE id = :id") suspend fun deleteAgentRow(id: String): Int
    @Query("SELECT COUNT(*) FROM agents WHERE role = 'title'") suspend fun titleAgentCount(): Int
    @Query("SELECT * FROM agents WHERE role = 'title' ORDER BY rowid LIMIT 1") suspend fun titleAgent(): AgentRow?
    /** Call after creating or importing a database and before exposing repositories. */
    @Transaction
    suspend fun ensureDefaultAgent(seed: AgentRow): AgentRow {
        check(getValue(CLOUD_REPLACEMENT_LOCK_KEY) == null) { "Cloud replacement requires completion or recovery" }
        require(seed.isDefault) { "Default agent seed must be marked default" }
        val defaults = defaultAgents()
        if (defaults.size == 1) return defaults.single()
        val chosen = defaults.firstOrNull() ?: agent(seed.id) ?: seed
        clearDefaults()
        if (agent(chosen.id) == null) upsertAgents(listOf(chosen.copy(isDefault = true)))
        else markDefault(chosen.id)
        return checkNotNull(agent(chosen.id))
    }
    @Transaction
    suspend fun upsertAgentPreservingDefault(row: AgentRow): List<AgentRow> {
        val defaults = defaultAgents()
        require(row.isDefault || defaults.size != 1 || defaults.single().id != row.id) { "Exactly one default agent is required" }
        val chosen = if (row.isDefault) row.id else defaults.firstOrNull()?.id ?: row.id
        clearDefaults()
        upsertAgents(listOf(row.copy(isDefault = row.id == chosen)))
        if (chosen != row.id) markDefault(chosen)
        return defaults.filter { it.id != chosen }
    }
    @Transaction
    suspend fun deleteAgentPreservingDefault(id: String, replacementTitle: AgentRow): Boolean {
        val existing = agent(id) ?: return false
        if (existing.isDefault) return false
        deleteAgentRow(id)
        if ((existing.role == "title" || id == replacementTitle.id) && titleAgentCount() == 0 && agent(replacementTitle.id) == null) {
            upsertAgents(listOf(replacementTitle))
        }
        return true
    }
    @Query("SELECT * FROM models ORDER BY createdAt, id") fun observeModels(): Flow<List<ModelRow>>
    @Query("SELECT * FROM models WHERE id = :id") fun observeModel(id: String): Flow<ModelRow?>
    @Query("SELECT * FROM models WHERE id = :id") suspend fun model(id: String): ModelRow?
    @Query("DELETE FROM models WHERE id IN (:ids)") suspend fun deleteModels(ids: List<String>)
    @Query("UPDATE models SET isEnabled = :enabled WHERE id IN (:ids)") suspend fun setModelsEnabled(ids: List<String>, enabled: Boolean)
    @Transaction
    suspend fun upsertModelsPreservingMetadata(newRows: List<ModelRow>) {
        val merged = newRows.map { row ->
            val old = model(row.id)
            row.copy(metadata = old?.metadata ?: row.metadata, metadataOverrides = old?.metadataOverrides ?: row.metadataOverrides)
        }
        upsertModels(merged)
    }
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC, id") fun observeAllConversations(): Flow<List<ConversationRow>>
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun conversation(id: String): ConversationRow?
    @Query("SELECT * FROM conversations WHERE id = :id") fun observeConversation(id: String): Flow<ConversationRow?>
    @Query("SELECT id FROM conversations WHERE projectId = :id") suspend fun conversationIdsForProject(id: String): List<String>
    @Query("SELECT id FROM conversations WHERE agentId = :id") suspend fun conversationIdsForAgent(id: String): List<String>
    @Query("SELECT * FROM conversations WHERE updatedAt <= :cutoff") suspend fun conversationsBefore(cutoff: Long): List<ConversationRow>
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :id") suspend fun messageCount(id: String): Int
    @Query("UPDATE conversations SET lastMessage = :message, updatedAt = :updatedAt WHERE id = :id") suspend fun updateLastMessage(id: String, message: String, updatedAt: Long)
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun deleteConversation(id: String)
    @Transaction
    suspend fun sweepEmptyConversations(cutoff: Long, excluded: List<String>): List<String> {
        val deleted = mutableListOf<String>()
        for (row in conversationsBefore(cutoff)) {
            if (row.id in excluded || !row.lastMessage.isNullOrBlank()) continue
            if (row.title.isNotBlank() && row.title != "新对话" && row.title != "New Chat") continue
            if (messageCount(row.id) != 0) continue
            deleteConversation(row.id)
            deleted.add(row.id)
        }
        return deleted
    }
    @Query("SELECT * FROM messages WHERE conversationId IN (:ids) ORDER BY timestamp, rowid") suspend fun messagesFor(ids: List<String>): List<MessageRow>
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY timestamp DESC, rowid DESC LIMIT :limit")
    suspend fun recentMessages(id: String, limit: Int): List<MessageRow>
    @Query("SELECT * FROM messages WHERE conversationId = :id AND (timestamp, rowid) < (SELECT timestamp, rowid FROM messages WHERE conversationId = :id AND id = :beforeId) ORDER BY timestamp DESC, rowid DESC LIMIT :limit")
    suspend fun messagesBefore(id: String, beforeId: String, limit: Int): List<MessageRow>
    @Query("SELECT * FROM messages WHERE conversationId = :id AND (timestamp, rowid) >= (SELECT timestamp, rowid FROM messages WHERE conversationId = :id AND id = :fromId) ORDER BY timestamp, rowid")
    suspend fun messagesFrom(id: String, fromId: String): List<MessageRow>
    @Query("SELECT MAX(timestamp) FROM messages WHERE conversationId = :id") suspend fun latestMessageTimestamp(id: String): Long?
    @Query("SELECT * FROM messages WHERE id = :id") suspend fun message(id: String): MessageRow?
    @Transaction
    suspend fun upsertMessagesPreservingUnknownParts(newRows: List<MessageRow>) {
        val merged = newRows.map { row ->
            val old = message(row.id)
            if (old != null && cc.ptoe.messenger.data.local.ContentPartCodec.decode(old.partsJson) == cc.ptoe.messenger.data.local.ContentPartCodec.decode(row.partsJson)) row.copy(partsJson = old.partsJson) else row
        }
        upsertMessages(merged)
    }
    @Query("DELETE FROM messages WHERE id = :id") suspend fun deleteMessage(id: String)
    @Query("DELETE FROM messages WHERE conversationId = :id") suspend fun deleteMessagesFor(id: String)
    @Query("SELECT * FROM providers WHERE id = :id") suspend fun provider(id: String): ProviderRow?
    @Query("DELETE FROM kv WHERE `key` = :key") suspend fun deleteValue(key: String)
    @Query("SELECT * FROM providers WHERE id = :id") fun observeProvider(id: String): Flow<ProviderRow?>
    @Query("SELECT * FROM projects WHERE id = :id") fun observeProject(id: String): Flow<ProjectRow?>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun project(id: String): ProjectRow?
    @Query("DELETE FROM providers WHERE id = :id") suspend fun deleteProvider(id: String)
    @Query("DELETE FROM projects WHERE id = :id") suspend fun deleteProject(id: String)
    @Transaction
    suspend fun upsertProjectPreservingDevice(row: ProjectRow) {
        val existing = project(row.id)
        upsertProjects(listOf(row.copy(deviceId = existing?.deviceId ?: row.deviceId)))
    }
    @Query("SELECT * FROM kv WHERE `key` LIKE 'kotlin_turn_active_v1:%'") fun observeActiveTurnKeys(): Flow<List<KeyValueRow>>
    @Query("SELECT * FROM kv WHERE `key` LIKE 'kotlin_turn_error_v1:%'") fun observeTurnErrorKeys(): Flow<List<KeyValueRow>>
    @Query("SELECT * FROM kv WHERE `key` = :key") fun observeValue(key: String): Flow<KeyValueRow?>
    @Query("SELECT * FROM kv WHERE `key` = :key") suspend fun getValue(key: String): KeyValueRow?
    @Query("SELECT * FROM kv WHERE substr(`key`, 1, length(:prefix)) = :prefix ORDER BY `key`") suspend fun valuesByPrefix(prefix: String): List<KeyValueRow>
    @Query("SELECT `key` FROM kv WHERE substr(`key`, 1, length(:prefix)) = :prefix AND `key` > :after ORDER BY `key` LIMIT :limit")
    suspend fun keysByPrefixPage(prefix: String, after: String, limit: Int): List<String>
    @Upsert suspend fun upsertProviders(rows: List<ProviderRow>)
    @Upsert suspend fun upsertModels(rows: List<ModelRow>)
    @Upsert suspend fun upsertAgents(rows: List<AgentRow>)
    @Upsert suspend fun upsertProjects(rows: List<ProjectRow>)
    @Upsert suspend fun upsertConversations(rows: List<ConversationRow>)
    @Upsert suspend fun upsertMessages(rows: List<MessageRow>)
    @Upsert suspend fun upsertSyncMeta(rows: List<SyncMetaRow>)
    @Upsert suspend fun upsertValues(rows: List<KeyValueRow>)
    @Upsert suspend fun upsertLearningRecords(rows: List<LearningRecordRow>)
    @Upsert suspend fun upsertLearningRevisions(rows: List<LearningRevisionRow>)
    @Upsert suspend fun upsertLearningReviewStates(rows: List<LearningReviewStateRow>)
    @Upsert suspend fun upsertLearningReviewAttempts(rows: List<LearningReviewAttemptRow>)
}
