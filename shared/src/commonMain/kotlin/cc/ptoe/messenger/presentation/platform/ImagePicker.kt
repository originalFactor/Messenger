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

package cc.ptoe.messenger.presentation.platform

import androidx.compose.runtime.Composable

/** An image the user picked, already read into memory. */
class PickedImage(
    val bytes: ByteArray,
    val extension: String,
    val mimeType: String
) {
    override fun equals(other: Any?): Boolean =
        other is PickedImage && other.extension == extension &&
            other.mimeType == mimeType && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + extension.hashCode()
}

interface FilePickerLauncher {
    fun launch()
}

/**
 * Gallery-style image picking for chat attachments. Android uses the
 * system photo picker; Desktop opens a native file dialog.
 */
@Composable
expect fun rememberImagePicker(onPicked: (PickedImage) -> Unit): FilePickerLauncher

/**
 * Avatar picking with square crop. On Android this chains the system
 * photo picker into uCrop (1:1, max 512px); on Desktop the picked file
 * is copied into the cache dir as-is. [onPicked] receives the path of
 * the resulting (temporary) image file.
 */
@Composable
expect fun rememberAvatarImagePicker(onPicked: (path: String) -> Unit): FilePickerLauncher
