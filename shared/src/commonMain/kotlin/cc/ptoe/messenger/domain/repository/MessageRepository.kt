/*
 * Copyright 2026 originalFactor
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

package cc.ptoe.messenger.domain.repository

import cc.ptoe.messenger.domain.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface MessageRepository {
    fun getByConversationId(conversationId: String): Flow<List<Message>>
    fun observeChanges(conversationId: String): Flow<Unit> = getByConversationId(conversationId).map { }
    suspend fun getRecent(conversationId: String, limit: Int): List<Message> =
        getByConversationId(conversationId).first().takeLast(limit)
    suspend fun getBefore(conversationId: String, beforeId: String, limit: Int): List<Message> {
        val rows = getByConversationId(conversationId).first()
        val index = rows.indexOfFirst { it.id == beforeId }
        return if (index < 0) emptyList() else rows.take(index).takeLast(limit)
    }
    suspend fun getFrom(conversationId: String, fromId: String): List<Message> {
        val rows = getByConversationId(conversationId).first()
        val index = rows.indexOfFirst { it.id == fromId }
        return if (index < 0) emptyList() else rows.drop(index)
    }
    suspend fun getByConversationIds(conversationIds: List<String>): List<Message>
    suspend fun insert(message: Message)
    suspend fun insertAll(messages: List<Message>)
    suspend fun update(message: Message)
    suspend fun delete(id: String)
    suspend fun deleteByConversationId(conversationId: String)
    suspend fun getConversationIdById(id: String): String?
}
