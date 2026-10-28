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

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChatScrollbarTest {
    @Test
    fun scrollbarIsInsetAndCanDragToOlderMessages() = runComposeUiTest {
        val items = (0 until 100).map { index ->
            ChatListItem.MessageItem(
                message = Message(
                    id = "message-$index",
                    conversationId = "conversation",
                    role = MessageRole.USER,
                    content = "Message $index",
                    timestamp = index.toLong(),
                    status = MessageStatus.SENT
                ),
                isLastInGroup = true
            )
        }
        setContent {
            MaterialTheme {
                ChatMessageList(
                    chatItems = items,
                    isGenerating = false,
                    streamingContent = null,
                    streamingMessageId = null,
                    streamingDiff = null,
                    agent = null,
                    userAvatar = null,
                    enableContextMenu = false,
                    copiedToastText = "Copied",
                    onRetryClick = {},
                    onMessageLongClick = {},
                    onCopyClick = {},
                    onRegenerateClick = {},
                    onDeleteClick = {},
                    modifier = Modifier.size(400.dp, 400.dp).testTag("chat_history")
                )
            }
        }

        val scrollbar = onNodeWithTag("chat_history_scrollbar")
        val listBounds = onNodeWithTag("chat_history").fetchSemanticsNode().boundsInRoot
        val scrollbarBounds = scrollbar.fetchSemanticsNode().boundsInRoot
        assertTrue(
            listBounds.right - scrollbarBounds.right >= listBounds.width * 0.039f,
            "Scrollbar must be inset by at least 16 dp from the window edge"
        )
        onNodeWithText("Message 99").assertExists()
        scrollbar.performMouseInput {
            moveTo(Offset(center.x, scrollbarBounds.height - 8f))
            press()
            moveTo(Offset(center.x, 1f))
            release()
        }
        waitForIdle()
        onNodeWithText("Message 0").assertExists()
    }
}

