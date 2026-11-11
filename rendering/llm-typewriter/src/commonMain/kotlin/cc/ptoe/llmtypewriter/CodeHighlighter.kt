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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxLanguage

/**
 * Spans a code-block string into ranges by token kind. Delegates structural analysis to the
 * [Highlights](https://github.com/SnipMeDev/Highlights) Kotlin Multiplatform engine, which covers
 * 17 languages (C, C++, Dart, Java, Kotlin, Rust, C#, CoffeeScript, JavaScript, Perl, Python,
 * Ruby, Shell, Swift, TypeScript, Go, PHP).
 *
 * The wrapper is stream-safe: every character of [source] is covered by exactly one [CodeSpan]
 * (gaps between Highlights' structural regions are filled with [CodeSpanKind.Plain]), so the live
 * renderer never sees an uncoloured character as the fence grows token-by-token.
 */
internal data class CodeSpan(val start: Int, val end: Int, val kind: CodeSpanKind)

internal enum class CodeSpanKind { Keyword, StringLit, Number, Comment, Plain }

/**
 * Language recognised by the highlighter. Each value maps to a [SyntaxLanguage] understood by
 * Highlights. [Plain] maps to [SyntaxLanguage.DEFAULT] (no keyword/string/comment analysis).
 */
internal enum class CodeLanguage(val syntaxLanguage: SyntaxLanguage) {
    Kotlin(SyntaxLanguage.KOTLIN),
    JavaScript(SyntaxLanguage.JAVASCRIPT),
    TypeScript(SyntaxLanguage.TYPESCRIPT),
    Python(SyntaxLanguage.PYTHON),
    Java(SyntaxLanguage.JAVA),
    C(SyntaxLanguage.C),
    Cpp(SyntaxLanguage.CPP),
    CSharp(SyntaxLanguage.CSHARP),
    Go(SyntaxLanguage.GO),
    Rust(SyntaxLanguage.RUST),
    Swift(SyntaxLanguage.SWIFT),
    Ruby(SyntaxLanguage.RUBY),
    Shell(SyntaxLanguage.SHELL),
    Php(SyntaxLanguage.PHP),
    Perl(SyntaxLanguage.PERL),
    Dart(SyntaxLanguage.DART),
    CoffeeScript(SyntaxLanguage.COFFEESCRIPT),
    Plain(SyntaxLanguage.DEFAULT),
    ;

    companion object {
        fun parse(name: String?): CodeLanguage = when (name?.lowercase()) {
            "kt", "kotlin", "kts" -> Kotlin
            "js", "javascript", "jsx" -> JavaScript
            "ts", "typescript", "tsx" -> TypeScript
            "py", "python" -> Python
            "java" -> Java
            "c" -> C
            "cpp", "c++", "cxx" -> Cpp
            "cs", "csharp", "c#" -> CSharp
            "go", "golang" -> Go
            "rs", "rust" -> Rust
            "swift" -> Swift
            "rb", "ruby" -> Ruby
            "sh", "shell", "bash" -> Shell
            "php" -> Php
            "pl", "perl" -> Perl
            "dart" -> Dart
            "coffee", "coffeescript" -> CoffeeScript
            else -> Plain
        }
    }
}

/**
 * Analyses [source] with Highlights and returns a contiguous, non-overlapping list of [CodeSpan]s
 * covering every character. Highlights' keyword / string / literal / comment / multiline-comment
 * regions are mapped to the corresponding [CodeSpanKind]; every unclassified character (whitespace,
 * operators, identifiers, punctuation, annotations, marks) is filled in as [CodeSpanKind.Plain].
 *
 * Empty input returns an empty list.
 */
