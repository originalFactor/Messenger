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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit

/**
 * Paints the revealed text of a [StreamingTypewriterState] in some way.
 *
 * Two implementations ship out of the box:
 *  - [PlainTypewriterRenderer] — paints raw text in the current text style. Zero parsing cost.
 *  - [MarkdownTypewriterRenderer] — re-parses the revealed string every recomposition and paints
 *    headings, bold/italic/code/strikethrough/links/images inline. Fenced code blocks are painted as
 *    full-width blocks with syntax highlighting that builds up as the fence grows.
 */
fun interface TypewriterRenderer {

    @Composable
    fun Render(text: String, modifier: Modifier)
}

/** Renderer that paints the revealed text in the ambient text style with no parsing. */
val PlainTypewriterRenderer: TypewriterRenderer = TypewriterRenderer { text, modifier ->
    Text(text = text, modifier = modifier)
}

/**
 * Returns a renderer that lays out the revealed text as progressive Markdown — bold spans render
 * as bold the moment the closing `**` arrives, fenced code blocks build up line-by-line with
 * inline syntax highlighting, etc.
 *
 * The renderer never reflows already-painted content because [parseStreamingMarkdown] is
 * prefix-stable: the same prefix of input always yields the same prefix of tokens.
 *
 * Pass the owning [state] so the renderer can use [StreamingTypewriterState.revealedTokens]
 * (incrementally cached) instead of re-parsing the whole revealed string on every recomposition.
 */
@Composable
fun rememberMarkdownTypewriterRenderer(
    state: StreamingTypewriterState,
    styles: MarkdownStyles = LlmTypewriterDefaults.markdownStyles(),
): TypewriterRenderer = remember(state, styles) { MarkdownTypewriterRenderer(state, styles) }

/** Direct factory of a Markdown renderer for callers that want to pass styles manually. */
fun MarkdownTypewriterRenderer(
    state: StreamingTypewriterState,
    styles: MarkdownStyles,
): TypewriterRenderer = TypewriterRenderer { text, modifier ->
    RenderMarkdownStream(text, state, styles, modifier)
}

/**
 * A block of layout for the markdown renderer to paint sequentially. Pre-built outside the
 * composition so the `RenderMarkdownStream` body can be a clean for-loop of `@Composable` calls
 * (without the "non-composable nested function calls @Composable" error).
 *
 * `internal` (not `private`) so that pure-logic helpers like [buildListBlock] — which produce
 * [ListBlock]s — can be unit-tested without the Compose runtime.
 */
internal sealed class MdBlock {
    data class Inline(val tokens: List<MdToken>) : MdBlock()
    data class InlineWithImages(val tokens: List<MdToken>) : MdBlock()
    /** Inline run that contains math segments interleaved with text — laid out on a single
     *  wrapped line via [FlowRow] so math sits inline with surrounding words (not on its own row). */
    data class InlineRunWithMath(val segments: List<InlineSegment>) : MdBlock()
    data class BlockQuote(val lines: List<MdToken.BlockQuote>) : MdBlock()
    /**
     * A Markdown heading. [tokens] is the heading's inline content (already parsed into tokens by
     * [parseStreamingMarkdown]); the renderer paints them with a bold + scaled-font style so
     * inline formatting (`**bold**`, `` `code` ``, `[link](url)`, …) works inside headings.
     */
    data class Heading(val level: Int, val tokens: List<MdToken>) : MdBlock()
    data object HorizontalRule : MdBlock()
    data class Image(val altText: String, val url: String) : MdBlock()
    data class Code(val token: MdToken.CodeBlock) : MdBlock()
    /** Display math `$$...$$` — block-level, centered, with background. */
    data class DisplayMath(val content: String) : MdBlock()
    /** GFM table — header row + aligned data rows, rendered as a bordered grid. */
    data class Table(val token: MdToken.Table) : MdBlock()
    data class Footnote(val token: MdToken.FootnoteDefinition) : MdBlock()
    /** Think block `<think>...</think>` — rendered as a collapsible special quote. */
    data class ThinkBlock(val token: MdToken.ThinkBlock) : MdBlock()
    /**
     * A list (ordered or unordered). Built from a run of [MdToken.ListItem] tokens via
     * [buildListBlock]. The first item's [startNumber] becomes the list's start number for
     * ordered lists (so `3. ...` starts at 3); for unordered lists it's 0.
     *
     * Named `ListBlock` (not `List`) to avoid collision with `kotlin.collections.List` inside
     * this file's many `List<…>` parameter / variable types.
     */
    data class ListBlock(
        val ordered: Boolean,
        val startNumber: Int,
        val items: List<ListItemNode>,
    ) : MdBlock()
}

/**
 * One entry in an [MdBlock.ListBlock]. [inline] is the item's text content (already parsed into
 * tokens by the streaming parser); [sublists] are nested lists rendered indented under this item.
 * [checked] is `null` for a plain item, `false` / `true` for a GFM task-list item — the renderer
 * shows the Unicode ballot-box glyph (`☐` / `☑`) in place of the bullet when it is non-null.
 *
 * `internal` (not `private`) so [buildListBlock] can return it for unit testing.
 */
internal data class ListItemNode(
    val inline: List<MdToken>,
    val sublists: List<MdBlock.ListBlock>,
    val checked: Boolean? = null,
)

/** A segment within an [MdBlock.InlineRunWithMath] — either styled text or a math fragment.
 *  `internal` (not `private`) because [MdBlock] is internal and this type leaks through
 *  [MdBlock.InlineRunWithMath.segments]. */
internal sealed class InlineSegment {
    data class Text(val tokens: List<MdToken>) : InlineSegment()
    data class Math(val content: String) : InlineSegment()
}

