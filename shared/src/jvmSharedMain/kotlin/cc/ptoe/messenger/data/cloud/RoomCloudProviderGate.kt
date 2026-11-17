/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import cc.ptoe.messenger.data.local.room.BUILTIN_PROVIDER_ID
import cc.ptoe.messenger.data.local.room.CloudSyncDao
import cc.ptoe.messenger.data.local.room.DEFERRED_BUILTIN_PROVIDER_CLEANUP
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
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray

/** One gate per Room DAO. All Cloud session invalidation and built-in provider streams use it.
 * The owning Room lease must exclude other database instances and all direct credential writers. */
class RoomCloudProviderGate private constructor(private val dao: CloudSyncDao,
    private val upstream: KotlinTurnProvider, private val closeTransport: () -> Unit,
    private val beforeRegistration: suspend () -> Unit = {}) {
    private data class Request(val session: SyncSession, val job: Job, val revoked: AtomicBoolean)
    private val mutex = Mutex()
    private val deviceFence = RoomDeviceControlFence.forDao(dao)
    private val admissionLock = Any()
    private val requests = mutableSetOf<Request>()
    private val toolPermits = mutableSetOf<ToolPermit>()
    private val invalidated = mutableSetOf<SyncSession>()
    private var stopped = false

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
            beforeRegistration: suspend () -> Unit = {}): RoomCloudProviderGate = synchronized(gates) {
            check(gates[dao]?.get() == null) { "Cloud gate already exists" }
            RoomCloudProviderGate(dao, upstream, {}, beforeRegistration).also { gates[dao] = WeakReference(it) }
        }
        private fun forget(dao: CloudSyncDao, gate: RoomCloudProviderGate) = synchronized(gates) {
            if (gates[dao]?.get() === gate) gates.remove(dao)
        }
    }

    internal fun stream(round: KotlinTurnSnapshot): Flow<ChatStreamEvent> = dispatch(round, false)
    internal fun complete(round: KotlinTurnSnapshot): Flow<ChatStreamEvent> = dispatch(round, true)

    /** Linearization point for work derived from a Cloud turn, including tool claims. */
    internal fun requireValid(session: SyncSession) = synchronized(admissionLock) {
        check(!stopped && session !in invalidated) { "Cloud session expired" }
    }

    /** A permit survives removal of the session's invalidation marker after durable logout. */
    internal inner class ToolPermit internal constructor(internal val session: SyncSession) {
        private var revoked = false
        internal fun revoke() { revoked = true }
        internal fun <T> authorize(block: () -> T): T = synchronized(admissionLock) {
            check(!stopped && !revoked && this in toolPermits && session !in invalidated) { "Cloud session expired" }
            block()
        }
        internal fun release() = synchronized(admissionLock) { toolPermits.remove(this); Unit }
    }

    /** Register only inside the same transaction that verified this exact session. */
    internal fun registerToolPermit(session: SyncSession): ToolPermit = synchronized(admissionLock) {
        check(!stopped && session !in invalidated) { "Cloud session expired" }
        ToolPermit(session).also { toolPermits.add(it) }
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
                        if (stopped || session in invalidated || revoked.get()) throw CloudSessionExpired()
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
                    check(session !in invalidated) { "Cloud session expired" }
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
                        check(session !in invalidated) { "Cloud session expired" }
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
            invalidated.add(session)
            toolPermits.filter { it.session == session }.forEach { it.revoke() }
            requests.filter { it.session == session }.onEach { it.revoked.set(true) }.map { it.job }
        }
        jobs.forEach { it.cancel(CancellationException("Cloud session revoked")) }
    }

    /** Invalidate first; durable revocation and worker drain may wait for other owners. */
    internal suspend fun revoke(session: SyncSession): Boolean = withContext(NonCancellable) {
        invalidate(session)
        val cleared = mutex.withLock {
            val cleared = deviceFence.mutate { dao.clearCredentialsIfCurrent(session) }
            // No validated admission can pass the mutex until the old identity is gone.
            if (cleared || dao.currentSession() != session) synchronized(admissionLock) { invalidated.remove(session) }
            cleared
        }
        val jobs = synchronized(admissionLock) { requests.filter { it.session == session }.map { it.job } }
        jobs.joinAll()
        cleared
    }

    /** Close admissions and drain the provider jobs before the Room owner closes the database. */
    internal suspend fun shutdown() = withContext(NonCancellable) {
        val jobs = synchronized(admissionLock) {
            stopped = true
            toolPermits.forEach { it.revoke() }
            requests.onEach { it.revoked.set(true) }.map { it.job }
        }
        jobs.forEach { it.cancel(CancellationException("Cloud provider gate closed")) }
        jobs.joinAll()
        mutex.withLock { /* Wait for any pending database admission check. */ }
        closeTransport()
        forget(dao, this@RoomCloudProviderGate)
    }
}
