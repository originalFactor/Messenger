/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.StoredModelDto
import cc.ptoe.messenger.core.StoredProviderDto
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.local.room.CLOUD_REPLACEMENT_LOCK_KEY
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ModelRow
import cc.ptoe.messenger.data.local.room.ProviderRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Unconnected Room-only base for the existing bridge adapters. Missing capabilities throw: no
 * operation falls back to a Rust bridge. Room repositories remain the only entity writers.
 * Install KotlinRoomEventsBridge outside this base before registering event subscribers.
 */
internal class KotlinRoomCoreBase(
    private val db: LocalDatabase,
    acp: AcpConversationStore,
) : CoreBridge by KotlinEntityReadBridge(KotlinUnavailableCoreBridge, db, acp) {
    private val json = Json { encodeDefaults = true }
    private fun <T> read(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    override fun listProvidersJson(): String = read { json.encodeToString(db.rows().observeProviders().first().map { it.dto() }) }
    override fun getProviderJson(id: String): String? = read { db.rows().provider(id)?.let { json.encodeToString(it.dto()) } }
    override fun listModelsJson(): String = read { json.encodeToString(db.rows().observeModels().first().map { it.dto() }) }
    override fun listModelsByProviderJson(providerId: String): String = read {
        json.encodeToString(db.rows().observeModels().first().filter { it.providerId == providerId }.map { it.dto() })
    }
    override fun getModelJson(id: String): String? = read { db.rows().model(id)?.let { json.encodeToString(it.dto()) } }

    override fun kvGet(key: String): String? = read { requireCurrentAgentKey(key); db.rows().getValue(key)?.value }
    override fun kvSet(key: String, value: String) = read { changeCurrentAgent(key, value) }
    override fun kvDelete(key: String) = read { changeCurrentAgent(key, null) }

    private suspend fun changeCurrentAgent(key: String, value: String?) {
        requireCurrentAgentKey(key)
        db.useWriterConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            check(db.rows().getValue(CLOUD_REPLACEMENT_LOCK_KEY) == null) { "Cloud replacement requires completion or recovery" }
            if (value == null) db.rows().deleteValue(key) else db.rows().upsertValues(listOf(KeyValueRow(key, value)))
        } }
    }
    private fun requireCurrentAgentKey(key: String) {
        require(key == "current_agent_id") { "This key must be accessed through its Room owner" }
    }

    override suspend fun agentDelegationEnabled(): Boolean = db.rows().getValue("mcp_agent_delegation_enabled")?.value == "true"
    override suspend fun setAgentDelegationEnabled(enabled: Boolean) {
        db.useWriterConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            db.rows().upsertValues(listOf(KeyValueRow("mcp_agent_delegation_enabled", enabled.toString())))
        } }
    }

    private fun ProviderRow.dto() = StoredProviderDto(id, name, baseUrl, apiKey, apiFormat, nativeWebSearch, createdAt, updatedAt)
    private fun ModelRow.dto() = StoredModelDto(id, providerId, modelId, displayName, isEnabled, contextWindow,
        inputRate, outputRate, cacheReadRate, cacheWriteRate, inputModalities, outputModalities, supportsToolCalling,
        supportsThinking, supportsJsonOutput, supportsTemperature, createdAt)
}

