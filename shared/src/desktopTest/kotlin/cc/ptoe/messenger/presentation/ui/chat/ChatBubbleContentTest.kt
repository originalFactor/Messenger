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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A finished assistant turn renders through `ChatMarkdownText(isStreaming = false)`.
 * That path must paint the message: it previously handed the Markdown renderer a
 * `StreamingTypewriterState` that had never received a token, so every settled
 * reply drew as an empty pill under a correctly sized row.
 */
@OptIn(ExperimentalTestApi::class)
class ChatBubbleContentTest {

    private fun assistantMessage(content: String) = Message(
        id = "m1",
        conversationId = "c1",
        role = MessageRole.ASSISTANT,
        content = content,
        timestamp = 1L,
        status = MessageStatus.SENT
    )

    @Test
    fun settledAssistantReplyPaintsItsText() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(800.dp)) {
                    ChatDocumentBubble(
                        message = assistantMessage("Hello! How can I help you today?"),
                        blocks = null,
                        avatar = null,
                        isGenerating = false,
                        isLastInGroup = true,
                        onRetryClick = null
                    )
                }
            }
        }

        onNodeWithText("Hello! How can I help you today?", substring = true).assertExists()
    }

    /**
     * The bubble must also MEASURE its text: an empty render still produced a
     * correctly sized row, so only the painted height proves the content is
     * really laid out.
     */
    @Test
    fun settledAssistantReplyGrowsTheBubble() = runComposeUiTest {
        val longText = buildString {
            repeat(12) { append("This is a long paragraph of assistant prose. ") }
        }
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(800.dp)) {
                    ChatDocumentBubble(
                        message = assistantMessage(longText),
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
            bubble.height > 80.dp,
            "A multi-paragraph reply must lay out its text, got a ${bubble.height} tall bubble"
        )
    }
}
