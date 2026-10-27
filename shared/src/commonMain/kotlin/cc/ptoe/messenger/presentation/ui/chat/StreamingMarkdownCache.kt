/*
 * Copyright 2026 originalFactor
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

package cc.ptoe.messenger.presentation.ui.chat

/**
 * Block cache for a streaming message.
 *
 * The Compose (Desktop/Web) renderers re-parse markdown from the accumulated
 * text, and that text changes on every token. Parsing the WHOLE response each
 * time is O(n²) plus a cross-language call per token, which is visible jank on
 * a long answer.
 *
 * Only complete lines can be parsed anyway: a trailing partial line (the token
 * being received) would produce a half-rendered block that flickers as it
 * grows. So the cache keeps the block list for the last COMPLETE-LINE prefix,
 * re-parses only the lines added since, and appends the in-progress tail as a
 * plain paragraph. Per token that is O(new text), and the markdown parse runs
 * only when a line completes.
 */
internal class StreamingMarkdownCache {
    private var prefixLines: List<String> = emptyList()
    private var blocks: List<ChatBlock> = emptyList()

    /**
     * The Rust core's markdown parser is the ONLY markdown parser — there is no
     * Kotlin implementation behind it. It runs at line boundaries, so the
     * cross-language call is per completed line, not per token.
     */
    private fun parsePrefix(markdown: String): List<ChatBlock> =
        ChatDocumentParser.parseMarkdown(markdown, baseId = 1L)

    fun blocksFor(markdown: String): List<ChatBlock> {
        val lines = markdown.lines()
        // The last line is still being typed unless the text ends with a
        // newline, in which case every line is complete.
        val complete = if (markdown.endsWith('\n')) lines.size else lines.size - 1
        val stable = lines.subList(0, complete.coerceAtLeast(0))

        if (stable.size < prefixLines.size || stable != prefixLines.take(stable.size)) {
            // Not an extension of what we cached (edited / rewound): restart.
            prefixLines = emptyList()
            blocks = emptyList()
        }
        if (stable.size > prefixLines.size) {
            prefixLines = stable
            blocks = parsePrefix(stable.joinToString("\n"))
        }

        val tail = if (complete < lines.size) lines.last() else ""
        if (tail.isEmpty()) return blocks
        // The in-progress line renders as a paragraph that the real block
        // replaces once its newline arrives.
        return blocks + ChatBlock.Paragraph(
            id = Long.MAX_VALUE,
            text = tail,
            inlines = listOf(ChatInline.Text(tail)),
            isFinalized = false
        )
    }
}