private fun planBlocks(tokens: List<MdToken>, styles: MarkdownStyles): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    for (group in groupIntoParagraphs(mergeWhitespaceSeparatedThinkBlocks(tokens))) {
        // List items group together — consecutive ListItem tokens (with no intervening paragraph
        // break) form one [MdBlock.ListBlock]. Detected before the single-token branches below so
        // a lone list item doesn't fall into the inline-assembly path.
        val firstTok = group.firstOrNull()
        if (firstTok is MdToken.ListItem) {
            val listItems = group.filterIsInstance<MdToken.ListItem>()
            if (listItems.isNotEmpty()) {
                blocks += buildListBlock(listItems)
                continue
            }
        }
        if (firstTok is MdToken.BlockQuote) {
            val quoteLines = group.filterIsInstance<MdToken.BlockQuote>()
            if (quoteLines.isNotEmpty()) {
                blocks += MdBlock.BlockQuote(quoteLines)
                continue
            }
        }
        if (group.size == 1) {
            when (val tok = group[0]) {
                is MdToken.CodeBlock -> { blocks += MdBlock.Code(tok); continue }
                is MdToken.Heading -> { blocks += MdBlock.Heading(tok.level, tok.inline); continue }
                MdToken.HorizontalRule -> { blocks += MdBlock.HorizontalRule; continue }
                is MdToken.Image -> { blocks += MdBlock.Image(tok.altText, tok.url); continue }
                is MdToken.DisplayMath -> { blocks += MdBlock.DisplayMath(tok.content); continue }
                is MdToken.Table -> { blocks += MdBlock.Table(tok); continue }
                is MdToken.FootnoteDefinition -> { blocks += MdBlock.Footnote(tok); continue }
                is MdToken.ThinkBlock -> { blocks += MdBlock.ThinkBlock(tok); continue }
                else -> Unit
            }
        }
        val hasMath = group.any { it is MdToken.InlineMath }
        val hasImage = group.any { it is MdToken.Image }
        if (hasImage) {
            blocks += MdBlock.InlineWithImages(group)
        } else if (hasMath) {
            blocks += MdBlock.InlineRunWithMath(buildInlineSegments(group, styles))
        } else {
            blocks += MdBlock.Inline(group)
        }
    }
    return blocks
}

/**
 * Builds an [MdBlock.List] tree from a flat run of [MdToken.ListItem] tokens. Indentation drives
 * nesting — an item with `indent = N + 1` (deeper than the previous `indent = N`) becomes a child
 * of the previous item at indent `N`. Items at the same indent are siblings.
 *
 * The stack holds the active nesting path; each level knows its [ordered] / [startNumber] / [indent]
 * plus a mutable list of items being built. When a new item arrives:
 *   1. Pop levels deeper than the new item's indent.
 *   2. If the top is shallower than the new item's indent, push a new level (as a sublist of the
 *      top's last item). Indent jumps larger than 1 are clamped to `top.indent + 1` so a stray
 *      6-space indent doesn't create 3 phantom nesting levels.
 *   3. Add the item to the top level.
 *
 * Pure and side-effect-free so it's unit-testable without the Compose runtime.
 */
internal fun buildListBlock(tokens: List<MdToken.ListItem>): MdBlock.ListBlock {
    if (tokens.isEmpty()) return MdBlock.ListBlock(ordered = false, startNumber = 0, items = emptyList())
    val root = MutableListLevel(
        ordered = tokens.first().ordered,
        startNumber = tokens.first().number,
        indent = tokens.first().indent,
    )
    val stack = mutableListOf(root)

    for (tok in tokens) {
        // Pop levels deeper than the current item's indent.
        while (stack.size > 1 && stack.last().indent > tok.indent) {
            stack.removeAt(stack.lastIndex)
        }
        val top = stack.last()
        if (top.indent < tok.indent) {
            // Deeper than the current top — push a new sublevel under the top's last item.
            // Clamp indent to top.indent + 1 to avoid phantom nesting on big jumps.
            val newIndent = minOf(tok.indent, top.indent + 1)
            val newLevel = MutableListLevel(
                ordered = tok.ordered,
                startNumber = tok.number,
                indent = newIndent,
            )
            if (top.items.isEmpty()) {
                // No parent item exists yet — create an empty placeholder so the sublist has a
                // parent to attach to. (Shouldn't normally happen for well-formed input.)
                top.items += MutableListItem()
            }
            top.items.last().sublists += newLevel
            stack += newLevel
        }
        stack.last().items += MutableListItem(inline = tok.inline, checked = tok.checked)
    }

    return root.freeze()
}

/** Mutable builder used by [buildListBlock] — frozen into an [MdBlock.ListBlock] at the end. */
private class MutableListLevel(
    val ordered: Boolean,
    val startNumber: Int,
    val indent: Int,
    val items: MutableList<MutableListItem> = mutableListOf(),
)

private class MutableListItem(
    var inline: List<MdToken> = emptyList(),
    val sublists: MutableList<MutableListLevel> = mutableListOf(),
    var checked: Boolean? = null,
)

private fun MutableListLevel.freeze(): MdBlock.ListBlock = MdBlock.ListBlock(
    ordered = ordered,
    startNumber = startNumber,
    items = items.map { it.freeze() },
)

private fun MutableListItem.freeze(): ListItemNode = ListItemNode(
    inline = inline,
    sublists = sublists.map { it.freeze() },
    checked = checked,
)

/**
 * Splits a flat token stream into paragraph groups following CommonMark soft-break rules:
 *  - A run of **two or more** consecutive [MdToken.Newline]s ends a paragraph (blank-line
 *    separator). Any number of newlines ≥ 2 collapses to a single paragraph break.
 *  - A **single** [MdToken.Newline] is a soft break — rendered as a space so wrapped text joins
 *    across the line instead of breaking.
 *  - Block-level tokens ([MdToken.Heading], [MdToken.HorizontalRule], [MdToken.CodeBlock], [MdToken.DisplayMath],
 *    [MdToken.Image], [MdToken.ListItem], [MdToken.BlockQuote]) always start their own group and act as implicit
 *    paragraph breaks.
 *
 * [MdToken.ListItem] is special-cased: consecutive items with no intervening blank line go into
 * the **same** group (so the renderer can build one list tree from them). A single newline
 * between two list items is dropped (no space inserted) — list items are visual blocks, not
 * soft-wrapped text. A non-ListItem token following a list group flushes it (so a paragraph
 * immediately after a list isn't glued onto the last item).
 *
 * [MdToken.BlockQuote] is grouped the same way: consecutive quote lines form one quote block, a
 * newline between quote lines is dropped, and non-quote content flushes the quote group.
 *
 * A single newline at a block boundary (when the current group is empty) is dropped so it doesn't
 * introduce a leading space in the next paragraph.
 *
 * Pure and side-effect-free so it's unit-testable without the Compose runtime.
 */
