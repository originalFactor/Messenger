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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.ratex.DisplayItem
import io.ratex.DisplayList
import io.ratex.RaTeXEngine
import io.ratex.RaTeXFontLoader
import io.ratex.compose.drawDisplayList
import io.ratex.measure
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/**
 * Bridge to [RaTeX-CMP](https://github.com/darriousliu/RaTeX-CMP) — the same
 * pure-Rust KaTeX-compatible engine the Compose chat flow uses — rendering its
 * display lists for the native renderer.
 *
 * `RaTeXEngine.parseBlocking` yields a [DisplayList] in em units valid for any
 * target font size. Drawing replays the list through RaTeX's `drawDisplayList`
 * onto an android canvas via a [CanvasDrawScope], with glyphs painted by OUR
 * painter: the library's internal Android font cache is not reliably populated
 * outside a Composable lifecycle, so the KaTeX TTFs bundled in the RaTeX AAR
 * assets are loaded into a name→Typeface table here. Parse or font failures
 * return null and callers fall back to the built-in [MathEngine].
 */
internal object RatexMath {

    private val fontsReady = AtomicBoolean(false)
    private val drawScope = CanvasDrawScope()
    private val pxDensity = Density(1f)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val typefaceTable = HashMap<String, Typeface>()

    private const val CAPACITY = 128
    private val displayListCache = object : LinkedHashMap<String, DisplayList>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DisplayList>): Boolean =
            size > CAPACITY
    }

    private fun ensureFonts() {
        if (fontsReady.compareAndSet(false, true)) {
            // Registers the KaTeX font metrics with the Rust engine so parse
            // produces positioned glyphs. Android Typeface loading happens in
            // [typefaces] at draw time.
            runCatching { runBlocking { RaTeXFontLoader.ensureLoaded() } }
        }
    }

    /**
     * Parse a complete formula. [colorInt] is 0xAARRGGBB. Returns null when
     * RaTeX cannot parse the input (caller falls back to [MathEngine]).
     */
    fun displayList(formula: String, displayMode: Boolean, colorInt: Int): DisplayList? {
        val trimmed = formula.trim()
        if (trimmed.isEmpty()) return null
        val key = "$trimmed|$displayMode|$colorInt"
        synchronized(displayListCache) { displayListCache[key] }?.let { return it }
        ensureFonts()
        // Compose Color(Long) packs sRGB in the LOW 32 bits (0xAARRGGBB)
        val packed = Color(colorInt.toLong() and 0xFFFFFFFFL)
        val list = runCatching { RaTeXEngine.parseBlocking(trimmed, displayMode, packed) }.getOrNull()
        if (list != null) {
            synchronized(displayListCache) { displayListCache[key] = list }
        }
        return list
    }

    /**
     * Draw a display list at [fontSizePx] onto an android canvas. The list's
     * top-left corner lands at the canvas's current origin.
     */
    fun draw(canvas: Canvas, dl: DisplayList, fontSizePx: Float, context: Context) {
        val widthPx = ceil(dl.width.toFloat() * fontSizePx) + 2f
        val heightPx = ceil((dl.height + dl.depth).toFloat() * fontSizePx) + 2f
        drawScope.draw(
            density = pxDensity,
            layoutDirection = LayoutDirection.Ltr,
            canvas = ComposeCanvas(canvas),
            size = Size(widthPx, heightPx)
        ) {
            drawDisplayList(dl, fontSizePx) { glyph, size ->
                drawGlyph(context, this, glyph, size)
            }
        }
    }

    private fun drawGlyph(context: Context, scope: DrawScope, glyph: DisplayItem.GlyphPath, fontSizePx: Float) {
        val table = typefaces(context)
        // Display lists reference bare names ("Main-Regular"); the AAR assets
        // use the KaTeX_ prefix.
        val typeface = table[glyph.font] ?: table["KaTeX_${glyph.font}"] ?: return
        glyphPaint.typeface = typeface
        glyphPaint.textSize = (fontSizePx * glyph.scale).toFloat()
        glyphPaint.color = glyph.color.toArgb()
        scope.drawContext.canvas.nativeCanvas.drawText(
            String(Character.toChars(glyph.charCode)),
            (glyph.x * fontSizePx).toFloat(),
            (glyph.y * fontSizePx).toFloat(),
            glyphPaint
        )
    }

    /** KaTeX font name → Typeface, loaded once from the RaTeX AAR assets. */
    private fun typefaces(context: Context): Map<String, Typeface> {
        if (typefaceTable.isEmpty()) {
            synchronized(typefaceTable) {
                if (typefaceTable.isEmpty()) {
                    runCatching {
                        context.assets.list(FONT_ASSET_DIR)?.orEmpty()?.forEach { fileName ->
                            Typeface.createFromAsset(context.assets, "$FONT_ASSET_DIR/$fileName")
                                .let { typefaceTable[fileName.removeSuffix(".ttf")] = it }
                        }
                    }
                }
            }
        }
        return typefaceTable
    }

    class BitmapInfo(val bitmap: Bitmap, val ascentPx: Float, val descentPx: Float)

    /**
     * Rasterize a formula into a transparent bitmap for inline math spans.
     * [ascentPx]/[descentPx] place the formula's own baseline so [MathSpan]
     * can align it with the surrounding text.
     */
    fun bitmap(
        context: Context,
        formula: String,
        displayMode: Boolean,
        fontSizePx: Float,
        colorInt: Int
    ): BitmapInfo? {
        val dl = displayList(formula, displayMode, colorInt) ?: return null
        val measured = dl.measure(fontSizePx)
        val widthPx = ceil(measured.widthPx).toInt() + 2
        val heightPx = ceil(measured.totalHeightPx).toInt() + 2
        if (widthPx <= 2 || heightPx <= 2) return null
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val androidCanvas = Canvas(bitmap)
        androidCanvas.translate(1f, 1f)
        draw(androidCanvas, dl, fontSizePx, context)
        return BitmapInfo(
            bitmap = bitmap,
            ascentPx = measured.heightPx.toFloat(), // above baseline
            descentPx = measured.depthPx.toFloat()  // below baseline
        )
    }

    private fun io.ratex.RaTeXColor.toArgb(): Int {
        val a = (this.a * 255f).toInt().coerceIn(0, 255)
        val r = (this.r * 255f).toInt().coerceIn(0, 255)
        val g = (this.g * 255f).toInt().coerceIn(0, 255)
        val b = (this.b * 255f).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private const val FONT_ASSET_DIR = "composeResources/io.ratex.compose.resources/files/fonts"
}
