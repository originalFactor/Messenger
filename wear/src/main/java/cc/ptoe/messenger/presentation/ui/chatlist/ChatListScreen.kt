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

package cc.ptoe.messenger.presentation.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import cc.ptoe.messenger.data.WearConnectionState
import cc.ptoe.messenger.presentation.ui.components.BannerMessage
import cc.ptoe.messenger.presentation.ui.components.WearAvatar
import cc.ptoe.messenger.presentation.ui.components.verticalRotaryScroll
import cc.ptoe.messenger.presentation.viewmodel.WearChatListItem
import cc.ptoe.messenger.presentation.viewmodel.WearChatUiState

@Composable
fun ChatListScreen(
    uiState: WearChatUiState,
    onChatOpen: (String) -> Unit,
    onNewChat: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalRotaryScroll(listState)
        ) {
            item(key = "title") {
                Text(
                    text = "Chats",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 4.dp),
                    textAlign = TextAlign.Center
                )
            }

            uiState.connectionState.let { state ->
                val banner = when (state) {
                    WearConnectionState.Connected -> null
                    WearConnectionState.Disconnected -> "未连手机 — 等待手机端 Messenger"
                    is WearConnectionState.Connecting -> state.detail
                    is WearConnectionState.Error -> state.message
                }
                if (banner != null) {
                    item(key = "conn_banner") {
                        Column {
                            BannerMessage(banner)
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = onReconnect,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("重试连接")
                            }
                        }
                    }
                }
            }

            if (uiState.bannerMessage != null) {
                item(key = "msg_banner") {
                    BannerMessage(uiState.bannerMessage)
                }
            }

            if (uiState.chats.isEmpty()) {
                item(key = "empty") {
                    EmptyChatList(
                        hasAgents = uiState.agents.isNotEmpty(),
                        isCreatingChat = uiState.isCreatingChat,
                        onNewChat = onNewChat,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                items(uiState.chats, key = { it.conversation.id }) { chat ->
                    ChatListItem(
                        chat = chat,
                        onClick = { onChatOpen(chat.conversation.id) }
                    )
                }
                item(key = "new_chat") {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onNewChat,
                        enabled = !uiState.isCreatingChat && uiState.agents.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (uiState.isCreatingChat) "Creating..." else "New chat")
                    }
                }
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        ScrollIndicator(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun ChatListItem(
    chat: WearChatListItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val conversation = chat.conversation
    val agent = chat.agent
    val preview = conversation.lastMessage?.ifBlank { null } ?: "No messages yet"
    val isReady = agent?.isReady != false

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            WearAvatar(
                avatarPath = agent?.avatarPath,
                size = 36.dp
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (isReady) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conversation.title.ifBlank { agent?.name ?: "Chat" },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (agent != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EmptyChatList(
    hasAgents: Boolean,
    isCreatingChat: Boolean,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (hasAgents) "No chats yet" else "Waiting for phone",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (hasAgents) {
                "Start a chat to message from your watch."
            } else {
                "Open Messenger on your phone to sync conversations automatically."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (hasAgents) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onNewChat,
                enabled = !isCreatingChat
            ) {
                Text(if (isCreatingChat) "Creating..." else "New chat")
            }
        }
    }
}
