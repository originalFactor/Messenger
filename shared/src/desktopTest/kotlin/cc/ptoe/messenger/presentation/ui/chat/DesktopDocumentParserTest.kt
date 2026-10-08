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

class ChatDocumentParserTest {

    @Test
    fun testParseChatBlocks() {
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
                  {"type": "code", "code": "key(block.id)"}
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

        val blocks = ChatDocumentParser.parseBlocksJson(json)
        assertEquals(6, blocks.size)

        val h = blocks[0] as ChatBlock.Heading
        assertEquals(101L, h.id)
        assertEquals(2, h.level)
        assertEquals("Desktop Architecture", h.text)

        val p = blocks[1] as ChatBlock.Paragraph
        assertEquals(102L, p.id)
        assertEquals("Testing immutable block key(block.id)", p.text)
        assertEquals(3, p.inlines.size)

        val c = blocks[2] as ChatBlock.Code
        assertEquals(103L, c.id)
        assertEquals("rust", c.language)

        val m = blocks[3] as ChatBlock.Math
        assertEquals(104L, m.id)
        assertEquals("E = mc^2", m.formula)

        val t = blocks[4] as ChatBlock.Think
        assertEquals(105L, t.id)
        assertEquals(false, t.isFinalized)

        val tc = blocks[5] as ChatBlock.ToolCall
        assertEquals(106L, tc.id)
        assertEquals("workspace_read", tc.name)
        assertEquals(false, tc.isError)
    }

