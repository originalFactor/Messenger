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
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil

/**
 * Pipe-table block: bordered rounded card with a bold header row on a subtle
 * fill, hairline row dividers, plain-text cells. Column widths are computed
 * up front from text measurement: cells get their natural column width, and
 * when the whole table fits the bubble every column is stretched
 * proportionally to fill the width exactly (rows are plain LinearLayouts, so
 * fixed pixel columns stay aligned without TableLayout's measure quirks).
 * Only tables genuinely wider than the bubble scroll horizontally.
 */
class TableView(context: Context) : HorizontalScrollView(context) {

    private val table = LinearLayout(context)
    private var theme: RendererTheme? = null
    private var head: List<String> = emptyList()
    private var rows: List<List<String>> = emptyList()

    init {
        isHorizontalScrollBarEnabled = false
        addView(table, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        setOnLongClickListener { bubbleLongClick() }
    }

    fun updateTheme(theme: RendererTheme?) {
        this.theme = theme
        rebuild()
    }

    fun bind(head: List<String>, rows: List<List<String>>, isFinalized: Boolean) {
        this.head = head
        this.rows = rows
        rebuild()
        post { stretchIfFits() }
    }

    private fun columnCount(): Int = maxOf(head.size, rows.maxOfOrNull { it.size } ?: 0)

    /** Natural (single-line) width of each column, including cell padding. */
    private fun naturalColumnWidths(): FloatArray {
        val dp = resources.displayMetrics.density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13f * dp
            typeface = Typeface.SANS_SERIF
        }
        val cellPadH = 2 * CELL_PAD_H_DP * dp
        val widths = FloatArray(columnCount())
        (listOf(head) + rows).forEach { row ->
            row.forEachIndexed { index, cell ->
                if (index < widths.size) {
                    widths[index] = maxOf(widths[index], paint.measureText(cell) + cellPadH)
                }
            }
        }
        for (i in widths.indices) if (widths[i] <= 0f) widths[i] = dp
        return widths
    }

    /**
     * When the natural table fits inside the bubble, rebuild with every
     * column widened proportionally so the table fills the width exactly.
     */
    private fun stretchIfFits() {
        if (width == 0 || table.childCount == 0) return
        val available = width - paddingLeft - paddingRight
        val columnWidths = naturalColumnWidths()
        val dp = resources.displayMetrics.density
        val naturalTotal = columnWidths.sum() + TABLE_MARGIN_DP * dp
        if (naturalTotal <= available) {
            // Redistribute the fill width across columns proportional to their natural widths
            val fillTotal = available - TABLE_MARGIN_DP * dp
            val total = columnWidths.sum()
            val stretched = FloatArray(columnWidths.size) { columnWidths[it] / total * fillTotal }
            rebuild(stretched)
        }
    }

    private fun rebuild(columnWidths: FloatArray? = null) {
        val dp = resources.displayMetrics.density
        val surface = theme?.onAiBubble ?: Color.BLACK

        table.removeAllViews()
        table.orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = 8f * dp
            setColor(Color.TRANSPARENT)
            setStroke((1 * dp).toInt(), withAlpha(surface, 0x25))
        }
        setPadding((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())

        if (head.isNotEmpty()) {
            table.addView(buildRow(head, bold = true, fill = withAlpha(surface, 0x0A), columnWidths))
            if (rows.isNotEmpty()) addDivider(surface)
        }
        rows.forEachIndexed { index, row ->
            table.addView(buildRow(row, bold = false, fill = Color.TRANSPARENT, columnWidths))
            if (index != rows.lastIndex) addDivider(surface)
        }
    }

    private fun addDivider(surface: Int) {
        val dp = resources.displayMetrics.density
        table.addView(
            View(context).apply { setBackgroundColor(withAlpha(surface, 0x20)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1))
        )
    }

    private fun buildRow(cells: List<String>, bold: Boolean, fill: Int, columnWidths: FloatArray?): LinearLayout {
        val dp = resources.displayMetrics.density
        val body = theme?.onAiBubble ?: 0xDE000000.toInt()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(fill) }
        }
        cells.forEachIndexed { index, cellText ->
            val label = TextView(context).apply {
                textSize = 13f
                setTypeface(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(body)
                setPadding(
                    (CELL_PAD_H_DP * dp).toInt(),
                    (6 * dp).toInt(),
                    (CELL_PAD_H_DP * dp).toInt(),
                    (6 * dp).toInt()
                )
                text = cellText
            }
            val cellWidth = columnWidths?.getOrNull(index)?.let { ceil(it).toInt() }
            row.addView(
                label,
                LinearLayout.LayoutParams(
                    cellWidth ?: LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return row
    }

    private companion object {
        const val CELL_PAD_H_DP = 10
        const val TABLE_MARGIN_DP = 4f
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)
}
