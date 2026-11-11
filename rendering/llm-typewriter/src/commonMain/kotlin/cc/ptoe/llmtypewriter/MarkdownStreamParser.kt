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

/**
 * Token classes emitted by [parseStreamingMarkdown]. The parser is **prefix-stable**: the same
 * prefix of the input string always produces the same prefix of the token list, modulo a trailing
 * "open" token (e.g. a `**bold` with no closing `**` yet) which gets re-classified once the close
 * arrives. That's what lets the live renderer paint a partial stream without flicker.
 */
sealed class MdToken {

    /** Plain unstyled text. */
    data class Plain(val text: String) : MdToken()

    /** Emphasis: `*italic*` / `_italic_`. */
    data class Italic(val text: String) : MdToken()

    /** Strong: `**bold**` / `__bold__`. */
    data class Bold(val text: String) : MdToken()

    /** Strong+emphasis: `***both***`. */
    data class BoldItalic(val text: String) : MdToken()

    /** Inline code: `` `snippet` ``. */
    data class InlineCode(val text: String) : MdToken()

    /** Strikethrough: `~~text~~`. */
    data class Strikethrough(val text: String) : MdToken()

    /** Link: `[label](url)`. */
    data class Link(val label: String, val url: String) : MdToken()

    /** Image: `![alt text](url)`. The renderer receives both the URL and alt text. */
    data class Image(val altText: String, val url: String) : MdToken()

    /** A footnote reference: `[^label]`. */
    data class FootnoteReference(val label: String) : MdToken()

    /** A footnote definition: `[^label]: text` at the start of a line. */
    data class FootnoteDefinition(val label: String, val inline: List<MdToken>) : MdToken()

    /**
     * Heading: `# ...` / `## ...` etc. Level is 1..6, capped.
     *
     * [inline] is the heading's text content parsed into tokens by [parseStreamingMarkdown] at
     * parse time, so inline formatting (`**bold**`, `*italic*`, `` `code` ``, `[link](url)`,
     * `$math$`, …) works inside headings — matching the pattern used by [ListItem] and
     * [BlockQuote]. As the heading line streams in, [inline] is re-parsed from scratch on each
     * growth; earlier tokens stay stable because [parseStreamingMarkdown] is itself prefix-stable.
     */
    data class Heading(val level: Int, val inline: List<MdToken>) : MdToken()

    /** Newline — emitted as its own token for layout purposes. */
    data object Newline : MdToken()

    /** Horizontal split line: `---` at the start of a line. */
    data object HorizontalRule : MdToken()

    /** A fenced code block. `closed` flips to `true` once the trailing ``` is seen — until then
     * the block is rendering progressively (every new char extends [content]).
     */
    data class CodeBlock(
        val language: String?,
        val content: String,
        val closed: Boolean,
    ) : MdToken()

    /**
     * Inline math `$...$` or `\(...\)`. [content] is the raw TeX fragment between the delimiters.
     * [closed] is `false` while the trailing delimiter hasn't arrived yet — the renderer paints
     * [content] as plain text in that case, then re-classifies to math once the close lands.
     */
    data class InlineMath(val content: String, val closed: Boolean) : MdToken()

    /**
     * Display math `$$...$$` or `\[...\]` — block-level, renders larger and centered with a
     * background. [closed] flips to `true` once the closing `$$` (or `\]`) arrives.
     */
    data class DisplayMath(val content: String, val closed: Boolean) : MdToken()

    /**
     * A list item — `-`, `*`, `+`, `1.`, `2.` etc. at line start (optionally indented for nesting).
     *
     * - [ordered] is `true` for `1.` / `1)` markers, `false` for `-` / `*` / `+`.
     * - [number] carries the marker number for ordered lists (e.g. `1`, `2`, `3`); `0` for
     *    unordered. The renderer uses the **first** item's number as the list's start number.
     * - [indent] is the leading-space count divided by 2 (so `  - sub` has `indent = 1`).
     *    Two-space indent unit mirrors the most common LLM output; larger indents still nest one
     *    level per [indent] step.
     * - [checked] is `null` for a plain item. For a GFM task-list item (`- [ ]` / `- [x]`), it is
     *    `false` (unchecked) or `true` (checked, accepts `x` or `X`). The renderer shows the
     *    Unicode ballot-box glyph (`☐` / `☑`) in place of the bullet marker when [checked] is
     *    non-null.
     * - [inline] is the inline content parsed from the rest of the line after the marker. Single
     *    line only — multi-line list items (continuation lines) are not yet supported; the parser
     *    falls back to treating each non-marker line as its own paragraph.
     *
     * Prefix-stability note: as the line grows, [inline] is re-parsed from scratch (just like a
     * [CodeBlock]'s content grows). Earlier inline tokens stay stable because
     * [parseStreamingMarkdown] is itself prefix-stable; only the trailing "open" token
     * re-classifies once its close arrives. A partial task marker (e.g. `- [x` with no closing
     * `]`) parses as a plain item with the `[x` text in [inline] and [checked] = `null`; once the
     * `]` arrives the trailing token re-classifies to a task item ([checked] flips to `true`,
     * [inline] shrinks to whatever follows the marker).
     */
    data class ListItem(
        val ordered: Boolean,
        val number: Int,
        val indent: Int,
        val inline: List<MdToken>,
        val checked: Boolean? = null,
    ) : MdToken()

