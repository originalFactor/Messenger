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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    modifier: Modifier
) {
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(
            items = chatItems.asReversed(),
            key = { item ->
                when (item) {
                    is ChatListItem.DateSeparator -> "date_${item.id}"
                    is ChatListItem.MessageItem -> "msg_${item.message.id}"
                    is ChatListItem.ToolGroupItem ->
                        "tool_${item.rounds.firstOrNull()?.id ?: item.toolMessages.firstOrNull()?.id}"
                }
            }
        ) { item ->
            when (item) {
                is ChatListItem.DateSeparator -> {
                    DateSeparator(timestamp = item.timestamp)
                }
                is ChatListItem.ToolGroupItem -> {
                    val anchor = item.finalMessage ?: item.rounds.lastOrNull()
                    val contextMenuState = rememberContextMenuState()
                    val groupInteractionSource = remember { MutableInteractionSource() }
                    val groupModifier = if (anchor != null) {
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                interactionSource = groupInteractionSource,
                                indication = null,
                                onClick = {},
                                onLongClick = {
                                    if (!enableContextMenu) {
                                        onMessageLongClick(anchor)
                                    }
                                }
                            )
                            .then(
                                if (enableContextMenu) Modifier.onContextMenu(contextMenuState)
                                else Modifier
                            )
                    } else {
                        Modifier
                    }
                    val isLiveStreaming = streamingMessageId != null && item.finalMessage?.id == streamingMessageId
                    val blocks = remember(item.rounds, item.toolMessages, item.finalMessage?.content, streamingContent, isLiveStreaming) {
                        buildDesktopToolGroupBlocks(
                            rounds = item.rounds,
                            toolMessages = item.toolMessages,
                            finalMessage = item.finalMessage,
                            streamingContent = if (isLiveStreaming) streamingContent else null
                        )
                    }
                    val anchorMessage = anchor ?: Message(
                        id = "tool_group_${item.toolMessages.firstOrNull()?.id ?: "0"}",
                        conversationId = "",
                        role = MessageRole.ASSISTANT,
                        content = "",
                        timestamp = item.toolMessages.firstOrNull()?.timestamp ?: 0L,
                        status = MessageStatus.SENT
                    )
                    Box(modifier = groupModifier) {
                        DesktopDocumentBubble(
                            message = anchorMessage,
                            blocks = blocks,
                            avatar = agent?.avatar,
                            isGenerating = isGenerating && isLiveStreaming,
                            isLastInGroup = item.isLastInGroup,
                            onRetryClick = {
                                if (anchorMessage.status == MessageStatus.ERROR) {
                                    onRetryClick(anchorMessage.id)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (enableContextMenu && anchor != null) {
                            MessageContextMenu(
                                state = contextMenuState,
                                messageRole = anchor.role,
                                onCopyClick = {
                                    val copyText = if (anchor.content.isNotBlank()) anchor.content else {
                                        blocks.joinToString("\n\n") { block ->
                                            when (block) {
                                                is DesktopBlock.Paragraph -> block.text
                                                is DesktopBlock.Heading -> block.text
                                                is DesktopBlock.Code -> block.code
                                                is DesktopBlock.Math -> block.formula
                                                is DesktopBlock.Think -> block.content
                                                is DesktopBlock.ToolCall -> block.output.orEmpty()
                                                is DesktopBlock.Quote -> block.text
                                                is DesktopBlock.Table -> block.rows.joinToString("\n") { it.joinToString(" | ") }
                                                is DesktopBlock.ListBlock -> block.items.joinToString("\n") { it.text }
                                                is DesktopBlock.Divider -> "---"
                                            }
                                        }
                                    }
                                    onCopyClick(copyText)
                                    showPlatformToast(copiedToastText)
                                },
                                onRegenerateClick = {
                                    onRegenerateClick(anchor.id)
                                },
                                onDeleteClick = {
                                    onDeleteClick(anchor.id)
                                },
                                onDismiss = {}
                            )
                        }
                    }
                }
                is ChatListItem.MessageItem -> {
                    val message = item.message
                    val contextMenuState = rememberContextMenuState()
                    val bubbleInteractionSource = remember { MutableInteractionSource() }
                    val bubbleModifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            interactionSource = bubbleInteractionSource,
                            indication = null,
                            onClick = {
                                if (message.status == MessageStatus.ERROR && message.role == MessageRole.ASSISTANT) {
                                    onRetryClick(message.id)
                                }
                            },
                            onLongClick = {
                                if (!enableContextMenu) {
                                    onMessageLongClick(message)
                                }
                            }
                        )
                        .then(
                            if (enableContextMenu) Modifier.onContextMenu(contextMenuState)
                            else Modifier
                        )

                    Box(modifier = bubbleModifier) {
                        when (message.role) {
                            MessageRole.USER -> {
                                UserMessageBubble(
                                    message = message,
                                    modifier = Modifier.fillMaxWidth(),
                                    isLastInGroup = item.isLastInGroup,
                                    avatar = userAvatar
                                )
                            }
                            MessageRole.ASSISTANT -> {
                                DesktopDocumentBubble(
                                    message = if (message.id == streamingMessageId && streamingContent != null) {
                                        message.copy(content = streamingContent)
                                    } else {
                                        message
                                    },
                                    blocks = null,
                                    avatar = agent?.avatar,
                                    isGenerating = isGenerating && message.id == streamingMessageId,
                                    isLastInGroup = item.isLastInGroup,
                                    onRetryClick = {
                                        if (message.status == MessageStatus.ERROR) {
                                            onRetryClick(message.id)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            MessageRole.SYSTEM, MessageRole.TOOL -> {}
                        }

                        if (enableContextMenu) {
                            MessageContextMenu(
                                state = contextMenuState,
                                messageRole = message.role,
                                onCopyClick = {
                                    onCopyClick(message.content)
                                    showPlatformToast(copiedToastText)
                                },
                                onRegenerateClick = {
                                    onRegenerateClick(message.id)
                                },
                                onDeleteClick = {
                                    onDeleteClick(message.id)
                                },
                                onDismiss = {}
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildDesktopToolGroupBlocks(
    rounds: List<Message>,
    toolMessages: List<Message>,
    finalMessage: Message?,
    streamingContent: String?
): List<DesktopBlock> {
    val blocks = mutableListOf<DesktopBlock>()
    var blockIdCounter = 1L

    if (rounds.isEmpty()) {
        val orphanResults = toolMessages.mapNotNull { it.parts.filterIsInstance<ContentPart.ToolResult>().firstOrNull() }
        orphanResults.forEachIndexed { _, result ->
            blocks.add(
                DesktopBlock.ToolCall(
                    id = blockIdCounter++,
                    callId = result.callId,
                    name = result.name,
                    arguments = "",
                    output = result.output,
                    isError = result.isError,
                    isFinalized = true
                )
            )
        }
        return blocks
    }

    rounds.forEachIndexed { roundIndex, round ->
        val roundBaseId = (roundIndex + 1) * 100_000L
        if (round.content.isNotBlank()) {
            val roundBlocks = DesktopDocumentParser.parseMarkdown(round.content, baseId = roundBaseId)
            blocks.addAll(roundBlocks)
        }
        val calls = round.parts.filterIsInstance<ContentPart.ToolCall>()
        calls.forEachIndexed { callIndex, call ->
            val result = toolMessages.findToolResult(call.callId)
            blocks.add(
                DesktopBlock.ToolCall(
                    id = roundBaseId + 50_000L + callIndex,
                    callId = call.callId,
                    name = call.name,
                    arguments = call.arguments,
                    output = result?.output,
                    isError = result?.isError ?: false,
                    isFinalized = result != null
                )
            )
        }
    }

    val finalText = streamingContent ?: finalMessage?.content
    if (!finalText.isNullOrBlank()) {
        val finalBlocks = DesktopDocumentParser.parseMarkdown(finalText, baseId = 10_000_000L)
        blocks.addAll(finalBlocks)
    }

    return blocks
}

private fun List<Message>.findToolResult(callId: String): ContentPart.ToolResult? {
    return asSequence()
        .mapNotNull { it.parts.filterIsInstance<ContentPart.ToolResult>().firstOrNull() }
        .filter { it.callId == callId }
        .filter { it.output.isNotEmpty() || it.isError }
        .firstOrNull()
}
