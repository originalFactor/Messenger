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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Markdown list block: one row per item — marker column (•, literal ordinal,
 * or GFM task checkbox) plus body text, nested items indented per their level.
 * Ordered numbering follows each item's parsed literal number, so the original
 * markers survive.
 */
class BulletListView(context: Context) : LinearLayout(context) {

    private var theme: RendererTheme? = null
    private var block: RenderBlock.ListBlock? = null

    init {
        orientation = VERTICAL
        setOnLongClickListener { bubbleLongClick() }
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
            val marker: View = if (item.task != null) {
                TaskCheckboxView(
                    context,
                    checked = item.task,
                    checkedColor = theme?.primary ?: 0xFF1565C0.toInt(),
                    checkColor = theme?.onPrimary ?: Color.WHITE,
                    uncheckedColor = theme?.outlineVariant ?: 0x61000000
                )
            } else {
                TextView(context).apply {
                    textSize = 16f
                    setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL)
                    setTextColor(bodyColor)
                    gravity = Gravity.RIGHT
                    text = if (item.ordered) "${item.number}." else "•"
                }
            }
            // Fixed marker column; nested rows indent the whole row below.
            // The checkbox takes a fixed 20×24dp slot (aligned to the first
            // text line); text markers keep their wrap-content height.
            marker.layoutParams = LayoutParams(
                (20 * dp).toInt(),
                if (item.task != null) (24 * dp).toInt() else LayoutParams.WRAP_CONTENT
            )
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

/**
 * GFM task-list checkbox drawn in the 20dp×24dp marker column slot: a 16dp
 * rounded square inset to align with the first text line — checked fills
 * [checkedColor] with a [checkColor] check mark, unchecked strokes
 * [uncheckedColor].
 */
private class TaskCheckboxView(
    context: Context,
    private val checked: Boolean,
    private val checkedColor: Int,
    private val checkColor: Int,
    private val uncheckedColor: Int
) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val checkPath = Path()

    override fun onDraw(canvas: Canvas) {
        val dp = resources.displayMetrics.density
        // 16dp square centered in the 20dp column, aligned to the 24dp first text line
        val size = 16f * dp
        val left = (width - size) / 2f
        val top = (24f * dp - size) / 2f
        val radius = 4f * dp
        if (checked) {
            fillPaint.color = checkedColor
            canvas.drawRoundRect(left, top, left + size, top + size, radius, radius, fillPaint)
            checkPaint.color = checkColor
            checkPath.reset()
            checkPath.moveTo(left + size * 0.25f, top + size * 0.52f)
            checkPath.lineTo(left + size * 0.42f, top + size * 0.70f)
            checkPath.lineTo(left + size * 0.76f, top + size * 0.30f)
            canvas.drawPath(checkPath, checkPaint)
        } else {
            strokePaint.color = uncheckedColor
            canvas.drawRoundRect(left, top, left + size, top + size, radius, radius, strokePaint)
        }
    }
}
