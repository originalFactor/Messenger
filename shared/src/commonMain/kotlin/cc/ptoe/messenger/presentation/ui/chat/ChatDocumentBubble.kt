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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.presentation.ui.components.AgentAvatar
import cc.ptoe.messenger.presentation.utils.DateTimeUtils

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

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        if (isLastInGroup) {
            AgentAvatar(
                avatar = avatar,
                size = 32.dp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        } else {
            Spacer(modifier = Modifier.width(32.dp))
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f, fill = false)) {
            Box(
                modifier = Modifier
                    .clip(bubbleShape)
                    .background(bubbleColor)
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
                    val parsed = remember(message.content) {
                        val bridgeJson = cc.ptoe.messenger.core.CoreBridgeRegistry.bridge?.parseMarkdownToBlocksJson(message.content)
                        if (!bridgeJson.isNullOrBlank() && bridgeJson != "[]") {
                            ChatDocumentParser.parseBlocksJson(bridgeJson)
                        } else {
                            ChatDocumentParser.parseMarkdown(message.content)
                        }
                    }
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

            if (isError) {
                TextButton(
                    onClick = { onRetryClick?.invoke() },
                    modifier = Modifier.padding(top = 2.dp)
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
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                )
            }
        }
    }
}
