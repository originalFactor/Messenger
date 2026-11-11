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

/**
 * Pure-logic tests for [groupIntoParagraphs] — the CommonMark soft-break / paragraph-break
 * handling that the renderer's [planBlocks] relies on.
 */
class ParagraphGroupingTest {

    @Test
    fun emptyInput_emitsNoGroups() {
        assertEquals(emptyList(), groupIntoParagraphs(emptyList()))
    }

    @Test
    fun singleNewline_isSoftBreak_becomesSpace() {
        // "a\nb" → one paragraph with a space joining the halves (CommonMark soft break).
        val tokens = listOf(MdToken.Plain("a"), MdToken.Newline, MdToken.Plain("b"))
        assertEquals(
            listOf(listOf(MdToken.Plain("a"), MdToken.Plain(" "), MdToken.Plain("b"))),
            groupIntoParagraphs(tokens),
        )
    }

    @Test
    fun twoNewlines_areParagraphBreak() {
        val tokens = listOf(
            MdToken.Plain("a"), MdToken.Newline, MdToken.Newline, MdToken.Plain("b"),
        )
        assertEquals(
            listOf(listOf(MdToken.Plain("a")), listOf(MdToken.Plain("b"))),
            groupIntoParagraphs(tokens),
        )
    }

    @Test
    fun manyNewlines_collapseToOneParagraphBreak() {
        // Five newlines should behave the same as two — one paragraph break, no empty groups.
        val tokens = listOf(
            MdToken.Plain("a"),
            MdToken.Newline, MdToken.Newline, MdToken.Newline, MdToken.Newline, MdToken.Newline,
            MdToken.Plain("b"),
        )
        assertEquals(
            listOf(listOf(MdToken.Plain("a")), listOf(MdToken.Plain("b"))),
            groupIntoParagraphs(tokens),
        )
    }

    @Test
    fun singleNewlineAtBlockBoundary_isDropped() {
        // A single newline right after a block-level token (heading) shouldn't seed the next
        // paragraph with a leading space.
        val tokens = listOf(
            MdToken.Heading(1, listOf(MdToken.Plain("Title"))),
            MdToken.Newline,
            MdToken.Plain("body"),
        )
        assertEquals(
            listOf(listOf(MdToken.Heading(1, listOf(MdToken.Plain("Title")))), listOf(MdToken.Plain("body"))),
            groupIntoParagraphs(tokens),
        )
    }

    @Test
    fun heading_alwaysOwnGroup_andBreaksParagraph() {
        val tokens = listOf(
            MdToken.Plain("intro"),
            MdToken.Newline,
            MdToken.Heading(2, listOf(MdToken.Plain("Section"))),
            MdToken.Plain("after"),
        )
        // The single newline before the heading is a block-boundary newline → dropped (no space
        // seeds the heading group). The heading is its own group, then "after" starts a new group.
        assertEquals(
            listOf(
                listOf(MdToken.Plain("intro")),
                listOf(MdToken.Heading(2, listOf(MdToken.Plain("Section")))),
                listOf(MdToken.Plain("after")),
            ),
            groupIntoParagraphs(tokens),
        )
    }

    @Test
    fun codeBlock_isOwnGroup() {
        val cb = MdToken.CodeBlock("kotlin", "val x = 1\n", closed = true)
        val tokens = listOf(MdToken.Plain("before"), MdToken.Newline, MdToken.Newline, cb)
        assertEquals(
            listOf(listOf(MdToken.Plain("before")), listOf(cb)),
            groupIntoParagraphs(tokens),
        )
    }

    @Test
    fun displayMath_isOwnGroup() {
        val dm = MdToken.DisplayMath("x^2", closed = true)
        val tokens = listOf(MdToken.Plain("p"), MdToken.Newline, dm, MdToken.Newline, MdToken.Plain("q"))
        assertEquals(
            listOf(
                listOf(MdToken.Plain("p")),
                listOf(dm),
                listOf(MdToken.Plain("q")),
            ),
            groupIntoParagraphs(tokens),
        )
    }

