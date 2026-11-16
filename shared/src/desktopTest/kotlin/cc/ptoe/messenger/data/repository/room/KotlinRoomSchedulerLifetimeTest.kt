/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.cloud.RoomCloudProviderGate
import cc.ptoe.messenger.data.local.artifact.RoomArtifactService
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.asset.RoomAssetGate
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.schedule.KotlinScheduledTask
import cc.ptoe.messenger.data.local.schedule.RoomScheduledTasks
import cc.ptoe.messenger.data.local.schedule.ScheduledRecoveryBarrier
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomStoreOwnership
import cc.ptoe.messenger.data.migration.RoomStoreStartup
import cc.ptoe.messenger.data.repository.LearningOwnership
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KotlinRoomSchedulerLifetimeTest {
    private class Ownership : RoomStoreOwnership {
        @Volatile var held = false
        override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T {
            check(!held); held = true
            return try { block() } finally { held = false }
        }
    }
    private fun room(path: Path) = Room.databaseBuilder<LocalDatabase>(name = path.toString())
        .setDriver(BundledSQLiteDriver()).build()

    @Test fun shutdownWaitsForClaimCleanupAndRetainedSchedulerRejectsNewWork(): Unit = runBlocking {
        val dir = Files.createTempDirectory("scheduler-owner-lifetime")
        val root = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val source = dir.resolve("missing-core.db")
        val target = dir.resolve("room.db")
        val taskId = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
        val ownership = Ownership()
        val shutdown = RoomManagedShutdown { }
        val holdClaim = AtomicBoolean(false)
        val firstPoll = CompletableDeferred<Unit>()
        val claimed = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val exit = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<IsolatedRoomScheduler>()
        try {
            val startup = async {
                RoomStoreStartup.withRoomStore(source, target, ownership, shutdown, ::room) { db ->
                    val gate = RoomAssetGate()
                    shutdown.registerStop { gate.shutdown() }
                    val cloud = RoomCloudProviderGate.forDao(db.cloudSync())
                    shutdown.registerStop { cloud.shutdown() }
                    val processJob = SupervisorJob()
                    val processScope = CoroutineScope(processJob + Dispatchers.IO)
                    val calls = PartialChatCalls()
                    shutdown.registerStop { calls.stopAndJoin(); processJob.cancelAndJoin() }
                    val assets = CoreAssetService(root, db.assetMetadata(), gate)
                    val tasks = RoomScheduledTasks(db)
                    val scheduler = IsolatedRoomScheduler(db, tasks, LearningOwnership(db, assets), RoomArtifactService(db, assets),
                        cloud, processScope, ScheduledRecoveryBarrier { }, { 1000 }, calls,
                        claimDue = { now ->
                            val claims = tasks.claimDue(now)
                            if (holdClaim.get() && claims.isNotEmpty()) {
                                claimed.complete(Unit)
                                try { awaitCancellation() } finally {
                                    withContext(NonCancellable) { cleanupStarted.complete(Unit); releaseCleanup.await() }
                                }
                            } else firstPoll.complete(Unit)
                            claims
                        })
                    assertFailsWith<IllegalStateException> { scheduler.tick() }
                    scheduler.start()
                    withTimeout(5_000) { firstPoll.await() }
                    val agentId = db.rows().defaultAgents().single().id
                    tasks.create(KotlinScheduledTask(taskId, "Task", "run", agentId, intervalMinutes = 1, nextRunAt = 1))
                    holdClaim.set(true)
                    ready.complete(scheduler)
                    exit.await()
                }
            }
            val scheduler = withTimeout(5_000) { ready.await() }
            val ticking = async(start = CoroutineStart.UNDISPATCHED) { runCatching { scheduler.tick() } }
            withTimeout(5_000) { claimed.await() }
            exit.complete(Unit)
            withTimeout(5_000) { cleanupStarted.await() }
            assertTrue(ownership.held)
            assertFalse(startup.isCompleted)
            releaseCleanup.complete(Unit)
            withTimeout(5_000) { assertTrue(ticking.await().isFailure); startup.await() }
            assertFalse(ownership.held)
            assertFailsWith<IllegalStateException> { scheduler.tick() }
            assertFailsWith<IllegalStateException> { scheduler.start() }
            val reopened = room(target)
            try {
                assertNotNull(RoomScheduledTasks(reopened).active(taskId))
                assertEquals("Execution interrupted before completion", RoomScheduledTasks(reopened).list().single().lastError)
            } finally { reopened.close() }
        } finally { releaseCleanup.complete(Unit); exit.complete(Unit); dir.toFile().deleteRecursively() }
    }
}
