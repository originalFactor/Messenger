/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/**
 * One authenticated controller session; no command or signal is replayed automatically.
 * The owner must close this controller before switching or clearing its session, and
 * serialize controller creation with that transition. An already-sent POST cannot be recalled.
 */
internal class RoomCloudRemoteControl(
    private val stored: StoredCloudSession,
    private val current: suspend (SyncSession) -> Boolean,
    private val expire: suspend (SyncSession) -> Unit,
    private val beforePostEnqueue: suspend () -> Unit = {},
) : AutoCloseable {
    private data class Lane(val signals: Channel<String?>, val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet())
    private class HttpFailure(val status: Int) : IOException("Remote relay HTTP $status")
    private val host = canonicalCloudBase(stored.host)
    private val lanes = ConcurrentHashMap<String, Lane>()
    private val lock = Any()
    @Volatile private var closed = false
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .addNetworkInterceptor { chain -> chain.proceed(chain.request()).let { if (it.code == 503) it.newBuilder().removeHeader("Retry-After").build() else it } }
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    suspend fun command(requestId: String, deviceId: String, payload: JsonObject, onEvent: (JsonObject) -> Unit) = coroutineScope {
        require(uuid(requestId) && deviceId.length in 1..64 && deviceId.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }) { "Invalid remote request identity" }
        val action = (payload["action"] as? JsonPrimitive)?.contentOrNull
        require(action in setOf("history", "prompt", "notificationOpen", "notificationDecision", "notificationCancel") && payload.toString().encodeToByteArray().size <= 240_000) { "Invalid remote command" }
        ensureCurrent()
        val lane = Lane(Channel(8))
        synchronized(lock) {
            check(!closed && lanes.size < 32 && lanes.putIfAbsent(requestId, lane) == null) { "Remote request already active or limit reached" }
        }
        val frames = Channel<RoomSseFrame>(8)
        val clientDevice = "client-${UUID.randomUUID()}"
        val request = Request.Builder().url("$host/api/remote/events?deviceId=$clientDevice&control=client&controlOnly=1")
            .header("Cookie", stored.cookie).header("Accept", "text/event-stream").get().build()
        val call = client.newCall(request)
        var watcher: Job? = null
        try {
            registerCall(lane, call)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { frames.close(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw HttpFailure(it.code)
                            val stream = BufferedInputStream(it.body.byteStream())
                            while (true) {
                                val frame = readFrame(stream) ?: throw IOException("Remote relay disconnected")
                                if (!frames.trySend(frame).isSuccess) throw IOException("Remote relay backpressure limit exceeded")
                            }
                        }
                    } catch (failure: Exception) { frames.close(failure) }
                }
            })
            watcher = launch {
                while (true) {
                    delay(2_000)
                    if (!current(stored.session)) {
                        frames.close(IllegalStateException("Cloud session changed"))
                        call.cancel()
                        return@launch
                    }
                }
            }
            val connection = withTimeout(15_000) {
                while (true) {
                    val frame = frames.receive()
                    if (frame.event == "controlReady") {
                        val parsed = parseObject(frame.data)
                        val id = (parsed["connectionId"] as? JsonPrimitive)?.contentOrNull
                        check(id != null && uuid(id)) { "Invalid remote relay handshake" }
                        return@withTimeout id
                    }
                }
                @Suppress("UNREACHABLE_CODE") error("Unreachable")
            }
            post(lane, buildJsonObject {
                put("connectionId", connection); put("kind", "request"); put("id", requestId); put("target", deviceId); put("payload", payload)
            })
            var done = false
            while (!done) {
                select<Unit> {
                    frames.onReceiveCatching { result ->
                        val frame = result.getOrThrow()
                        if (frame.event == "controlReply") {
                            val reply = parseObject(frame.data)
                            if ((reply["id"] as? JsonPrimitive)?.contentOrNull == requestId) {
                                val event = reply["payload"] as? JsonObject ?: error("Invalid remote reply")
                                val terminal = (reply["done"] as? JsonPrimitive)?.booleanOrNull ?: false
                                ensureCurrent()
                                onEvent(event)
                                done = terminal
                            }
                        }
                    }
                    lane.signals.onReceiveCatching { result ->
                        if (result.isClosed) throw IOException("Remote control lane closed")
                        val option = result.getOrNull()
                        post(lane, buildJsonObject {
                            put("connectionId", connection); put("kind", "signal"); put("id", requestId)
                            option?.let { put("optionId", it) }
                        })
                    }
                }
            }
        } catch (failure: HttpFailure) {
            if (failure.status == 401) { expire(stored.session); throw CloudSessionExpired() }
            throw failure
        } finally {
            lanes.remove(requestId, lane)
            lane.signals.close()
            lane.calls.forEach(Call::cancel)
            frames.cancel()
            watcher?.cancelAndJoin()
        }
    }

    suspend fun signal(requestId: String, optionId: String?) {
        require(uuid(requestId) && (optionId == null || optionId.isNotEmpty() && optionId.encodeToByteArray().size <= 256)) { "Invalid remote signal" }
        ensureCurrent()
        val lane = lanes[requestId] ?: error("Remote request is no longer active")
        check(lane.signals.trySend(optionId).isSuccess) { "Remote request is closed or signal queue is full" }
    }

    private suspend fun ensureCurrent() { check(!closed && current(stored.session)) { "Cloud session changed" } }
    private suspend fun post(lane: Lane, payload: JsonObject) {
        ensureCurrent()
        val bytes = payload.toString().encodeToByteArray()
        require(bytes.size <= 256_000) { "Remote relay payload exceeds limit" }
        val body = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
        }
        val call = client.newCall(Request.Builder().url("$host/api/remote/control").header("Cookie", stored.cookie).post(body).build())
        try {
            registerCall(lane, call)
            beforePostEnqueue()
            withTimeout(15_000) { execute(call).use { if (!it.isSuccessful) throw HttpFailure(it.code) } }
            ensureCurrent()
        } finally { lane.calls.remove(call) }
    }
    private fun registerCall(lane: Lane, call: Call) = synchronized(lock) {
        check(!closed && lanes.values.any { it === lane }) { "Remote controller is closed" }
        lane.calls.add(call)
    }
    private suspend fun execute(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }
    override fun close() {
        synchronized(lock) {
            closed = true
            lanes.values.forEach { lane -> lane.signals.close(); lane.calls.forEach(Call::cancel) }
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}

private fun uuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
private fun parseObject(data: String): JsonObject = Json.parseToJsonElement(data) as? JsonObject ?: error("Invalid remote relay JSON")

/** Reads one bounded SSE frame; comments and multi-line data follow the SSE wire format. */
private fun readFrame(input: BufferedInputStream): RoomSseFrame? {
    var consumed = 0
    var event = ""
    val data = StringBuilder()
    while (true) {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            check(++consumed <= 512 * 1024) { "Remote relay frame too large" }
            if (byte == '\n'.code) break
            line.write(byte)
        }
        val bytes = line.toByteArray().let { if (it.lastOrNull() == '\r'.code.toByte()) it.copyOf(it.size - 1) else it }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        if (text.isEmpty()) {
            if (data.isNotEmpty()) return RoomSseFrame(event, data.toString().removeSuffix("\n"))
            consumed = 0; event = ""; continue
        }
        if (text.startsWith(':')) continue
        val name = text.substringBefore(':')
        val value = if (':' in text) text.substringAfter(':').removePrefix(" ") else ""
        when (name) { "event" -> event = value; "data" -> data.append(value).append('\n') }
    }
}
private data class RoomSseFrame(val event: String, val data: String)
