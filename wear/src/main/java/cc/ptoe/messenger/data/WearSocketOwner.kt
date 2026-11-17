/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data

/** Serializes connection state and its callbacks across reconnects and timeouts. */
internal class WearSocketOwner<S : Any> {
    class Attempt internal constructor(internal val onStopped: () -> Unit)
    private val lock = Any()
    private var attempt: Attempt? = null
    private var socket: S? = null
    private var isOpen = false

    fun begin(onStopped: () -> Unit = {}): Attempt = synchronized(lock) {
        check(attempt == null) { "A socket attempt is already active" }
        Attempt(onStopped).also { attempt = it }
    }

    fun attach(candidate: Attempt, value: S): Boolean = synchronized(lock) {
        if (attempt !== candidate || (socket != null && socket !== value)) return false
        socket = value
        true
    }

    fun opened(candidate: Attempt, value: S, onOpen: () -> Unit): Boolean = synchronized(lock) {
        if (attempt !== candidate || isOpen || (socket != null && socket !== value)) return false
        socket = value
        isOpen = true
        onOpen()
        true
    }

    fun closed(candidate: Attempt, value: S, onClose: () -> Unit): Boolean = synchronized(lock) {
        if (attempt !== candidate || (socket != null && socket !== value)) return false
        attempt = null
        socket = null
        isOpen = false
        onClose()
        true
    }

    fun cancel(candidate: Attempt, onCancel: (S?) -> Unit): Boolean = synchronized(lock) {
        if (attempt !== candidate) return false
        val previous = socket
        attempt = null
        socket = null
        isOpen = false
        onCancel(previous)
        true
    }

    fun stop(onStop: (S?) -> Unit) = synchronized(lock) {
        val previous = socket
        val previousAttempt = attempt
        attempt = null
        socket = null
        isOpen = false
        onStop(previous)
        previousAttempt?.onStopped?.invoke()
    }

    fun <T> withOpen(block: (S) -> T): T? = synchronized(lock) { if (isOpen) socket?.let(block) else null }

    fun ifActive(candidate: Attempt, block: () -> Unit): Boolean = synchronized(lock) {
        if (attempt !== candidate) return false
        block()
        true
    }

    fun ifOpen(candidate: Attempt, value: S, block: () -> Unit) = synchronized(lock) {
        if (isOpen && attempt === candidate && socket === value) block()
    }
}
