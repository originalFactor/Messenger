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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.data.util.fileExists
import coil3.compose.AsyncImage

@Composable
fun AgentAvatar(
    avatar: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    fallbackIcon: ImageVector = Icons.Default.SmartToy,
    allowRemote: Boolean = false
) {
    // A String model, never an okio.Path: on web Coil's js/wasm component
    // registry maps FileSystem-backed values (Path / file:// URIs) onto a
    // ThrowingFileSystem, so any such model would fail to load.
    val avatarSource = remember(avatar) {
        avatar?.takeIf { it.isNotBlank() && !it.startsWith("http://") && !it.startsWith("https://") }
    }
    val isValid = avatarSource != null && fileExists(avatarSource)
    val remoteAvatar = avatar?.takeIf { allowRemote && (it.startsWith("http://") || it.startsWith("https://")) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (isValid || remoteAvatar != null) {
            AsyncImage(
                model = avatarSource ?: remoteAvatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size)
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                modifier = Modifier.size(size * 0.6f),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
