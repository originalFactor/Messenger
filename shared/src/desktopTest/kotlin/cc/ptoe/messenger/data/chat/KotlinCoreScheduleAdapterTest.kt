/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.local.schedule.RoomScheduledTasks
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.model.Agent
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class KotlinCoreScheduleAdapterTest {
    private suspend fun seed(db: LocalDatabase) {
        val rows = db.rows()
        rows.upsertProviders(listOf(ProviderRow("p", "provider", "https://example.test", "key", "chat", false, 1, 1)))
        rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", true, false, false, false, 1, null, null)))
        RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "system", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
        rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, true, 1, 1, null, null, null, 0, 0, 0)))
        RoomScheduledTasks(db).setToolEnabled(true)
    }
    private val call = ToolCallData("call", "messenger_schedule_task", """{"name":"Reminder","prompt":"Do work","intervalMinutes":10,"firstRunDelayMinutes":1,"projectless":true}""")

    @Test fun approvedProjectlessTaskUsesScopedTransactionalAuthorization() = runBlocking {
        val dir = Files.createTempDirectory("core-schedule")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            lateinit var store: RoomKotlinTurnStore
            val tools = KotlinCoreScheduleAdapter.create(db, "c", { store }) { 1000 }
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(tools), tools)
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "remind me", null))).turnId
            assertTrue(store.claim(id, false)); store.beginProvider(id); store.completeProvider(id, listOf(call), null)
            store.decideApproval(id, "call", true); assertEquals(call, store.beginTool(id))
            val result = tools.execute(id, call)
            assertFalse(result.isError, result.output)
            assertNotNull(result.structuredContent)
            assertEquals(1, RoomScheduledTasks(db).list().size)
            assertFailsWith<IllegalStateException> { tools.execute(id, call) }
            store.completeTool(id, call, result); store.cancel(id); store.release(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun revocationAndScheduledProvenancePreventCreation() = runBlocking {
        val dir = Files.createTempDirectory("core-schedule-policy")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            lateinit var store: RoomKotlinTurnStore
            val tools = KotlinCoreScheduleAdapter.create(db, "c", { store }) { 1000 }
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(tools), tools)
            assertIs<KotlinTurnAdmission.Rejected>(store.admit(KotlinTurnCommand.Send("c", "remind me", null, scheduledRun = true)))
            assertTrue(db.rows().messagesFor(listOf("c")).isEmpty())
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "remind me", null))).turnId
            assertTrue(store.claim(id, false)); store.beginProvider(id); store.completeProvider(id, listOf(call), null)
            store.decideApproval(id, "call", true); store.beginTool(id)
            RoomScheduledTasks(db).setToolEnabled(false)
            assertFailsWith<IllegalStateException> { tools.execute(id, call) }
            assertTrue(RoomScheduledTasks(db).list().isEmpty())
            store.cancel(id); store.release(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
