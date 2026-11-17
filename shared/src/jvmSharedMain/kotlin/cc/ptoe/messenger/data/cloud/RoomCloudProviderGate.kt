/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import cc.ptoe.messenger.data.local.room.BUILTIN_PROVIDER_ID
import cc.ptoe.messenger.data.local.room.CloudSyncDao
import cc.ptoe.messenger.data.local.room.DEFERRED_BUILTIN_PROVIDER_CLEANUP
import cc.ptoe.messenger.data.local.room.ProviderRow
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.api.completionEvents
import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.data.remote.dto.ChatCompletionResponseDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamParser
import cc.ptoe.messenger.data.remote.sse.SSEParser
import cc.ptoe.messenger.data.remote.format.ApiFormatRequests
import cc.ptoe.messenger.domain.model.ApiFormat
import cc.ptoe.messenger.domain.chat.KotlinTurnProvider
import cc.ptoe.messenger.domain.chat.KotlinTurnSnapshot
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readRemaining
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.lang.ref.WeakReference
import java.net.Proxy
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request as HttpRequest

/** One gate per Room DAO. All Cloud session invalidation and built-in provider streams use it.
 * The owning Room lease must exclude other database instances and all direct credential writers. */
class RoomCloudProviderGate private constructor(private val dao: CloudSyncDao,
    private val upstream: KotlinTurnProvider, private val closeTransport: () -> Unit,
    private val beforeRegistration: suspend () -> Unit = {}, private val invalidationLimit: Int = 64) {
    private data class Request(val session: SyncSession, val job: Job, val revoked: AtomicBoolean)
    private val mutex = Mutex()
    private val deviceFence = RoomDeviceControlFence.forDao(dao)
    private val admissionLock = Any()
    private val requests = mutableSetOf<Request>()
    private val toolPermits = mutableSetOf<ToolPermit>()
    private val invalidated = mutableMapOf<String, SyncSession>()
    private val invalidationOrder = ArrayDeque<String>()
    private var stopped = false
    private val externalRequestContext = ThreadLocal<Boolean>()
    private val externalHttp = lazy {
        OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    /** A gate can authorize work only for the exact Room DAO that owns its session state. */
    internal fun requireOwner(candidate: CloudSyncDao) {
        require(candidate === dao) { "Cloud provider gate belongs to another Room database" }
    }

    internal companion object {
        private val gates = WeakHashMap<CloudSyncDao, WeakReference<RoomCloudProviderGate>>()
        fun forDao(dao: CloudSyncDao): RoomCloudProviderGate = synchronized(gates) {
            gates[dao]?.get() ?: run {
                val transport = lazy { RoomCloudHttpClient() }
                val provider = object : KotlinTurnProvider {
                    override fun stream(selected: cc.ptoe.messenger.domain.model.Provider, request: cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto) = call(selected, request, true)
                    override fun complete(selected: cc.ptoe.messenger.domain.model.Provider, request: cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto) = call(selected, request, false)
                    private fun call(selected: cc.ptoe.messenger.domain.model.Provider, request: cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto, streaming: Boolean) = flow {
                        check(selected.apiFormat == ApiFormat.CHAT && !selected.nativeWebSearch) { "Unsupported Cloud provider format" }
                        // Capture the cookie before dispatch so error text cannot echo it after revocation.
                        val cookie = dao.value("cloud_session")?.value
                        val secrets = listOfNotNull(selected.apiKey, cookie, cookie?.substringAfter('=', ""))
                        transport.value.http.preparePost(selected.baseUrl.trimEnd('/') + "/chat/completions") {
                            contentType(ContentType.Application.Json)
                            header(HttpHeaders.Authorization, "Bearer ${selected.apiKey}")
                            setBody(ApiFormatRequests.encode(ApiFormat.CHAT, request.copy(stream = streaming)).toString())
                        }.execute { response ->
                            if (!response.status.isSuccess()) error(CloudProviderError.describe(response, secrets))
                            if (streaming) ChatStreamParser.parseToEvents(SSEParser.parse(response.bodyAsChannel())).collect { emit(it) }
                            else {
                                val bytes = response.bodyAsChannel().readRemaining(1_048_577).readByteArray()
                                require(bytes.size <= 1_048_576) { "Cloud completion exceeded size limit" }
                                val completion = NetworkClient.json.decodeFromString<ChatCompletionResponseDto>(bytes.decodeToString(throwOnInvalidSequence = true))
                                completionEvents(completion).forEach { emit(it) }
                            }
                        }
                    }
                }
                RoomCloudProviderGate(dao, provider, closeTransport = { if (transport.isInitialized()) transport.value.close() })
                    .also { gates[dao] = WeakReference(it) }
            }
        }
        /** Tests may inject a controlled cooperative stream before any owner obtains the gate. */
        fun forTesting(dao: CloudSyncDao, upstream: KotlinTurnProvider,
            invalidationLimit: Int = 64, beforeRegistration: suspend () -> Unit = {}): RoomCloudProviderGate = synchronized(gates) {
            check(gates[dao]?.get() == null) { "Cloud gate already exists" }
            RoomCloudProviderGate(dao, upstream, {}, beforeRegistration, invalidationLimit).also { gates[dao] = WeakReference(it) }
        }
        private fun forget(dao: CloudSyncDao, gate: RoomCloudProviderGate) = synchronized(gates) {
            if (gates[dao]?.get() === gate) gates.remove(dao)
        }
    }

    internal fun stream(round: KotlinTurnSnapshot): Flow<ChatStreamEvent> = dispatch(round, false)
    internal fun complete(round: KotlinTurnSnapshot): Flow<ChatStreamEvent> = dispatch(round, true)

    /** Linearization point for work derived from a Cloud turn, including tool claims. */
    internal fun requireValid(session: SyncSession) = synchronized(admissionLock) {
        check(!stopped && !isInvalidated(session)) { "Cloud session expired" }
    }

    private fun isInvalidated(session: SyncSession): Boolean = invalidated[session.accountId] == session

    /** A permit survives removal of the session's invalidation marker after durable logout. */
    internal inner class ToolPermit internal constructor(internal val session: SyncSession) {
        private var revoked = false
        internal fun revoke() { revoked = true }
        internal fun <T> authorize(block: () -> T): T = synchronized(admissionLock) {
            check(!stopped && !revoked && this in toolPermits && !isInvalidated(session)) { "Cloud session expired" }
            block()
        }
        internal fun release() = synchronized(admissionLock) { toolPermits.remove(this); Unit }
    }

    /** Register only inside the same transaction that verified this exact session. */
    internal fun registerToolPermit(session: SyncSession): ToolPermit = synchronized(admissionLock) {
        check(!stopped && !isInvalidated(session)) { "Cloud session expired" }
        ToolPermit(session).also { toolPermits.add(it) }
    }

    /** The gate creates exactly one call using its private no-retry, no-redirect client. */
    internal inner class ExternalProviderPermit internal constructor(
        private val session: SyncSession, private val job: Job, private val revoked: AtomicBoolean, private val expected: ProviderRow,
    ) {
        private var dispatched = false
        private var activeCall: Call? = null
        private val callbackDone = CompletableDeferred<Unit>()
        internal fun enqueue(request: HttpRequest, callback: Callback): Call = synchronized(admissionLock) {
            check(!dispatched && !stopped && !revoked.get() && job.isActive && !isInvalidated(session) &&
                requests.any { it.session == session && it.job === job }) { "Cloud session expired or request already dispatched" }
            val target = runCatching { (expected.baseUrl.trimEnd('/') + "/chat/completions").toHttpUrl() }.getOrNull()
                ?: error("Invalid Cloud provider URL")
            check(expected.apiFormat == "chat" && !expected.nativeWebSearch && target.username.isEmpty() && target.password.isEmpty() &&
                target.query == null && target.fragment == null && request.url == target && request.method == "POST" &&
                request.body?.isOneShot() == true && request.headers.values("Authorization") == listOf("Bearer ${expected.apiKey}") &&
                request.headers.values("Cookie").isEmpty()) {
                "Invalid Cloud provider request"
            }
            dispatched = true
            val call = externalHttp.value.newCall(request)
            activeCall = call
            val wrapped = object : Callback {
                override fun onFailure(call: Call, error: java.io.IOException) = callbackScope {
                    callback.onFailure(call, error)
                }
                override fun onResponse(call: Call, response: okhttp3.Response) = callbackScope {
                    response.use { callback.onResponse(call, it) }
                }
                private inline fun callbackScope(action: () -> Unit) {
                    val previous = externalRequestContext.get()
                    externalRequestContext.set(true)
                    try { action() } finally {
                        if (previous == null) externalRequestContext.remove() else externalRequestContext.set(previous)
                        callbackDone.complete(Unit)
                    }
                }
            }
            try { call.enqueue(wrapped) } catch (failure: Throwable) { callbackDone.complete(Unit); throw failure }
            call
        }
        internal suspend fun closeAndAwait() = withContext(NonCancellable) {
            val call = synchronized(admissionLock) { activeCall }
            if (call != null) { call.cancel(); callbackDone.await() }
        }
    }

    /**
     * Admission for a raw, one-shot provider call such as the ACP relay. The job remains registered
     * until [operation] actually finishes; invalidation cancels it and logout waits for its cleanup.
     * The operation must use [ExternalProviderPermit.enqueue] for its only network dispatch. Its
     * callback must consume the response before returning; the gate closes it and joins callbacks.
     * A callback must not start detached work with the response or invoke/wait for gate lifecycle
     * methods, including from another thread; account revocation runs in a separate owner scope.
     * Provider AI-key 401 is an upstream
     * error, not proof that the account's authenticated cookie expired.
     * The caller must hold a real lifetime fence against all other session and provider writers;
     * the gate coordinates invalidation but does not itself fence direct DAO mutations.
     */
    internal suspend fun <T> withExternalProviderRequest(session: SyncSession, expected: ProviderRow,
        operation: suspend (ExternalProviderPermit) -> T): T = coroutineScope {
        require(expected.id == BUILTIN_PROVIDER_ID && expected.apiKey.isNotBlank()) { "Cloud provider is unavailable" }
        val revoked = AtomicBoolean(false)
        val worker = async(start = CoroutineStart.LAZY) {
            val job = checkNotNull(currentCoroutineContext()[Job])
            val permit = ExternalProviderPermit(session, job, revoked, expected)
            try {
                withContext(externalRequestContext.asContextElement(true)) { operation(permit) }
            } finally {
                permit.closeAndAwait()
            }
        }
        val request = Request(session, worker, revoked)
        try {
            mutex.withLock {
                dao.requireExternalProvider(session, expected)
                synchronized(admissionLock) {
                    check(!stopped && !isInvalidated(session)) { "Cloud session expired" }
                    requests.add(request)
                }
            }
            worker.start()
            worker.await()
        } finally {
            withContext(NonCancellable) {
                worker.cancelAndJoin()
                synchronized(admissionLock) { requests.remove(request) }
            }
        }
    }

    private fun dispatch(round: KotlinTurnSnapshot, completion: Boolean): Flow<ChatStreamEvent> = flow {
        val identity = checkNotNull(round.cloudIdentity) { "Cloud turn has no session identity" }
        val session = SyncSession(identity.accountId, identity.generation)
        coroutineScope {
            val events = Channel<ChatStreamEvent>(Channel.RENDEZVOUS)
            val revoked = AtomicBoolean(false)
            val worker = launch(start = CoroutineStart.LAZY) {
                try {
                    synchronized(admissionLock) {
                        if (stopped || isInvalidated(session) || revoked.get()) throw CloudSessionExpired()
                    }
                    (if (completion) upstream.complete(round.provider, round.request) else upstream.stream(round.provider, round.request)).collect { events.send(it) }
                    events.close()
                } catch (cancelled: CancellationException) {
                    events.close(if (revoked.get()) CloudSessionExpired() else cancelled)
                    throw cancelled
                } catch (failure: Throwable) {
                    events.close(failure)
                }
            }
            worker.invokeOnCompletion { failure ->
                if (failure != null) events.close(if (revoked.get()) CloudSessionExpired() else failure)
            }
            val request = Request(session, worker, revoked)
            try {
                synchronized(admissionLock) {
                    check(!stopped) { "Cloud provider gate is closed" }
                    check(!isInvalidated(session)) { "Cloud session expired" }
                }
                mutex.withLock {
                    check(dao.currentSession() == session && dao.value(DEFERRED_BUILTIN_PROVIDER_CLEANUP) == null) { "Cloud session expired" }
                    val current = dao.providerRow(BUILTIN_PROVIDER_ID)
                    check(current != null && current.apiKey.isNotBlank() && current.apiKey == round.provider.apiKey &&
                        current.baseUrl == round.provider.baseUrl && current.apiFormat == round.provider.apiFormat.wireName &&
                        current.nativeWebSearch == round.provider.nativeWebSearch) { "Cloud provider changed" }
                    beforeRegistration()
                    synchronized(admissionLock) {
                        check(!stopped) { "Cloud provider gate is closed" }
                        check(!isInvalidated(session)) { "Cloud session expired" }
                        requests.add(request)
                    }
                }
                worker.start()
                for (event in events) emit(event)
            } finally {
                withContext(NonCancellable) {
                    worker.cancelAndJoin()
                    synchronized(admissionLock) { requests.remove(request) }
                    events.cancel()
                }
            }
        }
    }

    /** Reject an exact session immediately, without waiting for Room or an in-flight reply. */
    internal fun invalidate(session: SyncSession) {
        val jobs = synchronized(admissionLock) {
            val current = invalidated[session.accountId]
            if (current != null && current.generation != session.generation && isInvalidated(current)) {
                emptyList()
            } else {
                putInvalidation(session)
                revokeExact(session)
            }
        }
        jobs.forEach { it.cancel(CancellationException("Cloud session revoked")) }
    }

    private fun putInvalidation(session: SyncSession) {
        invalidationOrder.remove(session.accountId)
        invalidationOrder.addLast(session.accountId)
        invalidated[session.accountId] = session
        if (invalidated.size > invalidationLimit) {
            repeat(invalidated.size - invalidationLimit) {
                val oldest = invalidationOrder.removeFirst()
                invalidated.remove(oldest)
            }
        }
    }

    private fun revokeExact(session: SyncSession): List<Job> {
        toolPermits.filter { it.session == session }.forEach { it.revoke() }
        return requests.filter { it.session == session }.onEach { it.revoked.set(true) }.map { it.job }
    }

    /** Invalidate first; durable revocation and worker drain may wait for other owners. */
    internal suspend fun revoke(session: SyncSession): Boolean {
        check(externalRequestContext.get() != true) {
            "A Cloud request cannot revoke and join itself"
        }
        return withContext(NonCancellable) {
            invalidate(session)
            val cleared = mutex.withLock {
                val cleared = deviceFence.mutate { dao.clearCredentialsIfCurrent(session) }
                // No validated admission can pass the mutex until the old identity is gone.
                if (cleared || dao.currentSession() != session) synchronized(admissionLock) {
                    if (invalidated[session.accountId] == session) {
                        invalidated.remove(session.accountId)
                        invalidationOrder.remove(session.accountId)
                    }
                }
                cleared
            }
            val jobs = synchronized(admissionLock) { requests.filter { it.session == session }.map { it.job } }
            jobs.joinAll()
            cleared
        }
    }

    /** Close admissions and drain the provider jobs before the Room owner closes the database. */
    internal suspend fun shutdown() {
        check(externalRequestContext.get() != true) { "A Cloud request cannot shut down and join itself" }
        withContext(NonCancellable) {
            val jobs = synchronized(admissionLock) {
                stopped = true
                toolPermits.forEach { it.revoke() }
                requests.onEach { it.revoked.set(true) }.map { it.job }
            }
            jobs.forEach { it.cancel(CancellationException("Cloud provider gate closed")) }
            jobs.joinAll()
            mutex.withLock { /* Wait for any pending database admission check. */ }
            closeTransport()
            if (externalHttp.isInitialized()) {
                externalHttp.value.dispatcher.executorService.shutdown()
                externalHttp.value.connectionPool.evictAll()
            }
            forget(dao, this@RoomCloudProviderGate)
        }
    }
}
