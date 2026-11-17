/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Transactor
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.LocalRowsDao
import cc.ptoe.messenger.data.repository.room.AcpSessionMutationGate
import java.security.MessageDigest
import kotlinx.serialization.json.*

private const val INDEX = "kotlin_acp_replay_index_v1"
private const val SNAPSHOT = "kotlin_acp_replay_snapshot_v1:"
private const val MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024
private const val MAX_TOTAL_BYTES = 64 * 1024 * 1024L
private const val MAX_SNAPSHOTS = 256
private const val MAX_PER_SESSION = 8
private const val MAX_EVENTS = 10_000
private const val MAX_EVENT_BYTES = 192_000
private const val MAX_PAGE_BYTES = 256 * 1024

internal data class AcpReplaySnapshotInfo(val id: String, val sessionId: String, val capturedAt: Long, val eventCount: Int, val byteCount: Int)
internal data class AcpReplaySnapshotPage(val source: JsonObject, val events: JsonArray, val next: Int?)

/** Bounded parsed ACP notifications in wire order. No assumption is made about completeness. */
internal class AcpReplayCapture {
    private val events = mutableListOf<JsonObject>()
    private var bytes = 2L
    fun append(update: JsonObject) {
        val size = update.toString().encodeToByteArray().size
        require(size <= MAX_EVENT_BYTES && events.size < MAX_EVENTS && bytes + size + 1 <= MAX_SNAPSHOT_BYTES) { "ACP replay exceeds limit" }
        bytes += size + 1
        events.add(update)
    }
    fun snapshot(): JsonArray = JsonArray(events.toList())
}

