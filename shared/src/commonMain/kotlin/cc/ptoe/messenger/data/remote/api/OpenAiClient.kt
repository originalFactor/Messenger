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
import cc.ptoe.messenger.data.remote.sse.SSEParser
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.preparePost
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

import cc.ptoe.messenger.data.util.ioDispatcher

/**
 * OpenAI-compatible API client backed by Ktor (replaces the Retrofit
 * interface). Non-2xx responses surface as
 * [io.ktor.client.plugins.ResponseException] (see `expectSuccess` in
 * [NetworkClient]); callers can read the error body from it.
 */
class OpenAiClient(baseUrl: String, apiKey: String) {

    private val client = NetworkClient.clientFor(baseUrl, apiKey)
    private val root = baseUrl.trimEnd('/')

    suspend fun getModels(): ModelsResponseDto =
        client.get("$root/models").body()

    suspend fun createChatCompletion(request: ChatCompletionRequestDto): ChatCompletionResponseDto =
        client.post("$root/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    /**
     * Streams `chat/completions` and emits each raw SSE `data:` payload
     * (including the terminal `[DONE]` sentinel) via [SSEParser].
     */
    fun createChatCompletionStream(request: ChatCompletionRequestDto): Flow<String> = flow {
        client.preparePost("$root/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(request)
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
