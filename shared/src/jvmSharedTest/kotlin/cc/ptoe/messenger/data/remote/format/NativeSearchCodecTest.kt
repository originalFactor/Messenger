/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.remote.format

import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.domain.model.ApiFormat
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeSearchCodecTest {
    private val request = ChatCompletionRequestDto(model = "model", messages = emptyList())

    @Test fun searchIsExplicitAndLimitedToSupportedFormats() {
        val responses = ApiFormatRequests.encode(ApiFormat.RESPONSES, request, true)
        assertEquals("web_search", responses.getValue("tools").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
        val messages = ApiFormatRequests.encode(ApiFormat.MESSAGES, request, true)
        val search = messages.getValue("tools").jsonArray.single().jsonObject
        assertEquals("web_search_20250305", search.getValue("type").jsonPrimitive.content)
        assertEquals("5", search.getValue("max_uses").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { ApiFormatRequests.encode(ApiFormat.CHAT, request, true) }
        assertFailsWith<IllegalArgumentException> { ApiFormatRequests.encode(ApiFormat.INTERACTIONS, request, true) }
    }

    @Test fun citationsAreSafeAndDeduplicatedAcrossResponseSnapshots() = runBlocking {
        val events = listOf(
            """{"type":"response.output_text.delta","delta":"answer"}""",
            """{"type":"response.output_text.annotation.added","annotation":{"url":"https://example.com/a b","title":"[News]"}}""",
            """{"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","annotations":[{"url":"https://example.com/a b","title":"[News]"},{"url":"javascript:bad"}]}]}]}}"""
        )
        val result = ApiFormatStreamParser.parseToEvents(ApiFormat.RESPONSES, events.asFlow()).toList()
        assertEquals("answer [\\[News\\]](<https://example.com/a%20b>)", result.filterIsInstance<ChatStreamEvent.Content>().joinToString("") { it.text })
        assertTrue(result.last() is ChatStreamEvent.Done)
    }

    @Test fun hostedFailureAndPauseNeverLookLikeSuccess() = runBlocking {
        for (payload in listOf(
            """{"type":"content_block_start","content_block":{"type":"web_search_tool_result","content":{"type":"web_search_tool_result_error","error_code":"overloaded"}}}""",
            """{"type":"message_delta","delta":{"stop_reason":"pause_turn"}}"""
        )) {
            val result = ApiFormatStreamParser.parseToEvents(ApiFormat.MESSAGES, listOf(payload).asFlow()).toList()
            assertTrue(result.single() is ChatStreamEvent.Error)
        }
        val partial = ApiFormatStreamParser.parseToEvents(ApiFormat.RESPONSES, listOf("[DONE]").asFlow()).toList()
        assertTrue(partial.none { it is ChatStreamEvent.Done })
        assertFailsWith<IllegalStateException> {
            ApiFormatStreamParser.decodeCompletion(ApiFormat.MESSAGES, Json.parseToJsonElement("""{"stop_reason":"pause_turn"}""").jsonObject)
        }
        Unit
    }
}