    /**
     * A block-quote line — `> quote` / `>> nested quote` at line start.
     *
     * - [level] is the number of leading `>` markers (so `>> nested` has `level = 2`).
     * - [inline] is the inline content parsed from the rest of the line after the markers.
     *
     * Multi-line quotes are represented as consecutive [BlockQuote] tokens so the renderer can
     * group them into one visual quote block while preserving prefix-stability as lines stream in.
     */
    data class BlockQuote(
        val level: Int,
        val inline: List<MdToken>,
    ) : MdToken()

    /**
     * A GFM-style table. [headers] are the raw header cell texts (inline-parsed at render time);
     * [aligns] carries per-column alignment derived from the separator row's colons
     * ([TableAlign.DEFAULT] when the separator had no colons); [rows] is the list of data rows,
     * each a list of raw cell texts. [closed] is `false` while the table is at the end of the
     * input and might still grow more rows — the renderer paints whatever rows are present either
     * way, so [closed] is informational and does not affect rendering.
     *
     * Prefix-stability note: a header line alone (no separator yet) is emitted as plain/inline
     * tokens. Once the separator line arrives, the trailing plain tokens re-classify into a
     * single [Table] token. As subsequent rows stream in, the [Table] token grows (rows are
     * appended) — earlier rows stay stable. A row being typed (incomplete, no closing `|`) is
     * still consumed as a row; its cells are split from whatever content is present.
     */
    data class Table(
        val headers: List<String>,
        val aligns: List<TableAlign>,
        val rows: List<List<String>>,
        val closed: Boolean,
    ) : MdToken()

    /**
     * A think block: `<think>...</think>`. [content] is the raw text between the tags.
     * [closed] flips to `true` once the closing `</think>` is seen — until then the block
     * is rendering progressively. Think blocks render as special collapsible quotes.
     */
    data class ThinkBlock(
        val content: String,
        val closed: Boolean,
    ) : MdToken()
}

/** Per-column alignment for a [MdToken.Table], derived from the separator row's colons. */
enum class TableAlign { DEFAULT, LEFT, CENTER, RIGHT }

/**
 * A best-effort, prefix-stable streaming markdown parser.
 *
 * This isn't a full CommonMark implementation — it's a deliberately small subset chosen so the
 * tokens stay stable as the input grows. The set of patterns covered:
 *
 *   - Headings (`#` … `######`) on their own line
 *   - Fenced code blocks (` ``` `) with optional language
 *   - Inline code (backticks)
 *   - Bold (`**` / `__`)
 *   - Italic (`*` / `_`)
 *   - Bold+italic (`***` / `___`)
 *   - Strikethrough (`~~`)
 *   - Links (`[label](url)`)
 *   - Images (`![alt text](url)`)
 *   - Footnotes (`[^label]` references and `[^label]: definition` lines)
 *   - Block quotes (`> quote` / `>> nested quote`)
 *   - Unordered lists (`-` / `*` / `+` at line start, optionally indented for nesting)
 *   - Ordered lists (`1.` / `1)` at line start, optionally indented for nesting)
 *   - Task list items (`- [ ]` / `- [x]` after any unordered or ordered marker)
 *   - Horizontal split lines (`---` at line start)
 *   - GFM tables (`| a | b |` header + `| --- | --- |` separator + data rows)
 *   - Inline math (`$...$` and `\(...\)`)
 *   - Display math (`$$...$$` and `\[...\]`)
 *
 * Open spans at the tail of the input are rendered as plain text until they close — but the
 * tokens before them are stable, so the live renderer doesn't reflow earlier text.
 */
