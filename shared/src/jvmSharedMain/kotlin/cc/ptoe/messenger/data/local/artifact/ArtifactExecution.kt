/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.artifact

import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.image.isPublicAddress
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.ArrayDeque
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

internal data class ArtifactFetchRequest(val url: String, val method: String = "GET", val body: String? = null, val contentType: String? = null)
internal data class ArtifactFetchResponse(val status: Int, val body: String)
internal fun interface ArtifactDns { suspend fun lookup(host: String): List<InetAddress> }
internal fun interface ArtifactTransport { suspend fun send(url: HttpUrl, addresses: List<InetAddress>, request: ArtifactFetchRequest): ArtifactFetchResponse }

/** Process-wide admission: reject excess calls rather than buffering untrusted requests. */
internal class ArtifactRequestLimiter(
    private val maxGlobal: Int = 8, private val maxSession: Int = 2,
    private val globalPerMinute: Int = 120, private val sessionPerMinute: Int = 15,
    private val clock: () -> Long = System::nanoTime,
) {
    internal class Session { var active = 0; val attempts = ArrayDeque<Long>() }
    private var active = 0
    private val attempts = ArrayDeque<Long>()
    private val window = TimeUnit.MINUTES.toNanos(1)

    @Synchronized fun acquire(session: Session) {
        val now = clock()
        while (attempts.isNotEmpty() && now - attempts.first >= window) attempts.removeFirst()
        while (session.attempts.isNotEmpty() && now - session.attempts.first >= window) session.attempts.removeFirst()
        check(active < maxGlobal && session.active < maxSession) { "Artifact request concurrency limit exceeded" }
        check(attempts.size < globalPerMinute && session.attempts.size < sessionPerMinute) { "Artifact request rate limit exceeded" }
        active++
        session.active++
        attempts.addLast(now)
        session.attempts.addLast(now)
    }

    @Synchronized fun release(session: Session) {
        check(active > 0 && session.active > 0)
        active--
        session.active--
    }

    companion object { val shared = ArtifactRequestLimiter() }
}

/** A trusted host must bind this object to one sandbox identity and close it on teardown. */
internal class ArtifactExecutionService(
    private val artifacts: RoomArtifactService,
    private val assets: CoreAssetService,
    private val dns: ArtifactDns = ArtifactDns { host -> withContext(Dispatchers.IO) { InetAddress.getAllByName(host).toList() } },
    private val transport: ArtifactTransport = PinnedArtifactTransport,
    private val limiter: ArtifactRequestLimiter = ArtifactRequestLimiter.shared,
) {
    suspend fun open(id: String): ArtifactExecutionSession {
        return artifacts.withExecutionAuthorization(id, null) { artifact ->
            val revision = artifact.revisions.last()
            ArtifactExecutionSession(artifacts, assets, dns, transport, limiter, id, revision.revision, artifact.stateVersion).also { it.register() }
        }
    }
}