/** Isolated storage primitives. The caller owns Room and excludes other ACP writers. */
internal class RoomAcpReplaySnapshots(private val db: LocalDatabase, private val gate: AcpSessionMutationGate) {
    suspend fun list(sessionId: String): List<AcpReplaySnapshotInfo> = db.useReaderConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
            requireSession(db.rows(), sessionId)
            readIndex(db.rows()).filter { it.sessionId == sessionId }
        }
    }

    suspend fun page(sessionId: String, id: String, after: Int = 0): AcpReplaySnapshotPage = db.useReaderConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
            requireSession(db.rows(), sessionId)
            val info = readIndex(db.rows()).singleOrNull { it.sessionId == sessionId && it.id == id } ?: error("ACP replay snapshot not found")
            val stored = db.rows().getValue(SNAPSHOT + id)?.value ?: error("ACP replay snapshot is missing")
            require(stored.encodeToByteArray().size == info.byteCount) { "ACP replay snapshot size changed" }
            val payload = Json.parseToJsonElement(stored) as? JsonObject ?: error("Invalid ACP replay snapshot")
            check((payload["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull == sessionId &&
                sha256(canonical(payload).toString()) == id) { "ACP replay snapshot identity changed" }
            val array = payload["events"] as? JsonArray ?: error("Invalid ACP replay snapshot")
            require(array.size == info.eventCount && after in 0..array.size) { "Invalid ACP replay cursor" }
            val page = mutableListOf<JsonElement>()
            var bytes = 2
            for (event in array.drop(after)) {
                require(event is JsonObject) { "Invalid ACP replay event" }
                val size = event.toString().encodeToByteArray().size + 1
                require(size <= MAX_PAGE_BYTES - 2) { "ACP replay event exceeds page limit" }
                if (page.isNotEmpty() && (bytes + size > MAX_PAGE_BYTES || page.size >= 100)) break
                bytes += size; page.add(event)
            }
            val next = after + page.size
            AcpReplaySnapshotPage(JsonObject(payload - "events"), JsonArray(page), next.takeIf { it < array.size })
        }
    }

    suspend fun delete(sessionId: String, id: String) = gate.withInactiveSession(sessionId, false) {
        db.useWriterConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val rows = db.rows(); requireSession(rows, sessionId)
            val entries = readIndex(rows)
            check(entries.any { it.sessionId == sessionId && it.id == id }) { "ACP replay snapshot not found" }
            rows.deleteValue(SNAPSHOT + id)
            writeIndex(rows, entries.filterNot { it.sessionId == sessionId && it.id == id })
        } }
    }

    companion object {
        /** Caller holds the exact active turn guard and Room writer transaction. */
        internal suspend fun record(rows: LocalRowsDao, binding: AcpWorkerBinding, events: JsonArray, now: Long) {
            require(now >= 0 && events.size <= MAX_EVENTS)
            val session = requireSession(rows, binding.conversationId)
            fun field(key: String) = (session[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val delegated = session["delegated"]?.let {
                (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull ?: error("Invalid ACP session delegation")
            } ?: false
            check(field("agent_session") == binding.agentSessionId && field("profile") == binding.profileId &&
                field("project") == binding.projectId && field("cwd") == binding.workspace &&
                delegated == binding.delegated) { "ACP session binding changed" }
            val payload = buildJsonObject {
                put("version", 1); put("sessionId", binding.conversationId); put("agentSessionId", binding.agentSessionId)
                put("profileId", binding.profileId); put("projectId", binding.projectId); put("workspace", binding.workspace)
                put("delegated", binding.delegated); put("events", events)
            }
            val content = payload.toString()
            val bytes = content.encodeToByteArray().size
            require(bytes <= MAX_SNAPSHOT_BYTES && events.all { it is JsonObject && it.toString().encodeToByteArray().size <= MAX_EVENT_BYTES }) { "ACP replay exceeds limit" }
            val id = sha256(canonical(payload).toString())
            val entries = readIndex(rows)
            val existing = entries.singleOrNull { it.id == id }
            if (existing != null) {
                val previous = rows.getValue(SNAPSHOT + id)?.value ?: error("ACP replay snapshot is missing")
                check(existing.sessionId == binding.conversationId && existing.eventCount == events.size && previous.encodeToByteArray().size == existing.byteCount &&
                    sha256(canonical(Json.parseToJsonElement(previous)).toString()) == id) { "ACP replay identity collision" }
                return
            }
            check(entries.size < MAX_SNAPSHOTS && entries.count { it.sessionId == binding.conversationId } < MAX_PER_SESSION &&
                entries.sumOf { it.byteCount.toLong() } + bytes <= MAX_TOTAL_BYTES) { "ACP replay storage is full; remove a snapshot before continuing" }
            check(rows.getValue(SNAPSHOT + id) == null) { "ACP replay identity collision" }
            rows.upsertValues(listOf(KeyValueRow(SNAPSHOT + id, content)))
            writeIndex(rows, entries + AcpReplaySnapshotInfo(id, binding.conversationId, now, events.size, bytes))
        }

        /** Caller holds the session mutation gate and Room writer transaction. */
        internal suspend fun deleteSession(rows: LocalRowsDao, sessionId: String) {
            val entries = readIndex(rows)
            entries.filter { it.sessionId == sessionId }.forEach { rows.deleteValue(SNAPSHOT + it.id) }
            writeIndex(rows, entries.filterNot { it.sessionId == sessionId })
        }

        private suspend fun requireSession(rows: LocalRowsDao, sessionId: String): JsonObject {
            require(sessionId.startsWith("acp:") && sessionId.length in 5..256) { "Invalid ACP session" }
            val root = rows.getValue("local_acp_sessions")?.value?.let { Json.parseToJsonElement(it) as? JsonObject } ?: error("ACP sessions are unavailable")
            val session = root[sessionId] as? JsonObject ?: error("ACP session not found")
            check((session["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull == sessionId) { "ACP session identity changed" }
            return session
        }

        private suspend fun readIndex(rows: LocalRowsDao): List<AcpReplaySnapshotInfo> {
            val raw = rows.getValue(INDEX)?.value ?: return emptyList()
            val array = Json.parseToJsonElement(raw) as? JsonArray ?: error("Invalid ACP replay index")
            require(array.size <= MAX_SNAPSHOTS) { "Invalid ACP replay index" }
            val entries = array.map { item ->
                val obj = item as? JsonObject ?: error("Invalid ACP replay index")
                fun text(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("Invalid ACP replay index")
                fun number(name: String) = (obj[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: error("Invalid ACP replay index")
                val id = text("id"); val session = text("sessionId"); val captured = number("capturedAt")
                val count = number("eventCount"); val bytes = number("byteCount")
                require(id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' } && session.startsWith("acp:") &&
                    session.length in 5..256 && captured >= 0 && count in 0L..MAX_EVENTS.toLong() && bytes in 2L..MAX_SNAPSHOT_BYTES.toLong()) { "Invalid ACP replay index" }
                AcpReplaySnapshotInfo(id, session, captured, count.toInt(), bytes.toInt())
            }
            require(entries.map { it.id }.distinct().size == entries.size && entries.sumOf { it.byteCount.toLong() } <= MAX_TOTAL_BYTES &&
                entries.groupingBy { it.sessionId }.eachCount().values.all { it <= MAX_PER_SESSION }) { "Invalid ACP replay index" }
            return entries
        }

        private suspend fun writeIndex(rows: LocalRowsDao, entries: List<AcpReplaySnapshotInfo>) {
            val array = JsonArray(entries.map { info -> buildJsonObject {
                put("id", info.id); put("sessionId", info.sessionId); put("capturedAt", info.capturedAt)
                put("eventCount", info.eventCount); put("byteCount", info.byteCount)
            } })
            rows.upsertValues(listOf(KeyValueRow(INDEX, array.toString())))
        }

        private fun canonical(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(value.map(::canonical))
            else -> value
        }
        private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
