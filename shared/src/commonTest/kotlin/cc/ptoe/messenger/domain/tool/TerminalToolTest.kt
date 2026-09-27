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

package cc.ptoe.messenger.domain.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 终端工具的参数解析与输出截断（纯函数部分）。 */
class TerminalToolTest {

    @Test
    fun `parses command from valid arguments json`() {
        assertEquals("dir", TerminalTool.parseCommand("{\"command\":\"dir\"}"))
        assertEquals("ls -la", TerminalTool.parseCommand("""{"command":"ls -la","cwd":"/tmp"}"""))
    }

    @Test
    fun `returns null for malformed arguments`() {
        assertNull(TerminalTool.parseCommand("not json"))
        assertNull(TerminalTool.parseCommand("[1,2,3]"))
        assertNull(TerminalTool.parseCommand("{\"nope\":1}"))
        assertNull(TerminalTool.parseCommand("{\"command\":123}"))
    }

    @Test
    fun `truncate keeps short output untouched`() {
        assertEquals("hello", TerminalTool.truncateOutput("hello"))
        assertEquals("(no output)", TerminalTool.truncateOutput(""))
    }

    @Test
    fun `truncate keeps tail of long output with marker`() {
        val long = "x".repeat(500) + "TAIL"
        val truncated = TerminalTool.truncateOutput(long, maxChars = 100)
        assertTrue(truncated.startsWith("(output truncated"))
        assertTrue(truncated.endsWith("TAIL"))
        // 标记 + 保留字符数
        assertTrue(truncated.length < 160)
    }

    @Test
    fun `tool schema is well-formed json`() {
        val tool = TerminalTool()
        assertEquals("terminal", tool.name)
        // parametersJson 必须能被 JSON 解析（请求组装时原样发给 API）
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(tool.parametersJson)
        assertTrue(parsed is kotlinx.serialization.json.JsonObject)
    }
}
