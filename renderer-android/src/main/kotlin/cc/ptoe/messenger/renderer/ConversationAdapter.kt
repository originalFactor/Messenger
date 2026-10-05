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

import android.graphics.Bitmap
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/** One tool invocation inside an agent turn round. */
data class ToolCallData(
    val callId: String,
    val name: String,
    val arguments: String,
    val output: String?,
    val isError: Boolean,
    val running: Boolean
)

/** One agent turn round: the round's text plus the tool calls it issued. */
data class ToolRoundData(
    val text: String,
    val calls: List<ToolCallData>
)

data class MessageItem(
    val id: String,
    val role: String,
    val content: String,
    val blocks: List<RenderBlock>? = null,
    /** Structured agent-turn rounds; rendered as text blocks + tool cards ahead of [content]. */
    val rounds: List<ToolRoundData> = emptyList(),
    val isStreaming: Boolean = false,
    val status: String = "SENT",
    val errorMessage: String? = null,
    val timestamp: Long = 0L,
    val timeText: String = "",
    val isLastInGroup: Boolean = true,
    val isDateSeparator: Boolean = false
)

class ConversationAdapter : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

    private val items = mutableListOf<MessageItem>()
    private var streamingViewHolder: ViewHolder? = null

    /**
     * Theme tokens resolved from the host MaterialTheme. Setting a different
     * value rebinds every row (theme changes are rare — dark mode flips).
     */
    var theme: RendererTheme? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    var onMessageClickListener: ((MessageItem) -> Unit)? = null
    var onMessageLongClickListener: ((MessageItem, View) -> Unit)? = null
    var onRetryClickListener: ((MessageItem) -> Unit)? = null

    class ViewHolder(val messageView: MessageView) : RecyclerView.ViewHolder(messageView)

    fun submitList(newItems: List<MessageItem>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition].id == newItems[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = items[oldItemPosition]
                val new = newItems[newItemPosition]
                return old.content == new.content &&
                        old.rounds == new.rounds &&
                        old.isStreaming == new.isStreaming &&
                        old.status == new.status &&
                        old.timeText == new.timeText &&
                        old.isLastInGroup == new.isLastInGroup &&
                        old.blocks == new.blocks
            }
        }

        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items.clear()
        items.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    /** Direct streaming update: updates only the live section of the streaming DocumentView. */
    fun applyStreamingDiff(diffBatchJson: String) {
        val holder = streamingViewHolder
        holder ?: return
        holder.messageView.documentView.applyDiffBatch(holder.messageView.theme, diffBatchJson)
        holder.messageView.onLiveBlockApplied()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = MessageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            this.onMessageClickListener = { item -> this@ConversationAdapter.onMessageClickListener?.invoke(item) }
            this.onMessageLongClickListener = { item, v -> this@ConversationAdapter.onMessageLongClickListener?.invoke(item, v) }
            this.onRetryClickListener = { item -> this@ConversationAdapter.onRetryClickListener?.invoke(item) }
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.messageView.bind(item, theme)
        if (item.isStreaming) {
            streamingViewHolder = holder
        } else if (streamingViewHolder == holder) {
            streamingViewHolder = null
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (streamingViewHolder == holder) {
            streamingViewHolder = null
        }
    }

    companion object {
        /** Small shared cache for decoded avatar bitmaps (keyed by file path). */
        val avatarCache = object : LruCache<String, Bitmap>(8) {
            override fun sizeOf(key: String, value: Bitmap): Int = 1
        }
    }
}
