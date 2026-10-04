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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                val inlines = obj["inlines"]?.jsonArray?.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val t = o["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val textVal = o["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    when (t) {
                        "text" -> DesktopInline.Text(textVal)
                        "bold" -> DesktopInline.Bold(textVal)
                        "italic" -> DesktopInline.Italic(textVal)
                        "code" -> DesktopInline.Code(textVal)
                        "math" -> DesktopInline.Math(textVal)
                        else -> DesktopInline.Text(textVal)
                    }
                } ?: emptyList()
                val plainText = inlines.joinToString("") {
                    when (it) {
                        is DesktopInline.Text -> it.text
                        is DesktopInline.Bold -> it.text
                        is DesktopInline.Italic -> it.text
                        is DesktopInline.Code -> it.code
                        is DesktopInline.Math -> it.formula
                    }
                }
                DesktopBlock.Paragraph(id, plainText, inlines, isFinalized)
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
                val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                DesktopBlock.Quote(id, text, isFinalized)
            }
            "divider" -> DesktopBlock.Divider(id)
            else -> null
        }
    }
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
        if (block.inlines.isEmpty()) {
            AnnotatedString(block.text)
        } else {
            buildAnnotatedString {
                for (inline in block.inlines) {
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = if (block.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = block.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = if (block.isFinalized) (if (block.isError) "Failed" else "Finished") else "Running",
                style = MaterialTheme.typography.labelSmall,
                color = if (block.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
        if (block.output != null && block.output.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
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

@Composable
private fun RenderQuote(block: DesktopBlock.Quote) {
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
            text = block.text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}
