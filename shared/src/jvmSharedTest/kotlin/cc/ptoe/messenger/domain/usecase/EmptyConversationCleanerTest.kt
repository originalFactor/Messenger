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

package cc.ptoe.messenger.domain.usecase

import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyConversationCleanerTest {

    private val now = 10_000_000L

    /** 早于宽限期的更新时间：只有这类会话才有资格被清理。 */
    private val staleAt = now - EmptyConversationCleaner.DEFAULT_GRACE_PERIOD_MILLIS - 1

    @Test
    fun deletesOldUntitledConversationWithoutMessages() = runBlocking {
        val db = FakeDb(listOf(conversation("stale", "新对话", staleAt)))

        val deleted = cleaner(db).cleanUp()

        assertEquals(1, deleted)
        assertEquals(listOf("stale"), db.deletedIds)
        assertFalse(db.conversations.containsKey("stale"))
    }

    @Test
    fun keepsConversationInsideGracePeriod() = runBlocking {
        // 刚新建的会话仍可能被用来发第一条消息，宽限期内不清理。
        val db = FakeDb(
            listOf(
                conversation(
                    "fresh",
                    "新对话",
                    now - EmptyConversationCleaner.DEFAULT_GRACE_PERIOD_MILLIS + 1
                )
            )
        )

        assertEquals(0, cleaner(db).cleanUp())
        assertTrue(db.conversations.containsKey("fresh"))
    }

    @Test
    fun keepsConversationThatHasMessages() = runBlocking {
        val db = FakeDb(listOf(conversation("used", "新对话", staleAt)))
        db.messages["used"] = listOf(message("used"))

        assertEquals(0, cleaner(db).cleanUp())
        assertTrue(db.conversations.containsKey("used"))
    }

    @Test
    fun keepsConversationWhoseLastMessagePreviewSurvived() = runBlocking {
        // lastMessage 非空即说明发过消息，无需回查消息表。
        val db = FakeDb(listOf(conversation("preview", "新对话", staleAt, lastMessage = "你好")))

        assertEquals(0, cleaner(db).cleanUp())
        assertTrue(db.conversations.containsKey("preview"))
    }

    @Test
    fun keepsRenamedConversationWithoutMessages() = runBlocking {
        // 手动改名是明确的保留意图，即使一条消息都没有。
        val db = FakeDb(listOf(conversation("renamed", "稍后再聊", staleAt)))

        assertEquals(0, cleaner(db).cleanUp())
        assertTrue(db.conversations.containsKey("renamed"))
    }

    @Test
    fun keepsConversationExcludedByCaller() = runBlocking {
        val db = FakeDb(
            listOf(
                conversation("open", "新对话", staleAt),
                conversation("stale", "新对话", staleAt)
            )
        )

        val deleted = cleaner(db).cleanUp(excluding = setOf("open"))

        assertEquals(1, deleted)
        assertTrue(db.conversations.containsKey("open"))
        assertFalse(db.conversations.containsKey("stale"))
    }

    @Test
    fun continuesAfterAFailedDelete() = runBlocking {
        val db = FakeDb(
            listOf(
                conversation("bad", "新对话", staleAt),
                conversation("good", "新对话", staleAt)
            )
        )
        db.failingDeletes += "bad"

        val deleted = cleaner(db).cleanUp()

        assertEquals(1, deleted)
        assertTrue(db.conversations.containsKey("bad"))
        assertFalse(db.conversations.containsKey("good"))
    }

    private fun cleaner(db: FakeDb) = EmptyConversationCleaner(
        conversationRepository = FakeConversationRepository(db),
        messageRepository = FakeMessageRepository(db),
        now = { now }
    )

    private fun conversation(
        id: String,
        title: String,
        updatedAt: Long,
        lastMessage: String? = null
    ) = Conversation(
        id = id,
        title = title,
        providerId = "provider",
        agentId = "agent",
        createdAt = updatedAt,
        updatedAt = updatedAt,
        lastMessage = lastMessage
    )

    private fun message(conversationId: String) = Message(
        id = "message-$conversationId",
        conversationId = conversationId,
        role = MessageRole.USER,
        content = "你好",
        timestamp = now,
        status = MessageStatus.SENT
    )

    /** 内存版「数据库」：会话与消息各一张表，删除只记 id。 */
    private class FakeDb(initial: List<Conversation>) {
        val conversations = initial.associateBy { it.id }.toMutableMap()
        val messages = mutableMapOf<String, List<Message>>()
        val deletedIds = mutableListOf<String>()
        val failingDeletes = mutableSetOf<String>()
        val version = MutableStateFlow(0L)
    }

    private class FakeConversationRepository(private val db: FakeDb) : ConversationRepository {

        override fun getAll(): Flow<List<Conversation>> =
            db.version.map { db.conversations.values.sortedByDescending { it.updatedAt } }

        override fun getByAgentId(agentId: String): Flow<List<Conversation>> =
            getAll().map { all -> all.filter { it.agentId == agentId } }

        override fun getByProjectId(projectId: String): Flow<List<Conversation>> =
            getAll().map { all -> all.filter { it.projectId == projectId } }

        override fun getById(id: String): Flow<Conversation?> = db.version.map { db.conversations[id] }

        override suspend fun insert(conversation: Conversation) {
            db.conversations[conversation.id] = conversation
            db.version.value++
        }

        override suspend fun update(conversation: Conversation) = insert(conversation)

        override suspend fun updateLastMessage(id: String, lastMessage: String, updatedAt: Long) {
            db.conversations[id]?.let {
                db.conversations[id] = it.copy(lastMessage = lastMessage, updatedAt = updatedAt)
            }
            db.version.value++
        }

        override suspend fun delete(id: String) {
            if (id in db.failingDeletes) throw IllegalStateException("delete failed for $id")
            db.conversations.remove(id)
            db.messages.remove(id)
            db.deletedIds += id
            db.version.value++
        }
    }

    private class FakeMessageRepository(private val db: FakeDb) : MessageRepository {

        override fun getByConversationId(conversationId: String): Flow<List<Message>> =
            db.version.map { db.messages[conversationId].orEmpty() }

        override suspend fun getByConversationIds(conversationIds: List<String>): List<Message> =
            conversationIds.flatMap { db.messages[it].orEmpty() }

        override suspend fun insert(message: Message) {
            db.messages[message.conversationId] = db.messages[message.conversationId].orEmpty() + message
            db.version.value++
        }

        override suspend fun insertAll(messages: List<Message>) {
            messages.forEach { insert(it) }
        }

        override suspend fun update(message: Message) = insert(message)

        override suspend fun delete(id: String) {
            db.messages.entries.firstOrNull { (_, list) -> list.any { it.id == id } }?.let { (convId, list) ->
                db.messages[convId] = list.filterNot { it.id == id }
            }
            db.version.value++
        }

        override suspend fun deleteByConversationId(conversationId: String) {
            db.messages.remove(conversationId)
            db.version.value++
        }

        override suspend fun getConversationIdById(id: String): String? =
            db.messages.entries.firstOrNull { (_, list) -> list.any { it.id == id } }?.key
    }
}
