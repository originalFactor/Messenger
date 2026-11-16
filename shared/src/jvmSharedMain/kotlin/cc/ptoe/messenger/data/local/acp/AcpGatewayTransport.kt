/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.io.BufferedInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Private per-worker MCP HTTP/SSE transport. No credentials or URLs are logged or persisted. */
internal class AcpGatewayTransport private constructor(private val gateway: AcpGatewayCore, private val server: ServerSocket) {
    private class Session(parent: Job, val socket: Socket) {
        val job = SupervisorJob(parent)
        val scope = CoroutineScope(job + Dispatchers.IO)
        val events = Channel<String>(32)
        val calls = Semaphore(32)
    }
    private val stopped = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.IO)
    private val permits = Semaphore(40)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val stream = AtomicReference<Session?>()
    private val secret = gateway.capability()
    private val base = "http://127.0.0.1:${server.localPort}/$secret"
    val sseUrl: String get() = "$base/events"
    val rpcUrl: String get() = "$base/rpc"
    override fun toString() = "AcpGatewayTransport(capability=redacted)"

    private val accepting = scope.launch {
        while (isActive && !stopped.get()) {
            val socket = try { server.accept() } catch (error: SocketException) {
                if (stopped.get()) break else throw error
            }
            if (!socket.inetAddress.isLoopbackAddress || !permits.tryAcquire()) { socket.close(); continue }
            if (!globalSockets.tryAcquire()) { permits.release(); socket.close(); continue }
            sockets.add(socket)
            val connection = scope.launch(start = CoroutineStart.LAZY) {
                try { serve(socket) } catch (_: CancellationException) { throw CancellationException() }
                catch (_: Exception) { /* Malformed or disconnected clients do not affect the worker. */ }
            }
            connection.invokeOnCompletion { sockets.remove(socket); runCatching { socket.close() }; permits.release(); globalSockets.release() }
            connection.start()
        }
    }

    private suspend fun serve(socket: Socket) {
        socket.soTimeout = 1_000
        val input = BufferedInputStream(socket.getInputStream())
        val request = try { readHeaders(input) } catch (_: Exception) { respond(socket, 400); return }
        val components = request.path.split('/')
        if (components.size != 3 || components[0].isNotEmpty() ||
            !MessageDigest.isEqual(secret.toByteArray(Charsets.US_ASCII), components[1].toByteArray(Charsets.US_ASCII))) {
            respond(socket, 404); return
        }
        val host = request.headers["host"]
        if (host != "127.0.0.1:${server.localPort}" || request.headers["origin"]?.let { it != "http://$host" } == true) {
            respond(socket, 403); return
        }
        when {
            request.method == "GET" && components[2] == "events" -> {
                if (request.length != 0) { respond(socket, 400); return }
                events(socket, input)
            }
            request.method == "POST" && components[2] in setOf("rpc", "messages") -> {
                if (!request.hasLength || request.length !in 1..MAX_BODY) { respond(socket, 400); return }
                val bytes = ByteArray(request.length)
                try { readFully(input, bytes, System.nanoTime() + 15_000_000_000L) } catch (_: Exception) { respond(socket, 400); return }
                val document = try {
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    Json.parseToJsonElement(decoder.decode(ByteBuffer.wrap(bytes)).toString()) as? JsonObject ?: error("Invalid JSON")
                } catch (_: Exception) { respond(socket, 400); return }
                if (components[2] == "messages") postMessage(socket, document)
                else rpc(socket, input, document)
            }
            else -> respond(socket, 404)
        }
    }

    private suspend fun events(socket: Socket, input: BufferedInputStream) {
        val session = Session(owner, socket)
        if (!stream.compareAndSet(null, session)) { respond(socket, 409); session.job.cancel(); return }
        try {
            writeBounded(socket, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\nevent: endpoint\ndata: messages\n\n")
            socket.soTimeout = 1_000
            coroutineScope {
                val disconnected = launch(Dispatchers.IO) {
                    while (isActive) {
                        try {
                            // A persistent SSE request has no more request body or pipelined requests.
                            input.read()
                            break
                        } catch (_: SocketTimeoutException) { }
                    }
                    session.job.cancel()
                    session.events.close()
                }
                try {
                    while (session.job.isActive && isActive) {
                        val event = withTimeoutOrNull(10_000) { session.events.receiveCatching().getOrNull() }
                        if (!session.job.isActive) break
                        writeBounded(socket, event ?: ": keepalive\n\n")
                    }
                } finally {
                    session.job.cancel()
                    session.events.close()
                    runCatching { socket.shutdownInput() }
                    disconnected.cancelAndJoin()
                }
            }
        } finally {
            session.events.close()
            withContext(NonCancellable) { session.job.cancelAndJoin() }
            stream.compareAndSet(session, null)
        }
    }

    private suspend fun postMessage(socket: Socket, document: JsonObject) {
        val session = stream.get()?.takeIf { it.job.isActive } ?: run { respond(socket, 409); return }
        // Cancellation must remain available while the 32 tool-call slots are full.
        if ((document["method"] as? JsonPrimitive)?.contentOrNull == "notifications/cancelled") {
            gateway.request(secret, document)
            respond(socket, 202)
            return
        }
        if (!session.calls.tryAcquire()) { respond(socket, 429); return }
        if (!globalMessageCalls.tryAcquire()) { session.calls.release(); respond(socket, 429); return }
        val call = session.scope.launch(start = CoroutineStart.LAZY) {
            try {
                val response = gateway.request(secret, document)
                val serialized = response?.toString()
                require(serialized == null || serialized.toByteArray(Charsets.UTF_8).size <= MAX_BODY) { "Gateway response exceeds limit" }
                if (serialized != null && !session.events.trySend("event: message\ndata: $serialized\n\n").isSuccess) {
                    session.job.cancel()
                    runCatching { session.socket.close() }
                }
            } catch (_: CancellationException) { throw CancellationException() }
            catch (_: Exception) {
                session.job.cancel()
                runCatching { session.socket.close() }
            }
        }
        call.invokeOnCompletion { session.calls.release(); globalMessageCalls.release() }
        call.start()
        respond(socket, 202)
    }

    private suspend fun rpc(socket: Socket, input: BufferedInputStream, document: JsonObject) = supervisorScope {
        socket.soTimeout = 1_000
        val call = async { gateway.request(secret, document) }
        val disconnected = launch(Dispatchers.IO) {
            while (isActive) {
                try { input.read(); break } catch (_: SocketTimeoutException) { }
            }
            call.cancel()
        }
        try {
            val response = call.await()
            if (response == null) respond(socket, 202)
            else {
                val serialized = response.toString()
                require(serialized.toByteArray(Charsets.UTF_8).size <= MAX_BODY) { "Gateway response exceeds limit" }
                respond(socket, 200, serialized)
            }
        } catch (_: CancellationException) {
            // Client disconnect cancels the operation; cancellation itself is never retried.
        } catch (_: Exception) {
            respond(socket, 400)
        } finally {
            call.cancel()
            runCatching { socket.shutdownInput() }
            disconnected.cancelAndJoin()
        }
    }

    private suspend fun writeBounded(socket: Socket, data: String) = writeBytesBounded(socket, data.toByteArray(Charsets.UTF_8))

    private suspend fun writeBytesBounded(socket: Socket, bytes: ByteArray) {
        val timeout = writeWatchdog.schedule({ runCatching { socket.close() } }, 10, java.util.concurrent.TimeUnit.SECONDS)
        try {
            withContext(Dispatchers.IO) {
                val output = socket.getOutputStream()
                output.write(bytes)
                output.flush()
            }
        } finally { timeout.cancel(false) }
    }

    private suspend fun respond(socket: Socket, status: Int, text: String = "") = writeBytesBounded(socket, responseBytes(status, text))

    /** Waits for in-flight calls to stop, even if their cancellation is not immediate. */
    suspend fun close() = withContext(NonCancellable) {
        if (stopped.compareAndSet(false, true)) {
            try {
                runCatching { server.close() }
                sockets.forEach { runCatching { it.close() } }
                stream.get()?.job?.cancel()
                owner.cancelAndJoin()
                gateway.close()
                closed.complete(Unit)
            } catch (failure: Throwable) {
                closed.completeExceptionally(failure)
                throw failure
            }
        } else closed.await()
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val length: Int, val hasLength: Boolean)

    companion object {
        private val globalSockets = Semaphore(128)
        private val globalMessageCalls = Semaphore(128)
        private val writeWatchdog = java.util.concurrent.ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "messenger-acp-gateway-timeouts").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private const val MAX_BODY = 1024 * 1024
        private const val MAX_HEADER = 16 * 1024
        private const val MAX_LINE = 8192
        private const val MAX_HEADERS = 64

        fun start(gateway: AcpGatewayCore): AcpGatewayTransport {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 40)
                return AcpGatewayTransport(gateway, socket)
            } catch (failure: Throwable) { runCatching { socket.close() }; throw failure }
        }
        private fun readHeaders(input: BufferedInputStream): Request {
            var bytes = 0
            val deadline = System.nanoTime() + 15_000_000_000L
            fun line(): String {
                val data = java.io.ByteArrayOutputStream()
                while (true) {
                    val byte = readByte(input, deadline)
                    if (byte < 0) throw EOFException()
                    if (++bytes > MAX_HEADER || data.size() >= MAX_LINE) error("HTTP headers exceed limit")
                    if (byte == '\n'.code) {
                        val raw = data.toByteArray()
                        require(raw.isNotEmpty() && raw.last() == '\r'.code.toByte())
                        return raw.copyOf(raw.size - 1).toString(Charsets.US_ASCII)
                    }
                    require(byte == '\r'.code || byte in 32..126) { "Invalid HTTP header" }
                    data.write(byte)
                }
            }
            val parts = line().split(' ')
            require(parts.size == 3 && parts[2] == "HTTP/1.1" && parts[0] in setOf("GET", "POST") && parts[1].startsWith('/'))
            val headers = mutableMapOf<String, String>()
            var count = 0
            while (true) {
                val next = line()
                if (next.isEmpty()) break
                require(++count <= MAX_HEADERS && !next.startsWith(' ') && !next.startsWith('\t'))
                val colon = next.indexOf(':')
                require(colon > 0)
                val name = next.substring(0, colon).lowercase(java.util.Locale.ROOT)
                require(name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' })
                require(headers.put(name, next.substring(colon + 1).trim()) == null) { "Duplicate HTTP header" }
            }
            require("transfer-encoding" !in headers && "expect" !in headers)
            val rawLength = headers["content-length"]
            val length = if (rawLength == null) 0 else {
                require(rawLength.isNotEmpty() && rawLength.all { it in '0'..'9' })
                rawLength.toLongOrNull()?.takeIf { it in 0..MAX_BODY.toLong() }?.toInt() ?: error("Invalid content length")
            }
            return Request(parts[0], parts[1], headers, length, rawLength != null)
        }
        private fun readByte(input: BufferedInputStream, deadline: Long): Int {
            while (true) {
                check(System.nanoTime() < deadline) { "HTTP request timed out" }
                try { return input.read() } catch (_: SocketTimeoutException) { }
            }
        }
        private fun readFully(input: BufferedInputStream, bytes: ByteArray, deadline: Long) {
            var offset = 0
            while (offset < bytes.size) {
                check(System.nanoTime() < deadline) { "HTTP request timed out" }
                val count = try { input.read(bytes, offset, bytes.size - offset) } catch (_: SocketTimeoutException) { continue }
                if (count <= 0) throw EOFException()
                offset += count
            }
        }
        private fun responseBytes(status: Int, text: String): ByteArray {
            val phrase = when (status) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 429 -> "Too Many Requests"; else -> "Error" }
            val bytes = text.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 $status $phrase\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n".toByteArray(Charsets.US_ASCII)
            return header + bytes
        }
    }
}
