/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A grant and the exact session it authorizes must be published as one value. */
internal data class RoomDeviceHostAccess(val session: StoredCloudSession, val grant: RoomDeviceControlGrant?)

/**
 * Owns exactly one device relay for a Cloud session and its optional control grant.
 * Only the SSE connection is re-established after a drop; no command, permission or
 * reply is replayed. A session switch or shutdown cancels and joins the old host before
 * a new one may start. Disabling control leaves the account workspace relay online but
 * revokes all control lanes. The caller supplies a durable session check and observes failures.
 */
internal class RoomCloudDeviceHostManager(
    private val access: StateFlow<RoomDeviceHostAccess?>,
    private val stillCurrent: suspend (StoredCloudSession) -> Boolean,
    private val newHost: (StoredCloudSession, StateFlow<RoomDeviceControlGrant?>) -> ManagedRoomDeviceHost,
    private val onDisconnect: suspend (Throwable) -> Unit,
    private val initialDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 30_000,
) : AutoCloseable {
    private val lock = Any()
    private var active: Job? = null
    @Volatile private var closed = false
    @Volatile private var terminationFailure: Throwable? = null

    init { require(initialDelayMs > 0 && maxDelayMs >= initialDelayMs) }

    suspend fun run() = coroutineScope {
        val own = checkNotNull(currentCoroutineContext()[Job])
        synchronized(lock) {
            check(!closed && active == null && terminationFailure == null) { "Device host manager is unavailable or already running" }
            active = own
        }
        try {
            access.distinctUntilChanged { left, right -> sameBinding(left?.session, right?.session) }
                .collectLatest { desired ->
                    val session = desired?.session ?: return@collectLatest
                    if (!stillCurrent(session)) return@collectLatest
                    val grant = MutableStateFlow(desired.grant)
                    val grantWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                        access.collect { current ->
                            grant.value = current?.takeIf { sameBinding(it.session, session) }?.grant
                        }
                    }
                    val host = try { newHost(session, grant) } catch (failure: Throwable) {
                        grantWatcher.cancelAndJoin(); throw failure
                    }
                    try {
                        var backoff = initialDelayMs
                        while (isActive && sameBinding(access.value?.session, session) && stillCurrent(session)) {
                            val started = System.nanoTime()
                            try {
                                host.runOnce()
                                throw IOException("Remote device connection ended")
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (expired: CloudSessionExpired) { onDisconnect(expired); return@collectLatest }
                            catch (limit: DeviceRelayRequestLimit) { onDisconnect(limit); return@collectLatest }
                            catch (cleanup: DeviceRelayCleanupFailure) { onDisconnect(cleanup); throw cleanup }
                            catch (failure: Exception) {
                                onDisconnect(failure)
                                if (!sameBinding(access.value?.session, session) || !stillCurrent(session)) return@collectLatest
                                if (System.nanoTime() - started >= 30.seconds.inWholeNanoseconds) backoff = initialDelayMs
                                delay(backoff)
                                backoff = if (backoff >= maxDelayMs / 2) maxDelayMs else backoff * 2
                            }
                        }
                    } finally {
                        // A non-cooperative worker must finish before the next owner starts.
                        withContext(NonCancellable) { grantWatcher.cancelAndJoin(); host.shutdown() }
                    }
                }
        } catch (failure: Throwable) {
            if (failure !is CancellationException) terminationFailure = failure
            throw failure
        } finally {
            synchronized(lock) { if (active === own) active = null }
        }
    }

    override fun close() { synchronized(lock) { closed = true; active?.cancel() } }

    /** The Room owner must wait for success before closing the database or replacing the dispatcher. */
    suspend fun shutdown() {
        val running = synchronized(lock) { active }
        close()
        running?.join()
        terminationFailure?.let { throw IllegalStateException("Device host shutdown did not complete", it) }
    }
}

private fun sameBinding(left: StoredCloudSession?, right: StoredCloudSession?): Boolean =
    if (left == null || right == null) left == null && right == null
    else left.session == right.session && left.cookie == right.cookie && canonicalCloudBase(left.host) == canonicalCloudBase(right.host)
