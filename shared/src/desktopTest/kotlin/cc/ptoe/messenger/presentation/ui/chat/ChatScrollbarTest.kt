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
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
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
import kotlin.test.assertEquals

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
        onNodeWithText("Message 99").assertIsDisplayed()
        scrollbar.performMouseInput {
            moveTo(Offset(center.x, scrollbarBounds.height - 8f))
            press()
            moveTo(Offset(center.x, 1f))
            release()
        }
        waitForIdle()
        onNodeWithText("Message 0").assertIsDisplayed()
    }

    @Test
    fun loadingOlderKeepsTheVisibleMessageInPlace() = runComposeUiTest {
        fun items(range: IntRange) = range.map { index ->
            ChatListItem.MessageItem(
                message = Message(
                    id = "message-$index", conversationId = "conversation", role = MessageRole.USER,
                    content = "Message $index", timestamp = index.toLong(), status = MessageStatus.SENT
                ),
                isLastInGroup = true
            )
        }
        var rows by mutableStateOf(items(30..59))
        var loading by mutableStateOf(false)
        var requests = 0
        setContent {
            MaterialTheme {
                ChatMessageList(
                    chatItems = rows, isGenerating = false, streamingContent = null,
                    streamingMessageId = null, streamingDiff = null, agent = null,
                    userAvatar = null, enableContextMenu = false, copiedToastText = "Copied",
                    onRetryClick = {}, onMessageLongClick = {}, onCopyClick = {},
                    onRegenerateClick = {}, onDeleteClick = {},
                    hasOlderMessages = true, isLoadingHistory = loading,
                    onLoadOlderMessages = { requests++; loading = true },
                    modifier = Modifier.size(400.dp, 400.dp)
                )
            }
        }
        val scrollbar = onNodeWithTag("chat_history_scrollbar")
        val height = scrollbar.fetchSemanticsNode().boundsInRoot.height
        scrollbar.performMouseInput {
            moveTo(Offset(center.x, height - 8f))
            press()
            moveTo(Offset(center.x, 1f))
            release()
        }
        waitForIdle()
        assertEquals(1, requests)
        val before = onNodeWithText("Message 30").fetchSemanticsNode().boundsInRoot.top
        runOnIdle { rows = items(0..59); loading = false }
        waitForIdle()
        val after = onNodeWithText("Message 30").fetchSemanticsNode().boundsInRoot.top
        assertTrue(kotlin.math.abs(before - after) < 2f, "Loading history must not move the visible message")
        assertEquals(1, requests)
    }


    @Test
    fun scrollbarThumbStaysTheSameSizeAcrossUnequalBubbles() = runComposeUiTest {
        val items = (0 until 30).map { index ->
            ChatListItem.MessageItem(
                message = Message(
                    id = "message-$index", conversationId = "conversation", role = MessageRole.USER,
                    content = if (index % 2 == 0) "Short $index" else "Long $index " + "wide message ".repeat(70),
                    timestamp = index.toLong(), status = MessageStatus.SENT
                ), isLastInGroup = true
            )
        }
        setContent {
            MaterialTheme {
                val style = defaultScrollbarStyle().copy(
                    unhoverColor = Color.Magenta, hoverColor = Color.Magenta, hoverDurationMillis = 0
                )
                CompositionLocalProvider(LocalScrollbarStyle provides style) {
                    ChatMessageList(
                        chatItems = items, isGenerating = false, streamingContent = null,
                        streamingMessageId = null, streamingDiff = null, agent = null,
                        userAvatar = null, enableContextMenu = false, copiedToastText = "Copied",
                        onRetryClick = {}, onMessageLongClick = {}, onCopyClick = {},
                        onRegenerateClick = {}, onDeleteClick = {},
                        modifier = Modifier.size(400.dp, 400.dp)
                    )
                }
            }
        }
        val scrollbar = onNodeWithTag("chat_history_scrollbar")
        fun thumbRows(): IntRange {
            val pixels = scrollbar.captureToImage().toPixelMap()
            val rows = (0 until pixels.height).filter { y ->
                (0 until pixels.width).any { x ->
                    val color = pixels[x, y]
                    color.red > 0.9f && color.blue > 0.9f && color.green < 0.1f
                }
            }
            assertTrue(rows.isNotEmpty(), "Scrollbar thumb must be visible")
            return rows.first()..rows.last()
        }
        val before = thumbRows()
        val height = scrollbar.fetchSemanticsNode().boundsInRoot.height
        scrollbar.performMouseInput {
            moveTo(Offset(center.x, (before.first + before.last) / 2f))
            press()
            moveTo(Offset(center.x, height / 2f))
            release()
        }
        waitForIdle()
        val after = thumbRows()
        assertEquals(before.last - before.first, after.last - after.first)
        assertTrue(after.first < before.first, "Dragging upwards must move the thumb upwards")
    }

}