    @Test
    fun inlineMath_staysInParagraphAcrossSoftBreak() {
        // `$a$\n$b$` — single newline is a soft break; both inline math fragments stay in one
        // paragraph joined by a space.
        val tokens = listOf(
            MdToken.InlineMath("a", closed = true),
            MdToken.Newline,
            MdToken.InlineMath("b", closed = true),
        )
        assertEquals(
            listOf(
                listOf(
                    MdToken.InlineMath("a", closed = true),
                    MdToken.Plain(" "),
                    MdToken.InlineMath("b", closed = true),
                ),
            ),
            groupIntoParagraphs(tokens),
        )
    }

    @Test
    fun leadingNewlines_produceNoEmptyGroups() {
        val tokens = listOf(MdToken.Newline, MdToken.Newline, MdToken.Plain("x"))
        assertEquals(listOf(listOf(MdToken.Plain("x"))), groupIntoParagraphs(tokens))
    }

    @Test
    fun trailingNewlines_produceNoEmptyGroups() {
        val tokens = listOf(MdToken.Plain("x"), MdToken.Newline, MdToken.Newline)
        assertEquals(listOf(listOf(MdToken.Plain("x"))), groupIntoParagraphs(tokens))
    }

    @Test
    fun multipleSoftBreaks_eachBecomeSpace() {
        // "a\nb\nc" — two single newlines, both soft breaks → one paragraph "a b c".
        val tokens = listOf(
            MdToken.Plain("a"), MdToken.Newline,
            MdToken.Plain("b"), MdToken.Newline,
            MdToken.Plain("c"),
        )
        assertEquals(
            listOf(listOf(
                MdToken.Plain("a"), MdToken.Plain(" "),
                MdToken.Plain("b"), MdToken.Plain(" "),
                MdToken.Plain("c"),
            )),
            groupIntoParagraphs(tokens),
        )
    }

    // --- Block quotes ---

    @Test
    fun consecutiveBlockQuotes_groupTogether() {
        val a = MdToken.BlockQuote(level = 1, inline = listOf(MdToken.Plain("a")))
        val b = MdToken.BlockQuote(level = 1, inline = listOf(MdToken.Plain("b")))
        assertEquals(
            listOf(listOf(a, b)),
            groupIntoParagraphs(listOf(a, b)),
        )
    }

    @Test
    fun blankLineBetweenBlockQuotes_splitsGroups() {
        val a = MdToken.BlockQuote(level = 1, inline = listOf(MdToken.Plain("a")))
        val b = MdToken.BlockQuote(level = 1, inline = listOf(MdToken.Plain("b")))
        val tokens = listOf(a, MdToken.Newline, MdToken.Newline, b)
        assertEquals(
            listOf(listOf(a), listOf(b)),
            groupIntoParagraphs(tokens),
        )
    }

    @Test
    fun textAfterBlockQuote_singleNewlineFlushesQuote() {
        val quote = MdToken.BlockQuote(level = 1, inline = listOf(MdToken.Plain("quoted")))
        val tokens = listOf(quote, MdToken.Newline, MdToken.Plain("after"))
        assertEquals(
            listOf(listOf(quote), listOf(MdToken.Plain("after"))),
            groupIntoParagraphs(tokens),
        )
    }

    // --- Lists ---

