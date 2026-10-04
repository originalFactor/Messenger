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
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Android Native DocumentView (TARGET.md §8, §9, §10).
 *
 * Renders structured Markdown / LaTeX / Code / ToolCall AST blocks
 * using direct View hierarchy and fine-grained invalidation for high-frequency streaming.
 */
class DocumentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val blockViews = mutableMapOf<Long, View>()
    private val blockSpacing = (8 * context.resources.displayMetrics.density).toInt()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        orientation = VERTICAL
    }

    /** Set static completed blocks (e.g. historical message). */
    fun setBlocks(blocks: List<RenderBlock>) {
        removeAllViews()
        blockViews.clear()
        for (block in blocks) {
            val view = createViewForBlock(block)
            blockViews[block.id] = view
            addView(view, createBlockLayoutParams(block))
        }
    }

    /** Apply incremental DiffBatch JSON received from Rust StreamingSession. */
    fun applyDiffBatch(diffBatchJson: String) {
        val root = try {
            json.parseToJsonElement(diffBatchJson) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return

        val diffs = root["diffs"] as? JsonArray ?: return
        for (item in diffs) {
            val obj = item as? JsonObject ?: continue
            val diffType = obj["diff"]?.jsonPrimitive?.contentOrNull ?: continue
            when (diffType) {
                "append" -> {
                    val blockObj = obj["block"]?.jsonObject ?: continue
                    val block = DocumentParser.parseBlockObject(blockObj) ?: continue
                    val view = createViewForBlock(block)
                    blockViews[block.id] = view
                    addView(view, createBlockLayoutParams(block))
                }
                "update" -> {
                    val blockObj = obj["block"]?.jsonObject ?: continue
                    val block = DocumentParser.parseBlockObject(blockObj) ?: continue
                    val existing = blockViews[block.id]
                    if (existing != null) {
                        bindBlockToView(existing, block)
                        existing.invalidate()
                    } else {
                        val view = createViewForBlock(block)
                        blockViews[block.id] = view
                        addView(view, createBlockLayoutParams(block))
                    }
                }
                "finalize" -> {
                    val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                    val view = blockViews[id]
                    // Layout is now frozen; renderers can cache measurements
                    view?.invalidate()
                }
                "reset" -> {
                    removeAllViews()
                    blockViews.clear()
                }
            }
        }
    }

    private fun createBlockLayoutParams(block: RenderBlock): LayoutParams {
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lp.topMargin = blockSpacing
        return lp
    }

    private fun createViewForBlock(block: RenderBlock): View {
        return when (block) {
            is RenderBlock.Paragraph -> {
                TextView(context).apply {
                    textSize = 15f
                    setTextColor(Color.parseColor("#DE000000"))
                    setLineSpacing(4f * resources.displayMetrics.density, 1f)
                    setTextIsSelectable(true)
                    text = block.text
                }
            }
            is RenderBlock.Heading -> {
                TextView(context).apply {
                    val scale = when (block.level) {
                        1 -> 22f
                        2 -> 19f
                        3 -> 17f
                        else -> 15f
                    }
                    textSize = scale
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#DE000000"))
                    text = block.text
                }
            }
            is RenderBlock.Code -> {
                CodeBlockView(context).apply {
                    bind(block.language, block.code)
                }
            }
            is RenderBlock.Math -> {
                MathBlockView(context).apply {
                    bind(block.formula, block.isFinalized, display = true)
                }
            }
            is RenderBlock.Quote -> {
                TextView(context).apply {
                    textSize = 14f
                    setTypeface(null, Typeface.ITALIC)
                    setTextColor(Color.parseColor("#616161"))
                    setPadding((12 * resources.displayMetrics.density).toInt(), 0, 0, 0)
                    text = block.text
                }
            }
            is RenderBlock.Think -> {
                ThinkBlockView(context).apply {
                    bind(block.content, block.isFinalized)
                }
            }
            is RenderBlock.ToolCall -> {
                ToolCallView(context).apply {
                    bind(block.name, block.arguments, block.output, block.isError, block.isFinalized)
                }
            }
            is RenderBlock.Divider -> {
                View(context).apply {
                    setBackgroundColor(Color.parseColor("#1F000000"))
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (1 * resources.displayMetrics.density).toInt())
                }
            }
        }
    }

    private fun bindBlockToView(view: View, block: RenderBlock) {
        when (block) {
            is RenderBlock.Paragraph -> {
                (view as? TextView)?.text = block.text
            }
            is RenderBlock.Heading -> {
                (view as? TextView)?.text = block.text
            }
            is RenderBlock.Code -> {
                (view as? CodeBlockView)?.bind(block.language, block.code)
            }
            is RenderBlock.Math -> {
                (view as? MathBlockView)?.bind(block.formula, block.isFinalized, display = true)
            }
            is RenderBlock.Quote -> {
                (view as? TextView)?.text = block.text
            }
            is RenderBlock.Think -> {
                (view as? ThinkBlockView)?.bind(block.content, block.isFinalized)
            }
            is RenderBlock.ToolCall -> {
                (view as? ToolCallView)?.bind(
                    block.name,
                    block.arguments,
                    block.output,
                    block.isError,
                    block.isFinalized
                )
            }
            is RenderBlock.Divider -> {}
        }
    }
}
