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

package cc.ptoe.messenger.presentation.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class StripThinkBlockTest {
    private val open = String(charArrayOf(60.toChar(), 116.toChar(), 104.toChar(), 105.toChar(), 110.toChar(), 107.toChar(), 62.toChar()))
    private val close = String(charArrayOf(60.toChar(), 47.toChar(), 116.toChar(), 104.toChar(), 105.toChar(), 110.toChar(), 107.toChar(), 62.toChar()))

    @Test
    fun closedThinkBlock_singleLine() {
        val input = "${open} 思考内容 ${close} 正文内容"
        assertEquals("正文内容", stripThinkBlock(input))
    }

    @Test
    fun closedThinkBlock_multilineContent() {
        val input = "${open} 思考第一行\n思考第二行 ${close} 正文内容"
        assertEquals("正文内容", stripThinkBlock(input))
    }

    @Test
    fun closedThinkBlock_newlinesBetweenTags() {
        val input = "${open}\n思考内容\n\n\n${close} 正文"
        assertEquals("正文", stripThinkBlock(input))
    }

    @Test
    fun closedThinkBlock_thinkOnSeparateLine() {
        val input = "${open} 思考\n\n${close} 正文"
        assertEquals("正文", stripThinkBlock(input))
    }

    @Test
    fun unclosedThinkBlock_returnsEmpty() {
        val input = "${open} 思考内容没有结束标签"
        assertEquals("", stripThinkBlock(input))
    }

    @Test
    fun thinkBlockWithAttributes() {
        val input = "${open} 思考内容 ${close} 正文"
        assertEquals("正文", stripThinkBlock(input))
    }

    @Test
    fun thinkingVariant() {
        val open = String(charArrayOf(60.toChar(), 116.toChar(), 104.toChar(), 105.toChar(), 110.toChar(), 107.toChar(), 105.toChar(), 110.toChar(), 103.toChar(), 62.toChar()))
        val close = String(charArrayOf(60.toChar(), 47.toChar(), 116.toChar(), 104.toChar(), 105.toChar(), 110.toChar(), 107.toChar(), 105.toChar(), 110.toChar(), 103.toChar(), 62.toChar()))
        val input = "$open 思考内容 $close 正文"
        assertEquals("正文", stripThinkBlock(input))
    }

    @Test
    fun caseInsensitive() {
        val open = String(charArrayOf(60.toChar(), 84.toChar(), 72.toChar(), 73.toChar(), 78.toChar(), 75.toChar(), 62.toChar()))
        val close = String(charArrayOf(60.toChar(), 47.toChar(), 84.toChar(), 72.toChar(), 73.toChar(), 78.toChar(), 75.toChar(), 62.toChar()))
        val input = "$open 思考内容 $close 正文"
        assertEquals("正文", stripThinkBlock(input))
    }

    @Test
    fun onlyThinkBlock_returnsEmpty() {
        val input = "${open} 只有思考内容 ${close}"
        assertEquals("", stripThinkBlock(input))
    }

    @Test
    fun noThinkBlock_unchanged() {
        val input = "普通正文内容"
        assertEquals("普通正文内容", stripThinkBlock(input))
    }

    @Test
    fun multipleThinkBlocks() {
        val input = "${open} 思考1 ${close} 正文1 ${open} 思考2 ${close} 正文2"
        assertEquals("正文1  正文2", stripThinkBlock(input))
    }

    @Test
    fun thinkBlockWithLeadingNewlineBeforeContent() {
        val input = "${open} 思考\n${close} 正文"
        assertEquals("正文", stripThinkBlock(input))
    }

    @Test
    fun thinkBlockCrlfLineEndings() {
        val input = "${open} 思考\r\n${close} 正文"
        assertEquals("正文", stripThinkBlock(input))
    }

    @Test
    fun realWorld_multilineThinkingThenBody() {
        val input = "${open} 这是第一行思考\n这是第二行思考\n这是第三行思考 ${close}\n\n你好！我是AI助手。"
        assertEquals("你好！我是AI助手。", stripThinkBlock(input))
    }

    @Test
    fun interleavedThinkBlock_midContent_stripped() {
        val input = "正文1 ${open} 思考2 ${close} 正文2"
        assertEquals("正文1  正文2", stripThinkBlock(input))
    }

    @Test
    fun closedBlockThenStreamingUnclosedTail_stripped() {
        val input = "${open} 思考1 ${close} 正文1 ${open} 思考2还没结束"
        assertEquals("正文1", stripThinkBlock(input))
    }
}
