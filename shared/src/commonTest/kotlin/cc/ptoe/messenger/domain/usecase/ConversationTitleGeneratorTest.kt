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

package cc.ptoe.messenger.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTitleGeneratorTest {

    @Test
    fun isUntitled_blank() {
        assertTrue(ConversationTitleGenerator.isUntitledConversation(""))
        assertTrue(ConversationTitleGenerator.isUntitledConversation("   "))
    }

    @Test
    fun isUntitled_defaultPlaceholders() {
        assertTrue(ConversationTitleGenerator.isUntitledConversation("新对话"))
        assertTrue(ConversationTitleGenerator.isUntitledConversation("New Chat"))
    }

    @Test
    fun isUntitled_namedTitles() {
        assertFalse(ConversationTitleGenerator.isUntitledConversation("关于旅行计划"))
        assertFalse(ConversationTitleGenerator.isUntitledConversation("新对话的讨论"))
        assertFalse(ConversationTitleGenerator.isUntitledConversation("新对话（副本）"))
    }

    @Test
    fun sanitize_stripsThinkBlockAndQuotes() {
        val open = "<think>"
        val close = "</think>"
        val raw = "${open}用户想聊天气${close}\n\"天气闲聊\""
        assertEquals("天气闲聊", ConversationTitleGenerator.sanitizeGeneratedTitle(raw))
    }

    @Test
    fun sanitize_stripsWrappingQuotes() {
        assertEquals("旅行计划", ConversationTitleGenerator.sanitizeGeneratedTitle("“旅行计划”"))
        assertEquals("旅行计划", ConversationTitleGenerator.sanitizeGeneratedTitle("「旅行计划」"))
        assertEquals("旅行计划", ConversationTitleGenerator.sanitizeGeneratedTitle("'旅行计划'"))
    }

    @Test
    fun sanitize_takesFirstNonBlankLine() {
        val raw = "第一行标题\n附加说明不应出现"
        assertEquals("第一行标题", ConversationTitleGenerator.sanitizeGeneratedTitle(raw))
    }

    @Test
    fun sanitize_truncatesLongTitles() {
        val long = "这是一个非常长的标题超过了三十个字符的长度限制需要被截断处理才行啊"
        assertEquals(33, ConversationTitleGenerator.sanitizeGeneratedTitle(long).length)
        assertTrue(ConversationTitleGenerator.sanitizeGeneratedTitle(long).endsWith("..."))
    }

    @Test
    fun sanitize_shortTitlesKeptAsIs() {
        assertEquals("简短标题", ConversationTitleGenerator.sanitizeGeneratedTitle("简短标题"))
    }
}
