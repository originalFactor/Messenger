/*
 * Copyright 2026 originalFactor
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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ContentPart
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.presentation.platform.showPlatformToast
import cc.ptoe.messenger.presentation.ui.components.onContextMenu
import cc.ptoe.messenger.presentation.ui.components.rememberContextMenuState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Android chat list rendered entirely in Compose. Markdown/LaTeX now comes
 * from llm-typewriter; the former native RecyclerView renderer is no longer a
 * separate rendering path.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    hasOlderMessages: Boolean,
    isLoadingHistory: Boolean,
    onLoadOlderMessages: () -> Unit,
    modifier: Modifier
) {
    // The REST/SSE path no longer produces native DiffBatches. Keep the
    // parameter for the shared expect contract and let recomposition own it.
    @Suppress("UNUSED_VARIABLE")
    val ignoredStreamingDiff = streamingDiff

    val listState = rememberLazyListState()
    val visibleItems = chatItems.asReversed()

    LaunchedEffect(listState, hasOlderMessages, isLoadingHistory) {
        if (!hasOlderMessages) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .filter { index -> index != null && index >= visibleItems.lastIndex && !isLoadingHistory }
            .distinctUntilChanged()
            .collect { onLoadOlderMessages() }
    }

    LaunchedEffect(streamingMessageId, streamingContent) {
        if (streamingMessageId != null) listState.scrollToItem(0)
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(visibleItems, key = { item -> item.key() }) { item ->
                when (item) {
                    is ChatListItem.DateSeparator -> DateSeparator(item.timestamp)
                    is ChatListItem.ToolGroupItem -> ToolGroupRow(
                        item = item,
                        isGenerating = isGenerating,
                        streamingContent = streamingContent,
                        streamingMessageId = streamingMessageId,
                        agent = agent,
                        enableContextMenu = enableContextMenu,
                        copiedToastText = copiedToastText,
                        onRetryClick = onRetryClick,
                        onMessageLongClick = onMessageLongClick,
                        onCopyClick = onCopyClick,
                        onRegenerateClick = onRegenerateClick,
                        onDeleteClick = onDeleteClick,
                    )
                    is ChatListItem.MessageItem -> MessageRow(
                        item = item,
                        isGenerating = isGenerating,
                        streamingContent = streamingContent,
                        streamingMessageId = streamingMessageId,
                        agent = agent,
                        userAvatar = userAvatar,
                        enableContextMenu = enableContextMenu,
                        copiedToastText = copiedToastText,
                        onRetryClick = onRetryClick,
                        onMessageLongClick = onMessageLongClick,
                        onCopyClick = onCopyClick,
                        onRegenerateClick = onRegenerateClick,
                        onDeleteClick = onDeleteClick,
                    )
                }
            }
        }
        if (isLoadingHistory) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolGroupRow(
    item: ChatListItem.ToolGroupItem,
    isGenerating: Boolean,
    streamingContent: String?,
    streamingMessageId: String?,
    agent: Agent?,
    enableContextMenu: Boolean,
    copiedToastText: String,
    onRetryClick: (String) -> Unit,
    onMessageLongClick: (Message) -> Unit,
    onCopyClick: (String) -> Unit,
    onRegenerateClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
) {
    val anchor = item.finalMessage ?: item.rounds.lastOrNull()
    val contextMenuState = rememberContextMenuState()
    val interaction = remember { MutableInteractionSource() }
    val isLive = item.finalMessage?.id == streamingMessageId ||
        item.rounds.any { it.id == streamingMessageId }
    val blocks = remember(item.rounds, item.toolMessages, item.finalMessage?.content, streamingContent, isLive) {
        buildToolGroupBlocks(item.rounds, item.toolMessages, item.finalMessage, if (isLive) streamingContent else null)
    }
    val anchorMessage = anchor ?: Message(
        id = "tool_group_${item.toolMessages.firstOrNull()?.id ?: "0"}",
        conversationId = "",
        role = MessageRole.ASSISTANT,
        content = "",
        timestamp = item.toolMessages.firstOrNull()?.timestamp ?: 0L,
        status = MessageStatus.SENT,
    )
    Box(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    if (anchorMessage.status == MessageStatus.ERROR) onRetryClick(anchorMessage.id)
                },
                onLongClick = {
                    if (anchor != null && !enableContextMenu) onMessageLongClick(anchor)
                },
            )
            .then(if (enableContextMenu) Modifier.onContextMenu(contextMenuState) else Modifier),
    ) {
        ChatDocumentBubble(
            message = anchorMessage,
            blocks = blocks,
            avatar = agent?.avatar,
            isGenerating = isGenerating && isLive,
            isLastInGroup = item.isLastInGroup,
            onRetryClick = { if (anchorMessage.status == MessageStatus.ERROR) onRetryClick(anchorMessage.id) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (enableContextMenu && anchor != null) {
            MessageContextMenu(
                state = contextMenuState,
                messageRole = anchor.role,
                onCopyClick = {
                    onCopyClick(anchor.content.ifBlank { blocks.plainText() })
                    showPlatformToast(copiedToastText)
                },
                onRegenerateClick = { onRegenerateClick(anchor.id) },
                onDeleteClick = { onDeleteClick(anchor.id) },
                onDismiss = {},
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    item: ChatListItem.MessageItem,
    isGenerating: Boolean,
    streamingContent: String?,
    streamingMessageId: String?,
    agent: Agent?,
    userAvatar: String?,
    enableContextMenu: Boolean,
    copiedToastText: String,
    onRetryClick: (String) -> Unit,
    onMessageLongClick: (Message) -> Unit,
    onCopyClick: (String) -> Unit,
    onRegenerateClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
) {
    val message = item.message
    val contextMenuState = rememberContextMenuState()
    val interaction = remember { MutableInteractionSource() }
    val visibleMessage = if (message.id == streamingMessageId && streamingContent != null) {
        message.copy(content = streamingContent)
    } else {
        message
    }
    Box(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    if (message.status == MessageStatus.ERROR && message.role == MessageRole.ASSISTANT) {
                        onRetryClick(message.id)
                    }
                },
                onLongClick = {
                    if (!enableContextMenu) onMessageLongClick(message)
                },
            )
            .then(if (enableContextMenu) Modifier.onContextMenu(contextMenuState) else Modifier),
    ) {
        when (message.role) {
            MessageRole.USER -> UserMessageBubble(
                message = visibleMessage,
                modifier = Modifier.fillMaxWidth(),
                isLastInGroup = item.isLastInGroup,
                avatar = userAvatar,
            )
            MessageRole.ASSISTANT -> ChatDocumentBubble(
                message = visibleMessage,
                blocks = null,
                avatar = agent?.avatar,
                isGenerating = isGenerating && message.id == streamingMessageId,
                isLastInGroup = item.isLastInGroup,
                onRetryClick = { if (message.status == MessageStatus.ERROR) onRetryClick(message.id) },
                modifier = Modifier.fillMaxWidth(),
            )
            MessageRole.SYSTEM, MessageRole.TOOL -> Unit
        }
        if (enableContextMenu) {
            MessageContextMenu(
                state = contextMenuState,
                messageRole = message.role,
                onCopyClick = {
                    onCopyClick(message.content)
                    showPlatformToast(copiedToastText)
                },
                onRegenerateClick = { onRegenerateClick(message.id) },
                onDeleteClick = { onDeleteClick(message.id) },
                onDismiss = {},
            )
        }
    }
}

private fun ChatListItem.key(): String = when (this) {
    is ChatListItem.DateSeparator -> "date_$id"
    is ChatListItem.MessageItem -> "msg_${message.id}"
    is ChatListItem.ToolGroupItem -> "tool_${rounds.firstOrNull()?.id ?: toolMessages.firstOrNull()?.id}"
}

private fun buildToolGroupBlocks(
    rounds: List<Message>,
    toolMessages: List<Message>,
    finalMessage: Message?,
    streamingContent: String?,
): List<ChatBlock> {
    val blocks = mutableListOf<ChatBlock>()
    var nextId = 1L
    if (rounds.isEmpty()) {
        toolMessages.forEach { tool ->
            tool.parts.filterIsInstance<ContentPart.ToolResult>().firstOrNull()?.let { result ->
                blocks += ChatBlock.ToolCall(
                    id = nextId++,
                    callId = result.callId,
                    name = result.name,
                    arguments = "",
                    output = result.output,
                    isError = result.isError,
                    isFinalized = true,
                )
            }
        }
        return blocks
    }
    rounds.forEachIndexed { index, round ->
        val base = (index + 1) * 100_000L
        if (round.content.isNotBlank()) {
            blocks += ChatBlock.RawMarkdown(base, round.content, isFinalized = true)
        }
        round.parts.filterIsInstance<ContentPart.ToolCall>().forEachIndexed { callIndex, call ->
            val result = toolMessages.findToolResult(call.callId)
            blocks += ChatBlock.ToolCall(
                id = base + 50_000L + callIndex,
                callId = call.callId,
                name = call.name,
                arguments = call.arguments,
                output = result?.output,
                isError = result?.isError ?: false,
                isFinalized = result != null,
            )
        }
    }
    val finalText = streamingContent ?: finalMessage?.content
    if (!finalText.isNullOrBlank()) {
        blocks += ChatBlock.RawMarkdown(
            id = 10_000_000L,
            markdown = finalText,
            isFinalized = streamingContent == null,
        )
    }
    return blocks
}

private fun List<Message>.findToolResult(callId: String): ContentPart.ToolResult? = asSequence()
    .mapNotNull { it.parts.filterIsInstance<ContentPart.ToolResult>().firstOrNull() }
    .firstOrNull { it.callId == callId && (it.output.isNotEmpty() || it.isError) }

private fun List<ChatBlock>.plainText(): String = joinToString("\n\n") { block ->
    when (block) {
        is ChatBlock.RawMarkdown -> block.markdown
        is ChatBlock.Paragraph -> block.text
        is ChatBlock.Heading -> block.text
        is ChatBlock.Code -> block.code
        is ChatBlock.Math -> block.formula
        is ChatBlock.Think -> block.content
        is ChatBlock.ToolCall -> block.output.orEmpty()
        is ChatBlock.Quote -> block.text
        is ChatBlock.Table -> block.rows.joinToString("\n") { it.joinToString(" | ") }
        is ChatBlock.ListBlock -> block.items.joinToString("\n") { it.text }
        is ChatBlock.Divider -> "---"
    }
}
