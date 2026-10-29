/*
 * Copyright 2026 originalFactor
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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatDocumentParserTest {
    @Test
    fun parsesStructuredMessageBlocks() {
        val blocks = ChatDocumentParser.parseBlocksJson(
            """
            [
              {"kind":"heading","id":101,"level":2,"text":"Architecture","status":"finalized"},
              {"kind":"paragraph","id":102,"inlines":[
                {"type":"text","text":"Testing "},
                {"type":"bold","text":"blocks "},
                {"type":"code","code":"key(id)"}
              ],"status":"finalized"},
              {"kind":"tool_call","id":103,"call_id":"c1","name":"read",
               "arguments":"{}","output":"ok","is_error":false,"status":"finalized"}
            ]
            """.trimIndent(),
        )
        assertEquals(3, blocks.size)
        assertEquals("Testing blocks key(id)", (blocks[1] as ChatBlock.Paragraph).text)
        assertEquals("c1", (blocks[2] as ChatBlock.ToolCall).callId)
    }

    @Test
    fun nativeMarkdownIsPassedThroughToLlmTypewriter() {
        val markdown = """
            # Heading

            **bold** and ${'$'}x^2${'$'}
        """.trimIndent()
        val block = ChatDocumentParser.parseMarkdown(markdown, baseId = 42).single()
        val raw = assertIs<ChatBlock.RawMarkdown>(block)
        assertEquals(42, raw.id)
        assertEquals(markdown, raw.markdown)
        assertTrue(raw.isFinalized)
    }
}
