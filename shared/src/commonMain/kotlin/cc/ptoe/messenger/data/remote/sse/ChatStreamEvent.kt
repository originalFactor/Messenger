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
        val usage: UsageDto? = null,
        /**
         * 本轮流式响应累积完成的工具调用（finish_reason 为 "tool_calls" 时非空）。
         * 注意现有解析器在 finish_reason 块和 [DONE] 各发一次 Done，两次都会携带
         * 相同的工具调用列表 — 调用方以收集结束后的最终事件为准，不会重复执行。
         */
        val toolCalls: List<ToolCallData> = emptyList()
    ) : ChatStreamEvent()
    data class Error(val message: String) : ChatStreamEvent()
    /**
     * 表示流式响应中检测到了推理内容，[format] 标注来源字段：
     *  - `"reasoning_content"` — DeepSeek 风格完整思维链，后续请求需将
     *    think 标签还原为 `reasoning_content` 字段；
     *  - `"reasoning_summary"` — GPT 等推理模型的思维链加密不可见，仅经
     *    `reasoning` 字段回传 Reasoning Summary，后续请求直接剥离思考、
     *    不回传任何推理字段。
     */
    data class ReasoningDetected(val format: String) : ChatStreamEvent()
}

/** 一轮流式响应中累积完成的工具调用。 */
data class ToolCallData(
    val callId: String,
    val name: String,
    /** 原始 JSON 参数字符串（尚未解析）。 */
    val arguments: String
)
