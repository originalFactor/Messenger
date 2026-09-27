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

package cc.ptoe.messenger.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Thinking parameter for DeepSeek API compatibility.
 * When type is "disabled", the model will not output reasoning_content.
 */
@Serializable
data class ThinkingDto(
    @SerialName("type") val type: String
)

@Serializable
data class ChatCompletionRequestDto(
    @SerialName("model") val model: String,
    @SerialName("messages") val messages: List<ChatMessageDto>,
    @SerialName("temperature") val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("thinking") val thinking: ThinkingDto? = null,
    @SerialName("tools") val tools: List<ToolSpecDto>? = null,
    @SerialName("stream") val stream: Boolean = false
)
