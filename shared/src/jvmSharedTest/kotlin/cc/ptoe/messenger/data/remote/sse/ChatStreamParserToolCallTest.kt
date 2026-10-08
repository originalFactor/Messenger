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

package cc.ptoe.messenger.data.remote.sse

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 流式 tool_calls 增量累积测试：id/name 随首块到达、arguments 跨块拼接、
 * finish_reason 与 [DONE] 各发一次 Done 且携带相同调用列表。
 */
class ChatStreamParserToolCallTest {

    @Test
    fun `accumulates fragmented tool calls across chunks`() = runBlocking {
        // arguments 跨块拼接：首块 "{\"co" + 次块 "mmand\":\"ls -la\"}" = {"command":"ls -la"}
        val chunk1 = """
            {"choices":[{"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"terminal","arguments":"{\"co"}}]},"finish_reason":null}]}
        """.trimIndent()
        val chunk2 = """
            {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"mmand\":\"ls -la\"}"}}]},"finish_reason":"tool_calls"}]}
        """.trimIndent()

        val events = ChatStreamParser.parseToEvents(flowOf(chunk1, chunk2, "[DONE]")).toList()
        val dones = events.filterIsInstance<ChatStreamEvent.Done>()

        assertEquals(2, dones.size)
        assertEquals("tool_calls", dones[0].finishReason)
        assertEquals(1, dones[0].toolCalls.size)
        val call = dones[0].toolCalls.single()
        assertEquals("call_1", call.callId)
        assertEquals("terminal", call.name)
        assertEquals("{\"command\":\"ls -la\"}", call.arguments)
        // [DONE] 触发的第二次 Done 携带相同的累积结果
        assertEquals(dones[0].toolCalls, dones[1].toolCalls)
        assertNull(dones[1].finishReason)
    }

    @Test
    fun `accumulates multiple calls by index and synthesizes missing ids`() = runBlocking {
        val chunk1 = """
            {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"terminal","arguments":"{}"}},{"index":0,"id":"call_a","type":"function","function":{"name":"other","arguments":"{\"x\":1"}}]},"finish_reason":null}]}
        """.trimIndent()
        val chunk2 = """
            {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]},"finish_reason":"tool_calls"}]}
        """.trimIndent()

        val events = ChatStreamParser.parseToEvents(flowOf(chunk1, chunk2)).toList()
        val dones = events.filterIsInstance<ChatStreamEvent.Done>()
        assertEquals(1, dones.size)
        val calls = dones[0].toolCalls
        assertEquals(2, calls.size)
        // 按 index 排序输出
        assertEquals("call_a", calls[0].callId)
        assertEquals("other", calls[0].name)
        assertEquals("{\"x\":1}", calls[0].arguments)
        assertEquals("call_b", calls[1].callId)
    }

    @Test
    fun `no tool calls on plain text stream`() = runBlocking {
        val chunk = """{"choices":[{"delta":{"content":"hello"},"finish_reason":"stop"}]}"""
        val events = ChatStreamParser.parseToEvents(flowOf(chunk)).toList()
        val done = events.filterIsInstance<ChatStreamEvent.Done>().single()
        assertTrue(done.toolCalls.isEmpty())
        assertEquals("stop", done.finishReason)
    }
}
