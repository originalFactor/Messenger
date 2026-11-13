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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Web
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Group
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import cc.ptoe.messenger.data.repository.CoreToolCatalogState
import cc.ptoe.messenger.data.repository.CoreToolDescriptor
import cc.ptoe.messenger.domain.tool.ChatTool
import cc.ptoe.messenger.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import cc.ptoe.messenger.domain.tool.TerminalTool
import cc.ptoe.messenger.domain.tool.WorkspaceTool

/**
 * 内置工具的专属图标：聊天流的工具调用卡与工具配置子页（Agent 编辑 /
 * 会话设置）共用同一映射，同一工具在全应用里视觉一致。
 * MCP 工具与未知名称回退到通用的扩展图标。
 */
fun toolIcon(name: String): ImageVector = when (name) {
    TerminalTool.TOOL_NAME -> Icons.Default.Terminal
    WorkspaceTool.GLOB -> Icons.Default.FolderOpen
    WorkspaceTool.GREP -> Icons.Default.Search
    WorkspaceTool.READ -> Icons.Default.Description
    WorkspaceTool.EDIT -> Icons.Default.Edit
    WorkspaceTool.CREATE -> Icons.AutoMirrored.Filled.NoteAdd
    "messenger_learning" -> Icons.Default.School
    "messenger_artifacts" -> Icons.Default.Web
    "messenger_generate_image" -> Icons.Default.Image
    "messenger_skills" -> Icons.Default.MenuBook
    "messenger_agents" -> Icons.Default.Group
    else -> Icons.Default.Extension
}

@Composable
fun toolDisplayName(name: String): String = when (name) {
    TerminalTool.TOOL_NAME -> stringResource(Res.string.tool_name_terminal)
    "messenger_learning" -> stringResource(Res.string.tool_name_learning)
    "messenger_artifacts" -> stringResource(Res.string.tool_name_artifacts)
    "messenger_generate_image" -> stringResource(Res.string.tool_name_generate_image)
    "messenger_skills" -> stringResource(Res.string.tool_name_skills)
    "messenger_agents" -> stringResource(Res.string.tool_name_agents)
    else -> name
}

@Composable
fun toolDescription(tool: ChatTool): String {
    val description = when (tool.name) {
        TerminalTool.TOOL_NAME -> stringResource(Res.string.tool_terminal_desc)
        "messenger_learning" -> stringResource(Res.string.tool_desc_learning)
        "messenger_artifacts" -> stringResource(Res.string.tool_desc_artifacts)
        "messenger_generate_image" -> stringResource(Res.string.tool_desc_generate_image)
        "messenger_skills" -> stringResource(Res.string.tool_desc_skills)
        "messenger_agents" -> stringResource(Res.string.tool_desc_agents)
        else -> tool.description
    }
    return if (tool is CoreToolDescriptor && !tool.capabilityEnabled) {
        description + "\n" + stringResource(Res.string.tool_capability_disabled)
    } else description
}

@Composable
fun CoreToolCatalogStatus(state: CoreToolCatalogState, onRetry: () -> Unit) {
    val error = state.error
    if (error != null) {
        Text(stringResource(Res.string.tool_catalog_error, error), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
    } else if (!state.ready) {
        Text(stringResource(Res.string.tool_catalog_loading), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
    }
}
