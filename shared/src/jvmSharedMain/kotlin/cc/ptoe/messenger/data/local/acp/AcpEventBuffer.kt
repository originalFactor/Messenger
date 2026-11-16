/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal data class AcpBufferedEvent(val sequence: Long, val event: JsonObject)
internal data class AcpEventSnapshot(val events: List<AcpBufferedEvent>, val truncated: Boolean, val pendingPermission: AcpPermissionPrompt?, val completed: Boolean, val latestSequence: Long)

/** Per-turn bounded replay; observers detach without changing the producer's lifetime. */
internal class AcpEventBuffer(private val maxEvents: Int = 256, private val maxBytes: Int = 2 * 1024 * 1024) {
    init { require(maxEvents > 0 && maxBytes > 0) }
    private val gate = Mutex()
    private val events = ArrayDeque<Pair<AcpBufferedEvent, Int>>()
    private var bytes = 0L
    private var sequence = 0L
    private var permission: AcpPermissionPrompt? = null
    private var completed = false
    private val mutableChanges = MutableStateFlow(0L)
    val changes: StateFlow<Long> = mutableChanges

    suspend fun append(event: JsonObject): Long = gate.withLock {
        check(!completed) { "ACP turn already completed" }
        val text = event.toString()
        val size = text.encodeToByteArray().size
        require(size <= maxBytes) { "ACP event exceeds replay capacity" }
        check(sequence < Long.MAX_VALUE) { "ACP event sequence exhausted" }
        val copy = Json.parseToJsonElement(text) as JsonObject
        val current = AcpBufferedEvent(++sequence, copy)
        events.addLast(current to size); bytes += size
        while (events.size > maxEvents || bytes > maxBytes) {
            val evicted = events.removeFirst()
            bytes -= evicted.second
            evictedThrough = evicted.first.sequence
        }
        mutableChanges.value = sequence
        sequence
    }

    suspend fun permission(value: AcpPermissionPrompt?) = gate.withLock {
        check(!completed || value == null) { "ACP turn already completed" }
        permission = value?.copy(options = value.options.toList())
        check(sequence < Long.MAX_VALUE) { "ACP event sequence exhausted" }
        mutableChanges.value = ++sequence
    }

    suspend fun complete() = gate.withLock {
        if (!completed) {
            check(sequence < Long.MAX_VALUE) { "ACP event sequence exhausted" }
            completed = true; permission = null; mutableChanges.value = ++sequence
        }
    }

    suspend fun snapshot(after: Long): AcpEventSnapshot = gate.withLock {
        require(after >= 0 && after <= sequence) { "Invalid ACP replay cursor" }
        // Sequence values can also represent permission changes; only actual evictions truncate replay.
        val truncated = after < evictedThrough
        AcpEventSnapshot(events.map { it.first }.filter { it.sequence > after }, truncated, permission, completed, sequence)
    }

    private var evictedThrough = 0L
}
