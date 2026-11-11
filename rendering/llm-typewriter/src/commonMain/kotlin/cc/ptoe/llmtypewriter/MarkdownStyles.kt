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

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp

/** Composable hook used to load and paint a Markdown image URL. */
fun interface MarkdownImageRenderer {
    @Composable
    fun Render(url: String, altText: String)
}

/**
 * Style overrides used by [MarkdownTypewriterRenderer] when painting a markdown stream.
 *
 * Every field is a plain [SpanStyle] / [Color] so the caller can swap in their own design system —
 * the defaults in [LlmTypewriterDefaults.markdownStyles] resolve against the active MaterialTheme.
 *
 * Math rendering note: LaTeX fragments (`$…$` / `$$…$$`) are delegated to RaTeX-CMP's
 * `RaTeX` composable, which renders with KaTeX-compatible fonts via the Rust core. Of the [math]
 * field, only [SpanStyle.color] is honored — other SpanStyle properties (fontFamily, fontStyle, …)
 * are ignored because RaTeX owns the typography.
 */
@Immutable
data class MarkdownStyles(
    val bold: SpanStyle,
    val italic: SpanStyle,
    val code: SpanStyle,
    val link: SpanStyle,
    val heading: SpanStyle,
    val strikethrough: SpanStyle,
    val codeBlockBackground: Color,
    val codeBlockText: Color,
    val codeBlockKeyword: Color,
    val codeBlockString: Color,
    val codeBlockComment: Color,
    val codeBlockNumber: Color,
    /** Color for math fragments (`$...$` inline, `$$...$$` display). Only [SpanStyle.color] is
     *  honored — see class kdoc. */
    val math: SpanStyle = SpanStyle(),
    /** Background tint for display-math (`$$...$$`) blocks. */
    val displayMathBackground: Color = Color.Unspecified,
    /** Scale multiplier applied to the base font size when rendering display math. */
    val displayScale: Float = 1.2f,
    /** Border color for GFM table cells. */
    val tableBorder: Color = Color.Unspecified,
    /** Background tint for the header row of a GFM table. */
    val tableHeaderBackground: Color = Color.Unspecified,
    /** Background color for the rounded GFM table surface. */
    val tableBackground: Color = Color.Unspecified,
    /** Stripe color for markdown block quotes (`> quote`). */
    val blockQuoteStripe: Color = Color.Unspecified,
    /** Background tint for markdown block quotes (`> quote`). */
    val blockQuoteBackground: Color = Color.Unspecified,
    /** Stripe color for think blocks (`<think>...</think>`). */
    val thinkBlockStripe: Color = Color.Unspecified,
    /** Background tint for think blocks. */
    val thinkBlockBackground: Color = Color.Unspecified,
    /** Text color for think blocks. */
    val thinkBlockText: Color = Color.Unspecified,
    /** Style for superscript footnote references such as `[^1]`. */
    val footnoteReference: SpanStyle = SpanStyle(),
    /** Color for compact footnote definitions rendered below the main content. */
    val footnoteDefinition: Color = Color.Unspecified,
    /** Image loader/renderer. The default uses Coil; applications can replace it if needed. */
    val imageRenderer: MarkdownImageRenderer = MarkdownImageRenderer { url, altText ->
        val shape = RoundedCornerShape(8.dp)
        val fallbackLabel = altText.ifBlank { url }
        val imageLoader = rememberPlatformImageLoader()
        val imageModifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
        SubcomposeAsyncImage(
            model = url,
            imageLoader = imageLoader,
            contentDescription = altText,
            contentScale = ContentScale.FillWidth,
            modifier = imageModifier,
            loading = {
                if (fallbackLabel.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            text = "loading: $fallbackLabel",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            onError = { state ->
                logPlatformImageLoadError(url, state.result.throwable)
            },
            error = {
                Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(
                        text = "error: $fallbackLabel",
                        color = LocalContentColor.current,
                    )
                }
            },
            success = { SubcomposeAsyncImageContent() },
        )
    }
) {
    // [imageRenderer] 是函数式接口（lambda 实例），引用相等；默认样式工厂每次
    // 组合都会重建 lambda，导致 data class 默认的结构相等永远为 false。renderer
    // 是行为参数而非视觉参数，因此不等价参与 equals/hashCode —— 保证同一套颜色
    // 字体的 MarkdownStyles 在流式重组合中结构相等，从而命中 remember 记忆化缓存。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MarkdownStyles) return false
        return bold == other.bold &&
            italic == other.italic &&
            code == other.code &&
            link == other.link &&
            heading == other.heading &&
            strikethrough == other.strikethrough &&
            codeBlockBackground == other.codeBlockBackground &&
            codeBlockText == other.codeBlockText &&
            codeBlockKeyword == other.codeBlockKeyword &&
            codeBlockString == other.codeBlockString &&
            codeBlockComment == other.codeBlockComment &&
            codeBlockNumber == other.codeBlockNumber &&
            math == other.math &&
            displayMathBackground == other.displayMathBackground &&
            displayScale == other.displayScale &&
            tableBorder == other.tableBorder &&
            tableHeaderBackground == other.tableHeaderBackground &&
            tableBackground == other.tableBackground &&
            blockQuoteStripe == other.blockQuoteStripe &&
            blockQuoteBackground == other.blockQuoteBackground &&
            thinkBlockStripe == other.thinkBlockStripe &&
            thinkBlockBackground == other.thinkBlockBackground &&
            thinkBlockText == other.thinkBlockText &&
            footnoteReference == other.footnoteReference &&
            footnoteDefinition == other.footnoteDefinition
    }

    override fun hashCode(): Int {
        var result = bold.hashCode()
        result = 31 * result + italic.hashCode()
        result = 31 * result + code.hashCode()
        result = 31 * result + link.hashCode()
        result = 31 * result + heading.hashCode()
        result = 31 * result + strikethrough.hashCode()
        result = 31 * result + codeBlockBackground.hashCode()
        result = 31 * result + codeBlockText.hashCode()
        result = 31 * result + codeBlockKeyword.hashCode()
        result = 31 * result + codeBlockString.hashCode()
        result = 31 * result + codeBlockComment.hashCode()
        result = 31 * result + codeBlockNumber.hashCode()
        result = 31 * result + math.hashCode()
        result = 31 * result + displayMathBackground.hashCode()
        result = 31 * result + displayScale.hashCode()
        result = 31 * result + tableBorder.hashCode()
        result = 31 * result + tableHeaderBackground.hashCode()
        result = 31 * result + tableBackground.hashCode()
        result = 31 * result + blockQuoteStripe.hashCode()
        result = 31 * result + blockQuoteBackground.hashCode()
        result = 31 * result + thinkBlockStripe.hashCode()
        result = 31 * result + thinkBlockBackground.hashCode()
        result = 31 * result + thinkBlockText.hashCode()
        result = 31 * result + footnoteReference.hashCode()
        result = 31 * result + footnoteDefinition.hashCode()
        return result
    }
}