/** This capability contains no provider credentials; it is never exposed directly to generated code. */
internal class ArtifactExecutionSession internal constructor(
    private val artifacts: RoomArtifactService, private val assets: CoreAssetService,
    private val dns: ArtifactDns, private val transport: ArtifactTransport, private val limiter: ArtifactRequestLimiter,
    private val id: String, private val revision: Long, private val stateVersion: Long,
) {
    private val closed = AtomicBoolean(false)
    private val stateLock = Mutex()
    private val flightLock = Any()
    private val inFlight = mutableSetOf<Job>()
    private val requestLimit = ArtifactRequestLimiter.Session()
    private val invalidated: () -> Unit = { synchronized(flightLock) { inFlight.toList().forEach { it.cancel() } } }

    internal fun register() { artifacts.addListener(id, invalidated) }

    /** Waits for a state transaction already in progress and cancels in-flight requests. */
    suspend fun close() = stateLock.withLock {
        synchronized(flightLock) {
            closed.set(true)
            inFlight.toList().forEach { it.cancel() }
        }
        artifacts.removeListener(id, invalidated)
    }

    private suspend fun current(): ArtifactData = artifacts.withExecutionAuthorization(id, revision) { value ->
        check(!closed.get() && value.stateVersion == stateVersion) { "Artifact session is closed or state version changed" }
        value
    }

    /** Only the host receives the package, and must serve it within the opaque sandbox. */
    suspend fun packageSnapshot(): Pair<ArtifactRevision, JsonElement> = current().let { it.revisions.last() to it.state }
    suspend fun getState(): JsonElement = current().state
    suspend fun setState(value: JsonElement): JsonElement = stateLock.withLock {
        current()
        artifacts.saveState(id, revision, stateVersion, value).state
    }
    suspend fun readAsset(path: String): ByteArray {
        val artifact = current()
        val assetId = artifact.revisions.last().assets[path] ?: error("Asset is not part of this revision")
        val bytes = assets.read(assetId)
        current()
        return bytes
    }
    suspend fun fetch(request: ArtifactFetchRequest): ArtifactFetchResponse = coroutineScope {
        val task = async(start = CoroutineStart.LAZY) {
            withTimeout(20_000) {
                val artifact = current()
                require(request.url.toByteArray(Charsets.UTF_8).size <= 8192) { "External URL exceeds limit" }
                val url = request.url.toHttpUrlOrNull() ?: error("Invalid external URL")
                require(url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.host in artifact.approvedDomains) {
                    "External domain is not authorized for this application"
                }
                require(request.method in setOf("", "GET", "POST")) { "Only GET and POST are supported" }
                require(request.method == "POST" || request.body == null) { "GET body is unsupported" }
                require(request.body?.toByteArray(Charsets.UTF_8)?.size?.let { it <= 64 * 1024 } != false) { "Request exceeds 64 KiB" }
                if (request.body != null) require((request.contentType ?: "application/json") in setOf("application/json", "text/plain", "application/x-www-form-urlencoded")) { "Unsupported request content type" }
                val addresses = withTimeout(5_000) { dns.lookup(url.host) }
                require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) { "External requests cannot access local or reserved addresses" }
                // Dispatch under the same gate as revocation, then release it while the call is in flight.
                // Cancellation stops local I/O; a POST already received remotely cannot be recalled.
                val requestContext = currentCoroutineContext()
                val requestJob = requestContext[Job]
                val outgoing = artifacts.withExecutionAuthorization(id, revision) { latest ->
                    synchronized(flightLock) {
                        check(!closed.get() && latest.stateVersion == stateVersion && url.host in latest.approvedDomains) { "External domain grant changed" }
                        requestJob?.ensureActive()
                        CoroutineScope(requestContext).async(start = CoroutineStart.UNDISPATCHED) { transport.send(url, addresses, request) }
                    }
                }
                val response = outgoing.await()
                check(url.host in current().approvedDomains) { "External domain grant changed" }
                response
            }
        }
        var admitted = false
        try {
            synchronized(flightLock) {
                check(!closed.get()) { "Artifact session is closed" }
                limiter.acquire(requestLimit)
                admitted = true
                inFlight.add(task)
            }
            artifacts.withExecutionAuthorization(id, revision) {
                synchronized(flightLock) {
                    check(!closed.get()) { "Artifact session is closed" }
                    task.start()
                }
            }
            task.await()
        } finally {
            synchronized(flightLock) { inFlight.remove(task) }
            if (admitted) limiter.release(requestLimit)
        }
    }
}

/** No redirects, proxies, caller headers, authentication, cookies, or automatic retries. */
private object PinnedArtifactTransport : ArtifactTransport {
    // All requests share dispatcher threads; per-request DNS objects prevent connection reuse
    // across different validated address sets, and no idle connections are retained.
    private val dispatcher = Dispatcher().apply { maxRequests = 8; maxRequestsPerHost = 2 }
    private val base = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dispatcher(dispatcher).connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
        }.callTimeout(20, TimeUnit.SECONDS).build()
    override suspend fun send(url: HttpUrl, addresses: List<InetAddress>, request: ArtifactFetchRequest): ArtifactFetchResponse {
        val pinned = Dns { host -> if (host == url.host) addresses else throw UnknownHostException("Unexpected host") }
        val client = base.newBuilder().dns(pinned).build()
        val bodyBytes = request.body?.toByteArray(Charsets.UTF_8)
        val body = bodyBytes?.let { bytes ->
            object : RequestBody() {
                override fun contentType() = (request.contentType ?: "application/json").toMediaType()
                override fun contentLength() = bytes.size.toLong()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
            }
        }
        val method = request.method.ifEmpty { "GET" }
        // OkHttp disallows a GET body; fail closed rather than silently discard it.
        require(method != "GET" || body == null) { "GET body is unsupported" }
        val actualBody = if (method == "POST" && body == null) object : RequestBody() {
            override fun contentType() = null
            override fun contentLength() = 0L
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = Unit
        } else body
        val call = client.newCall(Request.Builder().url(url).method(method, actualBody).build())
        return execute(call)
    }
    private suspend fun execute(call: Call): ArtifactFetchResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        require(it.code !in 300..399) { "External redirects are not supported" }
                        val source = it.body.source()
                        source.request(1024L * 1024 + 1)
                        require(source.buffer.size <= 1024L * 1024) { "External response exceeds 1 MiB" }
                        val bytes = source.readByteArray()
                        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                        ArtifactFetchResponse(it.code, text)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }
}
