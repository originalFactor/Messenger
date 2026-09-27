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

package cc.ptoe.messenger.data.local

import cc.ptoe.messenger.domain.model.ContentPart
import cc.ptoe.messenger.domain.model.MessageImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 工具调用/结果 part 的 partsJson 编解码往返 + 未知类型丢弃（前向兼容）。 */
class ContentPartCodecToolTest {

    @Test
    fun `tool call and result round-trip`() {
        val parts = listOf(
            ContentPart.Text("让我查一下"),
            ContentPart.ToolCall(callId = "call_1", name = "terminal", arguments = "{\"command\":\"dir\"}"),
            ContentPart.ToolResult(
                callId = "call_1",
                name = "terminal",
                output = "Exit code: 0\nfile1.txt",
                isError = false
            )
        )

        val encoded = ContentPartCodec.encode(parts)
        val decoded = ContentPartCodec.decode(encoded)

        assertEquals(parts, decoded)
    }

    @Test
    fun `error result keeps isError flag`() {
        val parts = listOf(
            ContentPart.ToolResult(callId = "c", name = "terminal", output = "Exit code: 1", isError = true)
        )
        val decoded = ContentPartCodec.decode(ContentPartCodec.encode(parts))
        val result = decoded.single() as ContentPart.ToolResult
        assertTrue(result.isError)
    }

    @Test
    fun `unknown part types are dropped for forward compatibility`() {
        val legacyJson = """
            [{"type":"future_part","payload":"x"},
             {"type":"tool_call","callId":"c1","name":"terminal","arguments":"{}"}]
        """.trimIndent()
        val decoded = ContentPartCodec.decode(legacyJson)
        assertEquals(1, decoded.size)
        assertEquals("c1", (decoded.single() as ContentPart.ToolCall).callId)
    }

    @Test
    fun `mixed image and tool parts round-trip`() {
        val parts = listOf(
            ContentPart.Image(MessageImage(dataUri = "data:image/png;base64,abc", localPath = "/tmp/a.png")),
            ContentPart.ToolCall(callId = "c", name = "terminal", arguments = "{}")
        )
        val decoded = ContentPartCodec.decode(ContentPartCodec.encode(parts))
        assertEquals(parts, decoded)
    }
}
