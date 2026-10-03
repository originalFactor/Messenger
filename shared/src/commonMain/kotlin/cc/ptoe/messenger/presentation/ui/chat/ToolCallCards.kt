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
import cc.ptoe.llmtypewriter.MarkdownToolCallRenderer
import cc.ptoe.messenger.domain.model.ContentPart
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.tool.TerminalTool
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.tool_card_failed
import cc.ptoe.messenger.generated.resources.tool_card_result_label
import cc.ptoe.messenger.generated.resources.tool_card_running
import cc.ptoe.messenger.generated.resources.tool_card_success
import cc.ptoe.messenger.generated.resources.tool_name_terminal
import cc.ptoe.messenger.presentation.ui.components.toolIcon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.stringResource

/**
 * 一个完整的代理回合渲染为一条消息：内容流 = 各工具轮的轮内文本 + `<tool_call>`
 * 流内标记（卡片由 llm-typewriter 在 Agent 发起调用的位置就地绘出）+ 最终文本，
 * 全部同属一个气泡。[finalMessage] 是流式中的占位行时，正文部分实时读
 * [streamingContent]（卡片之下原地续写）。[rounds] 为空时是孤儿 TOOL 行的
 * 兜底展示（无气泡可挂，退回独立卡片）。
 */
@Composable
fun ToolGroupItem(
    rounds: List<Message>,
    toolMessages: List<Message>,
    finalMessage: Message?,
    isLastInGroup: Boolean,
    streamingContent: String?,
    streamingMessageId: String?,
    modifier: Modifier = Modifier
) {
    if (rounds.isEmpty()) {
        // 孤儿 TOOL 行（历史异常）：无 assistant 轮可挂，保持独立卡片兜底展示
        val orphanResults = toolMessages.mapNotNull { it.parts.filterIsInstance<ContentPart.ToolResult>().firstOrNull() }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            orphanResults.forEach { result ->
                ToolCallCard(
                    toolName = result.name,
                    command = null,
                    result = result,
                    isRunning = false,
                    inBubble = false,
                    modifier = Modifier.padding(start = 8.dp, end = 64.dp)
                )
            }
        }
        return
    }
    val anchor = finalMessage ?: rounds.last()
    // 占位行在流式中时正文实时取自 streamingContent（DB 行在 Done 前不更新）
    val isLiveStreaming = streamingMessageId != null && finalMessage?.id == streamingMessageId
    val renderContent = buildString {
        rounds.forEach { round ->
            if (round.content.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(round.content)
            }
            round.parts.filterIsInstance<ContentPart.ToolCall>().forEach { call ->
                if (isNotEmpty()) append("\n\n")
                // 结果实时取自 TOOL 行：已落定的行携带 output/isError，运行中行不带
                append(buildToolCallMarker(call, toolMessages.findResult(call.callId)))
            }
        }
        val finalText = if (isLiveStreaming) streamingContent.orEmpty() else finalMessage?.content.orEmpty()
        if (finalText.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(finalText)
        }
    }
    AiMessageBubble(
        message = anchor,
        isLastInGroup = isLastInGroup,
        modifier = modifier.fillMaxWidth(),
        displayContent = renderContent,
        toolCallRenderer = MarkdownToolCallRenderer { payload -> ToolCallBlockCard(payload) }
    )
}

/** `<tool_call>` 流内标记的载荷。[output] / [isError] 仅在结果落定时携带。 */
internal data class ToolCallMarkerPayload(
    val callId: String,
    val name: String,
    val arguments: String,
    val output: String? = null,
    val isError: Boolean = false
)

/**
 * 把一次工具调用编码为 `<tool_call>{json}</tool_call>` 流内标记。JSON 转义
 * 保证参数/输出中的引号与换行不破坏标记结构（字面 `</tool_call>` 除外，
 * 正常工具输出不会出现）。
 */
internal fun buildToolCallMarker(call: ContentPart.ToolCall, result: ContentPart.ToolResult?): String {
    val payload = buildJsonObject {
        put("callId", JsonPrimitive(call.callId))
        put("name", JsonPrimitive(call.name))
        put("arguments", JsonPrimitive(call.arguments))
        if (result != null) {
            put("output", JsonPrimitive(result.output))
            put("isError", JsonPrimitive(result.isError))
        }
    }
    return "<tool_call>$payload</tool_call>"
}

/** 解析 `<tool_call>` 标记载荷；不完整或非法的载荷返回 null（由调用方退化展示）。 */
internal fun parseToolCallMarker(payload: String): ToolCallMarkerPayload? = try {
    val obj = Json.parseToJsonElement(payload) as? JsonObject ?: return null
    ToolCallMarkerPayload(
        callId = obj["callId"]?.jsonPrimitive?.contentOrNull ?: return null,
        name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null,
        arguments = obj["arguments"]?.jsonPrimitive?.contentOrNull ?: "",
        output = obj["output"]?.jsonPrimitive?.contentOrNull,
        isError = obj["isError"]?.jsonPrimitive?.booleanOrNull ?: false
    )
} catch (_: Exception) {
    null
}

/** `<tool_call>` 流内标记的卡片渲染：解析载荷并复用 [ToolCallCard]；解析失败时等宽展示原文。 */
@Composable
internal fun ToolCallBlockCard(payload: String) {
    val parsed = remember(payload) { parseToolCallMarker(payload) }
    if (parsed == null) {
        MonospaceBlock(text = payload.ifBlank { "—" })
        return
    }
    val result = parsed.output?.let {
        ContentPart.ToolResult(
            callId = parsed.callId,
            name = parsed.name,
            output = it,
            isError = parsed.isError
        )
    }
    ToolCallCard(
        toolName = parsed.name,
        command = TerminalTool.parseCommand(parsed.arguments) ?: parsed.arguments,
        result = result,
        isRunning = result == null,
        inBubble = true
    )
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
    inBubble: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember(toolName, command) { mutableStateOf(false) }
    // 气泡内的卡片底色比气泡（surfaceContainerHigh）再深一级以保持对比；
    // 孤儿行的独立卡片直接坐在 surface 上，维持原底色。
    val containerColor = when {
        isRunning -> MaterialTheme.colorScheme.secondaryContainer
        result?.isError == true -> MaterialTheme.colorScheme.errorContainer
        inBubble -> MaterialTheme.colorScheme.surfaceContainerHighest
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
                imageVector = toolIcon(toolName),
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
