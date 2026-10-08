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

package cc.ptoe.messenger.data.local

import cc.ptoe.messenger.domain.model.MessageImage

/**
 * Web image store: there is no filesystem, so both fields carry the same
 * `data:` URI.
 *
 * `localPath` must NOT be an `okio.Path` or `file://` URI on web — Coil's
 * js/wasm component registry resolves those through `defaultFileSystem()`,
 * which is a `ThrowingFileSystem` there — while a `data:` URI is a natively
 * supported model and needs no filesystem at all (`blob:` would need a
 * fetcher the registry does not install).
 */
class WebChatImageStore : ChatImageStore {

    override suspend fun importImage(bytes: ByteArray, extension: String): MessageImage {
        val normalized = extension.ifBlank { "png" }.lowercase()
        val mime = when (normalized) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/png"
        }
        val dataUri = "data:$mime;base64,${encodeBase64(bytes)}"
        return MessageImage(dataUri = dataUri, localPath = dataUri)
    }

    override fun deleteIfExists(path: String?) = Unit
}

/**
 * Minimal base64 encoder: `kotlin.io.encoding.Base64` is still opt-in and the
 * only caller here is this single well-tested path.
 */
private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

internal fun encodeBase64(bytes: ByteArray): String {
    val sb = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i + 2 < bytes.size) {
        val n = ((bytes[i].toInt() and 0xFF) shl 16) or
            ((bytes[i + 1].toInt() and 0xFF) shl 8) or
            (bytes[i + 2].toInt() and 0xFF)
        sb.append(B64_ALPHABET[n ushr 18])
        sb.append(B64_ALPHABET[(n ushr 12) and 0x3F])
        sb.append(B64_ALPHABET[(n ushr 6) and 0x3F])
        sb.append(B64_ALPHABET[n and 0x3F])
        i += 3
    }
    when (bytes.size - i) {
        1 -> {
            val n = (bytes[i].toInt() and 0xFF) shl 16
            sb.append(B64_ALPHABET[n ushr 18])
            sb.append(B64_ALPHABET[(n ushr 12) and 0x3F])
            sb.append("==")
        }
        2 -> {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            sb.append(B64_ALPHABET[n ushr 18])
            sb.append(B64_ALPHABET[(n ushr 12) and 0x3F])
            sb.append(B64_ALPHABET[(n ushr 6) and 0x3F])
            sb.append('=')
        }
    }
    return sb.toString()
}
