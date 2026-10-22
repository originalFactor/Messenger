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

package cc.ptoe.messenger.presentation.ui.components

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import cc.ptoe.messenger.domain.model.Conversation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Recent Chats is the shortcut back into the last conversations: each row must
 * open its own conversation.
 */
@OptIn(ExperimentalTestApi::class)
class RecentChatsSectionTest {

    private fun conversation(id: String, title: String) = Conversation(
        id = id,
        title = title,
        providerId = "provider1",
        agentId = "agent1",
        createdAt = 1L,
        updatedAt = 2L,
        lastMessage = "hello"
    )

    @Test
    fun rendersRecentChatsAndOpensTheConversation() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            MaterialTheme {
                LazyColumn {
                    recentChatSection(
                        conversations = listOf(conversation("c1", "First chat")),
                        avatars = emptyMap(),
                        onClick = { opened += it }
                    )
                }
            }
        }

        // 标题来自 Compose 资源，随系统语言变化，所以只断言行本身。
        onNodeWithText("First chat").assertIsDisplayed()
        onNodeWithText("First chat").performClick()
        assertEquals(listOf("c1"), opened)
    }

    /** 每个快捷入口都跳到自己的会话，顺序与传入的最近顺序一致。 */
    @Test
    fun eachRowOpensItsOwnConversation() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            MaterialTheme {
                LazyColumn {
                    recentChatSection(
                        conversations = listOf(
                            conversation("c1", "First chat"),
                            conversation("c2", "Second chat")
                        ),
                        avatars = emptyMap(),
                        onClick = { opened += it }
                    )
                }
            }
        }

        onNodeWithText("Second chat").performClick()
        assertEquals(listOf("c2"), opened)
    }
}
