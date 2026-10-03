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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.domain.model.MessageImage
import cc.ptoe.messenger.presentation.platform.BackHandler
import cc.ptoe.messenger.presentation.platform.sendOnEnterShortcut
import coil3.compose.AsyncImage
import okio.Path.Companion.toPath
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_send
import cc.ptoe.messenger.generated.resources.action_stop
import cc.ptoe.messenger.generated.resources.chat_add_image
import cc.ptoe.messenger.generated.resources.chat_image_description_hint
import cc.ptoe.messenger.generated.resources.chat_message_hint
import cc.ptoe.messenger.generated.resources.chat_mode_readonly
import cc.ptoe.messenger.generated.resources.chat_mode_writable
import cc.ptoe.messenger.generated.resources.chat_remove_image
import org.jetbrains.compose.resources.stringResource

/**
 * Bottom-of-screen input area. Owns three concerns:
 *
 *  - A horizontal preview strip for images the user picked but hasn't
 *    sent yet. Each tile has an X to drop that single image.
 *  - The `+` button toggles a WeChat-style bottom panel: the input area
 *    (and the message list above it) shifts up while the panel expands in
 *    place, exposing the photo-picker entry and the Agent read-only/writable
 *    mode toggle (`onAddClick` and `onAgentModeChange` are wired by the
 *    screen; `agentWritable == null` hides the mode entry).
 *  - The pill-shaped text field plus the send / stop action button,
 *    identical to the legacy Google-Messages style.
 *
 * The send button is enabled when there is at least one pending image
 * OR the text field is non-blank, mirroring `ChatViewModel.sendMessage`.
 */
