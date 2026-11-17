/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*

internal fun AcpPermissionPrompt.toUiEvent(): JsonObject = buildJsonObject {
    put("type", "Permission")
    put("options", JsonArray(options.map { option -> buildJsonObject {
        put("id", option.id); put("name", option.label); put("kind", option.kind)
    } }))
    put("tool", buildJsonObject { put("title", title); put("detail", detail) })
}

/** Projects bounded replay onto the existing background ACP polling contract. */
internal fun AcpEventSnapshot.toUiBatch(requestId: String): JsonObject {
    require(requestId.isNotBlank()) { "ACP request ID is required" }
    val retained = events.map { it.event }
    val pending = pendingPermission?.toUiEvent()
    val result = buildList {
        if (truncated) {
            add(buildJsonObject { put("type", "ReplayGap") })
            // A live approval must survive eviction, but must never be duplicated.
            if (pending != null && retained.none { it == pending }) add(pending)
        }
        addAll(retained)
    }
    return buildJsonObject {
        put("requestId", requestId)
        put("events", JsonArray(result))
        put("nextCursor", latestSequence)
        put("done", completed)
    }
}

/** Attachment and turn-bound controls; task admission still needs a lifecycle owner. */
internal class AcpUiObservationBridge(private val coordinator: AcpTurnCoordinator, private val workers: AcpWorkerManager) {
    suspend fun events(requestId: String, after: Long): JsonObject = coordinator.snapshot(requestId, after).toUiBatch(requestId)

    suspend fun bySession(sessionId: String): JsonObject? {
        val requestId = coordinator.activeTurns.value[sessionId] ?: return null
        return events(requestId, 0)
    }

    suspend fun decide(requestId: String, optionId: String): Boolean = coordinator.decide(requestId, optionId)

    suspend fun cancel(requestId: String): Boolean {
        val sessionId = coordinator.activeTurns.value.entries.firstOrNull { it.value == requestId }?.key ?: return false
        return workers.cancelTurn(sessionId, requestId)
    }
}
