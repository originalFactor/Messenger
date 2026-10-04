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

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

data class MessageItem(
    val id: String,
    val role: String,
    val content: String,
    val blocks: List<RenderBlock>? = null,
    val isStreaming: Boolean = false
)

class ConversationAdapter : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

    private val items = mutableListOf<MessageItem>()
    private var streamingViewHolder: ViewHolder? = null

    class ViewHolder(val messageView: MessageView) : RecyclerView.ViewHolder(messageView)

    fun submitList(newItems: List<MessageItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** Direct streaming update: updates only DocumentView without notifying RecyclerView */
    fun applyStreamingDiff(diffBatchJson: String) {
        streamingViewHolder?.messageView?.documentView?.applyDiffBatch(diffBatchJson)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = MessageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.messageView.bind(
            role = item.role,
            content = item.content,
            blocks = item.blocks,
            isStreaming = item.isStreaming
        )
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
}
