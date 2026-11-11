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
 * Pure-logic tests for [parseStreamingMarkdown] — the prefix-stability property is the killer
 * feature, so we test it explicitly with a string that grows character-by-character.
 */
class MarkdownStreamParserTest {

    @Test
    fun emptyInput_emitsNothing() {
        assertEquals(emptyList(), parseStreamingMarkdown(""))
    }

    @Test
    fun plainText_emitsSinglePlain() {
        assertEquals(listOf(MdToken.Plain("hello world")), parseStreamingMarkdown("hello world"))
    }

    @Test
    fun bold_doubleStar() {
        val tokens = parseStreamingMarkdown("a **b** c")
        assertEquals(
            listOf(MdToken.Plain("a "), MdToken.Bold("b"), MdToken.Plain(" c")),
            tokens,
        )
    }

    @Test
    fun bold_doubleUnderscore() {
        val tokens = parseStreamingMarkdown("__yes__")
        assertEquals(listOf(MdToken.Bold("yes")), tokens)
    }

    @Test
    fun italic_singleStar() {
        val tokens = parseStreamingMarkdown("hello *world*")
        assertEquals(
            listOf(MdToken.Plain("hello "), MdToken.Italic("world")),
            tokens,
        )
    }

    @Test
    fun italic_singleUnderscore() {
        val tokens = parseStreamingMarkdown("_yes_")
        assertEquals(listOf(MdToken.Italic("yes")), tokens)
    }

    @Test
    fun boldItalic_tripleStar() {
        val tokens = parseStreamingMarkdown("***both***")
        assertEquals(listOf(MdToken.BoldItalic("both")), tokens)
    }

    @Test
    fun boldContainingItalic_preservesNestedMarkdownPayload() {
        assertEquals(
            listOf(MdToken.Bold("_bold italic_")),
            parseStreamingMarkdown("**_bold italic_**"),
        )
    }

    @Test
    fun inlineCode_backticks() {
        val tokens = parseStreamingMarkdown("call `foo()` here")
        assertEquals(
            listOf(MdToken.Plain("call "), MdToken.InlineCode("foo()"), MdToken.Plain(" here")),
            tokens,
        )
    }

    @Test
    fun strikethrough_doubleTilde() {
        val tokens = parseStreamingMarkdown("~~gone~~")
        assertEquals(listOf(MdToken.Strikethrough("gone")), tokens)
    }

    @Test
    fun link_simple() {
        val tokens = parseStreamingMarkdown("see [docs](https://example.com)")
        assertEquals(
            listOf(
                MdToken.Plain("see "),
                MdToken.Link("docs", "https://example.com"),
            ),
            tokens,
        )
    }

    @Test
    fun image_simple() {
        assertEquals(
            listOf(
                MdToken.Plain("see "),
                MdToken.Image("a cat", "https://example.com/cat.png"),
            ),
            parseStreamingMarkdown("see ![a cat](https://example.com/cat.png)"),
        )
    }

    @Test
    fun image_prefixIsPlainUntilClosed() {
        val full = "![alt](https://example.com/image.png)"
        for (length in 1 until full.length) {
            assertTrue(parseStreamingMarkdown(full.substring(0, length)).none {
                it is MdToken.Image
            }, "image token appeared before close at length=$length")
        }
        assertEquals(
            listOf(MdToken.Image("alt", "https://example.com/image.png")),
            parseStreamingMarkdown(full),
        )
    }

    @Test
    fun heading_levelOneTwoSix() {
        assertEquals(
            listOf(MdToken.Heading(1, listOf(MdToken.Plain("Title")))),
            parseStreamingMarkdown("# Title"),
        )
        assertEquals(
            listOf(MdToken.Heading(2, listOf(MdToken.Plain("Sub")))),
            parseStreamingMarkdown("## Sub"),
        )
        assertEquals(
            listOf(MdToken.Heading(6, listOf(MdToken.Plain("Tiny")))),
            parseStreamingMarkdown("###### Tiny"),
        )
    }

    @Test
    fun heading_inlineFormattingParsed() {
        // Bold, italic, inline code, and links all parse inside a heading — the heading's text
        // content is run through parseStreamingMarkdown just like a list item or block quote.
        val tokens = parseStreamingMarkdown("# Hello **world** and `code`")
        val heading = tokens.single() as MdToken.Heading
        assertEquals(1, heading.level)
        assertEquals(
            listOf(
                MdToken.Plain("Hello "),
                MdToken.Bold("world"),
                MdToken.Plain(" and "),
                MdToken.InlineCode("code"),
            ),
            heading.inline,
        )
    }

    @Test
    fun heading_inlineMathParsed() {
        // Inline math delimiters work inside headings.
        val tokens = parseStreamingMarkdown("# Formula \$E=mc^2\$ here")
        val heading = tokens.single() as MdToken.Heading
        assertEquals(
            listOf(
                MdToken.Plain("Formula "),
                MdToken.InlineMath("E=mc^2", closed = true),
                MdToken.Plain(" here"),
            ),
            heading.inline,
        )
    }

    @Test
    fun heading_prefixStability_growingBold() {
        // As the heading streams in char-by-char, the prefix of inline tokens stays stable — the
        // trailing `**` re-classifies from Plain to Bold once the close arrives.
        val full = "# Hello **world**"
        // While `**` is unclosed, the heading's inline is a single Plain carrying the trailing
        // `**` as literal text. Pick a prefix where the close hasn't arrived yet.
        val beforeClose = parseStreamingMarkdown(full.substring(0, "# Hello **world".length))
        val headingBefore = beforeClose.single() as MdToken.Heading
        assertEquals(
            listOf(MdToken.Plain("Hello **world")),
            headingBefore.inline,
            "before close: inline should be a single Plain carrying the unclosed **",
        )
        // Once the closing `**` arrives, the Plain prefix stays stable ("Hello ") and the trailing
        // text re-classifies to Bold("world").
        val final = (parseStreamingMarkdown(full).single() as MdToken.Heading).inline
        assertEquals(
            listOf(MdToken.Plain("Hello "), MdToken.Bold("world")),
            final,
            "after close: Plain prefix must stay stable, trailing text re-classifies to Bold",
        )
    }

    @Test
    fun heading_requiresSpaceAfterHashes() {
        // `#NoSpace` should NOT be parsed as a heading — emit as plain.
        val tokens = parseStreamingMarkdown("#nope")
        assertTrue(tokens.first() is MdToken.Plain)
    }

    @Test
    fun fencedCodeBlock_closed() {
        val tokens = parseStreamingMarkdown("```kotlin\nval x = 1\n```")
        val cb = tokens.first() as MdToken.CodeBlock
        assertEquals("kotlin", cb.language)
        assertEquals("val x = 1\n", cb.content)
        assertEquals(true, cb.closed)
    }