internal fun parseStreamingMarkdown(input: String): List<MdToken> {
    if (input.isEmpty()) return emptyList()
    val out = mutableListOf<MdToken>()
    var i = 0
    val len = input.length

    // Track newline boundaries so heading parsing only fires at the start of a line.
    var atLineStart = true

    while (i < len) {
        val c = input[i]

        // Code blocks have to be checked before everything else — they suppress all other parsing
        // until the close fence is seen.
        if (atLineStart && i + 2 < len && input[i] == '`' && input[i + 1] == '`' && input[i + 2] == '`') {
            i = consumeCodeBlock(input, i, out)
            atLineStart = false
            continue
        }

        if (matchesAt(input, i, "<think>")) {
            i = consumeThinkBlock(input, i, out)
            atLineStart = false
            continue
        }

        if (c == '\n') {
            out += MdToken.Newline
            atLineStart = true
            i++
            continue
        }

        if (atLineStart) {
            val quoteEnd = consumeBlockQuoteIfAny(input, i, out)
            if (quoteEnd > i) {
                i = quoteEnd
                atLineStart = true
                continue
            }
        }

        if (atLineStart && c == '#') {
            val headingEnd = consumeHeading(input, i, out)
            if (headingEnd > i) {
                i = headingEnd
                atLineStart = false
                continue
            }
        }

        if (atLineStart) {
            val horizontalRuleEnd = consumeHorizontalRuleIfAny(input, i, out)
            if (horizontalRuleEnd > i) {
                i = horizontalRuleEnd
                atLineStart = false
                continue
            }
        }

        if (atLineStart) {
            val footnoteEnd = consumeFootnoteDefinitionIfAny(input, i, out)
            if (footnoteEnd > i) {
                i = footnoteEnd
                atLineStart = true
                continue
            }
        }

        // List items — must fire before `atLineStart = false` so the leading `*` / `+` / `-`
        // markers win over inline emphasis parsing, and before the inline `Plain` run so the
        // marker isn't swallowed into a plain token. Heading check above already exited, so a
        // `#`-prefixed line never reaches here.
        if (atLineStart) {
            val listEnd = consumeListItemIfAny(input, i, out)
            if (listEnd > i) {
                i = listEnd
                // We consumed through the trailing newline (if any), so the next iteration is at
                // the start of a new line — list items chain without an intervening Newline
                // token, which is what lets `groupIntoParagraphs` treat consecutive items as one
                // list group.
                atLineStart = true
                continue
            }
        }

        // Tables — a `|` at line start begins a table only if the *next* line is a valid
        // separator (`| --- | --- |`). Until the separator arrives, the header line falls
        // through to inline parsing (plain text) — prefix-stable: the trailing plain tokens
        // re-classify into a Table token once the separator lands.
        if (atLineStart && c == '|') {
            val tableEnd = consumeTableIfAny(input, i, out)
            if (tableEnd > i) {
                i = tableEnd
                atLineStart = true
                continue
            }
        }

        atLineStart = false

        if (c == '`') {
            val close = input.indexOf('`', startIndex = i + 1)
            if (close >= 0 && close > i + 1) {
                out += MdToken.InlineCode(input.substring(i + 1, close))
                i = close + 1
                continue
            } else {
                // Unclosed backtick — render the rest as plain so we don't lose anything.
                out += MdToken.Plain(input.substring(i))
                i = len
                continue
            }
        }

        // TeX-style inline math. An unclosed opener is emitted as plain text so the partial
        // stream remains safe to render; once `\)` arrives, the trailing plain run re-classifies.
        if (c == '\\' && matchesAt(input, i, "\\(")) {
            val close = input.indexOf("\\)", startIndex = i + 2)
            if (close > i + 2) {
                out += MdToken.InlineMath(input.substring(i + 2, close), closed = true)
                i = close + 2
                continue
            }
            out += MdToken.Plain("\\")
            i++
            continue
        }

        // TeX-style display math `\[...\]`. An unclosed opener is emitted as plain text so the
        // partial stream remains safe to render; once `\]` arrives, the trailing plain run
        // re-classifies. Same prefix-stable pattern as `\(...\)` and `$$...$$`.
        if (c == '\\' && matchesAt(input, i, "\\[")) {
            val close = input.indexOf("\\]", startIndex = i + 2)
            if (close > i + 2) {
                out += MdToken.DisplayMath(input.substring(i + 2, close), closed = true)
                i = close + 2
                continue
            }
            out += MdToken.Plain("\\")
            i++
            continue
        }

        // Math delimiters — `$$...$$` (display) takes priority over `$...$` (inline). The inline
        // form requires no space immediately after the opening `$` or before the closing `$` so
        // that currency like `$5` doesn't false-positive into a math span. Unclosed delimiters
        // degrade to plain text (prefix-stable: the trailing plain run re-classifies to a math
        // token once the close arrives).
        if (c == '$') {
            val isDisplay = i + 1 < len && input[i + 1] == '$'
            if (isDisplay) {
                val close = findDoubleDollar(input, i + 2)
                if (close >= 0) {
                    out += MdToken.DisplayMath(input.substring(i + 2, close), closed = true)
                    i = close + 2
                    continue
                }
                // Unclosed `$$...` — emit `$$` as plain and reparse the rest at top level so we
                // don't swallow everything as a giant unclosed block.
                out += MdToken.Plain("$$")
                i += 2
                continue
            }
            // Inline `$...$`. Reject if next char is a space (e.g. "$ word") — that's almost
            // certainly currency or stray punctuation, not a math opener.
            if (i + 1 < len && !input[i + 1].isWhitespace()) {
                val close = findInlineDollar(input, i + 1)
                if (close > i + 1) {
                    out += MdToken.InlineMath(input.substring(i + 1, close), closed = true)
                    i = close + 1
                    continue
                }
            }
            // No close (or empty content) — emit `$` as plain and continue.
            out += MdToken.Plain("$")
            i++
            continue
        }

        // Order matters: triple-star before double, double before single.
        if (matchesAt(input, i, "***") || matchesAt(input, i, "___")) {
            val delim = input.substring(i, i + 3)
            val close = indexOfDelim(input, i + 3, delim)
            if (close >= 0) {
                out += MdToken.BoldItalic(input.substring(i + 3, close))
                i = close + 3
                continue
            } else {
                out += MdToken.Plain(input.substring(i))
                i = len
                continue
            }
        }
        if (matchesAt(input, i, "**") || matchesAt(input, i, "__")) {
            val delim = input.substring(i, i + 2)
            val close = indexOfDelim(input, i + 2, delim)
            if (close >= 0) {
                out += MdToken.Bold(input.substring(i + 2, close))
                i = close + 2
                continue
            } else {
                out += MdToken.Plain(input.substring(i))
                i = len
                continue
            }
        }
        if (matchesAt(input, i, "~~")) {
            val close = indexOfDelim(input, i + 2, "~~")
            if (close >= 0) {
                out += MdToken.Strikethrough(input.substring(i + 2, close))
                i = close + 2
                continue
            } else {
                out += MdToken.Plain(input.substring(i))
                i = len
                continue
            }
        }
        if (c == '*' || c == '_') {
            val delim = c.toString()
            val close = indexOfDelim(input, i + 1, delim)
            if (close >= 0 && close > i + 1) {
                out += MdToken.Italic(input.substring(i + 1, close))
                i = close + 1
                continue
            } else {
                // Treat as a plain character (it might be a literal '*').
                out += MdToken.Plain(c.toString())
                i++
                continue
            }
        }
        if (c == '!' && i + 1 < len && input[i + 1] == '[') {
            val imageEnd = consumeImage(input, i, out)
            if (imageEnd > i) {
                i = imageEnd
                continue
            }
        }
        if (c == '[') {
            val footnoteEnd = consumeFootnoteReference(input, i, out)
            if (footnoteEnd > i) {
                i = footnoteEnd
                continue
            }
            val linkEnd = consumeLink(input, i, out)
            if (linkEnd > i) {
                i = linkEnd
                continue
            }
        }

        // Plain run — accumulate until we hit a special char or the end.
        val runEnd = nextSpecial(input, i + 1)
        out += MdToken.Plain(input.substring(i, runEnd))
        i = runEnd
    }
    return mergeAdjacentPlain(out)
}

