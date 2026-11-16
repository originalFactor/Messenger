/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.chat

import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.dto.UsageDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinChatTurnRunnerTest {
    private val call = ToolCallData("call-1", "write", "{}")
    private val provider = Provider("p", "provider", "https://example.test", "key", createdAt = 1, updatedAt = 1)
    private fun snapshot(phase: KotlinTurnPhase) = KotlinTurnSnapshot("turn", "conversation", phase, provider, ChatCompletionRequestDto("model", emptyList()), call)

    private class MemoryStore(initial: KotlinTurnSnapshot) : KotlinTurnStore {
        val state = MutableStateFlow(initial)
        var modelAvailable = true
        var admitted = 0
        var claimed = false
        var content = ""
        val failures = mutableListOf<String>()
        var approvalRecorded = false
        var completedTools = 0
        override suspend fun admit(command: KotlinTurnCommand): KotlinTurnAdmission {
            if (!modelAvailable) return KotlinTurnAdmission.ModelRequired
            admitted++
            return KotlinTurnAdmission.Accepted(state.value.id)
        }
        override suspend fun claim(turnId: String, recovery: Boolean): Boolean = if (claimed) false else { claimed = true; true }
        override suspend fun release(turnId: String) { claimed = false }
        override suspend fun snapshot(turnId: String) = state.value
        override fun observe(turnId: String) = state
        override suspend fun beginProvider(turnId: String): KotlinTurnSnapshot { state.value = state.value.copy(phase = KotlinTurnPhase.REQUESTING_PROVIDER); return state.value }
        override suspend fun appendContent(turnId: String, content: String) { this.content += content }
        override suspend fun setReasoningFormat(turnId: String, format: String) = Unit
        override suspend fun completeProvider(turnId: String, calls: List<ToolCallData>, usage: UsageDto?) {
            state.value = state.value.copy(phase = if (calls.isEmpty()) KotlinTurnPhase.FINISHED else KotlinTurnPhase.WAITING_APPROVAL, pendingTool = calls.firstOrNull())
        }
        override suspend fun decideApproval(turnId: String, callId: String, allow: Boolean) {
            check(state.value.phase == KotlinTurnPhase.WAITING_APPROVAL && state.value.pendingTool?.callId == callId)
            approvalRecorded = true
            state.value = state.value.copy(phase = if (allow) KotlinTurnPhase.READY_TOOL else KotlinTurnPhase.FINISHED)
        }
        override suspend fun beginTool(turnId: String): ToolCallData {
            check(approvalRecorded)
            val call = checkNotNull(state.value.pendingTool)
            state.value = state.value.copy(phase = KotlinTurnPhase.EXECUTING_TOOL)
            return call
        }
        override suspend fun completeTool(turnId: String, call: ToolCallData, result: ToolExecutionResult) {
            completedTools++
            state.value = state.value.copy(phase = KotlinTurnPhase.READY_PROVIDER, pendingTool = null)
        }
        override suspend fun fail(turnId: String, detail: String) { failures.add(detail); state.value = state.value.copy(phase = KotlinTurnPhase.FAILED) }
        override suspend fun cancel(turnId: String) { state.value = state.value.copy(phase = KotlinTurnPhase.CANCELLED) }
    }

    private suspend fun MemoryStore.await(phase: KotlinTurnPhase) = withTimeout(5000) { state.first { it.phase == phase } }

    @Test fun modelRequiredDoesNotStartAnyOperation() = runBlocking {
        val store = MemoryStore(snapshot(KotlinTurnPhase.READY_PROVIDER)).apply { modelAvailable = false }
        var calls = 0
        val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> calls++; flowOf() }, KotlinTurnToolExecutor { _, _ -> error("unexpected tool") }, this)
        for (command in listOf(KotlinTurnCommand.Send("c", "hello", null), KotlinTurnCommand.Retry("c", "m"), KotlinTurnCommand.Regenerate("c", "m"))) {
            assertEquals(KotlinTurnAdmission.ModelRequired, runner.submit(command))
        }
        assertEquals(0, store.admitted)
        assertEquals(0, calls)
    }

    @Test fun approvalIsPersistedBeforeToolAndTurnContinues() = runBlocking {
        val store = MemoryStore(snapshot(KotlinTurnPhase.READY_PROVIDER))
        var streams = 0
        var tools = 0
        val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ ->
            streams++
            if (streams == 1) flowOf(ChatStreamEvent.Content("thinking"), ChatStreamEvent.Done("tool_calls", toolCalls = listOf(call)))
            else flowOf(ChatStreamEvent.Content("answer"), ChatStreamEvent.Done("stop"))
        }, KotlinTurnToolExecutor { _, _ -> tools++; assertTrue(store.approvalRecorded); ToolExecutionResult("ok") }, this)
        runner.submit(KotlinTurnCommand.Send("conversation", "hi", null))
        store.await(KotlinTurnPhase.WAITING_APPROVAL)
        assertEquals(0, tools)
        runner.approve("turn", "call-1", true)
        store.await(KotlinTurnPhase.FINISHED)
        assertEquals(1, tools)
        assertEquals(1, store.completedTools)
        assertEquals("thinkinganswer", store.content)
    }

    @Test fun recoveryNeverRepeatsAmbiguousProviderOrToolWork() = runBlocking {
        for (phase in listOf(KotlinTurnPhase.REQUESTING_PROVIDER, KotlinTurnPhase.EXECUTING_TOOL)) {
            val store = MemoryStore(snapshot(phase))
            var executions = 0
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> executions++; flowOf() }, KotlinTurnToolExecutor { _, _ -> executions++; ToolExecutionResult("unexpected") }, this)
            runner.recover("turn")
            store.await(KotlinTurnPhase.FAILED)
            assertEquals(0, executions)
            assertTrue(store.failures.single().contains("interrupted"))
        }
    }

    @Test fun errorAndCancellationBecomeDurableTerminalStates() = runBlocking {
        val failure = MemoryStore(snapshot(KotlinTurnPhase.READY_PROVIDER))
        val failedRunner = KotlinChatTurnRunner(failure, KotlinTurnProvider { _, _ -> flowOf(ChatStreamEvent.Error("HTTP 429: quota")) }, KotlinTurnToolExecutor { _, _ -> error("unexpected") }, this)
        failedRunner.submit(KotlinTurnCommand.Send("conversation", "hi", null))
        failure.await(KotlinTurnPhase.FAILED)
        assertEquals(listOf("HTTP 429: quota"), failure.failures)
        val waiting = MemoryStore(snapshot(KotlinTurnPhase.WAITING_APPROVAL))
        val waitingRunner = KotlinChatTurnRunner(waiting, KotlinTurnProvider { _, _ -> flowOf() }, KotlinTurnToolExecutor { _, _ -> error("unexpected") }, this)
        waitingRunner.recover("turn")
        waitingRunner.cancel("turn")
        waiting.await(KotlinTurnPhase.CANCELLED)
        Unit
    }
}
