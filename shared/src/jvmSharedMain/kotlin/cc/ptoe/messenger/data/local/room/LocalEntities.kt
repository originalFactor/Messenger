/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0. See https://www.apache.org/licenses/LICENSE-2.0
 */
package cc.ptoe.messenger.data.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// These storage rows deliberately preserve Core v9 field names and serialized values.
// They belong to a new Room database; never open the Core database as Room directly.
@Entity(tableName = "providers")
data class ProviderRow(@PrimaryKey val id: String, val name: String, val baseUrl: String, val apiKey: String,
    @ColumnInfo(defaultValue = "'chat'") val apiFormat: String, @ColumnInfo(defaultValue = "0") val nativeWebSearch: Boolean, val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "models", foreignKeys = [ForeignKey(entity = ProviderRow::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE)], indices = [Index("providerId")])
data class ModelRow(@PrimaryKey val id: String, val providerId: String, val modelId: String, val displayName: String,
    @ColumnInfo(defaultValue = "1") val isEnabled: Boolean, @ColumnInfo(defaultValue = "0") val contextWindow: Long, val inputRate: Double?, val outputRate: Double?,
    val cacheReadRate: Double?, val cacheWriteRate: Double?, @ColumnInfo(defaultValue = "'text'") val inputModalities: String, @ColumnInfo(defaultValue = "'text'") val outputModalities: String,
    @ColumnInfo(defaultValue = "0") val supportsToolCalling: Boolean, @ColumnInfo(defaultValue = "0") val supportsThinking: Boolean, @ColumnInfo(defaultValue = "0") val supportsJsonOutput: Boolean,
    @ColumnInfo(defaultValue = "0") val supportsTemperature: Boolean, val createdAt: Long, val metadata: String?, val metadataOverrides: String?)

@Entity(tableName = "agents")
data class AgentRow(@PrimaryKey val id: String, val name: String, val avatar: String?, val systemPrompt: String,
    @ColumnInfo(defaultValue = "''") val description: String, val defaultModelId: String?, val temperature: Double?, val topP: Double?,
    val maxTokens: Long?, val reasoningEffort: String?, @ColumnInfo(defaultValue = "0") val isDefault: Boolean, @ColumnInfo(defaultValue = "0") val followDefaultSystemPrompt: Boolean,
    @ColumnInfo(defaultValue = "0") val followDefaultModel: Boolean, @ColumnInfo(defaultValue = "0") val followDefaultTemperature: Boolean, @ColumnInfo(defaultValue = "0") val followDefaultTopP: Boolean,
    @ColumnInfo(defaultValue = "0") val followDefaultMaxTokens: Boolean, @ColumnInfo(defaultValue = "0") val followDefaultReasoningEffort: Boolean, val marketAgentId: String?,
    val marketAgentVersion: Long?, val marketAgentRole: String?, @ColumnInfo(defaultValue = "'chat'") val role: String, @ColumnInfo(defaultValue = "0") val toolsEnabled: Boolean,
    @ColumnInfo(defaultValue = "0") val toolsFollowDefault: Boolean, @ColumnInfo(defaultValue = "''") val toolsConfig: String, val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "projects")
data class ProjectRow(@PrimaryKey val id: String, val name: String, val workspace: String, val deviceId: String?,
    val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "conversations", foreignKeys = [
    ForeignKey(entity = AgentRow::class, parentColumns = ["id"], childColumns = ["agentId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = ProjectRow::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.SET_NULL)
], indices = [Index("agentId"), Index("projectId")])
data class ConversationRow(@PrimaryKey val id: String, val title: String, val providerId: String, val agentId: String,
    val projectId: String?, val overrideModelId: String?, val overrideTemperature: Double?, val overrideTopP: Double?,
    val overrideMaxTokens: Long?, val overrideReasoningEffort: String?, val overrideToolsEnabled: Boolean?,
    val overrideToolsConfig: String?, @ColumnInfo(defaultValue = "0") val writable: Boolean, val createdAt: Long, val updatedAt: Long, val lastMessage: String?,
    val reasoningFormat: String?, val contextSummary: String?, @ColumnInfo(defaultValue = "0") val contextSummaryUntil: Long, @ColumnInfo(defaultValue = "0") val contextTokens: Long,
    @ColumnInfo(defaultValue = "0") val contextTokensAt: Long)

@Entity(tableName = "messages", foreignKeys = [ForeignKey(entity = ConversationRow::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)], indices = [Index("conversationId"), Index(value = ["conversationId", "timestamp"], name = "index_messages_conversation_timestamp")])
data class MessageRow(@PrimaryKey val id: String, val conversationId: String, val role: String, val content: String,
    val partsJson: String?, val timestamp: Long, val status: String, val errorMessage: String?)

@Entity(tableName = "sync_meta")
data class SyncMetaRow(@PrimaryKey val accountId: String, @ColumnInfo(defaultValue = "0") val cursor: Long, @ColumnInfo(defaultValue = "''") val pendingUpserts: String, @ColumnInfo(defaultValue = "''") val pendingDeletes: String)
@Entity(tableName = "kv")
data class KeyValueRow(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "learning_records", indices = [Index(value = ["kind", "course_id", "updated_at"], name = "learning_kind_course")])
data class LearningRecordRow(@PrimaryKey val id: String, val kind: String, val version: Long, val course_id: String?,
    val title: String, val archived: Boolean, val updated_at: Long, val body: String)
@Entity(tableName = "learning_revisions", primaryKeys = ["item_id", "version"])
data class LearningRevisionRow(val item_id: String, val version: Long, val body: String)
@Entity(tableName = "learning_review_states", foreignKeys = [ForeignKey(entity = LearningRecordRow::class, parentColumns = ["id"], childColumns = ["card_id"], onDelete = ForeignKey.CASCADE)])
data class LearningReviewStateRow(@PrimaryKey val card_id: String, val body: String)
@Entity(tableName = "learning_review_attempts", indices = [Index(value = ["card_id", "created_at"], name = "learning_attempt_card")])
data class LearningReviewAttemptRow(@PrimaryKey val submission_id: String, val card_id: String, val created_at: Long, val body: String)
