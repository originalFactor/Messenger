/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Detached input workers still obey the lifecycle of the ACP turn that owns them. */
internal fun requireAcpTurnActive(owner: Job) = owner.ensureActive()

/** Cancel input and stop its direct child before waiting for a possibly blocked Binder write. */
internal suspend fun finishAcpInput(input: Job, timeoutMillis: Long, stop: suspend () -> Unit) = withContext(NonCancellable) {
    input.cancel()
    val stopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val stopping = stopScope.async { stop() }
    val stopResult = withTimeoutOrNull(timeoutMillis) { runCatching { stopping.await() } }
    val stopFailure = stopResult?.exceptionOrNull()
        ?: if (stopResult == null) IllegalStateException("ACP stop did not finish; process cleanup is unverified.") else null
    val joined = withTimeoutOrNull(timeoutMillis) { input.join(); true } ?: false
    stopScope.cancel()
    if (!joined) {
        val failure = IllegalStateException("ACP input did not finish; process cleanup is unverified.")
        stopFailure?.let(failure::addSuppressed)
        throw failure
    }
    stopFailure?.let { throw it }
}

/** Tracks only the exact Binder that started each ACP session, never a replacement connection. */
internal class AcpRuntimeConnections<B : Any> {
    internal class Session<B : Any>(val id: Int, val binder: B, val notify: (Throwable?) -> Unit) {
        @Volatile var terminalError: Throwable? = null
        val ended = CompletableDeferred<Unit>()
        var stopRequested = false
    }
    private val lock = Any()
    private val sessions = mutableMapOf<Int, Session<B>>()

    fun register(id: Int, binder: B, notify: (Throwable?) -> Unit): Session<B> = synchronized(lock) {
        check(id !in sessions) { "Duplicate ACP Runtime session" }
        Session(id, binder, notify).also { sessions[id] = it }
    }

    fun beginStop(session: Session<B>) = synchronized(lock) {
        if (sessions[session.id] === session) session.stopRequested = true
    }

    fun closed(session: Session<B>, exitCode: Int = 0) {
        val failure = synchronized(lock) {
            if (exitCode != 0 && !session.stopRequested) IllegalStateException("ACP process exited with status $exitCode.") else null
        }
        end(session, failure)
    }
    fun lost(session: Session<B>) = end(session, IllegalStateException("Messenger Runtime disconnected; ACP process stop is unverified."))
    fun abandon(session: Session<B>) = synchronized(lock) { if (sessions[session.id] === session) sessions.remove(session.id); Unit }

    /** Wait for this session's own exit callback or connection loss, never a replacement Binder. */
    suspend fun awaitEnd(session: Session<B>, timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0)
        return withTimeoutOrNull(timeoutMillis) { session.ended.await(); true } ?: false
    }

    /** An ID rejected at start may belong to someone else; never stop it. */
    suspend fun stopAndAwaitEnd(
        session: Session<B>, accepted: Boolean, timeoutMillis: Long, stop: () -> Unit,
    ) {
        if (!accepted || session.ended.isCompleted) return
        beginStop(session)
        stop()
        check(awaitEnd(session, timeoutMillis)) { "ACP exit callback was not received" }
    }

    fun disconnect(binder: B) {
        val affected = synchronized(lock) { sessions.values.filter { it.binder === binder } }
        affected.forEach(::lost)
    }

    private fun end(session: Session<B>, failure: Throwable?) {
        val admitted = synchronized(lock) {
            if (sessions[session.id] !== session) false else {
                session.terminalError = failure
                sessions.remove(session.id)
                true
            }
        }
        if (admitted) {
            session.ended.complete(Unit)
            session.notify(failure)
        }
    }
}
