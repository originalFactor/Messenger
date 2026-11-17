/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicLong

/** Transport implementations must enforce bounded lines and stopAndAwait must prove shutdown. */
internal interface AcpLineTransport {
    suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration)
    suspend fun readLine(): String?
    suspend fun sendLine(line: String)
    suspend fun stopAndAwait()
}

/** Newline-delimited ACP JSON-RPC. No filesystem/terminal callback is provided by default. */
internal class AcpJsonRpcPeer(
    private val transport: AcpLineTransport,
    private val scope: CoroutineScope,
    // Must not perform a request on this peer; it runs serially on the input loop.
    private val onNotification: suspend (String, JsonObject) -> Unit,
    private val onRequest: suspend (String, JsonObject) -> JsonElement,
) {
    private val gate = Mutex()
    private val writes = Mutex()
    private val nextId = AtomicLong()
    private val pending = mutableMapOf<Long, CompletableDeferred<JsonElement>>()
    private val incoming = mutableMapOf<String, Job>()
    private var closed = false
    private var closedMessage: String? = null
    private var reader: Job? = null

    suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration) {
        transport.start(binding, configuration)
        gate.withLock { check(!closed && reader == null) { "ACP peer already started" } }
        reader = scope.launch {
            try {
                while (true) {
                    val line = transport.readLine() ?: break
                    require(line.encodeToByteArray().size <= 192_000) { "ACP line exceeds limit" }
                    val packet = Json.parseToJsonElement(line) as? JsonObject ?: error("Invalid ACP JSON-RPC packet")
                    process(packet)
                }
                fail("ACP process exited")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: AcpSafeHostFailure) { fail(failure.message ?: "ACP process host failed") }
            catch (_: Exception) { fail("Invalid or interrupted ACP stream") }
        }
    }

    suspend fun notify(method: String, params: JsonObject) = write(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) })

    suspend fun request(method: String, params: JsonObject, beforeWrite: () -> Unit = {}): JsonElement {
        val id = nextId.incrementAndGet()
        check(id > 0) { "ACP request ID exhausted" }
        val result = CompletableDeferred<JsonElement>()
        var registered = false
        try {
            gate.withLock {
                check(!closed) { closedMessage ?: "ACP request capacity unavailable" }
                check(reader != null && pending.size < 32) { "ACP request capacity unavailable" }
                pending[id] = result
                registered = true
            }
            write(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }, beforeWrite)
            return result.await()
        } finally {
            if (registered) withContext(NonCancellable) { gate.withLock { pending.remove(id, result) } }
        }
    }

    private suspend fun process(packet: JsonObject) {
        require((packet["jsonrpc"] as? JsonPrimitive)?.contentOrNull == "2.0") { "Invalid ACP JSON-RPC version" }
        val method = (packet["method"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val id = packet["id"]?.takeUnless { it is JsonNull }
        if (method == null) {
            val numericId = (id as? JsonPrimitive)?.longOrNull ?: error("Invalid ACP response ID")
            val waiter = gate.withLock { pending[numericId] } ?: return
            val error = packet["error"] as? JsonObject
            if (error != null) waiter.completeExceptionally(IllegalStateException((error["message"] as? JsonPrimitive)?.contentOrNull ?: "ACP request failed"))
            else waiter.complete(packet["result"] ?: JsonNull)
            return
        }
        val params = packet["params"] as? JsonObject ?: if (packet["params"] == null) JsonObject(emptyMap()) else error("Invalid ACP parameters")
        if (id == null) {
            // Preserve wire ordering: persist each notification before completing a later response.
            onNotification(method, params)
            return
        }
        require(id is JsonPrimitive && (id.isString || id.longOrNull != null)) { "Invalid ACP request ID" }
        val key = id.toString()
        val worker = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = onRequest(method, params)
                write(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                write(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject { put("code", -32603); put("message", "ACP request rejected") }) })
            } finally { gate.withLock { incoming.remove(key) } }
        }
        val accepted = gate.withLock {
            if (closed || incoming.size >= 32 || incoming.containsKey(key)) false else { incoming[key] = worker; true }
        }
        if (!accepted) { worker.cancel(); error("Duplicate or excessive ACP requests") }
        worker.start()
    }

    private suspend fun write(packet: JsonObject, beforeWrite: () -> Unit = {}) {
        val line = packet.toString()
        require(line.encodeToByteArray().size <= 192_000) { "ACP line exceeds limit" }
        writes.withLock { currentCoroutineContext().ensureActive(); beforeWrite(); transport.sendLine(line) }
    }

    private suspend fun fail(message: String) {
        val waiters = gate.withLock {
            if (closed) return
            closed = true
            closedMessage = message
            pending.values.toList().also { pending.clear() }
        }
        waiters.forEach { it.completeExceptionally(IllegalStateException(message)) }
    }

    /** Cancels requests and waits for the platform to confirm process termination. */
    suspend fun closeAndAwait() = withContext(NonCancellable) {
        fail("ACP worker stopped")
        val handlers = gate.withLock { incoming.values.toList().also { incoming.clear() } }
        handlers.forEach { it.cancel() }
        reader?.cancel()
        try { transport.stopAndAwait() }
        finally {
            handlers.joinAll()
            reader?.join()
        }
    }
}
