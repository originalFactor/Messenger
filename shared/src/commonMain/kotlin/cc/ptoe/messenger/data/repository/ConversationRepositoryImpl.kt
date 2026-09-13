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

import cc.ptoe.messenger.data.local.dao.ConversationDao
import cc.ptoe.messenger.data.local.entity.ConversationEntity
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ConversationRepositoryImpl(
    private val conversationDao: ConversationDao,
    private val onChanged: (String, Boolean) -> Unit = { _, _ -> }
) : ConversationRepository {

    override fun getAll(): Flow<List<Conversation>> {
        return conversationDao.getAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getByAgentId(agentId: String): Flow<List<Conversation>> {
        return conversationDao.getByAgentId(agentId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getById(id: String): Flow<Conversation?> {
        return conversationDao.getById(id).map { entity ->
            entity?.toDomain()
        }
    }

    override suspend fun insert(conversation: Conversation) {
        conversationDao.insert(conversation.toEntity())
        onChanged(conversation.id, false)
    }

    override suspend fun update(conversation: Conversation) {
        conversationDao.update(conversation.toEntity())
        onChanged(conversation.id, false)
    }

    override suspend fun updateLastMessage(id: String, lastMessage: String, updatedAt: Long) {
        conversationDao.updateLastMessage(id, lastMessage, updatedAt)
        onChanged(id, false)
    }

    override suspend fun delete(id: String) {
        conversationDao.delete(id)
        onChanged(id, true)
    }

    private fun ConversationEntity.toDomain(): Conversation {
        return Conversation(
            id = id,
            title = title,
            providerId = providerId,
            agentId = agentId,
            overrideModelId = overrideModelId,
            overrideTemperature = overrideTemperature,
            overrideTopP = overrideTopP,
            overrideMaxTokens = overrideMaxTokens,
            overrideReasoningEffort = overrideReasoningEffort,
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

    private fun Conversation.toEntity(): ConversationEntity {
        return ConversationEntity(
            id = id,
            title = title,
            providerId = providerId,
            agentId = agentId,
            overrideModelId = overrideModelId,
            overrideTemperature = overrideTemperature,
            overrideTopP = overrideTopP,
            overrideMaxTokens = overrideMaxTokens,
            overrideReasoningEffort = overrideReasoningEffort,
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
}