/** A complete, explicit failure boundary for capabilities which no Kotlin adapter has supplied. */
private object KotlinUnavailableCoreBridge : CoreBridge {
    private fun unavailable(): Nothing = error("Kotlin backend capability is unavailable")
    override fun subscribe(listener: (String, List<String>) -> Unit): Unit = unavailable()
    override suspend fun hasActiveTasks(): Boolean = unavailable()
    override fun listProvidersJson(): String = unavailable()
    override fun getProviderJson(id: String): String? = unavailable()
    override fun upsertProviderJson(json: String): Unit = unavailable()
    override fun deleteProvider(id: String): Unit = unavailable()
    override fun listModelsJson(): String = unavailable()
    override fun listModelsByProviderJson(providerId: String): String = unavailable()
    override fun getModelJson(id: String): String? = unavailable()
    override fun upsertModelJson(json: String): Unit = unavailable()
    override fun setModelEnabled(id: String, enabled: Boolean): Unit = unavailable()
    override fun deleteModel(id: String): Unit = unavailable()
    override fun listAgentsJson(): String = unavailable()
    override fun getAgentJson(id: String): String? = unavailable()
    override fun getDefaultAgentJson(): String? = unavailable()
    override fun getTitleAgentJson(): String? = unavailable()
    override fun upsertAgentJson(json: String): Unit = unavailable()
    override fun deleteAgent(id: String): Unit = unavailable()
    override fun listProjectsJson(): String = unavailable()
    override fun getProjectJson(id: String): String? = unavailable()
    override fun upsertProjectJson(json: String): Unit = unavailable()
    override fun deleteProject(id: String): Unit = unavailable()
    override fun listConversationsJson(): String = unavailable()
    override fun listConversationsByAgentJson(agentId: String): String = unavailable()
    override fun listConversationsByProjectJson(projectId: String): String = unavailable()
    override fun getConversationJson(id: String): String? = unavailable()
    override fun upsertConversationJson(json: String): Unit = unavailable()
    override fun updateConversationLastMessage(id: String, lastMessage: String?, updatedAt: Long): Unit = unavailable()
    override fun deleteConversation(id: String): Unit = unavailable()
    override fun sweepEmptyConversations(excludeIdsJson: String): String = unavailable()
    override fun listMessagesByConversationJson(conversationId: String): String = unavailable()
    override fun listRecentMessagesByConversationJson(conversationId: String, limit: UInt): String = unavailable()
    override fun listMessagesBeforeJson(conversationId: String, beforeId: String, limit: UInt): String = unavailable()
    override fun listMessagesFromJson(conversationId: String, fromId: String): String = unavailable()
    override fun getMessageJson(id: String): String? = unavailable()
    override fun upsertMessageJson(json: String): Unit = unavailable()
    override fun deleteMessage(id: String): Unit = unavailable()
    override fun deleteMessagesByConversation(conversationId: String): Unit = unavailable()
    override fun kvGet(key: String): String? = unavailable()
    override fun kvSet(key: String, value: String): Unit = unavailable()
    override fun kvDelete(key: String): Unit = unavailable()
    override suspend fun cloudLogin(email: String, password: String): String = unavailable()
    override suspend fun cloudRegister(email: String, password: String): String = unavailable()
    override suspend fun cloudLogout(): Unit = unavailable()
    override suspend fun cloudRefreshUser(): String = unavailable()
    override fun cloudCurrentUserJson(): String? = unavailable()
    override fun cloudSetServerUrl(url: String): Unit = unavailable()
    override fun cloudGetServerUrl(): String = unavailable()
    override fun cloudHasLocalData(): Boolean = unavailable()
    override fun cloudMarkChange(kind: String, id: String, deleted: Boolean): Unit = unavailable()
    override suspend fun cloudSync(replaceLocal: Boolean): String = unavailable()
    override suspend fun cloudPushPending(): String = unavailable()
    override suspend fun cloudPreviewCard(code: String): String = unavailable()
    override suspend fun cloudRedeemCard(code: String): String = unavailable()
    override suspend fun cloudSyncBuiltinModels(force: Boolean): Long = unavailable()
    override suspend fun discoverProviderModelsJson(baseUrl: String, apiKey: String, apiFormat: String?): String = unavailable()
    override suspend fun modelsDevMetadataJson(modelId: String): String? = unavailable()
    override suspend fun cloudListMarketAgents(query: String, cursor: String?): String = unavailable()
    override suspend fun cloudGetMarketAgent(id: String): String = unavailable()
    override suspend fun cloudPublishMarketAgent(agentId: String): String = unavailable()
    override suspend fun cloudImportMarketAgent(marketId: String): String = unavailable()
    override suspend fun cloudCacheAvatar(scope: String, accountId: String, id: String, url: String, version: String?, destDir: String): String = unavailable()
    override suspend fun cloudPushSnapshot(): Long = unavailable()
    override suspend fun cloudSyncWith(since: Long?, replaceLocal: Boolean, expectedServerVersion: Long?): String = unavailable()
    override suspend fun cloudReplaceCloudWithLocal(): String = unavailable()
    override suspend fun cloudChangePassword(currentPassword: String, newPassword: String): Unit = unavailable()
    override suspend fun cloudDeleteAccount(currentPassword: String): Unit = unavailable()
    override suspend fun cloudEnsureBuiltinTitleAgent(): Unit = unavailable()
    override suspend fun cloudUploadUserAvatar(bytes: ByteArray, filename: String, mime: String): String = unavailable()
    override suspend fun cloudDeleteUserAvatar(): String = unavailable()
    override suspend fun cloudUploadAgentAvatar(agentId: String, bytes: ByteArray, filename: String, mime: String): String = unavailable()
    override suspend fun cloudDeleteAgentAvatar(agentId: String): String = unavailable()
    override suspend fun cloudPushMarketAgentUpdate(agentId: String): String = unavailable()
    override suspend fun cloudRemoveMarketAgent(agentId: String): Unit = unavailable()
    override suspend fun cloudCheckMarketAgentUpdate(agentId: String): String = unavailable()
    override suspend fun cloudApplyMarketAgentUpdate(agentId: String, marketJson: String): String = unavailable()
    override suspend fun cloudImportMarketAgentWithAvatar(marketId: String): String = unavailable()
    override suspend fun cloudClearMarketLinksAndBuiltinProvider(): Unit = unavailable()
    override fun clearLocalDataForReinit(): Unit = unavailable()
    override fun cancelTurn(): Unit = unavailable()
    override suspend fun backgroundTurnActive(conversationId: String): Boolean = unavailable()
    override suspend fun runTurn(config: TurnConfigBridge, toolExecutor: suspend (String, String) -> Pair<String, Boolean>, onEventJson: (String) -> Unit): Unit = unavailable()
}