    @Test
    fun consecutiveListItems_groupTogether() {
        // Two list items with no intervening newline token (the parser consumes the trailing
        // newline) — they should land in the same group so the renderer can build one list tree.
        val a = MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("a")))
        val b = MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("b")))
        assertEquals(
            listOf(listOf(a, b)),
            groupIntoParagraphs(listOf(a, b)),
        )
    }

    @Test
    fun blankLineBetweenListItems_splitsIntoTwoListGroups() {
        // `- a\n\n- b` — blank line separates the items into two list groups (two separate lists).
        val a = MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("a")))
        val b = MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("b")))
        val tokens = listOf(a, MdToken.Newline, MdToken.Newline, b)
        assertEquals(
            listOf(listOf(a), listOf(b)),
            groupIntoParagraphs(tokens),
        )
    }

    @Test
    fun textAfterList_singleNewlineFlushesList() {
        // A single newline between a list item and inline text ends the list group — the text
        // starts a fresh paragraph instead of being soft-break-joined onto the last item.
        val a = MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("a")))
        val tokens = listOf(a, MdToken.Newline, MdToken.Plain("after"))
        assertEquals(
            listOf(listOf(a), listOf(MdToken.Plain("after"))),
            groupIntoParagraphs(tokens),
        )
    }

    @Test
    fun listFollowedByBlockLevel_singleNewlineDropped() {
        // A single newline between a list item and a block-level token (e.g. heading) is dropped
        // — no leading-space seed in the heading's group.
        val a = MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("a")))
        val h = MdToken.Heading(2, listOf(MdToken.Plain("Section")))
        val tokens = listOf(a, MdToken.Newline, h)
        assertEquals(
            listOf(listOf(a), listOf(h)),
            groupIntoParagraphs(tokens),
        )
    }

    @Test
    fun listInterruptsParagraph() {
        // Some inline text, single newline, then a list item — the list item starts a fresh group.
        val a = MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("a")))
        val tokens = listOf(MdToken.Plain("intro"), MdToken.Newline, a)
        assertEquals(
            listOf(listOf(MdToken.Plain("intro")), listOf(a)),
            groupIntoParagraphs(tokens),
        )
    }

    @Test
    fun listFollowedByAnotherList_noSpaceJoined() {
        // Two list items separated by a single newline token (shouldn't normally happen — the
        // parser consumes trailing newlines — but if it does, the newline should be dropped, not
        // turned into a soft-break space).
        val a = MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("a")))
        val b = MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("b")))
        val tokens = listOf(a, MdToken.Newline, b)
        assertEquals(
            listOf(listOf(a, b)),
            groupIntoParagraphs(tokens),
        )
    }

    @Test
    fun paragraphFollowedByList_noLeadingSpaceInList() {
        // A paragraph followed by a list — the list starts its own group; no Plain(" ") gets
        // seeded at the front of the list group from the boundary newline.
        val a = MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("a")))
        val tokens = listOf(MdToken.Plain("intro"), MdToken.Newline, MdToken.Newline, a)
        assertEquals(
            listOf(listOf(MdToken.Plain("intro")), listOf(a)),
            groupIntoParagraphs(tokens),
        )
    }

    // --- Tables ---

    @Test
    fun table_isOwnGroup_andBreaksParagraph() {
        val table = MdToken.Table(
            headers = listOf("a", "b"),
            aligns = listOf(TableAlign.DEFAULT, TableAlign.DEFAULT),
            rows = listOf(listOf("1", "2")),
            closed = true,
        )
        val tokens = listOf(MdToken.Plain("intro"), MdToken.Newline, table, MdToken.Newline, MdToken.Plain("after"))
        // The single newline before the table is a block-boundary newline → dropped. The table is
        // its own group; "after" starts a new group.
        assertEquals(
            listOf(
                listOf(MdToken.Plain("intro")),
                listOf(table),
                listOf(MdToken.Plain("after")),
            ),
            groupIntoParagraphs(tokens),
        )
    }

    // --- buildListBlock: task-list `checked` propagation ---

    @Test
    fun buildListBlock_propagatesCheckedFlag() {
        // Plain and task items keep their `checked` status in the built tree.
        val tokens = listOf(
            MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("plain")), checked = null),
            MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("todo")), checked = false),
            MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("done")), checked = true),
        )
        val block = buildListBlock(tokens)
        assertEquals(null, block.items[0].checked)
        assertEquals(false, block.items[1].checked)
        assertEquals(true, block.items[2].checked)
    }

    @Test
    fun buildListBlock_propagatesCheckedIntoNestedSublist() {
        // A nested task item keeps its `checked` status through the sublist tree.
        val tokens = listOf(
            MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("parent")), checked = true),
            MdToken.ListItem(ordered = false, number = 0, indent = 1, inline = listOf(MdToken.Plain("child")), checked = false),
        )
        val block = buildListBlock(tokens)
        assertEquals(true, block.items[0].checked)
        assertEquals(1, block.items[0].sublists.size)
        val child = block.items[0].sublists[0].items.single()
        assertEquals(false, child.checked)
        assertEquals(listOf(MdToken.Plain("child")), child.inline)
    }

    @Test
    fun buildListBlock_defaultCheckedIsNull() {
        // ListItem constructed without `checked` (legacy callers) defaults to null in the tree.
        val tokens = listOf(
            MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("a"))),
        )
        val block = buildListBlock(tokens)
        assertEquals(null, block.items[0].checked)
    }
}
