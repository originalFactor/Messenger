/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.mcp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cc.ptoe.messenger.data.local.AppPreferences
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class McpSseDisconnectTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    /** A real HTTP transport whose chunked stream can end cleanly or with a malformed chunk. */
    private class FakeSseServer : AutoCloseable {
        private val server = ServerSocket(0)
        private val streamReady = CountDownLatch(1)
        private val outputLock = Any()
        private lateinit var stream: OutputStream
        val url = "http://127.0.0.1:${server.localPort}/sse"
        private val acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { handle(socket) }
            }
        }

        private fun readLine(input: BufferedInputStream): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                val value = input.read()
                if (value < 0 || value == 10) break
                if (value != 13) bytes.add(value.toByte())
            }
            return bytes.toByteArray().toString(Charsets.UTF_8)
        }

        private fun handle(socket: Socket) {
            try {
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input)
                var length = 0
                var chunked = false
                while (true) {
                    val line = readLine(input)
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    if (line.startsWith("Transfer-Encoding:", ignoreCase = true)) chunked = line.contains("chunked", ignoreCase = true)
                }
                if (requestLine.startsWith("GET ")) {
                    synchronized(outputLock) {
                        stream = socket.getOutputStream()
                        stream.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                        event("event: endpoint\ndata: /messages\n\n")
                    }
                    streamReady.countDown()
                    return // The test owns the open stream.
                }
                val body = ByteArrayOutputStream()
                if (chunked) {
                    while (true) {
                        val size = readLine(input).substringBefore(';').trim().toInt(16)
                        if (size == 0) { readLine(input); break }
                        repeat(size) { body.write(input.read()) }
                        readLine(input)
                    }
                } else {
                    repeat(length) { body.write(input.read()) }
                }
                val request = Json.decodeFromString<JsonRpcRequest>(body.toByteArray().toString(Charsets.UTF_8))
                assertTrue(streamReady.await(5, TimeUnit.SECONDS))
                val result = when (request.method) {
                    "initialize" -> """{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"test","version":"1"}}"""
                    "tools/list" -> """{"tools":[{"name":"work","inputSchema":{"type":"object"}}]}"""
                    else -> null
                }
                if (result != null) synchronized(outputLock) {
                    event("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":${request.id},\"result\":$result}\n\n")
                }
                socket.getOutputStream().write("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                socket.close()
            } catch (_: Exception) { socket.close() }
        }

        private fun event(value: String) {
            val bytes = value.toByteArray()
            stream.write("${bytes.size.toString(16)}\r\n".toByteArray())
            stream.write(bytes)
            stream.write("\r\n".toByteArray())
            stream.flush()
        }

        fun finish(failure: Boolean) = synchronized(outputLock) {
            stream.write(if (failure) "not-a-chunk\r\n".toByteArray() else "0\r\n\r\n".toByteArray())
            stream.flush()
        }

        override fun close() {
            server.close()
            synchronized(outputLock) { if (::stream.isInitialized) stream.close() }
            acceptor.join(1_000)
        }
    }

    @Test fun cleanEofWithdrawsSseInventory() = verifyStreamEnd(failure = false)
    @Test fun malformedStreamWithdrawsSseInventory() = verifyStreamEnd(failure = true)

    private fun verifyStreamEnd(failure: Boolean): Unit = runBlocking {
        val server = FakeSseServer()
        val preferences = AppPreferences(MemoryPreferences())
        val config = McpServerConfig(id = "sse", name = "SSE", transportType = McpTransportType.SSE, url = server.url)
        preferences.setMcpServersJson(Json.encodeToString(ListSerializer(McpServerConfig.serializer()), listOf(config)))
        val manager = McpManager(preferences)
        try {
            withTimeout(5_000) { manager.toolInventoryReady.first { it } }
            assertEquals(listOf("SSE_work"), manager.activeTools.value.map { it.name })
            server.finish(failure)
            withTimeout(5_000) { manager.activeTools.first { it.isEmpty() } }
        } finally { manager.shutdown(); server.close() }
    }

    @Test fun responseCompletedBeforeDisconnectCannotRepublishTools(): Unit = runBlocking {
        val publicationEntered = CompletableDeferred<Unit>()
        val releasePublication = CompletableDeferred<Unit>()
        lateinit var lineCallback: (String) -> Unit
        lateinit var closeCallback: (Int) -> Unit
        val bridge = object : McpProcessBridge {
            override suspend fun startProcess(command: String, env: Map<String, String>, onLine: (String) -> Unit,
                onError: (String) -> Unit, onClose: (Int) -> Unit): Boolean {
                lineCallback = onLine
                closeCallback = onClose
                return true
            }
            override suspend fun sendLine(line: String): Boolean {
                val request = Json.decodeFromString<JsonRpcRequest>(line)
                val result = when (request.method) {
                    "initialize" -> """{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"test","version":"1"}}"""
                    "tools/list" -> """{"tools":[{"name":"work","inputSchema":{"type":"object"}}]}"""
                    else -> null
                }
                if (result != null) lineCallback("""{"jsonrpc":"2.0","id":${request.id},"result":$result}""")
                return true
            }
            override suspend fun close() = Unit
        }
        val config = McpServerConfig(id = "test", name = "Test", transportType = McpTransportType.STDIO, command = "test")
        val client = McpClient(config) { bridge }
        client.beforeToolPublication = { publicationEntered.complete(Unit); releasePublication.await() }
        try {
            val connecting = async { client.connect() }
            withTimeout(5_000) { publicationEntered.await() }
            closeCallback(1)
            releasePublication.complete(Unit)
            assertFalse(withTimeout(5_000) { connecting.await() })
            assertTrue(client.tools.value.isEmpty())
        } finally { releasePublication.complete(Unit); client.close() }
    }

    @Test fun failedHandshakeDoesNotReplaceAnUnclosedTransport(): Unit = runBlocking {
        var starts = 0
        var closes = 0
        lateinit var lineCallback: (String) -> Unit
        val bridge = object : McpProcessBridge {
            override suspend fun startProcess(command: String, env: Map<String, String>, onLine: (String) -> Unit,
                onError: (String) -> Unit, onClose: (Int) -> Unit): Boolean {
                starts++
                lineCallback = onLine
                return true
            }
            override suspend fun sendLine(line: String): Boolean {
                val request = Json.decodeFromString<JsonRpcRequest>(line)
                if (request.method == "initialize") {
                    lineCallback("""{"jsonrpc":"2.0","id":${request.id},"error":{"code":-32000,"message":"handshake failed"}}""")
                }
                return true
            }
            override suspend fun close() { closes++ }
        }
        val config = McpServerConfig(id = "test", name = "Test", transportType = McpTransportType.STDIO, command = "test")
        val client = McpClient(config) { bridge }
        try {
            assertFalse(withTimeout(5_000) { client.connect() })
            assertFalse(withTimeout(5_000) { client.connect() })
            assertEquals(1, starts)
        } finally { client.close() }
        assertEquals(1, closes)
    }
}