internal fun groupIntoParagraphs(tokens: List<MdToken>): List<List<MdToken>> {
    val groups = mutableListOf<MutableList<MdToken>>()
    var current = mutableListOf<MdToken>()
    fun flush() {
        if (current.isNotEmpty()) {
            groups += current
            current = mutableListOf()
        }
    }
    var i = 0
    while (i < tokens.size) {
        val tok = tokens[i]
        when (tok) {
            is MdToken.ListItem -> {
                // If the current group isn't already a list, flush it — list items never mix
                // with regular inline text in one group.
                val currentIsList = current.firstOrNull() is MdToken.ListItem
                if (!currentIsList) flush()
                current += tok
            }
            is MdToken.BlockQuote -> {
                val currentIsQuote = current.firstOrNull() is MdToken.BlockQuote
                if (!currentIsQuote) flush()
                current += tok
            }
            is MdToken.Heading, is MdToken.HorizontalRule, is MdToken.CodeBlock, is MdToken.DisplayMath, is MdToken.Image,
            is MdToken.Table, is MdToken.FootnoteDefinition, is MdToken.ThinkBlock -> {
                flush()
                groups += mutableListOf(tok)
            }
            MdToken.Newline -> {
                // Count the run of consecutive newlines.
                var run = 1
                while (i + run < tokens.size && tokens[i + run] == MdToken.Newline) run++
                val next = tokens.getOrNull(i + run)
                val nextIsBlock =
                    next is MdToken.Heading || next is MdToken.CodeBlock ||
                    next is MdToken.HorizontalRule || next is MdToken.DisplayMath || next is MdToken.Image || next is MdToken.Table ||
                        next is MdToken.FootnoteDefinition || next is MdToken.ThinkBlock
                val currentIsList = current.firstOrNull() is MdToken.ListItem
                val currentIsQuote = current.firstOrNull() is MdToken.BlockQuote
                val nextIsListItem = next is MdToken.ListItem
                val nextIsQuote = next is MdToken.BlockQuote
                when {
                    // Blank line (2+ newlines) → paragraph break.
                    run >= 2 -> flush()
                    // Single newline immediately before a block-level token, or before a list
                    // item when we're NOT already inside a list group → paragraph break. Don't
                    // seed a trailing space; the block / list token flushes its own group.
                    nextIsBlock || (nextIsListItem && !currentIsList) || (nextIsQuote && !currentIsQuote) -> flush()
                    // Inside a list and the next non-newline token is another list item → drop
                    // the newline entirely. List items are visual blocks, not soft-wrapped text.
                    currentIsList && nextIsListItem -> { /* drop newline */ }
                    currentIsQuote && nextIsQuote -> { /* drop newline */ }
                    // List group ended (next is inline text, not a list item) → flush so the
                    // trailing paragraph isn't glued onto the last item as a soft-break space.
                    currentIsList && next != null -> flush()
                    currentIsQuote && next != null -> flush()
                    // Single newline between two inline runs → CommonMark soft break (space).
                    current.isNotEmpty() && next != null -> current += MdToken.Plain(" ")
                    // else: single newline at block boundary or end of input → drop.
                }
                i += run
                continue
            }
            else -> {
                // Non-list inline token (Plain, Bold, …) — if we were in a list group, flush it
                // so the new paragraph starts fresh.
                if (current.firstOrNull() is MdToken.ListItem || current.firstOrNull() is MdToken.BlockQuote) flush()
                current += tok
            }
        }
        i++
    }
    flush()
    return groups
}

/**
 * Merges [MdToken.ThinkBlock] tokens that are separated only by whitespace (Newline tokens or
 * blank [MdToken.Plain] runs) into a single think block. Some models emit `</think>` followed by
 * a blank line and another `<think>` mid-answer for what is visually one continuous reasoning
 * run; without merging, the renderer would paint several back-to-back "thinking" cards. The
 * merged block stays open until every merged part is closed, so streaming behavior (expanded
 * while open, auto-collapse on close) is preserved. Separators followed by non-think content are
 * left untouched.
 *
 * Pure and side-effect-free so it's unit-testable without the Compose runtime.
 */
internal fun mergeWhitespaceSeparatedThinkBlocks(tokens: List<MdToken>): List<MdToken> {
    if (tokens.none { it is MdToken.ThinkBlock }) return tokens
    val out = mutableListOf<MdToken>()
    var i = 0
    while (i < tokens.size) {
        val tok = tokens[i]
        if (tok !is MdToken.ThinkBlock) {
            out += tok
            i++
            continue
        }
        var content = tok.content
        var closed = tok.closed
        var j = i + 1
        while (j < tokens.size) {
            // Scan the candidate separator run: Newline tokens and whitespace-only Plain runs.
            var k = j
            while (k < tokens.size) {
                val t = tokens[k]
                if (t is MdToken.Newline) { k++; continue }
                if (t is MdToken.Plain && t.text.isBlank()) { k++; continue }
                break
            }
            if (k > j && k < tokens.size && tokens[k] is MdToken.ThinkBlock) {
                val next = tokens[k] as MdToken.ThinkBlock
                content += "\n" + next.content
                closed = closed && next.closed
                j = k + 1
            } else {
                break
            }
        }
        out += MdToken.ThinkBlock(content, closed)
        i = j
    }
    return out
}

/**
 * Splits a run of inline tokens (which may contain [MdToken.InlineMath] entries) into an ordered
 * list of [InlineSegment]s. Text between math tokens is kept as a single [InlineSegment.Text] —
 * the [Text] composable handles line wrapping itself, so we don't need word-level pre-splitting.
 */
