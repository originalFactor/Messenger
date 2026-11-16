/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.sse.*
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.tool.ChatTool
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.test.*

class KotlinRuntimeToolAdapterTest {
    @Test fun runnerRevocationCancelsHostButKeepsErrorAndReservationUntilStop(): Unit = runBlocking {
        val dir = Files.createTempDirectory("turn-runtime-revocation")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val release = CompletableDeferred<Unit>()
        try {
            val rows = db.rows()
            rows.upsertProviders(listOf(ProviderRow("p", "P", "https://example.test", "key", "chat", false, 1, 1)))
            rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", true, false, false, true, 1, null, null)))
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "A", systemPrompt = "prompt", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
            rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, true, 1, 1, null, null, null, 0, 0, 0)))
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val tool = object : ChatTool {
                override val name = "read"
                override val description = "read"
                override val parametersJson = """{"type":"object"}"""
                override suspend fun execute(argumentsJson: String): ToolExecutionResult = error("Host only")
            }
            var runs = 0
            val host = object : TrackedWorkspaceHost {
                override val protocolAvailable = true
                override val supportedOperations = setOf("read")
                override suspend fun execute(token: String, workspace: String, call: ToolCallData): ToolExecutionResult {
                    runs++
                    try { started.complete(Unit); awaitCancellation() }
                    finally { withContext(NonCancellable) { cancelled.complete(Unit); release.await(); stopped.complete(Unit) } }
                }
                override suspend fun status(token: String) = if (stopped.isCompleted) TrackedWorkspaceStatus.STOPPED else TrackedWorkspaceStatus.RUNNING
                override suspend fun cancelAndAwaitStopped(token: String): TrackedWorkspaceStatus {
                    stopped.await(); return TrackedWorkspaceStatus.STOPPED
                }
            }
            val fence = RuntimeWorkspaceFence.forDatabase(db)
            val grant = KotlinRuntimeToolGrant("c", null, "/workspace", null, "host-build", listOf("read"))
            lateinit var store: RoomKotlinTurnStore
            val adapter = KotlinRuntimeToolAdapter(db, grant, RuntimeWorkspaceVerifier { true }, { store }, host, fence, { listOf(tool) }, { true })
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(adapter), adapter)
            val call = ToolCallData("tool", "read", "{}")
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> flowOf(ChatStreamEvent.Done("tool_calls", toolCalls = listOf(call))) }, adapter, this)
            val id = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(KotlinTurnCommand.Send("c", "hi", null))).turnId
            withTimeout(5000) { started.await() }
            fence.invalidateAdmissions()
            withTimeout(5000) { cancelled.await() }
            assertEquals(KotlinTurnPhase.EXECUTING_TOOL, store.snapshot(id).phase)
            assertIs<KotlinTurnAdmission.Rejected>(store.admit(KotlinTurnCommand.Send("c", "too early", null)))
            val recovery = async(start = CoroutineStart.UNDISPATCHED) { fence.cancelAndRecover(host) }
            assertFalse(recovery.isCompleted)
            release.complete(Unit)
            withTimeout(5000) { recovery.await(); runner.awaitStopped(id) }
            assertEquals(KotlinTurnPhase.INTERRUPTED_TOOL, store.snapshot(id).phase)
            val detail = assertNotNull(store.observeFailure(id).first())
            assertTrue(detail.contains("Workspace authority revoked"))
            val assistant = rows.messagesFor(listOf("c")).single { it.role == "assistant" }
            assertEquals("error", assistant.status)
            assertEquals(detail, assistant.errorMessage)
            assertIs<KotlinTurnAdmission.Rejected>(store.admit(KotlinTurnCommand.Send("c", "still reserved", null)))
            assertTrue(store.reconcileInterruptedTool(id, host))
            assertEquals(1, runs)
            assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "after", null)))
        } finally { release.complete(Unit); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun returnedToolWithoutStopProofKeepsTurnAndFenceReserved(): Unit = runBlocking {
        val dir = Files.createTempDirectory("turn-runtime-unknown")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val rows = db.rows()
            rows.upsertProviders(listOf(ProviderRow("p", "P", "https://example.test", "key", "chat", false, 1, 1)))
            rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", true, false, false, true, 1, null, null)))
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "A", systemPrompt = "prompt", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
            rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, true, 1, 1, null, null, null, 0, 0, 0)))
            val grant = KotlinRuntimeToolGrant("c", null, "/workspace", null, "host-build", listOf("read"))
            val tool = object : ChatTool {
                override val name = "read"
                override val description = "read"
                override val parametersJson = """{"type":"object"}"""
                override suspend fun execute(argumentsJson: String): ToolExecutionResult = error("Host only")
            }
            var runs = 0
            var stopped = false
            val host = object : TrackedWorkspaceHost {
                override val protocolAvailable = true
                override val supportedOperations = setOf("read")
                override suspend fun execute(token: String, workspace: String, call: ToolCallData): ToolExecutionResult { runs++; return ToolExecutionResult("output") }
                override suspend fun status(token: String) = if (stopped) TrackedWorkspaceStatus.STOPPED else TrackedWorkspaceStatus.UNKNOWN
            }
            val fence = RuntimeWorkspaceFence.forDatabase(db)
            lateinit var store: RoomKotlinTurnStore
            val adapter = KotlinRuntimeToolAdapter(db, grant, RuntimeWorkspaceVerifier { true }, { store }, host, fence, { listOf(tool) }, { true })
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(adapter), adapter)
            val call = ToolCallData("tool", "read", "{}")
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> flowOf(ChatStreamEvent.Done("tool_calls", toolCalls = listOf(call))) }, adapter, this)
            val id = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(KotlinTurnCommand.Send("c", "hi", null))).turnId
            withTimeout(5_000) { store.observe(id).first { it.phase == KotlinTurnPhase.INTERRUPTED_TOOL } }
            assertEquals(1, runs)
            assertIs<KotlinTurnAdmission.Rejected>(store.admit(KotlinTurnCommand.Send("c", "too early", null)))
            var mutationRan = false
            assertFailsWith<IllegalStateException> { fence.mutate { mutationRan = true } }
            assertFalse(mutationRan)
            assertFalse(store.reconcileInterruptedTool(id, host))
            stopped = true
            fence.recover(host)
            assertTrue(store.reconcileInterruptedTool(id, host))
            assertEquals(1, runs)
            assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "after", null)))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun changingWorkspacePolicyBeforeDispatchRejectsClaimAndNeverRunsHost(): Unit = runBlocking {
        val dir = Files.createTempDirectory("turn-runtime-policy")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val rows = db.rows()
            rows.upsertProviders(listOf(ProviderRow("p", "P", "https://example.test", "key", "chat", false, 1, 1)))
            rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", true, false, false, true, 1, null, null)))
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "A", systemPrompt = "prompt", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
            rows.upsertProjects(listOf(ProjectRow("project", "Project", "/workspace", "device", 1, 1)))
            val conversation = ConversationRow("c", "Chat", "p", "a", "project", null, null, null, null, null, null, null, true, 1, 1, null, null, null, 0, 0, 0)
            rows.upsertConversations(listOf(conversation))
            val grant = KotlinRuntimeToolGrant("c", "project", "/workspace", "device", "host-build", listOf("edit"))
            val tool = object : ChatTool {
                override val name = "edit"
                override val description = "edit"
                override val writeAccess = true
                override val parametersJson = """{"type":"object"}"""
                override suspend fun execute(argumentsJson: String): ToolExecutionResult = error("Host only")
            }
            var executions = 0
            val host = object : TrackedWorkspaceHost {
                override val protocolAvailable = true
                override val supportedOperations = setOf("edit")
                override suspend fun execute(token: String, workspace: String, call: ToolCallData): ToolExecutionResult { executions++; return ToolExecutionResult("done") }
                override suspend fun status(token: String) = TrackedWorkspaceStatus.STOPPED
            }
            val fence = RuntimeWorkspaceFence.forDatabase(db)
            lateinit var store: RoomKotlinTurnStore
            val adapter = KotlinRuntimeToolAdapter(db, grant, RuntimeWorkspaceVerifier { true }, { store }, host, fence, { listOf(tool) }, { true })
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(adapter), adapter)
            val call = ToolCallData("tool", "edit", "{}")
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> flowOf(ChatStreamEvent.Done("tool_calls", toolCalls = listOf(call))) }, adapter, this)
            val id = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(KotlinTurnCommand.Send("c", "hi", null))).turnId
            withTimeout(5_000) { store.observe(id).first { it.phase == KotlinTurnPhase.WAITING_APPROVAL } }
            val acquired = CompletableDeferred<Unit>()
            val change = CompletableDeferred<Unit>()
            val changed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val mutation = launch { fence.mutate {
                acquired.complete(Unit); change.await()
                rows.upsertConversations(listOf(conversation.copy(writable = false)))
                changed.complete(Unit); release.await()
            } }
            try {
                withTimeout(5_000) { acquired.await() }
                runner.approve(id, "tool", true)
                withTimeout(5_000) { store.observe(id).first { it.phase == KotlinTurnPhase.EXECUTING_TOOL } }
                change.complete(Unit)
                withTimeout(5_000) { changed.await() }
                assertFailsWith<IllegalStateException> { store.claimRuntimeToolInvocation(id, call, grant) }
            } finally { change.complete(Unit); release.complete(Unit) }
            withTimeout(5_000) { mutation.join(); store.observe(id).first { it.phase == KotlinTurnPhase.FAILED } }
            assertEquals(0, executions)
            val detail = assertNotNull(store.observeFailure(id).first())
            assertEquals("Runtime tool permission or workspace is no longer available", detail)
            val assistant = rows.messagesFor(listOf("c")).single { it.role == "assistant" }
            assertEquals("error", assistant.status)
            assertEquals(detail, assistant.errorMessage)
            assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "after", null)))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun approvalDispatchAndCancellationHoldReservationUntilToolReturns() = runBlocking {
        val dir = Files.createTempDirectory("turn-runtime")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val rows = db.rows()
            rows.upsertProviders(listOf(ProviderRow("p", "P", "https://example.test", "key", "chat", false, 1, 1)))
            rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", true, false, false, true, 1, null, null)))
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "A", systemPrompt = "prompt", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
            rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, true, 1, 1, null, null, null, 0, 0, 0)))
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var runs = 0
            val tool = object : ChatTool {
                override val name = "edit"
                override val description = "edit"
                override val writeAccess = true
                override val parametersJson = """{"type":"object"}"""
                override suspend fun execute(argumentsJson: String): ToolExecutionResult { runs++; started.complete(Unit); finish.await(); return ToolExecutionResult("done") }
            }
            val grant = KotlinRuntimeToolGrant("c", null, "/workspace", null, "host-build", listOf("edit"))
            lateinit var store: RoomKotlinTurnStore
            val host = object : TrackedWorkspaceHost {
                var stopped = false
                override val protocolAvailable = true
                override val supportedOperations = setOf("glob", "read", "edit", "create")
                override suspend fun execute(token: String, workspace: String, call: ToolCallData) = tool.execute(call.arguments).also { stopped = true }
                override suspend fun status(token: String) = if (stopped) TrackedWorkspaceStatus.STOPPED else TrackedWorkspaceStatus.RUNNING
            }
            val adapter = KotlinRuntimeToolAdapter(db, grant, RuntimeWorkspaceVerifier { it.hostIdentity == "host-build" }, { store }, host,
                RuntimeWorkspaceFence.forDatabase(db), { listOf(tool) }, { true })
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(adapter), adapter)
            val call = ToolCallData("tool", "edit", "{}")
            var leases = 0
            val ended = CompletableDeferred<Unit>()
            val statuses = mutableListOf<String>()
            val lifecycle = object : KotlinTurnLifecycle {
                override fun begin(conversationId: String) { leases++ }
                override fun status(conversationId: String, status: String) { statuses.add(status) }
                override fun end(conversationId: String) { leases--; ended.complete(Unit) }
            }
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> flowOf(ChatStreamEvent.Done("tool_calls", toolCalls = listOf(call))) }, adapter, this, lifecycle)
            val id = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(KotlinTurnCommand.Send("c", "hi", null))).turnId
            withTimeout(5000) { store.observe(id).first { it.phase == KotlinTurnPhase.WAITING_APPROVAL } }
            assertEquals(0, runs)
            assertEquals(1, leases)
            assertTrue("approval" in statuses)
            runner.approve(id, "tool", true)
            withTimeout(5000) { started.await() }
            assertEquals(KotlinTurnPhase.EXECUTING_TOOL, store.snapshot(id).phase)
            // A claimed invocation must never be released by the pre-dispatch path.
            assertFailsWith<IllegalStateException> { store.rejectUndispatchedRuntimeTool(id, call, "rejected") }
            assertEquals(KotlinTurnPhase.EXECUTING_TOOL, store.snapshot(id).phase)
            val duplicate = async { runCatching { adapter.execute(id, call) } }
            val cancel = async { runner.cancel(id) }
            cancel.await()
            assertEquals(KotlinTurnPhase.CANCELLING_TOOL, store.snapshot(id).phase)
            assertEquals(1, leases)
            assertIs<KotlinTurnAdmission.Rejected>(store.admit(KotlinTurnCommand.Send("c", "too early", null)))
            finish.complete(Unit)
            assertTrue(withTimeout(5000) { duplicate.await() }.isFailure)
            withTimeout(5000) { store.observe(id).first { it.phase == KotlinTurnPhase.CANCELLED } }
            withTimeout(5000) { ended.await() }
            assertEquals(0, leases)
            assertTrue("cancelled" in statuses)
            assertEquals(1, runs)
            assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "after", null)))
        } finally { db.close(); dir.toFile().deleteRecursively() }
        Unit
    }
}
