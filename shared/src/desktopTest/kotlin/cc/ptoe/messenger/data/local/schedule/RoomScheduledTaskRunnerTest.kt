/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.schedule

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomScheduledTaskRunnerTest {
    @Test fun restartRefusesCancelledWorkerUntilItsNonCancellableCleanupEnds(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-restart-worker")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val recoveries = AtomicInteger()
        try {
            val rows = db.rows()
            rows.upsertAgents(listOf(Json.parseToJsonElement("""{"_id":"agent","isDefault":true,"defaultModelId":"m","toolsEnabled":false}""").jsonObject.agentRow(null)))
            rows.upsertProviders(listOf(ProviderRow("p", "Provider", "https://provider.test", "key", "chat", false, 1, 1)))
            rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 100, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
            val store = RoomScheduledTasks(db)
            val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            store.create(KotlinScheduledTask(id, "Reminder", "work", "agent", null, 1, 1))
            val runner = RoomScheduledTaskRunner(store, ScheduledTurnHost { _, record ->
                check(record("conversation")); entered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.complete(Unit); release.await() } }
            }, { emptySet() }, ScheduledRecoveryBarrier { recoveries.incrementAndGet(); Unit }, owner, { 2000 })
            val loop = runner.start()
            withTimeout(5_000) { entered.await() }
            val originalClaim = assertNotNull(store.active(id))
            ownerJob.cancel()
            withTimeout(5_000) { cleanup.await(); loop.join() }
            assertFailsWith<IllegalStateException> { runner.start() }
            assertEquals(1, recoveries.get())
            assertEquals(originalClaim, store.active(id))
            release.complete(Unit)
            withTimeout(5_000) { ownerJob.join() }
            assertEquals(null, store.active(id))
        } finally { release.complete(Unit); ownerJob.cancelAndJoin(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun restartRefusesCancelledLoopUntilItsCommittedBatchIsRetired(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-restart-loop")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.Default)
        val claimed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val recoveries = AtomicInteger()
        try {
            db.rows().upsertAgents(listOf(Json.parseToJsonElement("""{"_id":"agent","isDefault":true}""").jsonObject.agentRow(null)))
            val store = RoomScheduledTasks(db)
            val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            store.create(KotlinScheduledTask(id, "Reminder", "work", "agent", null, 1, 1))
            val runner = RoomScheduledTaskRunner(store, ScheduledTurnHost { _, _ -> error("must not start") },
                { emptySet() }, ScheduledRecoveryBarrier { recoveries.incrementAndGet(); Unit }, owner, { 2000 },
                claimDue = { now -> store.claimDue(now).also { claimed.complete(Unit); release.await() } })
            val loop = runner.start()
            withTimeout(5_000) { claimed.await() }
            val originalClaim = assertNotNull(store.active(id))
            loop.cancel()
            assertFailsWith<IllegalStateException> { runner.start() }
            assertEquals(1, recoveries.get())
            assertEquals(originalClaim, store.active(id))
            release.complete(Unit)
            withTimeout(5_000) { loop.join() }
            assertEquals(null, store.active(id))
            val restarted = runner.start()
            assertEquals(2, recoveries.get())
            restarted.cancelAndJoin()
        } finally { release.complete(Unit); ownerJob.cancelAndJoin(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun restartRefusesStandaloneTickUntilItsCommittedBatchIsRetired(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-restart-tick")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.Default)
        val claimed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val recoveries = AtomicInteger()
        try {
            db.rows().upsertAgents(listOf(Json.parseToJsonElement("""{"_id":"agent","isDefault":true}""").jsonObject.agentRow(null)))
            val store = RoomScheduledTasks(db)
            val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            store.create(KotlinScheduledTask(id, "Reminder", "work", "agent", null, 1, 1))
            val runner = RoomScheduledTaskRunner(store, ScheduledTurnHost { _, _ -> error("must not start") },
                { emptySet() }, ScheduledRecoveryBarrier { recoveries.incrementAndGet(); Unit }, owner, { 2000 },
                claimDue = { now -> store.claimDue(now).also { claimed.complete(Unit); release.await() } })
            val tick = async(Dispatchers.Default) { runner.tick() }
            withTimeout(5_000) { claimed.await() }
            val originalClaim = assertNotNull(store.active(id))
            tick.cancel()
            assertFailsWith<IllegalStateException> { runner.start() }
            assertEquals(0, recoveries.get())
            assertEquals(originalClaim, store.active(id))
            release.complete(Unit)
            assertFailsWith<CancellationException> { withTimeout(5_000) { tick.await() } }
            assertEquals(null, store.active(id))
            val restarted = runner.start()
            assertEquals(1, recoveries.get())
            restarted.cancelAndJoin()
        } finally { release.complete(Unit); ownerJob.cancelAndJoin(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun ownerCancellationAfterBatchClaimRetiresEveryUnstartedTask(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-batch-cancel")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.Default)
        val claimed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val hostCalls = AtomicInteger()
        val foreground = AtomicInteger()
        val statuses = kotlinx.coroutines.channels.Channel<Pair<String, String>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        try {
            val agent = Json.parseToJsonElement("""{"_id":"agent","isDefault":true}""").jsonObject.agentRow(null)
            db.rows().upsertAgents(listOf(agent))
            val store = RoomScheduledTasks(db)
            val first = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            val second = "d3bc81a8-0589-49da-8cb1-8dc6a82507c7"
            store.create(KotlinScheduledTask(first, "First", "work", "agent", null, 1, 1))
            store.create(KotlinScheduledTask(second, "Second", "work", "agent", null, 1, 1))
            val runner = RoomScheduledTaskRunner(store, ScheduledTurnHost { _, _ ->
                hostCalls.incrementAndGet(); error("A cancelled batch must not dispatch")
            }, { emptySet() }, ScheduledRecoveryBarrier {}, owner, { 2000 },
                object : ScheduledTaskLifecycle {
                    override fun beginAdmission(): () -> Unit { foreground.incrementAndGet(); return { foreground.decrementAndGet(); Unit } }
                    override fun status(taskId: String, status: String) { statuses.trySend(taskId to status) }
                },
                claimDue = { now -> store.claimDue(now).also { claimed.complete(Unit); release.await() } })
            owner.launch { runner.tick() }
            withTimeout(5_000) { claimed.await() }
            assertEquals(1, foreground.get())
            ownerJob.cancel()
            assertFalse(ownerJob.isCompleted)
            release.complete(Unit)
            withTimeout(5_000) { ownerJob.join() }
            assertEquals(0, foreground.get())
            assertEquals(0, hostCalls.get())
            assertEquals(null, store.active(first))
            assertEquals(null, store.active(second))
            assertEquals(setOf(first to "failed", second to "failed"), withTimeout(5_000) { setOf(statuses.receive(), statuses.receive()) })
            assertTrue(store.list().all { it.lastError == "Execution interrupted or cancelled" })
        } finally { release.complete(Unit); ownerJob.cancelAndJoin(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun foregroundAdmissionOverlapsQueuedWorkerAndItsLease(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-worker-handoff")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + dispatcher)
        val gateEntered = CountDownLatch(1)
        val gateRelease = CountDownLatch(1)
        val foreground = AtomicInteger()
        val claimed = CompletableDeferred<Unit>()
        val hostEntered = CompletableDeferred<Unit>()
        val hostRelease = CompletableDeferred<Unit>()
        try {
            val rows = db.rows()
            rows.upsertAgents(listOf(Json.parseToJsonElement("""{"_id":"agent","isDefault":true,"defaultModelId":"m","toolsEnabled":false}""").jsonObject.agentRow(null)))
            rows.upsertProviders(listOf(ProviderRow("p", "Provider", "https://provider.test", "key", "chat", false, 1, 1)))
            rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 100, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
            val store = RoomScheduledTasks(db)
            val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            store.create(KotlinScheduledTask(id, "Reminder", "work", "agent", null, 1, 1))
            val runner = RoomScheduledTaskRunner(store, ScheduledTurnHost { _, record ->
                check(record("conversation")); hostEntered.complete(Unit); hostRelease.await(); "conversation"
            }, { emptySet() }, ScheduledRecoveryBarrier {}, owner, { 2000 }, object : ScheduledTaskLifecycle {
                override fun beginAdmission(): () -> Unit { foreground.incrementAndGet(); return { foreground.decrementAndGet(); Unit } }
                override fun begin(taskId: String) { foreground.incrementAndGet() }
                override fun end(taskId: String) { foreground.decrementAndGet() }
            }, claimDue = { now -> store.claimDue(now).also { claimed.complete(Unit) } })
            owner.launch { gateEntered.countDown(); gateRelease.await() }
            assertTrue(withContext(Dispatchers.IO) { gateEntered.await(5, TimeUnit.SECONDS) })
            val tick = async(Dispatchers.Default) { runner.tick() }
            withTimeout(5_000) { claimed.await() }
            assertNotNull(store.active(id))
            assertEquals(1, foreground.get(), "queued work must retain the admission lease")
            assertFalse(tick.isCompleted)
            gateRelease.countDown()
            withTimeout(5_000) { hostEntered.await(); tick.await() }
            assertEquals(1, foreground.get(), "worker lease must overlap admission")
            hostRelease.complete(Unit)
            withTimeout(5_000) { while (foreground.get() != 0 || store.active(id) != null) yield() }
        } finally {
            gateRelease.countDown(); hostRelease.complete(Unit); ownerJob.cancelAndJoin()
            dispatcher.close(); db.close(); dir.toFile().deleteRecursively()
        }
    }

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
