/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.mcp

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpSseShutdownTest {
    @Test fun stalledPostDoesNotBlockAnotherToolAndCloseCancelsIt(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val release = CountDownLatch(1)
        val entered = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val outputLock = Any()
        var events: java.io.OutputStream? = null
        val base = "http://127.0.0.1:${server.address.port}"
        fun writeEvent(name: String, data: String) = synchronized(outputLock) {
            val stream = checkNotNull(events)
            stream.write("event: $name\ndata: $data\n\n".toByteArray())
            stream.flush()
        }
        server.createContext("/events") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            try {
                synchronized(outputLock) { events = exchange.responseBody }
                writeEvent("endpoint", "$base/post")
                release.await(10, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.createContext("/post") { exchange ->
            try {
                val request = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                val method = request.getValue("method").jsonPrimitive.content
                val toolName = (request["params"] as? kotlinx.serialization.json.JsonObject)?.get("name")?.jsonPrimitive?.content
                if (method == "tools/call" && toolName == "write") {
                    calls.incrementAndGet()
                    entered.complete(Unit)
                    release.await(10, TimeUnit.SECONDS)
                } else if (method != "notifications/initialized") {
                    val id = request.getValue("id").jsonPrimitive.content
                    val result = when (method) {
                        "initialize" -> """{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"mock","version":"1"}}"""
                        "tools/call" -> """{"content":[{"type":"text","text":"quick result"}],"isError":false}"""
                        else -> """{"tools":[]}"""
                    }
                    writeEvent("message", """{"jsonrpc":"2.0","id":$id,"result":$result}""")
                }
                exchange.sendResponseHeaders(202, -1)
            } finally { exchange.close() }
        }
        server.start()
        val client = McpClient(McpServerConfig(id = "sse", name = "Mock", transportType = McpTransportType.SSE, url = "$base/events"))
        try {
            assertTrue(withTimeout(5_000) { client.connect() })
            val call = async(Dispatchers.IO) { client.callTool("write", "{}") }
            withTimeout(5_000) { entered.await() }
            val quick = async(Dispatchers.IO) { client.callTool("quick", "{}") }
            val quickResult = withTimeoutOrNull(5_000) { quick.await() }
            val closing = async(Dispatchers.IO) { client.close() }
            val stoppedBeforeRelease = withTimeoutOrNull(5_000) { closing.await(); true } ?: false
            release.countDown()
            withTimeout(5_000) { closing.await() }
            assertEquals("quick result", quickResult?.output, "a stalled POST must not block an independent request")
            assertTrue(stoppedBeforeRelease, "close must cancel a stalled HTTP POST")
            assertTrue(withTimeout(5_000) { call.await() }.isError)
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            client.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
