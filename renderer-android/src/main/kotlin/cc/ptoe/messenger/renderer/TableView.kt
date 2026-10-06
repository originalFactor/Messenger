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
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView

/**
 * Pipe-table block: bordered rounded card with a bold header row on a subtle
 * fill, hairline row dividers, plain-text cells. Wide tables scroll
 * horizontally instead of squeezing the bubble.
 */
class TableView(context: Context) : HorizontalScrollView(context) {

    private val table = TableLayout(context)
    private var theme: RendererTheme? = null
    private var head: List<String> = emptyList()
    private var rows: List<List<String>> = emptyList()

    init {
        isHorizontalScrollBarEnabled = false
        addView(table, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun updateTheme(theme: RendererTheme?) {
        this.theme = theme
        rebuild()
    }

    fun bind(head: List<String>, rows: List<List<String>>, isFinalized: Boolean) {
        this.head = head
        this.rows = rows
        rebuild()
    }

    private fun rebuild() {
        val dp = resources.displayMetrics.density
        val surface = theme?.onAiBubble ?: Color.BLACK

        table.removeAllViews()
        background = GradientDrawable().apply {
            cornerRadius = 8f * dp
            setColor(Color.TRANSPARENT)
            setStroke((1 * dp).toInt(), withAlpha(surface, 0x25))
        }
        setPadding((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())

        if (head.isNotEmpty()) {
            table.addView(buildRow(head, bold = true, fill = withAlpha(surface, 0x0A)))
            if (rows.isNotEmpty()) addDivider(surface)
        }
        rows.forEachIndexed { index, row ->
            table.addView(buildRow(row, bold = false, fill = Color.TRANSPARENT))
            if (index != rows.lastIndex) addDivider(surface)
        }
    }

    private fun addDivider(surface: Int) {
        val dp = resources.displayMetrics.density
        table.addView(
            View(context).apply { setBackgroundColor(withAlpha(surface, 0x20)) },
            TableLayout.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1))
        )
    }

    private fun buildRow(cells: List<String>, bold: Boolean, fill: Int): TableRow {
        val dp = resources.displayMetrics.density
        val body = theme?.onAiBubble ?: 0xDE000000.toInt()
        val row = TableRow(context)
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = GradientDrawable().apply { setColor(fill) }
        cells.forEach { cellText ->
            val label = TextView(context).apply {
                textSize = 13f
                setTypeface(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(body)
                setPadding(
                    (10 * dp).toInt(),
                    (6 * dp).toInt(),
                    (10 * dp).toInt(),
                    (6 * dp).toInt()
                )
                text = cellText
            }
            row.addView(
                label,
                TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
            )
        }
        return row
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)
}