private fun consumeFootnoteReference(input: String, start: Int, out: MutableList<MdToken>): Int {
    if (!matchesAt(input, start, "[^")) return start
    val close = input.indexOf(']', startIndex = start + 2)
    if (close <= start + 2) return start
    val label = input.substring(start + 2, close)
    if (label.any { it.isWhitespace() }) return start
    out += MdToken.FootnoteReference(label)
    return close + 1
}

private fun consumeThinkBlock(input: String, start: Int, out: MutableList<MdToken>): Int {
    val openLen = "<think>".length
    var j = start + openLen
    val close = input.indexOf("</think>", startIndex = j)
    return if (close < 0) {
        out += MdToken.ThinkBlock(input.substring(j), closed = false)
        input.length
    } else {
        out += MdToken.ThinkBlock(input.substring(j, close), closed = true)
        close + "</think>".length
    }
}

private fun consumeFootnoteDefinitionIfAny(
    input: String,
    start: Int,
    out: MutableList<MdToken>,
): Int {
    if (!matchesAt(input, start, "[^")) return start
    val close = input.indexOf(']', startIndex = start + 2)
    if (close <= start + 2 || close + 1 >= input.length || input[close + 1] != ':') return start
    val label = input.substring(start + 2, close)
    if (label.any { it.isWhitespace() }) return start
    val contentStart = if (close + 2 < input.length && input[close + 2] == ' ') close + 3 else close + 2
    val lineEnd = input.indexOf('\n', startIndex = contentStart).let { if (it < 0) input.length else it }
    out += MdToken.FootnoteDefinition(label, parseStreamingMarkdown(input.substring(contentStart, lineEnd)))
    return if (lineEnd < input.length) lineEnd + 1 else input.length
}

