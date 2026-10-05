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
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool invocation card mirroring the Compose [ToolCallCard] design: title row
 * (name + status), a collapsed one-line command preview, and a tap-to-expand
 * section with the full command and result.
 */
class ToolCallView(context: Context) : LinearLayout(context) {

    private val nameLabel = TextView(context)
    private val statusBadge = TextView(context)
    private val commandPreview = TextView(context)
    private val expandedSection = LinearLayout(context)
    private val commandText = TextView(context)
    private val resultLabel = TextView(context)
    private val outputText = TextView(context)

    private var theme: RendererTheme? = null
    private var isExpanded = false
    private var name = ""
    private var arguments = ""
    private var output: String? = null
    private var isError = false
    private var isFinalized = true

    init {
        orientation = VERTICAL
        val dp = resources.displayMetrics.density
        background = GradientDrawable().apply { cornerRadius = 8f * dp }
        val pad = (10 * dp).toInt()
        setPadding(pad, pad, pad, pad)

        // Title row: name + status badge
        val topRow = LinearLayout(context)
        topRow.orientation = HORIZONTAL
        topRow.gravity = Gravity.CENTER_VERTICAL

        nameLabel.textSize = 14f
        nameLabel.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
        nameLabel.maxLines = 1
        nameLabel.ellipsize = TextUtils.TruncateAt.END
        topRow.addView(nameLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        statusBadge.textSize = 11f
        topRow.addView(statusBadge)
        addView(topRow)

        // Collapsed one-line command preview
        commandPreview.textSize = 12f
        commandPreview.typeface = Typeface.MONOSPACE
        commandPreview.maxLines = 1
        commandPreview.ellipsize = TextUtils.TruncateAt.END
        commandPreview.setPadding(0, (4 * dp).toInt(), 0, 0)
        addView(commandPreview)

        // Expanded section: full command + result
        expandedSection.orientation = VERTICAL
        expandedSection.visibility = GONE

        commandText.textSize = 12f
        commandText.typeface = Typeface.MONOSPACE
        commandText.setPadding(0, (8 * dp).toInt(), 0, 0)
        expandedSection.addView(commandText)

        resultLabel.textSize = 11f
        resultLabel.setPadding(0, (8 * dp).toInt(), 0, 0)
        expandedSection.addView(resultLabel)

        outputText.textSize = 12f
        outputText.typeface = Typeface.MONOSPACE
        outputText.setPadding(0, (4 * dp).toInt(), 0, 0)
        expandedSection.addView(outputText)

        addView(expandedSection)

        setOnClickListener { toggleExpanded() }
    }

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        applyExpandedState()
    }

    private fun applyExpandedState() {
        expandedSection.visibility = if (isExpanded) VISIBLE else GONE
        commandPreview.visibility =
            if (!isExpanded && arguments.isNotBlank()) VISIBLE else GONE
        commandPreview.text = commandDisplay()
        commandText.text = commandDisplay()
        commandText.visibility = if (arguments.isNotBlank()) VISIBLE else GONE
        resultLabel.text = theme?.resultLabel ?: "Result"
        outputText.text = output?.ifBlank { "—" } ?: "—"
        if (!isFinalized) {
            // Running cards hide the (not yet settled) result section
            resultLabel.visibility = GONE
            outputText.visibility = GONE
        } else {
            resultLabel.visibility = VISIBLE
            outputText.visibility = VISIBLE
        }
    }

    private fun commandDisplay(): String {
        if (arguments.isBlank()) return ""
        // Terminal cards show the human-readable command (mirrors TerminalTool.parseCommand);
        // other tools show their raw arguments JSON
        if (name == TERMINAL_TOOL_NAME) {
            return runCatching {
                Json.parseToJsonElement(arguments).jsonObject["command"]
                    ?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: arguments
        }
        return arguments
    }

    fun updateTheme(theme: RendererTheme?) {
        this.theme = theme
        applyContainerColor()
    }

    private fun applyContainerColor() {
        val t = theme ?: return
        val dp = resources.displayMetrics.density
        val bg = (background as? GradientDrawable) ?: return
        when {
            !isFinalized -> {
                bg.setColor(t.secondaryContainer)
                nameLabel.setTextColor(t.onSecondaryContainer)
                statusBadge.setTextColor(t.onSecondaryContainer)
                commandPreview.setTextColor(t.onSecondaryContainer)
                commandText.setTextColor(t.onSecondaryContainer)
                resultLabel.setTextColor(t.onSecondaryContainer)
                outputText.setTextColor(t.onSecondaryContainer)
            }
            isError -> {
                bg.setColor(t.errorBubble)
                nameLabel.setTextColor(t.onErrorBubble)
                statusBadge.setTextColor(t.onErrorBubble)
                commandPreview.setTextColor(t.onErrorBubble)
                commandText.setTextColor(t.onErrorBubble)
                resultLabel.setTextColor(t.onErrorBubble)
                outputText.setTextColor(t.onErrorBubble)
            }
            else -> {
                bg.setColor(t.surfaceContainerHighest)
                nameLabel.setTextColor(t.onAiBubble)
                statusBadge.setTextColor(t.onSurfaceVariant)
                commandPreview.setTextColor(t.onSurfaceVariant)
                commandText.setTextColor(t.onAiBubble)
                resultLabel.setTextColor(t.onSurfaceVariant)
                outputText.setTextColor(t.onAiBubble)
            }
        }
        bg.cornerRadius = 8f * dp
    }

    fun bind(name: String, arguments: String, output: String?, isError: Boolean, isFinalized: Boolean) {
        this.name = name
        this.arguments = arguments
        this.output = output
        this.isError = isError
        this.isFinalized = isFinalized

        nameLabel.text = if (name == TERMINAL_TOOL_NAME) {
            theme?.terminalToolName ?: name
        } else {
            name
        }

        if (!isFinalized) {
            statusBadge.text = theme?.runningLabel ?: "Running…"
        } else if (isError) {
            statusBadge.text = "✕ ${theme?.failedLabel ?: "Failed"}"
        } else {
            statusBadge.text = "✓ ${theme?.successLabel ?: "Done"}"
        }

        applyContainerColor()
        applyExpandedState()
    }

    private companion object {
        const val TERMINAL_TOOL_NAME = "terminal"
    }
}
