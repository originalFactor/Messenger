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

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ContentPart
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.presentation.utils.DateTimeUtils
import cc.ptoe.messenger.renderer.ConversationAdapter
import cc.ptoe.messenger.renderer.MessageItem
import cc.ptoe.messenger.renderer.RendererTheme
import cc.ptoe.messenger.renderer.ToolCallData
import cc.ptoe.messenger.renderer.ToolRoundData
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_copy
import cc.ptoe.messenger.generated.resources.action_retry
import cc.ptoe.messenger.generated.resources.chat_copied_toast
import cc.ptoe.messenger.generated.resources.chat_send_failed
import cc.ptoe.messenger.generated.resources.chat_think_title
import cc.ptoe.messenger.generated.resources.tool_card_failed
import cc.ptoe.messenger.generated.resources.tool_card_result_label
import cc.ptoe.messenger.generated.resources.tool_card_running
import cc.ptoe.messenger.generated.resources.tool_card_success
import cc.ptoe.messenger.generated.resources.tool_name_terminal
import org.jetbrains.compose.resources.stringResource
import java.io.File

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

    // Theme tokens: the native renderer follows the host MaterialTheme exactly
    // (light/dark + dynamic color), with localized labels and decoded avatars.
    val colorScheme = MaterialTheme.colorScheme
    val errorTitle = stringResource(Res.string.chat_send_failed)
    val retryAction = stringResource(Res.string.action_retry)
    val thinkingTitle = stringResource(Res.string.chat_think_title)
    val copyAction = stringResource(Res.string.action_copy)
    val copiedToast = stringResource(Res.string.chat_copied_toast)
    val runningLabel = stringResource(Res.string.tool_card_running)
    val successLabel = stringResource(Res.string.tool_card_success)
    val failedLabel = stringResource(Res.string.tool_card_failed)
    val resultLabel = stringResource(Res.string.tool_card_result_label)
    val terminalToolName = stringResource(Res.string.tool_name_terminal)
    // Avatars may be local file paths or content:// URIs (market agents, cloud
    // user avatar) — load them through Coil. Fallbacks are rendered from the
    // very same ImageVectors the Compose AgentAvatar draws (primaryContainer
    // circle + 60% icon), so native rows mirror the Compose fallback exactly.
    val avatarContext = LocalContext.current
    val assistantFallback = rememberVectorAvatarBitmap(
        icon = Icons.Default.SmartToy,
        background = colorScheme.primaryContainer,
        tint = colorScheme.onPrimaryContainer
    )
    val userFallback = rememberVectorAvatarBitmap(
        icon = Icons.Default.AccountCircle,
        background = colorScheme.primaryContainer,
        tint = colorScheme.onPrimaryContainer
    )
    var loadedAssistantAvatar by remember(agent?.avatar) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(agent?.avatar) {
        loadedAssistantAvatar = loadAvatarBitmap(avatarContext, agent?.avatar)
    }
    var loadedUserAvatar by remember(userAvatar) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(userAvatar) {
        loadedUserAvatar = loadAvatarBitmap(avatarContext, userAvatar)
    }
    val assistantAvatar = loadedAssistantAvatar ?: assistantFallback
    val userAvatarBitmap = loadedUserAvatar ?: userFallback
    val rendererTheme = remember(colorScheme, errorTitle, retryAction, thinkingTitle, copyAction, copiedToast, runningLabel, successLabel, failedLabel, resultLabel, terminalToolName, assistantAvatar, userAvatarBitmap) {
        RendererTheme(
            userBubble = colorScheme.primary.toArgb(),
            onUserBubble = colorScheme.onPrimary.toArgb(),
            aiBubble = colorScheme.surfaceContainerHigh.toArgb(),
            onAiBubble = colorScheme.onSurface.toArgb(),
            errorBubble = colorScheme.errorContainer.toArgb(),
            onErrorBubble = colorScheme.onErrorContainer.toArgb(),
            onSurfaceVariant = colorScheme.onSurfaceVariant.toArgb(),
            surfaceContainerHighest = colorScheme.surfaceContainerHighest.toArgb(),
            secondaryContainer = colorScheme.secondaryContainer.toArgb(),
            onSecondaryContainer = colorScheme.onSecondaryContainer.toArgb(),
            outlineVariant = colorScheme.outlineVariant.toArgb(),
            primary = colorScheme.primary.toArgb(),
            isDark = colorScheme.surface.luminance() < 0.5f,
            errorTitle = errorTitle,
            retryAction = retryAction,
            thinkingTitle = thinkingTitle,
            copyAction = copyAction,
            copiedToast = copiedToastText.ifBlank { copiedToast },
            runningLabel = runningLabel,
            successLabel = successLabel,
            failedLabel = failedLabel,
            resultLabel = resultLabel,
            terminalToolName = terminalToolName,
            assistantAvatar = assistantAvatar,
            userAvatar = userAvatarBitmap
        )
    }

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

    // Direct incremental streaming diff update: zero-recomposition, zero-adapter-notify.
    // The batch is routed by the round's message id — at tool-round boundaries
    // the next placeholder row may not have bound yet, in which case the
    // adapter parks the batch and replays it when the row binds.
    LaunchedEffect(streamingDiff) {
        if (!streamingDiff.isNullOrBlank()) {
            adapter.applyStreamingDiff(streamingMessageId, streamingDiff)
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
                    // One agent turn = one bubble: rounds render as text blocks +
                    // tool cards, the final text streams/appears after them.
                    val finalMsg = item.finalMessage ?: item.rounds.lastOrNull()
                    val isStreaming = finalMsg != null && finalMsg.id == streamingMessageId
                    val timestamp = finalMsg?.timestamp ?: item.rounds.lastOrNull()?.timestamp ?: 0L
                    val rounds = if (item.rounds.isEmpty()) {
                        // Orphan TOOL rows (historical anomaly): standalone result cards
                        listOf(orphanRound(item.toolMessages))
                    } else {
                        item.rounds.map { round -> roundToRoundData(round, item.toolMessages) }
                    }
                    MessageItem(
                        id = finalMsg?.id ?: ("tool_" + (item.rounds.firstOrNull()?.id ?: "0")),
                        role = "assistant",
                        content = if (isStreaming && streamingContent != null) streamingContent
                        else finalMsg?.content ?: "",
                        rounds = rounds,
                        isStreaming = isStreaming,
                        status = finalMsg?.status?.name ?: "SENT",
                        timestamp = timestamp,
                        timeText = DateTimeUtils.formatMessageTime(timestamp),
                        isLastInGroup = item.isLastInGroup
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
                        errorMessage = m.errorMessage,
                        timestamp = m.timestamp,
                        timeText = DateTimeUtils.formatMessageTime(m.timestamp),
                        isLastInGroup = item.isLastInGroup
                    )
                }
            }
        }
        adapter.submitList(rendererItems)
    }

    // Stick to the newest item when a turn starts and whenever a new round
    // placeholder takes over: position 0 is the viewport's bottom edge under
    // reverseLayout, and the growing bubble extends upward from there. Fires
    // on streamingMessageId changes only, so browsing history mid-turn is
    // never interrupted by per-token scrolls.
    var recyclerView by remember { mutableStateOf<RecyclerView?>(null) }
    LaunchedEffect(streamingMessageId) {
        if (streamingMessageId != null) {
            recyclerView?.scrollToPosition(0)
        } else {
            adapter.clearPendingDiffs()
        }
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
                recyclerView = this
            }
        },
        update = { rv ->
            adapter.theme = rendererTheme
        },
        modifier = modifier.fillMaxSize()
    )
}

