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

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/** Default tuning values used by [StreamingTypewriter] when the caller doesn't override them. */
object LlmTypewriterDefaults {

    /** Default reveal cadence: ~50 chars/second, a comfortable read for chat UIs. */
    const val DefaultBaseDelayMs: Long = 18L

    /**
     * Maximum buffered (unrevealed) characters before the typewriter starts collapsing — when the
     * model streams much faster than the reveal can keep up, this caps memory growth. The reveal
     * loop reads from the head of the buffer; bursts larger than this just get flushed
     * progressively.
     */
    const val DefaultBufferSoftCap: Int = 4000

    /** Default markdown styling — wired only when the caller uses [MarkdownTypewriterRenderer]. */
    @Composable
    @ReadOnlyComposable
    fun markdownStyles(): MarkdownStyles = MarkdownStyles(
        bold = SpanStyle(fontWeight = FontWeight.SemiBold),
        italic = SpanStyle(fontWeight = FontWeight.Normal, fontFamily = FontFamily.Default).copy(
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        ),
        code = SpanStyle(
            fontFamily = PlatformCodeBlockFontFamily,
            background = MaterialTheme.colorScheme.surfaceVariant,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        link = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
        heading = SpanStyle(fontWeight = FontWeight.Bold),
        strikethrough = SpanStyle(textDecoration = TextDecoration.LineThrough),
        codeBlockBackground = MaterialTheme.colorScheme.surfaceVariant,
        codeBlockText = MaterialTheme.colorScheme.onSurfaceVariant,
        codeBlockKeyword = MaterialTheme.colorScheme.primary,
        codeBlockString = MaterialTheme.colorScheme.tertiary,
        codeBlockComment = MaterialTheme.colorScheme.outline,
        codeBlockNumber = MaterialTheme.colorScheme.secondary,
        math = SpanStyle(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        displayMathBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        displayScale = 1.2f,
        tableBorder = MaterialTheme.colorScheme.outlineVariant,
        tableHeaderBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
        tableBackground = MaterialTheme.colorScheme.surfaceContainer,
        blockQuoteStripe = MaterialTheme.colorScheme.outlineVariant,
        blockQuoteBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        thinkBlockStripe = MaterialTheme.colorScheme.secondary,
        thinkBlockBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        thinkBlockText = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    /** The same as [markdownStyles] but takes an explicit color scheme for testing. */
    fun markdownStyles(
        primary: Color,
        secondary: Color,
        tertiary: Color,
        surfaceVariant: Color,
        onSurfaceVariant: Color,
        outline: Color,
    ): MarkdownStyles = MarkdownStyles(
        bold = SpanStyle(fontWeight = FontWeight.SemiBold),
        italic = SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
        code = SpanStyle(
            fontFamily = PlatformCodeBlockFontFamily,
            background = surfaceVariant,
            color = onSurfaceVariant,
        ),
        link = SpanStyle(color = primary, textDecoration = TextDecoration.Underline),
        heading = SpanStyle(fontWeight = FontWeight.Bold),
        strikethrough = SpanStyle(textDecoration = TextDecoration.LineThrough),
        codeBlockBackground = surfaceVariant,
        codeBlockText = onSurfaceVariant,
        codeBlockKeyword = primary,
        codeBlockString = tertiary,
        codeBlockComment = outline,
        codeBlockNumber = secondary,
        math = SpanStyle(),
        displayMathBackground = surfaceVariant.copy(alpha = 0.4f),
        displayScale = 1.2f,
        tableBorder = outline, // M3: outlineVariant; tests pass the primary palette only
        tableHeaderBackground = surfaceVariant, // M3: surfaceContainerHigh
        tableBackground = surfaceVariant, // M3: surfaceContainer
        blockQuoteStripe = outline,
        blockQuoteBackground = surfaceVariant.copy(alpha = 0.3f),
        thinkBlockStripe = secondary,
        thinkBlockBackground = surfaceVariant.copy(alpha = 0.5f),
        thinkBlockText = onSurfaceVariant,
    )
}
