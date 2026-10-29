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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
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

sealed class ChatBlock {
    abstract val id: Long
    abstract val isFinalized: Boolean

    /** Raw Markdown handed directly to the Kotlin streaming renderer. */
    data class RawMarkdown(
        override val id: Long,
        val markdown: String,
        override val isFinalized: Boolean,
    ) : ChatBlock()

    data class Paragraph(
        override val id: Long,
        val text: String,
        val inlines: List<ChatInline>,
        override val isFinalized: Boolean
    ) : ChatBlock()

    data class Heading(
        override val id: Long,
        val level: Int,
        val text: String,
        override val isFinalized: Boolean
    ) : ChatBlock()

    data class Code(
        override val id: Long,
        val language: String?,
        val code: String,
        override val isFinalized: Boolean
    ) : ChatBlock()

    data class Math(
        override val id: Long,
        val formula: String,
        override val isFinalized: Boolean
    ) : ChatBlock()

    data class Think(
        override val id: Long,
        val content: String,
        override val isFinalized: Boolean
    ) : ChatBlock()

    data class ToolCall(
        override val id: Long,
        val callId: String,
        val name: String,
        val arguments: String,
        val output: String?,
        val isError: Boolean,
        override val isFinalized: Boolean
    ) : ChatBlock()

    data class Quote(
        override val id: Long,
        val text: String,
        val inlines: List<ChatInline>,
        override val isFinalized: Boolean
    ) : ChatBlock()

    data class Table(
        override val id: Long,
        val head: List<String>,
        val rows: List<List<String>>,
        override val isFinalized: Boolean
    ) : ChatBlock()

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
    ) : ChatBlock()

    data class Divider(override val id: Long) : ChatBlock() {
        override val isFinalized: Boolean = true
    }
}

sealed class ChatInline {
    data class Text(val text: String) : ChatInline()
    data class Bold(val text: String) : ChatInline()
    data class Italic(val text: String) : ChatInline()
    data class Code(val code: String) : ChatInline()
    data class Math(val formula: String) : ChatInline()
}

object ChatDocumentParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseBlocksJson(blocksJson: String): List<ChatBlock> {
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

    fun parseBlockObject(obj: JsonObject): ChatBlock? {
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: return null
        val id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L
        val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "finalized"
        val isFinalized = status == "finalized"

        return when (kind) {
            "paragraph" -> {
                val inlines = parseInlinesJson(obj["inlines"]?.jsonArray)
                ChatBlock.Paragraph(id, plainInlinesText(inlines), inlines, isFinalized)
            }
            "heading" -> {
                val level = obj["level"]?.jsonPrimitive?.intOrNull ?: 1
                val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ChatBlock.Heading(id, level, text, isFinalized)
            }
            "code_block" -> {
                val lang = obj["language"]?.jsonPrimitive?.contentOrNull
                val code = obj["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ChatBlock.Code(id, lang, code, isFinalized)
            }
            "math" -> {
                val formula = obj["formula"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ChatBlock.Math(id, formula, isFinalized)
            }
            "think" -> {
                val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ChatBlock.Think(id, content, isFinalized)
            }
            "tool_call" -> {
                val callId = obj["call_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                val arguments = obj["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val output = obj["output"]?.jsonPrimitive?.contentOrNull
                val isError = obj["is_error"]?.jsonPrimitive?.booleanOrNull ?: false
                ChatBlock.ToolCall(id, callId, name, arguments, output, isError, isFinalized)
            }
            "quote" -> {
                // Quote content carries parsed inlines (bold/italic/code/math)
                val inlines = parseInlinesJson(obj["inlines"]?.jsonArray)
                ChatBlock.Quote(id, plainInlinesText(inlines), inlines, isFinalized)
            }
            "table" -> {
                val head = obj["head"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull.orEmpty() } ?: emptyList()
                val rows = obj["rows"]?.jsonArray?.map { row ->
                    (row as? JsonArray)?.map { cell -> cell.jsonPrimitive.contentOrNull.orEmpty() } ?: emptyList()
                } ?: emptyList()
                ChatBlock.Table(id, head, rows, isFinalized)
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
                    ChatBlock.ListItem(
                        indent = itemObj["indent"]?.jsonPrimitive?.intOrNull ?: 0,
                        ordered = itemObj["ordered"]?.jsonPrimitive?.booleanOrNull ?: false,
                        number = itemObj["number"]?.jsonPrimitive?.intOrNull ?: 0,
                        task = itemObj["task"]?.jsonPrimitive?.booleanOrNull,
                        text = plain
                    )
                } ?: emptyList()
                ChatBlock.ListBlock(id, items, isFinalized)
            }
            "divider" -> ChatBlock.Divider(id)
            else -> null
        }
    }

    /** Inlines from the Rust core wire format: text-bearing types carry `text`,
     *  code carries `code`, math carries `formula`. */
    private fun parseInlinesJson(array: JsonArray?): List<ChatInline> =
        array?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val text = o["text"]?.jsonPrimitive?.contentOrNull ?: ""
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "bold" -> ChatInline.Bold(text)
                "italic" -> ChatInline.Italic(text)
                "code" -> ChatInline.Code(o["code"]?.jsonPrimitive?.contentOrNull ?: "")
                "math" -> ChatInline.Math(o["formula"]?.jsonPrimitive?.contentOrNull ?: "")
                else -> ChatInline.Text(text)
            }
        } ?: emptyList()

