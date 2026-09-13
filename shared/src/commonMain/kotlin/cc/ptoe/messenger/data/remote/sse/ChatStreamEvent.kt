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

package cc.ptoe.messenger.data.remote.sse

import cc.ptoe.messenger.data.remote.dto.UsageDto

sealed class ChatStreamEvent {
    data class Content(val text: String) : ChatStreamEvent()
    data class Done(
        val finishReason: String?,
        /** 流式响应携带的 token 用量（prompt/completion）；服务商不支持时为 null。 */
        val usage: UsageDto? = null
    ) : ChatStreamEvent()
    data class Error(val message: String) : ChatStreamEvent()
    /**
     * 表示流式响应中检测到了 `reasoning_content` 字段，
     * 调用方应将对话标注为 "reasoning_content" 格式，
     * 后续请求中需将 think 标签还原为 `reasoning_content` 字段。
     */
    data object ReasoningDetected : ChatStreamEvent()
}
