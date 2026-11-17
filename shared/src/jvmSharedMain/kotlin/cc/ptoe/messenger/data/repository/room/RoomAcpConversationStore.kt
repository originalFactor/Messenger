/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0. See https://www.apache.org/licenses/LICENSE-2.0
 */
package cc.ptoe.messenger.data.repository.room

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

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
