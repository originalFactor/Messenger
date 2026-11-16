/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.remote.api

import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.domain.model.Provider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DirectProviderErrorTest {
    @Test fun errorsRetainDetailsWithoutLeakingKeysOrRetrying() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val mode = AtomicReference("quota")
        val requests = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.readBytes()
            val body = when (mode.get()) {
                "quota" -> """{"error":{"message":"Quota exhausted for key-abc","detail":"api_key=other-secret"}}"""
                "large" -> "private-prefix" + "x".repeat(100_000)
                else -> "[".repeat(64) + "0" + "]".repeat(64)
            }.toByteArray()
            try {
                exchange.sendResponseHeaders(429, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } catch (_: java.io.IOException) { exchange.close() }
        }
        server.start()
        try {
            val provider = Provider("p", "provider", "http://127.0.0.1:${server.address.port}/v1", "key-abc", createdAt = 0, updatedAt = 0)
            val request = ChatCompletionRequestDto("model", emptyList())
            suspend fun failure(): String = assertIs<ChatStreamEvent.Error>(
                withTimeout(5_000) { DirectProviderStream().stream(provider, request).toList() }.single()
            ).message
            val quota = failure()
            assertTrue(quota.contains("HTTP 429") && quota.contains("Quota exhausted"))
            assertFalse(quota.contains("key-abc") || quota.contains("other-secret"))
            mode.set("large")
            val large = failure()
            assertTrue(large.contains("HTTP 429") && large.contains("size limit"))
            assertFalse(large.contains("private-prefix"))
            mode.set("deep")
            assertEquals("HTTP 429", failure())
            assertEquals(3, requests.get())
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
