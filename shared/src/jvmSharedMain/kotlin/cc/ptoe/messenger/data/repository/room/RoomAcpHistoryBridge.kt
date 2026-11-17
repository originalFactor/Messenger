/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.AcpReplayPage
import cc.ptoe.messenger.core.AcpReplaySnapshot
import cc.ptoe.messenger.data.local.acp.RoomAcpReplaySnapshots
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Exposes stored ACP transcript pages; all worker and mutation commands remain unavailable. */
internal class RoomAcpHistoryBridge(private val delegate: CoreBridge, private val store: RoomAcpConversationStore,
    private val snapshots: RoomAcpReplaySnapshots, private val deletionAvailable: Boolean) : CoreBridge by delegate {
    override suspend fun controlCommand(requestId: String, command: JsonObject, onEvent: (JsonObject) -> Unit) {
        val action = (command["action"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val session = (command["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (action == "history" && session?.startsWith("acp:") == true) {
            val before = when (val cursor = command["before"]) {
                null, JsonNull -> null
                is JsonPrimitive -> cursor.takeIf { it.isString }?.contentOrNull ?: error("Invalid ACP history cursor")
                else -> error("Invalid ACP history cursor")
            }
            onEvent(store.history(session, before))
        } else delegate.controlCommand(requestId, command, onEvent)
    }

    override suspend fun localAcpHistory(sessionId: String, before: String?): JsonObject = store.history(sessionId, before)
    override val supportsAcpReplaySnapshots: Boolean get() = true
    override val supportsAcpReplayDeletion: Boolean get() = deletionAvailable
    override suspend fun listAcpReplaySnapshots(sessionId: String): List<AcpReplaySnapshot> =
        snapshots.list(sessionId).map { AcpReplaySnapshot(it.id, it.capturedAt, it.eventCount, it.byteCount) }
    override suspend fun readAcpReplaySnapshot(sessionId: String, snapshotId: String, after: Int): AcpReplayPage =
        snapshots.page(sessionId, snapshotId, after).let { AcpReplayPage(it.source, it.events, it.next) }
    override suspend fun deleteAcpReplaySnapshot(sessionId: String, snapshotId: String) {
        check(deletionAvailable) { "ACP replay deletion requires an inactive-session owner" }
        snapshots.delete(sessionId, snapshotId)
    }
}
