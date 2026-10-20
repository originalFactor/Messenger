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

package cc.ptoe.messenger.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI-style multimodal content part. Only [type] = "text" and "image_url"
 * are produced by the client today; other variants are kept in the JSON
 * untouched so the server can pass them through.
 */
@Serializable
data class ContentPartDto(
    @SerialName("type") val type: String,
    @SerialName("text") val text: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrlDto? = null
)

@Serializable
data class ImageUrlDto(
    /**
     * Either an https:// URL or a data: URI. The wire format is
     * deliberately permissive; the client only ever emits data: URIs
     * because selected bitmaps are base64-encoded at send time.
     */
    @SerialName("url") val url: String,
    /**
     * Optional detail hint (`low` / `high`). Default is server-side
     * (typically `auto`); we omit the field to maximize provider
     * compatibility since some compatible APIs reject an explicit
     * `auto` value.
     */
    @SerialName("detail") val detail: String? = null
)
