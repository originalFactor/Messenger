/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.lang.ref.WeakReference
import java.util.WeakHashMap
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
    private data class Uncertain(val token: String, val host: WeakReference<TrackedWorkspaceHost>)
    private var uncertain: Uncertain? = null
    private val dispatchedTokens = mutableSetOf<String>()

    internal fun requireOwner(candidate: LocalDatabase) {
        require(candidate === owner.get()) { "Workspace fence belongs to another Room owner" }
    }

    /** All authority-changing writers must use this gate, including Cloud restore. */
    suspend fun <T> mutate(block: suspend () -> T): T = mutex.withLock {
        check(uncertain == null) { "Workspace execution requires recovery" }
        block()
    }

    /** An exception after dispatch is unknown until the exact host proves the token stopped. */
    internal suspend fun <T> execute(token: String, host: TrackedWorkspaceHost,
        block: suspend (markDispatched: () -> Unit) -> T): T = mutex.withLock {
        require(token.matches(Regex("[0-9a-f]{64}"))) { "Invalid workspace operation token" }
        check(uncertain == null) { "Workspace execution requires recovery" }
        check(token !in dispatchedTokens && dispatchedTokens.size < 10_000) { "Workspace operation was already dispatched or tracking is full" }
        var dispatched = false
        try { block {
            check(!dispatched) { "Workspace operation was already dispatched" }
            dispatched = true
            dispatchedTokens.add(token)
        } }
        catch (failure: Throwable) {
            if (dispatched) uncertain = Uncertain(token, WeakReference(host))
            throw failure
        }
    }

    /** Only the same live tracked host can provide stop evidence; unknown never permits replay. */
    suspend fun recover(host: TrackedWorkspaceHost) = mutex.withLock {
        val pending = checkNotNull(uncertain) { "No uncertain workspace operation" }
        check(pending.host.get() === host && host.protocolAvailable && host.status(pending.token) == TrackedWorkspaceStatus.STOPPED) {
            "Workspace operation stop is unverified"
        }
        uncertain = null
    }
}
