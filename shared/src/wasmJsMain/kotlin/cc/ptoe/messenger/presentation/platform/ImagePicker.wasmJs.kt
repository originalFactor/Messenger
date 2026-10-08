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

package cc.ptoe.messenger.presentation.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.files.FileReader

/**
 * Browser file picking through a hidden `<input type="file">` element.
 *
 * The file is read as a `data:` URL — the one representation the browser and
 * Coil both handle without a filesystem — and the base64 payload is decoded
 * back into bytes for the chat attachment path.
 * [rememberAvatarImagePicker] hands the data URI straight to `onPicked`: there
 * is no uCrop in a browser, and a data URI is already a model the rest of the
 * pipeline (and Coil) renders.
 */
@Composable
actual fun rememberImagePicker(onPicked: (PickedImage) -> Unit): FilePickerLauncher {
    val element = rememberFileInput { name, mime, dataUri ->
        val extension = name.substringAfterLast('.', "").lowercase().ifBlank { "png" }
        val type = mime.ifBlank {
            when (extension) {
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/png"
            }
        }
        onPicked(PickedImage(bytes = decodeDataUrl(dataUri), extension = extension, mimeType = type))
    }
    return remember(element) {
        object : FilePickerLauncher {
            override fun launch() = element.click()
        }
    }
}

@Composable
actual fun rememberAvatarImagePicker(onPicked: (path: String) -> Unit): FilePickerLauncher {
    val element = rememberFileInput { _, _, dataUri -> onPicked(dataUri) }
    return remember(element) {
        object : FilePickerLauncher {
            override fun launch() = element.click()
        }
    }
}

/**
 * Creates the hidden input once per [remember] key and detaches it (with its
 * listener) on dispose, so recompositions do not accumulate DOM nodes.
 */
@Composable
private fun rememberFileInput(
    onFile: (name: String, mime: String, dataUri: String) -> Unit
): HTMLInputElement {
    val latest = rememberUpdatedState(onFile)
    val element = remember {
        (document.createElement("input") as HTMLInputElement).apply {
            type = "file"
            accept = "image/*"
            setAttribute("hidden", "true")
            document.body?.appendChild(this)
        }
    }
    DisposableEffect(element) {
        val listener: (Event) -> Unit = {
            val file = element.files?.item(0)
            if (file != null) {
                val reader = FileReader()
                reader.onload = {
                    val result = reader.result
                    if (result is String) {
                        latest.value(file.name, file.type, result)
                    }
                    // Reset so re-picking the same file fires `change` again.
                    element.value = ""
                }
                reader.readAsDataURL(file)
            }
        }
        element.addEventListener("change", listener)
        onDispose {
            element.removeEventListener("change", listener)
            element.parentNode?.removeChild(element)
        }
    }
    return element
}

/** Decodes the base64 payload of a `data:` URL. */
internal fun decodeDataUrl(dataUri: String): ByteArray = decodeBase64(dataUri.substringAfter(',', ""))

internal fun decodeBase64(text: String): ByteArray {
    val clean = text.filterNot { it.isWhitespace() }
    val output = ByteArray(clean.length / 4 * 3 + 3)
    var buffer = 0
    var bits = 0
    var index = 0
    for (char in clean) {
        if (char == '=') break
        val value = BASE64_ALPHABET.indexOf(char)
        if (value < 0) continue
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            output[index++] = ((buffer shr bits) and 0xFF).toByte()
        }
    }
    return output.copyOf(index)
}

internal fun encodeBase64(bytes: ByteArray): String {
    val sb = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i + 2 < bytes.size) {
        val n = ((bytes[i].toInt() and 0xFF) shl 16) or
            ((bytes[i + 1].toInt() and 0xFF) shl 8) or
            (bytes[i + 2].toInt() and 0xFF)
        sb.append(BASE64_ALPHABET[n ushr 18])
        sb.append(BASE64_ALPHABET[(n ushr 12) and 0x3F])
        sb.append(BASE64_ALPHABET[(n ushr 6) and 0x3F])
        sb.append(BASE64_ALPHABET[n and 0x3F])
        i += 3
    }
    when (bytes.size - i) {
        1 -> {
            val n = (bytes[i].toInt() and 0xFF) shl 16
            sb.append(BASE64_ALPHABET[n ushr 18])
            sb.append(BASE64_ALPHABET[(n ushr 12) and 0x3F])
            sb.append("==")
        }
        2 -> {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            sb.append(BASE64_ALPHABET[n ushr 18])
            sb.append(BASE64_ALPHABET[(n ushr 12) and 0x3F])
            sb.append(BASE64_ALPHABET[(n ushr 6) and 0x3F])
            sb.append('=')
        }
    }
    return sb.toString()
}

private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