/** Agent-turn round → structured renderer round (text + tool calls with settled results). */
private fun roundToRoundData(round: Message, toolMessages: List<Message>): ToolRoundData = ToolRoundData(
    text = round.content,
    calls = round.parts.filterIsInstance<ContentPart.ToolCall>().map { call ->
        val result = toolMessages.findResult(call.callId)
        ToolCallData(
            callId = call.callId,
            name = call.name,
            arguments = call.arguments,
            output = result?.output,
            isError = result?.isError ?: false,
            running = result == null
        )
    }
)

/** Orphan TOOL rows (no assistant round to hang onto): standalone result cards. */
private fun orphanRound(toolMessages: List<Message>): ToolRoundData = ToolRoundData(
    text = "",
    calls = toolMessages.mapNotNull { tool ->
        tool.parts.filterIsInstance<ContentPart.ToolResult>().firstOrNull()?.let { result ->
            ToolCallData(
                callId = result.callId,
                name = result.name,
                arguments = "",
                output = result.output,
                isError = result.isError,
                running = false
            )
        }
    }
)

private fun List<Message>.findResult(callId: String): ContentPart.ToolResult? {
    // 已完成行以 status=SENT 且结果非空为准；「运行中」行的空结果不在此返回
    return asSequence()
        .mapNotNull { it.parts.filterIsInstance<ContentPart.ToolResult>().firstOrNull() }
        .filter { it.callId == callId }
        .filter { it.output.isNotEmpty() || it.isError }
        .firstOrNull()
}

/**
 * Decode an avatar (file path or content:// URI) into a small bitmap; anything
 * else (blank, remote http(s) URL — AgentAvatar shows the fallback for those
 * too) or a failed load → null so the rendered vector fallback is used.
 */
private suspend fun loadAvatarBitmap(context: android.content.Context, source: String?): Bitmap? {
    if (source.isNullOrBlank() || source.startsWith("http://") || source.startsWith("https://")) return null
    return runCatching {
        val data = when {
            source.startsWith("/") -> File(source)
            else -> source
        }
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(96)
            .allowHardware(false)
            .build()
        (context.imageLoader.execute(request).image as? coil3.BitmapImage)?.bitmap
    }.getOrNull()
}

/**
 * Render the exact fallback the Compose AgentAvatar shows — background circle
 * + 60%-sized icon tinted — by drawing the ImageVector itself through a
 * CanvasDrawScope, so the glyph is identical by construction (no path-data
 * duplication that could drift from the material-icons version).
 */
@Composable
private fun rememberVectorAvatarBitmap(
    icon: ImageVector,
    background: Color,
    tint: Color,
    sizePx: Int = 96
): Bitmap {
    val painter = rememberVectorPainter(icon)
    return remember(painter, background, tint, sizePx) {
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bmp ->
            CanvasDrawScope().draw(
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                canvas = Canvas(bmp.asImageBitmap()),
                size = Size(sizePx.toFloat(), sizePx.toFloat())
            ) {
                drawCircle(color = background, radius = sizePx / 2f)
                val iconSize = sizePx * 0.6f
                val offset = (sizePx - iconSize) / 2f
                translate(offset, offset) {
                    with(painter) {
                        draw(size = Size(iconSize, iconSize), colorFilter = ColorFilter.tint(tint))
                    }
                }
            }
        }
    }
}
