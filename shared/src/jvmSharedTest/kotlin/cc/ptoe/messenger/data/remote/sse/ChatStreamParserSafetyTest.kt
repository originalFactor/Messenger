/*
 * Copyright 2026 originalFactor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package cc.ptoe.messenger.data.remote.sse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChatStreamParserSafetyTest {
    private val content = """{"choices":[{"delta":{"content":"hello"}}]}"""

    @Test fun providerErrorStopsTheStreamWithDetails() = runBlocking {
        val events = ChatStreamParser.parseToEvents(flowOf(content, """{"error":{"message":"quota exceeded"}}""", content, "[DONE]")).toList()
        assertEquals(2, events.size)
        assertEquals(ChatStreamEvent.Content("hello"), events.first())
        assertTrue((events.last() as ChatStreamEvent.Error).message.contains("quota exceeded"))
    }

    @Test fun malformedInputIsVisibleAndTerminal() = runBlocking {
        val events = ChatStreamParser.parseToEvents(flowOf("{invalid", content)).toList()
        assertEquals(1, events.size)
        assertTrue(events.single() is ChatStreamEvent.Error)
    }

    @Test fun downstreamCancellationAndErrorsAreNeverSwallowed() = runBlocking {
        assertFailsWith<CancellationException> {
            ChatStreamParser.parseToEvents(flowOf(content)).collect { throw CancellationException("cancel") }
        }
        assertFailsWith<IllegalArgumentException> {
            ChatStreamParser.parseToText(flowOf(content)).collect { throw IllegalArgumentException("consumer") }
        }
        Unit
    }
}
