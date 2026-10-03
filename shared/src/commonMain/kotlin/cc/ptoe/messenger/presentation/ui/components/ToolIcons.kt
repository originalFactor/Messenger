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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
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
    else -> Icons.Default.Extension
}