    @Test
    fun fencedCodeBlock_open() {
        val tokens = parseStreamingMarkdown("```kotlin\nval x = 1")
        val cb = tokens.first() as MdToken.CodeBlock
        assertEquals("kotlin", cb.language)
        assertEquals("val x = 1", cb.content)
        assertEquals(false, cb.closed)
    }

    @Test
    fun newline_emitsNewlineToken() {
        val tokens = parseStreamingMarkdown("a\nb")
        assertEquals(
            listOf(MdToken.Plain("a"), MdToken.Newline, MdToken.Plain("b")),
            tokens,
        )
    }

    @Test
    fun prefixStability_growingBoldDoesntScrambleEarlierTokens() {
        // Prefix stability: while the `**` is unclosed the parser emits the trailing delimiters as
        // part of a single Plain token, but the literal "Hello, " is always the very start of that
        // token. Once the closing `**` arrives, the parser breaks the bold out cleanly and the
        // first token becomes `Plain("Hello, ")` again.
        val full = "Hello, **world**!"
        listOf(8, 9, 10, 12, 15).forEach { len ->
            val toks = parseStreamingMarkdown(full.substring(0, len))
            val first = toks.first()
            assertTrue(first is MdToken.Plain, "len=$len: expected leading Plain, got $first")
            assertTrue(
                first.text.startsWith("Hello, "),
                "len=$len: expected leading 'Hello, ', got '${first.text}'",
            )
        }
        // Final closed state has the clean token sequence.
        assertEquals(
            listOf(MdToken.Plain("Hello, "), MdToken.Bold("world"), MdToken.Plain("!")),
            parseStreamingMarkdown(full),
        )
    }

    @Test
    fun prefixStability_growingCodeBlock_keepsFenceTokenStable() {
        val full = "```kt\nval x = 1\n```\nafter"
        // After the opening fence + newline, the parser emits an unclosed code block. Every
        // subsequent prefix should also have a single CodeBlock token at index 0.
        for (len in 8..full.length) {
            val toks = parseStreamingMarkdown(full.substring(0, len))
            val first = toks.first()
            assertTrue(first is MdToken.CodeBlock, "len=$len, first=$first")
        }
    }

    @Test
    fun unclosedItalic_emitsPlain() {
        val tokens = parseStreamingMarkdown("hello *world")
        // The unclosed single-star should render as plain so users don't see weird whitespace.
        assertTrue(tokens.any { it is MdToken.Plain && (it.text.contains("*world")) })
    }

    @Test
    fun adjacentPlainRunsAreMerged() {
        // Multiple plain emits should collapse into a single Plain token.
        val tokens = parseStreamingMarkdown("abc")
        assertEquals(1, tokens.size)
        assertEquals(MdToken.Plain("abc"), tokens.first())
    }

    @Test
    fun codeBlockWithoutLanguage() {
        val tokens = parseStreamingMarkdown("```\nplain\n```")
        val cb = tokens.first() as MdToken.CodeBlock
        assertEquals(null, cb.language)
        assertEquals("plain\n", cb.content)
    }

    // --- Math delimiters ---

    @Test
    fun inlineMath_closed() {
        val tokens = parseStreamingMarkdown("a \$x^2\$ b")
        assertEquals(
            listOf(
                MdToken.Plain("a "),
                MdToken.InlineMath("x^2", closed = true),
                MdToken.Plain(" b"),
            ),
            tokens,
        )
    }

    @Test
    fun inlineMath_texDelimiters_closed() {
        val tokens = parseStreamingMarkdown("a \\(x^2 + y^2\\) b")
        assertEquals(
            listOf(
                MdToken.Plain("a "),
                MdToken.InlineMath("x^2 + y^2", closed = true),
                MdToken.Plain(" b"),
            ),
            tokens,
        )
    }

    @Test
    fun inlineMath_texDelimiters_multipleSpans() {
        assertEquals(
            listOf(
                MdToken.InlineMath("a", closed = true),
                MdToken.Plain(" and "),
                MdToken.InlineMath("b", closed = true),
            ),
            parseStreamingMarkdown("\\(a\\) and \\(b\\)"),
        )
    }

    @Test
    fun prefixStability_growingTexInlineMath() {
        val full = "\\(x^2\\)"
        (1 until full.length).forEach { len ->
            assertTrue(
                parseStreamingMarkdown(full.substring(0, len)).none { it is MdToken.InlineMath },
                "len=$len",
            )
        }
        assertEquals(
            listOf(MdToken.InlineMath("x^2", closed = true)),
            parseStreamingMarkdown(full),
        )
    }

    @Test
    fun unclosedTexInlineMath_emitsPlain() {
        val tokens = parseStreamingMarkdown("a \\(x^2")
        assertTrue(tokens.none { it is MdToken.InlineMath })
        assertTrue(tokens.any { it is MdToken.Plain && it.text.contains("\\(") })
    }

    @Test
    fun displayMath_closed() {
        val tokens = parseStreamingMarkdown("\$\$\\sum_{i=1}^n i^2\$\$")
        assertEquals(
            listOf(MdToken.DisplayMath("\\sum_{i=1}^n i^2", closed = true)),
            tokens,
        )
    }

    @Test
    fun displayMath_priorityOverInline() {
        // `$$x$$` should be display math, not inline math `$` + `$x$` + `$`.
        val tokens = parseStreamingMarkdown("\$\$x\$\$")
        assertEquals(listOf(MdToken.DisplayMath("x", closed = true)), tokens)
    }

    @Test
    fun inlineMath_rejectsLeadingSpace() {
        // `$ word $` — the opening `$` followed by whitespace shouldn't start a math span.
        val tokens = parseStreamingMarkdown("\$ word \$")
        // The `$` should be plain text. The closing `$` would be rejected too because of the
        // preceding whitespace.
        assertTrue(tokens.all { it is MdToken.Plain })
    }

    @Test
    fun inlineMath_rejectsTrailingSpaceBeforeClose() {
        // `$word $` — closing `$` preceded by whitespace is rejected, so no math span.
        val tokens = parseStreamingMarkdown("\$word \$")
        assertTrue(tokens.none { it is MdToken.InlineMath })
    }

    @Test
    fun currency_notTreatedAsMath() {
        // `$5` is currency, not a math opener — but our rule is "no space after $", and `5` is
        // not whitespace, so this DOES open a math span. The close is the issue: there's no second
        // `$`. Without a close, the parser emits the `$` as plain text.
        val tokens = parseStreamingMarkdown("The price is \$5.")
        assertTrue(tokens.none { it is MdToken.InlineMath })
    }

