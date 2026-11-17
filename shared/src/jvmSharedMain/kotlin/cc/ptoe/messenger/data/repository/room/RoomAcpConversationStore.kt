/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0. See https://www.apache.org/licenses/LICENSE-2.0
 */
package cc.ptoe.messenger.data.repository.room

import cc.ptoe.messenger.data.local.acp.RoomAcpReplaySnapshots

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.domain.model.Conversation
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val ACP_SESSIONS = "local_acp_sessions"

/** Must exclude new turns, reject active ones and stop any idle worker on deletion. */
interface AcpSessionMutationGate {
    suspend fun <T> withInactiveSession(id: String, deleting: Boolean, action: suspend () -> T): T
}

/** Preserves the Core ACP record, including unknown fields and transcript content. */
class RoomAcpConversationStore(private val db: LocalDatabase, private val gate: AcpSessionMutationGate) : AcpConversationStore {
    override fun observeAll() = db.rows().observeValue(ACP_SESSIONS).map { row ->
        decode(row?.value).map { (id, value) -> project(id, value) }
    }

    override suspend fun messagesForSync(id: String): List<cc.ptoe.messenger.domain.model.Message> {
        val record = decode(db.rows().getValue(ACP_SESSIONS)?.value)[id] ?: error("ACP session not found")
        val messages = record["messages"] as? JsonArray ?: error("Invalid ACP transcript")
        return cc.ptoe.messenger.data.repository.projectAcpHistory(id, messages)
    }

    /** Read stored ACP history without starting or asserting ownership of a worker. */
    suspend fun history(id: String, before: String?): JsonObject {
        require(id.startsWith("acp:") && id.length in 5..256) { "Invalid ACP session" }
        val record = decode(db.rows().getValue(ACP_SESSIONS)?.value)[id] ?: error("ACP session not found")
        val rows = record["messages"] as? JsonArray ?: error("Invalid ACP transcript")
        val end = before?.let { cursor -> rows.indexOfFirst { (it as? JsonObject)?.text("id") == cursor }.takeIf { it >= 0 } }
            ?: rows.size
        val page = rows.subList((end - 20).coerceAtLeast(0), end)
        val next = if (page.size < end) {
            (page.firstOrNull() as? JsonObject)?.text("id")?.takeIf { it.isNotBlank() }
                ?: error("Invalid ACP history cursor")
        } else null
        return buildJsonObject {
            put("type", "History")
            put("messages", JsonArray(page))
            put("before", next?.let(::JsonPrimitive) ?: JsonNull)
            put("writable", JsonNull)
        }
    }

    override suspend fun update(conversation: Conversation) {
        require(conversation.id.startsWith("acp:"))
        gate.withInactiveSession(conversation.id, false) {
            db.useWriterConnection { connection ->
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    val sessions = decode(db.rows().getValue(ACP_SESSIONS)?.value).toMutableMap()
                    val old = sessions[conversation.id] ?: error("ACP session not found")
                    val projected = project(conversation.id, old)
                    require(conversation.agentId == projected.agentId && conversation.projectId == projected.projectId) {
                        "ACP conversations keep their original agent and workspace"
                    }
                    sessions[conversation.id] = JsonObject(old + mapOf("title" to JsonPrimitive(conversation.title), "updated_at" to JsonPrimitive(currentTimeMillis())))
                    db.rows().upsertValues(listOf(KeyValueRow(ACP_SESSIONS, JsonObject(sessions).toString())))
                }
            }
        }
    }

    override suspend fun delete(id: String) {
        require(id.startsWith("acp:"))
        gate.withInactiveSession(id, true) {
            db.useWriterConnection { connection ->
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    val sessions = decode(db.rows().getValue(ACP_SESSIONS)?.value).toMutableMap()
                    sessions.remove(id)
                    RoomAcpReplaySnapshots.deleteSession(db.rows(), id)
                    db.rows().upsertValues(listOf(KeyValueRow(ACP_SESSIONS, JsonObject(sessions).toString())))
                }
            }
        }
    }

    private fun decode(raw: String?): Map<String, JsonObject> {
        if (raw == null) return emptyMap()
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid ACP sessions")
        return root.mapValues { (key, value) ->
            val record = value as? JsonObject ?: error("Invalid ACP session")
            require(key.startsWith("acp:") && record.text("id") == key) { "Invalid ACP session identity" }
            record
        }
    }

    private fun project(id: String, record: JsonObject): Conversation {
        val profile = record.text("profile") ?: error("ACP session has no agent profile")
        val title = record.text("title") ?: error("ACP session has no title")
        val updated = (record["updated_at"] as? JsonPrimitive)?.longOrNull ?: 0
        val last = ((record["messages"] as? JsonArray)?.lastOrNull() as? JsonObject)?.text("text")
        return Conversation(id = id, title = title, providerId = "", agentId = "acp:$profile",
            projectId = record.text("project"), createdAt = updated, updatedAt = updated, lastMessage = last)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
