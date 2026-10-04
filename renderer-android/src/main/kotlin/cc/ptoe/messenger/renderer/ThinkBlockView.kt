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
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class ThinkBlockView(context: Context) : LinearLayout(context) {

    private val headerLayout = LinearLayout(context)
    private val titleLabel = TextView(context)
    private val arrowLabel = TextView(context)
    private val contentText = TextView(context)
    private var isExpanded = false

    init {
        orientation = VERTICAL
        val cornerRadius = 10f * context.resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#15000000")) // Semi-transparent card
            this.cornerRadius = cornerRadius
            setStroke((1 * context.resources.displayMetrics.density).toInt(), Color.parseColor("#25000000"))
        }
        background = bg

        val padH = (12 * context.resources.displayMetrics.density).toInt()
        val padV = (8 * context.resources.displayMetrics.density).toInt()

        // Header
        headerLayout.orientation = HORIZONTAL
        headerLayout.gravity = Gravity.CENTER_VERTICAL
        headerLayout.setPadding(padH, padV, padH, padV)
        headerLayout.setOnClickListener {
            toggle()
        }

        titleLabel.text = "Thinking Process"
        titleLabel.textSize = 12f
        titleLabel.setTextColor(Color.parseColor("#757575"))
        val titleLp = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        headerLayout.addView(titleLabel, titleLp)

        arrowLabel.text = "▼"
        arrowLabel.textSize = 10f
        arrowLabel.setTextColor(Color.parseColor("#757575"))
        headerLayout.addView(arrowLabel)

        addView(headerLayout)

        // Collapsible content
        contentText.textSize = 12f
        contentText.setTextColor(Color.parseColor("#616161"))
        contentText.setPadding(padH, 0, padH, padV)
        contentText.visibility = View.GONE
        addView(contentText)
    }

    private fun toggle() {
        isExpanded = !isExpanded
        contentText.visibility = if (isExpanded) View.VISIBLE else View.GONE
        arrowLabel.text = if (isExpanded) "▲" else "▼"
    }

    fun bind(content: String, isFinalized: Boolean) {
        contentText.text = content
        if (!isFinalized) {
            // While streaming thinking, keep expanded so user sees thoughts
            if (!isExpanded) {
                isExpanded = true
                contentText.visibility = View.VISIBLE
                arrowLabel.text = "▲"
            }
        }
    }
}
