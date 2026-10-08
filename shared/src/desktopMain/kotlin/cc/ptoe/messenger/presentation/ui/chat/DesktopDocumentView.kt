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

package cc.ptoe.messenger.presentation.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.ptoe.messenger.domain.tool.TerminalTool
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.tool_card_failed
import cc.ptoe.messenger.generated.resources.tool_card_result_label
import cc.ptoe.messenger.generated.resources.tool_card_running
import cc.ptoe.messenger.generated.resources.tool_card_success
import cc.ptoe.messenger.generated.resources.tool_name_terminal
import cc.ptoe.messenger.presentation.ui.components.toolIcon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.compose.resources.stringResource

sealed class DesktopBlock {
    abstract val id: Long
    abstract val isFinalized: Boolean

    data class Paragraph(
        override val id: Long,
        val text: String,
        val inlines: List<DesktopInline>,
        override val isFinalized: Boolean
    ) : DesktopBlock()

    data class Heading(
        override val id: Long,
        val level: Int,
        val text: String,
        override val isFinalized: Boolean
    ) : DesktopBlock()

    data class Code(
        override val id: Long,
        val language: String?,
        val code: String,
        override val isFinalized: Boolean
    ) : DesktopBlock()

    data class Math(
        override val id: Long,
        val formula: String,
        override val isFinalized: Boolean
    ) : DesktopBlock()

    data class Think(
        override val id: Long,
        val content: String,
        override val isFinalized: Boolean
    ) : DesktopBlock()

    data class ToolCall(
        override val id: Long,
        val callId: String,
        val name: String,
        val arguments: String,
        val output: String?,
        val isError: Boolean,
        override val isFinalized: Boolean
    ) : DesktopBlock()

    data class Quote(
        override val id: Long,
        val text: String,
        val inlines: List<DesktopInline>,
        override val isFinalized: Boolean
    ) : DesktopBlock()

    data class Table(
        override val id: Long,
        val head: List<String>,
        val rows: List<List<String>>,
        override val isFinalized: Boolean
    ) : DesktopBlock()

    data class ListItem(
        val indent: Int,
        val ordered: Boolean,
        val number: Int,
        val task: Boolean? = null,
        val text: String
    )

    data class ListBlock(
        override val id: Long,
        val items: List<ListItem>,
        override val isFinalized: Boolean
    ) : DesktopBlock()

    data class Divider(override val id: Long) : DesktopBlock() {
        override val isFinalized: Boolean = true
    }
}

sealed class DesktopInline {
    data class Text(val text: String) : DesktopInline()
    data class Bold(val text: String) : DesktopInline()
    data class Italic(val text: String) : DesktopInline()
    data class Code(val code: String) : DesktopInline()
    data class Math(val formula: String) : DesktopInline()
}

object DesktopDocumentParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseBlocksJson(blocksJson: String): List<DesktopBlock> {
        val array = try {
            json.parseToJsonElement(blocksJson) as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            parseBlockObject(obj)
        }
    }

    fun parseBlockObject(obj: JsonObject): DesktopBlock? {
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: return null
        val id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L
        val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "finalized"
        val isFinalized = status == "finalized"

        return when (kind) {
            "paragraph" -> {
                val inlines = parseInlinesJson(obj["inlines"]?.jsonArray)
                DesktopBlock.Paragraph(id, plainInlinesText(inlines), inlines, isFinalized)
            }
            "heading" -> {
                val level = obj["level"]?.jsonPrimitive?.intOrNull ?: 1
                val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                DesktopBlock.Heading(id, level, text, isFinalized)
            }
            "code_block" -> {
                val lang = obj["language"]?.jsonPrimitive?.contentOrNull
                val code = obj["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
                DesktopBlock.Code(id, lang, code, isFinalized)
            }
            "math" -> {
                val formula = obj["formula"]?.jsonPrimitive?.contentOrNull.orEmpty()
                DesktopBlock.Math(id, formula, isFinalized)
            }
            "think" -> {
                val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                DesktopBlock.Think(id, content, isFinalized)
            }
            "tool_call" -> {
                val callId = obj["call_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                val arguments = obj["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val output = obj["output"]?.jsonPrimitive?.contentOrNull
                val isError = obj["is_error"]?.jsonPrimitive?.booleanOrNull ?: false
                DesktopBlock.ToolCall(id, callId, name, arguments, output, isError, isFinalized)
            }
            "quote" -> {
                // Quote content carries parsed inlines (bold/italic/code/math)
                val inlines = parseInlinesJson(obj["inlines"]?.jsonArray)
                DesktopBlock.Quote(id, plainInlinesText(inlines), inlines, isFinalized)
            }
            "table" -> {
                val head = obj["head"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull.orEmpty() } ?: emptyList()
                val rows = obj["rows"]?.jsonArray?.map { row ->
                    (row as? JsonArray)?.map { cell -> cell.jsonPrimitive.contentOrNull.orEmpty() } ?: emptyList()
                } ?: emptyList()
                DesktopBlock.Table(id, head, rows, isFinalized)
            }
            "list" -> {
                val items = obj["items"]?.jsonArray?.mapNotNull { item ->
                    val itemObj = item as? JsonObject ?: return@mapNotNull null
                    val plain = itemObj["inlines"]?.jsonArray?.joinToString("") { inline ->
                        val o = inline as? JsonObject ?: return@joinToString ""
                        when (o["type"]?.jsonPrimitive?.contentOrNull) {
                            "code" -> o["code"]?.jsonPrimitive?.contentOrNull ?: ""
                            "math" -> o["formula"]?.jsonPrimitive?.contentOrNull ?: ""
                            else -> o["text"]?.jsonPrimitive?.contentOrNull ?: ""
                        }
                    }.orEmpty()
                    DesktopBlock.ListItem(
                        indent = itemObj["indent"]?.jsonPrimitive?.intOrNull ?: 0,
                        ordered = itemObj["ordered"]?.jsonPrimitive?.booleanOrNull ?: false,
                        number = itemObj["number"]?.jsonPrimitive?.intOrNull ?: 0,
                        task = itemObj["task"]?.jsonPrimitive?.booleanOrNull,
                        text = plain
                    )
                } ?: emptyList()
                DesktopBlock.ListBlock(id, items, isFinalized)
            }
            "divider" -> DesktopBlock.Divider(id)
            else -> null
        }
    }

    private val inlineTokenRegex = Regex("""(`[^`]+`|\*\*[^*]+\*\*|__[^_]+__|\*[^*]+\*|_[^_]+_|\$[^$]+\$)""")

    /** Inlines from the Rust core wire format: text-bearing types carry `text`,
     *  code carries `code`, math carries `formula`. */
    private fun parseInlinesJson(array: JsonArray?): List<DesktopInline> =
        array?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val text = o["text"]?.jsonPrimitive?.contentOrNull ?: ""
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "bold" -> DesktopInline.Bold(text)
                "italic" -> DesktopInline.Italic(text)
                "code" -> DesktopInline.Code(o["code"]?.jsonPrimitive?.contentOrNull ?: "")
                "math" -> DesktopInline.Math(o["formula"]?.jsonPrimitive?.contentOrNull ?: "")
                else -> DesktopInline.Text(text)
            }
        } ?: emptyList()

    /** Flatten parsed inlines to their human-visible plain text. */
    private fun plainInlinesText(inlines: List<DesktopInline>): String =
        inlines.joinToString("") {
            when (it) {
                is DesktopInline.Text -> it.text
                is DesktopInline.Bold -> it.text
                is DesktopInline.Italic -> it.text
                is DesktopInline.Code -> it.code
                is DesktopInline.Math -> it.formula
            }
        }

    fun parseInlines(text: String): List<DesktopInline> {
        if (text.isEmpty()) return emptyList()
        val inlines = mutableListOf<DesktopInline>()
        var lastIndex = 0

        for (match in inlineTokenRegex.findAll(text)) {
            val range = match.range
            if (range.first > lastIndex) {
                inlines.add(DesktopInline.Text(text.substring(lastIndex, range.first)))
            }
            val token = match.value
            val inline = when {
                token.startsWith("`") && token.endsWith("`") ->
                    DesktopInline.Code(token.removeSurrounding("`"))
                (token.startsWith("**") && token.endsWith("**")) || (token.startsWith("__") && token.endsWith("__")) ->
                    DesktopInline.Bold(token.substring(2, token.length - 2))
                (token.startsWith("*") && token.endsWith("*")) || (token.startsWith("_") && token.endsWith("_")) ->
                    DesktopInline.Italic(token.substring(1, token.length - 1))
                token.startsWith("$") && token.endsWith("$") ->
                    DesktopInline.Math(token.removeSurrounding("$"))
                else -> DesktopInline.Text(token)
            }
            inlines.add(inline)
            lastIndex = range.last + 1
        }
        if (lastIndex < text.length) {
            inlines.add(DesktopInline.Text(text.substring(lastIndex)))
        }
        return inlines
    }

    fun parseMarkdown(markdown: String, baseId: Long = 1L): List<DesktopBlock> {
        if (markdown.isBlank()) return emptyList()

        val blocks = mutableListOf<DesktopBlock>()
        var currentId = baseId

        val lines = markdown.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // 1. Think block: <think> ... </think> or unclosed <think>
            if (line.trimStart().startsWith("<think>")) {
                val thinkBuilder = StringBuilder()
                val firstLineContent = line.trimStart().removePrefix("<think>")
                if (firstLineContent.contains("</think>")) {
                    val content = firstLineContent.substringBefore("</think>")
                    blocks.add(DesktopBlock.Think(id = currentId++, content = content.trim(), isFinalized = true))
                    i++
                    continue
                }
                thinkBuilder.append(firstLineContent)
                var closed = false
                i++
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.contains("</think>")) {
                        val before = l.substringBefore("</think>")
                        if (before.isNotBlank()) thinkBuilder.append("\n").append(before)
                        closed = true
                        i++
                        break
                    } else {
                        thinkBuilder.append("\n").append(l)
                        i++
                    }
                }
                blocks.add(
                    DesktopBlock.Think(
                        id = currentId++,
                        content = thinkBuilder.toString().trim(),
                        isFinalized = closed
                    )
                )
                continue
            }

            // 2. Fenced code block: ```[lang] ... ```
            if (line.trimStart().startsWith("```")) {
                val lang = line.trimStart().removePrefix("```").trim().ifBlank { null }
                val codeBuilder = StringBuilder()
                var closed = false
                i++
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.trimStart().startsWith("```")) {
                        closed = true
                        i++
                        break
                    } else {
                        if (codeBuilder.isNotEmpty()) codeBuilder.append("\n")
                        codeBuilder.append(l)
                        i++
                    }
                }
                blocks.add(
                    DesktopBlock.Code(
                        id = currentId++,
                        language = lang,
                        code = codeBuilder.toString(),
                        isFinalized = closed
                    )
                )
                continue
            }

            // 3. Display Math block: $$ ... $$
            if (line.trimStart().startsWith("$$")) {
                val mathBuilder = StringBuilder()
                val first = line.trimStart().removePrefix("$$")
                if (first.contains("$$") && first.endsWith("$$")) {
                    val formula = first.removeSuffix("$$").trim()
                    blocks.add(DesktopBlock.Math(id = currentId++, formula = formula, isFinalized = true))
                    i++
                    continue
                }
                mathBuilder.append(first)
                var closed = false
                i++
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.contains("$$")) {
                        val part = l.substringBefore("$$")
                        if (part.isNotBlank()) mathBuilder.append("\n").append(part)
                        closed = true
                        i++
                        break
                    } else {
                        if (mathBuilder.isNotEmpty()) mathBuilder.append("\n")
                        mathBuilder.append(l)
                        i++
                    }
                }
                blocks.add(
                    DesktopBlock.Math(
                        id = currentId++,
                        formula = mathBuilder.toString().trim(),
                        isFinalized = closed
                    )
                )
                continue
            }

            // Blank lines
            if (line.isBlank()) {
                i++
                continue
            }

            // 4. Horizontal rule / divider: ---, ***, ___
            val trimmed = line.trim()
            if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                blocks.add(DesktopBlock.Divider(id = currentId++))
                i++
                continue
            }

            // 5. Headings: #, ##, ###, ####
            if (line.startsWith("#")) {
                val level = line.takeWhile { it == '#' }.length
                if (level in 1..6 && line.length > level && line[level] == ' ') {
                    val text = line.substring(level + 1).trim()
                    blocks.add(DesktopBlock.Heading(id = currentId++, level = level, text = text, isFinalized = true))
                    i++
                    continue
                }
            }

            // 6. Blockquote: > text (inline markup parsed like paragraphs)
            if (line.trimStart().startsWith(">")) {
                val quoteBuilder = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    val quoteLine = lines[i].trimStart().removePrefix(">").trimStart()
                    if (quoteBuilder.isNotEmpty()) quoteBuilder.append("\n")
                    quoteBuilder.append(quoteLine)
                    i++
                }
                val quoteText = quoteBuilder.toString()
                val quoteInlines = parseInlines(quoteText)
                blocks.add(
                    DesktopBlock.Quote(
                        id = currentId++,
                        text = plainInlinesText(quoteInlines),
                        inlines = quoteInlines,
                        isFinalized = true
                    )
                )
                continue
            }

            // 7. Table: | cell | cell |
            if (line.trim().startsWith("|") && line.trim().endsWith("|") && i + 1 < lines.size && lines[i + 1].trim().startsWith("|") && lines[i + 1].contains("-")) {
                val headerCells = line.trim().removeSurrounding("|", "|").split("|").map { it.trim() }
                i += 2 // skip header and separator (|---|---|)
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|") && lines[i].trim().endsWith("|")) {
                    val rowCells = lines[i].trim().removeSurrounding("|", "|").split("|").map { it.trim() }
                    rows.add(rowCells)
                    i++
                }
                blocks.add(DesktopBlock.Table(id = currentId++, head = headerCells, rows = rows, isFinalized = true))
                continue
            }

            // 8. Lists: - item, * item, + item, or 1. item
            val listMatch = matchListItem(line)
            if (listMatch != null) {
                val items = mutableListOf<DesktopBlock.ListItem>()
                while (i < lines.size) {
                    val curMatch = matchListItem(lines[i]) ?: break
                    items.add(curMatch)
                    i++
                }
                blocks.add(DesktopBlock.ListBlock(id = currentId++, items = items, isFinalized = true))
                continue
            }

            // 9. Paragraph: accumulate consecutive non-empty lines
            val paragraphBuilder = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() &&
                !lines[i].trimStart().startsWith("<think>") &&
                !lines[i].trimStart().startsWith("```") &&
                !lines[i].trimStart().startsWith("$$") &&
                !lines[i].trimStart().startsWith("#") &&
                !lines[i].trimStart().startsWith(">") &&
                matchListItem(lines[i]) == null &&
                !(lines[i].trim() == "---" || lines[i].trim() == "***" || lines[i].trim() == "___") &&
                !(lines[i].trim().startsWith("|") && lines[i].trim().endsWith("|"))) {
                if (paragraphBuilder.isNotEmpty()) paragraphBuilder.append("\n")
                paragraphBuilder.append(lines[i])
                i++
            }
            val paraText = paragraphBuilder.toString().trim()
            if (paraText.isNotEmpty()) {
                val inlines = parseInlines(paraText)
                blocks.add(
                    DesktopBlock.Paragraph(
                        id = currentId++,
                        text = paraText,
                        inlines = inlines,
                        isFinalized = true
                    )
                )
            }
        }

        return blocks
    }

    private fun matchListItem(line: String): DesktopBlock.ListItem? {
        val indent = (line.takeWhile { it == ' ' || it == '\t' }.length / 2).coerceAtMost(4)
        val trimmed = line.trimStart()
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            val (task, text) = splitTaskMarker(trimmed.substring(2).trim())
            return DesktopBlock.ListItem(
                indent = indent,
                ordered = false,
                number = 0,
                task = task,
                text = text
            )
        }
        val digitPrefix = trimmed.takeWhile { it.isDigit() }
        if (digitPrefix.isNotEmpty() && trimmed.length > digitPrefix.length + 1) {
            val num = digitPrefix.toIntOrNull() ?: 0
            val delim = trimmed[digitPrefix.length]
            if ((delim == '.' || delim == ')') && trimmed[digitPrefix.length + 1] == ' ') {
                val (task, text) = splitTaskMarker(trimmed.substring(digitPrefix.length + 2).trim())
                return DesktopBlock.ListItem(
                    indent = indent,
                    ordered = true,
                    number = num,
                    task = task,
                    text = text
                )
            }
        }
        return null
    }

    /** GFM task-list marker: `[ ]` unchecked / `[x]`-`[X]` checked, followed by
     *  whitespace (or ending the item). Anything else stays literal text. */
    private fun splitTaskMarker(text: String): Pair<Boolean?, String> = when {
        text.startsWith("[ ] ") -> false to text.removePrefix("[ ] ").trim()
        text.startsWith("[x] ") || text.startsWith("[X] ") -> true to text.substring(4).trim()
        text == "[ ]" -> false to ""
        text == "[x]" || text == "[X]" -> true to ""
        else -> null to text
    }
}

