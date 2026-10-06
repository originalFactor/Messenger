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

    /** Text width cap: screen − 64+8dp row insets − 32+8dp avatar+spacer − 28dp bubble padding.
     * Long single-line blocks must WRAP, never widen the bubble past the row's far inset. */
    private fun maxTextWidth(): Int {
        val dp = resources.displayMetrics.density
        return (resources.displayMetrics.widthPixels - ((112 + 28) * dp).toInt())
            .coerceAtLeast((100 * dp).toInt())
    }

    /** Set static completed blocks (e.g. historical message). */
    fun setBlocks(theme: RendererTheme?, blocks: List<RenderBlock>) {
        removeAllViews()
        blockViews.clear()
        blocks.forEachIndexed { index, block ->
            val view = createViewForBlock(theme, block)
            blockViews[block.id] = view
            addView(view, createBlockLayoutParams(index > 0))
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
                    addView(view, createBlockLayoutParams(childCount > 0))
                }
                "update" -> {
                    val blockObj = obj["block"]?.jsonObject ?: continue
                    val block = DocumentParser.parseBlockObject(blockObj) ?: continue
                    val existing = blockViews[block.id]
                    if (existing != null) {
                        bindBlockToView(existing, block)
                        existing.invalidate()
                    } else {
                        // Self-healing: the block's "append" may have been
                        // dropped before the streaming row ever bound (the
                        // first token can beat the rebind). Build it from the
                        // update's full block state.
                        val view = createViewForBlock(theme, block)
                        blockViews[block.id] = view
                        addView(view, createBlockLayoutParams(childCount > 0))
                    }
                }
                "finalize" -> {
                    val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                    blockViews[id]?.invalidate()
                }
                "reset" -> clearLiveBlocks()
            }
        }
    }

    /** Spacing between blocks; the FIRST block carries no top margin so text
     * sits vertically centered in the bubble padding. */
    private fun createBlockLayoutParams(isNotFirst: Boolean): LayoutParams {
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        if (isNotFirst) {
            lp.topMargin = blockSpacing
        }
        return lp
    }

    private fun createViewForBlock(theme: RendererTheme?, block: RenderBlock): View {
        val bodyColor = theme?.onAiBubble ?: 0xDE000000.toInt()
        val variantColor = theme?.onSurfaceVariant ?: 0x8A000000.toInt()
        return when (block) {
            is RenderBlock.Paragraph -> {
                TextView(context).apply {
                    // bodyLarge parity with the former llm-typewriter flow:
                    // 16sp text on a 24sp line height
                    textSize = 16f
                    lineHeight = (24 * resources.displayMetrics.density).toInt()
                    setTextColor(bodyColor)
                    typeface = Typeface.SANS_SERIF
                    maxWidth = maxTextWidth()
                    // Not selectable: long-press must reach the bubble's context menu
                    text = block.text
                }
            }
            is RenderBlock.Heading -> {
                TextView(context).apply {
                    // llm-typewriter headingScale × bodyLarge: 1.8/1.5/1.3/1.1/1.0/0.9
                    textSize = when (block.level) {
                        1 -> 28.8f
                        2 -> 24f
                        3 -> 20.8f
                        4 -> 17.6f
                        5 -> 16f
                        else -> 14.4f
                    }
                    setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                    setTextColor(bodyColor)
                    maxWidth = maxTextWidth()
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
                            textSize = 15f
                            lineHeight = (22 * resources.displayMetrics.density).toInt()
                            setTypeface(Typeface.SANS_SERIF, Typeface.ITALIC)
                            setTextColor(variantColor)
                            maxWidth = maxTextWidth()
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
            is RenderBlock.Table -> {
                TableView(context).apply {
                    updateTheme(theme)
                    bind(block.head, block.rows, block.isFinalized)
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
            is RenderBlock.Table -> {
                (view as? TableView)?.bind(block.head, block.rows, block.isFinalized)
            }
            is RenderBlock.Divider -> {}
        }
    }

    companion object {
        /** Static (rebuilt) block ids start above every live streaming-session id. */
        const val STATIC_ID_BASE = 1_000_000L
    }
}
