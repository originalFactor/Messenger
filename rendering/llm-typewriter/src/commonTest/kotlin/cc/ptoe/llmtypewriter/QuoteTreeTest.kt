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

package cc.ptoe.llmtypewriter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-logic tests for [buildQuoteTree] — the function that turns a flat run of
 * [MdToken.BlockQuote] tokens (one per `>` line) into a tree where same-level lines share one
 * visual quote box and deeper-level runs become nested quote children.
 *
 * The behaviour under test: a multi-line quote like `> a\n> b` must produce a single tree node
 * containing both lines (so the renderer draws ONE box, not two).
 */
class QuoteTreeTest {

    private fun line(level: Int, text: String) =
        MdToken.BlockQuote(level = level, inline = listOf(MdToken.Plain(text)))

    private fun emptyLine(level: Int) =
        MdToken.BlockQuote(level = level, inline = emptyList())

    @Test
    fun emptyInput_returnsEmptyNode() {
        assertEquals(QuoteNode(emptyList()), buildQuoteTree(emptyList()))
    }

    @Test
    fun singleLine_returnsSingleLineElement() {
        val a = line(1, "a")
        assertEquals(QuoteNode(listOf(QuoteElement.Line(a))), buildQuoteTree(listOf(a)))
    }

    @Test
    fun multiLineSameLevel_groupsIntoOneNode() {
        // `> a\n> b` — the bug fix: both lines land in ONE tree node so the renderer draws one box.
        val a = line(1, "a")
        val b = line(1, "b")
        assertEquals(
            QuoteNode(listOf(QuoteElement.Line(a), QuoteElement.Line(b))),
            buildQuoteTree(listOf(a, b)),
        )
    }

    @Test
    fun multiLineThreeLinesSameLevel_allInOneNode() {
        val a = line(1, "a")
        val b = line(1, "b")
        val c = line(1, "c")
        assertEquals(
            QuoteNode(listOf(QuoteElement.Line(a), QuoteElement.Line(b), QuoteElement.Line(c))),
            buildQuoteTree(listOf(a, b, c)),
        )
    }

    @Test
    fun emptyQuoteLineInMiddle_staysInSameNode() {
        // `> a\n>\n> b` — blank quote line should NOT split the box.
        val a = line(1, "a")
        val blank = emptyLine(1)
        val b = line(1, "b")
        assertEquals(
            QuoteNode(listOf(QuoteElement.Line(a), QuoteElement.Line(blank), QuoteElement.Line(b))),
            buildQuoteTree(listOf(a, blank, b)),
        )
    }

    @Test
    fun nestedLevel_becomesNestedSubtree() {
        // `> a\n>> b` — `b` is one level deeper, becomes a Nested child of the level-1 box.
        val a = line(1, "a")
        val b = line(2, "b")
        val tree = buildQuoteTree(listOf(a, b))
        assertEquals(2, tree.elements.size)
        assertEquals(QuoteElement.Line(a), tree.elements[0])
        val nested = tree.elements[1]
        assertTrue(nested is QuoteElement.Nested, "expected Nested, got $nested")
        // The nested subtree normalizes its level to 1.
        assertEquals(
            QuoteNode(listOf(QuoteElement.Line(line(1, "b")))),
            nested.subtree,
        )
    }

    @Test
    fun nestedRun_preservesSourceOrderWithSurroundingLines() {
        // `> a\n>> b\n> c` — Nested sits between Line(a) and Line(c), preserving source order.
        val a = line(1, "a")
        val b = line(2, "b")
        val c = line(1, "c")
        val tree = buildQuoteTree(listOf(a, b, c))
        assertEquals(3, tree.elements.size)
        assertEquals(QuoteElement.Line(a), tree.elements[0])
        assertTrue(tree.elements[1] is QuoteElement.Nested)
        assertEquals(QuoteElement.Line(c), tree.elements[2])
    }

    @Test
    fun consecutiveDeeperLines_groupIntoOneNestedSubtree() {
        // `> a\n>> b\n>> c\n> d` — the run `>> b\n>> c` forms ONE Nested subtree (not two).
        val a = line(1, "a")
        val b = line(2, "b")
        val c = line(2, "c")
        val d = line(1, "d")
        val tree = buildQuoteTree(listOf(a, b, c, d))
        assertEquals(3, tree.elements.size)
        assertEquals(QuoteElement.Line(a), tree.elements[0])
        val nested = tree.elements[1] as QuoteElement.Nested
        // Both deeper lines are in the same subtree, normalized to level 1.
        assertEquals(
            QuoteNode(listOf(QuoteElement.Line(line(1, "b")), QuoteElement.Line(line(1, "c")))),
            nested.subtree,
        )
        assertEquals(QuoteElement.Line(d), tree.elements[2])
    }

    @Test
    fun tripleNestedLevel_recursivelyBuildsTree() {
        // `> a\n>>> b` — jumps from level 1 to level 3. Normalization at the first step maps
        // level 3 → 3 - 1 = 2 in the subtree. The subtree then treats level 2 as its base, so `b`
        // becomes a Line at level 2 (no further nesting — only one deeper line in the run).
        val a = line(1, "a")
        val b = line(3, "b")
        val tree = buildQuoteTree(listOf(a, b))
        val outer = tree.elements[1] as QuoteElement.Nested
        assertEquals(
            QuoteNode(listOf(QuoteElement.Line(line(2, "b")))),
            outer.subtree,
        )
    }

    @Test
    fun tripleNestedChain_recursivelyBuildsTree() {
        // `> a\n>> b\n>>> c` — `>> b` and `>>> c` form one nested run (levels 2 and 3).
        // Normalized subtree: level 1 `b`, level 2 `c`. The recursion then nests `c` under `b`.
        val a = line(1, "a")
        val b = line(2, "b")
        val c = line(3, "c")
        val tree = buildQuoteTree(listOf(a, b, c))
        val outer = tree.elements[1] as QuoteElement.Nested
        assertEquals(
            QuoteNode(listOf(
                QuoteElement.Line(line(1, "b")),
                QuoteElement.Nested(QuoteNode(listOf(QuoteElement.Line(line(1, "c"))))),
            )),
            outer.subtree,
        )
    }
}
