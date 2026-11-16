/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpModelRelayHostTest {
    private fun request(url: String, token: String?, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap()): Pair<Int, ByteArray> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5000; connection.readTimeout = 5000
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        headers.forEach(connection::setRequestProperty)
        if (body != null) {
            val bytes = body.encodeToByteArray()
            connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
        }
        return try {
            val status = connection.responseCode
            status to (if (status >= 400) connection.errorStream else connection.inputStream).use { it?.readBytes() ?: byteArrayOf() }
        } finally { connection.disconnect() }
    }
    @Test fun hostAuthenticatesAndStreamsWithoutForwardingAgentCredentials(): Unit = runBlocking {
        var upstreamAuthorization: String? = null
        var upstreamCookie: String? = null
        var upstreamVersion: String? = null
        val bytes = "event: custom\ndata:{\"unknown\":true}\ndata: 2\n\n".encodeToByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            upstreamAuthorization = exchange.requestHeaders.getFirst("Authorization")
            upstreamCookie = exchange.requestHeaders.getFirst("Cookie")
            upstreamVersion = exchange.requestHeaders.getFirst("anthropic-version")
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val model = AcpRelayModel("model", "Name", "provider", 1, true, "actual", "http://127.0.0.1:${server.address.port}/v1", "provider-secret", AcpRelayFormat.MESSAGES)
        val relay = AcpModelRelay(AcpRelayCatalog { if (it == "model") model else null }, setOf("model"))
        relay.beginTurn("turn")
        val host = AcpModelRelayHost.start(relay)
        try {
            val token = relay.capability()
            assertEquals(401, withContext(Dispatchers.IO) { request("${host.baseUrl}/models", null).first })
            val catalog = withContext(Dispatchers.IO) { request("${host.baseUrl}/models", token) }
            assertEquals(200, catalog.first)
            assertTrue("messenger/model" in catalog.second.decodeToString())
            assertFalse("provider-secret" in catalog.second.decodeToString())
            assertEquals(200, withContext(Dispatchers.IO) { request("${host.baseUrl}/models", null, headers = mapOf("x-api-key" to token)).first })
            val port = URL(host.baseUrl).port
            val badOrigin = withContext(Dispatchers.IO) {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 5000
                    socket.getOutputStream().write("GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nOrigin: https://evil.example\r\nAuthorization: Bearer $token\r\n\r\n".toByteArray())
                    socket.getInputStream().bufferedReader().readLine()
                }
            }
            assertTrue(badOrigin.contains("403"))
            val payload = buildJsonObject { put("model", "messenger/model"); put("stream", true); put("messages", JsonArray(emptyList())) }.toString()
            val result = withContext(Dispatchers.IO) { request("${host.baseUrl}/messages", token, "POST", payload, mapOf("Cookie" to "private=value", "anthropic-version" to "2024-01-01")) }
            assertEquals(200, result.first)
            assertContentEquals(bytes, result.second)
            assertEquals("Bearer provider-secret", upstreamAuthorization)
            assertEquals(null, upstreamCookie)
            assertEquals("2024-01-01", upstreamVersion)
            val crossStream = buildJsonObject { put("model", "messenger/model"); put("input", "hi"); put("max_output_tokens", 100); put("stream", true); put("include", JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content")))) }.toString()
            val unsupported = withContext(Dispatchers.IO) { request("${host.baseUrl}/responses", token, "POST", crossStream) }
            assertEquals(422, unsupported.first)
            assertTrue("unsupported_feature" in unsupported.second.decodeToString())
            val excessive = "{\"model\":\"messenger/model\",\"messages\":[" + "0,".repeat(200_001) + "0]}"
            val limited = withContext(Dispatchers.IO) { request("${host.baseUrl}/messages", token, "POST", excessive) }
            assertEquals(413, limited.first)
            val badHost = withContext(Dispatchers.IO) {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 5000
                    socket.getOutputStream().write("GET /v1/models HTTP/1.1\r\nHost: evil.example:$port\r\nAuthorization: Bearer $token\r\n\r\n".toByteArray())
                    socket.getInputStream().bufferedReader().readLine()
                }
            }
            assertTrue(badHost.contains("403"))
        } finally { host.close(); server.stop(0) }
    }

    @Test fun disconnectCancelsTheUpstreamStream(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                exchange.requestBody.readBytes(); exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write("start".encodeToByteArray()); exchange.responseBody.flush(); entered.countDown()
                while (true) { Thread.sleep(10); exchange.responseBody.write(ByteArray(8192)); exchange.responseBody.flush() }
            } catch (_: Exception) { disconnected.countDown() } finally { exchange.close() }
        }
        server.start()
        val model = AcpRelayModel("model", "Name", "provider", 1, true, "actual", "http://127.0.0.1:${server.address.port}", "key", AcpRelayFormat.CHAT)
        val relay = AcpModelRelay(AcpRelayCatalog { model }, setOf("model"))
        relay.beginTurn("turn")
        val host = AcpModelRelayHost.start(relay)
        try {
            val port = URL(host.baseUrl).port
            val body = "{\"model\":\"messenger/model\",\"stream\":true}"
            val socket = Socket("127.0.0.1", port)
            withContext(Dispatchers.IO) {
                socket.getOutputStream().write(("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer ${relay.capability()}\r\nContent-Length: ${body.encodeToByteArray().size}\r\n\r\n$body").toByteArray())
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                socket.close()
                assertTrue(disconnected.await(5, TimeUnit.SECONDS))
            }
        } finally { host.close(); server.stop(0) }
    }
}
