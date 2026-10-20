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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.chat_select_conversation
import cc.ptoe.messenger.presentation.ui.chat.ChatScreen
import cc.ptoe.messenger.presentation.ui.components.EmptyState
import cc.ptoe.messenger.presentation.ui.projects.ProjectConversationsScreen
import org.jetbrains.compose.resources.stringResource

/**
 * Material 3 List-Detail layout for Desktop / large windows.
 *
 * Left pane  : folder list — `ConversationsScreen` (fixed ~360 dp wide).
 * Right pane : the selected folder's chat list (`ProjectConversationsScreen`),
 *              and `ChatScreen` for the currently selected conversation.
 *
 * The list stays visible while browsing; the folder page and chat render in
 * the right pane — no back-stack push, no back button there.
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
    /** 非空 = 右栏展示该文件夹（项目 id，或 [UNPROJECTED_FOLDER] 表示“无项目”）。 */
    var openFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var openFolderAgentId by rememberSaveable { mutableStateOf<String?>(null) }

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
                onProjectClick = { projectId ->
                    selectedConversationId = null
                    openFolderAgentId = null
                    openFolderId = projectId
                },
                onUnprojectedClick = {
                    selectedConversationId = null
                    openFolderAgentId = null
                    openFolderId = UNPROJECTED_FOLDER
                },
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
            val folderId = openFolderId
            when {
                conversationId != null -> {
                    ChatScreen(
                        conversationId = conversationId,
                        onBackClick = { selectedConversationId = null },
                        onSettingsClick = { onOpenConversationSettings(conversationId) },
                        showBackButton = false,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                folderId != null -> {
                    key(folderId, openFolderAgentId) {
                        ProjectConversationsScreen(
                            projectId = folderId.takeIf { it != UNPROJECTED_FOLDER },
                            agentId = openFolderAgentId,
                            showBackButton = false,
                            onBackClick = { openFolderId = null },
                            onConversationClick = { id -> selectedConversationId = id },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                else -> {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        message = stringResource(Res.string.chat_select_conversation),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/** “无项目”文件夹在保存状态里的哨兵 id（真实项目 id 是 UUID）。 */
internal const val UNPROJECTED_FOLDER = "__unprojected__"
