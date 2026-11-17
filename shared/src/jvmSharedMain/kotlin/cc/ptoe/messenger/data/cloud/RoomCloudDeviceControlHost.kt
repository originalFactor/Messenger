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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/**
 * Dispatch must independently check the current remote-control grant, operation policy,
 * project ownership, and session binding before any side effect. A disconnected caller
 * cancels its coroutine; implementations must stop and join their work on cancellation.
 */
internal fun interface RoomDeviceCommandDispatcher {
    suspend fun dispatch(grantEpoch: Long, command: JsonObject, permissions: ReceiveChannel<String>, update: suspend (JsonObject) -> Unit): JsonObject
    suspend fun dispatchWithIdentity(grantEpoch: Long, requestId: String, command: JsonObject, permissions: ReceiveChannel<String>,
        update: suspend (JsonObject) -> Unit): JsonObject = dispatch(grantEpoch, command, permissions, update)
}

internal data class RoomDeviceControlGrant(val name: String, val epoch: Long)

internal interface ManagedRoomDeviceHost {
    suspend fun runOnce()
    suspend fun shutdown()
}
internal class DeviceRelayRequestLimit : IllegalStateException("Remote request identity limit reached; device control stopped")
internal class DeviceRelayCleanupFailure(cause: Throwable) : IllegalStateException("Remote device work did not stop", cause)

/**
 * Workspace events share the device SSE connection and are independent of control
 * opt-in. Implementations must bind calls to this Cloud session, validate owner and
 * conversation policy before execution, retain invocation IDs across reconnects,
 * and cancel and join all local work when [disconnected] returns.
 */
internal interface RoomDeviceWorkspaceRelay {
    suspend fun event(kind: String, payload: JsonObject)
    suspend fun disconnected()
}

/**
 * One device control connection for one Cloud session. A null grant means disabled;
 * the owner must derive it from both explicit opt-in and the local execution grant.
 * `runOnce` never retries or replays a request. Its owner may reconnect a dropped SSE
 * connection, but must shut down and join this instance before replacing the session,
 * database, dispatcher or grant authority. Cross-device workspace tool relay is separate.
 */
