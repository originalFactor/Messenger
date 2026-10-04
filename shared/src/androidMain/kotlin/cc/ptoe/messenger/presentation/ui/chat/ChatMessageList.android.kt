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

package cc.ptoe.messenger.presentation.ui.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.presentation.utils.DateTimeUtils
import cc.ptoe.messenger.renderer.ConversationAdapter
import cc.ptoe.messenger.renderer.MessageItem

@Composable
internal actual fun ChatMessageList(
    chatItems: List<ChatListItem>,
    isGenerating: Boolean,
    streamingContent: String?,
    streamingMessageId: String?,
    streamingDiff: String?,
    agent: Agent?,
    userAvatar: String?,
    enableContextMenu: Boolean,
    copiedToastText: String,
    onRetryClick: (String) -> Unit,
    onMessageLongClick: (Message) -> Unit,
    onCopyClick: (String) -> Unit,
    onRegenerateClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    modifier: Modifier
) {
    val adapter = remember { ConversationAdapter() }

    LaunchedEffect(adapter, onRetryClick, onMessageLongClick) {
        adapter.onMessageClickListener = { item ->
            if (item.status.equals("ERROR", ignoreCase = true)) {
                onRetryClick(item.id)
            }
        }
        adapter.onRetryClickListener = { item ->
            onRetryClick(item.id)
        }
        adapter.onMessageLongClickListener = { item, _ ->
            val msg = chatItems.mapNotNull {
                when (it) {
                    is ChatListItem.MessageItem -> it.message
                    is ChatListItem.ToolGroupItem -> it.finalMessage ?: it.rounds.lastOrNull()
                    else -> null
                }
            }.firstOrNull { it.id == item.id }
            if (msg != null) {
                onMessageLongClick(msg)
            }
        }
    }

    // Direct incremental streaming diff update: zero-recomposition, zero-adapter-notify
    LaunchedEffect(streamingDiff) {
        if (!streamingDiff.isNullOrBlank()) {
            adapter.applyStreamingDiff(streamingDiff)
        }
    }

    // Sync message items list to RecyclerView adapter
    LaunchedEffect(chatItems, streamingContent, streamingMessageId) {
        val rendererItems = chatItems.asReversed().map { item ->
            when (item) {
                is ChatListItem.DateSeparator -> {
                    MessageItem(
                        id = "date_${item.id}",
                        role = "date_separator",
                        content = DateTimeUtils.formatDateSeparator(item.timestamp),
                        isDateSeparator = true,
                        timestamp = item.timestamp
                    )
                }
                is ChatListItem.ToolGroupItem -> {
                    val finalMsg = item.finalMessage ?: item.rounds.lastOrNull()
                    val isStreaming = finalMsg != null && finalMsg.id == streamingMessageId
                    val content = if (isStreaming && streamingContent != null) streamingContent
                    else finalMsg?.content ?: ""
                    MessageItem(
                        id = finalMsg?.id ?: ("tool_" + (item.rounds.firstOrNull()?.id ?: "0")),
                        role = "assistant",
                        content = content,
                        isStreaming = isStreaming,
                        status = finalMsg?.status?.name ?: "SENT",
                        timestamp = finalMsg?.timestamp ?: 0L
                    )
                }
                is ChatListItem.MessageItem -> {
                    val m = item.message
                    val isStreaming = m.id == streamingMessageId
                    val content = if (isStreaming && streamingContent != null) streamingContent else m.content
                    MessageItem(
                        id = m.id,
                        role = m.role.name.lowercase(),
                        content = content,
                        isStreaming = isStreaming,
                        status = m.status.name,
                        timestamp = m.timestamp
                    )
                }
            }
        }
        adapter.submitList(rendererItems)
    }

    AndroidView(
        factory = { ctx ->
            RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx).apply {
                    reverseLayout = true
                    stackFromEnd = false
                }
                this.adapter = adapter
                itemAnimator = null // Smooth streaming without layout jitter
                clipToPadding = false
            }
        },
        update = { rv ->
            // Auto-scroll to bottom during live streaming if near bottom
            val layoutManager = rv.layoutManager as? LinearLayoutManager
            if (isGenerating && layoutManager != null) {
                val firstVisible = layoutManager.findFirstVisibleItemPosition()
                if (firstVisible <= 1) {
                    rv.scrollToPosition(0)
                }
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
