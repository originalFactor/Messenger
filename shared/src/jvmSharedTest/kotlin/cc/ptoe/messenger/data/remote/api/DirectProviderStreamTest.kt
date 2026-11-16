/*
 * Copyright 2026 originalFactor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package cc.ptoe.messenger.data.remote.api

import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.model.ApiFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DirectProviderStreamTest {
    private val provider = Provider("id", "name", "https://example.test", "key", createdAt = 0, updatedAt = 0)
    private val request = ChatCompletionRequestDto(model = "model", messages = emptyList())

    @Test fun incompleteStreamSurfacesErrorWithoutRetrying() = runBlocking {
        var calls = 0
        val transport = DirectProviderStream { _, sent ->
            calls++
            assertTrue(sent.stream)
            flowOf(ChatStreamEvent.Content("partial"))
        }
        val events = transport.stream(provider, request).toList()
        assertEquals(1, calls)
        assertEquals(ChatStreamEvent.Content("partial"), events.first())
        assertTrue(events.last() is ChatStreamEvent.Error)
    }

    @Test fun errorsAndCompletionAreTerminal() = runBlocking {
        val done = ChatStreamEvent.Done("stop")
        assertEquals(listOf(done), DirectProviderStream { _, _ -> flowOf(done) }.stream(provider, request).toList())
        val failure = DirectProviderStream { _, _ -> flow { throw IllegalStateException("provider detail") } }
        assertEquals(listOf(ChatStreamEvent.Error("provider detail")), failure.stream(provider, request).toList())
        val setupFailure = DirectProviderStream { _, _ -> throw IllegalArgumentException("invalid URL") }
        assertEquals(listOf(ChatStreamEvent.Error("invalid URL")), setupFailure.stream(provider, request).toList())
    }

    @Test fun cancellationPropagatesAndNativeSearchIsNotSilentlyDropped() = runBlocking {
        val cancelled = DirectProviderStream { _, _ -> flow { throw CancellationException("stop") } }
        assertFailsWith<CancellationException> { cancelled.stream(provider, request).toList() }
        var called = false
        val transport = DirectProviderStream { _, _ -> called = true; flowOf() }
        assertTrue(transport.stream(provider.copy(nativeWebSearch = true), request).toList().single() is ChatStreamEvent.Error)
        assertEquals(false, called)
        val enabled = provider.copy(apiFormat = ApiFormat.RESPONSES, nativeWebSearch = true)
        val supported = DirectProviderStream { received, _ ->
            assertTrue(received.nativeWebSearch)
            flowOf(ChatStreamEvent.Done("stop"))
        }
        assertTrue(supported.stream(enabled, request).toList().single() is ChatStreamEvent.Done)
    }
}
