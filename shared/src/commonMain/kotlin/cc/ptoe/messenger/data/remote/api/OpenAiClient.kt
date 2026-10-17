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

package cc.ptoe.messenger.data.remote.api

import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.dto.ChatCompletionResponseDto
import cc.ptoe.messenger.data.remote.dto.ModelsResponseDto
import cc.ptoe.messenger.data.remote.format.ApiFormatRequests
import cc.ptoe.messenger.data.remote.format.ApiFormatStreamParser
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.sse.SSEParser
import cc.ptoe.messenger.domain.model.ApiFormat
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject

import cc.ptoe.messenger.data.util.ioDispatcher

/**
 * Provider client backed by Ktor. [format] selects the request path, extra
 * auth headers, and the response/SSE decoding; callers only see the
 * format-agnostic [ChatStreamEvent] flow.
 *
 * Non-2xx responses surface as [io.ktor.client.plugins.ResponseException]
 * (see `expectSuccess` in [NetworkClient]); callers can read the error body
 * from it.
 */
class OpenAiClient(
    baseUrl: String,
    apiKey: String,
    val format: ApiFormat = ApiFormat.CHAT,
) {

    private val client = NetworkClient.clientFor(baseUrl, apiKey)
    private val root = baseUrl.trimEnd('/')
    private val apiKey = apiKey

    suspend fun getModels(): ModelsResponseDto =
        client.get("$root/models") {
            ApiFormatRequests.authHeaders(format, apiKey).forEach { (name, value) ->
                header(name, value)
            }
        }.body()

    suspend fun createChatCompletion(request: ChatCompletionRequestDto): ChatCompletionResponseDto {
        val payload = client.post("$root${ApiFormatRequests.path(format)}") {
            contentType(ContentType.Application.Json)
            ApiFormatRequests.authHeaders(format, apiKey).forEach { (name, value) ->
                header(name, value)
            }
            setBody(ApiFormatRequests.encode(format, request))
        }.body<JsonObject>()
        return if (format == ApiFormat.CHAT) {
            NetworkClient.json.decodeFromJsonElement(ChatCompletionResponseDto.serializer(), payload)
        } else {
            ApiFormatStreamParser.decodeCompletion(format, payload)
        }
    }

    /**
     * Streams one turn and emits the protocol-agnostic events. Chat
     * Completions keeps its dedicated parser (it is the behavioral
     * reference); the other formats decode through
     * [ApiFormatStreamParser].
     */
    fun createChatCompletionStream(request: ChatCompletionRequestDto): Flow<ChatStreamEvent> = flow {
        val sse = streamSse(request)
        val events = if (format == ApiFormat.CHAT) {
            cc.ptoe.messenger.data.remote.sse.ChatStreamParser.parseToEvents(sse)
        } else {
            ApiFormatStreamParser.parseToEvents(format, sse)
        }
        events.collect { emit(it) }
    }.flowOn(ioDispatcher)

    /** Raw SSE `data:` payloads (including the terminal `[DONE]`). */
    fun streamSse(request: ChatCompletionRequestDto): Flow<String> = flow {
        client.preparePost("$root${ApiFormatRequests.path(format)}") {
            contentType(ContentType.Application.Json)
            ApiFormatRequests.authHeaders(format, apiKey).forEach { (name, value) ->
                header(name, value)
            }
            setBody(ApiFormatRequests.encode(format, request))
            timeout {
                // Reasoning models can think for minutes before the first
                // token arrives — disable the overall request timeout.
                requestTimeoutMillis = Long.MAX_VALUE
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                // expectSuccess throws before we get here, but guard anyway.
                throw IllegalStateException("HTTP ${response.status.value}")
            }
            SSEParser.parse(response.bodyAsChannel()).collect { emit(it) }
        }
    }.flowOn(ioDispatcher)
}
