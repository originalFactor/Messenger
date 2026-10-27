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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertTrue

/** Exercise pointer hit testing, not the semantics-only performClick action. */
@OptIn(ExperimentalTestApi::class)
class ChatDocumentLongInteractionTest {
    @Test
    fun cardsAtBottomOfLongBubbleReceiveMouseClicks() = runComposeUiTest {
        val blocks = buildList {
            repeat(180) {
                add(ChatBlock.Paragraph(it.toLong(), "Paragraph $it: " + "long text ".repeat(35), emptyList(), true))
            }
            add(ChatBlock.Think(1000, "Unique expanded thought", true))
            add(ChatBlock.ToolCall(1001, "call", "unique_tool", "", "Unique tool output", false, true))
        }
        setContent {
            MaterialTheme {
                // As in the chat screen, the bottom of a long item is visible first.
                LazyColumn(modifier = Modifier.size(600.dp, 500.dp), reverseLayout = true) {
                    item {
                        ChatDocumentBubble(
                            message = Message(
                                id = "m1",
                                conversationId = "c1",
                                role = MessageRole.ASSISTANT,
                                content = "",
                                timestamp = 1L,
                                status = MessageStatus.SENT
                            ),
                            blocks = blocks,
                            avatar = null,
                            isGenerating = false,
                            isLastInGroup = true,
                            onRetryClick = null
                        )
                    }
                }
            }
        }

        fun assertHover(node: SemanticsNodeInteraction) {
            val before = node.captureToImage().toPixelMap()
            node.performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(500)
            val after = node.captureToImage().toPixelMap()
            assertTrue(
                (0 until before.height).any { y ->
                    (0 until before.width).any { x -> before[x, y] != after[x, y] }
                },
                "Card must show a hover indication at the bottom of a long bubble"
            )
        }

        val tool = onNodeWithText("unique_tool")
        assertHover(tool)
        tool.performMouseInput { click() }
        onNodeWithText("Unique tool output").assertExists()

        val thought = onNodeWithText("Thought process")
        assertHover(thought)
        thought.performMouseInput { click() }
        onNodeWithText("Unique expanded thought").assertExists()
    }
}
