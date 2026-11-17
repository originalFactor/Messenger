/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.cloud.RoomCloudProviderGate
import cc.ptoe.messenger.data.local.artifact.RoomArtifactService
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.asset.RoomAssetGate
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ModelRow
import cc.ptoe.messenger.data.local.room.ProviderRow
import cc.ptoe.messenger.data.local.room.agentRow
import cc.ptoe.messenger.data.local.schedule.KotlinScheduledTask
import cc.ptoe.messenger.data.local.schedule.RoomScheduledTasks
import cc.ptoe.messenger.data.local.schedule.ScheduledRecoveryBarrier
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.repository.LearningOwnership
import cc.ptoe.messenger.domain.chat.KotlinTurnProvider
import cc.ptoe.messenger.presentation.platform.ForegroundTaskRegistry
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomSchedulerNotificationLeaseTest {
    @Test fun completedAndCancelledRunsHoldLeaseThroughCommittedCleanup(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-scheduler-events")
        if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            dir.toFile().deleteRecursively()
            return@runBlocking
        }
        val root = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val owner = RoomManagedShutdown { }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val calls = PartialChatCalls()
        val assetGate = RoomAssetGate()
        val cloudGate = RoomCloudProviderGate.forDao(db.cloudSync())
        val previousBegin = ForegroundTaskRegistry.begin
        val previousEnd = ForegroundTaskRegistry.end
        val active = AtomicInteger()
        val providerEntered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        val providerCalls = AtomicInteger()
        val now = AtomicLong(1)
        try {
            ForegroundTaskRegistry.begin = { active.incrementAndGet(); Unit }
            ForegroundTaskRegistry.end = { active.decrementAndGet(); Unit }
            val rows = db.rows()
            rows.upsertAgents(listOf(Json.parseToJsonElement("""{"_id":"agent","isDefault":true,"defaultModelId":"m","toolsEnabled":false}""").jsonObject.agentRow(null)))
            rows.upsertProviders(listOf(ProviderRow("p", "Provider", "https://example.test", "key", "chat", false, 1, 1)))
            rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
            owner.enter()
            val composition = KotlinRoomBridgeComposition.attach(db, owner)
            composition.bindChatWorkers(job)
            val events = Channel<Pair<List<String>, Int>>(Channel.UNLIMITED)
            composition.bridge.subscribe { kind, _ -> if (kind == "localTaskStatus") error("listener failure") }
            composition.bridge.subscribe { kind, ids -> if (kind == "localTaskStatus") events.trySend(ids to active.get()) }
            val assets = CoreAssetService(root, db.assetMetadata(), assetGate)
            val tasks = RoomScheduledTasks(db)
            val provider = KotlinTurnProvider { _, _ ->
                if (providerCalls.incrementAndGet() == 1) flowOf(ChatStreamEvent.Content("answer"), ChatStreamEvent.Done("stop"))
                else flow {
                    providerEntered.complete(Unit)
                    try { awaitCancellation() } finally {
                        cleanupEntered.complete(Unit)
                        withContext(NonCancellable) { cleanupRelease.await() }
                    }
                }
            }
            val scheduler = IsolatedRoomScheduler(db, tasks, LearningOwnership(db, assets), RoomArtifactService(db, assets),
                cloudGate, scope, ScheduledRecoveryBarrier { }, { now.get() }, calls,
                taskStatus = composition::scheduledTaskStatus, providerOverride = provider)
            scheduler.start()
            val first = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            val second = "d3bc81a8-0589-49da-8cb1-8dc6a82507c7"
            tasks.create(KotlinScheduledTask(first, "A", "first", "agent", null, 1, 1000))
            now.set(1000)
            scheduler.tick()
            val completed = withTimeout(10_000) { events.receive() }
            assertEquals(listOf("completed", "scheduled", first), completed.first)
            assertTrue(completed.second >= 1)
            assertNull(tasks.active(first))
            assertNull(tasks.list().single().lastError)
            withTimeout(10_000) { while (active.get() != 0) yield() }
            assertEquals(0, active.get())

            tasks.create(KotlinScheduledTask(second, "B", "second", "agent", null, 1, 1))
            scheduler.tick()
            withTimeout(10_000) { providerEntered.await() }
            assertEquals(1, active.get())
            tasks.update(tasks.list().single { it.id == second }.copy(enabled = false))
            withTimeout(10_000) { cleanupEntered.await() }
            assertEquals(1, active.get())
            assertNotNull(tasks.active(second))
            assertTrue(events.tryReceive().isFailure)
            cleanupRelease.complete(Unit)
            val cancelled = withTimeout(10_000) { events.receive() }
            assertEquals(listOf("cancelled", "scheduled", second), cancelled.first)
            assertTrue(cancelled.second >= 1)
            assertNull(tasks.active(second))
            assertEquals("Execution cancelled", tasks.list().single { it.id == second }.lastError)
            withTimeout(10_000) { while (active.get() != 0) yield() }
            assertEquals(0, active.get())
            assertFalse(events.tryReceive().isSuccess)
            assertEquals(2, providerCalls.get())
        } finally {
            cleanupRelease.complete(Unit)
            calls.stopAndJoin()
            job.cancelAndJoin()
            owner.stopAndAwait()
            cloudGate.shutdown()
            assetGate.shutdown()
            ForegroundTaskRegistry.begin = previousBegin
            ForegroundTaskRegistry.end = previousEnd
            db.close()
            dir.toFile().deleteRecursively()
        }
    }
}
