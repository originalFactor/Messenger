/**
 * Renders an inline run containing math segments interleaved with text. Uses [Text] with
 * [InlineTextContent] instead of [FlowRow] so that:
 *  - Line height is controlled entirely by [TextStyle.lineHeight] (no FlowRow row-height
 *    inflation). This fixes "行距 2-3 行那么高" caused by FlowRow's row height being max(cell
 *    heights), which was larger than a normal text line because AndroidView's internal measure
 *    reported a larger height than the text line height.
 *  - Text wrapping is handled natively by [Text] — long sentences with embedded math wrap at
 *    word boundaries automatically.
 *  - No layout-type switching between [MdBlock.Inline] and [MdBlock.InlineRunWithMath] mid-stream
 *    — both use [Text], so when the parser promotes a block to InlineRunWithMath mid-stream (on
 *    the closing `$`), the surrounding layout doesn't jump ("上面那行直接一个闪烁往上移了一行").
 *
 * Each math segment becomes an `appendInlineContent` marker in the [AnnotatedString]; the
 * [InlineTextContent] map renders each marker via [RenderInlineMath]. The placeholder width is
 * the equation's **actual measured width** (via [measurePlatformMath]) so each inline equation
 * gets its own width and surrounding text reflows correctly — previously every equation got a
 * fixed `fontSize * 4` slot regardless of content. The placeholder height stays at the text line
 * height so the math view cannot inflate the line; any vertical overflow is clipped (see
 * [RenderInlineMath]).
 */
@Composable
private fun RenderInlineRunWithMath(segments: List<InlineSegment>, styles: MarkdownStyles) {
    val baseStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val resolvedFontSize = baseStyle.fontSize.let { fs ->
        if (fs.value > 0f) fs else 16.sp
    }
    val lineHeight = baseStyle.lineHeight.let { lh ->
        if (lh.value > 0f) lh else resolvedFontSize * 1.2f
    }

    // Pre-measure each math fragment so the placeholder width matches the equation's actual
    // rendered width (instead of a fixed estimate). A manual loop avoids calling a @Composable
    // from an inline lambda such as List.map, which the Compose compiler rejects.
    val mathWidths = mutableListOf<TextUnit>()
    for (segment in segments) {
        if (segment is InlineSegment.Math) {
            val measured = measurePlatformMath(segment.content, displayMode = false, resolvedFontSize)
            mathWidths += with(density) { measured.width.toSp() }
        } else {
            mathWidths += TextUnit.Unspecified
        }
    }

    // Collect math segments for inline content; build the annotated string with markers.
    // 稳定前缀块（segments 结构相等，流式下不变化）复用已构建的 AnnotatedString，
    // 仅尾部增长的块会随 token 重建 — 避免每 token 对整段重建/重测量。
    // resolvedFontSize/lineHeight 参与 key，字体缩放变化时强制重建，占位宽度不失效。
    val built = remember(segments, styles, resolvedFontSize, lineHeight) {
        val contents = mutableMapOf<String, InlineTextContent>()
        val annotated = buildAnnotatedString {
            var mathIndex = 0
            var widthIndex = 0
            for (segment in segments) {
                when (segment) {
                    is InlineSegment.Text -> append(buildAnnotatedFromInline(segment.tokens, styles))
                    is InlineSegment.Math -> {
                        val id = "math_${mathIndex++}"
                        appendInlineContent(id, segment.content)
                        val width = mathWidths[widthIndex]
                        contents[id] = InlineTextContent(
                            placeholder = Placeholder(
                                width = width,
                                height = lineHeight,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ),
                        ) {
                            RenderInlineMath(segment.content, styles)
                        }
                    }
                }
                widthIndex++
            }
        }
        annotated to contents
    }

    Text(text = built.first, inlineContent = built.second)
}

/**
 * Inline math — delegates to [RenderPlatformMath] (RaTeX-CMP on every target) with
 * `displayMode = false` so the equation renders in text style and sits inline with the
 * surrounding text. The [content] is the complete LaTeX fragment (the closing `$` has arrived);
 * before that, the parser emits the partial input as plain text, so this is never called with a
 * half-formed string.
 *
 * The math view is wrapped in a [Box] whose height is forced to the text line height via
 * [Modifier.requiredHeight] (stronger than [Modifier.height] — ignores parent constraints) plus
 * [Modifier.clipToBounds] so the (taller) math composable cannot inflate the FlowRow row height.
 * The math view's visual overflow is clipped within the box. Center-aligned so the math sits at the
 * text's visual midline (fixes "行内TeX整体偏高/偏低" — BottomStart pushed it too low, plain
 * Bottom let it sit too high).
 */
@Composable
private fun RenderInlineMath(content: String, styles: MarkdownStyles) {
    val baseStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val resolvedFontSize = baseStyle.fontSize.let { fs ->
        if (fs.value > 0f) fs else 16.sp
    }
    val lineHeight = baseStyle.lineHeight.let { lh ->
        if (lh.value > 0f) lh else resolvedFontSize * 1.2f
    }
    val lineHeightDp = with(density) { lineHeight.toDp() }
    val textColor = styles.math.color.takeIf { it != Color.Unspecified } ?: LocalContentColor.current
    Box(
        modifier = Modifier
            .requiredHeight(lineHeightDp)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        RenderPlatformMath(
            latex = content,
            displayMode = false,
            textColor = textColor,
            fontSize = resolvedFontSize,
        )
    }
}
