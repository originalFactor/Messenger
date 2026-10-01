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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.domain.model.ContentPart
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.tool.TerminalTool
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.tool_card_failed
import cc.ptoe.messenger.generated.resources.tool_card_result_label
import cc.ptoe.messenger.generated.resources.tool_card_running
import cc.ptoe.messenger.generated.resources.tool_card_success
import cc.ptoe.messenger.generated.resources.tool_name_terminal
import org.jetbrains.compose.resources.stringResource

/**
 * 一组工具调用卡片：assistant 工具轮消息 + 紧随的 TOOL 结果行合并渲染。
 * [assistant] 带轮内文本时先渲染文本气泡；为 null 时是孤儿 TOOL 行的兜底展示。
 */
@Composable
fun ToolGroupItem(
    assistant: Message?,
    toolMessages: List<Message>,
    modifier: Modifier = Modifier
) {
    val calls = assistant?.parts?.filterIsInstance<ContentPart.ToolCall>().orEmpty()
    Column(
        // 水平缩进由各子项自理：AiMessageBubble 自带 start 8 / end 64 内边距，
        // 卡片在下方单独套用同样的缩进，避免双重叠加。
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (assistant != null && assistant.content.isNotBlank()) {
            // 轮内文本（如「让我先看一下目录结构」）仍以普通气泡呈现，
            // 卡片紧跟其下；组内 isLastInGroup=false，尾巴样式由下一组承担。
            AiMessageBubble(
                message = assistant,
                isLastInGroup = false,
                modifier = Modifier.fillMaxWidth()
            )
        }
        // 每个调用一张卡；无调用记录的孤儿 TOOL 行直接渲染结果卡
        val renderCalls: List<Pair<ContentPart.ToolCall, ContentPart.ToolResult?>> =
            calls.map { call -> call to toolMessages.findResult(call.callId) }
        val orphanResults = if (calls.isEmpty()) {
            toolMessages.mapNotNull { it.parts.filterIsInstance<ContentPart.ToolResult>().firstOrNull() }
        } else {
            emptyList()
        }
        (renderCalls.map { CardRender(call = it.first, result = it.second) } +
            orphanResults.map { CardRender(call = null, result = it) })
            .forEach { render ->
                ToolCallCard(
                    toolName = render.call?.name ?: render.result?.name ?: "",
                    command = render.call?.let { TerminalTool.parseCommand(it.arguments) ?: it.arguments },
                    result = render.result,
                    isRunning = render.isRunning(toolMessages),
                    modifier = Modifier.padding(start = 8.dp, end = 64.dp)
                )
            }
    }
}

private data class CardRender(val call: ContentPart.ToolCall?, val result: ContentPart.ToolResult?)

private fun CardRender.isRunning(toolMessages: List<Message>): Boolean {
    if (result != null) return false
    val callId = call?.callId ?: result?.callId
    val source = toolMessages.firstOrNull { row ->
        row.parts.filterIsInstance<ContentPart.ToolResult>().any { it.callId == callId }
    } ?: return false
    return source.status == MessageStatus.SENDING
}

private fun List<Message>.findResult(callId: String): ContentPart.ToolResult? {
    // 已完成行以 status=SENT 且结果非空为准；「运行中」行的空结果不在此返回
    return asSequence()
        .mapNotNull { it.parts.filterIsInstance<ContentPart.ToolResult>().firstOrNull() }
        .filter { it.callId == callId }
        .filter { it.output.isNotEmpty() || it.isError }
        .firstOrNull()
}

/** 单张工具调用卡：标题行（图标 + 名称 + 状态）+ 可折叠的命令与结果。 */
@Composable
private fun ToolCallCard(
    toolName: String,
    command: String?,
    result: ContentPart.ToolResult?,
    isRunning: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember(toolName, command) { mutableStateOf(false) }
    val containerColor = when {
        isRunning -> MaterialTheme.colorScheme.secondaryContainer
        result?.isError == true -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(containerColor)
            .animateContentSize()
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = toolDisplayName(toolName),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            StatusBadge(isRunning = isRunning, result = result)
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 180f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                if (!command.isNullOrBlank()) {
                    MonospaceBlock(text = command, modifier = Modifier.padding(top = 8.dp))
                }
                result?.let {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = stringResource(Res.string.tool_card_result_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MonospaceBlock(
                        text = it.output.ifBlank { "—" },
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        // 折叠态下给一行命令预览，用户不必展开也能看到模型要跑什么
        if (!expanded && !command.isNullOrBlank()) {
            Text(
                text = command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun StatusBadge(isRunning: Boolean, result: ContentPart.ToolResult?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            isRunning -> {
                Text(
                    text = stringResource(Res.string.tool_card_running),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            result == null -> {}
            result.isError -> {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = stringResource(Res.string.tool_card_failed),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(Res.string.tool_card_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(Res.string.tool_card_success),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MonospaceBlock(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

/** 工具显示名：内置终端工具本地化，其余展示模型侧原名。 */
@Composable
private fun toolDisplayName(name: String): String = when (name) {
    TerminalTool.TOOL_NAME -> stringResource(Res.string.tool_name_terminal)
    else -> name
}
