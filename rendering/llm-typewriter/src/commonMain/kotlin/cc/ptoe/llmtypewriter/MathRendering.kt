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

package cc.ptoe.llmtypewriter

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import io.ratex.compose.RaTeX
import io.ratex.compose.rememberBlockingRaTeXDisplayList
import io.ratex.measure
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Platform LaTeX math renderer backed by [RaTeX-CMP](https://github.com/darriousliu/RaTeX-CMP).
 *
 * RaTeX-CMP wraps a pure-Rust KaTeX-compatible core and exposes Compose Multiplatform
 * composables, so the same rendering path runs on Android, JVM Desktop (and iOS/Web). It parses
 * the full LaTeX grammar — fractions, big operators with stacked limits, roots, etc. — with
 * KaTeX-compatible fonts, so the local `TexParser` and `MathAst` are no longer used for rendering;
 * they're retained for prefix-stability testing and future programmatic AST use.
 *
 * Called once a math fragment is complete (the closing `$` or `$$` has arrived). Before that, the
 * streaming Markdown parser emits the partial input as plain text — so this function is never
 * called with a half-formed LaTeX string. This matches the user's requirement: "一旦一个 inline/block
 * LaTeX 完成，就丢给 RaTeX 渲染。在此之前直接显示原文本即可".
 *
 * @param latex The complete LaTeX fragment, e.g. `\frac{a}{b}` (no surrounding `$`).
 * @param displayMode `true` for `$$…$$` (block-level, oversized); `false` for inline `$…$`.
 * @param textColor Color to apply to the rendered equation.
 * @param fontSize Font size for the equation. Display mode is additionally scaled by
 *   [MarkdownStyles.displayScale] by the caller.
 * @param modifier Layout modifier.
 */
@Composable
internal fun RenderPlatformMath(
    latex: String,
    displayMode: Boolean,
    textColor: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    RaTeX(
        latex = latex,
        modifier = modifier,
        fontSize = fontSize,
        displayMode = displayMode,
        color = textColor,
    )
}

/**
 * Measures the rendered size (in pixels) of a LaTeX fragment without displaying it. Used by the
 * inline-math renderer to size the [androidx.compose.foundation.text.InlineTextContent]
 * placeholder to the equation's actual width instead of a fixed estimate, so each inline
 * equation gets its own width and surrounding text reflows correctly.
 *
 * Contractually returns `IntSize.Zero` only when [latex] is empty. Otherwise RaTeX-CMP parses the
 * fragment synchronously via [rememberBlockingRaTeXDisplayList] and measures the resulting
 * [io.ratex.DisplayList] at [fontSize]. The result is cached per (latex, displayMode, fontSizePx)
 * triple in the process-wide [MathMeasureCache], so a given equation is measured at most once for
 * the app's lifetime — across recompositions, across Composable instances, across chat bubbles.
 *
 * @param latex The complete LaTeX fragment (no surrounding `$`).
 * @param displayMode `true` for display style, `false` for inline text style.
 * @param fontSize Font size in sp.
 * @return Measured size in device pixels.
 */
@Composable
internal fun measurePlatformMath(
    latex: String,
    displayMode: Boolean,
    fontSize: TextUnit,
): IntSize {
    if (latex.isEmpty()) return IntSize.Zero
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSize.toPx() }
    val key = MathMeasureKey(latex, displayMode, fontSizePx.roundToInt())

    // Process-wide LRU cache hit — skip parsing/measuring entirely.
    MathMeasureCache.get(key)?.let { return it }

    // Cache miss: parse synchronously (composition-scoped cache via remember) and measure the
    // resulting DisplayList. Subsequent calls for the same inputs hit the cache above and never
    // reach this branch, so rememberBlockingRaTeXDisplayList runs at most once per triple.
    val parseResult = rememberBlockingRaTeXDisplayList(
        latex = latex,
        displayMode = displayMode,
    )
    val displayList = parseResult.getOrNull()
    val size = if (displayList != null) {
        val measured = displayList.measure(fontSizePx)
        IntSize(
            ceil(measured.widthPx).toInt(),
            ceil(measured.totalHeightPx).toInt(),
        )
    } else {
        IntSize.Zero
    }
    MathMeasureCache.put(key, size)
    return size
}

/** Key for [MathMeasureCache]. [fontSizePx] is rounded so floating-point drift doesn't miss. */
private data class MathMeasureKey(
    val latex: String,
    val displayMode: Boolean,
    val fontSizePx: Int,
)

/**
 * Process-wide LRU cache of measured math sizes. Keyed on a (latex, displayMode, fontSizePx)
 * triple. A given equation is only laid out once for the lifetime of the app — across
 * recompositions, across Composable instances, across different chat bubbles — instead of once
 * per recomposition (the old `remember`-only cache was scoped to a single Composable call site
 * and was lost whenever the inline-run block was rebuilt).
 *
 * 256 entries is plenty for a chat session; each entry is a few hundred bytes. Backed by an
 * access-ordered [LinkedHashMap] (LRU) since `android.util.LruCache` is Android-only and this
 * code now lives in commonMain. Access is serialized via `@Synchronized` because the cache is
 * shared across composition scopes (and potentially threads).
 */
private object MathMeasureCache {
    private const val CAPACITY = 256

    private val cache = object : LinkedHashMap<MathMeasureKey, IntSize>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MathMeasureKey, IntSize>?): Boolean =
            size > CAPACITY
    }

    @Synchronized
    fun get(key: MathMeasureKey): IntSize? = cache[key]

    @Synchronized
    fun put(key: MathMeasureKey, size: IntSize) {
        cache[key] = size
    }
}
