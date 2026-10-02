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

import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.data.remote.dto.ChatCompletionChunkDto
import cc.ptoe.messenger.data.remote.dto.ChatDeltaDto
import cc.ptoe.messenger.data.remote.dto.UsageDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object ChatStreamParser {

    /** 流式工具调用片段的累积器：id/name 随首块到达，arguments 跨块拼接。 */
    private class ToolCallAccumulator {
        var callId: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    fun parseToEvents(jsonFlow: Flow<String>): Flow<ChatStreamEvent> = flow {
        var inThinkBlock = false
        var reasoningEmitted = false
        // 用量统计块在 [DONE] 之前到达（choices 为空、只有 usage），先暂存，
        // 随终止事件一起抛给调用方做上下文用量记账。
        var lastUsage: UsageDto? = null
        val toolCallAccumulators = linkedMapOf<Int, ToolCallAccumulator>()
        jsonFlow.collect { json ->
            if (json == "[DONE]") {
                if (inThinkBlock) {
                    emit(ChatStreamEvent.Content("</think>\n"))
                    inThinkBlock = false
                }
                emit(ChatStreamEvent.Done(null, lastUsage, accumulatedToolCalls(toolCallAccumulators)))
                return@collect
            }
            try {
                val chunk = NetworkClient.json.decodeFromString<ChatCompletionChunkDto>(json)
                if (chunk.usage != null) {
                    lastUsage = chunk.usage
                }
                val choice = chunk.choices.firstOrNull()
                if (choice != null) {
                    val reasoning = choice.delta.incomingReasoning
                    if (reasoning != null) {
                        if (!reasoningEmitted) {
                            emit(
                                ChatStreamEvent.ReasoningDetected(
                                    format = if (choice.delta.reasoningContent.isNullOrEmpty()) {
                                        "reasoning_summary"
                                    } else {
                                        "reasoning_content"
                                    }
                                )
                            )
                            reasoningEmitted = true
                        }
                        if (!inThinkBlock) {
                            emit(ChatStreamEvent.Content("<think>"))
                            inThinkBlock = true
                        }
                        emit(ChatStreamEvent.Content(reasoning))
                    }
                    val content = choice.delta.content
                    if (content != null) {
                        val contentText = extractContentText(content)
                        if (contentText.isNotEmpty()) {
                            // 部分上游在多段思考之间用纯空白的 content 增量（如 "\n\n"）做分隔。
                            // 若据此关闭思考块，紧接着恢复的 reasoning 会重新开一个 <think>，
                            // 导致本应连续的思考被拆成多个思考块 — 纯空白增量直接丢弃。
                            if (!(contentText.isBlank() && inThinkBlock)) {
                                if (inThinkBlock) {
                                    emit(ChatStreamEvent.Content("</think>\n"))
                                    inThinkBlock = false
                                }
                                emit(ChatStreamEvent.Content(contentText))
                            }
                        }
                    }
                    choice.delta.toolCalls?.forEach { fragment ->
                        val index = fragment.index ?: 0
                        val acc = toolCallAccumulators.getOrPut(index) { ToolCallAccumulator() }
                        if (fragment.id != null) {
                            acc.callId = fragment.id
                        }
                        fragment.function?.name?.takeIf { it.isNotEmpty() }?.let { acc.name = it }
                        fragment.function?.arguments?.let { acc.arguments.append(it) }
                    }
                    val finishReason = choice.finishReason
                    if (finishReason != null) {
                        if (inThinkBlock) {
                            emit(ChatStreamEvent.Content("</think>\n"))
                            inThinkBlock = false
                        }
                        emit(ChatStreamEvent.Done(finishReason, lastUsage, accumulatedToolCalls(toolCallAccumulators)))
                    }
                }
            } catch (e: IllegalArgumentException) {
                // Malformed chunk (unexpected shape) — skip silently like
                // the old Gson JsonSyntaxException path.
            } catch (e: Exception) {
                emit(ChatStreamEvent.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun parseToText(jsonFlow: Flow<String>): Flow<String> = flow {
        var inThinkBlock = false
        jsonFlow.collect { json ->
            if (json == "[DONE]") return@collect
            try {
                val chunk = NetworkClient.json.decodeFromString<ChatCompletionChunkDto>(json)
                val choice = chunk.choices.firstOrNull()
                val reasoning = choice?.delta?.incomingReasoning
                if (reasoning != null) {
                    if (!inThinkBlock) {
                        emit("<think>")
                        inThinkBlock = true
                    }
                    emit(reasoning)
                }
                val content = choice?.delta?.content
                if (content != null) {
                    val text = extractContentText(content)
                    if (text.isNotEmpty()) {
                        // 与 parseToEvents 相同：思考期间到达的纯空白 content 增量直接丢弃，
                        // 避免多段思考被拆成多个 <think> 块。
                        if (!(text.isBlank() && inThinkBlock)) {
                            if (inThinkBlock) {
                                emit("</think>\n")
                                inThinkBlock = false
                            }
                            emit(text)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 把按 index 累积的工具调用片段汇总为完整调用列表。name 从未到达的碎片视为
     * 残缺数据直接丢弃；个别服务商不发 id 时回退为按 index 合成的稳定 ID，
     * 保证 tool_call_id 往返成立。
     */
    private fun accumulatedToolCalls(
        accumulators: LinkedHashMap<Int, ToolCallAccumulator>
    ): List<ToolCallData> {
        if (accumulators.isEmpty()) return emptyList()
        return accumulators.entries.sortedBy { it.key }.mapNotNull { (index, acc) ->
            val name = acc.name ?: return@mapNotNull null
            ToolCallData(
                callId = acc.callId ?: "call_$index",
                name = name,
                arguments = acc.arguments.toString()
            )
        }
    }

    /**
     * 本块携带的推理增量：GPT 等推理模型的思维链加密不可见，仅经 `reasoning`
     * 字段回传 Reasoning Summary，DeepSeek 等经 `reasoning_content` 回传完整
     * 思维链。二者统一包进 `<think>` 块显示；同块同时携带时
     * `reasoning_content` 优先。
     */
    private val ChatDeltaDto.incomingReasoning: String?
        get() = reasoningContent?.takeIf { it.isNotEmpty() }
            ?: reasoning?.takeIf { it.isNotEmpty() }

    private fun extractContentText(content: JsonElement): String {
        return when (content) {
            is JsonPrimitive -> if (content.isString) content.content else ""
            is JsonArray -> content.extractText()
            else -> ""
        }
    }

    private fun JsonArray.extractText(): String {
        val builder = StringBuilder()
        for (element in this) {
            if (element is JsonObject) {
                when (element["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> builder.append(element["text"]?.jsonPrimitive?.contentOrNull ?: "")
                    "image_url" -> {
                    }
                }
            }
        }
        return builder.toString()
    }
}
