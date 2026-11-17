/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import cc.ptoe.messenger.data.chat.RoomKotlinTurnStore
import cc.ptoe.messenger.data.local.acp.AcpUiObservationBridge
import cc.ptoe.messenger.domain.chat.KotlinTurnPhase
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Live, bounded notification projection without exposing stored provider requests. */
internal class RoomRemoteTurnSnapshots(
    private val turns: RoomKotlinTurnStore,
    private val acp: suspend (String) -> JsonObject?,
) : RoomRemoteTaskSnapshots {
    constructor(turns: RoomKotlinTurnStore, acp: AcpUiObservationBridge) : this(turns, acp::bySession)
    override suspend fun snapshot(conversationId: String): RoomRemoteTaskSnapshot {
        if (conversationId.startsWith("acp:")) return acpSnapshot(conversationId)
        val id = turns.latestTurn(conversationId) ?: return RoomRemoteTaskSnapshot(false)
        val turn = turns.observePresentation(id, conversationId).first()
        val active = turn.phase !in setOf(KotlinTurnPhase.FINISHED, KotlinTurnPhase.FAILED, KotlinTurnPhase.CANCELLED, KotlinTurnPhase.INTERRUPTED_TOOL)
        val events = buildList {
            if (active && turn.content.isNotBlank()) add(buildJsonObject { put("type", "TextDelta"); put("text", limited(turn.content, 8_000)) })
            turn.summaryFailure?.let { add(buildJsonObject { put("type", "SummaryFailed"); put("message", limited(it, 3_000)) }) }
            turn.approvalFailure?.let { add(buildJsonObject { put("type", "ApprovalFailed"); put("message", limited(it, 3_000)) }) }
            turn.titleFailure?.let { add(buildJsonObject { put("type", "TitleFailed"); put("message", limited(it, 3_000)) }) }
            if (turn.phase == KotlinTurnPhase.FAILED || turn.phase == KotlinTurnPhase.INTERRUPTED_TOOL) add(buildJsonObject {
                put("type", "Error"); put("message", limited(turn.detail ?: "Chat turn failed", 3_000))
            })
            val call = turn.pendingTool?.takeIf { active && turn.phase == KotlinTurnPhase.WAITING_APPROVAL }
            if (call != null) add(buildJsonObject {
                put("type", "Permission")
                put("options", JsonArray(listOf(
                    buildJsonObject { put("id", "approve:${call.callId}"); put("name", "Approve") },
                    buildJsonObject { put("id", "deny:${call.callId}"); put("name", "Deny") },
                )))
                put("tool", buildJsonObject { put("title", limited(call.name, 180)); put("detail", limited(call.arguments, 1_500)) })
            })
        }
        return RoomRemoteTaskSnapshot(active, id, events)
    }

    private suspend fun acpSnapshot(id: String): RoomRemoteTaskSnapshot {
        val batch = acp(id) ?: return RoomRemoteTaskSnapshot(false)
        val request = batch.text("requestId") ?: error("ACP task has no identity")
        val done = (batch["done"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("Invalid ACP task status")
        if (done) return RoomRemoteTaskSnapshot(false, request)
        val source = (batch["events"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val text = limited(source.filter { it.text("type") == "TextDelta" }.joinToString("") { it.text("text").orEmpty() }, 8_000)
        val permission = source.lastOrNull { it.text("type") == "Permission" }
        val events = buildList {
            if (text.isNotEmpty()) add(buildJsonObject { put("type", "TextDelta"); put("text", text) })
            if (permission != null) add(permission)
        }
        return RoomRemoteTaskSnapshot(true, request, events)
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun limited(value: String, limit: Int): String =
    if (value.codePointCount(0, value.length) <= limit) value else value.substring(0, value.offsetByCodePoints(0, limit)) + "…"