private fun buildInlineSegments(tokens: List<MdToken>, styles: MarkdownStyles): List<InlineSegment> {
    val segments = mutableListOf<InlineSegment>()
    val textBuffer = mutableListOf<MdToken>()

    fun flushText() {
        if (textBuffer.isEmpty()) return
        if (textBuffer.any { it !is MdToken.Plain || it.text.isNotEmpty() }) {
            segments += InlineSegment.Text(textBuffer.toList())
        }
        textBuffer.clear()
    }

    for (tok in tokens) {
        if (tok is MdToken.InlineMath) {
            flushText()
            segments += InlineSegment.Math(tok.content)
        } else {
            textBuffer += tok
        }
    }
    flushText()
    return segments
}

@Composable
private fun RenderMarkdownStream(
    text: String,
    state: StreamingTypewriterState,
    styles: MarkdownStyles,
    modifier: Modifier,
) {
    // Use the state's incrementally-cached tokens rather than re-parsing `text` from scratch.
    // Keying on the tokens list *reference* (not `text`) means non-streaming recompositions
    // (scroll, theme switch) hit the cache and skip planBlocks entirely —
    // revealedTokens() returns the same List instance when the revealed length is unchanged.
    // `text` still drives recomposition from the caller (state.revealed read) so new chars
    // still invalidate; it's just no longer substring-compared each frame.
    val tokens = state.revealedTokens()
    val blocks = remember(tokens, styles) { planBlocks(tokens, styles) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Inline -> {
                    RenderInlineText(block.tokens, styles)
                }
                is MdBlock.InlineWithImages -> RenderInlineWithImages(block.tokens, styles)
                is MdBlock.InlineRunWithMath -> RenderInlineRunWithMath(block.segments, styles)
                is MdBlock.BlockQuote -> RenderBlockQuote(block.lines, styles)
                is MdBlock.Heading -> {
                    val base = LocalTextStyle.current
                    val baseSize = base.fontSize.let { fs ->
                        if (fs.value > 0f) fs else 16.sp
                    }
                    val headingStyle = base.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        fontSize = baseSize * headingScale(block.level),
                    )
                    // Render the heading's inline tokens so bold / italic / inline code / links /
                    // inline math all work inside `# …`. ProvideTextStyle propagates the bold +
                    // scaled font to descendants (inline math measurer, inline code renderer) so
                    // equations and code spans inside a heading sit at the heading's size, not the
                    // body size.
                    ProvideTextStyle(headingStyle) {
                        if (block.tokens.any { it is MdToken.InlineMath }) {
                            RenderInlineRunWithMath(buildInlineSegments(block.tokens, styles), styles)
                        } else {
                            RenderInlineText(block.tokens, styles)
                        }
                    }
                }
                MdBlock.HorizontalRule -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(LocalContentColor.current.copy(alpha = 0.24f)),
                    )
                }
                is MdBlock.Image -> styles.imageRenderer.Render(block.url, block.altText)
                is MdBlock.Code -> RenderCodeBlock(block.token, styles)
                is MdBlock.DisplayMath -> RenderDisplayMath(block.content, styles)
                is MdBlock.Table -> RenderTable(block.token, styles)
                is MdBlock.Footnote -> RenderFootnote(block.token, styles)
                is MdBlock.ThinkBlock -> RenderThinkBlock(block.token, styles)
                is MdBlock.ListBlock -> RenderList(block, styles, depth = 0)
            }
        }
    }
}

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

/**
 * Display math — block-level, centered, with a background tint. `displayMode = true` bumps the
 * font size by [MarkdownStyles.displayScale] (the "块级 LaTeX 的缩放" requirement) and renders in
 * display style so big-operator limits stack above/below the glyph.
 */
@Composable
private fun RenderDisplayMath(content: String, styles: MarkdownStyles) {
    val baseStyle = LocalTextStyle.current
    val resolvedFontSize = baseStyle.fontSize.let { fs ->
        if (fs.value > 0f) fs else 16.sp
    }
    val displayFontSize = resolvedFontSize * styles.displayScale
    val textColor = styles.math.color.takeIf { it != Color.Unspecified } ?: LocalContentColor.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(styles.displayMathBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        RenderPlatformMath(
            latex = content,
            displayMode = true,
            textColor = textColor,
            fontSize = displayFontSize,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Renders a GFM table following Material 3 data-table conventions:
 *  - Header row tinted with [MarkdownStyles.tableHeaderBackground] (default `surfaceContainerHigh`)
 *    and medium-weight text.
 *  - 1dp [MarkdownStyles.tableBorder] (default `outlineVariant`) cell dividers.
 *  - 16dp horizontal / 12dp vertical cell padding (M3 spec).
 *  - 12dp corner radius on the whole table (cells stay square; the outer clip rounds the grid).
 *
 * Each column gets equal width via [RowScope.weight]. Cell text is inline-parsed (so bold /
 * italic / inline code / links work inside cells) and aligned per the separator row's colons.
 * Rows with fewer cells than the header are padded with empty cells; extra cells are truncated.
 */
@Composable
private fun RenderTable(token: MdToken.Table, styles: MarkdownStyles) {
    val baseStyle = LocalTextStyle.current
    val borderColor = styles.tableBorder.takeIf { it != Color.Unspecified }
        ?: LocalContentColor.current.copy(alpha = 0.3f)
    val headerBackground = styles.tableHeaderBackground.takeIf { it != Color.Unspecified }
        ?: Color.Transparent
    val tableBackground = styles.tableBackground.takeIf { it != Color.Unspecified }
        ?: Color.Transparent
    val columnCount = token.headers.size
    val tableShape = RoundedCornerShape(12.dp)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(tableBackground, tableShape)
            .clip(tableShape)
            .border(1.dp, borderColor, tableShape),
    ) {
        val cellWidth = if (columnCount == 0) 0.dp else maxWidth / columnCount
        Column {
        // Header row. height(IntrinsicSize.Min) + fillMaxHeight() on each cell ensures all cells
        // in a row stretch to the tallest cell's height (so borders align when one cell wraps).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(headerBackground)
                .drawBehind { drawLine(borderColor, androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), 1.dp.toPx()) },
            verticalAlignment = Alignment.Top,
        ) {
            token.headers.forEachIndexed { index, header ->
                val align = token.aligns.getOrNull(index) ?: TableAlign.DEFAULT
                TableCell(
                    text = header,
                    align = align,
                    borderColor = borderColor,
                    isLastColumn = index == columnCount - 1,
                    style = baseStyle.copy(fontWeight = FontWeight.Medium),
                    styles = styles,
                    maxInlineCodeWidth = cellWidth - 32.dp,
                )
            }
        }
        // Data rows.
        token.rows.forEach { row ->
            Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .drawBehind { drawLine(borderColor, androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), 1.dp.toPx()) },
                verticalAlignment = Alignment.Top,
            ) {
                for (i in 0 until columnCount) {
                    val cellText = row.getOrNull(i) ?: ""
                    val align = token.aligns.getOrNull(i) ?: TableAlign.DEFAULT
                    TableCell(
                        text = cellText,
                        align = align,
                        borderColor = borderColor,
                        isLastColumn = i == columnCount - 1,
                        style = baseStyle,
                        styles = styles,
                        maxInlineCodeWidth = cellWidth - 32.dp,
                    )
                }
            }
        }
        }
    }
}

