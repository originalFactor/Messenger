/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Process-local evidence for synchronous workspace calls. A call is STOPPED only after its
 * invocation returned normally on the same connection generation. An exception, disconnect or
 * unknown token is UNKNOWN. Evidence is deliberately not persisted across process restarts.
 */
class InMemoryWorkspaceTracker {
    private data class Entry(val generation: Long, val job: Job?,
        val completed: CompletableDeferred<Unit> = CompletableDeferred(), var state: TrackedWorkspaceStatus)
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private var generation = 0L

    fun disconnected() = synchronized(lock) {
        generation++
        entries.values.forEach { it.state = TrackedWorkspaceStatus.UNKNOWN; it.completed.complete(Unit) }
    }

    fun status(token: String): TrackedWorkspaceStatus = synchronized(lock) {
        entries[token]?.state ?: TrackedWorkspaceStatus.UNKNOWN
    }

    /** Request cancellation, but report STOPPED only after the actual synchronous call returns.
     * Disconnect and failed calls never provide stop evidence. Waiting may take indefinitely. */
    suspend fun cancelAndAwaitStopped(token: String): TrackedWorkspaceStatus {
        val entry = synchronized(lock) { entries[token] } ?: return TrackedWorkspaceStatus.UNKNOWN
        if (status(token) == TrackedWorkspaceStatus.RUNNING) {
            entry.job?.cancel(CancellationException("Workspace operation cancelled"))
            entry.completed.await()
        }
        return status(token)
    }

    suspend fun <T> run(token: String, operation: suspend () -> T): T {
        require(token.isNotBlank() && token.length <= 512) { "Invalid workspace operation token" }
        val parent = currentCoroutineContext()[Job]
        parent?.ensureActive()
        return withContext(NonCancellable) {
            val entry = synchronized(lock) {
                check(token !in entries) { "Workspace operation token was already used" }
                check(entries.size < 10_000) { "Workspace operation tracking capacity exceeded" }
                Entry(generation, parent, state = TrackedWorkspaceStatus.RUNNING).also { entries[token] = it }
            }
            try {
                parent?.ensureActive()
                val result = operation()
                synchronized(lock) {
                    check(entry.generation == generation && entry.state == TrackedWorkspaceStatus.RUNNING) {
                        "Workspace operation outcome is unknown after Runtime disconnect"
                    }
                    entry.state = TrackedWorkspaceStatus.STOPPED
                }
                result
            } catch (failure: Throwable) {
                synchronized(lock) { entry.state = TrackedWorkspaceStatus.UNKNOWN }
                throw failure
            } finally {
                entry.completed.complete(Unit)
            }
        }
    }
}
