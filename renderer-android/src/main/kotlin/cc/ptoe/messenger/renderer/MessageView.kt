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

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Android Native MessageView (TARGET.md §8, §9, §10, §18).
 *
 * Google Messages-style chat row: 32dp avatar on the bubble's outer side,
 * 18dp bubble with a 4dp tail corner on the group's last row, 11sp timestamp
 * below. Assistant bubbles host a [DocumentView]; user bubbles plain text;
 * error rows show the localized failure + retry hint.
 *
 * Static content (agent-turn rounds + finalized text) rebuilds only when its
 * fingerprint changes; while streaming, the live section of the DocumentView
 * is fed exclusively by Rust DiffBatch deltas (zero re-parse per token).
 */
class MessageView(context: Context) : LinearLayout(context) {

    val documentView: DocumentView = DocumentView(context)

    var theme: RendererTheme? = null
        private set

    private val contentRow: LinearLayout = LinearLayout(context)
    private val avatarView: ImageView = ImageView(context)
    private val leadingSpacer: View = View(context)
    private val trailingSpacer: View = View(context)
    private val bubbleContainer: FrameLayout = FrameLayout(context)
    private val userTextView: TextView = TextView(context)
    private val errorTitleView: TextView = TextView(context)
    private val errorMessageView: TextView = TextView(context)
    private val retryView: TextView = TextView(context)
    private val errorColumn: LinearLayout = LinearLayout(context)
    private val timestampView: TextView = TextView(context)
    private val dateSeparatorView: TextView = TextView(context)
    private val typingIndicator: LinearLayout = LinearLayout(context)
    private val typingDots: List<TextView> = (0 until 3).map { TextView(context) }

    private var typingAnimator: ValueAnimator? = null
    private var lastAvatarBitmapSet: Any? = null
    private var lastStaticKey: StaticKey? = null
    private var rowOrderIsUser: Boolean? = null

    var currentItem: MessageItem? = null
        private set

    var onMessageClickListener: ((MessageItem) -> Unit)? = null
    var onMessageLongClickListener: ((MessageItem, View) -> Unit)? = null
    var onRetryClickListener: ((MessageItem) -> Unit)? = null

    private data class StaticKey(
        val rounds: List<ToolRoundData>,
        val staticContent: String?,
        val theme: RendererTheme?
    )

    init {
        orientation = VERTICAL
        val dp = resources.displayMetrics.density

        contentRow.orientation = HORIZONTAL

        avatarView.scaleType = ImageView.ScaleType.CENTER
        leadingSpacer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        trailingSpacer.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        val innerPadH = (14 * dp).toInt()
        val innerPadV = (10 * dp).toInt()
        bubbleContainer.setPadding(innerPadH, innerPadV, innerPadH, innerPadV)
        bubbleContainer.addView(documentView)
        documentView.visibility = GONE

        userTextView.textSize = 14f
        userTextView.lineHeight = (20 * resources.displayMetrics.density).toInt()
        userTextView.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL)
        userTextView.setTextIsSelectable(false)
        // Cap the text at the row's usable width (screen − 64/8dp row insets −
        // 32dp avatar − 8dp spacer − 28dp bubble padding): a long single-line
        // message must WRAP, not push the bubble past the row's far inset.
        userTextView.maxWidth = maxContentTextWidth()
        userTextView.visibility = GONE
        bubbleContainer.addView(userTextView)

        errorColumn.orientation = VERTICAL
        errorTitleView.textSize = 14f
        errorMessageView.textSize = 13f
        errorMessageView.maxWidth = maxContentTextWidth()
        retryView.textSize = 13f
        retryView.setPadding(0, (6 * dp).toInt(), 0, 0)
        errorColumn.addView(errorTitleView)
        errorColumn.addView(errorMessageView)
        errorColumn.addView(retryView)
        errorColumn.visibility = GONE
        bubbleContainer.addView(errorColumn)

