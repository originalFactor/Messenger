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
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class CodeBlockView(context: Context) : LinearLayout(context) {

    private val headerLayout = LinearLayout(context)
    private val langLabel = TextView(context)
    private val copyButton = TextView(context)
    private val codeText = TextView(context)

    init {
        orientation = VERTICAL
        val cornerRadius = 12f * context.resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#1E1E1E"))
            this.cornerRadius = cornerRadius
        }
        background = bg

        // Header
        headerLayout.orientation = HORIZONTAL
        headerLayout.gravity = Gravity.CENTER_VERTICAL
        val padH = (12 * context.resources.displayMetrics.density).toInt()
        val padV = (8 * context.resources.displayMetrics.density).toInt()
        headerLayout.setPadding(padH, padV, padH, padV)

        langLabel.setTextColor(Color.parseColor("#9E9E9E"))
        langLabel.textSize = 12f
        langLabel.typeface = Typeface.MONOSPACE
        val langLp = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        headerLayout.addView(langLabel, langLp)

        copyButton.text = "Copy"
        copyButton.setTextColor(Color.parseColor("#4FC3F7"))
        copyButton.textSize = 12f
        copyButton.setOnClickListener {
            val clip = ClipData.newPlainText("Code", codeText.text)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        headerLayout.addView(copyButton)

        addView(headerLayout)

        // Code body
        codeText.setTextColor(Color.parseColor("#D4D4D4"))
        codeText.typeface = Typeface.MONOSPACE
        codeText.textSize = 13f
        val bodyPad = (12 * context.resources.displayMetrics.density).toInt()
        codeText.setPadding(bodyPad, 0, bodyPad, bodyPad)
        codeText.setTextIsSelectable(true)
        addView(codeText)
    }

    fun bind(language: String?, code: String) {
        langLabel.text = language ?: "code"
        codeText.text = code
    }
}
