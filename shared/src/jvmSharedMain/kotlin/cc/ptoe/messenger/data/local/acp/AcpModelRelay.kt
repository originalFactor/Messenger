/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.net.Proxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSink

internal enum class AcpRelayFormat(val wire: String, val path: String) {
    CHAT("chat", "/chat/completions"), RESPONSES("responses", "/responses"),
    MESSAGES("messages", "/messages"), INTERACTIONS("interactions", "/interactions")
}

/** Private provider credentials are process-local and must never be printed or returned to the agent. */
internal class AcpRelayModel(val id: String, val name: String, val providerId: String, val createdAt: Long,
    val enabled: Boolean, val upstreamModel: String, val baseUrl: String, val apiKey: String, val format: AcpRelayFormat) {
    override fun toString() = "AcpRelayModel(redacted)"
}
internal fun interface AcpRelayCatalog { suspend fun find(id: String): AcpRelayModel? }
internal interface AcpRelaySink {
    suspend fun start(status: Int, contentType: String?)
    suspend fun write(chunk: ByteArray)
    fun completed(): Boolean = false
}
internal class AcpRelayException(val code: String, message: String) : Exception(message)

/** One instance/capability per turn. HTTP listener, conversion and production integration are not implemented. */
internal class AcpModelRelay(private val catalog: AcpRelayCatalog, allowedModels: Set<String>, private val errorTimeoutMillis: Long = 5_000) {
    init { require(errorTimeoutMillis in 1..30_000) }
    private class Turn(val id: String) {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val calls = mutableSetOf<Deferred<Unit>>()
    }
    private val models = allowedModels.toSet().also {
        require(it.size <= 256 && it.all { id -> id.isNotBlank() && id.length <= 512 && '/' !in id && id.none(Char::isISOControl) })
    }
    private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val gate = Mutex()
    private var turn: Turn? = null
    private var used = false
    private var closing = false
    private var closed = false
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()

    fun capability(): String = token
    override fun toString() = "AcpModelRelay(capability=redacted)"

    private fun authenticate(candidate: String) {
        if (candidate.length > 256 || !MessageDigest.isEqual(token.toByteArray(Charsets.US_ASCII), candidate.toByteArray(Charsets.US_ASCII)))
            throw AcpRelayException("unauthorized", "Invalid relay token")
    }

    suspend fun models(candidate: String): JsonObject {
        authenticate(candidate)
        gate.withLock { check(!closed && (!used || turn != null)) { "Relay is closed" } }
        val data = models.mapNotNull { id -> lookup(id)?.takeIf { it.id == id && it.enabled }?.let { model ->
            buildJsonObject { put("id", "messenger/${model.id}"); put("object", "model"); put("created", model.createdAt / 1000); put("owned_by", model.providerId); put("name", model.name) }
        } }
        return gate.withLock {
            check(!closed && (!used || turn != null)) { "Relay is closed" }
            buildJsonObject { put("object", "list"); put("data", JsonArray(data)) }
        }
    }

