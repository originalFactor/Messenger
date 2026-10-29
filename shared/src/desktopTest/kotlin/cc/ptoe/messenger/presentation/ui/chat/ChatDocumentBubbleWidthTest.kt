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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Desktop/Web assistant bubbles are rendered by the shared Compose document
 * renderer. They must hug their text (a short reply is a small pill) while a
 * long answer still keeps a right margin instead of stretching to the pane's
 * edge. Android gets both from the native renderer's wrap-content + row insets.
 */
@OptIn(ExperimentalTestApi::class)
class ChatDocumentBubbleWidthTest {

    private val paneWidth = 800.dp

    private fun message(content: String) = Message(
        id = "m1",
        conversationId = "c1",
        role = MessageRole.ASSISTANT,
        content = content,
        timestamp = 1L,
        status = MessageStatus.SENT
    )

    @Test
    fun shortReplyDoesNotFillThePane() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(paneWidth)) {
                    ChatDocumentBubble(
                        message = message("hello"),
                        blocks = null,
                        avatar = null,
                        isGenerating = false,
                        isLastInGroup = true,
                        onRetryClick = null
                    )
                }
            }
        }

        val bubble = onNodeWithTag(CHAT_BUBBLE_TEST_TAG).getUnclippedBoundsInRoot()
        assertTrue(
            bubble.width < paneWidth / 2,
            "A one-word reply must size to its text, got width ${bubble.width} in a $paneWidth pane"
        )
    }

    @Test
    fun longReplyKeepsARightMargin() = runComposeUiTest {
        val longText = buildString {
            repeat(40) { append("This is a long paragraph of assistant prose. ") }
        }
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(paneWidth)) {
                    ChatDocumentBubble(
                        message = message(longText),
                        blocks = null,
                        avatar = null,
                        isGenerating = false,
                        isLastInGroup = true,
                        onRetryClick = null
                    )
                }
            }
        }

        val bubble = onNodeWithTag(CHAT_BUBBLE_TEST_TAG).getUnclippedBoundsInRoot()
        val margin: Dp = paneWidth - bubble.right
        assertTrue(
            margin >= 40.dp,
            "Long replies must keep a right margin, got ${margin} at the bubble's right edge"
        )
    }

    /**
     * The avatar is bottom-aligned with the bubble so it sits beside the bubble's
     * tail corner. The timestamp lives below the row: when it was inside the
     * bubble's column the row's tallest child was bubble + timestamp, which
     * pushed the avatar ~16.6 dp below the bubble's bottom edge (measured at
     * 175% scale) and hid the tail.
     */
    @Test
    fun avatarBottomAlignsWithTheBubble() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(paneWidth)) {
                    ChatDocumentBubble(
                        message = message("hello"),
                        blocks = null,
                        avatar = null,
                        isGenerating = false,
                        isLastInGroup = true,
                        onRetryClick = null
                    )
                }
            }
        }

        val bubble = onNodeWithTag(CHAT_BUBBLE_TEST_TAG).getUnclippedBoundsInRoot()
        val avatar = onNodeWithTag(CHAT_AVATAR_TEST_TAG).getUnclippedBoundsInRoot()
        val delta = (avatar.bottom - bubble.bottom).value
        assertTrue(
            kotlin.math.abs(delta) <= 1f,
            "Avatar must bottom-align with the bubble, got a ${delta}dp offset " +
                "(avatar ${avatar.top}..${avatar.bottom}, bubble ${bubble.top}..${bubble.bottom})"
        )
    }

    @Test
    fun errorShowsThePersistedErrorDetailsAndRetryAction() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(paneWidth)) {
                    ChatDocumentBubble(
                        message = Message(
                            id = "err",
                            conversationId = "c1",
                            role = MessageRole.ASSISTANT,
                            content = "provider rejected the request",
                            timestamp = 1L,
                            status = MessageStatus.ERROR,
                            errorMessage = "provider rejected the request",
                        ),
                        blocks = null,
                        avatar = null,
                        isGenerating = false,
                        isLastInGroup = true,
                        onRetryClick = {},
                    )
                }
            }
        }
        onNodeWithText("provider rejected the request").assertExists()
        onNodeWithText("Retry").assertExists()
    }

}
