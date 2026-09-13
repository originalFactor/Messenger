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

    fun parseToEvents(jsonFlow: Flow<String>): Flow<ChatStreamEvent> = flow {
        var inThinkBlock = false
        var reasoningEmitted = false
        // 用量统计块在 [DONE] 之前到达（choices 为空、只有 usage），先暂存，
        // 随终止事件一起抛给调用方做上下文用量记账。
        var lastUsage: UsageDto? = null
        jsonFlow.collect { json ->
            if (json == "[DONE]") {
                if (inThinkBlock) {
                    emit(ChatStreamEvent.Content("</think>\n"))
                    inThinkBlock = false
                }
                emit(ChatStreamEvent.Done(null, lastUsage))
                return@collect
            }
            try {
                val chunk = NetworkClient.json.decodeFromString<ChatCompletionChunkDto>(json)
                if (chunk.usage != null) {
                    lastUsage = chunk.usage
                }
                val choice = chunk.choices.firstOrNull()
                if (choice != null) {
                    val reasoning = choice.delta.reasoningContent
                    if (!reasoning.isNullOrEmpty()) {
                        if (!reasoningEmitted) {
                            emit(ChatStreamEvent.ReasoningDetected)
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
                            if (inThinkBlock) {
                                emit(ChatStreamEvent.Content("</think>\n"))
                                inThinkBlock = false
                            }
                            emit(ChatStreamEvent.Content(contentText))
                        }
                    }
                    val finishReason = choice.finishReason
                    if (finishReason != null) {
                        if (inThinkBlock) {
                            emit(ChatStreamEvent.Content("</think>\n"))
                            inThinkBlock = false
                        }
                        emit(ChatStreamEvent.Done(finishReason, lastUsage))
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
                val reasoning = choice?.delta?.reasoningContent
                if (!reasoning.isNullOrEmpty()) {
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
                        if (inThinkBlock) {
                            emit("</think>\n")
                            inThinkBlock = false
                        }
                        emit(text)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

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
