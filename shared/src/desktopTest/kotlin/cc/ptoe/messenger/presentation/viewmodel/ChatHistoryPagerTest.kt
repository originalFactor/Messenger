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

package cc.ptoe.messenger.presentation.viewmodel

import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatHistoryPagerTest {
    private class FakeRepository : MessageRepository {
        val rows = mutableListOf<Message>()
        val version = MutableStateFlow(0)
        var fullReads = 0
        override fun getByConversationId(conversationId: String): Flow<List<Message>> {
            fullReads++
            error("The pager must never subscribe to the entire history")
        }
        override fun observeChanges(conversationId: String): Flow<Unit> = version.map { Unit }
        private fun conversation(id: String) = rows.filter { it.conversationId == id }.sortedBy { it.timestamp }
        override suspend fun getRecent(conversationId: String, limit: Int) = conversation(conversationId).takeLast(limit)
        override suspend fun getBefore(conversationId: String, beforeId: String, limit: Int): List<Message> {
            val all = conversation(conversationId)
            return all.take(all.indexOfFirst { it.id == beforeId }.coerceAtLeast(0)).takeLast(limit)
        }
        override suspend fun getFrom(conversationId: String, fromId: String): List<Message> {
            val all = conversation(conversationId)
            val index = all.indexOfFirst { it.id == fromId }
            return if (index < 0) emptyList() else all.drop(index)
        }
        override suspend fun getByConversationIds(conversationIds: List<String>) = emptyList<Message>()
        override suspend fun insert(message: Message) { rows.add(message); version.value++ }
        override suspend fun insertAll(messages: List<Message>) { rows.addAll(messages); version.value++ }
        override suspend fun update(message: Message) { rows.removeAll { it.id == message.id }; rows.add(message); version.value++ }
        override suspend fun delete(id: String) { rows.removeAll { it.id == id }; version.value++ }
        override suspend fun deleteByConversationId(conversationId: String) { rows.removeAll { it.conversationId == conversationId }; version.value++ }
        override suspend fun getConversationIdById(id: String) = rows.firstOrNull { it.id == id }?.conversationId
    }

    private fun message(id: Int, role: MessageRole, conversation: String = "c") = Message(
        id = "$conversation-$id", conversationId = conversation, role = role,
        content = "message $id", timestamp = id.toLong(), status = MessageStatus.SENT
    )

    @Test
    fun loadsOlderPagesAndPreservesAnchorOnNewMessages() = runBlocking {
        val repository = FakeRepository()
        repeat(20) { repository.rows.add(message(it, if (it % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pager = ChatHistoryPager(repository, scope, { throw AssertionError(it) }, pageSize = 4)
            pager.start("c")
            withTimeout(5000) { pager.messages.first { it.size == 4 } }
            assertEquals("c-16", pager.messages.value.first().id)
            assertTrue(pager.hasOlder.value)
            pager.loadOlder()
            withTimeout(5000) { pager.messages.first { it.size == 8 } }
            assertEquals("c-12", pager.messages.value.first().id)
            repository.insert(message(20, MessageRole.USER))
            withTimeout(5000) { pager.messages.first { it.last().id == "c-20" } }
            assertEquals("c-12", pager.messages.value.first().id)
            assertEquals(0, repository.fullReads)
        } finally { scope.cancel() }
    }

    @Test
    fun pageBoundaryIncludesTheWholeToolTurn() = runBlocking {
        val repository = FakeRepository()
        repository.rows.add(message(0, MessageRole.USER))
        repeat(8) { repository.rows.add(message(it + 1, MessageRole.TOOL)) }
        repository.rows.add(message(9, MessageRole.ASSISTANT))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pager = ChatHistoryPager(repository, scope, { throw AssertionError(it) }, pageSize = 3)
            pager.start("c")
            withTimeout(5000) { pager.messages.first { it.size == 10 } }
            assertEquals("c-0", pager.messages.value.first().id)
            assertEquals(false, pager.hasOlder.value)
        } finally { scope.cancel() }
    }

    @Test
    fun deletingTheAnchorAndSwitchingConversationsRecovers() = runBlocking {
        val repository = FakeRepository()
        repeat(12) { repository.rows.add(message(it, if (it % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT)) }
        repeat(2) { repository.rows.add(message(it, MessageRole.USER, "other")) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pager = ChatHistoryPager(repository, scope, { throw AssertionError(it) }, pageSize = 4)
            pager.start("c")
            withTimeout(5000) { pager.messages.first { it.size == 4 } }
            repository.delete("c-8")
            withTimeout(5000) { pager.messages.first { rows -> rows.none { it.id == "c-8" } } }
            assertEquals("c-11", pager.messages.value.last().id)
            pager.start("other")
            withTimeout(5000) { pager.messages.first { it.size == 2 && it.all { m -> m.conversationId == "other" } } }
            assertEquals(false, pager.hasOlder.value)
            assertEquals(0, repository.fullReads)
        } finally { scope.cancel() }
    }


    @Test
    fun olderMessagesArrivingFromSyncBecomeLoadable() = runBlocking {
        val repository = FakeRepository()
        repeat(2) { repository.rows.add(message(it + 10, MessageRole.USER)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pager = ChatHistoryPager(repository, scope, { throw AssertionError(it) }, pageSize = 4)
            pager.start("c")
            withTimeout(5000) { pager.messages.first { it.size == 2 } }
            assertEquals(false, pager.hasOlder.value)
            repository.insert(message(1, MessageRole.USER))
            withTimeout(5000) { pager.hasOlder.first { it } }
            pager.loadOlder()
            withTimeout(5000) { pager.messages.first { it.firstOrNull()?.id == "c-1" } }
            Unit
        } finally { scope.cancel() }
    }

}
