/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpModelRelayErrorsTest {
    private class Sink : AcpRelaySink {
        var status = 0
        var type: String? = null
        val body = java.io.ByteArrayOutputStream()
        override suspend fun start(status: Int, contentType: String?) { this.status = status; type = contentType }
        override suspend fun write(chunk: ByteArray) { body.write(chunk) }
        fun error() = Json.parseToJsonElement(body.toString()).jsonObject["error"]!!.jsonObject
    }
    @Test fun preservesStatusesAndSafeDiagnosticsWithoutRetryOrHeaderLeaks(): Unit = runBlocking {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            val body = exchange.requestBody.readBytes().decodeToString()
            val scenario = if ("crossfail" in body) "quota" else Json.parseToJsonElement(body).jsonObject["scenario"]!!.jsonPrimitive.content
            val (status, contentType, text) = when (scenario) {
                "quota" -> Triple(429, "application/json", """{"error":{"code":"insufficient_quota","message":"Provider balance is exhausted","request":{"api_key":"provider-secret","prompt":"private"}}}""")
                "context" -> Triple(400, "application/json", """{"error":{"code":"context_length_exceeded","message":"Requested 150000 tokens, maximum 128000"}}""")
                "credential" -> Triple(401, "application/json", """{"error":{"code":"invalid_api_key","message":"Invalid API key provider-secret","api_key":"provider-secret"}}""")
                "oversized" -> Triple(502, "text/plain", "provider-secret" + "x".repeat(70_000))
                "plain" -> Triple(503, "text/plain", "Provider service is temporarily unavailable")
                else -> Triple(200, "application/json; x-provider-key=provider-secret", "{}")
            }
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.responseHeaders.add("Set-Cookie", "secret=hidden")
            exchange.responseHeaders.add("WWW-Authenticate", "Bearer provider-secret")
            exchange.responseHeaders.add("Retry-After", "0")
            val bytes = text.encodeToByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val model = AcpRelayModel("m", "Model", "provider", 1, true, "actual", "http://127.0.0.1:${server.address.port}", "provider-secret", AcpRelayFormat.CHAT)
        val relay = AcpModelRelay(AcpRelayCatalog { model }, setOf("m"))
        try {
            relay.beginTurn("turn")
            suspend fun send(scenario: String): Sink = Sink().also { sink ->
                relay.forward(relay.capability(), AcpRelayFormat.CHAT, buildJsonObject { put("model", "messenger/m"); put("scenario", scenario) }, null, null, sink)
            }
            val context = send("context")
            assertEquals(400, context.status)
            assertEquals("context_length_exceeded", context.error()["code"]!!.jsonPrimitive.content)
            assertEquals("Provider returned HTTP 400: Request exceeds the provider context limit", context.error()["message"]!!.jsonPrimitive.content)
            val credentials = send("credential")
            assertEquals(401, credentials.status)
            assertEquals("invalid_api_key", credentials.error()["code"]!!.jsonPrimitive.content)
            assertFalse(credentials.body.toString().contains("provider-secret"))
            val oversized = send("oversized")
            assertEquals(502, oversized.status)
            assertEquals("Provider returned HTTP 502", oversized.error()["message"]!!.jsonPrimitive.content)
            val plain = send("plain")
            assertEquals(503, plain.status)
            assertEquals("Provider returned HTTP 503", plain.error()["message"]!!.jsonPrimitive.content)
            val success = send("success")
            assertEquals(200, success.status)
            assertEquals("application/json", success.type)
            assertFalse(success.type!!.contains("provider-secret"))
            val converted = Sink()
            val request = buildJsonObject { put("model", "messenger/m"); put("input", "crossfail"); put("stream", true) }
            relay.forward(relay.capability(), AcpRelayFormat.RESPONSES, request, null, null, converted)
            assertEquals(429, converted.status)
            assertEquals("insufficient_quota", converted.error()["code"]!!.jsonPrimitive.content)
            assertEquals("Provider returned HTTP 429: Provider quota is exhausted", converted.error()["message"]!!.jsonPrimitive.content)
            assertFalse(converted.body.toString().contains("private"))
            assertFalse(converted.body.toString().contains("provider-secret"))
            assertEquals(6, calls.get())
            relay.endTurn("turn")
        } finally { relay.close(); server.stop(0) }
    }
    @Test fun incompleteSlowErrorFallsBackWithoutLosingHttpStatus(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                exchange.requestBody.readBytes()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(429, 0)
                exchange.responseBody.write("{\"error\":{\"message\":\"partial provider-secret".encodeToByteArray())
                exchange.responseBody.flush(); Thread.sleep(500)
            } catch (_: Exception) { } finally { exchange.close() }
        }
        server.start()
        val model = AcpRelayModel("m", "Model", "provider", 1, true, "actual", "http://127.0.0.1:${server.address.port}", "provider-secret", AcpRelayFormat.CHAT)
        val relay = AcpModelRelay(AcpRelayCatalog { model }, setOf("m"), errorTimeoutMillis = 100)
        try {
            relay.beginTurn("turn")
            val sink = Sink()
            relay.forward(relay.capability(), AcpRelayFormat.CHAT, buildJsonObject { put("model", "messenger/m") }, null, null, sink)
            assertEquals(429, sink.status)
            assertEquals("Provider returned HTTP 429", sink.error()["message"]!!.jsonPrimitive.content)
            assertFalse(sink.body.toString().contains("partial"))
            relay.endTurn("turn")
        } finally { relay.close(); server.stop(0) }
    }
}
