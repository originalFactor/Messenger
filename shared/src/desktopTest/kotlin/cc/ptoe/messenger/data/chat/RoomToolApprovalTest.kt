/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.*

class RoomToolApprovalTest {
    private val delegate = Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, method, _ -> error("Unexpected call: ${method.name}") } as CoreBridge
    private val policy = KotlinTurnToolPolicy { _, _, _ -> true }
    private fun preparer(name: String) = object : KotlinTurnRequestPreparer {
        override fun prepare(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow, model: ModelRow, provider: ProviderRow, history: List<MessageRow>) =
            ChatCompletionRequestDto(model.modelId, emptyList(), tools = listOf(ToolSpecDto("function", ToolSpecFunctionDto(name, "tool", buildJsonObject {}))))
        override fun toolGrant() = KotlinRuntimeToolGrant("c", null, "/workspace", null, "host", listOf(name))
    }
    private suspend fun seed(db: LocalDatabase) {
        val rows = db.rows()
        rows.upsertProviders(listOf(ProviderRow("p", "provider", "https://example.test", "key", "chat", false, 1, 1)))
        rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", true, false, false, false, 1, null, null)))
        RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "system", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
        rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
    }
    private suspend fun <T> database(block: suspend (LocalDatabase, CoroutineScope) -> T): T {
        val dir = Files.createTempDirectory("room-tool-approval")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try { seed(db); return block(db, scope) } finally { scope.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun readOnlyBypassesApprovalButWriteWithoutUiIsDenied() = runBlocking {
        database { db, _ ->
            val read = RoomKotlinTurnStore(db, preparer("read"), policy)
            val first = assertIs<KotlinTurnAdmission.Accepted>(read.admit(KotlinTurnCommand.Send("c", "read", null, supportsToolApproval = false))).turnId
            assertTrue(read.claim(first, false)); read.beginProvider(first)
            val readCall = ToolCallData("r", "read", "{}")
            read.completeProvider(first, listOf(readCall), null)
            assertEquals(KotlinTurnPhase.READY_TOOL, read.snapshot(first).phase)
            assertEquals(readCall, read.beginTool(first)); read.cancel(first); read.release(first)
            val write = RoomKotlinTurnStore(db, preparer("write"), policy)
            val second = assertIs<KotlinTurnAdmission.Accepted>(write.admit(KotlinTurnCommand.Send("c", "write", null, supportsToolApproval = false))).turnId
            assertTrue(write.claim(second, false)); write.beginProvider(second)
            write.completeProvider(second, listOf(ToolCallData("w", "write", "{}")), null)
            assertEquals(KotlinTurnPhase.APPROVAL_UNAVAILABLE, write.snapshot(second).phase)
            write.rejectUnavailableApproval(second)
            assertTrue(db.rows().messagesFor(listOf("c")).any { it.role == "tool" && it.content.contains("cannot request") })
            write.cancel(second); write.release(second)
        }
    }

    @Test fun cancelledUndispatchedRuntimeCallReleasesOnlyItsOwnReservation(): Unit = runBlocking {
        database { db, _ ->
            val store = RoomKotlinTurnStore(db, preparer("read"), policy)
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "read", null))).turnId
            assertTrue(store.claim(id, false)); store.beginProvider(id)
            val call = ToolCallData("r", "read", "{}")
            store.completeProvider(id, listOf(call), null)
            assertEquals(call, store.beginTool(id))
            assertFailsWith<IllegalStateException> {
                store.rejectUndispatchedRuntimeTool(id, call.copy(callId = "another"), "rejected")
            }
            assertEquals(KotlinTurnPhase.EXECUTING_TOOL, store.snapshot(id).phase)
            store.cancel(id)
            assertIs<KotlinTurnAdmission.Rejected>(store.admit(KotlinTurnCommand.Send("c", "too early", null)))
            store.rejectUndispatchedRuntimeTool(id, call, "rejected")
            assertEquals(KotlinTurnPhase.CANCELLED, store.snapshot(id).phase)
            assertNull(store.observeFailure(id).first())
            assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "after", null)))
        }
    }

    @Test fun automaticReviewUsesOneNoToolsRequestAndRunsOnlyApprovedCall() = runBlocking {
        database { db, scope ->
            val store = RoomKotlinTurnStore(db, preparer("write"), policy)
            store.setToolApprovalMode("automatic")
            var streams = 0; var reviews = 0; var executions = 0
            val call = ToolCallData("call", "write", "{}")
            val provider = object : KotlinTurnProvider {
                override fun stream(provider: Provider, request: ChatCompletionRequestDto): Flow<ChatStreamEvent> = flow {
                    if (++streams == 1) emit(ChatStreamEvent.Done("tool_calls", toolCalls = listOf(call)))
                    else { emit(ChatStreamEvent.Content("answer")); emit(ChatStreamEvent.Done("stop")) }
                }
                override fun complete(provider: Provider, request: ChatCompletionRequestDto): Flow<ChatStreamEvent> {
                    reviews++; assertFalse(request.stream); assertNull(request.tools); assertFalse(provider.nativeWebSearch)
                    assertTrue(request.messages.last().content.toString().contains("user request"))
                    return flowOf(ChatStreamEvent.Content("""{"approved":true}"""), ChatStreamEvent.Done("stop"))
                }
            }
            val runner = KotlinChatTurnRunner(store, provider, KotlinTurnToolExecutor { id, requested ->
                executions++; store.claimToolInvocation(id, requested); ToolExecutionResult("done")
            }, scope)
            val id = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(KotlinTurnCommand.Send("c", "user request", null, supportsToolApproval = false))).turnId
            withTimeout(5000) { runner.awaitStopped(id) }
            assertEquals(KotlinTurnPhase.FINISHED, store.snapshot(id).phase)
            assertEquals(1, reviews); assertEquals(1, executions); assertEquals(2, streams)
        }
    }

    @Test fun invalidReviewIsVisibleAndNeverExecutesOrRetries() = runBlocking {
        database { db, scope ->
            val store = RoomKotlinTurnStore(db, preparer("write"), policy); store.setToolApprovalMode("automatic")
            var streams = 0; var reviews = 0; var executions = 0
            val call = ToolCallData("call", "write", "{}")
            val provider = object : KotlinTurnProvider {
                override fun stream(provider: Provider, request: ChatCompletionRequestDto) = flow {
                    if (++streams == 1) emit(ChatStreamEvent.Done("tool_calls", toolCalls = listOf(call)))
                    else { emit(ChatStreamEvent.Content("answer")); emit(ChatStreamEvent.Done("stop")) }
                }
                override fun complete(provider: Provider, request: ChatCompletionRequestDto): Flow<ChatStreamEvent> {
                    reviews++; return flowOf(ChatStreamEvent.Content("""{"approved":false,"approved":true}"""), ChatStreamEvent.Done("stop"))
                }
            }
            val runner = KotlinChatTurnRunner(store, provider, KotlinTurnToolExecutor { _, _ -> executions++; error("must not execute") }, scope)
            val bridge = KotlinBackgroundTurnBridge(delegate, store, runner, scope)
            val id = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(KotlinTurnCommand.Send("c", "request", null))).turnId
            withTimeout(5000) { runner.awaitStopped(id) }
            assertEquals(KotlinTurnPhase.FINISHED, store.snapshot(id).phase)
            assertEquals(1, reviews); assertEquals(0, executions)
            assertTrue(db.rows().messagesFor(listOf("c")).any { it.status == "error" && it.errorMessage!!.contains("invalid decision") })
            val events = mutableListOf<String>(); bridge.observeBackgroundTurn("c") { events.add(it) }
            assertTrue(events.any { it.contains("ApprovalFailed") && it.contains("invalid decision") })
        }
    }

    @Test fun recoveryDeniesAnInFlightReviewWithoutResendingIt() = runBlocking {
        database { db, scope ->
            val old = RoomKotlinTurnStore(db, preparer("write"), policy); old.setToolApprovalMode("automatic")
            val id = assertIs<KotlinTurnAdmission.Accepted>(old.admit(KotlinTurnCommand.Send("c", "request", null))).turnId
            assertTrue(old.claim(id, false)); old.beginProvider(id)
            old.completeProvider(id, listOf(ToolCallData("call", "write", "{}")), null)
            assertEquals(KotlinTurnPhase.AUTO_APPROVAL_PENDING, old.snapshot(id).phase)
            assertNotNull(old.beginAutomaticApproval(id)); old.release(id)
            val current = RoomKotlinTurnStore(db, preparer("write"), policy)
            val provider = object : KotlinTurnProvider {
                override fun stream(provider: Provider, request: ChatCompletionRequestDto) = flowOf(ChatStreamEvent.Content("answer"), ChatStreamEvent.Done("stop"))
                override fun complete(provider: Provider, request: ChatCompletionRequestDto): Flow<ChatStreamEvent> = error("review must not be resent")
            }
            val runner = KotlinChatTurnRunner(current, provider, KotlinTurnToolExecutor { _, _ -> error("must not execute") }, scope)
            runner.recover(id); withTimeout(5000) { runner.awaitStopped(id) }
            assertEquals(KotlinTurnPhase.FINISHED, current.snapshot(id).phase)
            assertTrue(db.rows().messagesFor(listOf("c")).any { it.status == "error" && it.errorMessage!!.contains("interrupted") })
        }
    }

    @Test fun switchingToManualWhileReviewRunsDoesNotApproveTheCall() = runBlocking {
        database { db, _ ->
            val store = RoomKotlinTurnStore(db, preparer("write"), policy); store.setToolApprovalMode("automatic")
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "request", null))).turnId
            assertTrue(store.claim(id, false)); store.beginProvider(id)
            store.completeProvider(id, listOf(ToolCallData("call", "write", "{}")), null)
            assertNotNull(store.beginAutomaticApproval(id))
            store.setToolApprovalMode("manual")
            store.completeAutomaticApproval(id, "call", KotlinApprovalResult(true))
            assertEquals(KotlinTurnPhase.WAITING_APPROVAL, store.snapshot(id).phase)
            store.cancel(id); store.release(id)
        }
    }

    @Test fun togglingApprovalSettingsCannotReviveAnOldReview() = runBlocking {
        database { db, _ ->
            val store = RoomKotlinTurnStore(db, preparer("write"), policy)
            store.setToolApprovalMode("automatic")
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "request", null))).turnId
            assertTrue(store.claim(id, false)); store.beginProvider(id)
            store.completeProvider(id, listOf(ToolCallData("call", "write", "{}")), null)
            assertNotNull(store.beginAutomaticApproval(id))
            store.setToolApprovalMode("manual")
            store.setToolApprovalMode("automatic")
            store.completeAutomaticApproval(id, "call", KotlinApprovalResult(true))
            assertEquals(KotlinTurnPhase.READY_PROVIDER, store.snapshot(id).phase)
            assertTrue(db.rows().messagesFor(listOf("c")).any { it.status == "error" && it.errorMessage!!.contains("settings changed") })
            assertTrue(db.rows().messagesFor(listOf("c")).any { it.role == "tool" && it.content.contains("settings changed") })
            store.cancel(id); store.release(id)
        }
    }

    @Test fun settingsAreValidatedAndPersisted() = runBlocking {
        database { db, scope ->
            val store = RoomKotlinTurnStore(db, preparer("write"), policy)
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> flowOf() }, KotlinTurnToolExecutor { _, _ -> error("unused") }, scope)
            val bridge = KotlinBackgroundTurnBridge(delegate, store, runner, scope)
            assertEquals("""{"mode":"manual"}""", bridge.toolApprovalSettings("GET"))
            assertEquals("""{"mode":"automatic"}""", bridge.toolApprovalSettings("PUT", """{"mode":"automatic"}"""))
            assertFailsWith<IllegalArgumentException> { bridge.toolApprovalSettings("PUT", """{"mode":"automatic","extra":true}""") }
            assertEquals("automatic", store.toolApprovalMode())
        }
    }

    @Test fun manualApprovalTimeoutDeniesTheToolAndContinues() = runBlocking {
        database { db, scope ->
            val store = RoomKotlinTurnStore(db, preparer("write"), policy)
            var streams = 0; var executions = 0
            val call = ToolCallData("call", "write", "{}")
            val provider = KotlinTurnProvider { _, _ -> flow {
                if (++streams == 1) emit(ChatStreamEvent.Done("tool_calls", toolCalls = listOf(call)))
                else { emit(ChatStreamEvent.Content("answer")); emit(ChatStreamEvent.Done("stop")) }
            } }
            val runner = KotlinChatTurnRunner(store, provider, KotlinTurnToolExecutor { _, _ -> executions++; error("must not execute") }, scope, approvalTimeoutMillis = 30)
            val id = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(KotlinTurnCommand.Send("c", "request", null))).turnId
            withTimeout(5000) { runner.awaitStopped(id) }
            assertEquals(KotlinTurnPhase.FINISHED, store.snapshot(id).phase)
            assertEquals(0, executions)
            assertTrue(db.rows().messagesFor(listOf("c")).any { it.role == "tool" && it.content.contains("timed out") })
        }
    }
}
