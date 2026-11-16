/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import cc.ptoe.messenger.data.local.room.AgentRow
import cc.ptoe.messenger.data.local.room.CloudSyncDao
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.data.util.randomUuid

/** Market mutations keep the existing local record and queue its changed link atomically. */
internal class RoomCloudMarket(private val dao: CloudSyncDao, private val api: RoomCloudApi, private val session: SyncSession, private val avatars: RoomAvatarCache) {
    private suspend fun local(id: String): AgentRow {
        check(dao.currentSession() == session) { "Cloud session changed" }
        return requireNotNull(dao.agentRow(id)) { "Agent not found" }
    }
    private fun request(row: AgentRow): CloudMarketAgentRequest {
        val maxTokens = row.maxTokens?.let {
            require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Agent max tokens is out of range" }
            it.toInt()
        }
        return CloudMarketAgentRequest(row.name, row.systemPrompt, row.temperature ?: 0.0,
            row.topP ?: 0.0, maxTokens, row.reasoningEffort)
    }

    suspend fun publish(id: String): CloudMarketAgent {
        val row = local(id)
        require(!row.isDefault) { "The default Agent cannot be published" }
        require(row.marketAgentId == null) { "Agent is already linked to the market" }
        val market = api.createMarketAgent(request(row))
        require(market.id.isNotBlank()) { "Market response has no ID" }
        dao.commitMarketAgent(session, row, row.copy(marketAgentId = market.id, marketAgentVersion = market.version, marketAgentRole = "publisher"))
        return market
    }
    suspend fun updatePublished(id: String): CloudMarketAgent {
        val row = local(id)
        require(row.marketAgentRole == "publisher" && !row.marketAgentId.isNullOrBlank()) { "Only the publisher can update this Agent" }
        val market = api.updateMarketAgent(row.marketAgentId, request(row))
        require(market.id == row.marketAgentId) { "Market response ID mismatch" }
        dao.commitMarketAgent(session, row, row.copy(marketAgentVersion = market.version))
        return market
    }
    suspend fun removePublished(id: String) {
        val row = local(id)
        require(row.marketAgentRole == "publisher" && !row.marketAgentId.isNullOrBlank()) { "Only the publisher can remove this Agent" }
        api.deleteMarketAgent(row.marketAgentId)
        dao.commitMarketAgent(session, row, row.copy(marketAgentId = null, marketAgentVersion = null, marketAgentRole = null))
    }
    suspend fun import(id: String): AgentRow {
        check(dao.currentSession() == session) { "Cloud session changed" }
        val market = api.marketAgent(id)
        require(market.id == id) { "Market response ID mismatch" }
        val now = currentTimeMillis()
        val avatar = avatars.get(api, session, "market-agent", market.id, market.avatarUrl, market.avatarVersion)
        val row = AgentRow(randomUuid(), market.name, avatar, market.systemPrompt, "", null,
            market.temperature, market.topP, market.maxTokens?.toLong(), market.reasoningEffort, false,
            false, false, false, false, false, false, market.id, market.version, "importer", "chat", false, false, "", now, now)
        dao.commitMarketAgent(session, null, row)
        return row
    }
    suspend fun checkUpdate(id: String): CloudMarketAgentUpdate {
        val row = local(id)
        require(row.marketAgentRole == "importer" && !row.marketAgentId.isNullOrBlank()) { "Agent is not a market import" }
        val market = api.marketAgent(row.marketAgentId)
        require(market.id == row.marketAgentId) { "Market response ID mismatch" }
        return CloudMarketAgentUpdate(market, market.version > (row.marketAgentVersion ?: 0))
    }
    suspend fun applyUpdate(id: String, market: CloudMarketAgent): AgentRow {
        val row = local(id)
        require(row.marketAgentRole == "importer" && row.marketAgentId == market.id) { "Agent is not linked to this market entry" }
        val avatar = avatars.get(api, session, "market-agent", market.id, market.avatarUrl, market.avatarVersion) ?: row.avatar
        val updated = row.copy(name = market.name, avatar = avatar, systemPrompt = market.systemPrompt, temperature = market.temperature,
            topP = market.topP, maxTokens = market.maxTokens?.toLong(), reasoningEffort = market.reasoningEffort,
            followDefaultSystemPrompt = false, followDefaultModel = false, followDefaultTemperature = false,
            followDefaultTopP = false, followDefaultMaxTokens = false, followDefaultReasoningEffort = false,
            marketAgentVersion = market.version, updatedAt = currentTimeMillis())
        dao.commitMarketAgent(session, row, updated)
        return updated
    }
}