fun DesktopBlock.withId(newId: Long): DesktopBlock = when (this) {
    is DesktopBlock.Paragraph -> copy(id = newId)
    is DesktopBlock.Heading -> copy(id = newId)
    is DesktopBlock.Code -> copy(id = newId)
    is DesktopBlock.Math -> copy(id = newId)
    is DesktopBlock.Think -> copy(id = newId)
    is DesktopBlock.ToolCall -> copy(id = newId)
    is DesktopBlock.Quote -> copy(id = newId)
    is DesktopBlock.Table -> copy(id = newId)
    is DesktopBlock.ListBlock -> copy(id = newId)
    is DesktopBlock.Divider -> this
}

/**
 * Desktop Document View (TARGET.md §14).
 *
 * Renders structured Document AST blocks in Compose Desktop with [key] stability:
 * immutable, finalized blocks stay frozen and do not recompose when new streaming
 * tokens arrive.
 */
@Composable
fun DesktopDocumentView(
    blocks: List<DesktopBlock>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (block in blocks) {
            key(block.id) {
                when (block) {
                    is DesktopBlock.Paragraph -> RenderParagraph(block)
                    is DesktopBlock.Heading -> RenderHeading(block)
                    is DesktopBlock.Code -> RenderCodeBlock(block)
                    is DesktopBlock.Math -> RenderMathBlock(block)
                    is DesktopBlock.Think -> RenderThinkBlock(block)
                    is DesktopBlock.ToolCall -> RenderToolCall(block)
                    is DesktopBlock.Table -> RenderTable(block)
                    is DesktopBlock.ListBlock -> RenderList(block)
                    is DesktopBlock.Quote -> RenderQuote(block)
                    is DesktopBlock.Divider -> HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RenderParagraph(block: DesktopBlock.Paragraph) {
    val annotated = remember(block.inlines, block.text) {
        buildInlineAnnotatedString(block.inlines, block.text)
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
private fun RenderHeading(block: DesktopBlock.Heading) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
        2 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        3 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
        else -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    }
    Text(
        text = block.text,
        style = style,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun RenderCodeBlock(block: DesktopBlock.Code) {
    val clipboardManager = LocalClipboardManager.current
    val lang = block.language?.ifBlank { "code" } ?: "code"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E1E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2D2D2D))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    tint = Color(0xFF9E9E9E),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = lang,
                    color = Color(0xFFD4D4D4),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(block.code))
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy code",
                    tint = Color(0xFFB0B0B0),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Text(
            text = block.code,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = Color(0xFFD4D4D4),
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun RenderMathBlock(block: DesktopBlock.Math) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$$ ${block.formula} $$",
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            color = Color(0xFF1565C0),
            fontSize = 16.sp
        )
    }
}

@Composable
private fun RenderThinkBlock(block: DesktopBlock.Think) {
    var expanded by remember { mutableStateOf(!block.isFinalized) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (block.isFinalized) "Thought process" else "Thinking...",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 180f else 0f)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = block.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun RenderToolCall(block: DesktopBlock.ToolCall) {
    var expanded by remember(block.id) { mutableStateOf(false) }
    val isRunning = !block.isFinalized
    val command = remember(block.arguments) {
        TerminalTool.parseCommand(block.arguments) ?: block.arguments.ifBlank { null }
    }
    val containerColor = when {
        isRunning -> MaterialTheme.colorScheme.secondaryContainer
        block.isError -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .animateContentSize()
            .clickable { expanded = !expanded }
            .padding(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = toolIcon(block.name),
                    contentDescription = null,
                    tint = if (block.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = toolDisplayName(block.name),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        isRunning -> stringResource(Res.string.tool_card_running)
                        block.isError -> stringResource(Res.string.tool_card_failed)
                        else -> stringResource(Res.string.tool_card_success)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        isRunning -> MaterialTheme.colorScheme.primary
                        block.isError -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (expanded) 180f else 0f)
                )
            }
        }
        if (!expanded && !command.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = command,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                if (!command.isNullOrBlank()) {
                    Text(
                        text = command,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                            .padding(6.dp)
                    )
                }
                if (block.output != null && block.output.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(Res.string.tool_card_result_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = block.output,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                            .padding(6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun toolDisplayName(name: String): String = when (name) {
    TerminalTool.TOOL_NAME -> stringResource(Res.string.tool_name_terminal)
    else -> name
}

/** Inline spans → AnnotatedString, shared by paragraph and blockquote rendering. */
private fun buildInlineAnnotatedString(inlines: List<DesktopInline>, plainText: String): AnnotatedString =
    if (inlines.isEmpty()) {
        AnnotatedString(plainText)
    } else {
        buildAnnotatedString {
            for (inline in inlines) {
                when (inline) {
                    is DesktopInline.Text -> append(inline.text)
                    is DesktopInline.Bold -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(inline.text)
                        }
                    }
                    is DesktopInline.Italic -> {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(inline.text)
                        }
                    }
                    is DesktopInline.Code -> {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = Color(0x1F000000)
                            )
                        ) {
                            append(" ${inline.code} ")
                        }
                    }
                    is DesktopInline.Math -> {
                        withStyle(
                            SpanStyle(
                                fontStyle = FontStyle.Italic,
                                color = Color(0xFF1565C0)
                            )
                        ) {
                            append("$${inline.formula}$")
                        }
                    }
                }
            }
        }
    }

@Composable
private fun RenderQuote(block: DesktopBlock.Quote) {
    val annotated = remember(block.inlines, block.text) {
        buildInlineAnnotatedString(block.inlines, block.text)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(24.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Composable
private fun RenderList(block: DesktopBlock.ListBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        block.items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = (item.indent.coerceAtMost(4) * 18).dp,
                        top = 2.dp,
                        bottom = 2.dp
                    ),
                verticalAlignment = Alignment.Top
            ) {
                if (item.task != null) {
                    val boxShape = RoundedCornerShape(4.dp)
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .padding(top = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(boxShape)
                                .then(
                                    if (item.task) {
                                        Modifier.background(MaterialTheme.colorScheme.primary)
                                    } else {
                                        Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.outlineVariant,
                                            boxShape
                                        )
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (item.task) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = if (item.ordered) "${item.number}." else "•",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun RenderTable(block: DesktopBlock.Table) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
    ) {
        if (block.head.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                block.head.forEach { cell ->
                    Text(
                        text = cell,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
            }
        }
        block.rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEach { cell ->
                    Text(
                        text = cell,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
            }
            if (index != block.rows.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(borderColor)
                )
            }
        }
    }
}
