/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpGatewayTransportTest {
    private fun backend() = object : AcpGatewayBackend {
        override suspend fun liveTools() = emptyList<AcpGatewayTool>()
        override suspend fun execute(turnId: String, name: String, arguments: JsonObject) = ToolExecutionResult("ok")
    }
    private fun json(id: Int, method: String, params: String = "{}") = """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""
    private fun post(url: String, body: String): Pair<Int, String> {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3000; connection.readTimeout = 5000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val text = (if (status >= 400) connection.errorStream else connection.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return status to text
        } finally { connection.disconnect() }
    }
    private fun raw(host: URI, request: String): String = Socket("127.0.0.1", host.port).use { socket ->
        socket.soTimeout = 5000
        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII)); socket.getOutputStream().flush()
        socket.getInputStream().bufferedReader().readLine()
    }
    private fun openSse(url: String): Pair<Socket, java.io.BufferedReader> {
        val uri = URI(url)
        val socket = Socket("127.0.0.1", uri.port)
        socket.soTimeout = 5000
        socket.getOutputStream().write("GET ${uri.rawPath} HTTP/1.1\r\nHost: 127.0.0.1:${uri.port}\r\n\r\n".toByteArray())
        socket.getOutputStream().flush()
        val reader = socket.getInputStream().bufferedReader()
        assertTrue(reader.readLine().startsWith("HTTP/1.1 200"))
        while (reader.readLine().isNotEmpty()) { }
        assertEquals("event: endpoint", reader.readLine())
        assertEquals("data: messages", reader.readLine())
        assertEquals("", reader.readLine())
        return socket to reader
    }

    @Test fun authenticatesAndSupportsBothHttpAndSse(): Unit = runBlocking {
        val gateway = AcpGatewayCore(backend(), false, emptySet())
        val transport = AcpGatewayTransport.start(gateway)
        val uri = URI(transport.sseUrl)
        try {
            assertEquals("127.0.0.1", uri.host)
            assertFalse(transport.toString().contains(gateway.capability()))
            assertTrue(raw(uri, "GET /wrong/events HTTP/1.1\r\nHost: 127.0.0.1:${uri.port}\r\n\r\n").contains("404"))
            assertTrue(raw(uri, "GET ${uri.rawPath} HTTP/1.1\r\nHost: attacker.test\r\n\r\n").contains("403"))
            assertTrue(raw(uri, "GET ${uri.rawPath} HTTP/1.1\r\nHost: 127.0.0.1:${uri.port}\r\nOrigin: https://attacker.test\r\n\r\n").contains("403"))
            val (status, reply) = withContext(Dispatchers.IO) { post(transport.rpcUrl, json(1, "ping")) }
            assertEquals(200, status); assertTrue(reply.contains("\"id\":1"))
            val (socket, reader) = withContext(Dispatchers.IO) { openSse(transport.sseUrl) }
            try {
                assertTrue(raw(uri, "GET ${uri.rawPath} HTTP/1.1\r\nHost: 127.0.0.1:${uri.port}\r\n\r\n").contains("409"))
                val messages = transport.sseUrl.removeSuffix("events") + "messages"
                assertEquals(202, withContext(Dispatchers.IO) { post(messages, json(2, "ping")) }.first)
                val event = withContext(Dispatchers.IO) { reader.readLine() }
                val data = withContext(Dispatchers.IO) { reader.readLine() }
                assertEquals("event: message", event); assertTrue(data.contains("\"id\":2"))
            } finally { socket.close() }
        } finally { transport.close() }
    }

    @Test fun rejectsAmbiguousAndOversizedHttpRequests(): Unit = runBlocking {
        val gateway = AcpGatewayCore(backend(), false, emptySet())
        val transport = AcpGatewayTransport.start(gateway)
        val uri = URI(transport.rpcUrl)
        try {
            val base = "POST ${uri.rawPath} HTTP/1.1\r\nHost: 127.0.0.1:${uri.port}\r\n"
            assertTrue(raw(uri, base + "Transfer-Encoding: chunked\r\nContent-Length: 1\r\n\r\n").contains("400"))
            assertTrue(raw(uri, base + "Content-Length: 1\r\nContent-Length: 1\r\n\r\n").contains("400"))
            assertTrue(raw(uri, base + "Content-Length: 1048577\r\n\r\n").contains("400"))
        } finally { transport.close() }
    }

    @Test fun sseDisconnectCancelsCallsAndShutdownWaitsForCleanup(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>(); val stopping = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val backend = object : AcpGatewayBackend {
            override suspend fun liveTools() = listOf(AcpGatewayTool("messenger_learning", "learning", JsonObject(emptyMap())))
            override suspend fun execute(turnId: String, name: String, arguments: JsonObject): ToolExecutionResult {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopping.complete(Unit); withContext(NonCancellable) { release.await() } }
            }
        }
        val gateway = AcpGatewayCore(backend, false, setOf("messenger_learning"))
        val transport = AcpGatewayTransport.start(gateway)
        try {
            gateway.beginTurn("turn")
            val (socket, _) = withContext(Dispatchers.IO) { openSse(transport.sseUrl) }
            val messages = transport.sseUrl.removeSuffix("events") + "messages"
            val params = """{"name":"messenger_learning","arguments":{}}"""
            assertEquals(202, withContext(Dispatchers.IO) { post(messages, json(5, "tools/call", params)) }.first)
            withTimeout(5000) { started.await() }
            socket.close()
            withTimeout(5000) { stopping.await() }
            val closing = async { transport.close() }
            delay(100)
            assertFalse(closing.isCompleted)
            release.complete(Unit)
            withTimeout(5000) { closing.await() }
        } finally { release.complete(Unit); transport.close() }
    }
    @Test fun sseLimitsConcurrentCallsWithoutGrowingAnUnboundedQueue(): Unit = runBlocking {
        val started = java.util.concurrent.atomic.AtomicInteger()
        val allStarted = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val backend = object : AcpGatewayBackend {
            override suspend fun liveTools() = listOf(AcpGatewayTool("messenger_learning", "learning", JsonObject(emptyMap())))
            override suspend fun execute(turnId: String, name: String, arguments: JsonObject): ToolExecutionResult {
                if (started.incrementAndGet() == 32) allStarted.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        val gateway = AcpGatewayCore(backend, false, setOf("messenger_learning"))
        val transport = AcpGatewayTransport.start(gateway)
        try {
            gateway.beginTurn("turn")
            val (socket, _) = withContext(Dispatchers.IO) { openSse(transport.sseUrl) }
            try {
                val messages = transport.sseUrl.removeSuffix("events") + "messages"
                val params = """{"name":"messenger_learning","arguments":{}}"""
                for (id in 1..32) assertEquals(202, withContext(Dispatchers.IO) { post(messages, json(id, "tools/call", params)) }.first)
                withTimeout(5000) { allStarted.await() }
                assertEquals(429, withContext(Dispatchers.IO) { post(messages, json(33, "tools/call", params)) }.first)
                val cancel = """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}"""
                assertEquals(202, withContext(Dispatchers.IO) { post(messages, cancel) }.first)
                withTimeout(5000) { cancelled.await() }
                val status = withTimeout(5000) {
                    var value: Int
                    do {
                        value = withContext(Dispatchers.IO) { post(messages, json(33, "tools/call", params)) }.first
                        if (value == 429) delay(20)
                    } while (value == 429)
                    value
                }
                assertEquals(202, status)
            } finally { socket.close() }
        } finally { transport.close() }
    }

    @Test fun httpDisconnectCancelsTheInFlightTool(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>(); val stopping = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val backend = object : AcpGatewayBackend {
            override suspend fun liveTools() = listOf(AcpGatewayTool("messenger_learning", "learning", JsonObject(emptyMap())))
            override suspend fun execute(turnId: String, name: String, arguments: JsonObject): ToolExecutionResult {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopping.complete(Unit); withContext(NonCancellable) { release.await() } }
            }
        }
        val gateway = AcpGatewayCore(backend, false, setOf("messenger_learning"))
        val transport = AcpGatewayTransport.start(gateway)
        try {
            gateway.beginTurn("turn")
            val uri = URI(transport.rpcUrl)
            val socket = Socket("127.0.0.1", uri.port)
            val body = json(1, "tools/call", """{"name":"messenger_learning","arguments":{}}""")
            val bytes = body.toByteArray(Charsets.UTF_8)
            val headers = "POST ${uri.rawPath} HTTP/1.1\r\nHost: 127.0.0.1:${uri.port}\r\nContent-Length: ${bytes.size}\r\n\r\n"
            socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII) + bytes)
            socket.getOutputStream().flush()
            withTimeout(5000) { started.await() }
            socket.close()
            withTimeout(5000) { stopping.await() }
            val closing = async { transport.close() }
            val second = async { transport.close() }
            delay(100)
            assertFalse(closing.isCompleted); assertFalse(second.isCompleted)
            release.complete(Unit)
            withTimeout(5000) { closing.await(); second.await() }
        } finally { release.complete(Unit); transport.close() }
    }

}