private fun nextSpecial(s: String, from: Int): Int {
    var j = from
    while (j < s.length) {
        val c = s[j]
        if (c == '\n' || c == '`' || c == '*' || c == '_' || c == '~' || c == '[' || c == '!' || c == '#' || c == '$' || c == '\\') break
        j++
    }
    return j
}

private fun matchesAt(s: String, at: Int, pattern: String): Boolean {
    if (at + pattern.length > s.length) return false
    for (k in pattern.indices) if (s[at + k] != pattern[k]) return false
    return true
}

private fun indexOfDelim(s: String, from: Int, delim: String): Int {
    var j = from
    val end = s.length - delim.length
    while (j <= end) {
        if (matchesAt(s, j, delim)) return j
        j++
    }
    return -1
}

private fun consumeHeading(input: String, start: Int, out: MutableList<MdToken>): Int {
    var level = 0
    var j = start
    while (j < input.length && input[j] == '#' && level < 6) {
        j++
        level++
    }
    // Require a space after the hashes to qualify as a heading (matches CommonMark).
    if (j >= input.length || input[j] != ' ') return start
    j++ // consume the space
    val lineEnd = input.indexOf('\n', startIndex = j).let { if (it < 0) input.length else it }
    val content = input.substring(j, lineEnd).trimEnd()
    // Recursively parse the inline content of the heading. The recursive call sees no `\n`, so it
    // can't itself emit a CodeBlock fence or another Heading (those require `\n` boundaries) —
    // only inline spans (bold / italic / inline code / inline math / links). Same pattern as
    // [consumeListItemIfAny] and [consumeBlockQuoteIfAny].
    out += MdToken.Heading(level, parseStreamingMarkdown(content))
    return lineEnd
}

/**
 * Tries to consume a Markdown horizontal rule. Up to three leading spaces are accepted, and the
 * line must contain exactly three dashes apart from trailing spaces. Returning the line end (and
 * leaving its newline for the main loop) keeps the rule a block token without swallowing the
 * following paragraph boundary.
 */
private fun consumeHorizontalRuleIfAny(input: String, start: Int, out: MutableList<MdToken>): Int {
    val lineEnd = input.indexOf('\n', startIndex = start).let { if (it < 0) input.length else it }
    val line = input.substring(start, lineEnd)
    val leadingSpaces = line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }
    if (leadingSpaces > 3 || line.substring(leadingSpaces).trimEnd() != "---") return start
    out += MdToken.HorizontalRule
    return lineEnd
}

/**
 * Tries to consume a list item starting at [start] (which is at a line start, possibly with
 * leading spaces). On match, emits a [MdToken.ListItem] to [out] and returns the index past the
 * trailing newline (so the next iteration is at line start again). On no match, returns [start]
 * unchanged so the caller falls through to other parsing.
 *
 * Indentation is counted in units of 2 spaces (`  - sub` → indent 1) — the most common LLM
 * convention. A single leading space is treated as indent 0 (some style guides use one-space
 * indents); 3 spaces round down to 1, 4 to 2, etc. (CommonMark treats tabs and varying spaces
 * specially; we deliberately stay simpler to keep prefix-stability tractable).
 *
 * GFM task list items are detected after the marker: a `[ ]` or `[x]` / `[X]` immediately
 * following the marker's single space (and followed by a space or end-of-line) flips
 * [MdToken.ListItem.checked] to `false` / `true`. Partial markers (`- [x` with no closing `]`)
 * parse as a plain item — the trailing token re-classifies once the `]` arrives.
 */
private fun consumeListItemIfAny(input: String, start: Int, out: MutableList<MdToken>): Int {
    val len = input.length
    var j = start
    // Skip leading spaces — capped at 8 to avoid pathological inputs.
    var spaces = 0
    while (j < len && input[j] == ' ' && spaces < 8) {
        spaces++
        j++
    }
    val indent = spaces / 2

    val marker = matchListMarker(input, j) ?: return start
    val contentStart = marker.contentStart
    val lineEnd = input.indexOf('\n', startIndex = contentStart).let { if (it < 0) len else it }
    val content = input.substring(contentStart, lineEnd).trimEnd()
    // Detect a GFM task-list marker: `[ ]` / `[x]` / `[X]` followed by a space or end-of-content.
    // On a match, [checked] is non-null and the inline content starts after the marker (+ its
    // trailing space). On no match, [checked] stays null and the whole line is the inline content.
    val taskMarker = matchTaskMarker(content)
    val (inlineContent, checked) = if (taskMarker != null) {
        content.substring(taskMarker.contentStart) to taskMarker.checked
    } else {
        content to null
    }
    // Recursively parse the inline content of the line. The recursive call sees no `\n`, so it
    // can't itself emit a CodeBlock fence or a Heading (those require `\n` boundaries) — only
    // inline spans (bold / italic / inline code / inline math / links).
    val inline = parseStreamingMarkdown(inlineContent)
    out += MdToken.ListItem(
        ordered = marker.ordered,
        number = marker.number,
        indent = indent,
        inline = inline,
        checked = checked,
    )
    // Consume through the trailing newline so the next iteration starts at the next line — list
    // items chain without an intervening Newline token.
    return if (lineEnd < len) lineEnd + 1 else len
}

