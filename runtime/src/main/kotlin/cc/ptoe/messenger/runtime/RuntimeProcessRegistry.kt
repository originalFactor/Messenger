/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.runtime

import java.util.concurrent.CountDownLatch

/** Tracks the exact direct child for each admitted session; it cannot account for descendants. */
internal class RuntimeProcessRegistry<T : Any>(private val close: (T) -> Unit) {
    internal class Entry<T : Any>(val id: Int) {
        val ready = CountDownLatch(1)
        var session: T? = null
        var stopping = false
        var finished = false
        var callbacks = 0
        var stopComplete = false
        var launchComplete = false
        var stopFailure: Throwable? = null
        val stopped = CountDownLatch(1)
    }

    private val lock = java.lang.Object()
    private val sessions = mutableMapOf<Int, Entry<T>>()
    private var closed = false

    /** A duplicate ID never replaces a child that may still be launching or shutting down. */
    fun reserve(id: Int): Entry<T>? = synchronized(lock) {
        if (closed || sessions.containsKey(id)) null else Entry<T>(id).also { sessions[id] = it }
    }

    /** An exceptional launch may have left a child behind; keep its ID unavailable. */
    fun failLaunch(entry: Entry<T>, failure: Throwable) {
        synchronized(lock) { entry.stopFailure = failure; entry.launchComplete = true }
        entry.ready.countDown()
    }

    /** A stopped, superseded or already-exited start closes its direct child before signalling readiness. */
    fun publish(entry: Entry<T>, session: T?): Boolean {
        val accepted = synchronized(lock) {
            if (session != null && sessions[entry.id] === entry && !entry.stopping && !entry.finished && !closed) {
                entry.session = session
                true
            } else false
        }
        try {
            if (!accepted && session != null) close(session)
        } catch (failure: Throwable) {
            synchronized(lock) { entry.stopFailure = failure }
            throw failure
        } finally {
            synchronized(lock) {
                if (session == null) entry.finished = true
                entry.launchComplete = true
                retireIfSettled(entry)
            }
            entry.ready.countDown()
        }
        return accepted
    }

    /** Keep an admitted callback's generation reserved while invoking Binder outside the lock. */
    fun deliver(entry: Entry<T>, callback: () -> Unit) {
        val admitted = synchronized(lock) {
            if (sessions[entry.id] !== entry || entry.finished || entry.stopping) false
            else { entry.callbacks++; true }
        }
        if (!admitted) return
        try { callback() } finally { callbackFinished(entry) }
    }

    /** Close callback and all previously admitted output callbacks finish before ID reuse. */
    fun finished(entry: Entry<T>, callback: () -> Unit = {}) {
        val admitted = synchronized(lock) {
            if (sessions[entry.id] !== entry || entry.finished) false
            else { entry.finished = true; entry.callbacks++; true }
        }
        if (!admitted) return
        try { callback() } finally { callbackFinished(entry) }
    }

    private fun callbackFinished(entry: Entry<T>) = synchronized(lock) {
        entry.callbacks--
        retireIfSettled(entry)
        Unit
    }

    private fun retireIfSettled(entry: Entry<T>) {
        if (entry.finished && entry.launchComplete && entry.callbacks == 0 && (!entry.stopping || entry.stopComplete) &&
            entry.stopFailure == null && sessions[entry.id] === entry) {
            sessions.remove(entry.id)
            lock.notifyAll()
        }
    }

    fun get(id: Int): T? = synchronized(lock) { sessions[id]?.takeUnless { it.stopping }?.session }

    /** Wait for a pending launch, then close its direct child before returning. */
    fun stop(id: Int) { stopEntry(null, id) }

    fun stop(entry: Entry<T>) { stopEntry(entry, entry.id) }

    private fun stopEntry(expected: Entry<T>?, id: Int) {
        val (entry, leader) = synchronized(lock) {
            val entry = sessions[id] ?: return
            if (expected != null && entry !== expected) return
            entry to (!entry.stopping).also { entry.stopping = true }
        }
        if (!leader) {
            entry.stopped.await()
            synchronized(lock) { entry.stopFailure }?.let { throw IllegalStateException("Direct child stop failed", it) }
            return
        }
        try {
            entry.ready.await()
            synchronized(lock) { entry.stopFailure }?.let { throw it }
            val session = synchronized(lock) { entry.session }
            if (session != null) close(session)
            synchronized(lock) {
                // An admitted Binder callback may still be in flight when the child exits.
                // Keep its generation reserved until it returns.
                entry.stopComplete = true
                retireIfSettled(entry)
            }
        } catch (failure: Throwable) {
            synchronized(lock) { entry.stopFailure = failure }
            throw failure
        } finally { entry.stopped.countDown() }
    }

    /** Close admission before draining; launchers already in flight must finish publishing. */
    fun stopAll() {
        val ids = synchronized(lock) { closed = true; sessions.keys.toList() }
        var failure: Throwable? = null
        for (id in ids) try { stop(id) } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    /** Called off the Android main thread after stopAll; callback completion may be unbounded. */
    fun awaitDrained() = synchronized(lock) {
        check(closed) { "Admission must close before waiting for callbacks" }
        while (sessions.isNotEmpty()) lock.wait()
    }
}

/** Prevents a recreated service from admitting work while a previous instance drains. */
internal class RuntimeSessionShutdownGate {
    private val lock = Any()
    private var pending = 0
    fun <T> admit(block: () -> T): T? = synchronized(lock) { if (pending != 0) null else block() }
    fun begin() = synchronized(lock) { pending++ }
    fun complete() = synchronized(lock) { check(pending > 0); pending-- }
}
