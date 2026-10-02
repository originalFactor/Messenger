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

/**
 * 推理内容字段解析测试：
 *  - `reasoning_content`（DeepSeek 等完整思维链）→ 格式标注 "reasoning_content"；
 *  - `reasoning`（GPT 等加密思维链模型仅回传 Reasoning Summary）→ 格式标注
 *    "reasoning_summary"；两者统一包进 `<think>` 块显示。
 */
class ChatStreamParserReasoningTest {

    @Test
    fun `wraps reasoning summary field in think block and reports reasoning_summary`() = runBlocking {
        val chunk1 = """
            {"choices":[{"delta":{"reasoning":"Reading the directory first"}}]}
        """.trimIndent()
        val chunk2 = """
            {"choices":[{"delta":{"content":"Listing files now."}}]}
        """.trimIndent()

        val events = ChatStreamParser.parseToEvents(flowOf(chunk1, chunk2, "[DONE]")).toList()

        val detected = events.filterIsInstance<ChatStreamEvent.ReasoningDetected>()
        assertEquals(listOf("reasoning_summary"), detected.map { it.format })

        val contents = events.filterIsInstance<ChatStreamEvent.Content>().map { it.text }
        assertEquals(listOf("<think>", "Reading the directory first", "</think>\n", "Listing files now."), contents)
    }

    @Test
    fun `reasoning_content field reports reasoning_content format`() = runBlocking {
        val chunk = """
            {"choices":[{"delta":{"reasoning_content":"step by step"}}]}
        """.trimIndent()

        val events = ChatStreamParser.parseToEvents(flowOf(chunk, "[DONE]")).toList()

        val detected = events.filterIsInstance<ChatStreamEvent.ReasoningDetected>()
        assertEquals(listOf("reasoning_content"), detected.map { it.format })
        // 流在思考块内结束，[DONE] 补发闭合标签
        val contents = events.filterIsInstance<ChatStreamEvent.Content>().map { it.text }
        assertEquals(listOf("<think>", "step by step", "</think>\n"), contents)
    }

    @Test
    fun `reasoning_content takes precedence when both fields arrive in one chunk`() = runBlocking {
        val chunk = """
            {"choices":[{"delta":{"reasoning":"summary text","reasoning_content":"full chain"}}]}
        """.trimIndent()

        val events = ChatStreamParser.parseToEvents(flowOf(chunk, "[DONE]")).toList()

        val detected = events.filterIsInstance<ChatStreamEvent.ReasoningDetected>()
        assertEquals(listOf("reasoning_content"), detected.map { it.format })
        // 流在思考块内结束，[DONE] 补发闭合标签
        val contents = events.filterIsInstance<ChatStreamEvent.Content>().map { it.text }
        assertEquals(listOf("<think>", "full chain", "</think>\n"), contents)
    }

    @Test
    fun `empty reasoning fields are ignored`() = runBlocking {
        val chunk = """
            {"choices":[{"delta":{"reasoning":"","reasoning_content":""}}]}
        """.trimIndent()

        val events = ChatStreamParser.parseToEvents(flowOf(chunk, "[DONE]")).toList()

        assertEquals(0, events.filterIsInstance<ChatStreamEvent.ReasoningDetected>().size)
        assertEquals(0, events.filterIsInstance<ChatStreamEvent.Content>().size)
    }
}
