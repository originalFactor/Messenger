/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.ptoe.messenger.data.repository

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.StoredAgentDto
import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.data.util.deleteFile
import cc.ptoe.messenger.data.util.ToolsConfigCodec
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.repository.AgentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.Path

class RustAgentRepository(
    private val coreBridge: CoreBridge,
    private val onChanged: (previous: Agent?, current: Agent?) -> Unit = { _, _ -> },
    private val avatarDirectory: Path? = null
) : AgentRepository {

    private val version = MutableStateFlow(0L)

    init {
        coreBridge.subscribe { kind, _ ->
            if (kind == "Agent" || kind == "All" || kind == "Other") {
                version.value = currentTimeMillis()
            }
        }
    }

    override fun getAll(): Flow<List<Agent>> = version.map {
        val json = coreBridge.listAgentsJson()
        runCatching {
            NetworkClient.json.decodeFromString<List<StoredAgentDto>>(json).map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    override fun getById(id: String): Flow<Agent?> = version.map {
        val json = coreBridge.getAgentJson(id) ?: return@map null
        runCatching {
            NetworkClient.json.decodeFromString<StoredAgentDto>(json).toDomain()
        }.getOrNull()
    }

    override fun getDefaultAgent(): Flow<Agent?> = version.map {
        val json = coreBridge.getDefaultAgentJson() ?: return@map null
        runCatching {
            NetworkClient.json.decodeFromString<StoredAgentDto>(json).toDomain()
        }.getOrNull()
    }

    override suspend fun insert(agent: Agent) {
        val dto = agent.toDto()
        coreBridge.upsertAgentJson(NetworkClient.json.encodeToString(dto))
        version.value = currentTimeMillis()
        onChanged(null, agent)
    }

    override suspend fun update(agent: Agent) {
        val previous = getById(agent.id).first()
        val dto = agent.toDto()
        coreBridge.upsertAgentJson(NetworkClient.json.encodeToString(dto))
        version.value = currentTimeMillis()
        onChanged(previous, agent)
    }

    override suspend fun clone(id: String): Agent? {
        val source = getById(id).first() ?: return null
        val now = currentTimeMillis()
        val cloned = source.copy(
            id = randomUuid(),
            name = "${source.name} (Copy)",
            isDefault = false,
            role = Agent.ROLE_CHAT,
            createdAt = now,
            updatedAt = now
        )
        insert(cloned)
        return cloned
    }

    override suspend fun delete(id: String) {
        if (id == Agent.BUILTIN_TITLE_AGENT_ID) return
        val current = getById(id).first() ?: return
        if (current.isDefault || current.role == Agent.ROLE_TITLE) return

        current.avatar?.let { avatarPath ->
            if (!avatarPath.startsWith("http://") && !avatarPath.startsWith("https://")) {
                deleteFile(avatarPath)
            }
        }
        coreBridge.deleteAgent(id)
        version.value = currentTimeMillis()
        onChanged(current, null)
    }

    private fun StoredAgentDto.toDomain() = Agent(
        id = id,
        name = name,
        avatar = avatar,
        systemPrompt = systemPrompt,
        description = description,
        defaultModelId = defaultModelId,
        temperature = temperature ?: 0.7f,
        topP = topP ?: 1.0f,
        maxTokens = maxTokens,
        reasoningEffort = reasoningEffort,
        isDefault = isDefault,
        followDefaultSystemPrompt = followDefaultSystemPrompt,
        followDefaultModel = followDefaultModel,
        followDefaultTemperature = followDefaultTemperature,
        followDefaultTopP = followDefaultTopP,
        followDefaultMaxTokens = followDefaultMaxTokens,
        followDefaultReasoningEffort = followDefaultReasoningEffort,
        marketAgentId = marketAgentId,
        marketAgentVersion = marketAgentVersion,
        marketAgentRole = marketAgentRole,
        role = role,
        toolsEnabled = toolsEnabled,
        toolsFollowDefault = toolsFollowDefault,
        toolsConfig = ToolsConfigCodec.decode(toolsConfig),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun Agent.toDto() = StoredAgentDto(
        id = id,
        name = name,
        avatar = avatar,
        systemPrompt = systemPrompt,
        description = description,
        defaultModelId = defaultModelId,
        temperature = temperature,
        topP = topP,
        maxTokens = maxTokens,
        reasoningEffort = reasoningEffort,
        isDefault = isDefault,
        followDefaultSystemPrompt = followDefaultSystemPrompt,
        followDefaultModel = followDefaultModel,
        followDefaultTemperature = followDefaultTemperature,
        followDefaultTopP = followDefaultTopP,
        followDefaultMaxTokens = followDefaultMaxTokens,
        followDefaultReasoningEffort = followDefaultReasoningEffort,
        marketAgentId = marketAgentId,
        marketAgentVersion = marketAgentVersion,
        marketAgentRole = marketAgentRole,
        role = role,
        toolsEnabled = toolsEnabled,
        toolsFollowDefault = toolsFollowDefault,
        toolsConfig = ToolsConfigCodec.encode(toolsConfig),
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
