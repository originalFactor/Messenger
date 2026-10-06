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
import android.widget.LinearLayout
import android.widget.TextView

class ThinkBlockView(context: Context) : LinearLayout(context) {

    private val headerLayout = LinearLayout(context)
    private val titleLabel = TextView(context)
    private val arrowLabel = TextView(context)
    private val contentText = TextView(context)
    private var isExpanded = false
    private var theme: RendererTheme? = null
    private val sectionAnimator = SectionAnimator(contentText)

    init {
        orientation = VERTICAL
        val cornerRadius = 10f * context.resources.displayMetrics.density
        val bg = GradientDrawable().apply { this.cornerRadius = cornerRadius }
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

        titleLabel.textSize = 12f
        val titleLp = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        headerLayout.addView(titleLabel, titleLp)

        arrowLabel.text = "▼"
        arrowLabel.textSize = 10f
        headerLayout.addView(arrowLabel)

        addView(headerLayout)

        // Collapsible content
        contentText.textSize = 12f
        contentText.setPadding(padH, 0, padH, padV)
        contentText.visibility = GONE
        addView(contentText)

        updateTheme(null)
    }

    fun updateTheme(theme: RendererTheme?) {
        this.theme = theme
        (background as? GradientDrawable)?.apply {
            val surface = theme?.onAiBubble ?: Color.BLACK
            // 8% overlay of the bubble's own foreground keeps contrast on both themes
            setColor(withAlpha(surface, 0x14))
            setStroke(
                (1 * resources.displayMetrics.density).toInt(),
                withAlpha(surface, 0x25)
            )
        }
        val variant = theme?.onSurfaceVariant ?: 0xFF757575.toInt()
        titleLabel.text = theme?.thinkingTitle ?: "Thinking Process"
        titleLabel.setTextColor(variant)
        arrowLabel.setTextColor(variant)
        contentText.setTextColor(variant)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun toggle() {
        isExpanded = !isExpanded
        arrowLabel.text = if (isExpanded) "▲" else "▼"
        sectionAnimator.animate(isExpanded)
    }

    fun bind(content: String, isFinalized: Boolean) {
        contentText.text = content
        if (!isFinalized) {
            // While streaming thinking, keep expanded so user sees thoughts
            if (!isExpanded) {
                isExpanded = true
                arrowLabel.text = "▲"
                sectionAnimator.applyInstant(true)
            }
        } else if (!sectionAnimator.isRunning) {
            // Finalized rebinds must not fight a user-triggered animation, but
            // they do need to settle the exact collapsed/expanded state.
            sectionAnimator.applyInstant(isExpanded)
        }
    }
}
