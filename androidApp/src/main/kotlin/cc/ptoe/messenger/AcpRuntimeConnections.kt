/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger

/** Tracks only the exact Binder that started each ACP session, never a replacement connection. */
internal class AcpRuntimeConnections<B : Any> {
    internal class Session<B : Any>(val id: Int, val binder: B, val notify: (Throwable?) -> Unit) {
        @Volatile var terminalError: Throwable? = null
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
        if (admitted) session.notify(failure)
    }
}
