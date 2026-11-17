/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.util.randomUuid
import java.security.MessageDigest
import kotlinx.serialization.json.*

private const val SESSIONS = "local_acp_sessions"
private const val TURN = "kotlin_acp_transcript_v1:"

/** The guard holds the session reservation for the whole database transaction. */
internal interface AcpActiveTurnGuard {
    suspend fun <T> withActiveTurn(sessionId: String, turnId: String, action: suspend () -> T): T
}

/** Preserves the existing Core KV record and unknown fields; no ACP content is sent to Cloud. */
internal class RoomAcpSessionJournal(private val db: LocalDatabase, private val guard: AcpActiveTurnGuard) {
    /** Writes user input before sending an ACP prompt; repeated admission never duplicates it. */
    suspend fun begin(sessionId: String, turnId: String, text: String, content: JsonArray, now: Long,
        replay: AcpCompletedReplay? = null, binding: AcpWorkerBinding? = null) = guard.withActiveTurn(sessionId, turnId) {
        require(turnId.isNotBlank() && turnId.length <= 256 && now >= 0 && content.isNotEmpty())
        require(text.encodeToByteArray().size <= 256 * 1024 && content.toString().encodeToByteArray().size <= 4 * 1024 * 1024) { "ACP user input exceeds limit" }
        val fingerprint = sha256(JsonObject(mapOf("text" to JsonPrimitive(text), "content" to content)).toString())
        db.useWriterConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val rows = db.rows()
            val root = rows.getValue(SESSIONS)?.value?.let { Json.parseToJsonElement(it) as? JsonObject } ?: error("ACP sessions are unavailable")
            val record = root[sessionId] as? JsonObject ?: error("ACP session not found")
            check((record["id"] as? JsonPrimitive)?.contentOrNull == sessionId) { "ACP session identity changed" }
            val existing = rows.getValue(TURN + turnId)?.value?.let { Json.parseToJsonElement(it).jsonObject }
            if (existing != null) {
                check(existing["sessionId"]?.jsonPrimitive?.contentOrNull == sessionId && existing["fingerprint"]?.jsonPrimitive?.contentOrNull == fingerprint) { "ACP turn identity reused" }
                return@withTransaction
            }
            var messages = record["messages"] as? JsonArray ?: error("Invalid ACP transcript")
            if (replay != null) {
                require(replay.messages.toString().encodeToByteArray().size <= 16 * 1024 * 1024) { "ACP replay projection exceeds limit" }
                val bound = requireNotNull(binding) { "ACP replay binding is unavailable" }
                require(bound.conversationId == sessionId) { "ACP replay belongs to another session" }
                RoomAcpReplaySnapshots.record(rows, bound, replay.updates, now)
                if (messages.isEmpty()) messages = replay.messages
            }
            val row = buildJsonObject { put("id", randomUuid()); put("role", "user"); put("text", text); put("status", "sent"); put("timestamp", now); put("content", content) }
            val title = if (messages.isEmpty() && text.isNotBlank()) text.take(80) else (record["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val updated = JsonObject(record + mapOf("title" to JsonPrimitive(title), "updated_at" to JsonPrimitive(now), "messages" to JsonArray(messages + row)))
            val marker = buildJsonObject { put("sessionId", sessionId); put("fingerprint", fingerprint); put("state", "active") }
            rows.upsertValues(listOf(KeyValueRow(SESSIONS, JsonObject(root + (sessionId to updated)).toString()), KeyValueRow(TURN + turnId, marker.toString())))
        } }
    }

    /** Writes the terminal assistant/events and any visible error exactly once. */
    suspend fun finish(sessionId: String, turnId: String, text: String, events: JsonArray, error: String?, now: Long,
        content: JsonArray = JsonArray(emptyList())) = guard.withActiveTurn(sessionId, turnId) {
        require(now >= 0 && text.encodeToByteArray().size <= 4 * 1024 * 1024 && events.toString().encodeToByteArray().size <= 8 * 1024 * 1024 &&
            content.toString().encodeToByteArray().size <= 8 * 1024 * 1024 &&
            (error == null || error.encodeToByteArray().size <= 64 * 1024)) { "ACP transcript exceeds limit" }
        db.useWriterConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val rows = db.rows()
            val marker = rows.getValue(TURN + turnId)?.value?.let { Json.parseToJsonElement(it).jsonObject } ?: error("ACP turn was not admitted")
            check(marker["sessionId"]?.jsonPrimitive?.contentOrNull == sessionId) { "ACP turn belongs to another session" }
            if (marker["state"]?.jsonPrimitive?.contentOrNull == "finished") return@withTransaction
            check(marker["state"]?.jsonPrimitive?.contentOrNull == "active") { "Invalid ACP transcript state" }
            val root = rows.getValue(SESSIONS)?.value?.let { Json.parseToJsonElement(it) as? JsonObject } ?: error("ACP sessions are unavailable")
            val record = root[sessionId] as? JsonObject ?: error("ACP session not found")
            val messages = (record["messages"] as? JsonArray)?.toMutableList() ?: error("Invalid ACP transcript")
            if (text.isNotEmpty() || events.isNotEmpty()) messages += buildJsonObject {
                put("id", randomUuid()); put("role", "assistant"); put("text", text); put("status", "sent"); put("timestamp", now); put("events", events)
                if (content.isNotEmpty()) put("content", content)
            }
            if (error != null) messages += buildJsonObject {
                put("id", randomUuid()); put("role", "assistant"); put("text", error); put("status", "error"); put("timestamp", now)
            }
            val updated = JsonObject(record + mapOf("updated_at" to JsonPrimitive(now), "messages" to JsonArray(messages)))
            rows.upsertValues(listOf(KeyValueRow(SESSIONS, JsonObject(root + (sessionId to updated)).toString()),
                KeyValueRow(TURN + turnId, JsonObject(marker + ("state" to JsonPrimitive("finished"))).toString())))
        } }
    }
    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
