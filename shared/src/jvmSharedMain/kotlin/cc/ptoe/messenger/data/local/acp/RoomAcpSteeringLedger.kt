/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Transactor
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.util.randomUuid
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * At-most-once steering admission. The caller must hold the exact live worker's reservation from
 * admission through dispatch and settlement. A failed/ambiguous dispatch retains the session
 * index; recovery requires a stopped worker and explicit acknowledgement, never a resend.
 */
internal class RoomAcpSteeringLedger(private val db: LocalDatabase) {
    data class Pending(val request: String, val turn: String)
    private val random = SecureRandom()
    private data class Record(val session: String, val turn: String, val digest: String, val state: String, val json: JsonObject)

    suspend fun admit(request: String, session: String, turn: String, content: JsonArray) {
        ids(request, session, turn)
        val bytes = bytes(content)
        db.useReaderConnection { connection ->
            connection.usePrepared("SELECT length(CAST(value AS BLOB)) FROM kv WHERE key=?") { statement ->
                statement.bindText(1, "local_acp_sessions")
                check(statement.step() && statement.getLong(0) <= 16L * 1024 * 1024 - bytes.size - 256 * 1024 - 4096) {
                    "ACP sessions exceed steering transcript limit"
                }
            }
        }
        durableTransaction {
            val rows = db.rows()
            check(rows.getValue(key(request)) == null) { "ACP steering request was already admitted; do not resubmit" }
            check(rows.getValue(index(session)) == null) { "An ACP steering result is unresolved" }
            val salt = ByteArray(32).also(random::nextBytes)
            val digest = hex(salt) + ":" + hex(hash(salt, bytes))
            val record = buildJsonObject { put("session", session); put("turn", turn); put("digest", digest); put("state", "admitted") }
            val idx = buildJsonObject { put("session", session); put("request", request) }
            rows.upsertValues(listOf(KeyValueRow(key(request), record.toString()), KeyValueRow(index(session), idx.toString())))
        }
    }

    suspend fun pending(session: String): Pending? {
        sessionId(session)
        return transaction {
            val rows = db.rows()
            val request = rows.getValue(index(session))?.let { decodeIndex(it.value, session) } ?: return@transaction null
            val record = rows.getValue(key(request))?.let { decode(it.value) } ?: error("ACP steering index is inconsistent")
            check(record.session == session && record.state == "admitted") { "ACP steering index is inconsistent" }
            Pending(request, record.turn)
        }
    }

