/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.data.repository.room.RoomCurrentAgentRepository
import cc.ptoe.messenger.data.repository.room.RoomMessageRepository
import cc.ptoe.messenger.domain.model.Agent
import java.nio.file.Files
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RoomSelectionAndPagingTest {
    @Test fun selectedAgentUsesImportedKvAndRespectsReplacementLock(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-selected-agent")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        try {
            val agents = RoomAgentRepository(db)
            val seed = Agent("default", "Default", systemPrompt = "", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1)
            agents.ensureDefaultAgent(seed)
            agents.insert(seed.copy(id = "second", name = "Second", isDefault = false))
            db.rows().upsertValues(listOf(KeyValueRow("current_agent_id", "second")))
            val current = RoomCurrentAgentRepository(db, agents)
            assertEquals("second", current.currentAgent.first()?.id)
            current.setCurrentAgentId(null)
            assertNull(current.currentAgentId.first())
            assertEquals("default", current.currentAgent.first()?.id)
            current.setCurrentAgentId("")
            assertNull(current.currentAgentId.first())
            assertEquals("default", current.currentAgent.first()?.id)
            db.rows().upsertValues(listOf(KeyValueRow(CLOUD_REPLACEMENT_LOCK_KEY, "pending")))
            assertFailsWith<IllegalStateException> { current.setCurrentAgentId("second") }
            assertEquals("", db.rows().getValue("current_agent_id")?.value)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun pagingPreservesInsertionOrderForTiesAndRejectsForeignAnchors(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-message-paging")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        try {
            val rows = db.rows()
            val agent = AgentRow("a", "Agent", null, "", "", null, null, null, null, null,
                true, false, false, false, false, false, false, null, null, null, "chat", false, false, "", 1, 1)
            rows.upsertAgents(listOf(agent))
            fun conversation(id: String) = ConversationRow(id, "Chat", "p", "a", null, null, null, null,
                null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)
            rows.upsertConversations(listOf(conversation("c"), conversation("other")))
            fun message(id: String, conversation: String, time: Long) = MessageRow(id, conversation, "user", id, null, time, "sent", null)
            // Deliberately use IDs whose lexicographical order differs from insertion order.
            rows.upsertMessages(listOf(message("z", "c", 1), message("x", "c", 2),
                message("a", "c", 2), message("foreign", "other", 2), message("n", "c", 3)))
            val repo = RoomMessageRepository(db)
            assertEquals(listOf("z", "x", "a", "n"), repo.getByConversationId("c").first().map { it.id })
            assertEquals(listOf("a", "n"), repo.getRecent("c", 2).map { it.id })
            assertEquals(listOf("z", "x"), repo.getBefore("c", "a", 5).map { it.id })
            assertEquals(listOf("a", "n"), repo.getFrom("c", "a").map { it.id })
            assertEquals(emptyList(), repo.getBefore("c", "foreign", 2))
            assertEquals(emptyList(), repo.getFrom("c", "missing"))
            assertEquals(emptyList(), repo.getRecent("c", 0))
            assertFailsWith<IllegalArgumentException> { repo.getRecent("c", -1) }
            val invalidations = Channel<Unit>(Channel.UNLIMITED)
            val observer = launch { repo.observeChanges("c").collect { invalidations.send(Unit) } }
            try {
                withTimeout(5_000) { invalidations.receive() }
                // Count is unchanged, but edited content must still refresh a loaded page.
                rows.upsertMessages(listOf(message("a", "c", 2).copy(content = "edited")))
                withTimeout(5_000) { invalidations.receive() }
                assertEquals("edited", repo.getFrom("c", "a").first().content)
            } finally { observer.cancel(); invalidations.close() }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
