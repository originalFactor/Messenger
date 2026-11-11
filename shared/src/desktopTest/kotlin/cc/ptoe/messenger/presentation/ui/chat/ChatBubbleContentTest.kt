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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import cc.ptoe.llmtypewriter.LlmTypewriterDefaults
import cc.ptoe.llmtypewriter.StreamingTypewriterState
import cc.ptoe.llmtypewriter.rememberMarkdownTypewriterRenderer
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.chat_think_title
import cc.ptoe.messenger.generated.resources.chat_think_expanded
import cc.ptoe.messenger.generated.resources.chat_think_collapsed
import org.jetbrains.compose.resources.stringResource
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

    @Test
    fun finishedThinkingCardUsesLocalizedLabelAndExpandsFromHeader() = runComposeUiTest {
        var title = ""
        var expanded = ""
        var collapsed = ""
        setContent {
            MaterialTheme {
                title = stringResource(Res.string.chat_think_title)
                expanded = stringResource(Res.string.chat_think_expanded)
                collapsed = stringResource(Res.string.chat_think_collapsed)
                ChatMarkdownText(
                    markdown = "<think>Reasoning details</think>Answer",
                    isStreaming = false,
                    modifier = Modifier.width(400.dp),
                )
            }
        }
        onNodeWithText(title).assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsed))
        onNodeWithText("Reasoning details").assertDoesNotExist()
        onNodeWithText(title).performClick()
        onNodeWithText(title).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expanded))
        onNodeWithText("Reasoning details").assertExists()
        onNodeWithText(title).performClick()
        onNodeWithText("Reasoning details").assertDoesNotExist()
    }

    @Test
    fun streamingThinkingCardCollapsesWhenTheBlockCloses() = runComposeUiTest {
        val state = StreamingTypewriterState().apply {
            appendToken("<think>Live reasoning")
            skipToEnd()
        }
        setContent {
            MaterialTheme {
                val styles = LlmTypewriterDefaults.markdownStyles().copy(thinkBlockTitle = "Localized heading")
                rememberMarkdownTypewriterRenderer(state, styles).Render("", Modifier.width(400.dp))
            }
        }
        onNodeWithText("Live reasoning").assertExists()
        runOnIdle {
            state.appendToken("</think>")
            state.skipToEnd()
        }
        onNodeWithText("Localized heading").assertHasClickAction()
        onNodeWithText("Live reasoning").assertDoesNotExist()
        onNodeWithText("Localized heading").performClick()
        onNodeWithText("Live reasoning").assertExists()
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
