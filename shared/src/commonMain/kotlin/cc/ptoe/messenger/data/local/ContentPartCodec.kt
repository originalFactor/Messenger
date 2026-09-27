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

import cc.ptoe.messenger.domain.model.ContentPart
import cc.ptoe.messenger.domain.model.MessageImage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * (De)serialization helpers for the `parts` column on [MessageEntity].
 * Centralized here so the wire format and the [ContentPart] model stay
 * in lockstep — every read/write path uses the same JSON shape and the
 * same Json instance.
 *
 * The encoded form is a JSON array of discriminated objects:
 *
 *   `[{"type":"text","text":"..."},{"type":"image","dataUri":"...","localPath":"..."}]`
 *
 * We intentionally use our own `type` strings ("text" / "image") rather
 * than the OpenAI ones ("text" / "image_url") because the local model
 * carries the bitmap blob, not a URL. The translation to OpenAI's wire
 * shape happens in [cc.ptoe.messenger.data.repository.ApiRepositoryImpl].
 */
object ContentPartCodec {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun encode(parts: List<ContentPart>): String? {
        if (parts.isEmpty()) return null
        val stored = parts.map { it.toStored() }
        return json.encodeToString(stored)
    }

    fun decode(jsonString: String?): List<ContentPart> {
        if (jsonString.isNullOrBlank()) return emptyList()
        return try {
            val parsed: List<StoredContentPart> = json.decodeFromString(jsonString)
            parsed.mapNotNull { it.toDomain() }
        } catch (_: Exception) {
            // A malformed entry (e.g. a payload from a future build) must
            // never crash the message list — we just degrade to empty
            // parts and let the text projection stand on its own.
            emptyList()
        }
    }

    private fun ContentPart.toStored(): StoredContentPart = when (this) {
        is ContentPart.Text -> StoredContentPart(type = "text", text = text)
        is ContentPart.Image -> StoredContentPart(
            type = "image",
            dataUri = image.dataUri,
            localPath = image.localPath
        )
        is ContentPart.ToolCall -> StoredContentPart(
            type = "tool_call",
            callId = callId,
            name = name,
            arguments = arguments
        )
        is ContentPart.ToolResult -> StoredContentPart(
            type = "tool_result",
            callId = callId,
            name = name,
            output = output,
            isError = isError
        )
    }

    private fun StoredContentPart.toDomain(): ContentPart? = when (type) {
        "text" -> text?.let { ContentPart.Text(it) }
        "image" -> {
            if (dataUri.isNullOrEmpty() || localPath.isNullOrEmpty()) null
            else ContentPart.Image(MessageImage(dataUri = dataUri, localPath = localPath))
        }
        "tool_call" -> {
            if (callId.isNullOrEmpty() || name.isNullOrEmpty()) null
            else ContentPart.ToolCall(callId = callId, name = name, arguments = arguments ?: "")
        }
        "tool_result" -> {
            if (callId.isNullOrEmpty() || name.isNullOrEmpty()) null
            else ContentPart.ToolResult(callId = callId, name = name, output = output ?: "", isError = isError)
        }
        else -> null
    }

    /**
     * Mirror of [ContentPart] used purely for (de)serialization.
     * Kept private so the domain model stays free of serialization
     * annotations.
     */
    @Serializable
    private data class StoredContentPart(
        val type: String,
        val text: String? = null,
        val dataUri: String? = null,
        val localPath: String? = null,
        val callId: String? = null,
        val name: String? = null,
        val arguments: String? = null,
        val output: String? = null,
        val isError: Boolean = false
    )
}