        typingIndicator.orientation = HORIZONTAL
        typingIndicator.gravity = Gravity.CENTER_VERTICAL
        typingDots.forEach { dot ->
            dot.text = "●"
            dot.textSize = 9f
            dot.includeFontPadding = false
            typingIndicator.addView(
                dot,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, (4 * dp).toInt(), 0)
                }
            )
        }
        typingIndicator.visibility = GONE
        bubbleContainer.addView(typingIndicator)

        applyRowOrder(isUser = false)

        addView(contentRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (2 * dp).toInt()
            bottomMargin = (2 * dp).toInt()
        })

        timestampView.textSize = 11f
        timestampView.visibility = GONE
        addView(timestampView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (2 * dp).toInt()
        })

        dateSeparatorView.textSize = 12f
        dateSeparatorView.gravity = Gravity.CENTER
        dateSeparatorView.visibility = GONE
        addView(dateSeparatorView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            val vMargin = (8 * dp).toInt()
            setMargins(0, vMargin, 0, vMargin)
        })

        bubbleContainer.setOnClickListener {
            val item = currentItem ?: return@setOnClickListener
            if (item.status.equals("ERROR", ignoreCase = true)) {
                onRetryClickListener?.invoke(item)
            } else {
                onMessageClickListener?.invoke(item)
            }
        }

        bubbleContainer.setOnLongClickListener { v ->
            val item = currentItem ?: return@setOnLongClickListener false
            onMessageLongClickListener?.invoke(item, v)
            true
        }
    }

    /**
     * Row order follows the Google Messages layout: assistant rows are
     * [avatar, gap, bubble, gap], user rows mirror to [bubble, gap, avatar].
     * Applied only when the role side changes to avoid per-bind view churn.
     */
    private fun applyRowOrder(isUser: Boolean) {
        if (rowOrderIsUser == isUser) return
        rowOrderIsUser = isUser
        val dp = resources.displayMetrics.density
        val avatarLp = LayoutParams((32 * dp).toInt(), (32 * dp).toInt())
        val gapLp = LayoutParams((8 * dp).toInt(), 1)
        val bubbleLp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        contentRow.removeAllViews()
        if (isUser) {
            contentRow.addView(bubbleContainer, bubbleLp)
            contentRow.addView(leadingSpacer, gapLp)
            contentRow.addView(avatarView, avatarLp)
            trailingSpacer.visibility = GONE
        } else {
            contentRow.addView(avatarView, avatarLp)
            contentRow.addView(leadingSpacer, gapLp)
            contentRow.addView(bubbleContainer, bubbleLp)
            contentRow.addView(trailingSpacer, gapLp)
            trailingSpacer.visibility = VISIBLE
        }
    }

    fun bind(item: MessageItem, theme: RendererTheme?) {
        this.currentItem = item
        this.theme = theme
        val dp = resources.displayMetrics.density
        val r = 18f * dp

        if (item.isDateSeparator) {
            contentRow.visibility = GONE
            timestampView.visibility = GONE
            dateSeparatorView.visibility = VISIBLE
            dateSeparatorView.text = item.content
            dateSeparatorView.setTextColor(theme?.onSurfaceVariant ?: 0xFF757575.toInt())
            stopTypingAnimation()
            return
        }

        dateSeparatorView.visibility = GONE
        contentRow.visibility = VISIBLE

        val isUser = item.role.equals("user", ignoreCase = true)
        val isError = item.status.equals("ERROR", ignoreCase = true)
        val last = item.isLastInGroup

        // Row insets (Google Messages): AI hugs the start edge, user the end edge
        val dpI = dp.toInt()
        val lp = contentRow.layoutParams as LayoutParams
        if (isUser) {
            lp.setMargins((64 * dpI).toInt(), (2 * dpI).toInt(), (8 * dpI).toInt(), (2 * dpI).toInt())
        } else {
            lp.setMargins((8 * dpI).toInt(), (2 * dpI).toInt(), (64 * dpI).toInt(), (2 * dpI).toInt())
        }
        contentRow.layoutParams = lp
        contentRow.gravity = (if (isUser) Gravity.END else Gravity.START) or Gravity.BOTTOM
        applyRowOrder(isUser)

        // Bubble background + tail corner on the group's last row.
        // cornerRadii order: TL-x,TL-y, TR-x,TR-y, BR-x,BR-y, BL-x,BL-y.
        val tail = if (last) 4f * dp else r
        val radii = if (isUser) {
            floatArrayOf(r, r, r, r, tail, tail, r, r)
        } else {
            floatArrayOf(r, r, r, r, r, r, tail, tail)
        }
        val bubbleColor = when {
            isError -> theme?.errorBubble ?: 0xFFFFDAD6.toInt()
            isUser -> theme?.userBubble ?: 0xFF0084FF.toInt()
            else -> theme?.aiBubble ?: 0xFFF0F2F5.toInt()
        }
        bubbleContainer.background = GradientDrawable().apply {
            setColor(bubbleColor)
            cornerRadii = radii
        }

        avatarView.visibility = if (last) VISIBLE else INVISIBLE
        bindAvatar(isUser, theme)

        timestampView.visibility = if (last && item.timeText.isNotEmpty()) VISIBLE else GONE
        timestampView.text = item.timeText
        val variant = theme?.onSurfaceVariant ?: 0xFF757575.toInt()
        timestampView.setTextColor(withAlpha(variant, 0.6f))
        timestampView.layoutParams = (timestampView.layoutParams as LayoutParams).apply {
            if (isUser) {
                setMargins((64 * dpI).toInt(), (2 * dpI).toInt(), (44 * dpI).toInt(), 0)
            } else {
                setMargins((44 * dpI).toInt(), (2 * dpI).toInt(), (64 * dpI).toInt(), 0)
            }
            gravity = if (isUser) Gravity.END else Gravity.START
        }

        if (isUser) {
            documentView.visibility = GONE
            errorColumn.visibility = GONE
            userTextView.visibility = VISIBLE
            userTextView.setTextColor(if (isError) theme?.onErrorBubble ?: 0xFFFFFFFF.toInt() else theme?.onUserBubble ?: 0xFFFFFFFF.toInt())
            userTextView.text = item.content
            // A recycled TextView keeps its previous measured width when the
            // new text yields the same line count (checkForRelayout skips the
            // requestLayout) — force a full re-measure on the next pass.
            userTextView.forceLayout()
            stopTypingAnimation()
        } else {
            userTextView.visibility = GONE
            if (isError) {
                documentView.visibility = GONE
                typingIndicator.visibility = GONE
                stopTypingAnimation()
                errorColumn.visibility = VISIBLE
                val onErr = theme?.onErrorBubble ?: 0xFF410E0B.toInt()
                errorTitleView.setTextColor(onErr)
                errorTitleView.text = theme?.errorTitle ?: "Send failed"
                errorMessageView.setTextColor(withAlpha(onErr, 0.75f))
                errorMessageView.text = item.errorMessage.orEmpty()
                errorMessageView.visibility = if (item.errorMessage.isNullOrBlank()) GONE else VISIBLE
                retryView.setTextColor(onErr)
                retryView.text = "⟳ ${theme?.retryAction ?: "Retry"}"
                lastStaticKey = null
            } else {
                errorColumn.visibility = GONE
                documentView.visibility = VISIBLE

                // Static section: agent-turn rounds + finalized text. Rebuilt only
                // when the fingerprint changes — live streaming diffs own the tail.
                val key = StaticKey(item.rounds, if (item.isStreaming) null else item.content, theme)
                if (key != lastStaticKey) {
                    lastStaticKey = key
                    documentView.setBlocks(theme, buildStaticBlocks(item))
                }

                val hasContent = documentView.childCount > 0
                if (item.isStreaming && !hasContent) {
                    typingIndicator.visibility = VISIBLE
                    startTypingAnimation(theme)
                } else {
                    typingIndicator.visibility = GONE
                    stopTypingAnimation()
                }
            }
        }
    }

    /** Called by the adapter after a streaming DiffBatch touched the live section. */
    fun onLiveBlockApplied() {
        if (documentView.childCount > 0 && typingIndicator.visibility == VISIBLE) {
            typingIndicator.visibility = GONE
            stopTypingAnimation()
        }
    }

    private fun buildStaticBlocks(item: MessageItem): List<RenderBlock> {
        val blocks = mutableListOf<RenderBlock>()
        item.rounds.forEachIndexed { roundIndex, round ->
            val base = (roundIndex + 1) * DocumentView.STATIC_ID_BASE
            if (round.text.isNotBlank()) {
                parseStatic(round.text).forEach { blocks.add(withStaticId(it, base + it.id)) }
            }
            round.calls.forEachIndexed { callIndex, call ->
                blocks.add(
                    RenderBlock.ToolCall(
                        id = base + CALL_ID_BASE + callIndex,
                        callId = call.callId,
                        name = call.name,
                        arguments = call.arguments,
                        output = call.output,
                        isError = call.isError,
                        isFinalized = !call.running
                    )
                )
            }
        }
        if (!item.isStreaming && item.content.isNotBlank()) {
            parseStatic(item.content).forEach { blocks.add(withStaticId(it, FINAL_ID_BASE + it.id)) }
        }
        return blocks
    }

    private fun parseStatic(markdown: String): List<RenderBlock> =
        DocumentParser.parseBlocksJson(cc.ptoe.messenger.core.parseMarkdownToBlocksJson(markdown))

    private fun withStaticId(block: RenderBlock, newId: Long): RenderBlock = when (block) {
        is RenderBlock.Paragraph -> block.copy(id = newId)
        is RenderBlock.Heading -> block.copy(id = newId)
        is RenderBlock.Code -> block.copy(id = newId)
        is RenderBlock.Math -> block.copy(id = newId)
        is RenderBlock.Quote -> block.copy(id = newId)
        is RenderBlock.Think -> block.copy(id = newId)
        is RenderBlock.ToolCall -> block.copy(id = newId)
        is RenderBlock.Divider -> block.copy(id = newId)
    }

    private fun bindAvatar(isUser: Boolean, theme: RendererTheme?) {
        val bitmap = if (isUser) theme?.userAvatar else theme?.assistantAvatar
        val fallbackRes = if (isUser) R.drawable.ic_renderer_person else R.drawable.ic_renderer_bot
        val marker = bitmap ?: fallbackRes
        if (lastAvatarBitmapSet === marker) return
        lastAvatarBitmapSet = marker
        val variant = theme?.onSurfaceVariant ?: 0xFF757575.toInt()
        avatarView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme?.surfaceContainerHighest ?: 0xFFE0E0E0.toInt())
        }
        if (bitmap != null) {
            avatarView.setImageBitmap(bitmap)
        } else {
            avatarView.setImageResource(fallbackRes)
            avatarView.setColorFilter(variant)
        }
    }

    private fun startTypingAnimation(theme: RendererTheme?) {
        typingDots.forEach { it.setTextColor(theme?.onSurfaceVariant ?: 0xFF757575.toInt()) }
        if (typingAnimator?.isRunning == true) return
        typingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedFraction
                typingDots.forEachIndexed { i, dot ->
                    val phase = (t + i * 0.18f) % 1f
                    dot.alpha = 0.3f + 0.7f * (1f - Math.abs(2f * phase - 1f))
                }
            }
            start()
        }
    }

    private fun stopTypingAnimation() {
        typingAnimator?.cancel()
        typingAnimator = null
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)

    /** Text width cap: screen − 64+8dp row insets − 32+8dp avatar+spacer − 28dp bubble padding.
     * A long single-line message must WRAP, never push the bubble past the row's far inset. */
    private fun maxContentTextWidth(): Int {
        val dp = resources.displayMetrics.density
        return (resources.displayMetrics.widthPixels - ((112 + 28) * dp).toInt())
            .coerceAtLeast((100 * dp).toInt())
    }

    companion object {
        /** Tool-call card ids inside a round's id band. */
        private const val CALL_ID_BASE = 900_000L
        /** Finalized text blocks sit above every round band. */
        private val FINAL_ID_BASE = 11L * DocumentView.STATIC_ID_BASE
    }
}
