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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class CodeBlockView(context: Context) : LinearLayout(context) {

    private val spanJson = Json { isLenient = true }

    private val headerLayout = LinearLayout(context)
    private val langLabel = TextView(context)
    private val copyButton = TextView(context)
    private val codeText = TextView(context)
    private var theme: RendererTheme? = null
    private var language: String? = null
    private var code: String = ""

    init {
        orientation = VERTICAL
        val cornerRadius = 12f * context.resources.displayMetrics.density
        background = GradientDrawable().apply { this.cornerRadius = cornerRadius }

        // Header
        headerLayout.orientation = HORIZONTAL
        headerLayout.gravity = Gravity.CENTER_VERTICAL
        val padH = (12 * context.resources.displayMetrics.density).toInt()
        val padV = (8 * context.resources.displayMetrics.density).toInt()
        headerLayout.setPadding(padH, padV, padH, padV)

        langLabel.textSize = 12f
        langLabel.typeface = Typeface.MONOSPACE
        val langLp = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        headerLayout.addView(langLabel, langLp)

        copyButton.textSize = 12f
        copyButton.setPadding((8 * context.resources.displayMetrics.density).toInt(), 0, 0, 0)
        copyButton.setOnClickListener {
            val clip = ClipData.newPlainText("Code", code)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            Toast.makeText(context, theme?.copiedToast ?: "Copied", Toast.LENGTH_SHORT).show()
        }
        headerLayout.addView(copyButton)

        addView(headerLayout)

        // Code body
        codeText.typeface = Typeface.MONOSPACE
        codeText.textSize = 13f
        val bodyPad = (12 * context.resources.displayMetrics.density).toInt()
        codeText.setPadding(bodyPad, 0, bodyPad, bodyPad)
        codeText.setTextIsSelectable(true)
        addView(codeText)
    }

    fun updateTheme(theme: RendererTheme?) {
        this.theme = theme
        (background as? GradientDrawable)?.setColor(theme?.surfaceContainerHighest ?: 0xFF1E1E1E.toInt())
        langLabel.setTextColor(theme?.onSurfaceVariant ?: 0xFF9E9E9E.toInt())
        copyButton.setTextColor(theme?.primary ?: 0xFF4FC3F7.toInt())
        copyButton.text = theme?.copyAction ?: "Copy"
        applyCode()
    }

    fun bind(language: String?, code: String) {
        this.language = language
        this.code = code
        langLabel.text = language ?: "code"
        applyCode()
    }

    /** Re-apply highlighting (theme colors and code text may change independently). */
    private fun applyCode() {
        codeText.text = highlightCode(code, language)
    }

    /**
     * Syntax highlighting via syntect (Sublime Text engine) running in the
     * Rust core — same engine and themes as the Compose flow's Markdown
     * pipeline expects, spanning the whole [cc.ptoe.messenger.core] bridge.
     * Unknown languages and any bridge failure render as plain monospace.
     */
    private fun highlightCode(codeText: String, language: String?): CharSequence {
        val theme = theme ?: return codeText
        val spans = runCatching {
            cc.ptoe.messenger.core.highlightCodeJson(codeText, language.orEmpty(), theme.isDark)
        }.getOrNull() ?: return codeText
        val parsed = runCatching {
            spanJson.parseToJsonElement(spans).let { el ->
                (el as? JsonArray)?.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    // Colors are unsigned 0xAARRGGBB — beyond Int range, read as Long
                    Triple(
                        obj["start"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                        obj["end"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                        obj["color"]?.jsonPrimitive?.longOrNull?.toInt() ?: return@mapNotNull null
                    )
                }
            }
        }.getOrNull() ?: return codeText
        if (parsed.isEmpty()) return codeText

        val builder = SpannableStringBuilder(codeText)
        for ((start, end, color) in parsed) {
            if (start in 0 until end && end <= codeText.length) {
                builder.setSpan(
                    ForegroundColorSpan(color),
                    start, end,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return builder
    }
}