/**
 * One cell in a table row. Uses [RowScope.weight] so columns share the row width equally.
 * [fillMaxHeight] (paired with [IntrinsicSize.Min] on the parent Row) stretches each cell to the
 * tallest cell's height so borders align even when one cell's text wraps to multiple lines.
 * Cell padding follows the M3 data-table spec: 16dp horizontal / 12dp vertical. The cell text is
 * inline-parsed via [parseStreamingMarkdown] so inline formatting (bold, code, links, …) works
 * inside cells. [TextAlign] is derived from the column's [TableAlign].
 */
@Composable
private fun RowScope.TableCell(
    text: String,
    align: TableAlign,
    borderColor: Color,
    isLastColumn: Boolean,
    style: TextStyle,
    styles: MarkdownStyles,
    maxInlineCodeWidth: androidx.compose.ui.unit.Dp,
) {
    val textAlign = when (align) {
        TableAlign.LEFT, TableAlign.DEFAULT -> TextAlign.Start
        TableAlign.CENTER -> TextAlign.Center
        TableAlign.RIGHT -> TextAlign.End
    }
    val inlineTokens = remember(text) { parseStreamingMarkdown(text) }
    RenderInlineText(
        tokens = inlineTokens,
        styles = styles,
        maxInlineCodeWidth = maxInlineCodeWidth,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .drawBehind {
                if (!isLastColumn) {
                    drawLine(
                        color = borderColor,
                        start = androidx.compose.ui.geometry.Offset(size.width - 0.5f, 0f),
                        end = androidx.compose.ui.geometry.Offset(size.width - 0.5f, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        style = style.copy(textAlign = textAlign),
    )
}

/**
 * Renders a list (ordered or unordered). Each item is laid out as a [Row]: a fixed-width marker
 * column on the left (e.g. `•`, `1.`, `10.`) and the item's inline content on the right.
 *
 * Task-list items (`- [ ]` / `- [x]`) override the marker with a [TaskCheckbox] — the bullet or
 * ordered number is replaced by the Unicode ballot-box glyph, matching GFM rendering. Non-task
 * items in the same list keep their usual marker, so a list can mix plain and task items.
 *
 * Nested lists (sublists on an item) are rendered indented under that item. Indentation per
 * level is `12.dp` — enough to visually distinguish nesting without consuming too much
 * horizontal space on a phone screen.
 *
 * For ordered lists, the marker number is `startNumber + index` so a list that starts with `3.`
 * continues `3.`, `4.`, `5.`, … (matching the leading number, not the position).
 *
 * Marker column width: `20.dp` for unordered (single `•`), and `28.dp` for ordered to fit up to
 * two-digit numbers (`99.`). Larger numbers (three+ digits) clip; that's an acceptable edge case.
 *
 * The item's inline content may itself contain math (`$…$`) — handled via [RenderInlineRunWithMath]
 * when present, falling back to a plain [Text] otherwise. This mirrors how [planBlocks] renders
 * top-level paragraphs, so list items get the same inline capabilities as body text.
 *
 * @param depth current nesting depth — 0 for a top-level list, increments by 1 per sublist. Used
 *   only to drive the left-padding of the whole list block.
 */
@Composable
private fun RenderList(block: MdBlock.ListBlock, styles: MarkdownStyles, depth: Int) {
    val baseStyle = LocalTextStyle.current
    val markerWidth = if (block.ordered) 28.dp else 20.dp
    val indentPerLevel = 12.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentPerLevel * depth),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        block.items.forEachIndexed { index, item ->
            val isTask = item.checked != null
            val markerText = if (block.ordered) {
                "${block.startNumber + index}."
            } else {
                "•"
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                if (isTask) {
                    TaskCheckbox(
                        checked = item.checked == true,
                        modifier = Modifier.width(markerWidth).alignByBaseline(),
                    )
                } else {
                    Text(
                        text = markerText,
                        modifier = Modifier.width(markerWidth).alignByBaseline(),
                        style = baseStyle,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .alignByBaseline()
                        .padding(start = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    RenderItemInline(item.inline, styles)
                    for (sublist in item.sublists) {
                        RenderList(sublist, styles, depth = depth + 1)
                    }
                }
            }
        }
    }
}

/**
 * Read-only checkbox drawn for GFM task-list items (`- [ ]` / `- [x]`). Renders the Unicode
 * ballot-box glyphs — `☐` (U+2610) when unchecked, `☑` (U+2611) when checked — so the marker
 * matches the surrounding text style (font size, color, baseline) without any custom drawing.
 *
 * Not interactive — this is a typewriter display, not an editable checkbox.
 */
@Composable
private fun TaskCheckbox(checked: Boolean, modifier: Modifier = Modifier) {
    val glyph = if (checked) "\u2611" else "\u2610"
    Text(text = glyph, modifier = modifier, style = LocalTextStyle.current)
}

/**
 * Renders an item's inline content — same logic [planBlocks] uses for a top-level paragraph: if
 * any inline math token is present, route through [RenderInlineRunWithMath] so equations sit
 * inline with the surrounding words; otherwise emit a plain [Text] with the assembled
 * [AnnotatedString]. Empty inline (e.g. an empty `-` line) renders nothing.
 */
@Composable
private fun RenderItemInline(tokens: List<MdToken>, styles: MarkdownStyles) {
    if (tokens.any { it is MdToken.Image }) {
        RenderInlineWithImages(tokens, styles)
        return
    }
    val hasMath = tokens.any { it is MdToken.InlineMath }
    if (hasMath) {
        RenderInlineRunWithMath(buildInlineSegments(tokens, styles), styles)
    } else {
        RenderInlineText(tokens, styles)
    }
}

@Composable
private fun RenderInlineWithImages(tokens: List<MdToken>, styles: MarkdownStyles) {
    val textTokens = mutableListOf<MdToken>()
    @Composable
    fun flushText() {
        if (textTokens.isEmpty()) return
        RenderInlineText(textTokens, styles)
        textTokens.clear()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (token in tokens) {
            if (token is MdToken.Image) {
                flushText()
                styles.imageRenderer.Render(token.url, token.altText)
            } else {
                textTokens += token
            }
        }
        flushText()
    }
}

/** Renders inline markdown while giving each code span its own rounded, padded surface. */
private fun wrapInlineCodeText(
    text: String,
    style: TextStyle,
    maxWidth: androidx.compose.ui.unit.Dp,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    density: androidx.compose.ui.unit.Density,
): String {
    val maxWidthPx = with(density) { maxWidth.toPx() }
    if (text.isEmpty() || maxWidthPx <= 0f) return text

    val result = StringBuilder(text.length)
    val line = StringBuilder()
    for (char in text) {
        if (char == '\n') {
            result.append(line).append('\n')
            line.clear()
            continue
        }
        val candidate = line.toString() + char
        val width = textMeasurer.measure(AnnotatedString(candidate), style).size.width
        if (line.isNotEmpty() && width > maxWidthPx) {
            result.append(line).append('\n')
            line.clear()
        }
        line.append(char)
    }
    result.append(line)
    return result.toString()
}

@Composable
private fun RenderInlineText(
    tokens: List<MdToken>,
    styles: MarkdownStyles,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    maxInlineCodeWidth: androidx.compose.ui.unit.Dp? = null,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val codePaddingHorizontal = 4.dp
    val codePaddingVertical = 1.dp
    val codeBackground = styles.code.background.takeIf { it != Color.Unspecified }
        ?: LocalContentColor.current.copy(alpha = 0.12f)
    // 稳定前缀块（tokens 结构相等，流式下只有尾部块变化）复用已构建的
    // AnnotatedString 与 inline content，跳过整段重建与 inline code 测量。
    val built = remember(tokens, styles, style, maxInlineCodeWidth) {
        val inlineContent = mutableMapOf<String, InlineTextContent>()
        val annotated = buildAnnotatedString {
            var codeIndex = 0
            for (token in tokens) {
                if (token is MdToken.InlineCode) {
                    val codeTextStyle = style.copy(
                        color = styles.code.color.takeIf { it != Color.Unspecified } ?: style.color,
                        fontFamily = styles.code.fontFamily ?: style.fontFamily,
                        fontWeight = styles.code.fontWeight ?: style.fontWeight,
                        fontStyle = styles.code.fontStyle ?: style.fontStyle,
                        fontSize = styles.code.fontSize.takeUnless { it == TextUnit.Unspecified } ?: style.fontSize,
                    )
                    val fontSize = codeTextStyle.fontSize.takeUnless { it == TextUnit.Unspecified } ?: 16.sp
                    val lineHeight = style.lineHeight.takeUnless { it == TextUnit.Unspecified } ?: fontSize * 1.2f
                    val codeChunks = maxInlineCodeWidth?.let {
                        wrapInlineCodeText(
                            token.text,
                            codeTextStyle,
                            (it - codePaddingHorizontal * 2).coerceAtLeast(0.dp),
                            textMeasurer,
                            density,
                        ).split('\n')
                    } ?: listOf(token.text)
                    codeChunks.forEachIndexed { chunkIndex, displayText ->
                        if (chunkIndex > 0) append('\n')
                        val id = "inline_code_${codeIndex++}"
                        appendInlineContent(id, displayText)
                        val measuredWidth = textMeasurer.measure(
                            text = AnnotatedString(displayText),
                            style = codeTextStyle,
                        ).size.width
                        val width = with(density) {
                            (measuredWidth.toDp() + codePaddingHorizontal * 2).toSp()
                        }
                        val height = with(density) {
                            (lineHeight.toDp() + codePaddingVertical * 2).toSp()
                        }
                        inlineContent[id] = InlineTextContent(
                            placeholder = Placeholder(
                                width = width,
                                height = height,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(codeBackground)
                                    .padding(horizontal = codePaddingHorizontal, vertical = codePaddingVertical),
                            ) {
                                Text(text = displayText, style = codeTextStyle)
                            }
                        }
                    }
                } else {
                    append(buildAnnotatedFromInline(listOf(token), styles))
                }
            }
        }
        annotated to inlineContent
    }
    Text(text = built.first, inlineContent = built.second, modifier = modifier, style = style)
}

@Composable
private fun RenderBlockQuote(lines: List<MdToken.BlockQuote>, styles: MarkdownStyles) {
    val stripeColor = styles.blockQuoteStripe.takeIf { it != Color.Unspecified }
        ?: LocalContentColor.current.copy(alpha = 0.35f)
    val backgroundColor = styles.blockQuoteBackground.takeIf { it != Color.Unspecified }
        ?: Color.Transparent
    RenderQuoteNode(buildQuoteTree(lines), styles, stripeColor, backgroundColor)
}

/**
 * A quote element — either a same-level line in the current quote, or a nested quote (a run of
 * deeper-level lines, recursively tree-built). The order of [QuoteNode.elements] preserves the
 * source order so `> a\n>> b\n> c` renders as `[Line(a), Nested(b), Line(c)]`.
 */
internal sealed class QuoteElement {
    data class Line(val line: MdToken.BlockQuote) : QuoteElement()
    data class Nested(val subtree: QuoteNode) : QuoteElement()
}

internal data class QuoteNode(val elements: List<QuoteElement>)

/**
 * Builds a [QuoteNode] tree from a flat run of [MdToken.BlockQuote] tokens by walking level
 * transitions: lines at the run's base level become [QuoteElement.Line]s, and any run of
 * deeper-level lines is recursively tree-built (with levels normalized down by `baseLevel` so
 * `baseLevel + 1` becomes level 1 in the subtree) and attached as a single [QuoteElement.Nested].
 *
 * Pure (no Compose dependency) so it stays unit-testable.
 */
internal fun buildQuoteTree(lines: List<MdToken.BlockQuote>): QuoteNode {
    if (lines.isEmpty()) return QuoteNode(emptyList())
    val baseLevel = lines.first().level
    val elements = mutableListOf<QuoteElement>()
    var i = 0
    while (i < lines.size) {
        if (lines[i].level == baseLevel) {
            elements += QuoteElement.Line(lines[i])
            i++
        } else {
            // Collect consecutive deeper-level lines as one nested subtree.
            val nested = mutableListOf<MdToken.BlockQuote>()
            while (i < lines.size && lines[i].level > baseLevel) {
                // Normalize the level down by baseLevel so `baseLevel + 1` becomes 1 in the subtree.
                nested += lines[i].copy(level = lines[i].level - baseLevel)
                i++
            }
            elements += QuoteElement.Nested(buildQuoteTree(nested))
        }
    }
    return QuoteNode(elements)
}

@Composable
private fun RenderQuoteNode(
    subtree: QuoteNode,
    styles: MarkdownStyles,
    stripeColor: Color,
    backgroundColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(stripeColor, RoundedCornerShape(999.dp))
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(backgroundColor)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (element in subtree.elements) {
                when (element) {
                    is QuoteElement.Line -> {
                        if (element.line.inline.isEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                        } else {
                            RenderItemInline(element.line.inline, styles)
                        }
                    }
                    is QuoteElement.Nested -> {
                        RenderQuoteNode(element.subtree, styles, stripeColor, backgroundColor)
                    }
                }
            }
        }
    }
}

/**
 * Font-size multiplier for a heading level relative to the ambient body font size. Mirrors the
 * common Markdown/HTML convention: `#` is largest, `######` is below body size.
 */
private fun headingScale(level: Int): Float = when (level) {
    1 -> 1.8f
    2 -> 1.5f
    3 -> 1.3f
    4 -> 1.1f
    5 -> 1.0f
    6 -> 0.9f
    else -> 1.0f
}

internal fun buildAnnotatedFromInline(tokens: List<MdToken>, styles: MarkdownStyles): AnnotatedString {
    return buildAnnotatedString {
        appendInlineTokens(this, tokens, styles)
    }
}

/**
 * Appends inline tokens while preserving nested Markdown styles. The parser keeps the payload of
 * emphasis tokens as text so the streaming token shape stays prefix-stable; parse that payload at
 * render time to support forms such as `**_bold italic_**`.
 */
private fun appendInlineTokens(
    builder: androidx.compose.ui.text.AnnotatedString.Builder,
    tokens: List<MdToken>,
    styles: MarkdownStyles,
    preserveBoldWeight: Boolean = false,
) {
    with(builder) {
        for (tok in tokens) {
            when (tok) {
                is MdToken.Plain -> append(tok.text)
                is MdToken.Bold -> withStyle(styles.bold) {
                    appendInlineTokens(this, parseStreamingMarkdown(tok.text), styles, preserveBoldWeight = true)
                }
                is MdToken.Italic -> {
                    val italicStyle = if (preserveBoldWeight) {
                        // MarkdownStyles.italic may explicitly use FontWeight.Normal. Do not let
                        // that replace the inherited bold weight in **_bold italic_**.
                        styles.italic.copy(fontWeight = null)
                    } else {
                        styles.italic
                    }
                    withStyle(italicStyle) {
                        appendInlineTokens(this, parseStreamingMarkdown(tok.text), styles, preserveBoldWeight)
                    }
                }
                is MdToken.BoldItalic -> withStyle(styles.bold) {
                    withStyle(styles.italic.copy(fontWeight = null)) {
                        appendInlineTokens(this, parseStreamingMarkdown(tok.text), styles, preserveBoldWeight = true)
                    }
                }
                is MdToken.InlineCode -> withStyle(styles.code) { append(tok.text) }
                is MdToken.Strikethrough -> withStyle(styles.strikethrough) {
                    appendInlineTokens(this, parseStreamingMarkdown(tok.text), styles)
                }
                is MdToken.Link -> withLink(
                    LinkAnnotation.Url(
                        url = tok.url,
                        styles = TextLinkStyles(style = styles.link),
                    ),
                ) { append(tok.label) }
                is MdToken.FootnoteReference -> withStyle(
                    styles.footnoteReference.copy(
                        baselineShift = androidx.compose.ui.text.style.BaselineShift.Superscript,
                    ),
                ) { append("[${tok.label}]") }
                is MdToken.Image -> append(tok.altText)
                is MdToken.Heading -> withStyle(styles.heading) {
                    appendInlineTokens(this, tok.inline, styles)
                }
                is MdToken.CodeBlock, MdToken.Newline, MdToken.HorizontalRule -> { /* block-level */ }
                is MdToken.ListItem, is MdToken.BlockQuote, is MdToken.Table, is MdToken.FootnoteDefinition, is MdToken.ThinkBlock -> {
                    /* block-level */
                }
                is MdToken.InlineMath -> append(tok.content)
                is MdToken.DisplayMath -> append(tok.content)
            }
        }
    }
}

@Composable
private fun RenderFootnote(token: MdToken.FootnoteDefinition, styles: MarkdownStyles) {
    val definitionColor = styles.footnoteDefinition.takeIf { it != Color.Unspecified }
        ?: LocalContentColor.current.copy(alpha = 0.72f)
    Text(
        text = buildAnnotatedString {
            withStyle(styles.footnoteReference.copy(
                baselineShift = androidx.compose.ui.text.style.BaselineShift.Superscript,
            )) { append("[${token.label}] ") }
            withStyle(androidx.compose.ui.text.SpanStyle(color = definitionColor)) {
                append(buildAnnotatedFromInline(token.inline, styles))
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )
}

@Composable
private fun RenderCodeBlock(token: MdToken.CodeBlock, styles: MarkdownStyles) {
    val lang = remember(token.language) { CodeLanguage.parse(token.language) }
    val languageLabel = codeBlockLanguageLabel(token.language)
    val renderedContent = remember(token.content, token.closed) {
        codeBlockContentForDisplay(token.content, token.closed)
    }
    val annotated = remember(renderedContent, lang, styles, token.closed) {
        if (token.closed) {
            // 围栏闭合后：一次性完整语法高亮
            highlightedAnnotated(
                source = renderedContent,
                language = lang,
                keywordColor = styles.codeBlockKeyword,
                stringColor = styles.codeBlockString,
                numberColor = styles.codeBlockNumber,
                commentColor = styles.codeBlockComment,
                textColor = styles.codeBlockText,
            )
        } else {
            // 未闭合（流式进行中）：跳过 Highlights 解析，整块按代码文本色
            // 单色渲染，避免随 token 增长对全长代码重复高亮（O(N²)）
            AnnotatedString(renderedContent, androidx.compose.ui.text.SpanStyle(color = styles.codeBlockText))
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(styles.codeBlockBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (languageLabel != null) {
                Text(
                    text = languageLabel,
                    modifier = Modifier.fillMaxWidth(),
                    color = styles.codeBlockText.copy(alpha = 0.65f),
                    textAlign = TextAlign.End,
                    style = LocalTextStyle.current.copy(
                        fontFamily = PlatformCodeBlockFontFamily,
                        fontSize = 11.sp,
                    ),
                )
            }
            Text(
                text = annotated,
                style = LocalTextStyle.current.copy(fontFamily = PlatformCodeBlockFontFamily),
            )
        }
    }
}

/** Returns the optional fenced-code info string as a display label. */
internal fun codeBlockLanguageLabel(language: String?): String? =
    language?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Closed fenced blocks carry the newline that precedes the closing fence because it's required by
 * markdown syntax (`code\n````). Rendering that trailing newline directly makes `Text` paint an
 * extra empty visual line that is not perceived as code content. Trim exactly one terminal line
 * break for closed blocks, while preserving intentional blank lines (`...\n\n``` ` stays `...\n`).
 */
internal fun codeBlockContentForDisplay(content: String, closed: Boolean): String {
    if (!closed) return content
    return when {
        content.endsWith("\r\n") -> content.dropLast(2)
        content.endsWith("\n") -> content.dropLast(1)
        else -> content
    }
}

@Composable
private fun RenderThinkBlock(token: MdToken.ThinkBlock, styles: MarkdownStyles) {
    val stripeColor = styles.thinkBlockStripe.takeIf { it != Color.Unspecified }
        ?: LocalContentColor.current.copy(alpha = 0.35f)
    val backgroundColor = styles.thinkBlockBackground.takeIf { it != Color.Unspecified }
        ?: Color.Transparent
    val textColor = styles.thinkBlockText.takeIf { it != Color.Unspecified }
        ?: LocalContentColor.current.copy(alpha = 0.72f)

    val isClosed = token.closed
    var isExpanded by remember { mutableStateOf(!isClosed) }

    // Auto-collapse when the think block transitions from open to closed
    // (i.e. when </think> arrives or the stream is force-completed).
    LaunchedEffect(isClosed) {
        if (isClosed) {
            isExpanded = false
        }
    }

    val displayContent = codeBlockContentForDisplay(token.content, token.closed)
    val inlineTokens = remember(displayContent) { parseStreamingMarkdown(displayContent) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(backgroundColor),
    ) {
        val headerAction = if (isClosed) {
            Modifier
                .clickable(role = Role.Button) { isExpanded = !isExpanded }
                .semantics {
                    stateDescription = if (isExpanded) styles.thinkBlockExpanded else styles.thinkBlockCollapsed
                }
        } else Modifier
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(headerAction)
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(16.dp)
                    .background(stripeColor, MaterialTheme.shapes.extraSmall)
            )
            Text(
                text = styles.thinkBlockTitle,
                style = MaterialTheme.typography.labelLarge,
                color = stripeColor,
                modifier = Modifier.weight(1f),
            )
            if (isClosed) {
                Canvas(Modifier.size(18.dp)) {
                    val left = size.width * 0.22f
                    val right = size.width * 0.78f
                    val upper = size.height * 0.38f
                    val lower = size.height * 0.62f
                    val startY = if (isExpanded) lower else upper
                    val endY = if (isExpanded) upper else lower
                    drawLine(stripeColor, Offset(left, startY), Offset(size.width / 2, endY), 2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(stripeColor, Offset(size.width / 2, endY), Offset(right, startY), 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }

        AnimatedVisibility(
            visible = isExpanded || !isClosed,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 29.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ProvideTextStyle(LocalTextStyle.current.copy(color = textColor)) {
                    if (inlineTokens.any { it is MdToken.Image }) {
                        RenderInlineWithImages(inlineTokens, styles)
                    } else if (inlineTokens.any { it is MdToken.InlineMath }) {
                        RenderInlineRunWithMath(buildInlineSegments(inlineTokens, styles), styles)
                    } else if (inlineTokens.any { it is MdToken.CodeBlock }) {
                        for (tok in inlineTokens) {
                            if (tok is MdToken.CodeBlock) {
                                RenderCodeBlock(tok, styles)
                            } else {
                                RenderInlineText(listOf(tok), styles)
                            }
                        }
                    } else {
                        RenderInlineText(inlineTokens, styles)
                    }
                }
            }
        }
    }
}
