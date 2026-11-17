/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomShutdownState
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class LocalMcpWorkerExclusionTest {
    @Test fun completedEvidenceRemainsAfterOldOwnerReferencesAreReleased(): Unit = runBlocking {
        val dir = Files.createTempDirectory("local-mcp-released-owner")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("one.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val key = LocalMcpWorkerExclusion.Key("owner", "turn", "call")
        suspend fun stopPreviousOwner() {
            val job = Job()
            val shutdown = RoomManagedShutdown { }
            shutdown.registerStop { job.cancelAndJoin(); LocalMcpWorkerExclusion.record(db, key, job, shutdown) }
            shutdown.enter()
            shutdown.stopAndAwait()
        }
        try {
            stopPreviousOwner()
            repeat(3) { System.gc(); kotlinx.coroutines.yield() }
            assertTrue(LocalMcpWorkerExclusion.provesStopped(db, key))
            LocalMcpWorkerExclusion.forget(db, key)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun workerCleanupMustFinishBeforeEvidenceCanBeUsed(): Unit = runBlocking {
        val dir = Files.createTempDirectory("local-mcp-held-worker")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("one.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val job = SupervisorJob()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val shutdown = RoomManagedShutdown { }
        val key = LocalMcpWorkerExclusion.Key("owner", "turn", "call")
        try {
            CoroutineScope(job + Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleanupEntered.complete(Unit); releaseCleanup.await() } }
            }
            shutdown.registerStop { job.cancelAndJoin(); LocalMcpWorkerExclusion.record(db, key, job, shutdown) }
            shutdown.enter()
            val stopping = async { shutdown.stopAndAwait() }
            withTimeout(5_000) { cleanupEntered.await() }
            assertFalse(LocalMcpWorkerExclusion.provesStopped(db, key))
            releaseCleanup.complete(Unit)
            withTimeout(5_000) { stopping.await() }
            assertTrue(LocalMcpWorkerExclusion.provesStopped(db, key))
        } finally { releaseCleanup.complete(Unit); job.cancelAndJoin(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun evidenceRequiresExactRoomAndCompletedRealShutdownIncludingRetry(): Unit = runBlocking {
        val dir = Files.createTempDirectory("local-mcp-stop-proof")
        val first = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("one.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val other = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("two.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val reopened = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("one.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val job = Job()
        var fail = true
        val shutdown = RoomManagedShutdown { check(!fail) { "local shutdown failed" } }
        val key = LocalMcpWorkerExclusion.Key("owner", "turn", "call")
        try {
            shutdown.registerStop {
                job.cancelAndJoin()
                LocalMcpWorkerExclusion.record(first, key, job, shutdown)
            }
            shutdown.enter()
            assertFalse(LocalMcpWorkerExclusion.provesStopped(first, key))
            assertFailsWith<IllegalStateException> { LocalMcpWorkerExclusion.record(first, key, job, shutdown) }
            val pending = async { shutdown.stopAndAwait() }
            withTimeout(5_000) { shutdown.state.first { it is RoomShutdownState.RecoveryRequired } }
            assertFalse(LocalMcpWorkerExclusion.provesStopped(first, key))
            assertFalse(LocalMcpWorkerExclusion.provesStopped(other, key))
            assertFalse(LocalMcpWorkerExclusion.provesStopped(reopened, key))
            fail = false
            shutdown.retryStop()
            withTimeout(5_000) { pending.await() }
            assertTrue(LocalMcpWorkerExclusion.provesStopped(first, key))
            assertFalse(LocalMcpWorkerExclusion.provesStopped(first, key.copy(callId = "different")))
            assertFalse(LocalMcpWorkerExclusion.provesStopped(other, key))
            LocalMcpWorkerExclusion.forget(first, key)
            assertFalse(LocalMcpWorkerExclusion.provesStopped(first, key))
        } finally {
            fail = false
            if (shutdown.state.value is RoomShutdownState.RecoveryRequired) shutdown.retryStop()
            first.close(); other.close(); reopened.close(); dir.toFile().deleteRecursively()
        }
    }
}