private data class TaskMarkerMatch(val checked: Boolean, val contentStart: Int)

/**
 * Matches a GFM task-list marker at the start of [content] (the text after the list marker +
 * its single space). Returns `null` if no marker matches. A valid marker is `[ ]` (unchecked),
 * `[x]` / `[X]` (checked), followed by either a single space or the end of [content] — matching
 * the GFM spec's "followed by a single space or end of line" rule. The returned [contentStart]
 * skips the marker and its trailing space (when present) so the caller can substring the
 * remaining inline content.
 *
 * Prefix-stability: `- [x` (no `]` yet) returns `null` — the line parses as a plain item with
 * `[x` as inline text. Once the `]` arrives the trailing token re-classifies to a task item.
 */
private fun matchTaskMarker(content: String): TaskMarkerMatch? {
    if (content.length < 3) return null
    if (content[0] != '[') return null
    val c = content[1]
    if (c != ' ' && c != 'x' && c != 'X') return null
    if (content[2] != ']') return null
    val checked = c != ' '
    return when {
        // `[ ]` or `[x]` at end of line — inline content is empty.
        content.length == 3 -> TaskMarkerMatch(checked, contentStart = 3)
        // `[ ] todo` — skip the trailing space; inline content starts at index 4.
        content[3] == ' ' -> TaskMarkerMatch(checked, contentStart = 4)
        // `[x]todo` (no space) — not a task marker, falls back to plain item.
        else -> null
    }
}

private fun consumeBlockQuoteIfAny(input: String, start: Int, out: MutableList<MdToken>): Int {
    val len = input.length
    var j = start
    var spaces = 0
    while (j < len && input[j] == ' ' && spaces < 3) {
        spaces++
        j++
    }
    if (j >= len || input[j] != '>') return start

    var level = 0
    while (j < len && input[j] == '>') {
        level++
        j++
        if (j < len && input[j] == ' ') j++
    }

    val lineEnd = input.indexOf('\n', startIndex = j).let { if (it < 0) len else it }
    val content = input.substring(j, lineEnd).trimEnd()
    out += MdToken.BlockQuote(level = level, inline = parseStreamingMarkdown(content))
    return if (lineEnd < len) lineEnd + 1 else len
}

private data class ListMarkerMatch(val ordered: Boolean, val number: Int, val contentStart: Int)

/**
 * Matches a list marker at position [at] in [input]. Supports:
 *   - Unordered: `-`, `*`, `+` followed by a space (or end-of-line / end-of-input — empty item).
 *   - Ordered:   `\d+(\.|\))` followed by a space (or end-of-line / end-of-input — empty item).
 *
 * Returns `null` if no marker matches. CommonMark requires the space-after-marker rule, so
 * `1.text` (no space) does NOT parse as a list item — it falls through to plain text. This is
 * important for things like dates (`2024.01.01`) and dotted version numbers.
 */
private fun matchListMarker(input: String, at: Int): ListMarkerMatch? {
    val len = input.length
    if (at >= len) return null
    val c = input[at]

    // Unordered: - * +
    if (c == '-' || c == '*' || c == '+') {
        if (at + 1 < len && input[at + 1] == ' ') {
            return ListMarkerMatch(ordered = false, number = 0, contentStart = at + 2)
        }
        // Allow an empty item at end-of-line / end-of-input: `-` or `-\n`.
        if (at + 1 >= len || input[at + 1] == '\n') {
            return ListMarkerMatch(ordered = false, number = 0, contentStart = minOf(at + 1, len))
        }
        return null
    }

    // Ordered: \d+(\.|\))
    if (c.isDigit()) {
        var j = at
        var num = 0
        var digits = 0
        while (j < len && input[j].isDigit()) {
            num = num * 10 + (input[j] - '0')
            j++
            digits++
            // 9+ digits is almost certainly not a list marker (e.g. a large number) — bail.
            if (digits > 9) return null
        }
        if (j < len && (input[j] == '.' || input[j] == ')')) {
            if (j + 1 < len && input[j + 1] == ' ') {
                return ListMarkerMatch(ordered = true, number = num, contentStart = j + 2)
            }
            // Allow an empty item at end-of-line / end-of-input: `1.` or `1.\n`.
            if (j + 1 >= len || input[j + 1] == '\n') {
                return ListMarkerMatch(ordered = true, number = num, contentStart = minOf(j + 1, len))
            }
        }
    }

    return null
}