    @Test
    fun unclosedInlineMath_emitsPlain() {
        val tokens = parseStreamingMarkdown("a \$x")
        // The unclosed `$` should degrade to plain text.
        assertTrue(tokens.none { it is MdToken.InlineMath })
        assertTrue(tokens.any { it is MdToken.Plain && it.text.contains("\$") })
    }

    @Test
    fun unclosedDisplayMath_emitsPlain() {
        val tokens = parseStreamingMarkdown("\$\$\\frac{a}{b}")
        // The unclosed `$$` should degrade to plain text (mergeAdjacentPlain may fold it into a
        // following plain run, so we check containment rather than equality).
        assertTrue(tokens.none { it is MdToken.DisplayMath })
        assertTrue(tokens.any { it is MdToken.Plain && it.text.contains("\$\$") })
    }

    @Test
    fun displayMath_texDelimiters_closed() {
        val tokens = parseStreamingMarkdown("\\[\\sum_{i=1}^n i^2\\]")
        assertEquals(
            listOf(MdToken.DisplayMath("\\sum_{i=1}^n i^2", closed = true)),
            tokens,
        )
    }

    @Test
    fun displayMath_texDelimiters_followedByText() {
        val tokens = parseStreamingMarkdown("\\[x = 1\\]\nThen x is one.")
        assertEquals(
            listOf(
                MdToken.DisplayMath("x = 1", closed = true),
                MdToken.Newline,
                MdToken.Plain("Then x is one."),
            ),
            tokens,
        )
    }

    @Test
    fun displayMath_texDelimiters_multipleSpans() {
        val tokens = parseStreamingMarkdown("\\[a\\] and \\[b\\]")
        assertEquals(
            listOf(
                MdToken.DisplayMath("a", closed = true),
                MdToken.Plain(" and "),
                MdToken.DisplayMath("b", closed = true),
            ),
            tokens,
        )
    }

    @Test
    fun prefixStability_growingTexDisplayMath() {
        val full = "\\[x^2\\]"
        // For every prefix shorter than the full string, no DisplayMath token should be emitted;
        // once the closing `\]` arrives, the equation snaps into a DisplayMath token.
        for (len in 0 until full.length) {
            val toks = parseStreamingMarkdown(full.substring(0, len))
            assertTrue(toks.none { it is MdToken.DisplayMath }, "len=\$len")
        }
        assertEquals(listOf(MdToken.DisplayMath("x^2", closed = true)), parseStreamingMarkdown(full))
    }

    @Test
    fun unclosedTexDisplayMath_emitsPlain() {
        val tokens = parseStreamingMarkdown("a \\[x^2")
        assertTrue(tokens.none { it is MdToken.DisplayMath })
        // The unclosed `\[` should degrade to plain text (mergeAdjacentPlain may fold it into a
        // following plain run, so we check containment rather than equality).
        assertTrue(tokens.any { it is MdToken.Plain && it.text.contains("\\[") })
    }

    @Test
    fun mathSurroundedByText_parsesCleanly() {
        val tokens = parseStreamingMarkdown("The formula \$a^2 + b^2 = c^2\$ is Pythagoras.")
        assertEquals(
            listOf(
                MdToken.Plain("The formula "),
                MdToken.InlineMath("a^2 + b^2 = c^2", closed = true),
                MdToken.Plain(" is Pythagoras."),
            ),
            tokens,
        )
    }

    @Test
    fun displayMath_followedByText() {
        val tokens = parseStreamingMarkdown("\$\$x = 1\$\$\nThen x is one.")
        assertEquals(
            listOf(
                MdToken.DisplayMath("x = 1", closed = true),
                MdToken.Newline,
                MdToken.Plain("Then x is one."),
            ),
            tokens,
        )
    }

    @Test
    fun multipleInlineMath_inOneLine() {
        val tokens = parseStreamingMarkdown("\$a\$ and \$b\$")
        assertEquals(
            listOf(
                MdToken.InlineMath("a", closed = true),
                MdToken.Plain(" and "),
                MdToken.InlineMath("b", closed = true),
            ),
            tokens,
        )
    }

    @Test
    fun prefixStability_growingInlineMath() {
        val full = "\$x^2\$"
        // While the close hasn't arrived, the `$` is plain text. Once the close lands, the whole
        // thing becomes an InlineMath token. The tokens before the `$` (none in this case) stay
        // stable.
        listOf(1, 2, 3, 4).forEach { len ->
            val toks = parseStreamingMarkdown(full.substring(0, len))
            // No exception, no math token until the close.
            if (len < full.length) {
                assertTrue(toks.none { it is MdToken.InlineMath }, "len=\$len")
            }
        }
        // Closed form.
        assertEquals(listOf(MdToken.InlineMath("x^2", closed = true)), parseStreamingMarkdown(full))
    }

    // --- Block quotes ---

    @Test
    fun blockQuote_simple() {
        val tokens = parseStreamingMarkdown("> quoted text")
        assertEquals(
            listOf(MdToken.BlockQuote(level = 1, inline = listOf(MdToken.Plain("quoted text")))),
            tokens,
        )
    }

    @Test
    fun blockQuote_nestedLevel() {
        val tokens = parseStreamingMarkdown(">> nested")
        assertEquals(
            listOf(MdToken.BlockQuote(level = 2, inline = listOf(MdToken.Plain("nested")))),
            tokens,
        )
    }

    @Test
    fun blockQuote_inlineFormattingParsed() {
        val tokens = parseStreamingMarkdown("> **bold** and `code`")
        val quote = tokens.single() as MdToken.BlockQuote
        assertEquals(
            listOf(MdToken.Bold("bold"), MdToken.Plain(" and "), MdToken.InlineCode("code")),
            quote.inline,
        )
    }

    @Test
    fun blockQuote_followedByParagraph() {
        val tokens = parseStreamingMarkdown("> quoted\n\nAfter quote.")
        assertEquals(
            listOf(
                MdToken.BlockQuote(level = 1, inline = listOf(MdToken.Plain("quoted"))),
                MdToken.Newline,
                MdToken.Plain("After quote."),
            ),
            tokens,
        )
    }

    @Test
    fun blockQuote_midLineMarker_isPlain() {
        val tokens = parseStreamingMarkdown("text > more")
        assertEquals(listOf(MdToken.Plain("text > more")), tokens)
    }

    @Test
    fun prefixStability_growingBlockQuote() {
        val full = "> hello **world**"
        listOf(3, 7, 10, 16).forEach { len ->
            val toks = parseStreamingMarkdown(full.substring(0, len))
            assertTrue(toks.first() is MdToken.BlockQuote, "len=$len: expected leading BlockQuote, got ${toks.firstOrNull()}")
            val quote = toks.first() as MdToken.BlockQuote
            val firstInline = quote.inline.firstOrNull()
            assertTrue(firstInline is MdToken.Plain, "len=$len: expected leading Plain inline, got $firstInline")
        }
        val finalQuote = parseStreamingMarkdown(full).single() as MdToken.BlockQuote
        assertEquals(
            listOf(MdToken.Plain("hello "), MdToken.Bold("world")),
            finalQuote.inline,
        )
    }

