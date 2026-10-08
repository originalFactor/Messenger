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
import cc.ptoe.messenger.core.StoredConversationDto
import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.data.util.ToolsConfigCodec
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class RustConversationRepository(
    private val coreBridge: CoreBridge,
    private val onChanged: (String, Boolean) -> Unit = { _, _ -> }
) : ConversationRepository {

    private val version = MutableStateFlow(0L)

    init {
        coreBridge.subscribe { kind, _ ->
            if (kind == "Conversation" || kind == "All" || kind == "Other") {
                version.value = currentTimeMillis()
            }
        }
    }

    override fun getAll(): Flow<List<Conversation>> = version.map {
        val json = coreBridge.listConversationsJson()
        runCatching {
            NetworkClient.json.decodeFromString<List<StoredConversationDto>>(json).map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    override fun getByAgentId(agentId: String): Flow<List<Conversation>> = version.map {
        val json = coreBridge.listConversationsByAgentJson(agentId)
        runCatching {
            NetworkClient.json.decodeFromString<List<StoredConversationDto>>(json).map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    override fun getByProjectId(projectId: String): Flow<List<Conversation>> = version.map {
        val json = coreBridge.listConversationsByProjectJson(projectId)
        runCatching {
            NetworkClient.json.decodeFromString<List<StoredConversationDto>>(json).map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    override fun getById(id: String): Flow<Conversation?> = version.map {
        val json = coreBridge.getConversationJson(id) ?: return@map null
        runCatching {
            NetworkClient.json.decodeFromString<StoredConversationDto>(json).toDomain()
        }.getOrNull()
    }

    override suspend fun insert(conversation: Conversation) {
        val dto = conversation.toDto()
        coreBridge.upsertConversationJson(NetworkClient.json.encodeToString(dto))
        version.value = currentTimeMillis()
        onChanged(conversation.id, false)
    }

    override suspend fun update(conversation: Conversation) {
        val dto = conversation.toDto()
        coreBridge.upsertConversationJson(NetworkClient.json.encodeToString(dto))
        version.value = currentTimeMillis()
        onChanged(conversation.id, false)
    }

    override suspend fun updateLastMessage(id: String, lastMessage: String, updatedAt: Long) {
        coreBridge.updateConversationLastMessage(id, lastMessage, updatedAt)
        version.value = currentTimeMillis()
        onChanged(id, false)
    }

    override suspend fun delete(id: String) {
        coreBridge.deleteMessagesByConversation(id)
        coreBridge.deleteConversation(id)
        version.value = currentTimeMillis()
        onChanged(id, true)
    }

    private fun StoredConversationDto.toDomain() = Conversation(
        id = id,
        title = title,
        providerId = providerId,
        agentId = agentId,
        overrideModelId = overrideModelId,
        overrideTemperature = overrideTemperature,
        overrideTopP = overrideTopP,
        overrideMaxTokens = overrideMaxTokens,
        overrideReasoningEffort = overrideReasoningEffort,
        projectId = projectId,
        overrideToolsEnabled = overrideToolsEnabled,
        overrideToolsConfig = overrideToolsConfig?.let { ToolsConfigCodec.decode(it) },
        writable = writable,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessage = lastMessage,
        reasoningFormat = reasoningFormat,
        contextSummary = contextSummary,
        contextSummaryUntil = contextSummaryUntil,
        contextTokens = contextTokens,
        contextTokensAt = contextTokensAt
    )

    private fun Conversation.toDto() = StoredConversationDto(
        id = id,
        title = title,
        providerId = providerId,
        agentId = agentId,
        projectId = projectId,
        overrideModelId = overrideModelId,
        overrideTemperature = overrideTemperature,
        overrideTopP = overrideTopP,
        overrideMaxTokens = overrideMaxTokens,
        overrideReasoningEffort = overrideReasoningEffort,
        overrideToolsEnabled = overrideToolsEnabled,
        overrideToolsConfig = overrideToolsConfig?.let { ToolsConfigCodec.encode(it) },
        writable = writable,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessage = lastMessage,
        reasoningFormat = reasoningFormat,
        contextSummary = contextSummary,
        contextSummaryUntil = contextSummaryUntil,
        contextTokens = contextTokens,
        contextTokensAt = contextTokensAt
    )
}
