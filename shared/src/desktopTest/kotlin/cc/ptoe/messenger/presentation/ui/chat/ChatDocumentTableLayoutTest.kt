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

import androidx.compose.ui.test.onNodeWithTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Markdown pipe tables used to render as loose, unaligned text: every row was
 * an independent [androidx.compose.foundation.layout.Row] sized to its own
 * cells, so columns never lined up and the header fill stopped at the widest
 * row. [RenderTable] now measures every cell up front and gives each column one
 * shared width, stretching the columns proportionally when the table fits the
 * bubble.
 */
class ChatDocumentTableLayoutTest {
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
