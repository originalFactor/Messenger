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
import cc.ptoe.messenger.generated.resources.projects_section
import cc.ptoe.messenger.presentation.utils.DateTimeUtils
import org.jetbrains.compose.resources.stringResource

/**
 * 项目分组：聊天列表（会话主页与 Agent 聊天列表）顶部的 Projects 区。
 */
fun LazyListScope.projectSection(
    projects: List<Project>,
    conversationCounts: Map<String, Int>,
    onProjectClick: (String) -> Unit,
    onProjectEditClick: (String) -> Unit = {},
    onProjectDeleteClick: (String) -> Unit = {},
    enableContextMenu: Boolean = false
) {
    if (projects.isEmpty()) return
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
                text = stringResource(Res.string.projects_count, conversationCount),
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