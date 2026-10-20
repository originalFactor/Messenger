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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import cc.ptoe.messenger.di.AppContainerHolder
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")

@Composable
actual fun rememberImagePicker(onPicked: (PickedImage) -> Unit): FilePickerLauncher {
    val scope = rememberCoroutineScope()
    return remember(onPicked) {
        object : FilePickerLauncher {
            override fun launch() {
                pickImageFile { file ->
                    scope.launch {
                        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                        val extension = file.extension.ifBlank { "png" }.lowercase()
                        val mime = when (extension) {
                            "jpg", "jpeg" -> "image/jpeg"
                            "webp" -> "image/webp"
                            "gif" -> "image/gif"
                            else -> "image/png"
                        }
                        onPicked(PickedImage(bytes = bytes, extension = extension, mimeType = mime))
                    }
                }
            }
        }
    }
}

@Composable
actual fun rememberAvatarImagePicker(onPicked: (path: String) -> Unit): FilePickerLauncher {
    val scope = rememberCoroutineScope()
    return remember(onPicked) {
        object : FilePickerLauncher {
            override fun launch() {
                pickImageFile { file ->
                    scope.launch {
                        val copied = withContext(Dispatchers.IO) {
                            runCatching {
                                val cacheDir = AppContainerHolder.instance.appDirs.cacheDir.toFile()
                                    .apply { mkdirs() }
                                val extension = file.extension.ifBlank { "jpg" }.lowercase()
                                val target = File(cacheDir, "crop_${UUID.randomUUID()}.$extension")
                                file.copyTo(target, overwrite = true)
                                target.absolutePath
                            }.getOrNull()
                        }
                        copied?.let(onPicked)
                    }
                }
            }
        }
    }
}

/** Shows the native file dialog on a background thread (it blocks until dismissed). */
private fun pickImageFile(onResult: (File) -> Unit) {
    Thread {
        val dialog = FileDialog(null as Frame?, "Choose image", FileDialog.LOAD).apply {
            filenameFilter = FilenameFilter { _, name ->
                name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
            }
            isVisible = true
        }
        val directory = dialog.directory
        val name = dialog.file
        if (directory != null && name != null) {
            onResult(File(directory, name))
        }
    }.start()
}
