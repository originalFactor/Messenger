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
import cc.ptoe.messenger.core.StoredMessageDto
import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.data.local.ContentPartCodec
import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class RustMessageRepository(
    private val coreBridge: CoreBridge,
    private val chatImageStore: ChatImageStore? = null,
    private val onChanged: (conversationId: String) -> Unit = {}
) : MessageRepository {

    private val version = MutableStateFlow(0L)

    init {
        coreBridge.subscribe { kind, _ ->
            if (kind == "Message" || kind == "All" || kind == "Other") {
                version.value = System.currentTimeMillis()
            }
        }
    }

    override fun getByConversationId(conversationId: String): Flow<List<Message>> = version.map {
        val json = coreBridge.listMessagesByConversationJson(conversationId)
        runCatching {
            NetworkClient.json.decodeFromString<List<StoredMessageDto>>(json).map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    override suspend fun getByConversationIds(conversationIds: List<String>): List<Message> {
        return conversationIds.flatMap { id ->
            val json = coreBridge.listMessagesByConversationJson(id)
            runCatching {
                NetworkClient.json.decodeFromString<List<StoredMessageDto>>(json).map { it.toDomain() }
            }.getOrDefault(emptyList())
        }
    }

    override suspend fun insert(message: Message) {
        val dto = message.toDto()
        coreBridge.upsertMessageJson(NetworkClient.json.encodeToString(dto))
        version.value = System.currentTimeMillis()
        onChanged(message.conversationId)
    }

    override suspend fun insertAll(messages: List<Message>) {
        for (m in messages) {
            val dto = m.toDto()
            coreBridge.upsertMessageJson(NetworkClient.json.encodeToString(dto))
        }
        version.value = System.currentTimeMillis()
        messages.firstOrNull()?.let { onChanged(it.conversationId) }
    }

    override suspend fun update(message: Message) {
        insert(message)
    }

    override suspend fun delete(id: String) {
        val convId = getConversationIdById(id)
        coreBridge.deleteMessage(id)
        version.value = System.currentTimeMillis()
        if (convId != null) onChanged(convId)
    }

    override suspend fun deleteByConversationId(conversationId: String) {
        coreBridge.deleteMessagesByConversation(conversationId)
        version.value = System.currentTimeMillis()
        onChanged(conversationId)
    }

    override suspend fun getConversationIdById(id: String): String? {
        val json = coreBridge.getMessageJson(id) ?: return null
        return runCatching {
            NetworkClient.json.decodeFromString<StoredMessageDto>(json).conversationId
        }.getOrNull()
    }

    private fun StoredMessageDto.toDomain(): Message {
        val roleEnum = when (role.lowercase()) {
            "user" -> MessageRole.USER
            "assistant" -> MessageRole.ASSISTANT
            "system" -> MessageRole.SYSTEM
            else -> MessageRole.TOOL
        }
        val statusEnum = when (status.lowercase()) {
            "sending" -> MessageStatus.SENDING
            "error" -> MessageStatus.ERROR
            else -> MessageStatus.SENT
        }
        val parts = ContentPartCodec.decode(partsJson)
        return Message(
            id = id,
            conversationId = conversationId,
            role = roleEnum,
            content = content,
            parts = parts,
            timestamp = timestamp,
            status = statusEnum,
            errorMessage = errorMessage
        )
    }

    private fun Message.toDto(): StoredMessageDto {
        return StoredMessageDto(
            id = id,
            conversationId = conversationId,
            role = when (role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> "system"
                MessageRole.TOOL -> "tool"
            },
            content = content,
            partsJson = ContentPartCodec.encode(parts),
            timestamp = timestamp,
            status = when (status) {
                MessageStatus.SENDING -> "sending"
                MessageStatus.SENT -> "sent"
                MessageStatus.ERROR -> "error"
            },
            errorMessage = errorMessage
        )
    }
}
