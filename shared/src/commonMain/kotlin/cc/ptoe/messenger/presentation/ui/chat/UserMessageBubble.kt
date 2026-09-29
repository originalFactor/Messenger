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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.ptoe.messenger.domain.model.ContentPart
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.presentation.ui.components.AgentAvatar
import cc.ptoe.messenger.presentation.utils.DateTimeUtils
import coil3.compose.AsyncImage
import okio.Path.Companion.toPath

@Composable
fun UserMessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    isLastInGroup: Boolean = true,
    avatar: String? = null
) {
    val isError = message.status == MessageStatus.ERROR
    val bubbleColor = if (isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    // Google Messages 风格：仅当组内最后一条消息时显示尾巴（右下角小尖角）
    val bubbleShape = RoundedCornerShape(
        topStart = 22.dp,
        topEnd = 22.dp,
        bottomStart = 22.dp,
        bottomEnd = if (isLastInGroup) 6.dp else 22.dp
    )

    val imageParts = message.parts.filterIsInstance<ContentPart.Image>()
    val textParts = message.parts.filterIsInstance<ContentPart.Text>()
    val displayText = if (textParts.isNotEmpty()) {
        textParts.joinToString("\n") { it.text }
    } else {
        message.content
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 64.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.End
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            // The bubble is wrapped in a weighted Box so the avatar (non-weighted)
            // is measured first and always gets its full 32dp. Without this, a long
            // message consumes all the row width during the bubble's measurement and
            // squeezes the avatar down to nothing. The Box aligns the bubble to its
            // end so it stays flush against the avatar on the right.
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd
            ) {
                Column(
                    modifier = Modifier
                        .clip(bubbleShape)
                        .background(bubbleColor)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .wrapContentWidth(),
                    horizontalAlignment = Alignment.End
                ) {
                    if (imageParts.isNotEmpty()) {
                        // Caption-then-images matches the natural reading
                        // order in chat UIs (Google Messages does the same).
                        UserMessageImageRow(images = imageParts)
                    }
                    if (displayText.isNotEmpty()) {
                        if (imageParts.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            text = displayText,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // 行内状态指示（Google Messages 风格：放气泡右下角，半透明）
                    // 仅在发送中/出错时显示，已发送不展示勾选
                    if (message.status != MessageStatus.SENT) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MessageStatusIndicator(message = message, tint = Color.White.copy(alpha = 0.75f))
                        }
                    }
                }
            }
            if (isLastInGroup) {
                Spacer(modifier = Modifier.width(8.dp))
                AgentAvatar(
                    avatar = avatar,
                    size = 32.dp,
                    fallbackIcon = Icons.Default.AccountCircle
                )
            } else {
                Spacer(modifier = Modifier.width(40.dp))
            }
        }

        if (isLastInGroup) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = DateTimeUtils.formatMessageTime(message.timestamp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier.padding(end = 36.dp)
            )
        }
    }
}

@Composable
private fun UserMessageImageRow(
    images: List<ContentPart.Image>,
    modifier: Modifier = Modifier
) {
    // Horizontal row, capped at a reasonable width so a 6-image
    // attachment doesn't push the bubble to the screen edge. Each
    // thumbnail is square and 96dp; Coil decodes the cached PNG copy.
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(images, key = { it.image.localPath }) { part ->
            AsyncImage(
                model = part.image.localPath.toPath(),
                contentDescription = null,
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.18f))
            )
        }
    }
}

@Composable
private fun MessageStatusIndicator(message: Message, tint: Color) {
    when (message.status) {
        MessageStatus.SENDING -> Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = tint
        )
        MessageStatus.ERROR -> Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = tint
        )
        MessageStatus.SENT -> {}
    }
}
