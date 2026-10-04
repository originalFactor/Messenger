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

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.Message

@Composable
internal expect fun ChatMessageList(
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
    modifier: Modifier = Modifier
)
