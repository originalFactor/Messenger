/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ProjectRow
import cc.ptoe.messenger.data.chat.InMemoryWorkspaceTracker
import cc.ptoe.messenger.data.chat.TrackedWorkspaceStatus
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RoomWorkspaceExecutionFenceTest {
    private val scope = KotlinRemoteControlScope("account", "generation", "https://cloud.example", "device")
    private val call = RoomInboundWorkspaceCall("call", "sender", "device", "project", "conversation", "read", "{}")
    private suspend fun fixture(block: suspend (LocalDatabase, RoomWorkspaceExecutionFence) -> Unit) {
        val dir = Files.createTempDirectory("workspace-execution-fence")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            db.rows().upsertValues(listOf(
                KeyValueRow("kotlin_sync_session", "[\"account\",\"generation\"]"), KeyValueRow("device_id", "device"),
                KeyValueRow("cloud_session", "messenger_session=secret"), KeyValueRow("cloud_session_host", "https://cloud.example"),
                KeyValueRow("cloud_user_json", "{\"id\":\"account\"}"),
            ))
            db.rows().upsertProjects(listOf(ProjectRow("project", "Project", "/workspace", "device", 1, 1)))
            block(db, RoomWorkspaceExecutionFence.forDatabase(db))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun legacyExecutionCannotBypassDurableReplayProtectionInEitherOrder(): Unit = runBlocking {
        fixture { db, gate ->
            assertSame(gate, RoomWorkspaceExecutionFence.forDatabase(db))
            var calls = 0
            assertFailsWith<IllegalStateException> { gate.execute(scope, call) { calls++; WorkspaceExecutionOutcome.Stopped("unexpected", false) } }
            val result = gate.executeDurable(scope, "messenger_session=secret", call) { _, _ ->
                calls++; WorkspaceExecutionOutcome.Stopped("done", false)
            }
            assertFailsWith<IllegalStateException> { gate.execute(scope, call) { calls++; WorkspaceExecutionOutcome.Stopped("unexpected", false) } }
            val stopped = object : WorkspaceInvocationStopBarrier {
                override suspend fun <T> withStopped(ref: WorkspaceInvocationRef, action: suspend () -> T): T = action()
            }
            gate.confirmResultDelivered(result.ref, stopped)
            assertFailsWith<IllegalStateException> { gate.execute(scope, call) { calls++; WorkspaceExecutionOutcome.Stopped("unexpected", false) } }
            assertFailsWith<IllegalStateException> { gate.executeDurable(scope, "messenger_session=secret", call) { _, _ ->
                calls++; WorkspaceExecutionOutcome.Stopped("unexpected", false)
            } }
            assertEquals(1, calls)
        }
    }

    @Test fun unknownStopBlocksBothFutureWorkAndProtectedMutations(): Unit = runBlocking {
        fixture { _, gate ->
            assertFailsWith<IllegalStateException> { gate.executeDurable(scope, "messenger_session=secret", call) { _, _ -> WorkspaceExecutionOutcome.Unknown } }
            assertFailsWith<IllegalStateException> { gate.mutate { Unit } }
            assertFailsWith<IllegalStateException> { gate.executeDurable(scope, "messenger_session=secret", call.copy(callId = "new")) { _, _ -> WorkspaceExecutionOutcome.Stopped("unexpected", false) } }
        }
    }

    @Test fun durableInvocationWaitsForTrackedCompletionBeforeReleasingWriterAndRejectsReplay(): Unit = runBlocking {
        fixture { _, gate ->
            val tracker = InMemoryWorkspaceTracker()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val barrier = object : WorkspaceInvocationStopBarrier {
                override suspend fun <T> withStopped(ref: WorkspaceInvocationRef, action: suspend () -> T): T {
                    check(tracker.status(ref.owner) == TrackedWorkspaceStatus.STOPPED)
                    return action()
                }
            }
            var calls = 0
            val work = async { gate.executeDurable(scope, "messenger_session=secret", call) { ref, grant ->
                tracker.run(ref.owner) {
                    calls++
                    assertEquals("/workspace", grant.workspace)
                    entered.complete(Unit); release.await()
                    WorkspaceExecutionOutcome.Stopped("done", false)
                }
            } }
            withTimeout(5_000) { entered.await() }
            val writer = async { runCatching { gate.mutate { "wrote" } } }
            delay(100)
            assertFalse(writer.isCompleted)
            release.complete(Unit)
            val result = withTimeout(5_000) { work.await() }
            assertEquals("done", result.outcome.output)
            assertEquals(true, withTimeout(5_000) { writer.await() }.isFailure)
            assertNotNull(gate.pendingRecovery())
            gate.confirmResultDelivered(result.ref, barrier)
            assertEquals("wrote", gate.mutate { "wrote" })
            assertNull(gate.pendingRecovery())
            assertFailsWith<IllegalStateException> {
                gate.executeDurable(scope, "messenger_session=secret", call) { _, _ ->
                    calls++; WorkspaceExecutionOutcome.Stopped("unexpected", false)
                }
            }
            assertEquals(1, calls)
        }
    }

    @Test fun cancelledTrackedInvocationRetainsReservationUntilExplicitProofBackedAcknowledgement(): Unit = runBlocking {
        fixture { _, gate ->
            val tracker = InMemoryWorkspaceTracker()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val barrier = object : WorkspaceInvocationStopBarrier {
                override suspend fun <T> withStopped(ref: WorkspaceInvocationRef, action: suspend () -> T): T {
                    check(tracker.status(ref.owner) == TrackedWorkspaceStatus.STOPPED)
                    return action()
                }
            }
            val work = async { gate.executeDurable(scope, "messenger_session=secret", call) { ref, _ ->
                tracker.run(ref.owner) {
                    entered.complete(Unit); release.await()
                    WorkspaceExecutionOutcome.Stopped("result that caller may not have received", false)
                }
            } }
            withTimeout(5_000) { entered.await() }
            work.cancel()
            val writer = async { runCatching { gate.mutate { "wrote" } } }
            delay(100)
            assertFalse(writer.isCompleted)
            release.complete(Unit)
            withTimeout(5_000) { work.cancelAndJoin() }
            assertEquals(true, withTimeout(5_000) { writer.await() }.isFailure)
            val pending = assertNotNull(gate.pendingRecovery())
            assertFailsWith<IllegalStateException> { gate.acknowledgeUnknown(pending, object : WorkspaceInvocationStopBarrier {
                override suspend fun <T> withStopped(ref: WorkspaceInvocationRef, action: suspend () -> T): T = error("stop is unverified")
            }) }
            assertNotNull(gate.pendingRecovery())
            gate.acknowledgeUnknown(pending, barrier)
            assertNull(gate.pendingRecovery())
            assertEquals("wrote", gate.mutate { "wrote" })
            assertFailsWith<IllegalStateException> { gate.executeDurable(scope, "messenger_session=secret", call) { _, _ ->
                WorkspaceExecutionOutcome.Stopped("unexpected", false)
            } }
        }
    }

    @Test fun cancellationAfterWorkerReturnsKeepsUndeliveredResultReviewable(): Unit = runBlocking {
        fixture { _, gate ->
            val tracker = InMemoryWorkspaceTracker()
            val barrier = object : WorkspaceInvocationStopBarrier {
                override suspend fun <T> withStopped(ref: WorkspaceInvocationRef, action: suspend () -> T): T {
                    check(tracker.status(ref.owner) == TrackedWorkspaceStatus.STOPPED)
                    return action()
                }
            }
            val work = async { gate.executeDurable(scope, "messenger_session=secret", call) { ref, _ ->
                val result = tracker.run(ref.owner) { WorkspaceExecutionOutcome.Stopped("sent but unobserved", false) }
                kotlinx.coroutines.currentCoroutineContext()[Job]!!.cancel()
                result
            } }
            withTimeout(5_000) { work.join() }
            assertTrue(work.isCancelled)
            val pending = assertNotNull(gate.pendingRecovery())
            assertFailsWith<IllegalStateException> { gate.mutate { Unit } }
            gate.acknowledgeUnknown(pending, barrier)
            assertNull(gate.pendingRecovery())
        }
    }
}
