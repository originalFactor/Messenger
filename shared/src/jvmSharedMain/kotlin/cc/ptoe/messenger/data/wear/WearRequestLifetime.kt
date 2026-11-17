/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.wear

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One service instance owns every request until its coroutine and cleanup have completed. */
internal class WearRequestLifetime(private val onStop: () -> Unit) {
    private val lock = Any()
    private val root = SupervisorJob()
    private val scope = CoroutineScope(root + Dispatchers.IO)
    private var stopped = false

    val accepting: Boolean get() = synchronized(lock) { !stopped }

    fun launch(block: suspend CoroutineScope.() -> Unit): Boolean = synchronized(lock) {
        if (stopped) false else { scope.launch(block = block); true }
    }

    fun requestStop() {
        val first = synchronized(lock) { if (stopped) false else { stopped = true; true } }
        if (first) {
            root.cancel()
            onStop()
        }
    }

    suspend fun awaitStopped() { root.join() }
}

/** Retains destroyed instances until their requests have actually finished. */
internal class WearRequestRegistry {
    private val lock = Any()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = mutableSetOf<WearRequestLifetime>()
    private var closed = false
    private var transportCleanup = CompletableDeferred<Unit>().apply { complete(Unit) }

    fun register(owner: WearRequestLifetime): Boolean = synchronized(lock) {
        if (closed) false else active.add(owner)
    }

    fun retire(owner: WearRequestLifetime) {
        owner.requestStop()
        cleanup.launch {
            owner.awaitStopped()
            synchronized(lock) { active.remove(owner) }
        }
    }

    /** Register synchronously so a replacement service cannot race the old socket teardown. */
    fun cleanupTransport(block: suspend () -> Unit) {
        val (previous, current) = synchronized(lock) {
            val next = CompletableDeferred<Unit>()
            (transportCleanup to next).also { transportCleanup = next }
        }
        cleanup.launch {
            try { previous.await(); block(); current.complete(Unit) }
            catch (failure: Throwable) { current.completeExceptionally(failure) }
        }
    }

    suspend fun awaitTransportCleanup() {
        val pending = synchronized(lock) { transportCleanup }
        pending.await()
    }

    /** Call before the shared backend closes; no later service may acquire it in this process. */
    suspend fun stopAndJoin() = withContext(NonCancellable) {
        val owners = synchronized(lock) { closed = true; active.toList() }
        owners.forEach { it.requestStop() }
        owners.forEach { it.awaitStopped() }
        synchronized(lock) { active.removeAll(owners.toSet()) }
    }
}
