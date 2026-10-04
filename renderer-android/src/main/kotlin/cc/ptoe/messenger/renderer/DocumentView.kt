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
 * Renders structured Markdown / LaTeX / Code / ToolCall AST blocks using direct
 * View hierarchy and fine-grained invalidation for high-frequency streaming.
 *
 * The view hosts two sections: STATIC blocks (parsed rounds + finalized text,
 * ids ≥ [STATIC_ID_BASE]) and LIVE blocks appended by Rust streaming DiffBatch
 * ids (small ids from a fresh StreamingSession). [clearLiveBlocks] removes the
 * live section when the static section is rebuilt, keeping the id spaces
 * collision-free.
 */
class DocumentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val blockViews = mutableMapOf<Long, View>()
    private val blockSpacing = dp(8f).toInt()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        orientation = VERTICAL
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** Set static completed blocks (e.g. historical message). */
    fun setBlocks(theme: RendererTheme?, blocks: List<RenderBlock>) {
        removeAllViews()
        blockViews.clear()
        for (block in blocks) {
            val view = createViewForBlock(theme, block)
            blockViews[block.id] = view
            addView(view, createBlockLayoutParams())
        }
    }

    /** Remove every live (streaming-session) block; static blocks are preserved. */
    fun clearLiveBlocks() {
        for (id in blockViews.keys.filter { it < STATIC_ID_BASE }) {
            blockViews.remove(id)?.let { removeView(it) }
        }
    }

    /** Apply incremental DiffBatch JSON received from Rust StreamingSession. */
    fun applyDiffBatch(theme: RendererTheme?, diffBatchJson: String) {
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
                    if (blockViews.containsKey(block.id)) continue
                    val view = createViewForBlock(theme, block)
                    blockViews[block.id] = view
                    addView(view, createBlockLayoutParams())
                }
                "update" -> {
                    val blockObj = obj["block"]?.jsonObject ?: continue
                    val block = DocumentParser.parseBlockObject(blockObj) ?: continue
                    val existing = blockViews[block.id] ?: continue
                    bindBlockToView(existing, block)
                    existing.invalidate()
                }
                "finalize" -> {
                    val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                    blockViews[id]?.invalidate()
                }
                "reset" -> clearLiveBlocks()
            }
        }
    }

    private fun createBlockLayoutParams(): LayoutParams {
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lp.topMargin = blockSpacing
        return lp
    }

    private fun createViewForBlock(theme: RendererTheme?, block: RenderBlock): View {
        val bodyColor = theme?.onAiBubble ?: 0xDE000000.toInt()
        val variantColor = theme?.onSurfaceVariant ?: 0x8A000000.toInt()
        return when (block) {
            is RenderBlock.Paragraph -> {
                TextView(context).apply {
                    textSize = 15f
                    setTextColor(bodyColor)
                    typeface = Typeface.SANS_SERIF
                    setLineSpacing(0f, 1.3f)
                    // Not selectable: long-press must reach the bubble's context menu
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
                    setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                    setTextColor(bodyColor)
                    text = block.text
                }
            }
            is RenderBlock.Code -> {
                CodeBlockView(context).apply {
                    updateTheme(theme)
                    bind(block.language, block.code)
                }
            }
            is RenderBlock.Math -> {
                MathBlockView(context).apply {
                    updateTheme(theme)
                    bind(block.formula, block.isFinalized, display = true)
                }
            }
            is RenderBlock.Quote -> {
                // Left accent bar + italic variant-colored text
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    addView(
                        View(context).apply { setBackgroundColor(theme?.primary ?: bodyColor) },
                        LayoutParams(dp(3f).toInt(), LayoutParams.MATCH_PARENT)
                    )
                    addView(
                        TextView(context).apply {
                            textSize = 14f
                            setTypeface(Typeface.SANS_SERIF, Typeface.ITALIC)
                            setTextColor(variantColor)
                            setPadding(dp(10f).toInt(), 0, 0, 0)
                            text = block.text
                        },
                        LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                    )
                }
            }
            is RenderBlock.Think -> {
                ThinkBlockView(context).apply {
                    updateTheme(theme)
                    bind(block.content, block.isFinalized)
                }
            }
            is RenderBlock.ToolCall -> {
                ToolCallView(context).apply {
                    updateTheme(theme)
                    bind(block.name, block.arguments, block.output, block.isError, block.isFinalized)
                }
            }
            is RenderBlock.Divider -> {
                View(context).apply {
                    setBackgroundColor(theme?.outlineVariant ?: 0x1F000000)
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1f).toInt())
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
                val row = view as? LinearLayout ?: return
                val quote = row.getChildAt(1) as? TextView ?: return
                quote.text = block.text
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

    companion object {
        /** Static (rebuilt) block ids start above every live streaming-session id. */
        const val STATIC_ID_BASE = 1_000_000L
    }
}
