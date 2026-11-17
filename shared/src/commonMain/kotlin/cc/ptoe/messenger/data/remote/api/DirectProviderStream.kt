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

package cc.ptoe.messenger.data.remote.api

import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.dto.ChatCompletionResponseDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.model.ApiFormat
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull

/**
 * Lifecycle and error handling for a direct Kotlin provider request. The caller owns
 * message persistence, tool execution, and presentation of errors. This does not retry.
 *
 */
class DirectProviderStream internal constructor(
    private val completeRequest: suspend (Provider, ChatCompletionRequestDto) -> ChatCompletionResponseDto = { provider, request ->
        OpenAiClient(provider.baseUrl, provider.apiKey, provider.apiFormat.requestFormat, false).createChatCompletion(request)
    },
    private val openStream: (Provider, ChatCompletionRequestDto) -> Flow<ChatStreamEvent>,
) {
    constructor() : this(openStream = { provider, request ->
        OpenAiClient(provider.baseUrl, provider.apiKey, provider.apiFormat.requestFormat, provider.nativeWebSearch)
            .createChatCompletionStream(request)
    })

    fun stream(provider: Provider, request: ChatCompletionRequestDto): Flow<ChatStreamEvent> = flow {
        if (provider.nativeWebSearch && provider.apiFormat.requestFormat !in setOf(ApiFormat.RESPONSES, ApiFormat.MESSAGES)) {
            emit(ChatStreamEvent.Error("Provider native web search is unsupported for ${provider.apiFormat.requestFormat}"))
            return@flow
        }
        var terminal = false
        flow { emitAll(openStream(provider, request.copy(stream = true))) }
            .catch { error ->
                if (error is CancellationException || error !is Exception) throw error
                emit(ChatStreamEvent.Error(providerError(error, provider.apiKey)))
            }
            .collect { event ->
                if (event is ChatStreamEvent.Done || event is ChatStreamEvent.Error) terminal = true
                emit(event)
            }
        if (!terminal) emit(ChatStreamEvent.Error("Provider stream ended without a completion event"))
    }

    /** One non-streaming auxiliary request, with the same visible, redacted errors and no retry. */
    fun complete(provider: Provider, request: ChatCompletionRequestDto): Flow<ChatStreamEvent> = flow {
        try {
            completionEvents(completeRequest(provider.copy(nativeWebSearch = false), request.copy(stream = false))).forEach { emit(it) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { emit(ChatStreamEvent.Error(providerError(error, provider.apiKey))) }
    }

    private suspend fun providerError(error: Throwable, apiKey: String): String {
        if (error is ResponseException) return describeProviderError(error.response, apiKey)
        return sanitizeProviderError(error.message ?: "Provider request failed", apiKey)
    }
}

internal fun completionEvents(response: ChatCompletionResponseDto): List<ChatStreamEvent> {
    val choice = response.choices.firstOrNull() ?: error("Provider returned no completion choice")
    val content = choice.message.content
    val text = when {
        content == JsonNull -> ""
        content is JsonPrimitive && content.isString -> content.content
        else -> error("Provider returned unsupported completion content")
    }
    val calls = choice.message.toolCalls.orEmpty().map { call ->
        ToolCallData(requireNotNull(call.id) { "Tool call has no ID" },
            requireNotNull(call.function?.name) { "Tool call has no name" }, call.function.arguments.orEmpty())
    }
    return buildList {
        if (text.isNotEmpty()) add(ChatStreamEvent.Content(text))
        add(ChatStreamEvent.Done(choice.finishReason, response.usage, calls))
    }
}

internal suspend fun describeProviderError(response: HttpResponse, apiKey: String): String {
    val status = "HTTP ${response.status.value}"
    val bytes = try {
        withTimeoutOrNull(3_000) { response.bodyAsChannel().readRemaining(16_385).readByteArray() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    if (bytes == null) return "$status: error details unavailable"
    if (bytes.size > 16_384) return "$status: error response exceeded size limit"
    val raw = try { bytes.decodeToString(throwOnInvalidSequence = true).trim() }
        catch (_: Exception) { return status }
    val detail = providerErrorDetail(raw, apiKey)
    return if (detail.isEmpty()) status else "$status: $detail"
}

/** Bound provider-controlled details and avoid reflecting credentials into persistent chat history. */
internal fun providerErrorDetail(raw: String, apiKey: String): String {
    if (raw.isBlank()) return ""
    val body = if (raw.first() in "{[\"") {
        // Reject deeply nested input before the recursive JSON parser sees it.
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in raw) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 32) return ""
                '}', ']' -> if (--depth < 0) return ""
            }
        }
        if (depth != 0 || quoted) return ""
        val value = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return ""
        fun strings(obj: JsonObject) = listOf("message", "detail").mapNotNull { key ->
            (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
        when (value) {
            is JsonObject -> {
                val nested = when (val error = value["error"]) {
                    is JsonObject -> strings(error)
                    is JsonPrimitive -> if (error.isString) listOf(error.content) else emptyList()
                    else -> emptyList()
                }
                (strings(value) + nested).filter(String::isNotBlank).distinct().joinToString(" — ")
            }
            is JsonPrimitive -> if (value.isString) value.content else ""
            else -> ""
        }
    } else raw
    return sanitizeProviderError(body, apiKey)
}

internal fun sanitizeProviderError(value: String, apiKey: String): String {
    val redacted = (if (apiKey.isEmpty()) value else value.replace(apiKey, "[redacted]"))
        .replace(Regex("(?i)\\bbearer\\s+[^\\s,;}\\]]+"), "Bearer [redacted]")
        .replace(Regex("(?i)\\b(authorization|(?:x[-_])?api[-_ ]?key|password|secret|(?:access[-_]?|refresh[-_]?)?token|cookie|set-cookie)[\"']?\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;}\\]]+)")) {
            it.groupValues[1] + "=[redacted]"
        }
        .map { if (it.isISOControl() && it != '\n' && it != '\t') ' ' else it }.joinToString("").trim()
    return if (redacted.length > 2048) redacted.take(2048) + "…" else redacted
}
