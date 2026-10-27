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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Markdown pipe tables used to render as loose, unaligned text: every row was
 * an independent [androidx.compose.foundation.layout.Row] sized to its own
 * cells, so columns never lined up and the header fill stopped at the widest
 * row. [RenderTable] now measures every cell up front and gives each column one
 * shared width, stretching the columns proportionally when the table fits the
 * bubble (mirroring `:renderer-android`'s TableView).
 */
@OptIn(ExperimentalTestApi::class)
class ChatDocumentTableLayoutTest {

    private val paneWidth = 800.dp

    private fun table() = ChatBlock.Table(
        id = 1L,
        head = listOf("Module", "Role", "Size"),
        rows = listOf(
            listOf("shared", "KMP library", "268"),
            listOf("androidApp", "Android shell", "41")
        ),
        isFinalized = true
    )

    @Test
    fun columnsAlignAcrossRows() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(paneWidth)) {
                    ChatDocumentView(blocks = listOf(table()))
                }
            }
        }

        // A column's cells must start at the same x on every row. Before the
        // fix each row placed its own cells, so the second row started further
        // left (or right) than the first.
        val header = onNodeWithText("Role").getUnclippedBoundsInRoot()
        val secondRow = onNodeWithText("KMP library").getUnclippedBoundsInRoot()
        val delta = abs((header.left - secondRow.left).value)
        assertTrue(
            delta <= 1f,
            "Second-column cells must share a left edge, got a ${delta}dp offset " +
                "(header ${header.left}, row ${secondRow.left})"
        )
    }

    /** A wide table must wrap its prose rather than force the bubble sideways. */
    @Test
    fun wideTableStaysInsideTheBubble() = runComposeUiTest {
        val narrowPane = 360.dp
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(narrowPane)) {
                    ChatDocumentBubble(
                        message = cc.ptoe.messenger.domain.model.Message(
                            id = "m1",
                            conversationId = "c1",
                            role = cc.ptoe.messenger.domain.model.MessageRole.ASSISTANT,
                            content = "",
                            timestamp = 1L,
                            status = cc.ptoe.messenger.domain.model.MessageStatus.SENT
                        ),
                        blocks = listOf(table()),
                        avatar = null,
                        isGenerating = false,
                        isLastInGroup = true,
                        onRetryClick = null
                    )
                }
            }
        }

        val bubble = onNodeWithTag(CHAT_BUBBLE_TEST_TAG).getUnclippedBoundsInRoot()
        val lastCell = onNodeWithText("268").getUnclippedBoundsInRoot()
        assertTrue(
            lastCell.right <= bubble.right + 1.dp,
            "Table cells must stay inside the bubble, cell ends at ${lastCell.right}, " +
                "bubble ends at ${bubble.right}"
        )
    }

    @Test
    fun tableFillsTheAvailableWidth() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(paneWidth)) {
                    ChatDocumentView(blocks = listOf(table()))
                }
            }
        }

        // The table card is a full-width block; the columns inside it are
        // stretched to fill it instead of packing to the left.
        val header = onNodeWithText("Module").getUnclippedBoundsInRoot()
        val lastCell = onNodeWithText("268").getUnclippedBoundsInRoot()
        assertTrue(
            lastCell.right > paneWidth / 2,
            "A fitting table must stretch its columns across the bubble, " +
                "got the last cell ending at ${lastCell.right} in a $paneWidth pane"
        )
        assertTrue(header.right > header.left, "header must have a measured width")
    }

    /** A table that is already wider than the card keeps its natural widths. */
    @Test
    fun fitKeepsNaturalWidthsWhenTheTableIsWiderThanTheCard() {
        val natural = intArrayOf(300, 600, 150)
        assertEquals(natural.toList(), fitTableColumns(natural, 350).toList())
        assertEquals(natural.toList(), fitTableColumns(natural, 500).toList())
    }

    @Test
    fun fitStretchesProportionallyWhenTheTableIsNarrower() {
        val fitted = fitTableColumns(intArrayOf(100, 200, 100), 800)
        assertEquals(800, fitted.sum(), "columns must fill the available width exactly")
        assertTrue(fitted[1] > fitted[0], "the widest column must stay the widest")
        // Proportional: 100 / 400 of 800 = 200, 200 -> 400.
        assertEquals(listOf(200, 400, 200), fitted.toList())
    }
}
