/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.schedule

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomScheduledTaskRunnerTest {
    @Test fun disablingCancelsAndWaitsForTheActualHostBeforeReleasingClaim(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-runner")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val job = SupervisorJob()
        val owner = CoroutineScope(job + Dispatchers.Default)
        try {
            val rows = db.rows()
            val agent = Json.parseToJsonElement("""{"_id":"agent","isDefault":true,"defaultModelId":"m","toolsEnabled":true}""").jsonObject.agentRow(null)
            rows.upsertAgents(listOf(agent))
            rows.upsertProviders(listOf(ProviderRow("p", "Provider", "https://provider.test", "key", "chat", false, 1, 1)))
            rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 100, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
            val store = RoomScheduledTasks(db)
            val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            store.create(KotlinScheduledTask(id, "Reminder", "work", "agent", null, 1, 1))
            val entered = CompletableDeferred<Unit>()
            val cancelSeen = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val status = CompletableDeferred<String>()
            val starts = AtomicInteger()
            val host = ScheduledTurnHost { _, record ->
                starts.incrementAndGet()
                check(record("conversation"))
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    cancelSeen.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
            val runner = RoomScheduledTaskRunner(store, host, { setOf("read") }, ScheduledRecoveryBarrier {}, owner, { 2000 },
                object : ScheduledTaskLifecycle { override fun status(taskId: String, statusValue: String) { status.complete(statusValue) } })
            val loop = runner.start()
            withTimeout(10_000) { entered.await() }
            val current = store.list().single()
            store.update(current.copy(enabled = false))
            withTimeout(10_000) { cancelSeen.await() }
            assertTrue(store.active(id)!!.cancelRequested)
            assertTrue(store.claimDue(100_000).isEmpty())
            assertEquals(1, starts.get())
            release.complete(Unit)
            assertEquals("cancelled", withTimeout(10_000) { status.await() })
            assertEquals("Execution cancelled", store.list().single().lastError)
            assertEquals("conversation", store.list().single().lastConversationId)
            assertEquals(null, store.active(id))
            loop.cancelAndJoin()
        } finally { job.cancelAndJoin(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun screenBridgePreservesToolCeilingAndSettings(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-bridge")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val rows = db.rows()
            val agent = Json.parseToJsonElement("""{"_id":"agent","isDefault":true}""").jsonObject.agentRow(null)
            rows.upsertAgents(listOf(agent))
            val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            rows.upsertValues(listOf(KeyValueRow("scheduled_task:$id", """{"id":"$id","name":"Task","prompt":"work","agentId":"agent","intervalMinutes":1,"nextRunAt":10,"enabled":true,"allowedTools":["read"]}""")))
            val bridge = RoomScheduledTaskBridge(RoomScheduledTasks(db))
            assertFalse(Json.parseToJsonElement(bridge.request("GET", "/scheduled-tasks/tool-settings")).jsonObject["enabled"].toString().toBoolean())
            bridge.request("PUT", "/scheduled-tasks/tool-settings", """{"enabled":true}""")
            assertTrue(Json.parseToJsonElement(bridge.request("GET", "/scheduled-tasks/tool-settings")).jsonObject["enabled"].toString().toBoolean())
            val task = Json.parseToJsonElement(bridge.request("GET", "/scheduled-tasks")).jsonArray.single().jsonObject
            assertNotNull(task["allowedTools"])
            val editorBody = """{"id":"$id","name":"Changed","prompt":"work","agentId":"agent","intervalMinutes":1,"nextRunAt":10,"enabled":true}"""
            bridge.request("PUT", "/scheduled-tasks/$id", editorBody)
            val after = Json.parseToJsonElement(bridge.request("GET", "/scheduled-tasks")).jsonArray.single().jsonObject
            assertEquals("[\"read\"]", after["allowedTools"].toString())
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