private fun consumeCodeBlock(input: String, start: Int, out: MutableList<MdToken>): Int {
    var j = start + 3
    val langEnd = input.indexOf('\n', startIndex = j).let { if (it < 0) input.length else it }
    val language = input.substring(j, langEnd).trim().ifEmpty { null }
    j = if (langEnd < input.length) langEnd + 1 else input.length
    val close = findCodeFenceClose(input, j)
    return if (close < 0) {
        // Open fence — emit unclosed code block containing everything up to the current tail.
        out += MdToken.CodeBlock(language, input.substring(j), closed = false)
        input.length
    } else {
        out += MdToken.CodeBlock(language, input.substring(j, close), closed = true)
        // Skip past the closing ``` and the trailing newline if present.
        var after = close + 3
        if (after < input.length && input[after] == '\n') after++
        after
    }
}

private fun findCodeFenceClose(input: String, from: Int): Int {
    // The closing fence must be at line start.
    var line = from
    while (line < input.length) {
        if (matchesAt(input, line, "```")) return line
        val nl = input.indexOf('\n', startIndex = line)
        if (nl < 0) return -1
        line = nl + 1
    }
    return -1
}

private fun consumeLink(input: String, start: Int, out: MutableList<MdToken>): Int {
    val labelClose = input.indexOf(']', startIndex = start + 1)
    if (labelClose < 0 || labelClose + 1 >= input.length || input[labelClose + 1] != '(') return start
    val urlClose = input.indexOf(')', startIndex = labelClose + 2)
    if (urlClose < 0) return start
    val label = input.substring(start + 1, labelClose)
    val url = input.substring(labelClose + 2, urlClose)
    out += MdToken.Link(label, url)
    return urlClose + 1
}

private fun consumeImage(input: String, start: Int, out: MutableList<MdToken>): Int {
    val labelClose = input.indexOf(']', startIndex = start + 2)
    if (labelClose < 0 || labelClose + 1 >= input.length || input[labelClose + 1] != '(') return start
    val urlClose = input.indexOf(')', startIndex = labelClose + 2)
    if (urlClose < 0) return start
    out += MdToken.Image(
        altText = input.substring(start + 2, labelClose),
        url = input.substring(labelClose + 2, urlClose),
    )
    return urlClose + 1
}

/**
 * Tries to consume a GFM-style table starting at [start] (which is at a line start, with the
 * first char being `|`). On match, emits a [MdToken.Table] to [out] and returns the index past
 * the last consumed row's trailing newline (so the next iteration is at line start again). On no
 * match, returns [start] unchanged so the caller falls through to inline parsing.
 *
 * A table requires:
 *   1. A header line containing `|`.
 *   2. A separator line immediately after — each cell matches `:?-+:?` (optional colon, one+
 *      dashes, optional colon), deriving [TableAlign] per column.
 *   3. Zero or more data rows, each a `|`-delimited line.
 *
 * The table ends at the first line that doesn't start with `|` (after optional spaces), at a
 * blank line, or at end of input. [MdToken.Table.closed] is `false` when the table extends to
 * end of input (more rows might stream in); `true` when a non-table line follows.
 *
 * Prefix stability: while only the header line is present (no separator yet), this returns
 * [start] → the header falls through to inline parsing as plain text. Once the separator
 * arrives, the trailing plain tokens re-classify into a [MdToken.Table].
 */
