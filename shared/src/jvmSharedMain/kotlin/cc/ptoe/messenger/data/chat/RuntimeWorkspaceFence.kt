/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes one Room owner's workspace dispatches with every participating authority mutation.
 * It is not a platform/process lock. Before installation the host must route all project,
 * conversation, Agent, Cloud/session, sync and restore writers through this same instance and
 * independently exclude other Room/Core owners. No production caller currently does so.
 */
class RuntimeWorkspaceFence private constructor(db: LocalDatabase) {
    private val owner = WeakReference(db)
    companion object {
        // The value must survive while its Room owner does, including after an adapter is replaced.
        // The fence holds only a weak reference back to the key so the registry cannot retain Room.
        private val owners = WeakHashMap<LocalDatabase, RuntimeWorkspaceFence>()

        /** Every participant using this Room instance must retain and use this same fence. */
        fun forDatabase(db: LocalDatabase): RuntimeWorkspaceFence = synchronized(owners) {
            owners[db] ?: RuntimeWorkspaceFence(db).also { owners[db] = it }
        }
    }
    private val mutex = Mutex()
    private val admissionLock = Any()
    private data class Uncertain(val token: String, val host: WeakReference<TrackedWorkspaceHost>)
    private data class Active(val token: String, val host: TrackedWorkspaceHost, val job: Job?,
        var dispatched: Boolean = false, var revoked: Boolean = false)
    private var uncertain: Uncertain? = null
    private var active: Active? = null
    private var invalidated = false
    private var generation = 0L
    private var authorityEpoch = 0L
    private val dispatchedTokens = mutableSetOf<String>()

    internal fun requireOwner(candidate: LocalDatabase) {
        require(candidate === owner.get()) { "Workspace fence belongs to another Room owner" }
    }

    /** All authority-changing writers must use this gate, including Cloud restore. */
    suspend fun <T> mutate(block: suspend () -> T): T = mutex.withLock {
        synchronized(admissionLock) {
            check(!invalidated && uncertain == null) { "Workspace execution requires recovery" }
            authorityEpoch++
        }
        block()
    }

    internal fun authoritySnapshot(): Long = synchronized(admissionLock) {
        check(!invalidated && uncertain == null) { "Workspace execution requires recovery" }
        authorityEpoch
    }

    /** Synchronous final admission; the block must not suspend or perform I/O. */
    internal fun <T> authorizeAuthority(epoch: Long, block: () -> T): T = synchronized(admissionLock) {
        check(!invalidated && uncertain == null && authorityEpoch == epoch) { "Workspace authority changed" }
        block()
    }

    /** Close admissions without waiting for an executing host. Credential revocation must not
     * wait for a potentially stuck Binder/process call. Recovery requires actual stop evidence. */
    fun invalidateAdmissions() {
        val job = synchronized(admissionLock) {
            generation++
            authorityEpoch++
            invalidated = true
            active?.also {
                it.revoked = true
                if (it.dispatched) uncertain = Uncertain(it.token, WeakReference(it.host))
            }?.job
        }
        job?.cancel(CancellationException("Workspace authority revoked"))
    }

    /** An exception after dispatch is unknown until the exact host proves the token stopped. */
    internal suspend fun <T> execute(token: String, host: TrackedWorkspaceHost,
        block: suspend (markDispatched: () -> Unit) -> T): T {
        var admitted: Active? = null
        try {
            return coroutineScope {
                mutex.withLock {
                    // A fresh scope has its own cancellable Job even when the runner deliberately
                    // invokes tools under NonCancellable. It still waits for host cleanup.
                    require(token.matches(Regex("[0-9a-f]{64}"))) { "Invalid workspace operation token" }
                    val job = currentCoroutineContext()[Job]
                    val invocation = synchronized(admissionLock) {
                        check(!invalidated && uncertain == null) { "Workspace execution requires recovery" }
                        check(token !in dispatchedTokens && dispatchedTokens.size < 10_000) { "Workspace operation was already dispatched or tracking is full" }
                        Active(token, host, job).also { active = it; admitted = it }
                    }
                    try {
                        block { synchronized(admissionLock) {
                            invocation.job?.ensureActive()
                            check(!invalidated) { "Workspace authority revoked" }
                            check(!invocation.dispatched) { "Workspace operation was already dispatched" }
                            invocation.dispatched = true
                            dispatchedTokens.add(token)
                        } }
                    } catch (failure: Throwable) {
                        synchronized(admissionLock) { if (invocation.dispatched) uncertain = Uncertain(token, WeakReference(host)) }
                        throw failure
                    } finally {
                        synchronized(admissionLock) { if (active === invocation) active = null }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // Revocation is an interrupted operation, not user cancellation: the turn must keep
            // its reservation and surface the error until the host can prove it stopped.
            if (synchronized(admissionLock) { admitted?.revoked == true }) {
                throw IllegalStateException("Workspace authority revoked; operation outcome requires verification", cancelled)
            }
            throw cancelled
        }
    }

    /** Only the same live tracked host can provide stop evidence; unknown never permits replay. */
    suspend fun recover(host: TrackedWorkspaceHost) = recover(host, null)

    private suspend fun recover(host: TrackedWorkspaceHost, expectedGeneration: Long?) = mutex.withLock {
        val (pending, observedGeneration) = synchronized(admissionLock) {
            check(invalidated || uncertain != null) { "No uncertain workspace operation" }
            check(expectedGeneration == null || generation == expectedGeneration) { "Workspace authority changed during recovery" }
            uncertain to generation
        }
        if (pending != null) {
            check(pending.host.get() === host && host.protocolAvailable && host.status(pending.token) == TrackedWorkspaceStatus.STOPPED) {
                "Workspace operation stop is unverified"
            }
        }
        synchronized(admissionLock) {
            check(generation == observedGeneration && uncertain === pending) { "Workspace authority changed during recovery" }
            uncertain = null
            invalidated = false
        }
    }

    /** Request host cancellation without taking the workspace mutex; UNKNOWN remains fenced. */
    suspend fun cancelAndRecover(host: TrackedWorkspaceHost) {
        val (pending, observedGeneration) = synchronized(admissionLock) { uncertain to generation }
        if (pending != null) {
            check(pending.host.get() === host && host.protocolAvailable &&
                host.cancelAndAwaitStopped(pending.token) == TrackedWorkspaceStatus.STOPPED) {
                "Workspace operation stop is unverified"
            }
        }
        recover(host, observedGeneration)
    }
}
