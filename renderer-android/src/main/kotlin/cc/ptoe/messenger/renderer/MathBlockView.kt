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
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import io.ratex.DisplayList
import io.ratex.measure
import kotlin.math.min

/**
 * Display-mode math block. Formulas render through RaTeX (the pure-Rust
 * KaTeX-compatible engine the Compose flow uses, drawn as display-list
 * geometry onto this view's canvas) and fall back to the built-in
 * [MathEngine] when RaTeX cannot parse the input. Either path caches its
 * layout ([MathLayoutCache] / [RatexMath]) and scales down uniformly when
 * wider than the bubble.
 */
class MathBlockView(context: Context) : View(context) {

    private var formula: String = ""
    private var isFinalized: Boolean = true
    private var isDisplayMode: Boolean = true
    private var color: Int = Color.parseColor("#1565C0")

    private var ratexList: DisplayList? = null
    private var engineList: MathDisplayList? = null
    private var drawScale: Float = 1f
    private var contentWidth: Float = 0f
    private var contentHeight: Float = 0f

    init {
        val padH = (12 * context.resources.displayMetrics.density).toInt()
        val padV = (8 * context.resources.displayMetrics.density).toInt()
        setPadding(padH, padV, padH, padV)
    }

    /** Layouts hold geometry only; color is a draw/parse-time property. */
    fun updateTheme(theme: RendererTheme?) {
        color = theme?.primary ?: Color.parseColor("#1565C0")
        ratexList = null
        engineList = null
        requestLayout()
        invalidate()
    }

    fun bind(formulaText: String, finalized: Boolean, display: Boolean = true) {
        if (this.formula == formulaText && this.isFinalized == finalized && this.isDisplayMode == display) {
            return
        }
        this.formula = formulaText
        this.isFinalized = finalized
        this.isDisplayMode = display
        this.ratexList = null
        this.engineList = null
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val fontSizePx = 16f * density * (if (isDisplayMode) 1.15f else 1f)

        val ratex = ratexList ?: RatexMath.displayList(formula, isDisplayMode, color)
        ratexList = ratex
        if (ratex != null) {
            val measured = ratex.measure(fontSizePx)
            contentWidth = measured.widthPx
            contentHeight = measured.totalHeightPx
        } else {
            val key = MathCacheKey(formula, fontSizePx.toInt(), isDisplayMode)
            var dl = MathLayoutCache.get(key)
            if (dl == null) {
                dl = MathEngine.compile(formula, fontSizePx)
                if (isFinalized) {
                    MathLayoutCache.put(key, dl)
                }
            }
            engineList = dl
            contentWidth = dl.width
            contentHeight = dl.height
        }

        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
            .coerceAtLeast(0)
        drawScale = if (contentWidth > available && contentWidth > 0f) {
            min(1f, available / contentWidth)
        } else {
            1f
        }
        val naturalW = (contentWidth * drawScale).toInt() + paddingLeft + paddingRight
        val naturalH = (contentHeight * drawScale).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(naturalW, widthMeasureSpec), resolveSize(naturalH, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (contentWidth <= 0f || contentHeight <= 0f) return

        canvas.save()
        val contentW = contentWidth * drawScale
        val contentH = contentHeight * drawScale
        val availableW = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableH = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        val offsetX = paddingLeft + (availableW - contentW) / 2f
        val offsetY = paddingTop + (availableH - contentH) / 2f
        canvas.translate(offsetX, offsetY)
        if (drawScale < 1f) canvas.scale(drawScale, drawScale)
        val ratex = ratexList
        if (ratex != null) {
            val fontSizePx = 16f * resources.displayMetrics.density * (if (isDisplayMode) 1.15f else 1f)
            RatexMath.draw(canvas, ratex, fontSizePx, context)
        } else {
            engineList?.let { MathEngine.drawCommands(canvas, it, color) }
        }
        canvas.restore()
    }
}

sealed class MathCommand {
    data class DrawText(
        val text: String,
        val x: Float,
        val y: Float,
        val fontSize: Float,
        val isSymbol: Boolean
    ) : MathCommand()

    data class DrawLine(
        val startX: Float,
        val startY: Float,
        val stopX: Float,
        val stopY: Float,
        val thickness: Float
    ) : MathCommand()

    data class DrawRoot(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val thickness: Float
    ) : MathCommand()
}

data class MathDisplayList(
    val width: Float,
    val height: Float,
    /** Distance from the top edge to the formula's baseline (inline alignment). */
    val ascent: Float,
    val commands: List<MathCommand>
)

data class MathCacheKey(
    val formula: String,
    val fontSizePx: Int,
    val isDisplayMode: Boolean
)

object MathLayoutCache {
    private const val CAPACITY = 256
    private val cache = object : LinkedHashMap<MathCacheKey, MathDisplayList>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MathCacheKey, MathDisplayList>?): Boolean =
            size > CAPACITY
    }

    @Synchronized
    fun get(key: MathCacheKey): MathDisplayList? = cache[key]

    @Synchronized
    fun put(key: MathCacheKey, dl: MathDisplayList) {
        cache[key] = dl
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }
}
