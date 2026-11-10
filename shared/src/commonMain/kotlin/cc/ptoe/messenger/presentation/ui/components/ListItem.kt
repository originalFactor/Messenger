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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    showArrow: Boolean = true,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** 卡片背景相对父容器的水平外边距；父容器已有边距（如表单列）时传 0 对齐相邻输入框。 */
    horizontalMargin: Dp = 12.dp,
    groupIndex: Int = LocalSettingsItemPosition.current.index,
    groupCount: Int = LocalSettingsItemPosition.current.count,
    onClick: (() -> Unit)? = null
) {
    val leading: (@Composable () -> Unit)? = icon?.let { image -> {
        Icon(image, null, Modifier.size(24.dp), tint = iconColor)
    } }
    val supporting: (@Composable () -> Unit)? = subtitle?.let { text -> {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = subtitleColor)
    } }
    val trailing: (@Composable () -> Unit)? = if (showArrow) ({
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }) else null
    val itemModifier = modifier.fillMaxWidth().padding(horizontal = horizontalMargin, vertical = if (groupCount > 1) 0.dp else 1.dp)
    SettingsItem(
        modifier = itemModifier, onClick = onClick,
        index = groupIndex, count = groupCount,
        leadingContent = leading, supportingContent = supporting, trailingContent = trailing,
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor) },
    )
}
