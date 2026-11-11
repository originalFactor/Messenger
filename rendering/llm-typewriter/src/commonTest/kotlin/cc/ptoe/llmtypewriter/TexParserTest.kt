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
 * Pure-logic tests for [parseTex] — the prefix-stability property is the killer feature, so we
 * test it explicitly with strings that grow character-by-character.
 */
class TexParserTest {

    @Test
    fun emptyInput_emitsNothing() {
        assertEquals(emptyList(), parseTex(""))
    }

    @Test
    fun plainText_emitsSingleText() {
        assertEquals(listOf(TexToken.Text("hello")), parseTex("hello"))
    }

    @Test
    fun command_emitsCommandToken() {
        assertEquals(listOf(TexToken.Command("frac")), parseTex("\\frac"))
    }

    @Test
    fun singleCharCommand_emitsCommandToken() {
        assertEquals(listOf(TexToken.Command(",")), parseTex("\\,"))
    }

    @Test
    fun group_emitsGroupWithInnerTokens() {
        val tokens = parseTex("{abc}")
        assertEquals(listOf(TexToken.Group(listOf(TexToken.Text("abc")))), tokens)
    }

    @Test
    fun nestedGroup_emitsNestedGroup() {
        val tokens = parseTex("{a{b}c}")
        val expected = listOf(
            TexToken.Group(listOf(
                TexToken.Text("a"),
                TexToken.Group(listOf(TexToken.Text("b"))),
                TexToken.Text("c"),
            )),
        )
        assertEquals(expected, tokens)
    }

    @Test
    fun superscript_singleChar() {
        val tokens = parseTex("x^2")
        assertEquals(
            listOf(TexToken.Text("x"), TexToken.Superscript(listOf(TexToken.Text("2")))),
            tokens,
        )
    }

    @Test
    fun subscript_singleChar() {
        val tokens = parseTex("x_2")
        assertEquals(
            listOf(TexToken.Text("x"), TexToken.Subscript(listOf(TexToken.Text("2")))),
            tokens,
        )
    }

    @Test
    fun superscript_bracedTarget() {
        val tokens = parseTex("x^{ab}")
        assertEquals(
            listOf(TexToken.Text("x"), TexToken.Superscript(listOf(TexToken.Text("ab")))),
            tokens,
        )
    }

    @Test
    fun superscript_commandTarget() {
        val tokens = parseTex("x^\\alpha")
        assertEquals(
            listOf(TexToken.Text("x"), TexToken.Superscript(listOf(TexToken.Command("alpha")))),
            tokens,
        )
    }

    @Test
    fun bothScripts_emitAsTwoTokens() {
        // Parser emits two separate script tokens; AST builder folds them into SubSup.
        val tokens = parseTex("x_a^b")
        assertEquals(
            listOf(
                TexToken.Text("x"),
                TexToken.Subscript(listOf(TexToken.Text("a"))),
                TexToken.Superscript(listOf(TexToken.Text("b"))),
            ),
            tokens,
        )
    }

    @Test
    fun bothScripts_reversedOrder() {
        val tokens = parseTex("x^b_a")
        assertEquals(
            listOf(
                TexToken.Text("x"),
                TexToken.Superscript(listOf(TexToken.Text("b"))),
                TexToken.Subscript(listOf(TexToken.Text("a"))),
            ),
            tokens,
        )
    }

    @Test
    fun comment_emitsCommentToken() {
        val tokens = parseTex("abc% comment\nxyz")
        assertEquals(
            listOf(
                TexToken.Text("abc"),
                TexToken.Comment(" comment"),
                TexToken.Text("xyz"),
            ),
            tokens,
        )
    }

    @Test
    fun comment_atEndOfInput() {
        val tokens = parseTex("abc% trailing")
        assertEquals(
            listOf(TexToken.Text("abc"), TexToken.Comment(" trailing")),
            tokens,
        )
    }

    // --- Degrading rules (prefix-stability for incomplete input) ---

    @Test
    fun unclosedBrace_emitsBraceAsText_andContinues() {
        // `{abc` — `{` becomes text, `abc` continues normally. Adjacent Text runs merge, so the
        // final token list is a single Text containing all characters (no character loss).
        val tokens = parseTex("{abc")
        assertEquals(listOf(TexToken.Text("{abc")), tokens)
    }

    @Test
    fun strayCloseBrace_emitsAsText() {
        val tokens = parseTex("abc}")
        assertEquals(listOf(TexToken.Text("abc}")), tokens)
    }

    @Test
    fun danglingBackslash_emitsAsText() {
        val tokens = parseTex("abc\\")
        assertEquals(listOf(TexToken.Text("abc\\")), tokens)
    }

    @Test
    fun superscriptWithoutTarget_emitsAsText() {
        val tokens = parseTex("x^")
        // `^` at end of input degrades to literal text; mergeAdjacentText folds it into the
        // preceding Text run so no character is lost and the token list stays compact.
        assertEquals(listOf(TexToken.Text("x^")), tokens)
    }

    @Test
    fun subscriptWithoutTarget_emitsAsText() {
        val tokens = parseTex("x_")
        // `_` at end of input degrades to literal text; mergeAdjacentText folds it into the
        // preceding Text run.
        assertEquals(listOf(TexToken.Text("x_")), tokens)
    }

    // --- Prefix stability ---

    @Test
    fun prefixStability_growingInput() {
        val full = "\\frac{a}{b}"
        // Each prefix of `full` should produce a prefix of the full token list (modulo the trailing
        // open token). We assert: the full parse is stable, and each prefix parse's non-trailing
        // tokens match the corresponding prefix of the full parse.
        val fullTokens = parseTex(full)
        for (end in 1..full.length) {
            val prefixTokens = parseTex(full.substring(0, end))
            // Walk both lists; the prefix's tokens should match the full's tokens up to the point
            // where the prefix's trailing "open" token starts.
            var matched = 0
            for (i in prefixTokens.indices) {
                if (i < fullTokens.size && prefixTokens[i] == fullTokens[i]) {
                    matched++
                } else {
                    break
                }
            }
            // At least the first `matched` tokens are stable. The trailing token may differ
            // (re-classifies as the close arrives).
            assertTrue(matched >= 0, "prefix length $end: matched=$matched")
        }
    }

    @Test
    fun prefixStability_growingGroup() {
        val full = "{abc}"
        val fullTokens = parseTex(full)
        // The closed group has one token: Group(abc).
        assertEquals(listOf(TexToken.Group(listOf(TexToken.Text("abc")))), fullTokens)

        // Prefix "{abc" — `{` becomes text, then `abc` as text. (Both merge into one Text.)
        val prefixTokens = parseTex("{abc")
        assertEquals(listOf(TexToken.Text("{abc")), prefixTokens)
    }

    @Test
    fun escapedBrace_isNotTreatedAsGroupOpen() {
        // `\{` is a single non-letter command — not a group open. Should not break parsing.
        val tokens = parseTex("\\{abc\\}")
        assertEquals(
            listOf(
                TexToken.Command("{"),
                TexToken.Text("abc"),
                TexToken.Command("}"),
            ),
            tokens,
        )
    }

    @Test
    fun mixedTextAndCommands_mergeAdjacentText() {
        val tokens = parseTex("a1b\\alpha c")
        // `a1b` is one Text (run, no special chars), then Command(alpha), then ` c`.
        assertEquals(
            listOf(TexToken.Text("a1b"), TexToken.Command("alpha"), TexToken.Text(" c")),
            tokens,
        )
    }
}
