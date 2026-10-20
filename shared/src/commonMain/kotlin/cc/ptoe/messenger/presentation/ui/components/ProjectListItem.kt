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

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.domain.model.Project
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_delete
import cc.ptoe.messenger.generated.resources.action_edit
import cc.ptoe.messenger.generated.resources.action_more
import cc.ptoe.messenger.generated.resources.projects_count
import cc.ptoe.messenger.generated.resources.projects_count_one
import cc.ptoe.messenger.generated.resources.projects_no_project_folder
import cc.ptoe.messenger.generated.resources.projects_section
import cc.ptoe.messenger.presentation.utils.DateTimeUtils
import org.jetbrains.compose.resources.stringResource

/**
 * 聊天列表的文件夹分组：项目（= 工作区）各自一行，外加一个"无项目"
 * 文件夹收纳不属于任何项目的普通对话。列表不再直接铺开会话行，避免与
 * 其他分组重复展示。
 */
fun LazyListScope.projectSection(
    projects: List<Project>,
    conversationCounts: Map<String, Int>,
    onProjectClick: (String) -> Unit,
    onProjectEditClick: (String) -> Unit = {},
    onProjectDeleteClick: (String) -> Unit = {},
    enableContextMenu: Boolean = false,
    unprojectedConversationCount: Int = 0,
    onUnprojectedClick: () -> Unit = {}
) {
    if (projects.isEmpty() && unprojectedConversationCount == 0) return
    item(key = "section_projects") {
        SectionHeader(stringResource(Res.string.projects_section))
    }
    items(items = projects, key = { "project_${it.id}" }) { project ->
        ProjectListItem(
            project = project,
            conversationCount = conversationCounts[project.id] ?: 0,
            onClick = { onProjectClick(project.id) },
            onEditClick = { onProjectEditClick(project.id) },
            onDeleteClick = { onProjectDeleteClick(project.id) },
            enableContextMenu = enableContextMenu
        )
    }
    // 始终显示“无项目”文件夹：新建的普通对话总会落在这里，入口不应随
    // 会话数忽隐忽现。
    item(key = "unprojected_folder") {
        UnprojectedFolderItem(
            conversationCount = unprojectedConversationCount,
            onClick = onUnprojectedClick,
            enableContextMenu = enableContextMenu
        )
    }
}

/**
 * "无项目"文件夹行：没有工作区的普通对话都收纳在这里，因此聊天列表
 * 只有文件夹，不会再出现同一会话既在 Recent 又在底部的重复。
 */
@Composable
fun UnprojectedFolderItem(
    conversationCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enableContextMenu: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val backgroundColor = if (hovered && enableContextMenu) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(backgroundColor)
            .hoverable(interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.projects_no_project_folder),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = conversationCountText(conversationCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 项目行：文件夹图标 + 名称 + 会话数 + 更新时间，菜单提供重命名与删除。
 */
@Composable
fun ProjectListItem(
    project: Project,
    conversationCount: Int,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    enableContextMenu: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val contextMenuState = rememberContextMenuState()
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val backgroundColor = if (hovered && enableContextMenu) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(backgroundColor)
            .hoverable(interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onEditClick
            )
            .then(
                if (enableContextMenu) Modifier.onContextMenu(contextMenuState)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = project.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = conversationCountText(conversationCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = DateTimeUtils.formatMessageTime(project.updatedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(Res.string.action_more)
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_edit)) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onEditClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_delete)) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onDeleteClick()
                    }
                )
            }
        }

        if (enableContextMenu) {
            CursorDropdownMenu(
                state = contextMenuState,
                onDismiss = {}
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_edit)) },
                    onClick = {
                        contextMenuState.hide()
                        onEditClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_delete)) },
                    onClick = {
                        contextMenuState.hide()
                        onDeleteClick()
                    }
                )
            }
        }
    }
}

/** “1 个对话”用单数文案，避免英文出现 "1 chats"。 */
@Composable
private fun conversationCountText(count: Int): String =
    if (count == 1) stringResource(Res.string.projects_count_one, count)
    else stringResource(Res.string.projects_count, count)
