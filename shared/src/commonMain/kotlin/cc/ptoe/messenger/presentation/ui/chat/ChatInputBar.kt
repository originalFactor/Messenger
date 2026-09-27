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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.domain.model.MessageImage
import cc.ptoe.messenger.presentation.platform.sendOnEnterShortcut
import coil3.compose.AsyncImage
import okio.Path.Companion.toPath
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_send
import cc.ptoe.messenger.generated.resources.action_stop
import cc.ptoe.messenger.generated.resources.chat_add_image
import cc.ptoe.messenger.generated.resources.chat_image_description_hint
import cc.ptoe.messenger.generated.resources.chat_message_hint
import cc.ptoe.messenger.generated.resources.chat_remove_image
import cc.ptoe.messenger.generated.resources.tool_mode_auto
import cc.ptoe.messenger.generated.resources.tool_mode_manual
import org.jetbrains.compose.resources.stringResource

/**
 * Bottom-of-screen input area. Owns three concerns:
 *
 *  - A horizontal preview strip for images the user picked but hasn't
 *    sent yet. Each tile has an X to drop that single image.
 *  - The `+` button that opens the photo picker (handled by
 *    [onAddClick]; the screen wires it to a `rememberLauncherForActivityResult`).
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
     * 工具执行确认模式；null 表示当前会话不涉及工具（不展示切换按钮）。
     * false=手动确认（默认），true=自动执行。
     */
    toolAutoConfirm: Boolean? = null,
    onToolModeToggle: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }

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
            .background(MaterialTheme.colorScheme.surface)
    ) {
        if (pendingImages.isNotEmpty()) {
            PendingImagesStrip(
                images = pendingImages,
                onRemoveImage = onRemoveImage
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧 + 按钮（Google Messages 风格：附件入口）
            IconButton(
                onClick = onAddClick,
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 工具执行模式切换（手动确认 / 自动执行），仅当 Agent 开启工具时展示
            if (toolAutoConfirm != null) {
                IconButton(
                    onClick = onToolModeToggle,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(44.dp)
                ) {
                    Icon(
                        imageVector = if (toolAutoConfirm) Icons.Default.Bolt else Icons.Default.TouchApp,
                        contentDescription = stringResource(
                            if (toolAutoConfirm) Res.string.tool_mode_auto
                            else Res.string.tool_mode_manual
                        ),
                        tint = if (toolAutoConfirm) MaterialTheme.colorScheme.primary
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
                shape = RoundedCornerShape(24.dp),
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
