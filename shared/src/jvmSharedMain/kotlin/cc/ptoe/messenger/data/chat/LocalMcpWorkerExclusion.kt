/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomShutdownState
import java.lang.ref.WeakReference
import kotlinx.coroutines.Job

/**
 * In-process evidence only: a specific previous Room owner has stopped its local worker tree.
 * This deliberately says nothing about remote effects and cannot survive a process/Room reopen.
 * Every participating backend must still be enclosed by the same exclusive Room ownership.
 */
internal object LocalMcpWorkerExclusion {
    internal data class Key(val owner: String, val turnId: String, val callId: String)
    private data class Evidence(val db: WeakReference<LocalDatabase>, val key: Key,
        val job: WeakReference<Job>, val shutdown: WeakReference<RoomManagedShutdown>, var stopped: Boolean = false)
    private val entries = mutableListOf<Evidence>()

    private fun removeExpired() { entries.removeAll { it.db.get() == null || (!it.stopped && it.shutdown.get() == null) } }

    /** Called from that backend's shutdown action, after joining its actual worker tree. */
    fun record(db: LocalDatabase, key: Key, job: Job, shutdown: RoomManagedShutdown) = synchronized(entries) {
        check(job.isCompleted && shutdown.state.value == RoomShutdownState.Stopping) { "Local MCP worker has not stopped" }
        require(key.owner.isNotBlank() && key.turnId.isNotBlank() && key.callId.isNotBlank()) { "Invalid MCP worker identity" }
        removeExpired()
        val existing = entries.singleOrNull { it.db.get() === db && it.key == key }
        check(existing == null || (existing.job.get() === job && existing.shutdown.get() === shutdown)) { "MCP worker identity changed" }
        check(existing != null || entries.count { it.db.get() === db } < 10_000) {
            "Too many interrupted MCP calls"
        }
        if (existing == null) {
            val evidence = Evidence(WeakReference(db), key, WeakReference(job), WeakReference(shutdown))
            entries.add(evidence)
            // Stopped is terminal, as is job completion. The callback captures only weak
            // owner references, so successful proof survives collection of the old slice.
            shutdown.onStopped { synchronized(entries) { if (evidence in entries) evidence.stopped = true } }
        }
    }

    /** Exact Room object, owner, turn and call must still match live local stop evidence. */
    fun provesStopped(db: LocalDatabase, key: Key): Boolean = synchronized(entries) {
        removeExpired()
        val evidence = entries.singleOrNull { it.db.get() === db && it.key == key } ?: return@synchronized false
        evidence.stopped
    }

    fun forget(db: LocalDatabase, key: Key) = synchronized(entries) {
        entries.removeAll { it.db.get() == null || (it.db.get() === db && it.key == key) }
        Unit
    }
}