    /** A completed agent response and injected transcript row are committed together. */
    suspend fun settle(request: String, session: String, turn: String, content: JsonArray, outcome: String, now: Long) {
        ids(request, session, turn)
        require(outcome == "injected" || outcome == "promptRequired") { "Invalid steering outcome" }
        require(now >= 0)
        val bytes = bytes(content)
        db.useWriterConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val rows = db.rows()
            val record = rows.getValue(key(request))?.let { decode(it.value) } ?: error("ACP steering request is unavailable")
            check(record.session == session && record.turn == turn && record.state == "admitted" && matches(record.digest, bytes)) { "ACP steering request changed" }
            check(decodeIndex(rows.getValue(index(session))?.value ?: error("ACP steering index is unavailable"), session) == request)
            if (outcome == "injected") {
                connection.usePrepared("SELECT length(CAST(value AS BLOB)) FROM kv WHERE key=?") { statement ->
                    statement.bindText(1, "local_acp_sessions")
                    check(statement.step() && statement.getLong(0) <= 16 * 1024 * 1024) { "ACP sessions exceed steering transcript limit" }
                }
                val root = rows.getValue("local_acp_sessions")?.value?.let { Json.parseToJsonElement(it) as? JsonObject } ?: error("ACP sessions are unavailable")
                val sessionRow = root[session] as? JsonObject ?: error("ACP session not found")
                check((sessionRow["id"] as? JsonPrimitive)?.contentOrNull == session) { "ACP session identity changed" }
                val messages = sessionRow["messages"] as? JsonArray ?: error("Invalid ACP transcript")
                val text = content.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "text") (obj["text"] as? JsonPrimitive)?.contentOrNull else null
                }.joinToString("\n")
                require(text.encodeToByteArray().size <= 256 * 1024) { "ACP steering text exceeds limit" }
                val row = buildJsonObject { put("id", randomUuid()); put("role", "user"); put("text", text); put("status", "sent"); put("timestamp", now); put("content", content) }
                val updated = JsonObject(sessionRow + mapOf("updated_at" to JsonPrimitive(now), "messages" to JsonArray(messages + row)))
                rows.upsertValues(listOf(KeyValueRow("local_acp_sessions", JsonObject(root + (session to updated)).toString())))
            }
            rows.upsertValues(listOf(KeyValueRow(key(request), JsonObject(record.json + ("state" to JsonPrimitive(outcome))).toString())))
            rows.deleteValue(index(session))
        } }
    }

    /** Explicitly retain unknown outcome after proving the old worker cannot resume. */
    suspend fun acknowledgeUnknown(request: String, session: String, turn: String, barrier: AcpRecoveryBarrier) {
        ids(request, session, turn)
        barrier.withStopped(turn, session) {
            transaction {
                val rows = db.rows()
                val record = rows.getValue(key(request))?.let { decode(it.value) } ?: error("ACP steering request is unavailable")
                check(record.session == session && record.turn == turn && record.state == "admitted") { "ACP steering request changed" }
                check(decodeIndex(rows.getValue(index(session))?.value ?: error("ACP steering index is unavailable"), session) == request)
                rows.upsertValues(listOf(KeyValueRow(key(request), JsonObject(record.json + ("state" to JsonPrimitive("interrupted"))).toString())))
                rows.deleteValue(index(session))
            }
        }
    }

    private suspend fun <T> transaction(block: suspend () -> T): T = db.useWriterConnection { it.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() } }
    private suspend fun <T> durableTransaction(block: suspend () -> T): T = db.useWriterConnection { connection ->
        val path = connection.usePrepared("PRAGMA database_list") { statement ->
            check(statement.step() && statement.getText(1) == "main") { "ACP database is unavailable" }
            statement.getText(2).takeIf { it.isNotBlank() } ?: error("ACP steering requires a persistent database")
        }
        connection.usePrepared("PRAGMA synchronous=FULL") { it.step() }
        connection.usePrepared("PRAGMA synchronous") { check(it.step() && it.getLong(0) == 2L) { "ACP steering durability is unavailable" } }
        val result = connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() }
        val directory = requireNotNull(Paths.get(path).toAbsolutePath().normalize().parent)
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        result
    }
    private fun bytes(content: JsonArray): ByteArray {
        val data = content.toString().toByteArray(Charsets.UTF_8)
        require(content.isNotEmpty() && data.size <= 4 * 1024 * 1024) { "Invalid ACP steering content" }
        val textSize = content.sumOf { item ->
            val obj = item as? JsonObject ?: return@sumOf 0L
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "text")
                ((obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.encodeToByteArray()?.size ?: 0).toLong() + 1 else 0L
        }
        require(textSize <= 256 * 1024) { "ACP steering text exceeds limit" }
        return data
    }
    private fun ids(request: String, session: String, turn: String) { uuid(request); sessionId(session); uuid(turn) }
    private fun uuid(id: String) { require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid ACP request ID" } }
    private fun sessionId(id: String) { require(id.startsWith("acp:") && id.length in 5..256 && id.none { it.isISOControl() }) { "Invalid ACP session ID" } }
    private fun key(id: String) = "kotlin_acp_steering_v1:$id"
    private fun index(session: String) = "kotlin_acp_steering_active_v1:" + hex(MessageDigest.getInstance("SHA-256").digest(session.toByteArray(Charsets.UTF_8)))
    private fun decode(value: String): Record {
        val json = Json.parseToJsonElement(value) as? JsonObject ?: error("Invalid ACP steering record")
        fun string(key: String) = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid ACP steering record")
        val session = string("session"); sessionId(session)
        val turn = string("turn"); uuid(turn)
        val digest = string("digest"); require(digest.matches(Regex("[0-9a-f]{64}:[0-9a-f]{64}")))
        val state = string("state"); require(state in setOf("admitted", "injected", "promptRequired", "interrupted"))
        return Record(session, turn, digest, state, json)
    }
    private fun decodeIndex(value: String, session: String): String {
        val obj = Json.parseToJsonElement(value) as? JsonObject ?: error("Invalid ACP steering index")
        check((obj["session"] as? JsonPrimitive)?.takeIf { it.isString }?.content == session)
        return ((obj["request"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid ACP steering index")).also(::uuid)
    }
    private fun matches(digest: String, data: ByteArray): Boolean {
        val (salt, expected) = digest.split(":")
        return MessageDigest.isEqual(hash(unhex(salt), data), unhex(expected))
    }
    private fun hash(salt: ByteArray, data: ByteArray) = MessageDigest.getInstance("SHA-256").run { update(salt); digest(data) }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun unhex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
