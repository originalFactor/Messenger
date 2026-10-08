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

import cc.ptoe.messenger.data.local.ContentPartCodec
import cc.ptoe.messenger.data.local.dao.MessageDao
import cc.ptoe.messenger.data.local.entity.MessageEntity
import cc.ptoe.messenger.domain.model.ContentPart
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MessageRepositoryImpl(
    private val messageDao: MessageDao,
    private val onChanged: (String) -> Unit = {}
) : MessageRepository {

    override fun getByConversationId(conversationId: String): Flow<List<Message>> {
        return messageDao.getByConversationId(conversationId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getByConversationIds(conversationIds: List<String>): List<Message> {
        return messageDao.getByConversationIds(conversationIds).map { it.toDomain() }
    }

    override suspend fun insert(message: Message) {
        messageDao.insert(message.toEntity())
        onChanged(message.conversationId)
    }

    override suspend fun insertAll(messages: List<Message>) {
        messageDao.insertAll(messages.map { it.toEntity() })
        messages.firstOrNull()?.conversationId?.let(onChanged)
    }

    override suspend fun update(message: Message) {
        messageDao.update(message.toEntity())
        onChanged(message.conversationId)
    }

    override suspend fun delete(id: String) {
        val conversationId = messageDao.getConversationIdById(id)
        messageDao.delete(id)
        conversationId?.let(onChanged)
    }

    override suspend fun deleteByConversationId(conversationId: String) {
        messageDao.deleteByConversationId(conversationId)
        onChanged(conversationId)
    }

    override suspend fun getConversationIdById(id: String): String? {
        return messageDao.getConversationIdById(id)
    }

    private fun MessageEntity.toDomain(): Message {
        val decodedParts = ContentPartCodec.decode(partsJson)
        val parts = if (decodedParts.isNotEmpty()) {
            decodedParts
        } else if (content.isNotEmpty()) {
            // Legacy row from before the parts column existed — wrap
            // the stored text into a single text part so callers can
            // treat the message uniformly.
            listOf(ContentPart.Text(content))
        } else {
            emptyList()
        }
        return Message(
            id = id,
            conversationId = conversationId,
            role = MessageRole.valueOf(role.uppercase()),
            content = content,
            parts = parts,
            timestamp = timestamp,
            status = MessageStatus.valueOf(status.uppercase()),
            errorMessage = errorMessage
        )
    }

    private fun Message.toEntity(): MessageEntity {
        // If `parts` was never populated (e.g. callers that only pass
        // `content`), build a single text part so the encoded column
        // stays consistent with the in-memory model.
        val effectiveParts = if (parts.isNotEmpty()) {
            parts
        } else if (content.isNotEmpty()) {
            listOf(ContentPart.Text(content))
        } else {
            emptyList()
        }
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            role = role.name.lowercase(),
            content = content,
            partsJson = ContentPartCodec.encode(effectiveParts),
            timestamp = timestamp,
            status = status.name.lowercase(),
            errorMessage = errorMessage
        )
    }
}
