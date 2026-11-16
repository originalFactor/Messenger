/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.data.repository.room.RoomMessageRepository
import cc.ptoe.messenger.data.repository.room.RoomProjectRepository
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.model.Project
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RuntimeWorkspaceFenceTest {
    @Test fun invalidationCancelsWithoutWaitingAndRecoveryWaitsForRealStop(): Unit = runBlocking {
        val dir = Files.createTempDirectory("workspace-cancel")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val release = CompletableDeferred<Unit>()
        try {
            val tracker = InMemoryWorkspaceTracker()
            val host = object : TrackedWorkspaceHost {
                override val protocolAvailable = true
                override val supportedOperations = setOf("read")
                override suspend fun execute(token: String, workspace: String, call: ToolCallData): ToolExecutionResult = error("not used")
                override suspend fun status(token: String) = tracker.status(token)
                override suspend fun cancelAndAwaitStopped(token: String) = tracker.cancelAndAwaitStopped(token)
            }
            val fence = RuntimeWorkspaceFence.forDatabase(db)
            val started = CompletableDeferred<Unit>()
            val token = "d".repeat(64)
            val invocation = launch {
                runCatching { fence.execute(token, host) { dispatch ->
                    dispatch(); tracker.run(token) { started.complete(Unit); release.await() }
                } }
            }
            withTimeout(5000) { started.await() }
            fence.invalidateAdmissions()
            assertFalse(invocation.isCompleted)
            val recovery = async(start = CoroutineStart.UNDISPATCHED) { fence.cancelAndRecover(host) }
            assertFalse(recovery.isCompleted)
            release.complete(Unit)
            withTimeout(5000) { invocation.join(); recovery.await() }
            assertTrue(fence.mutate { true })
            assertFailsWith<IllegalStateException> { fence.execute(token, host) { it(); Unit } }
        } finally { release.complete(Unit); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun invalidationBeforeDispatchClosesAdmissionEvenWhenCallerIgnoresCancellation(): Unit = runBlocking {
        val dir = Files.createTempDirectory("workspace-admission")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val release = CompletableDeferred<Unit>()
        try {
            val host = object : TrackedWorkspaceHost {
                override val protocolAvailable = true
                override val supportedOperations = setOf("read")
                override suspend fun execute(token: String, workspace: String, call: ToolCallData): ToolExecutionResult = error("not used")
                override suspend fun status(token: String) = TrackedWorkspaceStatus.UNKNOWN
            }
            val fence = RuntimeWorkspaceFence.forDatabase(db)
            val entered = CompletableDeferred<Unit>()
            var dispatched = false
            val invocation = launch {
                runCatching { fence.execute("e".repeat(64), host) { mark ->
                    withContext(NonCancellable) { entered.complete(Unit); release.await(); mark(); dispatched = true }
                } }
            }
            withTimeout(5000) { entered.await() }
            fence.invalidateAdmissions()
            release.complete(Unit)
            withTimeout(5000) { invocation.join() }
            assertFalse(dispatched)
            assertFailsWith<IllegalStateException> { fence.mutate { Unit } }
            fence.recover(host)
            assertTrue(fence.mutate { true })
        } finally { release.complete(Unit); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun disconnectDuringCancellationCannotReleaseTheFence(): Unit = runBlocking {
        val dir = Files.createTempDirectory("workspace-disconnect")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val release = CompletableDeferred<Unit>()
        try {
            val tracker = InMemoryWorkspaceTracker()
            val host = object : TrackedWorkspaceHost {
                override val protocolAvailable = true
                override val supportedOperations = setOf("read")
                override suspend fun execute(token: String, workspace: String, call: ToolCallData): ToolExecutionResult = error("not used")
                override suspend fun status(token: String) = tracker.status(token)
                override suspend fun cancelAndAwaitStopped(token: String) = tracker.cancelAndAwaitStopped(token)
            }
            val fence = RuntimeWorkspaceFence.forDatabase(db)
            val started = CompletableDeferred<Unit>()
            val invocation = launch {
                runCatching { fence.execute("f".repeat(64), host) { mark ->
                    mark(); tracker.run("f".repeat(64)) { started.complete(Unit); release.await() }
                } }
            }
            withTimeout(5000) { started.await() }
            fence.invalidateAdmissions()
            tracker.disconnected()
            assertFailsWith<IllegalStateException> { fence.cancelAndRecover(host) }
            release.complete(Unit)
            withTimeout(5000) { invocation.join() }
            assertFailsWith<IllegalStateException> { fence.recover(host) }
            assertFailsWith<IllegalStateException> { fence.mutate { Unit } }
        } finally { release.complete(Unit); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun anotherInvalidationDuringStopVerificationCannotReopenAdmission(): Unit = runBlocking {
        val dir = Files.createTempDirectory("workspace-recovery-race")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        try {
            val checking = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val host = object : TrackedWorkspaceHost {
                override val protocolAvailable = true
                override val supportedOperations = setOf("read")
                override suspend fun execute(token: String, workspace: String, call: ToolCallData): ToolExecutionResult = error("not used")
                override suspend fun status(token: String): TrackedWorkspaceStatus {
                    checking.complete(Unit); release.await(); return TrackedWorkspaceStatus.STOPPED
                }
            }
            val fence = RuntimeWorkspaceFence.forDatabase(db)
            assertFailsWith<IllegalStateException> { fence.execute("1".repeat(64), host) { it(); error("unknown") } }
            val recovery = async { runCatching { fence.recover(host) } }
            withTimeout(5000) { checking.await() }
            fence.invalidateAdmissions()
            release.complete(Unit)
            assertTrue(withTimeout(5000) { recovery.await() }.isFailure)
            assertFailsWith<IllegalStateException> { fence.mutate { Unit } }
            fence.recover(host)
            assertTrue(fence.mutate { true })
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun anotherInvalidationDuringHostCancellationCannotReopenAdmission(): Unit = runBlocking {
        val dir = Files.createTempDirectory("workspace-cancel-race")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        try {
            val cancelling = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val host = object : TrackedWorkspaceHost {
                override val protocolAvailable = true
                override val supportedOperations = setOf("read")
                override suspend fun execute(token: String, workspace: String, call: ToolCallData): ToolExecutionResult = error("not used")
                override suspend fun status(token: String) = TrackedWorkspaceStatus.STOPPED
                override suspend fun cancelAndAwaitStopped(token: String): TrackedWorkspaceStatus {
                    cancelling.complete(Unit); release.await(); return TrackedWorkspaceStatus.STOPPED
                }
            }
            val fence = RuntimeWorkspaceFence.forDatabase(db)
            assertFailsWith<IllegalStateException> { fence.execute("2".repeat(64), host) { it(); error("unknown") } }
            fence.invalidateAdmissions()
            val recovery = async { runCatching { fence.cancelAndRecover(host) } }
            withTimeout(5000) { cancelling.await() }
            fence.invalidateAdmissions()
            release.complete(Unit)
            assertTrue(withTimeout(5000) { recovery.await() }.isFailure)
            assertFailsWith<IllegalStateException> { fence.mutate { Unit } }
            fence.recover(host)
            assertTrue(fence.mutate { true })
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun replacingTheAdapterCannotDiscardAnUncertainOperation(): Unit = runBlocking {
        val dir = Files.createTempDirectory("workspace-fence-owner")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val host = object : TrackedWorkspaceHost {
            override val protocolAvailable = true
            override val supportedOperations = setOf("read")
            override suspend fun execute(token: String, workspace: String, call: ToolCallData): ToolExecutionResult = error("must not replay")
            override suspend fun status(token: String) = TrackedWorkspaceStatus.STOPPED
        }
        try {
            suspend fun dispatchFromDiscardedAdapter() {
                val fence = RuntimeWorkspaceFence.forDatabase(db)
                assertFailsWith<IllegalStateException> { fence.execute("c".repeat(64), host) { it(); error("connection lost") } }
            }
            dispatchFromDiscardedAdapter()
            // The adapter and its fence are no longer referenced by the caller, but Room is live.
            repeat(3) { System.gc() }
            val replacement = RuntimeWorkspaceFence.forDatabase(db)
            assertFailsWith<IllegalStateException> { replacement.mutate { Unit } }
            replacement.recover(host)
            assertFailsWith<IllegalStateException> { replacement.execute("c".repeat(64), host) { it(); Unit } }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun projectWritesShareTheOwnerFenceAndWaitForStopEvidence(): Unit = runBlocking {
        val dir = Files.createTempDirectory("workspace-project-fence")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val release = CompletableDeferred<Unit>()
        try {
            val fence = RuntimeWorkspaceFence.forDatabase(db)
            assertSame(fence, RuntimeWorkspaceFence.forDatabase(db))
            val repository = RoomProjectRepository(db)
            val entered = CompletableDeferred<Unit>()
            var stopped = false
            val host = object : TrackedWorkspaceHost {
                override val protocolAvailable = true
                override val supportedOperations = setOf("read")
                override suspend fun execute(token: String, workspace: String, call: ToolCallData): ToolExecutionResult = error("not used")
                override suspend fun status(token: String) = if (stopped) TrackedWorkspaceStatus.STOPPED else TrackedWorkspaceStatus.UNKNOWN
            }
            val operation = async { fence.execute("a".repeat(64), host) { dispatch ->
                dispatch(); entered.complete(Unit); release.await()
            } }
            withTimeout(5_000) { entered.await() }
            val write = async(start = CoroutineStart.UNDISPATCHED) {
                repository.insert(Project("project", "Project", "/workspace", 1, 1))
            }
            assertFalse(write.isCompleted)
            assertEquals(null, db.rows().project("project"))
            release.complete(Unit)
            withTimeout(5_000) { operation.await(); write.await() }
            assertEquals("/workspace", db.rows().project("project")?.workspace)

            RoomAgentRepository(db).ensureDefaultAgent(Agent("agent", "Agent", systemPrompt = "prompt", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            db.rows().upsertConversations(listOf(ConversationRow("chat", "Chat", "provider", "agent", "project",
                null, null, null, null, null, null, null, true, 1, 1, null, null, null, 0, 0, 0)))
            assertFailsWith<IllegalStateException> { fence.execute("b".repeat(64), host) { it(); error("connection lost") } }
            assertFailsWith<IllegalStateException> { repository.update(Project("project", "Changed", "/new", 1, 2)) }
            assertEquals("/workspace", db.rows().project("project")?.workspace)
            RoomMessageRepository(db).insert(Message("error", "chat", MessageRole.ASSISTANT, "Connection lost",
                timestamp = 2, status = MessageStatus.ERROR, errorMessage = "Connection lost"))
            assertEquals("Connection lost", db.rows().message("error")?.errorMessage)
            stopped = true
            fence.recover(host)
            repository.update(Project("project", "Changed", "/new", 1, 2))
            assertEquals("/new", db.rows().project("project")?.workspace)
        } finally { release.complete(Unit); db.close(); dir.toFile().deleteRecursively() }
    }
    @Test fun mutationWaitsForTheActualOperationAndUnknownStopBlocksFurtherWork(): Unit = runBlocking {
        val dir = Files.createTempDirectory("workspace-fence")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        try {
            val fence = RuntimeWorkspaceFence.forDatabase(db)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val token = "a".repeat(64)
            val failed = "b".repeat(64)
            var stopped = false
            val host = object : TrackedWorkspaceHost {
                override val protocolAvailable = true
                override val supportedOperations = setOf("read")
                override suspend fun execute(token: String, workspace: String, call: ToolCallData): ToolExecutionResult = error("must not replay")
                override suspend fun status(token: String): TrackedWorkspaceStatus {
                    assertEquals(failed, token)
                    return if (stopped) TrackedWorkspaceStatus.STOPPED else TrackedWorkspaceStatus.UNKNOWN
                }
            }
            val invocation = async {
                fence.execute(token, host) { dispatch ->
                    dispatch(); started.complete(Unit); release.await(); "done"
                }
            }
            withTimeout(5_000) { started.await() }
            val mutation = async(start = CoroutineStart.UNDISPATCHED) {
                fence.mutate { db.rows().upsertValues(listOf(KeyValueRow("workspace-owner", "new"))) }
            }
            assertFalse(mutation.isCompleted)
            assertEquals(null, db.rows().getValue("workspace-owner"))
            release.complete(Unit)
            assertEquals("done", withTimeout(5_000) { invocation.await() })
            withTimeout(5_000) { mutation.await() }
            assertEquals("new", db.rows().getValue("workspace-owner")?.value)
            assertFailsWith<IllegalStateException> { fence.execute(token, host) { it(); Unit } }

            assertFailsWith<IllegalStateException> { fence.execute(failed, host) { it(); error("connection lost") } }
            var mutationRan = false
            assertFailsWith<IllegalStateException> { fence.mutate { mutationRan = true } }
            assertFalse(mutationRan)
            assertFailsWith<IllegalStateException> { fence.recover(host) }
            val wrongHost = object : TrackedWorkspaceHost by host {}
            stopped = true
            assertFailsWith<IllegalStateException> { fence.recover(wrongHost) }
            fence.recover(host)
            assertTrue(fence.mutate { true })
            assertFailsWith<IllegalStateException> { fence.execute(failed, host) { it(); Unit } }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
