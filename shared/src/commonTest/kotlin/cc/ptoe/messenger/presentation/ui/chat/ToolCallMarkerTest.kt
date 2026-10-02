/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.ptoe.messenger.presentation.ui.chat

import cc.ptoe.messenger.domain.model.ContentPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `<tool_call>` 流内标记的构建/解析往返测试：标记由 [buildToolCallMarker]
 * 拼进工具轮的渲染内容，由 llm-typewriter 解析出块后经
 * [parseToolCallMarker] 还原为卡片数据。
 */
class ToolCallMarkerTest {

    private val call = ContentPart.ToolCall(
        callId = "call_1",
        name = "terminal",
        arguments = "{\"command\":\"ls -la\"}"
    )

    @Test
    fun `running call marker carries no result fields`() {
        val marker = buildToolCallMarker(call, result = null)

        assertTrue(marker.startsWith("<tool_call>"))
        assertTrue(marker.endsWith("</tool_call>"))
        val parsed = parseToolCallMarker(marker.removeSurrounding("<tool_call>", "</tool_call>"))!!
        assertEquals("call_1", parsed.callId)
        assertEquals("terminal", parsed.name)
        assertEquals("{\"command\":\"ls -la\"}", parsed.arguments)
        assertNull(parsed.output)
        assertFalse(parsed.isError)
    }

    @Test
    fun `finished call marker carries output and error flag`() {
        val result = ContentPart.ToolResult(
            callId = "call_1",
            name = "terminal",
            output = "total 0",
            isError = false
        )
        val parsed = parseToolCallMarker(buildToolCallMarker(call, result).toPayload())!!

        assertEquals("total 0", parsed.output)
        assertFalse(parsed.isError)
    }

    @Test
    fun `failed call marker preserves error flag`() {
        val result = ContentPart.ToolResult(
            callId = "call_1",
            name = "terminal",
            output = "command rejected",
            isError = true
        )
        val parsed = parseToolCallMarker(buildToolCallMarker(call, result).toPayload())!!

        assertTrue(parsed.isError)
        assertEquals("command rejected", parsed.output)
    }

    @Test
    fun `output newlines and quotes are escaped into the marker`() {
        // 工具输出里的换行/引号经 JSON 转义后不破坏标记结构，往返不失真
        val result = ContentPart.ToolResult(
            callId = "call_1",
            name = "terminal",
            output = "line1\nline2 \"quoted\"\ttab",
            isError = false
        )
        val marker = buildToolCallMarker(call, result)

        assertEquals(1, Regex("<tool_call>").findAll(marker).count())
        assertEquals(1, Regex("</tool_call>").findAll(marker).count())
        val parsed = parseToolCallMarker(marker.toPayload())!!
        assertEquals("line1\nline2 \"quoted\"\ttab", parsed.output)
    }

    @Test
    fun `malformed payload parses to null`() {
        assertNull(parseToolCallMarker("not json"))
        assertNull(parseToolCallMarker("{}"))
        assertNull(parseToolCallMarker("{\"name\":\"x\"}"))
    }

    private fun String.toPayload(): String = removeSurrounding("<tool_call>", "</tool_call>")
}