    /** Flatten parsed inlines to their human-visible plain text. */
    private fun plainInlinesText(inlines: List<ChatInline>): String =
        inlines.joinToString("") {
            when (it) {
                is ChatInline.Text -> it.text
                is ChatInline.Bold -> it.text
                is ChatInline.Italic -> it.text
                is ChatInline.Code -> it.code
                is ChatInline.Math -> it.formula
            }
        }

    fun parseMarkdown(markdown: String, baseId: Long = 1L): List<ChatBlock> {
        if (markdown.isBlank()) return emptyList()
        return parseMarkdownToBlocks(markdown, baseId)
    }
}

fun ChatBlock.withId(newId: Long): ChatBlock = when (this) {
    is ChatBlock.RawMarkdown -> copy(id = newId)
    is ChatBlock.Paragraph -> copy(id = newId)
    is ChatBlock.Heading -> copy(id = newId)
    is ChatBlock.Code -> copy(id = newId)
    is ChatBlock.Math -> copy(id = newId)
    is ChatBlock.Think -> copy(id = newId)
    is ChatBlock.ToolCall -> copy(id = newId)
    is ChatBlock.Quote -> copy(id = newId)
    is ChatBlock.Table -> copy(id = newId)
    is ChatBlock.ListBlock -> copy(id = newId)
    is ChatBlock.Divider -> this
}

/**
 * Desktop Document View (TARGET.md §14).
 *
 * Renders structured Document AST blocks in Compose Desktop with [key] stability:
 * immutable, finalized blocks stay frozen and do not recompose when new streaming
 * tokens arrive.
 */
