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
    private val open = "<think>"
    private val close = "</think>"

    @Test
    fun removesClosedMultilineThinkBlock() {
        val input = "$open 思考第一行\n思考第二行 $close 正文内容"
        assertEquals("正文内容", stripThinkBlock(input))
    }

    @Test
    fun supportsAttributesCaseDifferencesAndThinkingVariant() {
        assertEquals("正文", stripThinkBlock("<THINK type=\"analysis\">思考</THINK> 正文"))
        assertEquals("正文", stripThinkBlock("<thinking>思考</thinking> 正文"))
    }

    @Test
    fun removesInterleavedThinkBlocks() {
        val input = "$open 思考1 $close 正文1 $open 思考2 $close 正文2"
        assertEquals("正文1  正文2", stripThinkBlock(input))
    }

    @Test
    fun removesUnclosedThinkTail() {
        assertEquals("", stripThinkBlock("$open 思考内容没有结束标签"))
        assertEquals("正文1", stripThinkBlock("$open 思考1 $close 正文1 $open 思考2还没结束"))
    }

    @Test
    fun leavesContentWithoutThinkBlockUnchanged() {
        assertEquals("普通正文内容", stripThinkBlock("普通正文内容"))
    }
}
