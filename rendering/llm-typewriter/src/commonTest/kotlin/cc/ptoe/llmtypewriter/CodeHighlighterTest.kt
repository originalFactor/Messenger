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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodeHighlighterTest {

    @Test
    fun languageParse_aliases() {
        assertEquals(CodeLanguage.Kotlin, CodeLanguage.parse("kt"))
        assertEquals(CodeLanguage.Kotlin, CodeLanguage.parse("Kotlin"))
        assertEquals(CodeLanguage.JavaScript, CodeLanguage.parse("javascript"))
        assertEquals(CodeLanguage.TypeScript, CodeLanguage.parse("ts"))
        assertEquals(CodeLanguage.TypeScript, CodeLanguage.parse("typescript"))
        assertEquals(CodeLanguage.Python, CodeLanguage.parse("py"))
        assertEquals(CodeLanguage.Java, CodeLanguage.parse("java"))
        assertEquals(CodeLanguage.C, CodeLanguage.parse("c"))
        assertEquals(CodeLanguage.Cpp, CodeLanguage.parse("c++"))
        assertEquals(CodeLanguage.CSharp, CodeLanguage.parse("c#"))
        assertEquals(CodeLanguage.Go, CodeLanguage.parse("go"))
        assertEquals(CodeLanguage.Rust, CodeLanguage.parse("rs"))
        assertEquals(CodeLanguage.Swift, CodeLanguage.parse("swift"))
        assertEquals(CodeLanguage.Ruby, CodeLanguage.parse("rb"))
        assertEquals(CodeLanguage.Shell, CodeLanguage.parse("bash"))
        assertEquals(CodeLanguage.Php, CodeLanguage.parse("php"))
        assertEquals(CodeLanguage.Plain, CodeLanguage.parse("unknown"))
        assertEquals(CodeLanguage.Plain, CodeLanguage.parse(null))
    }

    @Test
    fun kotlin_keywordsAreHighlighted() {
        val src = "fun greet(name: String): String = \"hi, \" + name"
        val spans = highlightCode(src, CodeLanguage.Kotlin)
        val funSpan = spans.firstOrNull { it.kind == CodeSpanKind.Keyword && src.substring(it.start, it.end) == "fun" }
        assertNotNull(funSpan, "expected `fun` to be a keyword span")
    }

    @Test
    fun kotlin_stringLiteralHighlighted() {
        val src = "val msg = \"hello\""
        val spans = highlightCode(src, CodeLanguage.Kotlin)
        val stringSpan = spans.firstOrNull {
            it.kind == CodeSpanKind.StringLit && src.substring(it.start, it.end) == "\"hello\""
        }
        assertNotNull(stringSpan, "expected double-quoted string to be a StringLit span")
    }

    @Test
    fun kotlin_numberLiteralHighlighted() {
        val src = "val x = 42"
        val spans = highlightCode(src, CodeLanguage.Kotlin)
        val numberSpan = spans.firstOrNull {
            it.kind == CodeSpanKind.Number && src.substring(it.start, it.end) == "42"
        }
        assertNotNull(numberSpan)
    }

    @Test
    fun kotlin_lineCommentHighlighted() {
        val src = "val x = 1 // explanation"
        val spans = highlightCode(src, CodeLanguage.Kotlin)
        val commentSpan = spans.firstOrNull { it.kind == CodeSpanKind.Comment }
        assertNotNull(commentSpan)
        assertTrue(src.substring(commentSpan.start, commentSpan.end).startsWith("//"))
    }

    @Test
    fun python_hashCommentIsHighlighted() {
        // Highlights correctly classifies `#` as a line comment in Python.
        val pyComment = highlightCode("x = 1 # comment", CodeLanguage.Python).any { it.kind == CodeSpanKind.Comment }
        assertTrue(pyComment, "python's # should be a comment")
    }

    @Test
    fun blockComment_spansMultipleLines() {
        val src = "/* hello\nworld */ next"
        val spans = highlightCode(src, CodeLanguage.JavaScript)
        val comment = spans.firstOrNull { it.kind == CodeSpanKind.Comment }
        assertNotNull(comment)
        assertEquals(0, comment.start)
        assertTrue(comment.end >= "/* hello\nworld */".length)
    }

    @Test
    fun stringWithEscapedQuote_startsAtOpeningQuote() {
        // Highlights splits strings at escaped quotes — the first segment starts at the opening
        // `"` and covers the text up to the `\"`. We verify the string span begins correctly;
        // full-escape handling is a Highlights behaviour, not something the wrapper controls.
        val src = "\"a\\\"b\""
        val spans = highlightCode(src, CodeLanguage.JavaScript)
        val s = spans.firstOrNull { it.kind == CodeSpanKind.StringLit }
        assertNotNull(s)
        assertEquals(0, s.start)
        assertTrue(s.end >= 2, "string span should cover at least \"a")
    }

    @Test
    fun plainLanguage_noKeywordsRecognized() {
        val src = "fun greet"
        val spans = highlightCode(src, CodeLanguage.Plain)
        val keyword = spans.firstOrNull { it.kind == CodeSpanKind.Keyword }
        assertEquals(null, keyword, "Plain language has no keywords")
    }

    @Test
    fun emptyInput_isEmpty() {
        assertEquals(emptyList(), highlightCode("", CodeLanguage.Kotlin))
    }

    @Test
    fun spans_coverEveryCharacter_exactlyOnce() {
        val src = "val x = 42 // hi\n\"str\""
        val spans = highlightCode(src, CodeLanguage.Kotlin)
        var pos = 0
        for (sp in spans) {
            assertEquals(pos, sp.start, "gap or overlap at $pos")
            assertTrue(sp.end > sp.start, "empty span at $pos")
            pos = sp.end
        }
        assertEquals(src.length, pos, "spans don't cover the full source")
    }

    @Test
    fun spans_coverEveryCharacter_plainLanguage() {
        val src = "anything goes here {}()#"
        val spans = highlightCode(src, CodeLanguage.Plain)
        var pos = 0
        for (sp in spans) {
            assertEquals(pos, sp.start)
            pos = sp.end
        }
        assertEquals(src.length, pos)
    }

    @Test
    fun javaLanguage_keywordsHighlighted() {
        val src = "public class Main { }"
        val spans = highlightCode(src, CodeLanguage.Java)
        val publicSpan = spans.firstOrNull {
            it.kind == CodeSpanKind.Keyword && src.substring(it.start, it.end) == "public"
        }
        assertNotNull(publicSpan, "expected `public` to be a keyword span in Java")
    }

    @Test
    fun python_tripleQuotedStringHighlighted() {
        val src = "x = \"\"\"hello\"\"\""
        val spans = highlightCode(src, CodeLanguage.Python)
        val stringSpan = spans.firstOrNull { it.kind == CodeSpanKind.StringLit }
        assertNotNull(stringSpan, "expected triple-quoted string to be highlighted")
    }

    @Test
    fun prefixStability_growingCodeKeepsEarlierCharacterKinds() {
        // Growing the input must not re-classify an already-coloured character to a different
        // kind (the live typewriter reveal depends on this). The prefix ends at a word boundary
        // so every token in the prefix is complete.
        val full = "val x = 42 // comment"
        val prefixLen = 10 // "val x = 42"
        val prefixSpans = highlightCode(full.substring(0, prefixLen), CodeLanguage.Kotlin)
        val fullSpans = highlightCode(full, CodeLanguage.Kotlin)
        for (pos in 0 until prefixLen) {
            val prefixKind = prefixSpans.firstOrNull { pos >= it.start && pos < it.end }?.kind
            val fullKind = fullSpans.firstOrNull { pos >= it.start && pos < it.end }?.kind
            assertEquals(
                prefixKind, fullKind,
                "character at $pos ('${full[pos]}') changed kind from $prefixKind to $fullKind"
            )
        }
    }
}
