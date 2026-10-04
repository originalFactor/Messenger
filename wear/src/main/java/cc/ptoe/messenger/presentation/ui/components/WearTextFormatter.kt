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
 * - Folds <think> blocks into compact thought indicators
 * - Replaces verbose <tool_call> JSON blocks with compact tool badges
 * - Avoids full desktop markdown typesetting overhead on watch processors
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

    fun format(content: String, isPending: Boolean): String {
        if (content.isBlank()) {
            return if (isPending) "Thinking..." else "No response."
        }

        // If currently streaming inside an unclosed think block:
        if (isPending && content.contains("<think", ignoreCase = true) && !content.contains("</think", ignoreCase = true)) {
            val thought = content.replace(Regex("<think(?:ing)?(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE), "").trim()
            return if (thought.isBlank()) "Thinking..." else "💭 $thought"
        }

        var text = content
        if (text.contains("<think", ignoreCase = true)) {
            text = text.replace(closedThink, "").replace(unclosedThink, "").trim()
            if (text.isBlank()) {
                return if (isPending) "Thinking..." else "💭 Thought complete."
            }
        }

        if (text.contains("<tool_call", ignoreCase = true)) {
            text = text.replace(toolCallRegex) { match ->
                val raw = match.value
                val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1) ?: "tool"
                "🔧 [$name]\n"
            }.trim()
        }

        return text.ifBlank { if (isPending) "Thinking..." else "No response." }
    }
}