internal class RoomCloudDeviceControlHost(
    private val stored: StoredCloudSession,
    private val deviceId: String,
    private val grant: StateFlow<RoomDeviceControlGrant?>,
    private val current: suspend (SyncSession) -> Boolean,
    private val expire: suspend (SyncSession) -> Unit,
    private val replyGate: RoomDeviceReplyGate,
    private val dispatcher: RoomDeviceCommandDispatcher,
    private val workspaceRelay: RoomDeviceWorkspaceRelay? = null,
) : ManagedRoomDeviceHost, AutoCloseable {
    private data class Lane(val permissions: Channel<String>, val job: Job, val remotelyCancelled: AtomicBoolean, val grantEpoch: Long)
    private class HttpFailure(val status: Int) : IOException("Remote device relay HTTP $status")
    private class ReplyFailure(cause: Throwable) : IOException("Remote device reply failed", cause)
    private val root = canonicalCloudBase(stored.host)
    private val lock = Any()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val lanes = ConcurrentHashMap<String, Lane>()
    // Retain seen IDs across reconnects in this session; never replay a command.
    private val seen = mutableSetOf<String>()
    private var active: Job? = null
    @Volatile private var closed = false
    @Volatile private var cleanupFailure: Throwable? = null
    @Volatile private var acceptingControl = false
    @Volatile private var acceptedEpoch: Long? = null
    @Volatile private var settledEpoch: Long? = null
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .protocols(listOf(Protocol.HTTP_1_1))
        .addNetworkInterceptor { chain -> chain.proceed(chain.request()).let {
            if (it.code == 503) it.newBuilder().removeHeader("Retry-After").build() else it
        } }
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    init {
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid device identity" }
    }

    override suspend fun runOnce() = coroutineScope {
        val own = checkNotNull(currentCoroutineContext()[Job])
        synchronized(lock) {
            check(!closed && active == null && cleanupFailure == null) { "Remote device host is unavailable or already running" }
            active = own
        }
        try { runConnection() }
        catch (error: HttpFailure) {
            if (error.status == 401) { expire(stored.session); throw CloudSessionExpired() }
            throw error
        } finally { synchronized(lock) { if (active === own) active = null } }
    }

    private suspend fun runConnection() = coroutineScope {
        val frames = Channel<DeviceFrame>(32)
        val changes = Channel<Unit>(Channel.CONFLATED)
        var stream: Call? = null
        try {
            ensureCurrent()
            val request = Request.Builder().url("$root/api/remote/events?deviceId=$deviceId&control=host")
                .header("Cookie", stored.cookie).header("Accept", "text/event-stream").get().build()
            stream = client.newCall(request)
            register(stream)
            stream.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { frames.close(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw HttpFailure(it.code)
                            val input = BufferedInputStream(it.body.byteStream())
                            while (true) {
                                val frame = readDeviceFrame(input) ?: throw IOException("Remote device relay disconnected")
                                if (!frames.trySend(frame).isSuccess) throw IOException("Remote device relay backpressure limit exceeded")
                            }
                        }
                    } catch (failure: Exception) { frames.close(failure) }
                }
            })
            val grantWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                var previousEpoch: Long? = null
                grant.collect { value ->
                    settledEpoch = null
                    if (value == null || previousEpoch != null && previousEpoch != value.epoch) {
                        acceptingControl = false
                        acceptedEpoch = null
                        val cancelled = synchronized(lock) { lanes.values.toList().also { list -> list.forEach { it.job.cancel() } } }
                        cancelled.map { it.job }.joinAll()
                    }
                    previousEpoch = value?.epoch
                    settledEpoch = value?.epoch
                    changes.trySend(Unit)
                }
            }
            val sessionWatcher = launch {
                while (isActive) { delay(1_000); ensureCurrent() }
            }
            var connection: String? = null
            try {
                while (isActive) {
                    select<Unit> {
                        changes.onReceive {
                            connection?.let { updateAdvertisement(it) }
                        }
                        frames.onReceiveCatching { result ->
                            val frame = result.getOrThrow()
                            when (frame.event) {
                                "controlReady" -> {
                                    check(connection == null) { "Duplicate remote control handshake" }
                                    val id = frame.objectValue().string("connectionId")
                                    require(validDeviceUuid(id)) { "Invalid remote control handshake" }
                                    connection = id
                                    updateAdvertisement(id)
                                }
                                "controlRequest" -> {
                                    val credential = checkNotNull(connection) { "Remote request before handshake" }
                                    val packet = frame.objectValue()
                                    val id = packet.string("id")
                                    val command = packet["payload"] as? JsonObject ?: error("Invalid remote command")
                                    require(validDeviceUuid(id) && command.toString().encodeToByteArray().size <= 240_000) { "Invalid remote command identity" }
                                    synchronized(lock) {
                                        if (seen.size >= 10_000) throw DeviceRelayRequestLimit()
                                        check(seen.add(id)) { "Duplicate remote command" }
                                    }
                                    val admittedGrant = checkNotNull(grant.value) { "Remote control is disabled" }
                                    check(acceptingControl && acceptedEpoch == admittedGrant.epoch) { "Remote control is disabled" }
                                    check(lanes.size < 32) { "Remote command limit reached" }
                                    val permissions = Channel<String>(8)
                                    val remotelyCancelled = AtomicBoolean(false)
                                    val worker = launch(start = CoroutineStart.LAZY) {
                                        try {
                                            ensureAdmitted(admittedGrant.epoch)
                                            val final = dispatcher.dispatchWithIdentity(admittedGrant.epoch, id, command, permissions) { event ->
                                                sendReply(credential, id, admittedGrant.epoch, event, false)
                                            }
                                            sendReply(credential, id, admittedGrant.epoch, final, true)
                                        } catch (cancelled: CancellationException) {
                                            if (remotelyCancelled.get()) withContext(NonCancellable) {
                                                runCatching { reply(credential, id, admittedGrant.epoch, buildJsonObject { put("type", "Cancelled") }, true) }
                                            }
                                            throw cancelled
                                        }
                                        catch (error: ReplyFailure) { throw error }
                                        catch (error: HttpFailure) { throw error }
                                        catch (error: CloudSessionExpired) { throw error }
                                        catch (error: Exception) {
                                            // Do not expose stack traces or credentials to remote peers.
                                            reply(credential, id, admittedGrant.epoch, buildJsonObject {
                                                put("type", "Error"); put("code", "device_error"); put("message", "Device command failed")
                                            }, true)
                                        } finally { permissions.close(); lanes.remove(id) }
                                    }
                                    synchronized(lock) {
                                        check(!closed && acceptingControl && acceptedEpoch == admittedGrant.epoch && grant.value?.epoch == admittedGrant.epoch) { "Remote control is disabled" }
                                        check(lanes.putIfAbsent(id, Lane(permissions, worker, remotelyCancelled, admittedGrant.epoch)) == null)
                                    }
                                    worker.start()
                                }
                                "controlSignal" -> {
                                    val packet = frame.objectValue()
                                    val id = packet.string("id")
                                    require(validDeviceUuid(id)) { "Invalid remote signal identity" }
                                    val lane = lanes[id] ?: return@onReceiveCatching
                                    if (grant.value?.epoch != lane.grantEpoch) return@onReceiveCatching
                                    val option = (packet["optionId"] as? JsonPrimitive)?.contentOrNull
                                    if (option == null) { lane.remotelyCancelled.set(true); lane.job.cancel() }
                                    else {
                                        require(option.isNotEmpty() && option.encodeToByteArray().size <= 256) { "Invalid remote permission" }
                                        check(lane.permissions.trySend(option).isSuccess) { "Remote permission queue is full" }
                                    }
                                }
                                "presence", "toolCall", "toolResult", "toolCancel" -> {
                                    ensureCurrent()
                                    checkNotNull(workspaceRelay) { "Workspace relay is unavailable" }.event(frame.event, frame.objectValue())
                                }
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) { grantWatcher.cancelAndJoin(); sessionWatcher.cancelAndJoin() }
            }
        } finally {
            stream?.cancel()
            acceptingControl = false
            acceptedEpoch = null
            settledEpoch = null
            stream?.let(calls::remove)
            frames.cancel(); changes.cancel()
            val outstanding = synchronized(lock) { lanes.values.toList().also { list -> list.forEach { it.job.cancel() } } }
            try {
                withContext(NonCancellable) {
                    outstanding.map { it.job }.joinAll()
                    workspaceRelay?.disconnected()
                }
            } catch (failure: Throwable) {
                cleanupFailure = failure
                throw DeviceRelayCleanupFailure(failure)
            }
        }
    }

    private suspend fun ensureCurrent() { check(!closed && current(stored.session)) { "Cloud session changed" } }
    private suspend fun ensureAdmitted(epoch: Long) { ensureCurrent(); check(grant.value?.epoch == epoch) { "Remote control grant changed" } }
    private suspend fun updateAdvertisement(connection: String) {
        val current = grant.value?.takeIf { it.epoch == settledEpoch }
        if (current == null || acceptedEpoch != current.epoch) { acceptingControl = false; acceptedEpoch = null }
        advertise(connection, current?.name)
        if (current != null && grant.value == current && settledEpoch == current.epoch && !closed) { acceptedEpoch = current.epoch; acceptingControl = true }
    }
    private suspend fun advertise(connection: String, name: String?) {
        ensureCurrent()
        require(name == null || name.length <= 120 && name.none(Char::isISOControl)) { "Invalid remote device name" }
        post(buildJsonObject { put("connectionId", connection); put("kind", "advertise"); put("enabled", name != null); put("name", name ?: "") })
    }
    private suspend fun reply(connection: String, id: String, epoch: Long, payload: JsonObject, done: Boolean) {
        replyGate.send(epoch) {
            ensureAdmitted(epoch)
            post(buildJsonObject { put("connectionId", connection); put("kind", "reply"); put("id", id); put("payload", payload); put("done", done) })
        }
    }
    private suspend fun sendReply(connection: String, id: String, epoch: Long, payload: JsonObject, done: Boolean) {
        try { reply(connection, id, epoch, payload, done) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: CloudSessionExpired) { throw error }
        catch (error: HttpFailure) { throw error }
        catch (error: Exception) { throw ReplyFailure(error) }
    }
    private suspend fun post(payload: JsonObject) {
        ensureCurrent()
        val bytes = payload.toString().encodeToByteArray()
        require(bytes.size <= 256_000) { "Remote device relay payload exceeds limit" }
        val body = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
        }
        val call = client.newCall(Request.Builder().url("$root/api/remote/control").header("Cookie", stored.cookie).post(body).build())
        try {
            register(call)
            withTimeout(15_000) { execute(call).use {
                // Expire outside the reply fence: session revocation uses the same gate.
                if (it.code == 401) throw HttpFailure(401)
                if (!it.isSuccessful) throw HttpFailure(it.code)
            } }
            ensureCurrent()
        } finally { calls.remove(call) }
    }
    private fun register(call: Call) = synchronized(lock) {
        check(!closed && active != null) { "Remote device host is closed" }
        calls.add(call)
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
            active?.cancel()
            calls.forEach(Call::cancel)
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
    /** Must complete before the owner closes Room or disposes the dispatcher. */
    override suspend fun shutdown() {
        val running = synchronized(lock) { active }
        close()
        running?.join()
        cleanupFailure?.let { throw DeviceRelayCleanupFailure(it) }
    }
}

private fun validDeviceUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: error("Missing remote control $key")
private data class DeviceFrame(val event: String, val data: String) {
    fun objectValue(): JsonObject = Json.parseToJsonElement(data) as? JsonObject ?: error("Invalid remote control frame")
}
private fun readDeviceFrame(input: BufferedInputStream): DeviceFrame? {
    var consumed = 0
    var event = ""
    val data = StringBuilder()
    while (true) {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            check(++consumed <= 512 * 1024) { "Remote device frame too large" }
            if (byte == '\n'.code) break
            line.write(byte)
        }
        val bytes = line.toByteArray().let { if (it.lastOrNull() == '\r'.code.toByte()) it.copyOf(it.size - 1) else it }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        if (text.isEmpty()) {
            if (data.isNotEmpty()) return DeviceFrame(event, data.toString().removeSuffix("\n"))
            consumed = 0; event = ""; continue
        }
        if (text.startsWith(':')) continue
        val name = text.substringBefore(':')
        val value = if (':' in text) text.substringAfter(':').removePrefix(" ") else ""
        when (name) { "event" -> event = value; "data" -> data.append(value).append('\n') }
    }
}