internal fun highlightCode(source: String, language: CodeLanguage): List<CodeSpan> {
    if (source.isEmpty()) return emptyList()
    // Plain language skips Highlights entirely — SyntaxLanguage.DEFAULT is not a no-op (it still
    // recognises keywords), so we short-circuit to an all-Plain result.
    if (language == CodeLanguage.Plain) return listOf(CodeSpan(0, source.length, CodeSpanKind.Plain))
    val structure = Highlights.Builder()
        .code(source)
        .language(language.syntaxLanguage)
        .build()
        .getCodeStructure()

    val spans = mutableListOf<CodeSpan>()
    for (loc in structure.keywords) spans += CodeSpan(loc.start, loc.end, CodeSpanKind.Keyword)
    for (loc in structure.strings) spans += CodeSpan(loc.start, loc.end, CodeSpanKind.StringLit)
    for (loc in structure.literals) spans += CodeSpan(loc.start, loc.end, CodeSpanKind.Number)
    for (loc in structure.comments) spans += CodeSpan(loc.start, loc.end, CodeSpanKind.Comment)
    for (loc in structure.multilineComments) spans += CodeSpan(loc.start, loc.end, CodeSpanKind.Comment)
    return fillGaps(spans, source.length)
}

/**
 * Fills the gaps between structural [spans] with [CodeSpanKind.Plain] so the result covers
 * `[0, length)` exactly once. Overlapping or nested spans (defensive — Highlights shouldn't
 * produce any) are resolved by keeping the first span at each position. Adjacent Plain runs are
 * collapsed by [collapsePlain] for a compact result.
 */
private fun fillGaps(spans: List<CodeSpan>, length: Int): List<CodeSpan> {
    if (length == 0) return emptyList()
    if (spans.isEmpty()) return listOf(CodeSpan(0, length, CodeSpanKind.Plain))
    val sorted = spans.sortedBy { it.start }
    val result = mutableListOf<CodeSpan>()
    var pos = 0
    for (sp in sorted) {
        // Skip spans that start before the current cursor (overlap/nesting) — keep first wins.
        if (sp.start < pos) {
            if (sp.end > pos) {
                result += sp.copy(start = pos)
                pos = sp.end
            }
            continue
        }
        if (sp.start > pos) {
            result += CodeSpan(pos, sp.start, CodeSpanKind.Plain)
        }
        result += sp
        pos = sp.end
    }
    if (pos < length) {
        result += CodeSpan(pos, length, CodeSpanKind.Plain)
    }
    return collapsePlain(result)
}

private fun collapsePlain(spans: List<CodeSpan>): List<CodeSpan> {
    if (spans.size < 2) return spans
    val out = mutableListOf<CodeSpan>()
    for (sp in spans) {
        val last = out.lastOrNull()
        if (last != null && last.kind == CodeSpanKind.Plain && sp.kind == CodeSpanKind.Plain && last.end == sp.start) {
            out[out.lastIndex] = last.copy(end = sp.end)
        } else {
            out += sp
        }
    }
    return out
}

/** Render highlighted spans into an [AnnotatedString] using the given palette. */
internal fun highlightedAnnotated(
    source: String,
    language: CodeLanguage,
    keywordColor: Color,
    stringColor: Color,
    numberColor: Color,
    commentColor: Color,
    textColor: Color,
): AnnotatedString {
    val spans = highlightCode(source, language)
    return buildAnnotated(source, spans, keywordColor, stringColor, numberColor, commentColor, textColor)
}

internal fun buildAnnotated(
    source: String,
    spans: List<CodeSpan>,
    keywordColor: Color,
    stringColor: Color,
    numberColor: Color,
    commentColor: Color,
    textColor: Color,
): AnnotatedString {
    val builder = AnnotatedString.Builder(source)
    for (sp in spans) {
        val style = when (sp.kind) {
            CodeSpanKind.Keyword -> SpanStyle(color = keywordColor)
            CodeSpanKind.StringLit -> SpanStyle(color = stringColor)
            CodeSpanKind.Number -> SpanStyle(color = numberColor)
            CodeSpanKind.Comment -> SpanStyle(color = commentColor)
            CodeSpanKind.Plain -> SpanStyle(color = textColor)
        }
        builder.addStyle(style, sp.start, sp.end)
    }
    return builder.toAnnotatedString()
}
