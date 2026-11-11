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
 * Token kinds emitted by [parseTex]. The parser is **prefix-stable**: any prefix of the input
 * string produces the same prefix of the token list (modulo a trailing token that may be
 * re-classified once the closing character arrives). That lets the live renderer paint a
 * partial stream without flicker — the same invariant [parseStreamingMarkdown] upholds.
 *
 * Degrading rules for incomplete input (no character loss):
 *  - Unclosed `{` → emit the `{` as [TexToken.Text] and continue parsing the rest.
 *  - Dangling `\` at end of input → emit `\` as [TexToken.Text].
 *  - `^` / `_` without a following target → emit the `^` / `_` as [TexToken.Text].
 */
sealed class TexToken {

    /** Plain run of literal characters (identifiers, digits, punctuation, whitespace). */
    data class Text(val text: String) : TexToken()

    /** A control sequence: `\frac`, `\sqrt`, `\sum`, `\alpha`, … [name] excludes the leading `\`. */
    data class Command(val name: String) : TexToken()

    /** A balanced `{ ... }` group. [tokens] is the recursive parse of the inner content. */
    data class Group(val tokens: List<TexToken>) : TexToken()

    /** Superscript target — `^{...}` or `^x`. [target] is the parsed target token list. */
    data class Superscript(val target: List<TexToken>) : TexToken()

    /** Subscript target — `_{...}` or `_x`. [target] is the parsed target token list. */
    data class Subscript(val target: List<TexToken>) : TexToken()

    /** A `%`-style line comment, terminated by newline or end of input. Excludes the `%`. */
    data class Comment(val text: String) : TexToken()
}

/**
 * Parses a (possibly partial) TeX math string into a list of [TexToken]s. Prefix-stable: a
 * prefix of the input always yields the same prefix of tokens, so the live reveal can repaint
 * the same early tokens as more text streams in.
 *
 * The parser is deliberately minimal — it understands structure (`{}`, `^`, `_`, `\command`,
 * `%comment`) but does **not** interpret commands semantically. Building the semantic AST
 * ([MathNode]) is the job of [buildMathAst].
 */
internal fun parseTex(input: String): List<TexToken> {
    if (input.isEmpty()) return emptyList()
    val out = mutableListOf<TexToken>()
    var i = 0
    val len = input.length

    while (i < len) {
        val c = input[i]

        // Line comment — % to end of line (or end of input). TeX semantics: the trailing newline
        // is consumed by the comment (so the next line follows directly).
        if (c == '%') {
            val nl = input.indexOf('\n', startIndex = i + 1)
            if (nl < 0) {
                out += TexToken.Comment(input.substring(i + 1, len))
                i = len
            } else {
                out += TexToken.Comment(input.substring(i + 1, nl))
                i = nl + 1
            }
            continue
        }

        // Control sequence: a backslash followed by either a name (one or more letters) or a
        // single non-letter character (e.g. `\,`, `\{`). A dangling backslash at the very end
        // degrades to a literal `\`.
        if (c == '\\') {
            if (i + 1 >= len) {
                out += TexToken.Text("\\")
                i = len
                continue
            }
            val next = input[i + 1]
            if (next.isLetter()) {
                var j = i + 1
                while (j < len && input[j].isLetter()) j++
                out += TexToken.Command(input.substring(i + 1, j))
                i = j
                continue
            }
            // Single non-letter command like `\,` `\{` `\%`.
            out += TexToken.Command(next.toString())
            i += 2
            continue
        }

        // Group open: `{`. If the matching `}` never arrives, degrade to a literal `{` and keep
        // parsing the contents as if they were at top level — no character is lost.
        if (c == '{') {
            val close = findMatchingBrace(input, i + 1)
            if (close < 0) {
                // Unclosed — emit `{` as text and continue parsing the rest at top level.
                out += TexToken.Text("{")
                i++
                continue
            }
            val inner = input.substring(i + 1, close)
            out += TexToken.Group(parseTex(inner))
            i = close + 1
            continue
        }

        // Stray `}` with no opener — emit as literal text so we don't silently drop it.
        if (c == '}') {
            out += TexToken.Text("}")
            i++
            continue
        }

        // Superscript / subscript — `^` or `_` followed by either a `{...}` group or a single
        // token (one char, one command, or a nested `^`/`_`). With no target (end of input),
        // degrade to a literal `^` / `_`.
        if (c == '^' || c == '_') {
            if (i + 1 >= len) {
                out += TexToken.Text(c.toString())
                i++
                continue
            }
            val (target, consumed) = parseScriptTarget(input, i + 1)
            if (consumed == 0) {
                // Couldn't form a target — degrade to literal text.
                out += TexToken.Text(c.toString())
                i++
                continue
            }
            val tok = if (c == '^') TexToken.Superscript(target) else TexToken.Subscript(target)
            out += tok
            i += 1 + consumed
            continue
        }

        // Plain run — accumulate until we hit a special char, so we emit one [Text] per run.
        val runEnd = nextSpecial(input, i + 1)
        out += TexToken.Text(input.substring(i, runEnd))
        i = runEnd
    }

    return mergeAdjacentText(out)
}

/**
 * Parses a `^`/`_` target starting at [start]. Returns the parsed target tokens and the number
 * of input characters consumed. Target is one of:
 *  - `{ ... }` group → recurses into [parseTex] (degrades to literal `{` if unclosed)
 *  - a single `\command`
 *  - a single character
 */
private fun parseScriptTarget(input: String, start: Int): Pair<List<TexToken>, Int> {
    if (start >= input.length) return emptyList<TexToken>() to 0
    val c = input[start]

    // Braced target — if unclosed, degrade to literal `{` (consumed = 1, parsed as Text).
    if (c == '{') {
        val close = findMatchingBrace(input, start + 1)
        if (close < 0) {
            return listOf(TexToken.Text("{")) to 1
        }
        val inner = input.substring(start + 1, close)
        return parseTex(inner) to (close - start + 1)
    }

    // Command target.
    if (c == '\\') {
        if (start + 1 >= input.length) {
            // Dangling `\` — treat as literal `\`.
            return listOf(TexToken.Text("\\")) to 1
        }
        val next = input[start + 1]
        if (next.isLetter()) {
            var j = start + 1
            while (j < input.length && input[j].isLetter()) j++
            val name = input.substring(start + 1, j)
            return listOf(TexToken.Command(name)) to (j - start)
        }
        return listOf(TexToken.Command(next.toString())) to 2
    }

    // Single character target.
    return listOf(TexToken.Text(c.toString())) to 1
}

/** Finds the matching `}` for the `{` whose contents start at [from]. -1 if none. */
private fun findMatchingBrace(input: String, from: Int): Int {
    var depth = 1
    var i = from
    val len = input.length
    while (i < len) {
        when (input[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return i
            }
            '\\' -> i++ // skip the escaped char (e.g. `\{`)
        }
        i++
    }
    return -1
}

private fun nextSpecial(s: String, from: Int): Int {
    var j = from
    val len = s.length
    while (j < len) {
        val c = s[j]
        // `(`, `)`, `[`, `]` are delimiter chars — breaking them out as standalone Text tokens
        // lets `\left( ... \right)` parse cleanly (the delimiter is its own token, not merged
        // into a following text run).
        if (c == '\\' || c == '{' || c == '}' || c == '^' || c == '_' || c == '%' ||
            c == '(' || c == ')' || c == '[' || c == ']'
        ) break
        j++
    }
    return j
}

private fun mergeAdjacentText(tokens: List<TexToken>): List<TexToken> {
    if (tokens.size < 2) return tokens
    val out = mutableListOf<TexToken>()
    for (tok in tokens) {
        val last = out.lastOrNull()
        if (last is TexToken.Text && tok is TexToken.Text) {
            out[out.lastIndex] = TexToken.Text(last.text + tok.text)
        } else {
            out += tok
        }
    }
    return out
}
