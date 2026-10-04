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
import android.widget.LinearLayout
import android.widget.TextView

class ToolCallView(context: Context) : LinearLayout(context) {

    private val nameLabel = TextView(context)
    private val statusBadge = TextView(context)
    private val argsText = TextView(context)
    private val outputText = TextView(context)

    init {
        orientation = VERTICAL
        val cornerRadius = 10f * context.resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#10000000"))
            this.cornerRadius = cornerRadius
            setStroke((1 * context.resources.displayMetrics.density).toInt(), Color.parseColor("#20000000"))
        }
        background = bg

        val pad = (10 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)

        // Top row: name + status badge
        val topRow = LinearLayout(context)
        topRow.orientation = HORIZONTAL
        topRow.gravity = Gravity.CENTER_VERTICAL

        nameLabel.textSize = 13f
        nameLabel.setTypeface(null, Typeface.BOLD)
        nameLabel.setTextColor(Color.parseColor("#1E88E5"))
        val nameLp = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        topRow.addView(nameLabel, nameLp)

        statusBadge.textSize = 11f
        topRow.addView(statusBadge)
        addView(topRow)

        // Args preview
        argsText.textSize = 11f
        argsText.typeface = Typeface.MONOSPACE
        argsText.setTextColor(Color.parseColor("#757575"))
        argsText.setPadding(0, (4 * context.resources.displayMetrics.density).toInt(), 0, 0)
        addView(argsText)

        // Output
        outputText.textSize = 12f
        outputText.typeface = Typeface.MONOSPACE
        outputText.setTextColor(Color.parseColor("#37474F"))
        outputText.setPadding(0, (6 * context.resources.displayMetrics.density).toInt(), 0, 0)
        addView(outputText)
    }

    fun bind(name: String, arguments: String, output: String?, isError: Boolean, isFinalized: Boolean) {
        nameLabel.text = "Tool: $name"
        argsText.text = if (arguments.isNotBlank()) "args: $arguments" else ""

        if (!isFinalized) {
            statusBadge.text = "Running..."
            statusBadge.setTextColor(Color.parseColor("#FB8C00"))
            outputText.visibility = GONE
        } else {
            statusBadge.text = if (isError) "Failed" else "Success"
            statusBadge.setTextColor(if (isError) Color.parseColor("#E53935") else Color.parseColor("#43A047"))
            if (!output.isNullOrBlank()) {
                outputText.visibility = VISIBLE
                outputText.text = output
            } else {
                outputText.visibility = GONE
            }
        }
    }
}
