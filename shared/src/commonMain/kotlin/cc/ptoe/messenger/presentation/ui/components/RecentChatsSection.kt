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

package cc.ptoe.messenger.presentation.ui.components

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.projects_recent_section
import org.jetbrains.compose.resources.stringResource

/**
 * Recent Chats 区：最近若干会话的快捷入口。
 *
 * 会话本身仍归在下方各自的文件夹里，这里只是让用户一键回到上一次的
 * 对话，因此行内不提供重命名/删除/切换 Agent —— 这些会话级操作统一留在
 * 文件夹页（[cc.ptoe.messenger.presentation.ui.projects.ProjectConversationsScreen]），
 * 避免同一操作出现两套入口。
 */
fun LazyListScope.recentChatSection(
    conversations: List<Conversation>,
    avatars: Map<String, String?>,
    onClick: (String) -> Unit,
    selectedConversationId: String? = null
) {
    if (conversations.isEmpty()) return
    item(key = "section_recent") {
        SectionHeader(stringResource(Res.string.projects_recent_section))
    }
    items(items = conversations, key = { "recent_${it.id}" }) { conversation ->
        ConversationListItem(
            conversation = conversation,
            avatar = avatars[conversation.agentId],
            isHighlighted = conversation.id == selectedConversationId,
            showActions = false,
            onClick = { onClick(conversation.id) },
            onLongClick = {},
            onCloneClick = {},
            onRenameClick = {},
            onDeleteClick = {},
            onSwitchAgentClick = {}
        )
    }
}