@Composable
fun ChatInputBar(
    text: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    isGenerating: Boolean,
    pendingImages: List<MessageImage> = emptyList(),
    isAttachingImage: Boolean = false,
    onAddClick: () -> Unit = {},
    onRemoveImage: (MessageImage) -> Unit = {},
    /**
     * Agent 模式；null 表示平台未注册工具（面板不展示模式切换项）。
     * false=只读（终端保持只读命令白名单 + 禁用写入工具），true=可写
     * （终端解除只读策略 + 写入工具可用）。两种模式下工具都自动执行。
     */
    agentWritable: Boolean? = null,
    onAgentModeChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var functionPanelExpanded by remember { mutableStateOf(false) }

    // 面板展开时拦截系统返回：先收起面板而不是退出聊天页
    BackHandler(enabled = functionPanelExpanded, onBack = { functionPanelExpanded = false }) {}

    // Enter-to-send keyboard shortcut. Controlled by the platform expect
    // value `sendOnEnterShortcut` — enabled on Desktop (physical keyboard)
    // and disabled on Android (virtual IME doesn't send KeyEvents).
    val sendShortcutModifier: Modifier = if (sendOnEnterShortcut) {
        Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
            val hasModifier = event.isShiftPressed || event.isCtrlPressed
            if (hasModifier) {
                // Shift+Enter / Ctrl+Enter → insert newline at cursor / selection.
                val src = text.text
                val selMin = minOf(text.selection.min, text.selection.max)
                val selMax = maxOf(text.selection.min, text.selection.max)
                val newText = src.take(selMin) + "\n" + src.drop(selMax)
                val caret = selMin + 1
                onTextChange(TextFieldValue(newText, androidx.compose.ui.text.TextRange(caret)))
                true
            } else {
                // Bare Enter → send, if there's anything to send.
                if (text.text.isNotBlank() || pendingImages.isNotEmpty()) {
                    onSendClick()
                }
                true
            }
        }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        if (pendingImages.isNotEmpty()) {
            PendingImagesStrip(
                images = pendingImages,
                onRemoveImage = onRemoveImage
            )
        }

        // 微信式功能面板：展开时把输入行（及其上方的消息列表）顶起，
        // 在输入行上方原地展开功能项；"+" 再点一次收起。
        AnimatedVisibility(
            visible = functionPanelExpanded,
            enter = expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                expandFrom = Alignment.Bottom
            ) + fadeIn(tween(durationMillis = 180)),
            exit = shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                shrinkTowards = Alignment.Bottom
            ) + fadeOut(tween(durationMillis = 180))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // 微信「+」面板样式：圆角方块图标 + 下方小字标签，每行固定 4 格
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    FunctionPanelEntry(
                        text = stringResource(Res.string.chat_add_image),
                        icon = Icons.Default.Image,
                        enabled = !isAttachingImage,
                        onClick = {
                            functionPanelExpanded = false
                            onAddClick()
                        }
                    )
                    if (agentWritable != null) {
                        // 只读/可写：点击切换（取代原「手动/自动执行」开关）
                        FunctionPanelEntry(
                            text = stringResource(
                                if (agentWritable) Res.string.chat_mode_writable
                                else Res.string.chat_mode_readonly
                            ),
                            icon = if (agentWritable) Icons.Default.LockOpen else Icons.Default.Lock,
                            onClick = { onAgentModeChange(!agentWritable) }
                        )
                    }
                    // 空槽位补齐固定列宽（每格 1/4 行宽，入口靠左依次排列）
                    val usedSlots = if (agentWritable != null) 2 else 1
                    repeat(4 - usedSlots) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainer),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧 + 按钮：切换底部功能面板（微信风格）
            IconButton(
                onClick = {
                    if (!functionPanelExpanded) {
                        // 打开面板时收起键盘（微信行为）
                        focusManager.clearFocus()
                    }
                    functionPanelExpanded = !functionPanelExpanded
                },
                enabled = !isGenerating && !isAttachingImage,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(44.dp)
            ) {
                if (isAttachingImage) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(Res.string.chat_add_image),
                        tint = if (functionPanelExpanded) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 胶囊形输入框
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = {
                    Text(
                        text = if (pendingImages.isNotEmpty() && text.text.isBlank()) {
                            stringResource(Res.string.chat_image_description_hint)
                        } else {
                            stringResource(Res.string.chat_message_hint)
                        }
                    )
                },
                shape = MaterialTheme.shapes.large,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        // 点击输入框获得焦点时收起功能面板（微信行为）
                        if (state.isFocused) functionPanelExpanded = false
                    }
                    .then(sendShortcutModifier),
                maxLines = 5,
                minLines = 1,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                enabled = !isGenerating
            )

            Spacer(modifier = Modifier.width(4.dp))

            // 发送 / 停止 按钮：圆形填充，遵循 Google Messages 视觉
            if (isGenerating) {
                FilledIconButton(
                    onClick = onStopClick,
                    modifier = Modifier
                        .clip(CircleShape)
                        .size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = stringResource(Res.string.action_stop),
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
            } else {
                val canSend = text.text.isNotBlank() || pendingImages.isNotEmpty()
                FilledIconButton(
                    onClick = onSendClick,
                    enabled = canSend,
                    modifier = Modifier
                        .clip(CircleShape)
                        .size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(Res.string.action_send),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/** 功能面板的一个入口（微信样式）：圆角方块图标 + 下方小字标签，占行宽的 1/4。 */
@Composable
private fun RowScope.FunctionPanelEntry(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(
        modifier = modifier
            .weight(1f)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun PendingImagesStrip(
    images: List<MessageImage>,
    onRemoveImage: (MessageImage) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
    ) {
        items(images, key = { it.localPath }) { image ->
            PendingImageTile(
                image = image,
                onRemove = { onRemoveImage(image) }
            )
        }
    }
}

@Composable
private fun PendingImageTile(
    image: MessageImage,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(72.dp)
    ) {
        AsyncImage(
            model = image.localPath.toPath(),
            contentDescription = null,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        // The X button is layered on top of the thumbnail using a
        // Box so the strip layout doesn't need to allocate a separate
        // row for the close affordance.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
        ) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(22.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.chat_remove_image),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
