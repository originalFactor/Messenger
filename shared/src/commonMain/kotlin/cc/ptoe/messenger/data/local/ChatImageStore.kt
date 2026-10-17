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
 * Reads user-picked images into the chat app and writes them to private
 * storage so the rest of the pipeline (Coil, the API, cloud sync) can
 * reference a stable path/URI.
 *
 * Two outputs are produced for every picked image:
 *
 *  - a base64 data: URI used to ship the bitmap to OpenAI-compatible
 *    chat APIs as a vision input
 *  - a cached copy inside `filesDir/chat_images/` that Coil can
 *    decode without re-reading the source
 *
 * Cached copies are reused on subsequent app launches because the
 * [MessageImage.localPath] is persisted in the message row.
 *
 * Platform implementations: Android downscales + EXIF-rotates via the
 * Bitmap pipeline; Desktop stores bytes as-is.
 */
interface ChatImageStore {

    /**
     * Persists [bytes] (a picked image of type [extension], e.g. "jpg")
     * into the chat-images cache and returns a ready-to-attach
     * [MessageImage].
     */
    suspend fun importImage(bytes: ByteArray, extension: String): MessageImage

    /**
     * Drops cached bitmaps for message ids that no longer appear in
     * the conversation. Called by
     * [cc.ptoe.messenger.data.repository.RustMessageRepository] after
     * a delete; the chat screen doesn't need to invoke it directly.
     */
    fun deleteIfExists(path: String?)

    companion object {
        const val CACHE_SUBDIR = "chat_images"
        /**
         * Cap sent bitmaps at 1568px on the longest side. This matches
         * the OpenAI "high" detail suggestion and keeps the base64
         * payload under ~3 MB for typical JPEGs, well inside the
         * request body limits of every common OpenAI-compatible
         * provider.
         */
        const val MAX_DIMENSION_PX = 1568
    }
}