private fun consumeTableIfAny(input: String, start: Int, out: MutableList<MdToken>): Int {
    val len = input.length
    // Header line: from `start` to next newline (or end of input).
    val headerLineEnd = input.indexOf('\n', startIndex = start).let { if (it < 0) len else it }
    val headerLine = input.substring(start, headerLineEnd)
    // Header must contain at least one `|` to be a table header.
    if (!headerLine.contains('|')) return start
    // Need a second line (the separator). If the header is at end of input (no newline), no
    // separator can exist yet → not a table.
    if (headerLineEnd >= len) return start
    // Separator line: from headerLineEnd+1 to next newline (or end of input).
    val sepLineEnd = input.indexOf('\n', startIndex = headerLineEnd + 1).let { if (it < 0) len else it }
    val sepLine = input.substring(headerLineEnd + 1, sepLineEnd)
    // Validate separator and extract alignments.
    val aligns = parseSeparatorAligns(sepLine) ?: return start
    // Parse header cells.
    val headers = splitTableCells(headerLine)
    // Consume subsequent `|`-delimited lines as rows.
    val rows = mutableListOf<List<String>>()
    var j = if (sepLineEnd < len) sepLineEnd + 1 else len
    // closed = false while the table extends to end of input (might grow); set true when a
    // non-table line follows.
    var closed = false
    while (j < len) {
        val rowLineEnd = input.indexOf('\n', startIndex = j).let { if (it < 0) len else it }
        val rowLine = input.substring(j, rowLineEnd)
        // A row must start with `|` (after optional leading spaces). A blank line or any other
        // content ends the table.
        val firstNonSpace = rowLine.indexOfFirst { !it.isWhitespace() }
        if (firstNonSpace < 0 || rowLine[firstNonSpace] != '|') {
            closed = true
            break
        }
        rows += splitTableCells(rowLine)
        j = if (rowLineEnd < len) rowLineEnd + 1 else len
        // If we reached end of input, the table might still grow → closed stays false.
    }
    out += MdToken.Table(headers, aligns, rows, closed)
    return j
}

/**
 * Splits a `|`-delimited table line into trimmed cell texts. A leading `|` (which produces an
 * empty first element) and a trailing `|` (empty last element) are stripped — so `| a | b |`
 * yields `["a", "b"]` and `a | b` yields `["a", "b"]` too. Interior empty cells (`||`) are
 * preserved as empty strings.
 */
private fun splitTableCells(line: String): List<String> {
    val parts = line.split('|')
    var startIdx = 0
    var endIdx = parts.size
    if (startIdx < endIdx && parts[startIdx].isEmpty()) startIdx++
    if (endIdx > startIdx && parts[endIdx - 1].isEmpty()) endIdx--
    return parts.subList(startIdx, endIdx).map { it.trim() }
}

/**
 * Validates a separator line and extracts per-column [TableAlign] from the colons. Each cell
 * must match `:?-+:?` — optional leading colon, one+ dashes, optional trailing colon. Returns
 * `null` if the line is not a valid separator (no cells, empty cells, or cells with invalid
 * characters).
 */
private fun parseSeparatorAligns(line: String): List<TableAlign>? {
    val cells = splitTableCells(line)
    if (cells.isEmpty()) return null
    val aligns = mutableListOf<TableAlign>()
    for (cell in cells) {
        if (cell.isEmpty()) return null
        var s = 0
        var e = cell.length
        val leftColon = s < e && cell[s] == ':'
        if (leftColon) s++
        val rightColon = s < e && cell[e - 1] == ':'
        if (rightColon) e--
        // The middle must be all dashes and non-empty.
        if (s >= e) return null
        for (k in s until e) if (cell[k] != '-') return null
        aligns += when {
            leftColon && rightColon -> TableAlign.CENTER
            rightColon -> TableAlign.RIGHT
            leftColon -> TableAlign.LEFT
            else -> TableAlign.DEFAULT
        }
    }
    return aligns
}

internal fun mergeAdjacentPlain(input: List<MdToken>): List<MdToken> {
    if (input.size < 2) return input
    val out = mutableListOf<MdToken>()
    for (tok in input) {
        val last = out.lastOrNull()
        if (last is MdToken.Plain && tok is MdToken.Plain) {
            out[out.lastIndex] = MdToken.Plain(last.text + tok.text)
        } else {
            out += tok
        }
    }
    return out
}

/**
 * Finds the closing `$$` for a display-math span that starts at [from] (i.e. just past the
 * opening `$$`). Skips escaped `\$$` sequences. Returns -1 if no close is present.
 */
private fun findDoubleDollar(input: String, from: Int): Int {
    var j = from
    val len = input.length
    while (j + 1 < len) {
        if (input[j] == '\\' && j + 1 < len) { j += 2; continue }
        if (input[j] == '$' && input[j + 1] == '$') return j
        j++
    }
    return -1
}

/**
 * Finds the closing `$` for an inline-math span. Starts at [from] (the first char after the
 * opening `$`). Rejects whitespace-immediately-before candidates — the close must not be
 * preceded by whitespace, and it must not be a `$$` (which is display math). Returns -1 if no
 * valid close is present.
 */
private fun findInlineDollar(input: String, from: Int): Int {
    var j = from
    val len = input.length
    while (j < len) {
        val c = input[j]
        if (c == '\\' && j + 1 < len) { j += 2; continue }
        if (c == '$') {
            // Reject `$$` — that's display math, not an inline close.
            if (j + 1 < len && input[j + 1] == '$') return -1
            // Reject if the previous char is whitespace — `$ word $` is not a math span.
            if (j > 0 && input[j - 1].isWhitespace()) return -1
            return j
        }
        j++
    }
    return -1
}