    suspend fun beginTurn(id: String) = gate.withLock {
        require(id.isNotBlank() && id.length <= 256)
        check(!closed && !closing && !used && turn == null) { "Relay is already used or closed" }
        used = true
        turn = Turn(id)
    }
    suspend fun endTurn(id: String) = withContext(NonCancellable) {
        val prior = gate.withLock {
            val active = turn ?: return@withContext
            check(active.id == id) { "Relay turn changed" }
            turn = null; closing = true; active
        }
        try { prior.job.cancelAndJoin() } finally { gate.withLock { closing = false } }
    }
    suspend fun close() = withContext(NonCancellable) {
        val prior = gate.withLock { closed = true; closing = true; val active = turn; turn = null; active }
        try { prior?.job?.cancelAndJoin() } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            gate.withLock { closing = false }
        }
    }

    /** Downstream receives only raw upstream bytes; it must close/cancel this call on disconnect. */
    suspend fun forward(candidate: String, format: AcpRelayFormat, body: JsonObject,
        version: String?, beta: String?, sink: AcpRelaySink) {
        authenticate(candidate)
        val id = (body["model"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.removePrefix("messenger/")?.takeIf { (body["model"] as JsonPrimitive).content == "messenger/$it" && it in models }
            ?: throw AcpRelayException("invalid_model", "Select a model from the relay catalog")
        if (body.toString().encodeToByteArray().size > MAX_BYTES) throw AcpRelayException("request_too_large", "Relay request is too large")
        val task = gate.withLock {
            val active = turn ?: throw AcpRelayException("inactive_turn", "No active ACP turn")
            check(!closed && !closing) { "Relay is closed" }
            if (active.calls.size >= 8) throw AcpRelayException("busy", "Too many relay calls")
            active.scope.async(start = CoroutineStart.LAZY) { send(id, format, body, version, beta, sink) }
                .also { active.calls.add(it) }
        }
        try { task.start(); task.await() } finally { task.cancel(); gate.withLock { turn?.calls?.remove(task) } }
    }

    private suspend fun send(id: String, requested: AcpRelayFormat, body: JsonObject, version: String?, beta: String?, sink: AcpRelaySink) {
        val model = lookup(id)?.takeIf { it.id == id && it.enabled } ?: throw AcpRelayException("model_not_found", "The selected model is unavailable")
        val converted = if (model.format != requested) {
            try {
                val chat = when (requested) {
                    AcpRelayFormat.RESPONSES -> {
                        val value = AcpResponsesToChat.convert(body, model.upstreamModel)
                        ConvertedRequest(value.body, value.stream, value.customTools)
                    }
                    AcpRelayFormat.MESSAGES, AcpRelayFormat.INTERACTIONS -> {
                        val value = AcpMessagesInteractionsToChat.convert(requested.wire, body, model.upstreamModel)
                        val stream = (value["stream"] as? JsonPrimitive)?.booleanOrNull == true
                        ConvertedRequest(value, stream, emptySet())
                    }
                    AcpRelayFormat.CHAT -> {
                        val stream = (body["stream"] as? JsonPrimitive)?.booleanOrNull == true
                        ConvertedRequest(body, stream, emptySet())
                    }
                }
                if (model.format == AcpRelayFormat.CHAT) chat
                else {
                    ConvertedRequest(AcpChatToNative.convert(model.format.wire, chat.body, model.upstreamModel), chat.stream, chat.customTools)
                }
            } catch (failure: AcpRelayException) { throw failure }
            catch (failure: Exception) { throw AcpRelayException("unsupported_feature", failure.message?.take(512) ?: "Unsupported request") }
        } else null
        val base = try { model.baseUrl.trimEnd('/').toHttpUrl() } catch (_: Exception) { throw AcpRelayException("invalid_provider", "Invalid provider address") }
        if (base.username.isNotEmpty() || base.password.isNotEmpty() || base.query != null || base.fragment != null)
            throw AcpRelayException("invalid_provider", "Invalid provider address")
        val url = try { (model.baseUrl.trimEnd('/') + model.format.path).toHttpUrl() } catch (_: Exception) { throw AcpRelayException("invalid_provider", "Invalid provider address") }
        val upstreamBody = converted?.body ?: JsonObject(body + ("model" to JsonPrimitive(model.upstreamModel)))
        val payload = upstreamBody.toString().encodeToByteArray()
        if (payload.size > MAX_BYTES) throw AcpRelayException("request_too_large", "Relay request is too large")
        val requestBody = object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun isOneShot() = true
            override fun contentLength() = payload.size.toLong()
            override fun writeTo(sink: BufferedSink) { sink.write(payload) }
        }
        val builder = Request.Builder().url(url).post(requestBody)
        try {
            if (model.apiKey.isNotEmpty()) {
                builder.header("Authorization", "Bearer ${model.apiKey}")
                if (model.format == AcpRelayFormat.MESSAGES) builder.header("x-api-key", model.apiKey)
                if (model.format == AcpRelayFormat.INTERACTIONS) builder.header("x-goog-api-key", model.apiKey)
            }
            if (model.format == AcpRelayFormat.MESSAGES) {
                builder.header("anthropic-version", safeHeader(version) ?: "2023-06-01")
                safeHeader(beta)?.let { builder.header("anthropic-beta", it) }
            }
        } catch (_: IllegalArgumentException) { throw AcpRelayException("invalid_provider", "Invalid provider credentials or headers") }
        val call = http.newCall(builder.build())
        if (converted != null && !conversionPermits.tryAcquire()) throw AcpRelayException("busy", "Too many converted relay calls")
        try {
            if (converted == null) transfer(call, sink)
            else if (converted.stream) {
                val projected = StreamingResponse(sink, AcpChatResponsesStream("messenger/$id", converted.customTools), requested, "messenger/$id", model.format)
                try { transfer(call, projected); projected.finish() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (projected.projecting) projected.fail() else throw failure
                }
            } else {
                val buffered = BufferedResponse()
                transfer(call, buffered)
                if (buffered.status !in 200..299) {
                    sink.start(buffered.status, buffered.type)
                    sink.write(buffered.bytes.toByteArray())
                } else {
                    val bytes = buffered.bytes.toByteArray()
                    if (!jsonBudget(bytes)) throw AcpRelayException("invalid_upstream", "Provider response exceeds JSON limits")
                    val result = try {
                        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        val json = AcpRelayStrictJson.parse(decoder.decode(ByteBuffer.wrap(bytes)).toString()) as? JsonObject ?: error("Expected response object")
                        val chat = if (model.format == AcpRelayFormat.CHAT) json else AcpNativeToChat().convert(model.format.wire, json, "messenger/$id")
                        when (requested) {
                            AcpRelayFormat.CHAT -> chat
                            AcpRelayFormat.RESPONSES -> AcpChatToResponses().convert(chat, "messenger/$id", converted.customTools)
                            AcpRelayFormat.MESSAGES, AcpRelayFormat.INTERACTIONS -> AcpChatToMessagesInteractions().convert(chat, requested, "messenger/$id")
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { throw AcpRelayException("invalid_upstream", "The provider response cannot be converted") }
                    sink.start(buffered.status, "application/json")
                    sink.write(result.toString().encodeToByteArray())
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: AcpRelayException) {
            currentCoroutineContext().ensureActive()
            throw failure
        }
        catch (_: java.io.IOException) {
            currentCoroutineContext().ensureActive()
            throw AcpRelayException("upstream_error", "Could not reach the selected provider")
        } finally { if (converted != null) conversionPermits.release() }
    }

    private data class ConvertedRequest(val body: JsonObject, val stream: Boolean, val customTools: Set<String>)

    private class StreamingResponse(private val downstream: AcpRelaySink, private val converter: AcpChatResponsesStream,
        target: AcpRelayFormat, model: String, source: AcpRelayFormat) : AcpRelaySink {
        private val framer = AcpRelaySseFramer()
        private val chatFramer = AcpRelaySseFramer()
        private val responsesNative = if (source == AcpRelayFormat.RESPONSES) AcpResponsesStreamToChat(model) else null
        private val messagesNative = if (source == AcpRelayFormat.MESSAGES) AcpMessagesStreamToChat(model) else null
        private val interactionsNative = if (source == AcpRelayFormat.INTERACTIONS) AcpInteractionsStreamToChat(model) else null
        private val projector = if (target == AcpRelayFormat.RESPONSES) null else AcpResponsesEventProjector(target, model)
        private suspend fun emit(events: String) {
            val output = projector?.project(events) ?: events
            if (output.isNotEmpty()) downstream.write(output.encodeToByteArray())
        }
        var projecting = false
            private set
        private var passthrough = false
        override suspend fun start(status: Int, contentType: String?) {
            if (status !in 200..299) { passthrough = true; downstream.start(status, contentType); return }
            if (contentType?.substringBefore(';')?.trim()?.lowercase() != "text/event-stream")
                throw AcpRelayException("invalid_upstream", "Expected a provider event stream")
            downstream.start(status, "text/event-stream")
            projecting = true
            emit(converter.start())
        }
        override suspend fun write(chunk: ByteArray) {
            if (passthrough) { downstream.write(chunk); return }
            check(projecting)
            try {
                for (data in framer.feed(chunk)) {
                    if (converter.isFinished) break
                    val chatEvents = when {
                        responsesNative != null -> responsesNative.feed(data)
                        messagesNative != null -> messagesNative.feed(data)
                        interactionsNative != null -> interactionsNative.feed(data)
                        else -> null
                    }
                    if (chatEvents == null) emit(converter.feed(data))
                    else for (chunk in chatFramer.feed(chatEvents.encodeToByteArray())) {
                        if (converter.isFinished) break
                        emit(converter.feed(chunk))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail() }
        }
        override fun completed() = projecting && (projector?.isFinished ?: converter.isFinished)
        suspend fun finish() {
            if (projecting && !converter.isFinished) emit(converter.endOfStream())
        }
        suspend fun fail() {
            if (!projecting) return
            val output = if (projector != null) projector.fail() else converter.fail("The provider stream could not be converted")
            if (output.isNotEmpty()) downstream.write(output.encodeToByteArray())
        }
    }

    private class BufferedResponse : AcpRelaySink {
        var status = 0
        var type: String? = null
        val bytes = ByteArrayOutputStream()
        override suspend fun start(status: Int, contentType: String?) { this.status = status; type = contentType }
        override suspend fun write(chunk: ByteArray) { bytes.write(chunk) }
    }

    private suspend fun transfer(call: Call, sink: AcpRelaySink) = coroutineScope {
        val operation = async(Dispatchers.IO) {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val message = readError(call, response)
                    currentCoroutineContext().ensureActive()
                    sink.start(response.code, "application/json")
                    sink.write(message)
                    return@use
                }
                val type = publicContentType(response.header("Content-Type"))
                sink.start(response.code, type)
                val source = response.body.byteStream()
                val buffer = ByteArray(16 * 1024)
                var count = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    count += read
                    if (count > MAX_BYTES) throw AcpRelayException("invalid_upstream", "Provider response is too large")
                    if (read > 0) sink.write(buffer.copyOf(read))
                    if (sink.completed()) break
                }
            }
        }
        try { operation.await() } finally { call.cancel() }
    }

    /** No partial error text escapes on an oversized, timed-out, interrupted or malformed body. */
    private suspend fun readError(call: Call, response: Response): ByteArray {
        val timeout = errorWatchdog.schedule({ call.cancel() }, errorTimeoutMillis, TimeUnit.MILLISECONDS)
        val raw = try {
            val source = response.body.byteStream()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    if (output.size() + count > MAX_ERROR_BYTES) return AcpRelayErrorSanitizer.format(response.code, null)
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: java.io.IOException) { currentCoroutineContext().ensureActive(); null }
        finally { timeout.cancel(false) }
        return AcpRelayErrorSanitizer.format(response.code, raw)
    }

    /** Never reflect arbitrary provider-controlled header parameters into the local client. */
    private fun publicContentType(value: String?): String? = when (value?.substringBefore(';')?.trim()?.lowercase()) {
        "application/json" -> "application/json"
        "text/event-stream" -> "text/event-stream"
        "text/plain" -> "text/plain"
        "application/octet-stream" -> "application/octet-stream"
        else -> null
    }

    private suspend fun lookup(id: String): AcpRelayModel? = try { catalog.find(id) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { throw AcpRelayException("store_error", "Could not load model") }

    private fun safeHeader(value: String?): String? {
        if (value == null) return null
        require(value.length in 1..512 && value.all { it.code in 32..126 }) { "Invalid relay header" }
        return value
    }
    companion object {
        private const val MAX_BYTES = 24 * 1024 * 1024
        private const val MAX_ERROR_BYTES = 64 * 1024
        private val errorWatchdog = java.util.concurrent.ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "messenger-acp-relay-errors").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private val conversionPermits = Semaphore(4)
        private fun jsonBudget(bytes: ByteArray): Boolean {
            var inString = false; var escaped = false; var depth = 0; var nodes = 0
            for (byte in bytes) {
                val value = byte.toInt() and 255
                if (inString) {
                    if (escaped) escaped = false else if (value == 92) escaped = true else if (value == 34) inString = false
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
    }
}
