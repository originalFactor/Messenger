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

package cc.ptoe.messenger.data.local

import cc.ptoe.messenger.domain.model.MessageImage
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path

/**
 * Desktop implementation: persists picked image bytes as-is (desktop
 * sources are local files, already decoded by the OS; no EXIF pass)
 * and builds the data: URI from the same bytes.
 */
class DesktopChatImageStore(private val filesDir: Path) : ChatImageStore {

    private val cacheDir: File
        get() = filesDir.resolve(ChatImageStore.CACHE_SUBDIR).toFile().apply { mkdirs() }

    override suspend fun importImage(bytes: ByteArray, extension: String): MessageImage =
        withContext(Dispatchers.IO) {
            val normalizedExtension = extension.ifBlank { "png" }.lowercase()
            val mime = when (normalizedExtension) {
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/png"
            }
            val targetFile = File(cacheDir, "${UUID.randomUUID()}.$normalizedExtension")
            targetFile.writeBytes(bytes)
            val b64 = Base64.getEncoder().encodeToString(bytes)
            MessageImage(
                dataUri = "data:$mime;base64,$b64",
                localPath = targetFile.absolutePath
            )
        }

    override fun deleteIfExists(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }
}
