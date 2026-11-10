/**
 * Reserve the complete equation bounds. A matrix or fraction may increase the line height;
 * constraining it to prose line height clips its lower rows and descenders.
 */
@Composable
private fun RenderInlineRunWithMath(segments: List<InlineSegment>, styles: MarkdownStyles) {
    val baseStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val resolvedFontSize = baseStyle.fontSize.takeIf { it.value > 0f } ?: 16.sp
    val mathFontSize = inlineMathFontSize(resolvedFontSize)
    val mathSizes = mutableListOf<androidx.compose.ui.unit.IntSize>()
    for (segment in segments) {
        mathSizes += if (segment is InlineSegment.Math) {
            measurePlatformMath(segment.content, displayMode = false, mathFontSize)
        } else {
            androidx.compose.ui.unit.IntSize.Zero
        }
    }
    val built = remember(segments, styles, mathFontSize, density, mathSizes) {
        val contents = mutableMapOf<String, InlineTextContent>()
        val annotated = buildAnnotatedString {
            for ((index, segment) in segments.withIndex()) {
                when (segment) {
                    is InlineSegment.Text -> append(buildAnnotatedFromInline(segment.tokens, styles))
                    is InlineSegment.Math -> {
                        val id = "math_$index"
                        appendInlineContent(id, segment.content)
                        val size = mathSizes[index]
                        contents[id] = InlineTextContent(
                            placeholder = Placeholder(
                                width = with(density) { size.width.coerceAtLeast(1).toSp() },
                                height = with(density) { size.height.coerceAtLeast(1).toSp() },
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ),
                        ) {
                            RenderInlineMath(segment.content, styles)
                        }
                    }
                }
            }
        }
        annotated to contents
    }
    Text(text = built.first, inlineContent = built.second)
}

/**
 * KaTeX's main font has a smaller x-height (0.431 em) than the app's Roboto body font
 * (approximately 0.52 em). Match their visual text size without fitting tall expressions into
 * the prose line. Measurement and painting must always use this same scale, including fontScale.
 */
private fun inlineMathFontSize(bodyFontSize: TextUnit): TextUnit = bodyFontSize * 1.2f

@Composable
private fun RenderInlineMath(content: String, styles: MarkdownStyles) {
    val baseStyle = LocalTextStyle.current
    val resolvedFontSize = baseStyle.fontSize.takeIf { it.value > 0f } ?: 16.sp
    val textColor = styles.math.color.takeIf { it != Color.Unspecified } ?: LocalContentColor.current
    Box(contentAlignment = Alignment.Center) {
        RenderPlatformMath(
            latex = content,
            displayMode = false,
            textColor = textColor,
            fontSize = inlineMathFontSize(resolvedFontSize),
        )
    }
}
