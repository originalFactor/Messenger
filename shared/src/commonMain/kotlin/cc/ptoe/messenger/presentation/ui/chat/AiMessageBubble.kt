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

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.ptoe.llmtypewriter.SpeedCurve
import cc.ptoe.llmtypewriter.StreamingTypewriter
import cc.ptoe.llmtypewriter.StreamingTypewriterState
import cc.ptoe.llmtypewriter.rememberMarkdownTypewriterRenderer
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.presentation.ui.components.AgentAvatar
import cc.ptoe.messenger.presentation.utils.DateTimeUtils
import kotlinx.coroutines.flow.emptyFlow
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_retry
import cc.ptoe.messenger.generated.resources.chat_send_failed
import org.jetbrains.compose.resources.stringResource

@Composable
fun AiMessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    isGenerating: Boolean = false,
    isLastInGroup: Boolean = true,
    avatar: String? = null,
    onRetryClick: (() -> Unit)? = null,
    /**
     * The streamed text owned by the chat view model. When non-null and
     * [message]'s id matches [streamingMessageId], the bubble renders this
     * text instantly as the SSE stream feeds tokens into it — no typewriter
     * animation, every token paints as soon as it arrives.
     */
    streamingContent: String? = null,
    streamingMessageId: String? = null
) {
    val isError = message.status == MessageStatus.ERROR
    val bubbleColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val textColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    // Google Messages 风格：仅当组内最后一条消息时显示尾巴（左下角小尖角）
    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isLastInGroup) 4.dp else 18.dp,
        bottomEnd = 18.dp
    )

    val isLiveStream = streamingContent != null &&
        streamingMessageId != null &&
        message.id == streamingMessageId
    // 渲染内容：live 流式读 streamingContent（随 SSE token 即时增长），否则读已持久化内容
    val textForRender = if (isLiveStream) streamingContent.orEmpty() else message.content

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 64.dp, top = 2.dp, bottom = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            // 头像占位：仅在组内最后一条消息显示头像，其余保留相同宽度避免气泡跳动
            if (isLastInGroup) {
                AgentAvatar(avatar = avatar, size = 32.dp)
            } else {
                Spacer(modifier = Modifier.width(32.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .clip(bubbleShape)
                    .background(bubbleColor)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                if (isError) {
                    ErrorContent(
                        message = message,
                        textColor = textColor,
                        onRetryClick = onRetryClick
                    )
                } else if (textForRender.isNotEmpty() || isLiveStream) {
                    // token 即时绘制（无打字机动画）：live 流式内容读 streamingContent，
                    // 结束后回退到已持久化的 message.content。状态预填充 + skipToEnd +
                    // baseDelayMs=0 即立即渲染全文，且仅增长的尾部块随 token 重解析。
                    if (textForRender.isEmpty() && isLiveStream) {
                        // 尚未收到首个 token：显示三点输入指示
                        TypingIndicator()
                    } else {
                        val state = remember(message.id, textForRender) {
                            StreamingTypewriterState().apply {
                                if (textForRender.isNotEmpty()) {
                                    appendToken(textForRender)
                                    completeSource()
                                    skipToEnd()
                                }
                            }
                        }
                        val renderer = rememberMarkdownTypewriterRenderer(state)
                        StreamingTypewriter(
                            tokens = emptyFlow(),
                            state = state,
                            renderer = renderer,
                            baseDelayMs = 0L,
                            speedCurve = SpeedCurve.Linear,
                            tapToSkip = false
                        )
                    }
                } else {
                    TypingIndicator()
                }
            }
        }

        if (isLastInGroup) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = DateTimeUtils.formatMessageTime(message.timestamp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 44.dp)
            )
        }
    }
}

@Composable
private fun ErrorContent(
    message: Message,
    textColor: Color,
    onRetryClick: (() -> Unit)?
) {
    Column {
        Text(
            text = stringResource(Res.string.chat_send_failed),
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
        if (!message.errorMessage.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.errorMessage,
                color = textColor.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (onRetryClick != null) {
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                onClick = onRetryClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = textColor
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(Res.string.action_retry), color = textColor, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
        label = "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600, delayMillis = 200), repeatMode = RepeatMode.Reverse),
        label = "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600, delayMillis = 400), repeatMode = RepeatMode.Reverse),
        label = "dot3"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Dot(alpha = dot1Alpha)
        Spacer(modifier = Modifier.width(4.dp))
        Dot(alpha = dot2Alpha)
        Spacer(modifier = Modifier.width(4.dp))
        Dot(alpha = dot3Alpha)
    }
}

@Composable
private fun Dot(alpha: Float) {
    Box(
        modifier = Modifier
            .size(6.dp)
            .background(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                shape = RoundedCornerShape(50)
            )
    )
}
