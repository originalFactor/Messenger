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

package cc.ptoe.messenger.data.remote.format

import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.dto.ChatMessageDto
import cc.ptoe.messenger.domain.model.ApiFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Encoders for the three non-Chat protocols. [ChatCompletionRequestDto] stays
 * the internal request shape; each encoder re-projects it onto the target
 * wire format. Response/SSE decoding lives in [ApiFormatStreamParser].
 */
object ApiFormatRequests {

    /** Path appended to the provider base URL. */
    fun path(format: ApiFormat): String = when (format) {
        ApiFormat.CHAT, ApiFormat.AUTO -> "/chat/completions"
        ApiFormat.RESPONSES -> "/responses"
        ApiFormat.MESSAGES -> "/messages"
        ApiFormat.INTERACTIONS -> "/interactions"
    }

    /** Extra auth headers this protocol expects besides `Authorization`. */
    fun authHeaders(format: ApiFormat, apiKey: String): Map<String, String> = when (format) {
        ApiFormat.MESSAGES -> mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
        ApiFormat.INTERACTIONS -> mapOf("x-goog-api-key" to apiKey)
        else -> emptyMap()
    }

    fun encode(format: ApiFormat, request: ChatCompletionRequestDto): JsonObject = when (format) {
        ApiFormat.CHAT, ApiFormat.AUTO -> encodeChat(request)
        ApiFormat.RESPONSES -> encodeResponses(request)
        ApiFormat.MESSAGES -> encodeMessages(request)
        ApiFormat.INTERACTIONS -> encodeInteractions(request)
    }

    /**
     * Chat Completions keeps the DTO's own serialization (encodeDefaults =
     * false, so absent fields stay off the wire) and only injects the usage
     * extension when streaming.
     */
    private fun encodeChat(request: ChatCompletionRequestDto): JsonObject {
        val body = NetworkClient.json
            .encodeToJsonElement(ChatCompletionRequestDto.serializer(), request)
            .jsonObject
            .toMutableMap()
        if (request.stream) {
            body["stream_options"] = buildJsonObject { put("include_usage", JsonPrimitive(true)) }
        }
        return JsonObject(body)
    }

