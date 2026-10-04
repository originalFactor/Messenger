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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Android Native MessageView (TARGET.md §8, §9, §10, §18).
 *
 * High-performance native view wrapping [DocumentView] inside an adaptive chat bubble.
 * Supports User, Assistant, and Date Separator layouts, error state retry hooks,
 * streaming indicators, and touch/long-press gesture dispatches.
 */
class MessageView(context: Context) : LinearLayout(context) {

    val documentView: DocumentView = DocumentView(context)
    val userTextView: TextView = TextView(context)
    val statusTextView: TextView = TextView(context)
    val dateSeparatorTextView: TextView = TextView(context)
    private val bubbleContainer: FrameLayout = FrameLayout(context)

    var currentItem: MessageItem? = null
        private set

    var onMessageClickListener: ((MessageItem) -> Unit)? = null
    var onMessageLongClickListener: ((MessageItem, View) -> Unit)? = null
    var onRetryClickListener: ((MessageItem) -> Unit)? = null

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
        userTextView.setTextIsSelectable(false) // Handle selection via custom copy actions

        statusTextView.textSize = 12f
        statusTextView.setTextColor(Color.parseColor("#BA1A1A")) // Error red
        statusTextView.visibility = GONE

        dateSeparatorTextView.textSize = 12f
        dateSeparatorTextView.setTextColor(Color.parseColor("#757575"))
        dateSeparatorTextView.gravity = Gravity.CENTER
        dateSeparatorTextView.visibility = GONE

        val dateLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            val vMargin = (8 * context.resources.displayMetrics.density).toInt()
            setMargins(0, vMargin, 0, vMargin)
        }
        addView(dateSeparatorTextView, dateLp)

        bubbleContainer.addView(documentView)
        bubbleContainer.addView(userTextView)

        val bubbleLp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        addView(bubbleContainer, bubbleLp)

        val statusLp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (4 * context.resources.displayMetrics.density).toInt()
        }
        addView(statusTextView, statusLp)

        bubbleContainer.setOnClickListener {
            val item = currentItem ?: return@setOnClickListener
            if (item.status.equals("ERROR", ignoreCase = true)) {
                onRetryClickListener?.invoke(item)
            } else {
                onMessageClickListener?.invoke(item)
            }
        }

        bubbleContainer.setOnLongClickListener { v ->
            val item = currentItem ?: return@setOnLongClickListener false
            onMessageLongClickListener?.invoke(item, v)
            true
        }
    }

    fun bind(item: MessageItem) {
        this.currentItem = item
        val density = context.resources.displayMetrics.density
        val cornerRadius = 18f * density

        if (item.isDateSeparator) {
            gravity = Gravity.CENTER_HORIZONTAL
            dateSeparatorTextView.visibility = VISIBLE
            dateSeparatorTextView.text = item.content
            bubbleContainer.visibility = GONE
            statusTextView.visibility = GONE
            return
        }

        dateSeparatorTextView.visibility = GONE
        bubbleContainer.visibility = VISIBLE

        val isUser = item.role.equals("user", ignoreCase = true)
        val isError = item.status.equals("ERROR", ignoreCase = true)

        if (isUser) {
            gravity = Gravity.END
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#0084FF")) // Brand primary bubble
                this.cornerRadius = cornerRadius
            }
            bubbleContainer.background = bg
            userTextView.visibility = VISIBLE
            userTextView.text = item.content
            documentView.visibility = GONE
            statusTextView.visibility = GONE
        } else {
            gravity = Gravity.START
            val bg = GradientDrawable().apply {
                if (isError) {
                    setColor(Color.parseColor("#FFDAD6")) // Error container background
                } else {
                    setColor(Color.parseColor("#F0F2F5")) // Assistant surface bubble
                }
                this.cornerRadius = cornerRadius
            }
            bubbleContainer.background = bg
            userTextView.visibility = GONE
            documentView.visibility = VISIBLE

            if (item.blocks != null) {
                documentView.setBlocks(item.blocks)
            } else if (item.content.isNotBlank()) {
                val parsed = DocumentParser.parseBlocksJson(
                    cc.ptoe.messenger.core.parseMarkdownToBlocksJson(item.content)
                )
                documentView.setBlocks(parsed)
            } else if (item.isStreaming) {
                documentView.setBlocks(emptyList())
            }

            if (isError) {
                statusTextView.visibility = VISIBLE
                statusTextView.text = "Failed to send. Tap to retry."
            } else {
                statusTextView.visibility = GONE
            }
        }
    }
}
