/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package cc.ptoe.messenger.presentation.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InlineMathLayoutTest {
    @Test fun inlineMathUsesFullHeightAndBodyGlyphSize() = checkLayout(1f)
    @Test fun inlineMathReservesScaledBounds() = checkLayout(1.5f)

    @Test fun listMarkersFollowFirstTextBaseline() = checkListBaselines(1f)
    @Test fun listMarkersFollowScaledFirstTextBaseline() = checkListBaselines(1.5f)

    private fun checkListBaselines(fontScale: Float) = runComposeUiTest {
        val matrix = "\$\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}\$"
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme {
                    ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(color = Color.Black)) {
                        Column(Modifier.width(320.dp).background(Color.White)) {
                            ChatMarkdownText(
                                "- Bullet: $matrix with additional words that wrap onto another line.",
                                false, Modifier,
                            )
                            ChatMarkdownText("7. Number: $matrix", false, Modifier)
                            ChatMarkdownText("- [ ] Task: $matrix", false, Modifier)
                        }
                    }
                }
            }
        }
        for ((marker, prefix) in listOf("•" to "Bullet:", "7." to "Number:", "☐" to "Task:")) {
            fun baseline(text: String, substring: Boolean): Float {
                val node = onNodeWithText(text, substring = substring)
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                return node.getUnclippedBoundsInRoot().top.value + layouts.single().firstBaseline
            }
            val markerBaseline = baseline(marker, false)
            val bodyBaseline = baseline(prefix, true)
            assertTrue(abs(markerBaseline - bodyBaseline) < 1f,
                "$marker baseline $markerBaseline must follow body $bodyBaseline at fontScale $fontScale")
        }
        val screenshot = onRoot().captureToImage()
        Image.makeFromBitmap(screenshot.asSkiaBitmap()).use { image ->
            image.encodeToData()?.use { data ->
                File(System.getProperty("java.io.tmpdir"), "messenger-list-baseline-$fontScale.png").writeBytes(data.bytes)
            }
        }
    }

    private fun checkLayout(fontScale: Float) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme {
                    ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(color = Color.Black)) {
                        Column(Modifier.width(720.dp).background(Color.White)) {
                            Text("xxxxxxxxxx", Modifier.testTag("prose"))
                            ChatMarkdownText("\$xxxxxxxxxx\$", false, Modifier.testTag("math"))
                            ChatMarkdownText(
                                "Vectors and matrices: \$\\vec v=(v_1,v_2),\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}\$.",
                                false, Modifier.testTag("matrix"),
                            )
                            ChatMarkdownText(
                                "Fractions: \$\\frac{a}{b}+\\sqrt{x}+a_k^{(n)}\$.\n\nThe next paragraph stays below the equation.",
                                false, Modifier.testTag("fraction"),
                            )
                        }
                    }
                }
            }
        }
        // RaTeX loads its glyph fonts asynchronously; check actual paint rather than an empty
        // Canvas with correct layout bounds. Capture also checks glyphs survive placeholder clipping.
        fun inkHeight(tag: String): Int {
            val pixels = onNodeWithTag(tag).captureToImage().toPixelMap()
            val rows = (0 until pixels.height).filter { y ->
                (0 until pixels.width).any { x ->
                    val p = pixels[x, y]
                    p.alpha > .5f && p.red < .5f && p.green < .5f && p.blue < .5f
                }
            }
            return if (rows.isEmpty()) 0 else rows.last() - rows.first() + 1
        }
        waitUntil(timeoutMillis = 10_000) { inkHeight("math") > 0 }
        val proseInk = inkHeight("prose")
        val mathInk = inkHeight("math")
        assertTrue(abs(proseInk - mathInk) <= 2, "Body x-height $proseInk vs math $mathInk at fontScale $fontScale")
        val prose = onNodeWithTag("prose").getUnclippedBoundsInRoot()
        val matrix = onNodeWithTag("matrix").getUnclippedBoundsInRoot()
        assertTrue(matrix.height > prose.height * 1.4f, "Matrix must expand line height: $matrix vs $prose")
        val matrixInk = inkHeight("matrix")
        assertTrue(matrixInk > proseInk * 2, "Both matrix rows must paint: ink height $matrixInk")
        val fraction = onNodeWithTag("fraction").getUnclippedBoundsInRoot()
        assertTrue(fraction.top >= matrix.bottom, "Following paragraph must not overlap tall math")
        val screenshot = onRoot().captureToImage()
        Image.makeFromBitmap(screenshot.asSkiaBitmap()).use { image ->
            image.encodeToData()?.use { data ->
                File(System.getProperty("java.io.tmpdir"), "messenger-inline-math-$fontScale.png").writeBytes(data.bytes)
            }
        }
    }
}