@Composable
fun ChatDocumentView(
    blocks: List<ChatBlock>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (block in blocks) {
            key(block.id) {
                when (block) {
                    is ChatBlock.RawMarkdown -> ChatMarkdownText(
                        markdown = block.markdown,
                        isStreaming = !block.isFinalized,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    is ChatBlock.Paragraph -> RenderParagraph(block)
                    is ChatBlock.Heading -> RenderHeading(block)
                    is ChatBlock.Code -> RenderCodeBlock(block)
                    is ChatBlock.Math -> RenderMathBlock(block)
                    is ChatBlock.Think -> RenderThinkBlock(block)
                    is ChatBlock.ToolCall -> RenderToolCall(block)
                    is ChatBlock.Table -> RenderTable(block)
                    is ChatBlock.ListBlock -> RenderList(block)
                    is ChatBlock.Quote -> RenderQuote(block)
                    is ChatBlock.Divider -> HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RenderParagraph(block: ChatBlock.Paragraph) {
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
private fun RenderHeading(block: ChatBlock.Heading) {
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
private fun RenderCodeBlock(block: ChatBlock.Code) {
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
private fun RenderMathBlock(block: ChatBlock.Math) {
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
private fun RenderThinkBlock(block: ChatBlock.Think) {
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
private fun RenderToolCall(block: ChatBlock.ToolCall) {
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
private fun buildInlineAnnotatedString(inlines: List<ChatInline>, plainText: String): AnnotatedString =
    if (inlines.isEmpty()) {
        AnnotatedString(plainText)
    } else {
        buildAnnotatedString {
            for (inline in inlines) {
                when (inline) {
                    is ChatInline.Text -> append(inline.text)
                    is ChatInline.Bold -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(inline.text)
                        }
                    }
                    is ChatInline.Italic -> {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(inline.text)
                        }
                    }
                    is ChatInline.Code -> {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = Color(0x1F000000)
                            )
                        ) {
                            append(" ${inline.code} ")
                        }
                    }
                    is ChatInline.Math -> {
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
private fun RenderQuote(block: ChatBlock.Quote) {
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
private fun RenderList(block: ChatBlock.ListBlock) {
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
private fun RenderTable(block: ChatBlock.Table) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val headerStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
    val cellStyle = MaterialTheme.typography.bodySmall
    val columnCount = maxOf(block.head.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (columnCount == 0) return

    val textMeasurer = rememberTextMeasurer()
    val cellPadding = 10.dp
    val cellPaddingPx = with(LocalDensity.current) { cellPadding.roundToPx() }

    // Measure every cell up front, like the native TableView: each row used to
    // size to its own text, so columns never lined up and the header fill
    // stopped at the widest row instead of spanning the whole card.
    val naturalWidths = remember(
        block.head, block.rows, headerStyle, cellStyle, textMeasurer, cellPaddingPx
    ) {
        val natural = IntArray(columnCount)
        fun record(cells: List<String>, style: TextStyle) {
            cells.forEachIndexed { index, cell ->
                if (index >= columnCount) return@forEachIndexed
                val textWidth = textMeasurer.measure(
                    text = cell,
                    style = style,
                    softWrap = false,
                    maxLines = 1
                ).size.width
                natural[index] = maxOf(natural[index], textWidth + 2 * cellPaddingPx)
            }
        }
        record(block.head, headerStyle)
        block.rows.forEach { record(it, cellStyle) }
        for (index in 0 until columnCount) if (natural[index] == 0) natural[index] = 1
        natural
    }

    // The bubble is sized by INTRINSIC measurement, which cannot see the real
    // pane width, so the first pass renders at natural width. `onSizeChanged`
    // would report the CONTENT width inside the scroll container (unbounded),
    // not the viewport, so read the scroll state's measured viewport instead;
    // it is 0 until the first layout pass, which then stretches the columns.
    val scrollState = rememberScrollState()
    val fittedWidths = remember(naturalWidths, scrollState.viewportSize) {
        fitTableColumns(naturalWidths, scrollState.viewportSize)
    }
    val tableWidthPx = fittedWidths.sum()
    val density = LocalDensity.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .horizontalScroll(scrollState)
    ) {
        Column(modifier = Modifier.width(with(density) { tableWidthPx.toDp() })) {
            if (block.head.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    block.head.forEachIndexed { index, cell ->
                        TableCell(cell, headerStyle, fittedWidths[index], cellPadding)
                    }
                }
                if (block.rows.isNotEmpty()) TableRowDivider(borderColor)
            }
            block.rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (column in 0 until columnCount) {
                        TableCell(
                            text = row.getOrElse(column) { "" },
                            style = cellStyle,
                            widthPx = fittedWidths[column],
                            horizontalPadding = cellPadding
                        )
                    }
                }
                if (index != block.rows.lastIndex) TableRowDivider(borderColor)
            }
        }
    }
}

@Composable
private fun TableCell(text: String, style: TextStyle, widthPx: Int, horizontalPadding: Dp) {
    val density = LocalDensity.current
    Text(
        text = text,
        style = style,
        modifier = Modifier
            .width(with(density) { widthPx.toDp() })
            .padding(horizontal = horizontalPadding)
    )
}

/**
 * Column widths in pixels for a table that has [availableWidthPx] to fill.
 *
 * Mirrors the table layout contract: when the natural (single-line) table is
 * narrower than the available width every column grows proportionally so the
 * table spans the card exactly; when it is wider the natural widths are kept
 * and the caller scrolls horizontally. Squeezing columns instead would break
 * long tokens into vertical letter strips.
 */
internal fun fitTableColumns(
    naturalWidthsPx: IntArray,
    availableWidthPx: Int
): IntArray {
    if (naturalWidthsPx.isEmpty()) return IntArray(0)
    val naturalTotal = naturalWidthsPx.sum()
    if (availableWidthPx <= naturalTotal || naturalTotal == 0) return naturalWidthsPx.copyOf()

    val grown = IntArray(naturalWidthsPx.size)
    var assigned = 0
    naturalWidthsPx.forEachIndexed { index, width ->
        grown[index] = if (index == naturalWidthsPx.lastIndex) {
            availableWidthPx - assigned
        } else {
            (width.toLong() * availableWidthPx / naturalTotal).toInt()
        }
        assigned += grown[index]
    }
    return grown
}

@Composable
private fun TableRowDivider(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}
