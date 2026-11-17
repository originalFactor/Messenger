/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.*

/** One loopback host per turn; credentials are never included in URLs or logs. */
internal class AcpModelRelayHost private constructor(private val relay: AcpModelRelay, private val server: ServerSocket) {
    private val stopped = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.IO)
    private val permits = Semaphore(16)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
    override fun toString() = "AcpModelRelayHost(capability=redacted)"

    private val accepting = scope.launch {
        while (isActive && !stopped.get()) {
            val socket = try { server.accept() } catch (error: SocketException) { if (stopped.get()) break else throw error }
            if (!socket.inetAddress.isLoopbackAddress || !permits.tryAcquire()) { socket.close(); continue }
            if (!globalPermits.tryAcquire()) { permits.release(); socket.close(); continue }
            sockets.add(socket)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try { serve(socket) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A disconnected or malformed client is isolated to this request. */ }
            }
            job.invokeOnCompletion { sockets.remove(socket); runCatching { socket.close() }; permits.release(); globalPermits.release() }
            job.start()
        }
    }

    private suspend fun serve(socket: Socket) {
        socket.soTimeout = 1_000
        val input = BufferedInputStream(socket.getInputStream())
        val request = try { readHeaders(input) } catch (_: Exception) { respond(socket, 400, "bad_request", "Invalid request"); return }
        val host = "127.0.0.1:${server.localPort}"
        if (request.headers["host"] != host || request.headers["origin"]?.let { it != "http://$host" } == true) {
            respond(socket, 403, "forbidden", "Invalid request origin"); return
        }
        val candidates = listOfNotNull(request.headers["authorization"]?.removePrefix("Bearer ")?.takeIf { request.headers["authorization"]!!.startsWith("Bearer ") }, request.headers["x-api-key"])
        val secret = relay.capability().toByteArray(Charsets.US_ASCII)
        val token = candidates.firstOrNull { it.length <= 256 && MessageDigest.isEqual(secret, it.toByteArray(Charsets.US_ASCII)) }
            ?: run { respond(socket, 401, "unauthorized", "Invalid relay token"); return }
        if (request.method == "GET" && request.path == "/v1/models") {
            if (request.length != 0) { respond(socket, 400, "bad_request", "Invalid request"); return }
            respondBytes(socket, 200, relay.models(token).toString().encodeToByteArray()); return
        }
        val format = when (request.path) {
            "/v1/chat/completions" -> AcpRelayFormat.CHAT
            "/v1/responses" -> AcpRelayFormat.RESPONSES
            "/v1/messages" -> AcpRelayFormat.MESSAGES
            "/v1/interactions" -> AcpRelayFormat.INTERACTIONS
            else -> { respond(socket, 404, "not_found", "Unknown relay path"); return }
        }
        if (request.method != "POST" || !request.hasLength || request.length !in 1..MAX_BODY) {
            respond(socket, 400, "bad_request", "Invalid request"); return
        }
        val reservation = request.length.toLong() * 8L
        if (!reserve(reservation)) { respond(socket, 429, "busy", "Relay request capacity exceeded"); return }
        try {
        val bytes = ByteArray(request.length)
        try { readFully(input, bytes, System.nanoTime() + 60_000_000_000L) } catch (_: Exception) { respond(socket, 400, "bad_request", "Invalid request"); return }
        if (!jsonBudget(bytes)) { respond(socket, 413, "request_too_large", "JSON structure exceeds relay limits"); return }
        val body = try {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            AcpRelayStrictJson.parse(decoder.decode(ByteBuffer.wrap(bytes)).toString()) as? JsonObject ?: error("Invalid JSON")
        } catch (_: Exception) { respond(socket, 400, "bad_request", "Invalid JSON"); return }
        val sink = SocketSink(socket)
        supervisorScope {
            val call = async(start = CoroutineStart.LAZY) { relay.forward(token, format, body, request.headers["anthropic-version"], request.headers["anthropic-beta"], sink) }
            val disconnected = launch(Dispatchers.IO) {
                while (isActive) {
                    try { input.read(); break } catch (_: SocketTimeoutException) { }
                }
                call.cancel()
            }
            try {
                call.start(); call.await(); sink.finish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: AcpRelayException) {
                if (!sink.started) respond(socket, status(failure.code), failure.code, failure.message ?: "Relay failed")
            } catch (_: Exception) {
                if (!sink.started) respond(socket, 500, "relay_error", "Relay request failed")
            } finally {
                call.cancel()
                runCatching { socket.shutdownInput() }
                disconnected.cancelAndJoin()
            }
        }
        } finally { bytesInUse.addAndGet(-reservation) }
    }

    private class SocketSink(private val socket: Socket) : AcpRelaySink {
        var started = false
            private set
        override suspend fun start(status: Int, contentType: String?) {
            check(!started && status in 100..599)
            started = true
            val content = contentType ?: "application/octet-stream"
            write(socket, "HTTP/1.1 $status ${phrase(status)}\r\nContent-Type: $content\r\nTransfer-Encoding: chunked\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n".toByteArray(Charsets.US_ASCII))
        }
        override suspend fun write(chunk: ByteArray) {
            check(started)
            if (chunk.isEmpty()) return
            val prefix = "${chunk.size.toString(16)}\r\n".toByteArray(Charsets.US_ASCII)
            write(socket, prefix + chunk + "\r\n".toByteArray(Charsets.US_ASCII))
        }
        suspend fun finish() { check(started); write(socket, "0\r\n\r\n".toByteArray(Charsets.US_ASCII)) }
    }

    suspend fun close() = withContext(NonCancellable) {
        if (stopped.compareAndSet(false, true)) {
            try {
                runCatching { server.close() }
                sockets.forEach { runCatching { it.close() } }
                owner.cancelAndJoin()
                relay.close()
                closed.complete(Unit)
            } catch (failure: Throwable) { closed.completeExceptionally(failure); throw failure }
        } else closed.await()
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val length: Int, val hasLength: Boolean)
    companion object {
        private const val MAX_BODY = 24 * 1024 * 1024
        private const val MAX_HEADER = 16 * 1024
        private const val MAX_LINE = 8192
        private const val MAX_HEADERS = 64
        private val globalPermits = Semaphore(128)
        private val bytesInUse = AtomicLong(0)
        private const val GLOBAL_BODY_BUDGET = 192L * 1024 * 1024
        private val watchdog = ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, "messenger-acp-relay-writes").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }

        fun start(relay: AcpModelRelay): AcpModelRelayHost {
            val server = ServerSocket()
            try {
                server.reuseAddress = false
                server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
                return AcpModelRelayHost(relay, server)
            } catch (failure: Throwable) { runCatching { server.close() }; throw failure }
        }
        private suspend fun write(socket: Socket, bytes: ByteArray) {
            val timeout = watchdog.schedule({ runCatching { socket.close() } }, 10, TimeUnit.SECONDS)
            try { withContext(Dispatchers.IO) { socket.getOutputStream().write(bytes); socket.getOutputStream().flush() } }
            finally { timeout.cancel(false) }
        }
        private suspend fun respond(socket: Socket, status: Int, code: String, message: String) =
            respondBytes(socket, status, buildJsonObject { put("error", buildJsonObject { put("code", code); put("message", message) }) }.toString().encodeToByteArray())
        private suspend fun respondBytes(socket: Socket, status: Int, bytes: ByteArray) {
            val header = "HTTP/1.1 $status ${phrase(status)}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n".toByteArray(Charsets.US_ASCII)
            write(socket, header + bytes)
        }
        private fun status(code: String) = when (code) {
            "unauthorized" -> 401; "invalid_model" -> 400; "model_not_found" -> 404; "inactive_turn" -> 409
            "busy" -> 429; "unsupported_format", "unsupported_feature" -> 422; "request_too_large" -> 413
            "upstream_error", "invalid_upstream" -> 502; else -> 500
        }
        private fun phrase(status: Int) = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Content Too Large"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"; 500 -> "Internal Server Error"; 502 -> "Bad Gateway"; else -> "Response" }
        private fun readHeaders(input: BufferedInputStream): Request {
            var bytes = 0
            val deadline = System.nanoTime() + 15_000_000_000L
            fun line(): String {
                val data = ByteArrayOutputStream()
                while (true) {
                    val byte = readByte(input, deadline)
                    if (byte < 0) throw EOFException()
                    if (++bytes > MAX_HEADER || data.size() >= MAX_LINE) error("Headers exceed limit")
                    if (byte == '\n'.code) {
                        val raw = data.toByteArray()
                        require(raw.isNotEmpty() && raw.last() == '\r'.code.toByte() && raw.dropLast(1).none { it == '\r'.code.toByte() })
                        return raw.copyOf(raw.size - 1).toString(Charsets.US_ASCII)
                    }
                    require(byte == '\r'.code || byte in 32..126)
                    data.write(byte)
                }
            }
            val parts = line().split(' ')
            require(parts.size == 3 && parts[2] == "HTTP/1.1" && parts[0] in setOf("GET", "POST") && parts[1].startsWith('/'))
            val headers = mutableMapOf<String, String>()
            var count = 0
            while (true) {
                val next = line(); if (next.isEmpty()) break
                require(++count <= MAX_HEADERS && !next.startsWith(' ') && !next.startsWith('\t'))
                val colon = next.indexOf(':'); require(colon > 0)
                val name = next.substring(0, colon).lowercase(Locale.ROOT)
                require(name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' })
                require(headers.put(name, next.substring(colon + 1).trim()) == null)
            }
            require("transfer-encoding" !in headers && "expect" !in headers)
            val raw = headers["content-length"]
            val length = if (raw == null) 0 else {
                require(raw.isNotEmpty() && raw.all { it in '0'..'9' })
                raw.toLongOrNull()?.takeIf { it in 0..MAX_BODY.toLong() }?.toInt() ?: error("Invalid content length")
            }
            return Request(parts[0], parts[1], headers, length, raw != null)
        }
        private fun reserve(amount: Long): Boolean {
            while (true) {
                val current = bytesInUse.get()
                if (amount > GLOBAL_BODY_BUDGET - current) return false
                if (bytesInUse.compareAndSet(current, current + amount)) return true
            }
        }
        /** Limits parser object count and nesting before materializing the JSON tree. */
        private fun jsonBudget(bytes: ByteArray): Boolean {
            var inString = false
            var escaped = false
            var depth = 0
            var nodes = 0
            for (byte in bytes) {
                val value = byte.toInt() and 255
                if (inString) {
                    if (escaped) escaped = false
                    else if (value == 92) escaped = true
                    else if (value == 34) inString = false
                } else when (value) {
                    34 -> inString = true
                    91, 123 -> { depth++; nodes++ }
                    93, 125 -> depth--
                    44, 58 -> nodes++
                }
                if (depth !in 0..128 || nodes > 200_000) return false
            }
            return depth == 0 && !inString
        }
        private fun readByte(input: BufferedInputStream, deadline: Long): Int {
            while (true) {
                check(System.nanoTime() < deadline) { "Request timed out" }
                try { return input.read() } catch (_: SocketTimeoutException) { }
            }
        }
        private fun readFully(input: BufferedInputStream, bytes: ByteArray, deadline: Long) {
            var offset = 0
            while (offset < bytes.size) {
                check(System.nanoTime() < deadline) { "Request timed out" }
                val count = try { input.read(bytes, offset, bytes.size - offset) } catch (_: SocketTimeoutException) { continue }
                if (count <= 0) throw EOFException()
                offset += count
            }
        }
    }
}
