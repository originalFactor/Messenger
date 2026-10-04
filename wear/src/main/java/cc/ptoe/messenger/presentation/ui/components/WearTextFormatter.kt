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

package cc.ptoe.messenger.presentation.ui.components

/**
 * Compact document and text formatter for Wear OS (TARGET.md §11).
 *
 * Wear displays must stay minimal and low-overhead:
 * - Ultra-fast short-circuit path for plain text avoiding regular expression passes
 * - Folds <think> blocks into compact thought summaries (💭 ...)
 * - Compacts <tool_call> JSON blocks into badges (🔧 [name])
 * - Replaces long code fences and LaTeX display blocks with concise on-wrist indicators
 * - Conserves memory allocations and CPU battery life on wearable chipsets
 */
object WearTextFormatter {

    private val closedThink = Regex(
        "<think(?:ing)?(?:\\s[^>]*)?>[\\s\\S]*?</think(?:ing)?>",
        RegexOption.IGNORE_CASE
    )
    private val unclosedThink = Regex(
        "<think(?:ing)?(?:\\s[^>]*)?>[\\s\\S]*$",
        RegexOption.IGNORE_CASE
    )
    private val toolCallRegex = Regex(
        "<tool_call(?:\\s[^>]*)?>[\\s\\S]*?</tool_call>",
        RegexOption.IGNORE_CASE
    )
    private val codeBlockRegex = Regex(
        "```([a-zA-Z0-9_-]*)\\s*\\n([\\s\\S]*?)```"
    )
    private val mathBlockRegex = Regex(
        "\\$\\$([\\s\\S]*?)\\$\\$"
    )

    fun format(content: String, isPending: Boolean): String {
        if (content.isBlank()) {
            return if (isPending) "Thinking..." else "No response."
        }

        // Fast-path: If content contains no markup indicators, return directly with 0 allocation
        if (!content.contains('<') && !content.contains("```") && !content.contains("$$")) {
            return content
        }

        var text = content

        // 1. Live stream inside an unclosed <think> block
        if (isPending && text.contains("<think", ignoreCase = true) && !text.contains("</think", ignoreCase = true)) {
            val thought = text.replace(Regex("<think(?:ing)?(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE), "").trim()
            if (thought.isBlank()) {
                return "Thinking..."
            }
            // Keep recent preview (up to 90 characters) so wearable layout doesn't thrash
            val preview = if (thought.length > 90) "..." + thought.takeLast(85) else thought
            return "💭 $preview"
        }

        // 2. Strip closed and trailing think blocks from main body
        if (text.contains("<think", ignoreCase = true)) {
            text = text.replace(closedThink, "").replace(unclosedThink, "").trim()
            if (text.isBlank()) {
                return if (isPending) "Thinking..." else "💭 Thought complete."
            }
        }

        // 3. Compact <tool_call> JSON blocks into neat status badges
        if (text.contains("<tool_call", ignoreCase = true)) {
            text = text.replace(toolCallRegex) { match ->
                val raw = match.value
                val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1) ?: "tool"
                val isError = raw.contains("\"is_error\"\\s*:\\s*true".toRegex())
                val icon = if (isError) "⚠️" else "🔧"
                "$icon [$name]\n"
            }.trim()
        }

        // 4. Compact oversized code blocks on wearable display
        if (text.contains("```")) {
            text = text.replace(codeBlockRegex) { match ->
                val lang = match.groupValues[1].ifBlank { "code" }
                val code = match.groupValues[2].trim()
                val lineCount = code.lines().size
                if (lineCount > 4) {
                    val previewLines = code.lines().take(3).joinToString("\n")
                    "💻 [$lang: $lineCount lines]\n$previewLines\n..."
                } else {
                    "💻 [$lang]\n$code"
                }
            }
        }

        // 5. Compact math formulas
        if (text.contains("$$")) {
            text = text.replace(mathBlockRegex) { match ->
                val formula = match.groupValues[1].trim()
                "📐 $formula"
            }
        }

        return text.ifBlank { if (isPending) "Thinking..." else "No response." }
    }
}