    // --- Lists ---

    @Test
    fun unorderedList_dash() {
        val tokens = parseStreamingMarkdown("- a\n- b\n- c")
        assertEquals(
            listOf(
                MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("a"))),
                MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("b"))),
                MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("c"))),
            ),
            tokens,
        )
    }

    @Test
    fun unorderedList_star() {
        val tokens = parseStreamingMarkdown("* a\n* b")
        assertEquals(2, tokens.size)
        assertTrue(tokens.all { it is MdToken.ListItem && !it.ordered })
    }

    @Test
    fun unorderedList_plus() {
        val tokens = parseStreamingMarkdown("+ a\n+ b")
        assertEquals(2, tokens.size)
        assertTrue(tokens.all { it is MdToken.ListItem && !it.ordered })
    }

    @Test
    fun orderedList_dot() {
        val tokens = parseStreamingMarkdown("1. first\n2. second\n3. third")
        assertEquals(
            listOf(
                MdToken.ListItem(ordered = true, number = 1, indent = 0, inline = listOf(MdToken.Plain("first"))),
                MdToken.ListItem(ordered = true, number = 2, indent = 0, inline = listOf(MdToken.Plain("second"))),
                MdToken.ListItem(ordered = true, number = 3, indent = 0, inline = listOf(MdToken.Plain("third"))),
            ),
            tokens,
        )
    }

    @Test
    fun orderedList_paren() {
        // `1)` form is also a valid ordered-list marker (CommonMark).
        val tokens = parseStreamingMarkdown("1) one\n2) two")
        assertEquals(2, tokens.size)
        assertTrue(tokens.all { it is MdToken.ListItem && it.ordered })
        assertEquals(1, (tokens[0] as MdToken.ListItem).number)
        assertEquals(2, (tokens[1] as MdToken.ListItem).number)
    }

    @Test
    fun orderedList_nonSequentialNumbers_preserved() {
        // CommonMark lets the starting number be anything; the renderer preserves it. (Subsequent
        // items re-number from the start, but the parser just records each marker's literal
        // number.)
        val tokens = parseStreamingMarkdown("3. third\n4. fourth")
        assertEquals(3, (tokens[0] as MdToken.ListItem).number)
        assertEquals(4, (tokens[1] as MdToken.ListItem).number)
    }

    @Test
    fun listItem_inlineFormattingParsed() {
        // Bold + inline code inside a list item — the item's inline content is recursively parsed.
        val tokens = parseStreamingMarkdown("- **bold** and `code`")
        val item = tokens.single() as MdToken.ListItem
        assertEquals(
            listOf(MdToken.Bold("bold"), MdToken.Plain(" and "), MdToken.InlineCode("code")),
            item.inline,
        )
    }

    @Test
    fun listItem_inlineMathParsed() {
        val tokens = parseStreamingMarkdown("- formula: \$x^2\$")
        val item = tokens.single() as MdToken.ListItem
        assertEquals(
            listOf(MdToken.Plain("formula: "), MdToken.InlineMath("x^2", closed = true)),
            item.inline,
        )
    }

    @Test
    fun listItem_indented_nestingLevelOne() {
        // Two-space indent → indent level 1.
        val tokens = parseStreamingMarkdown("- parent\n  - child")
        val parent = tokens[0] as MdToken.ListItem
        val child = tokens[1] as MdToken.ListItem
        assertEquals(0, parent.indent)
        assertEquals(1, child.indent)
    }

    @Test
    fun listItem_indented_nestingLevelTwo() {
        // Four-space indent → indent level 2.
        val tokens = parseStreamingMarkdown("- a\n  - b\n    - c")
        assertEquals(0, (tokens[0] as MdToken.ListItem).indent)
        assertEquals(1, (tokens[1] as MdToken.ListItem).indent)
        assertEquals(2, (tokens[2] as MdToken.ListItem).indent)
    }

    @Test
    fun listItem_singleSpaceIndent_treatedAsLevelZero() {
        // A single leading space rounds down to indent 0 (one-space indents are non-standard but
        // should not be misread as a deeper nesting level).
        val tokens = parseStreamingMarkdown("- a\n - b")
        assertEquals(0, (tokens[1] as MdToken.ListItem).indent)
    }

    @Test
    fun listItem_emptyContent() {
        // `- ` (dash + space, end of line) — empty item.
        val tokens = parseStreamingMarkdown("- \n- b")
        val first = tokens[0] as MdToken.ListItem
        assertEquals(emptyList<MdToken>(), first.inline)
    }

    @Test
    fun listItem_markerAtEndOfInput_noSpace() {
        // `-` alone at end of input — empty item (matches CommonMark "marker followed by EOL").
        val tokens = parseStreamingMarkdown("-")
        val item = tokens.single() as MdToken.ListItem
        assertEquals(emptyList<MdToken>(), item.inline)
    }

    @Test
    fun listItem_orderedEmpty_atEndOfInput() {
        // `1.` at end of input — empty ordered item.
        val tokens = parseStreamingMarkdown("1.")
        val item = tokens.single() as MdToken.ListItem
        assertTrue(item.ordered)
        assertEquals(1, item.number)
        assertEquals(emptyList<MdToken>(), item.inline)
    }

    @Test
    fun listItem_markerWithoutSpace_notAList() {
        // `1.text` — no space after `.` → not a list marker; falls through to plain text.
        // Important so dates and version numbers (`2024.01.01`) don't false-positive.
        val tokens = parseStreamingMarkdown("1.text")
        assertTrue(tokens.none { it is MdToken.ListItem })
        assertTrue(tokens.any { it is MdToken.Plain })
    }

    @Test
    fun listItem_dashWithoutSpace_notAList() {
        // `-text` — no space after `-` → not a list marker.
        val tokens = parseStreamingMarkdown("-text")
        assertTrue(tokens.none { it is MdToken.ListItem })
    }

    @Test
    fun listItem_threeDashes_notAList() {
        assertEquals(listOf(MdToken.HorizontalRule), parseStreamingMarkdown("---"))
    }

    @Test
    fun horizontalRule_lineBoundariesAndWhitespace() {
        assertEquals(
            listOf(MdToken.HorizontalRule, MdToken.Newline, MdToken.Plain("after")),
            parseStreamingMarkdown("---\nafter"),
        )
        assertEquals(listOf(MdToken.HorizontalRule), parseStreamingMarkdown("  ---  "))
        assertTrue(parseStreamingMarkdown("    ---").none { it is MdToken.HorizontalRule })
        assertTrue(parseStreamingMarkdown("----").none { it is MdToken.HorizontalRule })
        assertEquals(
            listOf(MdToken.Plain("before --- after")),
            parseStreamingMarkdown("before --- after"),
        )
    }

    @Test
    fun listItem_dateNotOrdered() {
        // `2024.01.01` — looks like `\d+.` but no space after, so not an ordered list marker.
        val tokens = parseStreamingMarkdown("2024.01.01")
        assertTrue(tokens.none { it is MdToken.ListItem })
        assertTrue(tokens.any { it is MdToken.Plain && it.text == "2024.01.01" })
    }

    @Test
    fun listItem_midLineDash_isPlain() {
        // A `-` mid-line is plain text, not a list marker (list markers only fire at line start).
        val tokens = parseStreamingMarkdown("text - more")
        assertEquals(listOf(MdToken.Plain("text - more")), tokens)
    }

    @Test
    fun listItem_followedByParagraph() {
        // List followed by a paragraph (blank-line separated) — the paragraph shouldn't be glued
        // onto the last item. Note: the parser consumes the list item's trailing newline (so
        // consecutive items chain without an intervening Newline), so a blank line between the
        // item and the paragraph yields ONE Newline token, not two.
        val tokens = parseStreamingMarkdown("- item\n\nAfter list.")
        assertEquals(
            listOf(
                MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("item"))),
                MdToken.Newline,
                MdToken.Plain("After list."),
            ),
            tokens,
        )
    }

    @Test
    fun listItem_interruptsParagraph() {
        // A list can interrupt a paragraph (no blank line needed) — CommonMark behavior.
        val tokens = parseStreamingMarkdown("Some text\n- item")
        assertEquals(
            listOf(
                MdToken.Plain("Some text"),
                MdToken.Newline,
                MdToken.ListItem(ordered = false, number = 0, indent = 0, inline = listOf(MdToken.Plain("item"))),
            ),
            tokens,
        )
    }

    @Test
    fun prefixStability_growingListItem() {
        // As the item's line grows, the ListItem token's inline content grows — but the token
        // list structure (one ListItem) stays stable, and the inline prefix is itself
        // prefix-stable (the leading Plain stays Plain).
        val full = "- hello **world**"
        listOf(7, 10, 13, 17).forEach { len ->
            val toks = parseStreamingMarkdown(full.substring(0, len))
            assertTrue(toks.first() is MdToken.ListItem, "len=$len: expected leading ListItem, got ${toks.firstOrNull()}")
            val item = toks.first() as MdToken.ListItem
            // The inline content's first token should be Plain starting with "hello" (or shorter
            // prefix as it grows). Never re-classifies earlier inline tokens.
            val firstInline = item.inline.firstOrNull()
            assertTrue(firstInline is MdToken.Plain, "len=$len: expected leading Plain inline, got $firstInline")
        }
        // Closed form has the bold broken out cleanly.
        val finalItem = parseStreamingMarkdown(full).single() as MdToken.ListItem
        assertEquals(
            listOf(MdToken.Plain("hello "), MdToken.Bold("world")),
            finalItem.inline,
        )
    }

    @Test
    fun prefixStability_growingOrderedNumber() {
        // Streaming `1. item` char-by-char: while we're still in the digits, the parser shouldn't
        // commit to a ListItem until the `.` and space arrive. But once they do, the token list
        // stays as a single ListItem with growing inline content.
        val full = "1. hello"
        // At len=1 ("1") it's just a digit, no list yet.
        assertEquals(1, parseStreamingMarkdown("1").size)
        // At len=2 ("1.") it's an empty ordered list item.
        val atDot = parseStreamingMarkdown("1.")
        assertTrue(atDot.single() is MdToken.ListItem)
        // At full input, it's a populated list item.
        val atFull = parseStreamingMarkdown(full).single() as MdToken.ListItem
        assertEquals(listOf(MdToken.Plain("hello")), atFull.inline)
    }

    @Test
    fun listItem_firesBeforeInlineStarParsing() {
        // `* item` should be a list item, not the start of italic emphasis. Confirms the list
        // detection wins over the inline `*` branch when at line start.
        val tokens = parseStreamingMarkdown("* item")
        assertTrue(tokens.single() is MdToken.ListItem)
        // And `*item*` (no space) is still italic — list detection correctly rejects it.
        val italic = parseStreamingMarkdown("*item*")
        assertTrue(italic.any { it is MdToken.Italic })
        assertTrue(italic.none { it is MdToken.ListItem })
    }

    // --- Task lists ---

    @Test
    fun taskList_unchecked() {
        val tokens = parseStreamingMarkdown("- [ ] todo")
        val item = tokens.single() as MdToken.ListItem
        assertEquals(false, item.checked)
        assertEquals(listOf(MdToken.Plain("todo")), item.inline)
    }

    @Test
    fun taskList_checked_lowercaseX() {
        val tokens = parseStreamingMarkdown("- [x] done")
        val item = tokens.single() as MdToken.ListItem
        assertEquals(true, item.checked)
        assertEquals(listOf(MdToken.Plain("done")), item.inline)
    }

    @Test
    fun taskList_checked_uppercaseX() {
        val tokens = parseStreamingMarkdown("- [X] done")
        val item = tokens.single() as MdToken.ListItem
        assertEquals(true, item.checked)
        assertEquals(listOf(MdToken.Plain("done")), item.inline)
    }

    @Test
    fun taskList_emptyContent() {
        // `[ ]` at end of line (no trailing content) is a valid task marker.
        val tokens = parseStreamingMarkdown("- [ ]")
        val item = tokens.single() as MdToken.ListItem
        assertEquals(false, item.checked)
        assertEquals(emptyList<MdToken>(), item.inline)
    }

    @Test
    fun taskList_checkedEmptyContent() {
        val tokens = parseStreamingMarkdown("- [x]")
        val item = tokens.single() as MdToken.ListItem
        assertEquals(true, item.checked)
        assertEquals(emptyList<MdToken>(), item.inline)
    }

    @Test
    fun taskList_starAndPlusMarkers() {
        // Task markers work with `*` and `+` markers too, not just `-`.
        val star = parseStreamingMarkdown("* [ ] a").single() as MdToken.ListItem
        assertEquals(false, star.checked)
        val plus = parseStreamingMarkdown("+ [x] b").single() as MdToken.ListItem
        assertEquals(true, plus.checked)
    }

    @Test
    fun taskList_inlineFormattingParsed() {
        // Task items support inline formatting just like plain items.
        val tokens = parseStreamingMarkdown("- [x] **bold** and `code`")
        val item = tokens.single() as MdToken.ListItem
        assertEquals(true, item.checked)
        assertEquals(
            listOf(MdToken.Bold("bold"), MdToken.Plain(" and "), MdToken.InlineCode("code")),
            item.inline,
        )
    }

    @Test
    fun taskList_mixedWithPlainItems() {
        // A list can mix plain and task items; only the task items get a non-null checked.
        val tokens = parseStreamingMarkdown("- plain\n- [ ] todo\n- [x] done")
        val plain = tokens[0] as MdToken.ListItem
        val todo = tokens[1] as MdToken.ListItem
        val done = tokens[2] as MdToken.ListItem
        assertEquals(null, plain.checked)
        assertEquals(false, todo.checked)
        assertEquals(true, done.checked)
    }

    @Test
    fun taskList_noSpaceAfterCloseBracket_notATask() {
        // `- [x]todo` (no space after `]`) is NOT a task marker per GFM — falls back to a plain
        // item with `[x]todo` as inline content.
        val tokens = parseStreamingMarkdown("- [x]todo")
        val item = tokens.single() as MdToken.ListItem
        assertEquals(null, item.checked)
        assertEquals(listOf(MdToken.Plain("[x]todo")), item.inline)
    }

    @Test
    fun taskList_invalidMarkerContent_notATask() {
        // `- [y]` — `y` is not ` ` / `x` / `X`, so not a task marker.
        val tokens = parseStreamingMarkdown("- [y] maybe")
        val item = tokens.single() as MdToken.ListItem
        assertEquals(null, item.checked)
        assertEquals(listOf(MdToken.Plain("[y] maybe")), item.inline)
    }

    @Test
    fun taskList_indented_nestingPreserved() {
        // Indentation still drives nesting for task items.
        val tokens = parseStreamingMarkdown("- [x] parent\n  - [ ] child")
        val parent = tokens[0] as MdToken.ListItem
        val child = tokens[1] as MdToken.ListItem
        assertEquals(true, parent.checked)
        assertEquals(0, parent.indent)
        assertEquals(false, child.checked)
        assertEquals(1, child.indent)
    }

    @Test
    fun prefixStability_growingTaskMarker() {
        // Streaming `- [ ] todo` char-by-char. Before the closing `]` arrives, the line parses
        // as a plain list item with the partial `[` / `[ ` / `[ ]` text in inline. Once the `]`
        // arrives, the trailing token re-classifies to a task item (checked flips from null to
        // false, inline shrinks). After that, inline grows stably with the remaining text.
        val full = "- [ ] todo"
        // `- ` — empty plain item.
        val atDash = parseStreamingMarkdown("- ")
        assertTrue(atDash.single() is MdToken.ListItem)
        assertEquals(null, (atDash.single() as MdToken.ListItem).checked)
        // `- [` — partial marker, plain item with `[` inline.
        val atOpen = parseStreamingMarkdown("- [")
        val openItem = atOpen.single() as MdToken.ListItem
        assertEquals(null, openItem.checked)
        assertEquals(listOf(MdToken.Plain("[")), openItem.inline)
        // `- [ ` (trailing space trimmed) — still `[`, not yet closed.
        val atSpace = parseStreamingMarkdown("- [ ")
        val spaceItem = atSpace.single() as MdToken.ListItem
        assertEquals(null, spaceItem.checked)
        assertEquals(listOf(MdToken.Plain("[")), spaceItem.inline)
        // `- [ ]` — closed task marker, checked=false, empty inline.
        val atClose = parseStreamingMarkdown("- [ ]")
        val closedItem = atClose.single() as MdToken.ListItem
        assertEquals(false, closedItem.checked)
        assertEquals(emptyList<MdToken>(), closedItem.inline)
        // `- [ ] todo` — inline grows with the remaining text, checked stays false.
        val atFull = parseStreamingMarkdown(full).single() as MdToken.ListItem
        assertEquals(false, atFull.checked)
        assertEquals(listOf(MdToken.Plain("todo")), atFull.inline)
    }

    @Test
    fun prefixStability_growingCheckedTaskMarker() {
        // Streaming `- [x] done` char-by-char. The `x` arrives before `]`, so `- [x` is a plain
        // item with `[x` inline. Only once `]` lands does checked flip to true.
        val atX = parseStreamingMarkdown("- [x")
        val xItem = atX.single() as MdToken.ListItem
        assertEquals(null, xItem.checked)
        assertEquals(listOf(MdToken.Plain("[x")), xItem.inline)

        val atClose = parseStreamingMarkdown("- [x]")
        val closedItem = atClose.single() as MdToken.ListItem
        assertEquals(true, closedItem.checked)
        assertEquals(emptyList<MdToken>(), closedItem.inline)

        val atFull = parseStreamingMarkdown("- [x] done").single() as MdToken.ListItem
        assertEquals(true, atFull.checked)
        assertEquals(listOf(MdToken.Plain("done")), atFull.inline)
    }

    // --- Tables ---

    @Test
    fun table_basic() {
        val tokens = parseStreamingMarkdown("| Name | Age |\n| --- | --- |\n| Alice | 30 |\n| Bob | 25 |")
        val table = tokens.single() as MdToken.Table
        assertEquals(listOf("Name", "Age"), table.headers)
        assertEquals(listOf(TableAlign.DEFAULT, TableAlign.DEFAULT), table.aligns)
        assertEquals(
            listOf(listOf("Alice", "30"), listOf("Bob", "25")),
            table.rows,
        )
    }

    @Test
    fun table_alignments() {
        val tokens = parseStreamingMarkdown(
            "| L | C | R | D |\n| :--- | :---: | ---: | --- |\n| a | b | c | d |",
        )
        val table = tokens.single() as MdToken.Table
        assertEquals(
            listOf(TableAlign.LEFT, TableAlign.CENTER, TableAlign.RIGHT, TableAlign.DEFAULT),
            table.aligns,
        )
    }

    @Test
    fun table_noDataRows_openAtEndOfInput() {
        // Header + separator, no data rows — table at end of input → closed = false (rows might
        // still stream in).
        val tokens = parseStreamingMarkdown("| a | b |\n| --- | --- |")
        val table = tokens.single() as MdToken.Table
        assertEquals(listOf("a", "b"), table.headers)
        assertEquals(emptyList<List<String>>(), table.rows)
        assertEquals(false, table.closed)
    }

    @Test
    fun table_closed_whenFollowedByText() {
        val tokens = parseStreamingMarkdown("| a | b |\n| --- | --- |\n| 1 | 2 |\nAfter table.")
        val table = tokens.filterIsInstance<MdToken.Table>().single()
        assertEquals(true, table.closed)
        assertEquals(listOf(listOf("1", "2")), table.rows)
        // The text after the table is a separate Plain token.
        assertTrue(tokens.any { it is MdToken.Plain && it.text == "After table." })
    }

    @Test
    fun table_open_atEndOfInput() {
        val tokens = parseStreamingMarkdown("| a | b |\n| --- | --- |\n| 1 | 2 |")
        val table = tokens.single() as MdToken.Table
        assertEquals(false, table.closed)
    }

    @Test
    fun table_headerOnly_noSeparator_isPlain() {
        // Prefix stability: header line alone (no separator) is NOT a table yet.
        val tokens = parseStreamingMarkdown("| a | b |")
        assertTrue(tokens.none { it is MdToken.Table })
        assertTrue(tokens.any { it is MdToken.Plain })
    }

    @Test
    fun table_invalidSeparator_notATable() {
        // Separator cell without a dash → not a valid separator → not a table.
        val tokens = parseStreamingMarkdown("| a | b |\n| : | : |\n| 1 | 2 |")
        assertTrue(tokens.none { it is MdToken.Table })
    }

    @Test
    fun table_separatorWithLetters_notATable() {
        val tokens = parseStreamingMarkdown("| a | b |\n| abc | def |\n| 1 | 2 |")
        assertTrue(tokens.none { it is MdToken.Table })
    }

    @Test
    fun table_precededByText() {
        val tokens = parseStreamingMarkdown("Intro text\n| a | b |\n| --- | --- |\n| 1 | 2 |")
        val table = tokens.filterIsInstance<MdToken.Table>().single()
        assertEquals(listOf("a", "b"), table.headers)
        // The intro text is a leading Plain token.
        assertTrue(tokens.first() is MdToken.Plain)
    }

    @Test
    fun table_followedByText_noNewlineTokenBetween() {
        // The table consumes the trailing newline of its last row, so there's no Newline token
        // between the Table and the following text.
        val tokens = parseStreamingMarkdown("| a |\n| --- |\n| 1 |\nText")
        val tableIdx = tokens.indexOfFirst { it is MdToken.Table }
        assertTrue(tableIdx >= 0)
        // The token right after the Table should be Plain("Text"), not Newline.
        val afterTable = tokens[tableIdx + 1]
        assertTrue(afterTable is MdToken.Plain)
        assertEquals("Text", afterTable.text)
    }

    @Test
    fun table_rowWithFewerCells() {
        // A row with fewer cells than the header — the missing cells are just absent (the renderer
        // pads with empty cells).
        val tokens = parseStreamingMarkdown("| a | b | c |\n| --- | --- | --- |\n| 1 |")
        val table = tokens.single() as MdToken.Table
        assertEquals(listOf(listOf("1")), table.rows)
    }

    @Test
    fun table_emptyCell() {
        val tokens = parseStreamingMarkdown("| a | b |\n| --- | --- |\n| | 2 |")
        val table = tokens.single() as MdToken.Table
        assertEquals(listOf(listOf("", "2")), table.rows)
    }

    @Test
    fun table_cellsStoredAsRawStrings() {
        // Inline formatting in cells is stored as raw text (the renderer parses it at render time).
        val tokens = parseStreamingMarkdown("| a | b |\n| --- | --- |\n| **bold** | `code` |")
        val table = tokens.single() as MdToken.Table
        assertEquals(listOf("**bold**", "`code`"), table.rows.first())
    }

    @Test
    fun table_terminatedByBlankLine() {
        // A blank line ends the table; the separator after the blank line is a new paragraph.
        val tokens = parseStreamingMarkdown(
            "| a | b |\n| --- | --- |\n| 1 | 2 |\n\n| c | d |\n| --- | --- |\n| 3 | 4 |",
        )
        val tables = tokens.filterIsInstance<MdToken.Table>()
        assertEquals(2, tables.size)
        assertEquals(listOf("a", "b"), tables[0].headers)
        assertEquals(listOf("c", "d"), tables[1].headers)
    }

    @Test
    fun table_noLeadingPipe_notATable() {
        // Without a leading `|` at line start, the table detector doesn't fire. `a | b` is plain
        // text (the `|` is not a special char in the inline parser).
        val tokens = parseStreamingMarkdown("a | b\n--- | ---\n1 | 2")
        assertTrue(tokens.none { it is MdToken.Table })
    }

    @Test
    fun prefixStability_growingTable() {
        val full = "| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |"
        // While only the header line is present, no Table token should be emitted.
        val headerOnly = "| a | b |"
        assertTrue(parseStreamingMarkdown(headerOnly).none { it is MdToken.Table })

        // Once the separator arrives, a Table token appears and stays stable as rows grow.
        val withSep = "| a | b |\n| --- | --- |"
        val sepTokens = parseStreamingMarkdown(withSep)
        assertTrue(sepTokens.single() is MdToken.Table)
        assertEquals(0, (sepTokens.single() as MdToken.Table).rows.size)

        // As rows stream in, the Table token grows — earlier rows stay stable.
        val oneRow = parseStreamingMarkdown("| a | b |\n| --- | --- |\n| 1 | 2 |")
        val oneRowTable = oneRow.single() as MdToken.Table
        assertEquals(listOf(listOf("1", "2")), oneRowTable.rows)

        val twoRow = parseStreamingMarkdown(full)
        val twoRowTable = twoRow.single() as MdToken.Table
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), twoRowTable.rows)
        // The first row is unchanged.
        assertEquals(listOf("1", "2"), twoRowTable.rows.first())
    }

    @Test
    fun prefixStability_tableAfterHeadingStable() {
        // A heading before a table must stay stable as the table grows.
        val full = "# Title\n| a | b |\n| --- | --- |\n| 1 | 2 |"
        // At every prefix length, the heading (if present) is always the first token.
        for (len in 1..full.length) {
            val toks = parseStreamingMarkdown(full.substring(0, len))
            val first = toks.firstOrNull()
            // Once the `# ` prefix is present, the first token should be a Heading — never
            // re-classified to something else as the table arrives later.
            if (len >= 7) { // "# Title" is 7 chars
                assertTrue(first is MdToken.Heading, "len=$len: expected Heading, got $first")
            }
        }
        // Final state: heading + (newline) + table. The heading doesn't consume its trailing
        // newline, so a Newline token sits between them — but the table is always present.
        val final = parseStreamingMarkdown(full)
        assertTrue(final[0] is MdToken.Heading)
        assertTrue(final.any { it is MdToken.Table })
    }

    @Test
    fun table_singleDashSeparator() {
        // A single dash per cell is enough for a valid separator.
        val tokens = parseStreamingMarkdown("| a | b |\n| - | - |\n| 1 | 2 |")
        val table = tokens.single() as MdToken.Table
        assertEquals(listOf(TableAlign.DEFAULT, TableAlign.DEFAULT), table.aligns)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun footnote_reference_isInlineToken() {
        assertEquals(
            listOf(MdToken.Plain("Text "), MdToken.FootnoteReference("1"), MdToken.Plain(".")),
            parseStreamingMarkdown("Text [^1]."),
        )
    }

    @Test
    fun footnote_definition_isBlockTokenWithInlineFormatting() {
        assertEquals(
            listOf(
                MdToken.FootnoteDefinition(
                    "1",
                    listOf(MdToken.Bold("source")),
                ),
            ),
            parseStreamingMarkdown("[^1]: **source**"),
        )
    }

    @Test
    fun footnote_definition_consumesItsLine() {
        val tokens = parseStreamingMarkdown("[^a]: first\nText")
        assertEquals(MdToken.FootnoteDefinition("a", listOf(MdToken.Plain("first"))), tokens[0])
        assertEquals(MdToken.Plain("Text"), tokens[1])
    }

    @Test
    fun prefixStability_growingFootnoteDefinition() {
        val full = "[^1]: source **text**"
        for (len in 1..full.length) {
            val tokens = parseStreamingMarkdown(full.substring(0, len))
            if (len >= 6) assertTrue(tokens.first() is MdToken.FootnoteDefinition, "len=$len")
        }
        val definition = parseStreamingMarkdown(full).single() as MdToken.FootnoteDefinition
        assertEquals(listOf(MdToken.Plain("source "), MdToken.Bold("text")), definition.inline)
    }

    // --- Think blocks ---

    @Test
    fun thinkBlock_closed() {
        val tokens = parseStreamingMarkdown("<think>thinking content</think>")
        val thinkBlock = tokens.single() as MdToken.ThinkBlock
        assertEquals("thinking content", thinkBlock.content)
        assertEquals(true, thinkBlock.closed)
    }

    @Test
    fun thinkBlock_open() {
        val tokens = parseStreamingMarkdown("<think>partial content")
        val thinkBlock = tokens.single() as MdToken.ThinkBlock
        assertEquals("partial content", thinkBlock.content)
        assertEquals(false, thinkBlock.closed)
    }

    @Test
    fun thinkBlock_followedByText() {
        val tokens = parseStreamingMarkdown("<think>thinking</think>\nAfter think.")
        assertEquals(3, tokens.size)
        val thinkBlock = tokens[0] as MdToken.ThinkBlock
        assertEquals("thinking", thinkBlock.content)
        assertEquals(true, thinkBlock.closed)
        assertTrue(tokens[1] is MdToken.Newline)
        assertEquals("After think.", (tokens[2] as MdToken.Plain).text)
    }

    @Test
    fun thinkBlock_emptyContent() {
        val tokens = parseStreamingMarkdown("<think></think>")
        val thinkBlock = tokens.single() as MdToken.ThinkBlock
        assertEquals("", thinkBlock.content)
        assertEquals(true, thinkBlock.closed)
    }

    @Test
    fun thinkBlock_containsInlineFormatting() {
        val tokens = parseStreamingMarkdown("<think>**bold** and `code`</think>")
        val thinkBlock = tokens.single() as MdToken.ThinkBlock
        assertEquals("**bold** and `code`", thinkBlock.content)
    }

    @Test
    fun thinkBlock_prefixStability() {
        val full = "<think>thinking</think>"
        for (len in 1..full.length) {
            val tokens = parseStreamingMarkdown(full.substring(0, len))
            if (len >= 7) {
                assertTrue(tokens.first() is MdToken.ThinkBlock, "len=$len")
            }
        }
        val finalBlock = parseStreamingMarkdown(full).single() as MdToken.ThinkBlock
        assertEquals("thinking", finalBlock.content)
        assertEquals(true, finalBlock.closed)
    }

    @Test
    fun thinkBlock_notAtLineStart_isPlain() {
        val tokens = parseStreamingMarkdown("text <think>thinking</think>")
        assertTrue(tokens.none { it is MdToken.ThinkBlock })
        assertTrue(tokens.any { it is MdToken.Plain && it.text.contains("<think>") })
    }

    // --- Whitespace-separated think block merging ---

    @Test
    fun mergeThinkBlocks_newlineSeparated() {
        val tokens = parseStreamingMarkdown("<think>first</think>\n<think>second</think>")
        val merged = mergeWhitespaceSeparatedThinkBlocks(tokens)
        val block = merged.single() as MdToken.ThinkBlock
        assertEquals("first\nsecond", block.content)
        assertEquals(true, block.closed)
    }

    @Test
    fun mergeThinkBlocks_blankLineSeparated() {
        val tokens = parseStreamingMarkdown("<think>first</think>\n\n<think>second")
        val merged = mergeWhitespaceSeparatedThinkBlocks(tokens)
        val block = merged.single() as MdToken.ThinkBlock
        assertEquals("first\nsecond", block.content)
        assertEquals(false, block.closed)
    }

    @Test
    fun mergeThinkBlocks_runsOfThree() {
        val tokens = parseStreamingMarkdown("<think>a</think>\n\n<think>b</think>\n\n<think>c</think>")
        val merged = mergeWhitespaceSeparatedThinkBlocks(tokens)
        val block = merged.single() as MdToken.ThinkBlock
        assertEquals("a\nb\nc", block.content)
        assertEquals(true, block.closed)
    }

    @Test
    fun mergeThinkBlocks_openBlockStaysOpen() {
        val tokens = parseStreamingMarkdown("<think>a</think>\n\n<think>b</think>\n\n<think>c")
        val merged = mergeWhitespaceSeparatedThinkBlocks(tokens)
        val block = merged.single() as MdToken.ThinkBlock
        assertEquals("a\nb\nc", block.content)
        assertEquals(false, block.closed)
    }

    @Test
    fun mergeThinkBlocks_keepsSeparatorsBeforeRealContent() {
        val tokens = parseStreamingMarkdown("<think>a</think>\n\nAnswer text\n\n<think>b</think>")
        val merged = mergeWhitespaceSeparatedThinkBlocks(tokens)
        assertEquals(2, merged.count { it is MdToken.ThinkBlock })
        assertTrue(merged.any { it is MdToken.Plain && it.text.contains("Answer text") })
    }

    @Test
    fun mergeThinkBlocks_singleBlockUntouched() {
        val tokens = parseStreamingMarkdown("<think>only</think>")
        val merged = mergeWhitespaceSeparatedThinkBlocks(tokens)
        assertEquals(tokens, merged)
    }

    @Test
    fun mergeThinkBlocks_noThinkBlocksUntouched() {
        val tokens = parseStreamingMarkdown("Hello **world**\n\nMore text")
        assertEquals(tokens, mergeWhitespaceSeparatedThinkBlocks(tokens))
    }
}
