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

package cc.ptoe.messenger.renderer

import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan

/**
 * Lightweight regex code highlighter for the native renderer: strings, line/
 * block comments, numbers and language keywords over a small language table.
 * Semantic colors come from the host MaterialTheme (primary/secondary/tertiary/
 * onSurfaceVariant) so highlighting follows light/dark and dynamic color.
 * Strings are matched FIRST and blanked, so `#`/`//` inside them (colors,
 * URLs) never masquerade as comments; keywords/numbers inside masked regions
 * stay plain.
 */
internal object CodeHighlighter {

    data class Colors(
        val keyword: Int,
        val string: Int,
        val number: Int,
        val comment: Int
    )

    private val KEYWORDS: Map<String, Set<String>> = mapOf(
        "python" to setOf(
            "def", "class", "return", "if", "elif", "else", "for", "while", "in", "not",
            "and", "or", "import", "from", "as", "with", "try", "except", "finally",
            "raise", "lambda", "yield", "pass", "break", "continue", "global", "nonlocal",
            "assert", "del", "async", "await", "True", "False", "None", "print"
        ),
        "javascript" to setOf(
            "function", "return", "if", "else", "for", "while", "do", "switch", "case",
            "break", "continue", "const", "let", "var", "class", "extends", "new", "this",
            "import", "export", "from", "default", "try", "catch", "finally", "throw",
            "typeof", "instanceof", "await", "async", "yield", "true", "false", "null", "undefined"
        ),
        "kotlin" to setOf(
            "fun", "val", "var", "class", "object", "interface", "return", "if", "else",
            "for", "while", "do", "when", "is", "in", "as", "import", "package", "private",
            "public", "internal", "protected", "override", "open", "abstract", "sealed",
            "data", "companion", "init", "constructor", "try", "catch", "finally", "throw",
            "suspend", "lateinit", "by", "get", "set", "true", "false", "null", "this", "super"
        ),
        "java" to setOf(
            "public", "private", "protected", "class", "interface", "enum", "record",
            "static", "final", "void", "return", "if", "else", "for", "while", "do",
            "switch", "case", "break", "continue", "new", "this", "super", "extends",
            "implements", "import", "package", "try", "catch", "finally", "throw", "throws",
            "abstract", "synchronized", "volatile", "true", "false", "null"
        ),
        "rust" to setOf(
            "fn", "let", "mut", "const", "struct", "enum", "trait", "impl", "pub", "use",
            "mod", "return", "if", "else", "match", "for", "in", "while", "loop", "break",
            "continue", "async", "await", "move", "ref", "where", "unsafe", "dyn", "crate",
            "self", "Self", "super", "true", "false"
        ),
        "go" to setOf(
            "func", "return", "if", "else", "for", "range", "switch", "case", "default",
            "break", "continue", "go", "defer", "chan", "select", "package", "import",
            "struct", "interface", "map", "type", "const", "var", "nil", "true", "false"
        ),
        "bash" to setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case",
            "esac", "function", "return", "local", "export", "echo", "exit", "in"
        ),
        "sql" to setOf(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
            "DELETE", "CREATE", "TABLE", "DROP", "ALTER", "JOIN", "LEFT", "RIGHT",
            "INNER", "OUTER", "ON", "GROUP", "BY", "ORDER", "LIMIT", "HAVING", "AS",
            "AND", "OR", "NOT", "NULL", "DISTINCT", "COUNT", "INDEX"
        )
    )

    private val ALIASES: Map<String, String> = mapOf(
        "py" to "python", "python3" to "python",
        "js" to "javascript", "ts" to "javascript", "typescript" to "javascript",
        "jsx" to "javascript", "tsx" to "javascript", "node" to "javascript",
        "kt" to "kotlin", "kts" to "kotlin",
        "rs" to "rust",
        "golang" to "go",
        "sh" to "bash", "shell" to "bash", "zsh" to "bash", "console" to "bash",
        "c" to "java", "cpp" to "java", "c++" to "java", "cs" to "java", "csharp" to "java",
        "dart" to "java", "swift" to "java", "scala" to "java", "php" to "java",
        "xml" to "javascript", "html" to "javascript", "css" to "javascript",
        "json" to "javascript", "yaml" to "javascript", "yml" to "javascript"
    )

    private val STRING_REGEX = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`")
    private val NUMBER_REGEX = Regex("\\b(?:0[xX][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)[fFlLdDuU]?\\b")
    private val BLOCK_COMMENT_REGEX = Regex("/\\*(?:[^*]|\\*(?!/))*\\*/")
    private val LINE_COMMENT_REGEX = Regex("//[^\\n]*|#[^\\n]*")

    /** Return [code] with keyword/string/number/comment color spans applied. */
    fun highlight(code: String, language: String?, colors: Colors): CharSequence {
        val builder = SpannableStringBuilder(code)
        val masked = BooleanArray(code.length)
        val keywords = normalizeLanguage(language)?.let { KEYWORDS[it] }

        fun apply(regex: Regex, color: Int, blank: Boolean, skipMasked: Boolean) {
            regex.findAll(code).forEach { match ->
                if (skipMasked && masked[match.range.first]) return@forEach
                builder.setSpan(
                    ForegroundColorSpan(color),
                    match.range.first, match.range.last + 1,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (blank) for (i in match.range) masked[i] = true
            }
        }

        apply(STRING_REGEX, colors.string, blank = true, skipMasked = false)
        apply(BLOCK_COMMENT_REGEX, colors.comment, blank = true, skipMasked = true)
        apply(LINE_COMMENT_REGEX, colors.comment, blank = true, skipMasked = true)
        apply(NUMBER_REGEX, colors.number, blank = false, skipMasked = true)
        keywords?.forEach { keyword ->
            apply(Regex("\\b${Regex.escape(keyword)}\\b"), colors.keyword, blank = false, skipMasked = true)
        }
        return builder
    }

    private fun normalizeLanguage(language: String?): String? {
        val raw = language?.trim()?.lowercase() ?: return null
        return ALIASES[raw] ?: if (KEYWORDS.containsKey(raw)) raw else null
    }
}
