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
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Markdown list block: one row per item — marker column (• or literal ordinal)
 * plus body text, nested items indented per their level. Ordered numbering
 * follows each item's parsed literal number, so the original markers survive.
 */
class BulletListView(context: Context) : LinearLayout(context) {

    private var theme: RendererTheme? = null
    private var block: RenderBlock.ListBlock? = null

    init {
        orientation = VERTICAL
    }

    fun updateTheme(theme: RendererTheme?) {
        this.theme = theme
        block?.let { bind(it) }
    }

    fun bind(block: RenderBlock.ListBlock) {
        this.block = block
        removeAllViews()
        val dp = resources.displayMetrics.density
        val bodyColor = theme?.onAiBubble ?: 0xDE000000.toInt()

        block.items.forEach { item ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
            }
            val marker = TextView(context).apply {
                textSize = 16f
                setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL)
                setTextColor(bodyColor)
                gravity = Gravity.RIGHT
                // Fixed marker column; nested rows indent the whole row below
                layoutParams = LayoutParams((20 * dp).toInt(), LayoutParams.WRAP_CONTENT)
                text = if (item.ordered) "${item.number}." else "•"
            }
            val text = TextView(context).apply {
                textSize = 16f
                lineHeight = (24 * resources.displayMetrics.density).toInt()
                setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL)
                setTextColor(bodyColor)
                maxWidth = maxBulletTextWidth(item.indent)
                text = item.text
            }
            row.addView(marker)
            row.addView(
                text,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (6 * dp).toInt()
                }
            )
            val indentPx = (item.indent.coerceAtMost(4) * 18 * dp).toInt()
            addView(
                row,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = indentPx
                }
            )
        }
    }

    /** Same cap as DocumentView.maxTextWidth, narrowed by this list's indents. */
    private fun maxBulletTextWidth(indent: Int): Int {
        val dp = resources.displayMetrics.density
        val base = DocumentView.maxContentTextWidth(resources)
        return (base - (indent * 18 * dp + 26 * dp).toInt()).coerceAtLeast((100 * dp).toInt())
    }
}
