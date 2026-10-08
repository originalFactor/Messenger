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

package cc.ptoe.messenger.renderer

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DocumentParserTest {

    @Test
    fun testParseBlocksJson() {
        val json = """
            [
              {
                "kind": "heading",
                "id": 1,
                "level": 1,
                "text": "Header Title",
                "status": "finalized"
              },
              {
                "kind": "paragraph",
                "id": 2,
                "inlines": [
                  {"type": "text", "text": "Hello "},
                  {"type": "bold", "text": "World"}
                ],
                "status": "finalized"
              },
              {
                "kind": "code_block",
                "id": 3,
                "language": "rust",
                "code": "fn main() {}",
                "status": "finalized"
              },
              {
                "kind": "think",
                "id": 4,
                "content": "Step 1...",
                "status": "streaming"
              },
              {
                "kind": "tool_call",
                "id": 5,
                "call_id": "c1",
                "name": "terminal",
                "arguments": "{\"command\":\"ls\"}",
                "output": "file1\nfile2",
                "is_error": false,
                "status": "finalized"
              },
              {
                "kind": "math",
                "id": 6,
                "formula": "\\sum_{i=0}^n x_i",
                "status": "finalized"
              }
            ]
        """.trimIndent()

        val blocks = DocumentParser.parseBlocksJson(json)
        assertEquals(6, blocks.size)

        val h = blocks[0] as RenderBlock.Heading
        assertEquals(1L, h.id)
        assertEquals(1, h.level)
        assertEquals("Header Title", h.text)
        assertTrue(h.isFinalized)

        val p = blocks[1] as RenderBlock.Paragraph
        assertEquals(2L, p.id)
        println("P.TEXT: '${p.text}'")

        val c = blocks[2] as RenderBlock.Code
        assertEquals(3L, c.id)
        assertEquals("rust", c.language)
        assertEquals("fn main() {}", c.code)

        val t = blocks[3] as RenderBlock.Think
        assertEquals(4L, t.id)
        assertEquals("Step 1...", t.content)
        assertFalse(t.isFinalized)

        val tc = blocks[4] as RenderBlock.ToolCall
        assertEquals(5L, tc.id)
        assertEquals("terminal", tc.name)
        assertEquals("file1\nfile2", tc.output)
        assertFalse(tc.isError)

        val m = blocks[5] as RenderBlock.Math
        assertEquals(6L, m.id)
        assertEquals("\\sum_{i=0}^n x_i", m.formula)
    }

    @Test
    fun testParseTaskList() {
        val json = """
            [
              {
                "kind": "list",
                "id": 7,
                "items": [
                  {"indent": 0, "ordered": false, "number": 0, "task": false, "inlines": [{"type": "text", "text": "Todo item"}]},
                  {"indent": 0, "ordered": false, "number": 0, "task": true, "inlines": [{"type": "text", "text": "Done item"}]},
                  {"indent": 0, "ordered": false, "number": 0, "inlines": [{"type": "text", "text": "Plain item"}]}
                ],
                "status": "finalized"
              }
            ]
        """.trimIndent()

        val blocks = DocumentParser.parseBlocksJson(json)
        assertEquals(1, blocks.size)

        // Task flags ride the list item; text content is a SpannableStringBuilder
        // (not assertable in a plain-JVM unit test — see testParseBlocksJson)
        val list = blocks[0] as RenderBlock.ListBlock
        assertEquals(3, list.items.size)
        assertEquals(false, list.items[0].task)
        assertEquals(true, list.items[1].task)
        assertEquals(null, list.items[2].task)
        assertEquals(0, list.items[2].number)
        assertFalse(list.items[0].ordered)
    }
}
