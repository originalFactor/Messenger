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

import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopDocumentParserTest {

    @Test
    fun testParseDesktopBlocks() {
        val json = """
            [
              {
                "kind": "heading",
                "id": 101,
                "level": 2,
                "text": "Desktop Architecture",
                "status": "finalized"
              },
              {
                "kind": "paragraph",
                "id": 102,
                "inlines": [
                  {"type": "text", "text": "Testing "},
                  {"type": "bold", "text": "immutable block "},
                  {"type": "code", "text": "key(block.id)"}
                ],
                "status": "finalized"
              },
              {
                "kind": "code_block",
                "id": 103,
                "language": "rust",
                "code": "fn main() { println!(\"desktop\"); }",
                "status": "finalized"
              },
              {
                "kind": "math",
                "id": 104,
                "formula": "E = mc^2",
                "status": "finalized"
              },
              {
                "kind": "think",
                "id": 105,
                "content": "Analyzing desktop performance...",
                "status": "streaming"
              },
              {
                "kind": "tool_call",
                "id": 106,
                "call_id": "call_1",
                "name": "workspace_read",
                "arguments": "{\"path\":\"TARGET.md\"}",
                "output": "TARGET.md content",
                "is_error": false,
                "status": "finalized"
              }
            ]
        """.trimIndent()

        val blocks = DesktopDocumentParser.parseBlocksJson(json)
        assertEquals(6, blocks.size)

        val h = blocks[0] as DesktopBlock.Heading
        assertEquals(101L, h.id)
        assertEquals(2, h.level)
        assertEquals("Desktop Architecture", h.text)

        val p = blocks[1] as DesktopBlock.Paragraph
        assertEquals(102L, p.id)
        assertEquals("Testing immutable block key(block.id)", p.text)
        assertEquals(3, p.inlines.size)

        val c = blocks[2] as DesktopBlock.Code
        assertEquals(103L, c.id)
        assertEquals("rust", c.language)

        val m = blocks[3] as DesktopBlock.Math
        assertEquals(104L, m.id)
        assertEquals("E = mc^2", m.formula)

        val t = blocks[4] as DesktopBlock.Think
        assertEquals(105L, t.id)
        assertEquals(false, t.isFinalized)

        val tc = blocks[5] as DesktopBlock.ToolCall
        assertEquals(106L, tc.id)
        assertEquals("workspace_read", tc.name)
        assertEquals(false, tc.isError)
    }

    @Test
    fun benchmarkDesktopDocumentParsing() {
        val iterations = 300
        val sampleJson = """
            [
              {"kind":"paragraph","id":1,"inlines":[{"type":"text","text":"Line 1 "}],"status":"finalized"},
              {"kind":"code_block","id":2,"language":"kt","code":"fun run() {}","status":"finalized"},
              {"kind":"math","id":3,"formula":"x^2+y^2=r^2","status":"finalized"},
              {"kind":"tool_call","id":4,"name":"grep","arguments":"{}","output":"matches","status":"finalized"}
            ]
        """.trimIndent()

        val elapsedNanos = measureNanoTime {
            for (i in 1..iterations) {
                val blocks = DesktopDocumentParser.parseBlocksJson(sampleJson)
                assertEquals(4, blocks.size)
            }
        }

        val elapsedMs = elapsedNanos / 1_000_000.0
        val avgMs = elapsedMs / iterations
        println("DesktopDocumentParser Benchmark: $iterations iterations in $elapsedMs ms (avg $avgMs ms/run)")
        assertTrue(avgMs < 0.2, "Desktop parsing took too long: $avgMs ms")
    }
}
