/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import cc.ptoe.messenger.core.CoreBridge
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Exposes stored ACP transcript pages; all worker and mutation commands remain unavailable. */
internal class RoomAcpHistoryBridge(private val delegate: CoreBridge, private val store: RoomAcpConversationStore) : CoreBridge by delegate {
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
}
