/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.remote.api

import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.domain.model.ApiFormat
import cc.ptoe.messenger.domain.model.Provider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DirectProviderCompletionTest {
    @Test fun auxiliaryCallsUseNonStreamingAcrossAllProtocolsAndDoNotRetryErrors() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val calls = AtomicInteger()
        server.createContext("/v1") { exchange ->
            calls.incrementAndGet()
            val input = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            val nonStreaming = input["stream"]?.jsonPrimitive?.content == "false"
            val (status, response) = when {
                input["model"]?.jsonPrimitive?.content == "unavailable" -> { exchange.responseHeaders.add("Retry-After", "0"); 503 to "unavailable" }
                input["model"]?.jsonPrimitive?.content == "redirect" -> { exchange.responseHeaders.add("Location", "/v1/chat/completions"); 302 to "redirect" }
                !nonStreaming -> 400 to "requires stream=false"
                input["model"]?.jsonPrimitive?.content == "error" -> 429 to """{"error":{"message":"quota key-abc","detail":"api_key=another-secret"}}"""
                exchange.requestURI.path.endsWith("/chat/completions") -> 200 to """{"choices":[{"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}"""
                exchange.requestURI.path.endsWith("/responses") -> 200 to """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"done"}]}]}"""
                exchange.requestURI.path.endsWith("/messages") -> 200 to """{"stop_reason":"end_turn","content":[{"type":"text","text":"done"}]}"""
                else -> 200 to """{"steps":[{"type":"model_output","content":[{"type":"text","text":"done"}]}]}"""
            }
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}/v1"
            for (format in listOf(ApiFormat.CHAT, ApiFormat.RESPONSES, ApiFormat.MESSAGES, ApiFormat.INTERACTIONS)) {
                val provider = Provider("p", "provider", base, "key-abc", apiFormat = format, nativeWebSearch = true, createdAt = 0, updatedAt = 0)
                val events = withTimeout(5000) { DirectProviderStream().complete(provider, ChatCompletionRequestDto("model", emptyList(), stream = true)).toList() }
                assertEquals(ChatStreamEvent.Content("done"), events.first(), format.toString())
                assertIs<ChatStreamEvent.Done>(events.last())
            }
            val provider = Provider("p", "provider", base, "key-abc", createdAt = 0, updatedAt = 0)
            val failure = assertIs<ChatStreamEvent.Error>(withTimeout(5000) { DirectProviderStream().complete(provider, ChatCompletionRequestDto("error", emptyList())).toList() }.single())
            assertTrue(failure.message.contains("HTTP 429") && failure.message.contains("quota"))
            assertFalse(failure.message.contains("key-abc") || failure.message.contains("another-secret"))
            assertIs<ChatStreamEvent.Error>(withTimeout(5000) { DirectProviderStream().complete(provider, ChatCompletionRequestDto("unavailable", emptyList())).toList() }.single())
            assertIs<ChatStreamEvent.Error>(withTimeout(5000) { DirectProviderStream().stream(provider, ChatCompletionRequestDto("unavailable", emptyList())).toList() }.single())
            assertIs<ChatStreamEvent.Error>(withTimeout(5000) { DirectProviderStream().complete(provider, ChatCompletionRequestDto("redirect", emptyList())).toList() }.single())
            assertEquals(8, calls.get(), "503 and redirect must each cause exactly one network request")
        } finally { server.stop(0); executor.shutdownNow() }
    }
}
