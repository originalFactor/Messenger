/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.chat

import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.domain.model.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class KotlinToolApprovalTest {
    private val turn = KotlinTurnSnapshot("t", "c", KotlinTurnPhase.AUTO_APPROVAL_REQUESTING,
        Provider("p", "Provider", "https://example.test", "secret", createdAt = 0, updatedAt = 0),
        ChatCompletionRequestDto("model", emptyList()))
    private val work = KotlinApprovalWork(turn, ToolCallData("call", "edit", "{\"path\":\"x\"}"), "change x")

    @Test fun trustedReadOnlyBoundaryMatchesCore() {
        for (name in listOf("glob", "grep", "read", "messenger_skills")) assertTrue(isTrustedReadOnlyTool(ToolCallData("id", name, "{}")))
        for (action in listOf("overview", "list", "get")) assertTrue(isTrustedReadOnlyTool(ToolCallData("id", "messenger_learning", """{"action":"$action"}""")))
        assertTrue(isTrustedReadOnlyTool(ToolCallData("id", "messenger_artifacts", """{"action":"get"}""")))
        for (call in listOf(ToolCallData("id", "edit", "{}"), ToolCallData("id", "messenger_generate_image", "{}"),
            ToolCallData("id", "messenger_learning", """{"action":"upsert"}"""), ToolCallData("id", "messenger_learning", "bad"),
            ToolCallData("id", "messenger_artifacts", """{"action":"update"}"""))) assertFalse(isTrustedReadOnlyTool(call))
    }

    @Test fun reviewIsOneToolFreeNonStreamingRequestWithStrictDecision() = runBlocking {
        var count = 0
        val allowed = KotlinToolApproval.review(work) { request ->
            count++
            assertFalse(request.stream); assertNull(request.tools); assertEquals("model", request.model)
            assertTrue(request.messages.last().content.toString().contains("change x"))
            flowOf(ChatStreamEvent.Content("""{"approved":true}"""), ChatStreamEvent.Done("stop"))
        }
        assertTrue(allowed.approved); assertNull(allowed.failure); assertEquals(1, count)
        for (invalid in listOf("yes", "{}", """{"approved":"true"}""", """{"approved":true,"other":1}""", """{"approved":false,"approved":true}""", """{"approved":true} trailing""")) {
            val result = KotlinToolApproval.review(work) { flowOf(ChatStreamEvent.Content(invalid), ChatStreamEvent.Done("stop")) }
            assertFalse(result.approved); assertNotNull(result.failure)
        }
        val denied = KotlinToolApproval.review(work) { flowOf(ChatStreamEvent.Content("""{"approved":false}"""), ChatStreamEvent.Done("stop")) }
        assertFalse(denied.approved); assertNull(denied.failure)
    }

    @Test fun reviewErrorsAndCancellationNeverRetry() = runBlocking {
        var count = 0
        val failed = KotlinToolApproval.review(work) { count++; flowOf(ChatStreamEvent.Error("HTTP 429: quota")) }
        assertFalse(failed.approved); assertEquals("HTTP 429: quota", failed.failure); assertEquals(1, count)
        assertFailsWith<CancellationException> { KotlinToolApproval.review(work) { flow { throw CancellationException("stop") } } }
        Unit
    }
}
