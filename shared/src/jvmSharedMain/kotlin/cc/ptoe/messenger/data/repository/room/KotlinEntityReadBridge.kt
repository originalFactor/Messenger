/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.StoredAgentDto
import cc.ptoe.messenger.core.StoredConversationDto
import cc.ptoe.messenger.core.StoredProjectDto
import cc.ptoe.messenger.data.local.room.AgentRow
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ProjectRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Legacy synchronous read boundary used by ACP, task settings and notification navigation. */
internal class KotlinEntityReadBridge(
    private val delegate: CoreBridge,
    private val db: LocalDatabase,
    private val acp: AcpConversationStore,
) : CoreBridge by delegate {
    private val json = Json { encodeDefaults = true }
    private fun <T> read(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    override fun listAgentsJson(): String = read { json.encodeToString(db.rows().observeAgents().first().map { it.dto() }) }
    override fun getAgentJson(id: String): String? = read { db.rows().agent(id)?.let { json.encodeToString(it.dto()) } }
    override fun getDefaultAgentJson(): String? = read {
        val agents = db.rows().defaultAgents()
        check(agents.size <= 1) { "Multiple default agents" }
        agents.firstOrNull()?.let { json.encodeToString(it.dto()) }
    }
    override fun getTitleAgentJson(): String? = read {
        val agents = db.rows().observeAgents().first().filter { it.role == "title" }
        check(agents.size <= 1) { "Multiple title agents" }
        agents.firstOrNull()?.let { json.encodeToString(it.dto()) }
    }
    override fun listProjectsJson(): String = read { json.encodeToString(db.rows().observeProjects().first().map { it.dto() }) }
    override fun getProjectJson(id: String): String? = read { db.rows().project(id)?.let { json.encodeToString(it.dto()) } }
    override fun getConversationJson(id: String): String? = read {
        if (id.startsWith("acp:")) {
            acp.observeAll().first().singleOrNull { it.id == id }?.let { row ->
                json.encodeToString(StoredConversationDto(id = row.id, title = row.title, providerId = row.providerId,
                    agentId = row.agentId, projectId = row.projectId, createdAt = row.createdAt, updatedAt = row.updatedAt,
                    lastMessage = row.lastMessage))
            }
        } else db.rows().conversation(id)?.let { json.encodeToString(it.dto()) }
    }

    private fun AgentRow.dto() = StoredAgentDto(id, name, avatar, systemPrompt, description, defaultModelId,
        temperature?.toFloat(), topP?.toFloat(), maxTokens?.toCheckedInt(), reasoningEffort, isDefault,
        followDefaultSystemPrompt, followDefaultModel, followDefaultTemperature, followDefaultTopP,
        followDefaultMaxTokens, followDefaultReasoningEffort, marketAgentId, marketAgentVersion, marketAgentRole,
        role, toolsEnabled, toolsFollowDefault, toolsConfig, createdAt, updatedAt)

    private fun ProjectRow.dto() = StoredProjectDto(id, name, workspace, createdAt, updatedAt)

    private fun ConversationRow.dto() = StoredConversationDto(id, title, providerId, agentId, projectId, overrideModelId,
        overrideTemperature?.toFloat(), overrideTopP?.toFloat(), overrideMaxTokens?.toCheckedInt(), overrideReasoningEffort,
        overrideToolsEnabled, overrideToolsConfig, writable, createdAt, updatedAt, lastMessage, reasoningFormat,
        contextSummary, contextSummaryUntil, contextTokens, contextTokensAt)

    private fun Long.toCheckedInt(): Int {
        require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Stored token limit is out of range" }
        return toInt()
    }
}
