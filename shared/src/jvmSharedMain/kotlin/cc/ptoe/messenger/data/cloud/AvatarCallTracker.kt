/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll

/** Tracks Ktor calls and their session callbacks until shutdown has cancelled and drained them. */
internal class AvatarCallTracker {
    internal class Admission(val request: Job) {
        val done = CompletableDeferred<Unit>()
    }
    private val lock = Any()
    private var closed = false
    private val calls = mutableSetOf<Admission>()
    private val callbacks = mutableSetOf<CompletableDeferred<Unit>>()

    fun admit(request: Job): Admission = synchronized(lock) {
        check(!closed) { "Cloud avatar client is closed" }
        check(calls.size < 64) { "Too many cloud avatar requests" }
        Admission(request).also { admission ->
            calls.add(admission)
            request.invokeOnCompletion { finish(admission) }
        }
    }

    fun checkOpen() = synchronized(lock) { check(!closed) { "Cloud avatar client is closed" } }

    fun bind(admission: Admission, response: Job) {
        response.invokeOnCompletion { finish(admission) }
    }

    fun finish(admission: Admission) = synchronized(lock) {
        calls.remove(admission)
        admission.done.complete(Unit)
        Unit
    }

    suspend fun <T> callback(block: suspend () -> T): T {
        val done = CompletableDeferred<Unit>()
        synchronized(lock) {
            check(!closed) { "Cloud avatar client is closed" }
            callbacks.add(done)
        }
        try { return block() }
        finally { synchronized(lock) { callbacks.remove(done); done.complete(Unit) } }
    }

    fun stop() {
        val jobs = synchronized(lock) { closed = true; calls.map { it.request } }
        jobs.forEach { it.cancel() }
    }

    suspend fun awaitStopped() {
        val (pendingCalls, pendingCallbacks) = synchronized(lock) {
            check(closed)
            calls.map { it.done } to callbacks.toList()
        }
        pendingCalls.joinAll()
        pendingCallbacks.joinAll()
    }
}
