/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Room-backed invalidation events for the existing Core tool inventory subscription. */
internal class KotlinRoomEventsBridge(
    private val delegate: CoreBridge,
    private val db: LocalDatabase,
) : CoreBridge by delegate {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = CopyOnWriteArrayList<(String, List<String>) -> Unit>()
    private var worker: Job? = null
    private var closed = false

    override fun subscribe(listener: (kind: String, ids: List<String>) -> Unit) {
        synchronized(lock) {
            check(!closed) { "Room event bridge is closed" }
            listeners.add(listener)
            if (worker == null) {
                val keys = listOf("learning_tools_enabled", "artifact_tools_enabled", "image_generation_enabled",
                    "scheduled_task_tool_enabled", "mcp_agent_delegation_enabled")
                val flows = keys.map { key -> db.rows().observeValue(key).map { it?.value }.distinctUntilChanged() }
                worker = scope.launch {
                    combine(flows) { values -> values.toList() }.distinctUntilChanged().collect {
                        for (subscriber in listeners) runCatching { subscriber("Other", emptyList()) }
                    }
                }
            }
        }
    }

    /** The database owner must call this and wait before closing Room. */
    suspend fun shutdown() = withContext(NonCancellable) {
        val job = synchronized(lock) {
            closed = true
            listeners.clear()
            worker.also { worker = null }
        }
        job?.cancelAndJoin()
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
}
