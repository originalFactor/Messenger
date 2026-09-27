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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Chat completion message. The [content] field is rendered as either a
 * plain string (text-only messages) or an array of [ContentPartDto]
 * (multimodal messages). The shape follows the OpenAI Chat Completions
 * spec; providers that don't accept arrays still get a string because
 * [toRequestContent] picks the appropriate representation at request
 * build time.
 */
@Serializable
data class ChatMessageDto(
    @SerialName("role") val role: String = "",
    /**
     * Raw JSON element so the same DTO can hold either a string or an
     * array. We don't deserialize it into a sealed class because the
     * server echoes it back unchanged and we want to stay forward
     * compatible with future part types.
     */
    @SerialName("content") val content: JsonElement = JsonPrimitive(""),
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    /** 出站：assistant 历史回显中的工具调用；入站：非流式响应的完整工具调用。 */
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    /** 出站：role=tool 消息对应的调用 ID。 */
    @SerialName("tool_call_id") val toolCallId: String? = null
)
