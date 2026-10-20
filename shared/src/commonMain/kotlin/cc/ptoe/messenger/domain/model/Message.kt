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

package cc.ptoe.messenger.domain.model

data class Message(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    /**
     * Plain-text projection of [parts]. For backwards compatibility with
     * last-message previews, conversation titles, and search, this is the
     * concatenation of all [ContentPart.Text] segments. Multimodal
     * messages also populate this so existing UI keeps working; image
     * parts are NOT inlined as text here.
     */
    val content: String,
    /**
     * Full multimodal payload. Single-text messages are stored as a
     * one-element list with a [ContentPart.Text] so send and render
     * paths can be unified.
     */
    val parts: List<ContentPart> = emptyList(),
    val timestamp: Long,
    val status: MessageStatus,
    val errorMessage: String? = null
) {
    /** True if the message contains at least one image part. */
    val hasImages: Boolean
        get() = parts.any { it is ContentPart.Image }
}

enum class MessageStatus {
    SENDING,
    SENT,
    ERROR
}