    private fun encodeResponses(request: ChatCompletionRequestDto): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(request.model))
        val system = systemText(request.messages)
        if (system.isNotEmpty()) put("instructions", JsonPrimitive(system))
        put("input", buildJsonArray {
            request.messages.forEach { message ->
                if (message.role == "system") return@forEach
                if (message.role == "tool") {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("function_call_output"))
                        put("call_id", JsonPrimitive(message.toolCallId ?: ""))
                        put("output", JsonPrimitive(textOf(message.content)))
                    })
                    return@forEach
                }
                message.toolCalls?.forEach { call ->
                    add(buildJsonObject {
                        put("type", JsonPrimitive("function_call"))
                        put("call_id", JsonPrimitive(call.id ?: ""))
                        put("id", JsonPrimitive(call.id ?: ""))
                        put("name", JsonPrimitive(call.function?.name ?: ""))
                        put("arguments", JsonPrimitive(call.function?.arguments ?: "{}"))
                    })
                }
                val text = textOf(message.content)
                val images = imagesOf(message.content)
                if (text.isEmpty() && images.isEmpty()) return@forEach
                val partType = if (message.role == "assistant") "output_text" else "input_text"
                add(buildJsonObject {
                    put("type", JsonPrimitive("message"))
                    put("role", JsonPrimitive(message.role))
                    put("content", buildJsonArray {
                        if (text.isNotEmpty()) {
                            add(buildJsonObject {
                                put("type", JsonPrimitive(partType))
                                put("text", JsonPrimitive(text))
                            })
                        }
                        images.forEach { url ->
                            add(buildJsonObject {
                                put("type", JsonPrimitive("input_image"))
                                put("image_url", JsonPrimitive(url))
                            })
                        }
                    })
                })
            }
        })
        request.temperature?.let { put("temperature", JsonPrimitive(it)) }
        request.topP?.let { put("top_p", JsonPrimitive(it)) }
        request.maxTokens?.let { put("max_output_tokens", JsonPrimitive(it)) }
        request.reasoningEffort?.takeIf { it != "none" }?.let { effort ->
            put("reasoning", buildJsonObject { put("effort", JsonPrimitive(effort)) })
        }
        request.tools?.let { tools ->
            put("tools", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", JsonPrimitive("function"))
                        put("name", JsonPrimitive(tool.function.name))
                        put("description", JsonPrimitive(tool.function.description))
                        put("parameters", tool.function.parameters)
                    })
                }
            })
        }
        put("stream", JsonPrimitive(request.stream))
    }

    private fun encodeMessages(request: ChatCompletionRequestDto): JsonObject {
        // Anthropic wants tool_result blocks inside a user turn; collect them
        // while walking the history and flush before the next real turn.
        val turns = mutableListOf<JsonElement>()
        val pendingResults = mutableListOf<JsonElement>()
        for (message in request.messages) {
            if (message.role == "system") continue
            if (message.role == "tool") {
                pendingResults.add(buildJsonObject {
                    put("type", JsonPrimitive("tool_result"))
                    put("tool_use_id", JsonPrimitive(message.toolCallId ?: ""))
                    put("content", JsonPrimitive(textOf(message.content)))
                })
                continue
            }
            if (pendingResults.isNotEmpty()) {
                turns.add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonArray(pendingResults.toList()))
                })
                pendingResults.clear()
            }
            val content = buildJsonArray {
                val text = textOf(message.content)
                if (text.isNotEmpty()) {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(text))
                    })
                }
                imagesOf(message.content).forEach { url ->
                    val (mediaType, data) = splitDataUri(url)
                    add(
                        if (data != null) buildJsonObject {
                            put("type", JsonPrimitive("image"))
                            put("source", buildJsonObject {
                                put("type", JsonPrimitive("base64"))
                                put("media_type", JsonPrimitive(mediaType))
                                put("data", JsonPrimitive(data))
                            })
                        } else buildJsonObject {
                            put("type", JsonPrimitive("image"))
                            put("source", buildJsonObject {
                                put("type", JsonPrimitive("url"))
                                put("url", JsonPrimitive(url))
                            })
                        }
                    )
                }
                message.toolCalls?.forEach { call ->
                    add(buildJsonObject {
                        put("type", JsonPrimitive("tool_use"))
                        put("id", JsonPrimitive(call.id ?: ""))
                        put("name", JsonPrimitive(call.function?.name ?: ""))
                        put("input", parseArguments(call.function?.arguments))
                    })
                }
            }
            if (content.isNotEmpty()) {
                turns.add(buildJsonObject {
                    put("role", JsonPrimitive(message.role))
                    put("content", content)
                })
            }
        }
        if (pendingResults.isNotEmpty()) {
            turns.add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonArray(pendingResults.toList()))
            })
        }

        return buildJsonObject {
            put("model", JsonPrimitive(request.model))
            put("max_tokens", JsonPrimitive(request.maxTokens ?: 4096))
            val system = systemText(request.messages)
            if (system.isNotEmpty()) put("system", JsonPrimitive(system))
            put("messages", JsonArray(turns))
            request.temperature?.let { put("temperature", JsonPrimitive(it)) }
            request.topP?.let { put("top_p", JsonPrimitive(it)) }
            request.tools?.let { tools ->
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", JsonPrimitive(tool.function.name))
                            put("description", JsonPrimitive(tool.function.description))
                            put("input_schema", tool.function.parameters)
                        })
                    }
                })
            }
            put("stream", JsonPrimitive(request.stream))
        }
    }

    private fun encodeInteractions(request: ChatCompletionRequestDto): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(request.model))
        val system = systemText(request.messages)
        if (system.isNotEmpty()) put("system_instruction", JsonPrimitive(system))
        put("input", buildJsonArray {
            request.messages.forEach { message ->
                if (message.role == "system") return@forEach
                if (message.role == "tool") {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("function_result"))
                        put("call_id", JsonPrimitive(message.toolCallId ?: ""))
                        put("result", JsonPrimitive(textOf(message.content)))
                    })
                    return@forEach
                }
                val content = buildJsonArray {
                    val text = textOf(message.content)
                    if (text.isNotEmpty()) {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(text))
                        })
                    }
                    imagesOf(message.content).forEach { url ->
                        val (mediaType, data) = splitDataUri(url)
                        add(
                            if (data != null) buildJsonObject {
                                put("type", JsonPrimitive("image"))
                                put("data", JsonPrimitive(data))
                                put("mime_type", JsonPrimitive(mediaType))
                            } else buildJsonObject {
                                put("type", JsonPrimitive("image"))
                                put("uri", JsonPrimitive(url))
                            }
                        )
                    }
                }
                if (content.isNotEmpty()) {
                    add(buildJsonObject {
                        put("type", JsonPrimitive(if (message.role == "assistant") "model_output" else "user_input"))
                        put("content", content)
                    })
                }
                message.toolCalls?.forEach { call ->
                    add(buildJsonObject {
                        put("type", JsonPrimitive("function_call"))
                        put("id", JsonPrimitive(call.id ?: ""))
                        put("name", JsonPrimitive(call.function?.name ?: ""))
                        put("arguments", parseArguments(call.function?.arguments))
                    })
                }
            }
        })
        val generationConfig = buildJsonObject {
            request.maxTokens?.let { put("max_output_tokens", JsonPrimitive(it)) }
            thinkingLevel(request.reasoningEffort)?.let { put("thinking_level", JsonPrimitive(it)) }
        }
        if (generationConfig.isNotEmpty()) put("generation_config", generationConfig)
        request.tools?.let { tools ->
            put("tools", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", JsonPrimitive("function"))
                        put("name", JsonPrimitive(tool.function.name))
                        put("description", JsonPrimitive(tool.function.description))
                        put("parameters", tool.function.parameters)
                    })
                }
            })
        }
        // Stateless: the full history is replayed on every call.
        put("store", JsonPrimitive(false))
        put("stream", JsonPrimitive(request.stream))
    }

    private fun systemText(messages: List<ChatMessageDto>): String = messages
        .filter { it.role == "system" }
        .map { textOf(it.content) }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    private fun textOf(content: JsonElement): String = when (content) {
        is JsonPrimitive -> if (content.isString) content.content else ""
        is JsonArray -> content.mapNotNull { element ->
            (element as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        }.joinToString("")
        else -> ""
    }

    private fun imagesOf(content: JsonElement): List<String> {
        val array = content as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val part = element as? JsonObject ?: return@mapNotNull null
            if (part["type"]?.jsonPrimitive?.contentOrNull != "image_url") return@mapNotNull null
            (part["image_url"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
        }
    }

    /** `data:` URI → (media type, base64 payload); null payload = plain URL. */
    private fun splitDataUri(uri: String): Pair<String, String?> {
        if (!uri.startsWith("data:")) return "application/octet-stream" to null
        val rest = uri.removePrefix("data:")
        val comma = rest.indexOf(',')
        if (comma < 0) return "application/octet-stream" to null
        val meta = rest.substring(0, comma)
        val data = rest.substring(comma + 1)
        val mediaType = meta.substringBefore(';').ifEmpty { "application/octet-stream" }
        return mediaType to data
    }

    private fun parseArguments(arguments: String?): JsonElement = try {
        Json.parseToJsonElement(arguments ?: "{}")
    } catch (_: Exception) {
        buildJsonObject { }
    }

    private fun thinkingLevel(effort: String?): String? = when (effort) {
        null -> null
        "none", "minimal" -> "minimal"
        "low" -> "low"
        "medium" -> "medium"
        "high" -> "high"
        else -> null
    }
}