    @Test
    fun testParseMarkdownTaskList() {
        // JSON from the Rust core carries the stripped task marker as `task`
        val blocksJson = """
            [
              {
                "kind": "list",
                "id": 201,
                "items": [
                  {"indent": 0, "ordered": false, "number": 0, "task": false, "inlines": [{"type": "text", "text": "Todo item"}]},
                  {"indent": 0, "ordered": false, "number": 0, "task": true, "inlines": [{"type": "text", "text": "Done item"}]},
                  {"indent": 0, "ordered": false, "number": 0, "inlines": [{"type": "text", "text": "Plain item"}]}
                ],
                "status": "finalized"
              }
            ]
        """.trimIndent()
        val jsonBlocks = ChatDocumentParser.parseBlocksJson(blocksJson)
        val jsonList = jsonBlocks[0] as ChatBlock.ListBlock
        assertEquals(false, jsonList.items[0].task)
        assertEquals("Todo item", jsonList.items[0].text)
        assertEquals(true, jsonList.items[1].task)
        assertEquals(null, jsonList.items[2].task)

        // The built-in Kotlin markdown parser strips the same markers
        val markdown = """
            - [ ] Todo item
            - [x] Done item
            - [X] Upper done
            1. [ ] Ordered todo
            - [x]no-space stays literal
        """.trimIndent()
        val blocks = ChatDocumentParser.parseMarkdown(markdown)
        val list = blocks[0] as ChatBlock.ListBlock
        assertEquals(5, list.items.size)
        assertEquals(false, list.items[0].task)
        assertEquals("Todo item", list.items[0].text)
        assertEquals(true, list.items[1].task)
        assertEquals(true, list.items[2].task)
        assertEquals(false, list.items[3].task)
        assertEquals("Ordered todo", list.items[3].text)
        assertEquals(null, list.items[4].task)
        assertEquals("[x]no-space stays literal", list.items[4].text)
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
                val blocks = ChatDocumentParser.parseBlocksJson(sampleJson)
                assertEquals(4, blocks.size)
            }
        }

        val elapsedMs = elapsedNanos / 1_000_000.0
        val avgMs = elapsedMs / iterations
        println("ChatDocumentParser Benchmark: $iterations iterations in $elapsedMs ms (avg $avgMs ms/run)")
        assertTrue(avgMs < 0.2, "Desktop parsing took too long: $avgMs ms")
    }

    @Test
    fun testParseMarkdownHeadingsAndParagraphs() {
        val markdown = """
            # Heading 1
            ## Heading 2
            ### Heading 3
            #### Heading 4

            This is a paragraph with **bold**, *italic*, `code`, and ${'$'}E = mc^2${'$'}.
        """.trimIndent()

        val blocks = ChatDocumentParser.parseMarkdown(markdown)
        assertEquals(5, blocks.size)

        val h1 = blocks[0] as ChatBlock.Heading
        assertEquals(1, h1.level)
        assertEquals("Heading 1", h1.text)

        val h2 = blocks[1] as ChatBlock.Heading
        assertEquals(2, h2.level)
        assertEquals("Heading 2", h2.text)

        val h3 = blocks[2] as ChatBlock.Heading
        assertEquals(3, h3.level)
        assertEquals("Heading 3", h3.text)

        val h4 = blocks[3] as ChatBlock.Heading
        assertEquals(4, h4.level)
        assertEquals("Heading 4", h4.text)

        val p = blocks[4] as ChatBlock.Paragraph
        assertEquals(9, p.inlines.size)
        assertTrue(p.inlines.any { it is ChatInline.Bold && it.text == "bold" })
        assertTrue(p.inlines.any { it is ChatInline.Italic && it.text == "italic" })
        assertTrue(p.inlines.any { it is ChatInline.Code && it.code == "code" })
        assertTrue(p.inlines.any { it is ChatInline.Math && it.formula == "E = mc^2" })
    }

    @Test
    fun testParseMarkdownCodeBlock() {
        val markdown = """
            ```kotlin
            fun main() {
                println("Hello")
            }
            ```
        """.trimIndent()

        val blocks = ChatDocumentParser.parseMarkdown(markdown)
        assertEquals(1, blocks.size)

        val c = blocks[0] as ChatBlock.Code
        assertEquals("kotlin", c.language)
        assertEquals("fun main() {\n    println(\"Hello\")\n}", c.code)
        assertTrue(c.isFinalized)
    }

    @Test
    fun testParseMarkdownThinkBlock() {
        val closedMarkdown = """
            <think>
            Internal deliberation...
            </think>
            Final answer.
        """.trimIndent()

        val closedBlocks = ChatDocumentParser.parseMarkdown(closedMarkdown)
        assertEquals(2, closedBlocks.size)
        val thinkClosed = closedBlocks[0] as ChatBlock.Think
        assertEquals("Internal deliberation...", thinkClosed.content)
        assertTrue(thinkClosed.isFinalized)

        val unclosedMarkdown = """
            <think>
            Still deliberating...
        """.trimIndent()
        val streamingBlocks = ChatDocumentParser.parseMarkdown(unclosedMarkdown)
        assertEquals(1, streamingBlocks.size)
        val thinkStreaming = streamingBlocks[0] as ChatBlock.Think
        assertEquals("Still deliberating...", thinkStreaming.content)
        assertEquals(false, thinkStreaming.isFinalized)
    }

    @Test
    fun testParseMarkdownMathAndTableAndList() {
        val markdown = """
            $$
            \int_0^\infty e^{-x^2} dx = \frac{\sqrt{\pi}}{2}
            $$

            | Col A | Col B |
            |---|---|
            | 1 | 2 |
            | 3 | 4 |

            - First bullet
            - Second bullet
              - Indented bullet

            > Important quote
            > second line

            ---
        """.trimIndent()

        val blocks = ChatDocumentParser.parseMarkdown(markdown)
        assertEquals(5, blocks.size)

        val math = blocks[0] as ChatBlock.Math
        assertTrue(math.formula.contains("\\int_0^\\infty"))
        assertTrue(math.isFinalized)

        val table = blocks[1] as ChatBlock.Table
        assertEquals(listOf("Col A", "Col B"), table.head)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("1", "2"), table.rows[0])
        assertEquals(listOf("3", "4"), table.rows[1])

        val list = blocks[2] as ChatBlock.ListBlock
        assertEquals(3, list.items.size)
        assertEquals("First bullet", list.items[0].text)
        assertEquals("Second bullet", list.items[1].text)
        assertEquals(1, list.items[2].indent)
        assertEquals("Indented bullet", list.items[2].text)

        val quote = blocks[3] as ChatBlock.Quote
        assertEquals("Important quote\nsecond line", quote.text)

        val divider = blocks[4] as ChatBlock.Divider
        assertTrue(divider.isFinalized)
    }

    @Test
    fun testParseQuoteInlines() {
        // Rust core wire format: quote content carries parsed inlines
        val blocksJson = """
            [
              {
                "kind": "quote",
                "id": 301,
                "inlines": [
                  {"type": "text", "text": "Quote with "},
                  {"type": "bold", "text": "bold"},
                  {"type": "text", "text": " and "},
                  {"type": "code", "code": "code"}
                ],
                "status": "finalized"
              }
            ]
        """.trimIndent()
        val jsonQuote = ChatDocumentParser.parseBlocksJson(blocksJson)[0] as ChatBlock.Quote
        assertEquals("Quote with bold and code", jsonQuote.text)
        assertTrue(jsonQuote.inlines.any { it is ChatInline.Bold && it.text == "bold" })
        assertTrue(jsonQuote.inlines.any { it is ChatInline.Code && it.code == "code" })

        // The built-in Kotlin parser strips the same markers
        val mdQuote = ChatDocumentParser.parseMarkdown("> Quote with **bold** and `code`.")[0] as ChatBlock.Quote
        assertEquals("Quote with bold and code.", mdQuote.text)
        assertTrue(mdQuote.inlines.any { it is ChatInline.Bold && it.text == "bold" })
        assertTrue(mdQuote.inlines.any { it is ChatInline.Code && it.code == "code" })
    }
}
