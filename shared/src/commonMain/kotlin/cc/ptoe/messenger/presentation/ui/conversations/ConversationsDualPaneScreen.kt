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

package cc.ptoe.messenger.presentation.ui.conversations

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.presentation.ui.chat.ChatScreen
import cc.ptoe.messenger.presentation.ui.components.EmptyState
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.chat_select_conversation
import org.jetbrains.compose.resources.stringResource

/**
 * Material 3 List-Detail layout for Desktop / large windows.
 *
 * Left pane  : `ConversationsScreen` (list, fixed ~360 dp wide).
 * Right pane : `ChatScreen` for the currently selected conversation, or an
 *              empty placeholder prompting the user to pick a conversation.
 *
 * The conversation list stays visible while chatting — no back-stack push,
 * no back button on the chat pane. This is the canonical M3 desktop chat
 * surface (cf. Gmail / Messages for web).
 *
 * Selection state is `rememberSaveable` so it survives rail navigation
 * to Agents / Settings and back.
 */
@Composable
fun ConversationsDualPaneScreen(
    initialConversationId: String?,
    onOpenConversationSettings: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedConversationId by rememberSaveable { mutableStateOf(initialConversationId) }

    Row(modifier = modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxHeight()
                .width(360.dp)
        ) {
            ConversationsScreen(
                onConversationClick = { id -> selectedConversationId = id },
                selectedConversationId = selectedConversationId,
                modifier = Modifier.fillMaxSize()
            )
        }

        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
        ) {
            val conversationId = selectedConversationId
            if (conversationId == null) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    message = stringResource(Res.string.chat_select_conversation),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                ChatScreen(
                    conversationId = conversationId,
                    onBackClick = { selectedConversationId = null },
                    onSettingsClick = { onOpenConversationSettings(conversationId) },
                    showBackButton = false,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
