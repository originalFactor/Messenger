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

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
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

object DocumentParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Theme color baked into rendered inline-math bitmaps; set by the host views. */
    @Volatile
    var inlineMathColor: Int = 0xFF1565C0.toInt()

    /** [context] enables rendered inline-math spans; null falls back to italic text. */
    fun parseBlocksJson(blocksJson: String, context: Context? = null): List<RenderBlock> {
        val array = try {
            json.parseToJsonElement(blocksJson) as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            parseBlockObject(obj, context)
        }
    }

    fun parseBlockObject(obj: JsonObject, context: Context? = null): RenderBlock? {
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: return null
        val id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L
        val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "finalized"
        val isFinalized = status == "finalized"

        return when (kind) {
            "paragraph" -> {
                val inlines = obj["inlines"]?.jsonArray
                val text = if (inlines != null) buildInlinesSpanned(inlines, context) else ""
                RenderBlock.Paragraph(id, text, isFinalized)
            }
            "heading" -> {
                val level = obj["level"]?.jsonPrimitive?.intOrNull ?: 1
                val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                RenderBlock.Heading(id, level, text, isFinalized)
            }
            "code_block" -> {
                val lang = obj["language"]?.jsonPrimitive?.contentOrNull
                val code = obj["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
                RenderBlock.Code(id, lang, code, isFinalized)
            }
            "math" -> {
                val formula = obj["formula"]?.jsonPrimitive?.contentOrNull.orEmpty()
                RenderBlock.Math(id, formula, isFinalized)
            }
            "quote" -> {
                // Quote content carries parsed inlines (bold/italic/code/math),
                // rendered as spans over the italic base typeface
                val inlines = obj["inlines"]?.jsonArray
                RenderBlock.Quote(
                    id,
                    text = if (inlines != null) buildInlinesSpanned(inlines, context) else "",
                    isFinalized
                )
            }
            "think" -> {
                val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                RenderBlock.Think(id, content, isFinalized)
            }
            "tool_call" -> {
                val callId = obj["call_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                val arguments = obj["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val output = obj["output"]?.jsonPrimitive?.contentOrNull
                val isError = obj["is_error"]?.jsonPrimitive?.booleanOrNull ?: false
                RenderBlock.ToolCall(id, callId, name, arguments, output, isError, isFinalized)
            }
            "table" -> {
                val head = obj["head"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull.orEmpty() } ?: emptyList()
                val rows = obj["rows"]?.jsonArray?.map { row ->
                    (row as? JsonArray)?.map { cell -> cell.jsonPrimitive.contentOrNull.orEmpty() } ?: emptyList()
                } ?: emptyList()
                RenderBlock.Table(id, head, rows, isFinalized)
            }
            "list" -> {
                val items = obj["items"]?.jsonArray?.mapNotNull { item ->
                    val itemObj = item as? JsonObject ?: return@mapNotNull null
                    val inlines = itemObj["inlines"]?.jsonArray
                    RenderBlock.ListItemData(
                        indent = itemObj["indent"]?.jsonPrimitive?.intOrNull ?: 0,
                        ordered = itemObj["ordered"]?.jsonPrimitive?.booleanOrNull ?: false,
                        number = itemObj["number"]?.jsonPrimitive?.intOrNull ?: 0,
                        task = itemObj["task"]?.jsonPrimitive?.booleanOrNull,
                        text = if (inlines != null) buildInlinesSpanned(inlines, context) else ""
                    )
                } ?: emptyList()
                RenderBlock.ListBlock(id, items, isFinalized)
            }
            "divider" -> RenderBlock.Divider(id)
            else -> null
        }
    }

    private fun buildInlinesSpanned(inlines: JsonArray, context: Context?): CharSequence {
        val rawText = inlines.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text", "bold", "italic", "strikethrough", "link" -> obj["text"]?.jsonPrimitive?.contentOrNull
                "code" -> obj["code"]?.jsonPrimitive?.contentOrNull
                "math" -> obj["formula"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }.joinToString("")

        val builder = SpannableStringBuilder(rawText)
        var cursor = 0
        for (item in inlines) {
            val obj = item as? JsonObject ?: continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: continue
            val segment = when (type) {
                "text", "bold", "italic", "strikethrough", "link" -> obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                "code" -> obj["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
                "math" -> obj["formula"]?.jsonPrimitive?.contentOrNull.orEmpty()
                else -> ""
            }
            val start = cursor
            val end = cursor + segment.length
            cursor = end
            if (start >= end) continue
            when (type) {
                "bold" -> runCatching { builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
                "italic" -> runCatching { builder.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
                "code" -> runCatching {
                    builder.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(RelativeSizeSpan(0.9f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "math" -> runCatching {
                    if (context != null) {
                        // 16sp matches the paragraph body size the span lives in
                        val sizePx = 16f * context.resources.displayMetrics.density
                        MathInlineRenderer.span(context, segment, sizePx, inlineMathColor)
                            ?.let { builder.setSpan(it, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
                            ?: builder.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        builder.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                "strikethrough" -> runCatching { builder.setSpan(StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
                "link" -> runCatching { builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
        }
        return builder
    }
}
