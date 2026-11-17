/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.chat

import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinConversationTitleTest {
    private val input = KotlinTitleInput("title-model", "Write a title", "First question", "First answer", 0.5f, 0.9f, 32)

    @Test fun usesTitleModelAndSanitizesWithoutSplittingUnicode() = runBlocking {
        var calls = 0
        val result = KotlinConversationTitle.generate(input) { request ->
            calls++
            assertEquals("title-model", request.model)
            assertNull(request.tools)
            assertFalse(request.stream)
            assertTrue(request.messages.last().content.toString().contains("First answer"))
            flowOf(ChatStreamEvent.Content("<think>private</think>「${"😀".repeat(31)}」\nsecond line"), ChatStreamEvent.Done("stop"))
        }
        assertEquals("😀".repeat(30) + "...", result.title)
        assertNull(result.failure)
        assertEquals(1, calls)
        assertTrue(KotlinConversationTitle.isUntitled("New Chat"))
        assertFalse(KotlinConversationTitle.isUntitled(" renamed "))
    }

    @Test fun failureFallsBackAndNeverRetries() = runBlocking {
        var calls = 0
        val failed = KotlinConversationTitle.generate(input) {
            calls++
            flowOf(ChatStreamEvent.Content("partial"), ChatStreamEvent.Error("HTTP 429: quota"))
        }
        assertEquals(KotlinTitleResult("First question", "HTTP 429: quota"), failed)
        assertEquals(1, calls)
        val incomplete = KotlinConversationTitle.generate(input) { flowOf(ChatStreamEvent.Content("partial")) }
        assertEquals("First question", incomplete.title)
        assertTrue(incomplete.failure.orEmpty().contains("without a completion"))
        val empty = KotlinConversationTitle.generate(input) { flowOf(ChatStreamEvent.Done("stop")) }
        assertEquals(KotlinTitleResult("First question"), empty)
    }

    @Test fun cancellationIsPropagated() = runBlocking {
        var calls = 0
        assertFailsWith<CancellationException> {
            KotlinConversationTitle.generate(input) {
                calls++
                flow { throw CancellationException("stop") }
            }
        }
        assertEquals(1, calls)
    }
}
