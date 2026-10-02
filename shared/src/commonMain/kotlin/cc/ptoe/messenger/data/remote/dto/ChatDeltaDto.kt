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

@Serializable
data class ChatDeltaDto(
    @SerialName("role") val role: String? = null,
    @SerialName("content") val content: JsonElement? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    /**
     * GPT 等推理模型的 Reasoning Summary（思维链加密不可见，只能回传摘要）。
     * OpenRouter 与 Responses→Chat Completions 网关的标准字段，与
     * [reasoningContent] 互为来源，先到达者优先。
     */
    @SerialName("reasoning") val reasoning: String? = null,
    /** 流式工具调用增量片段：首块携带 id/name，arguments 跨块拼接。 */
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null
)
