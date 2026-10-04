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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Android Native MessageView (TARGET.md §8, §9, §10).
 *
 * Wraps DocumentView inside a chat bubble container with sender alignment
 * and bubble styling.
 */
class MessageView(context: Context) : LinearLayout(context) {

    val documentView: DocumentView = DocumentView(context)
    val userTextView: TextView = TextView(context)
    private val bubbleContainer: FrameLayout = FrameLayout(context)

    init {
        orientation = VERTICAL
        val padH = (12 * context.resources.displayMetrics.density).toInt()
        val padV = (4 * context.resources.displayMetrics.density).toInt()
        setPadding(padH, padV, padH, padV)

        val innerPadH = (14 * context.resources.displayMetrics.density).toInt()
        val innerPadV = (10 * context.resources.displayMetrics.density).toInt()
        bubbleContainer.setPadding(innerPadH, innerPadV, innerPadH, innerPadV)

        userTextView.textSize = 15f
        userTextView.setTextColor(Color.WHITE)
        userTextView.setTextIsSelectable(true)

        bubbleContainer.addView(documentView)
        bubbleContainer.addView(userTextView)

        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        addView(bubbleContainer, lp)
    }

    fun bind(
        role: String,
        content: String,
        blocks: List<RenderBlock>?,
        isStreaming: Boolean
    ) {
        val density = context.resources.displayMetrics.density
        val cornerRadius = 18f * density
        val isUser = role.equals("user", ignoreCase = true)

        if (isUser) {
            gravity = Gravity.END
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#0084FF")) // Brand primary bubble
                this.cornerRadius = cornerRadius
            }
            bubbleContainer.background = bg
            userTextView.visibility = VISIBLE
            userTextView.text = content
            documentView.visibility = GONE
        } else {
            gravity = Gravity.START
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#F0F2F5")) // Assistant surface bubble
                this.cornerRadius = cornerRadius
            }
            bubbleContainer.background = bg
            userTextView.visibility = GONE
            documentView.visibility = VISIBLE

            if (blocks != null) {
                documentView.setBlocks(blocks)
            }
        }
    }
}
