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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.presentation.ui.components.AgentAvatar
import cc.ptoe.messenger.presentation.utils.DateTimeUtils

/** Test hook: identifies the assistant bubble surface for layout assertions. */
internal const val CHAT_BUBBLE_TEST_TAG = "chat_bubble"

/** Test hook: identifies the assistant row's avatar for alignment assertions. */
internal const val CHAT_AVATAR_TEST_TAG = "chat_avatar"

@Composable
fun ChatDocumentBubble(
    message: Message,
    blocks: List<ChatBlock>?,
    avatar: String?,
    isGenerating: Boolean,
    isLastInGroup: Boolean,
    onRetryClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    // Stable across recompositions: the cache memoizes the parsed block list
    // for the completed-line prefix of a streaming message.
    val markdownCache = remember { StreamingMarkdownCache() }
    val isError = message.status == MessageStatus.ERROR
    val bubbleColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isLastInGroup) 4.dp else 18.dp,
        bottomEnd = 18.dp
    )

    // Same row insets as the Android renderer (8 dp at the avatar side, 64 dp
    // opposite): without the trailing inset a long answer stretches the bubble
    // all the way to the pane's edge.
    //
    // The timestamp sits BELOW the row, not inside the bubble's column. The row
    // bottom-aligns the avatar against the bubble, so a timestamp inside the
    // row's tallest child would push the avatar a whole line (16.6 dp at 175%
    // scale) below the bubble's bottom edge and bury the tail corner. Android
    // keeps its timestamp outside the content row for the same reason.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 64.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (isLastInGroup) {
                AgentAvatar(
                    avatar = avatar,
                    size = 32.dp,
                    modifier = Modifier.testTag(CHAT_AVATAR_TEST_TAG)
                )
            } else {
                Spacer(modifier = Modifier.width(32.dp))
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(modifier = Modifier.weight(1f, fill = false)) {
                Box(
                    modifier = Modifier
                        // Size to the message, capped by the available width. The
                        // document blocks fill this width, so without the intrinsic
                        // pass a one-word reply would still span the whole pane.
                        .width(IntrinsicSize.Max)
                        .testTag(CHAT_BUBBLE_TEST_TAG)
                        // Draw the shape without clipping the entire (possibly very tall)
                        // layer: on Desktop that clip can reject pointer hits far
                        // down the bubble. Padding keeps content inside the corners.
                        .background(bubbleColor, bubbleShape)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    if (blocks != null && blocks.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChatDocumentView(blocks = blocks)
                            val lastBlock = blocks.lastOrNull()
                            val waitingForText = isGenerating && (lastBlock is ChatBlock.ToolCall && lastBlock.isFinalized)
                            if (waitingForText) {
                                Text(
                                    text = "● ● ●",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else if (message.content.isNotBlank()) {
                        // Streaming text changes on every token. Re-parsing the
                        // WHOLE accumulated markdown per token is O(n²) plus a
                        // cross-language call each time; the cache only re-parses
                        // the stable line prefix when it changes.
                        val parsed = remember(message.content) { markdownCache.blocksFor(message.content) }
                        if (parsed.isNotEmpty()) {
                            ChatDocumentView(blocks = parsed)
                        } else {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else if (isGenerating) {
                        Text(
                            text = "● ● ●",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Both trailing labels start at the bubble's left edge: the avatar (32
        // dp) plus its gap (8 dp) inside the padded row.
        if (isError) {
            TextButton(
                onClick = { onRetryClick?.invoke() },
                modifier = Modifier.padding(start = 40.dp, top = 2.dp)
            ) {
                Text(
                    text = "Retry",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else if (isLastInGroup) {
            Text(
                text = DateTimeUtils.formatMessageTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 44.dp, top = 2.dp)
            )
        }
    }
}
