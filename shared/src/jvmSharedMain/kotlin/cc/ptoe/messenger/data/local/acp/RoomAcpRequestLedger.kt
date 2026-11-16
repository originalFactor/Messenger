/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.serialization.json.*

internal enum class AcpAdmissionState { ADMITTED, COMPLETED, UNCERTAIN, INTERRUPTED }
internal sealed interface AcpAdmission {
    data object New : AcpAdmission
    data class Existing(val state: AcpAdmissionState, val sameRequest: Boolean) : AcpAdmission
    data object SessionBusy : AcpAdmission
}

/** Must prove that the prior process and side effects stopped and cannot resume, and exclude
 * competing starts for the session through [action]. A cancellation acknowledgement is not proof. */
internal interface AcpRecoveryBarrier {
    suspend fun <T> withStopped(requestId: String, sessionId: String, action: suspend () -> T): T
}

/** Persistent at-most-once admission only. This does not start or retry any worker. */
internal class RoomAcpRequestLedger(private val db: LocalDatabase) {
    private val random = SecureRandom()
    private data class Record(val session: String, val digest: String, val state: AcpAdmissionState, val json: JsonObject)

    suspend fun admit(requestId: String, sessionId: String, content: JsonArray): AcpAdmission {
        validateId(requestId); validateSession(sessionId)
        require(content.isNotEmpty()) { "ACP prompt is empty" }
        val bytes = content.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4 * 1024 * 1024) { "ACP prompt exceeds limit" }
        return transaction {
            val rows = db.rows()
            val old = rows.getValue(requestKey(requestId))?.let { decode(it.value) }
            if (old != null) {
                return@transaction AcpAdmission.Existing(old.state, old.session == sessionId && matches(old.digest, bytes))
            }
            val active = rows.getValue(sessionKey(sessionId))?.let { decodeIndex(it.value) }
            if (active != null) {
                check(active.first == sessionId) { "ACP session index collision" }
                val activeRecord = rows.getValue(requestKey(active.second))?.let { decode(it.value) } ?: error("ACP session index is inconsistent")
                check(activeRecord.session == sessionId && activeRecord.state in setOf(AcpAdmissionState.ADMITTED, AcpAdmissionState.UNCERTAIN)) { "ACP session index is inconsistent" }
                return@transaction AcpAdmission.SessionBusy
            }
            val salt = ByteArray(32).also(random::nextBytes)
            val digest = hex(salt) + ":" + hex(hash(salt, bytes))
            val record = buildJsonObject { put("sessionId", sessionId); put("digest", digest); put("state", AcpAdmissionState.ADMITTED.name) }
            val index = buildJsonObject { put("sessionId", sessionId); put("requestId", requestId) }
            rows.upsertValues(listOf(KeyValueRow(requestKey(requestId), record.toString()), KeyValueRow(sessionKey(sessionId), index.toString())))
            AcpAdmission.New
        }
    }

    /** Locate interrupted or active work without exposing request contents. */
    suspend fun activeRequest(sessionId: String): String? {
        validateSession(sessionId)
        return transaction {
            val rows = db.rows()
            val index = rows.getValue(sessionKey(sessionId))?.let { decodeIndex(it.value) } ?: return@transaction null
            check(index.first == sessionId) { "ACP session index collision" }
            val record = rows.getValue(requestKey(index.second))?.let { decode(it.value) } ?: error("ACP session index is inconsistent")
            check(record.session == sessionId && record.state in setOf(AcpAdmissionState.ADMITTED, AcpAdmissionState.UNCERTAIN)) { "ACP session index is inconsistent" }
            index.second
        }
    }

    /** A retained ID must not be routed to a different task owner after its ACP turn ends. */
    suspend fun contains(requestId: String): Boolean {
        if (!runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false)) return false
        return transaction { db.rows().getValue(requestKey(requestId))?.let { decode(it.value); true } ?: false }
    }

    /** Uncertain effects keep the session blocked until an explicit proof-backed recovery. */
    suspend fun markUncertain(requestId: String, sessionId: String): AcpAdmissionState {
        validateId(requestId); validateSession(sessionId)
        return transaction {
            val rows = db.rows()
            val current = rows.getValue(requestKey(requestId))?.let { decode(it.value) } ?: error("ACP request is unavailable")
            check(current.session == sessionId) { "ACP request belongs to another session" }
            if (current.state == AcpAdmissionState.UNCERTAIN) return@transaction current.state
            check(current.state == AcpAdmissionState.ADMITTED) { "ACP request is already settled" }
            requireIndex(sessionId, requestId)
            rows.upsertValues(listOf(KeyValueRow(requestKey(requestId), updated(current, AcpAdmissionState.UNCERTAIN))))
            AcpAdmissionState.UNCERTAIN
        }
    }

    /** Completion releases the session only while the old worker is proven stopped. */
    suspend fun complete(requestId: String, sessionId: String, barrier: AcpRecoveryBarrier): AcpAdmissionState {
        validateId(requestId); validateSession(sessionId)
        return barrier.withStopped(requestId, sessionId) {
            transaction {
                val rows = db.rows()
                val current = rows.getValue(requestKey(requestId))?.let { decode(it.value) } ?: error("ACP request is unavailable")
                check(current.session == sessionId) { "ACP request belongs to another session" }
                if (current.state == AcpAdmissionState.COMPLETED) return@transaction current.state
                check(current.state == AcpAdmissionState.ADMITTED) { "ACP request is already settled" }
                requireIndex(sessionId, requestId)
                rows.upsertValues(listOf(KeyValueRow(requestKey(requestId), updated(current, AcpAdmissionState.COMPLETED))))
                rows.deleteValue(sessionKey(sessionId))
                AcpAdmissionState.COMPLETED
            }
        }
    }

    /** Unknown execution outcomes remain unknown; recovery only records interruption, never retries. */
    suspend fun recover(requestId: String, sessionId: String, barrier: AcpRecoveryBarrier): AcpAdmissionState {
        validateId(requestId); validateSession(sessionId)
        return barrier.withStopped(requestId, sessionId) {
            transaction {
                val rows = db.rows()
                val current = rows.getValue(requestKey(requestId))?.let { decode(it.value) } ?: error("ACP request is unavailable")
                check(current.session == sessionId) { "ACP request belongs to another session" }
                if (current.state == AcpAdmissionState.COMPLETED || current.state == AcpAdmissionState.INTERRUPTED) return@transaction current.state
                requireIndex(sessionId, requestId)
                rows.upsertValues(listOf(KeyValueRow(requestKey(requestId), updated(current, AcpAdmissionState.INTERRUPTED))))
                rows.deleteValue(sessionKey(sessionId))
                AcpAdmissionState.INTERRUPTED
            }
        }
    }

    private suspend fun requireIndex(session: String, id: String) {
        val index = db.rows().getValue(sessionKey(session))?.let { decodeIndex(it.value) } ?: error("ACP session index is unavailable")
        check(index.first == session && index.second == id) { "ACP session index is inconsistent" }
    }
    private suspend fun <T> transaction(block: suspend () -> T): T = db.useWriterConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() }
    }
    private fun decode(value: String): Record {
        val json = Json.parseToJsonElement(value) as? JsonObject ?: error("Invalid ACP request record")
        val session = string(json, "sessionId"); validateSession(session)
        val digest = string(json, "digest")
        require(digest.matches(Regex("[0-9a-f]{64}:[0-9a-f]{64}"))) { "Invalid ACP request digest" }
        val state = AcpAdmissionState.entries.firstOrNull { it.name == string(json, "state") } ?: error("Invalid ACP request state")
        return Record(session, digest, state, json)
    }
    private fun decodeIndex(value: String): Pair<String, String> {
        val json = Json.parseToJsonElement(value) as? JsonObject ?: error("Invalid ACP session index")
        val session = string(json, "sessionId"); val id = string(json, "requestId")
        validateSession(session); validateId(id)
        return session to id
    }
    private fun string(json: JsonObject, key: String): String = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid ACP request record")
    private fun updated(record: Record, state: AcpAdmissionState) = JsonObject(record.json + ("state" to JsonPrimitive(state.name))).toString()
    private fun matches(digest: String, bytes: ByteArray): Boolean {
        val (salt, expected) = digest.split(":")
        return MessageDigest.isEqual(hash(unhex(salt), bytes), unhex(expected))
    }
    private fun hash(salt: ByteArray, bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { update(salt); digest(bytes) }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun unhex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun requestKey(id: String) = "kotlin_acp_request_v1:$id"
    private fun sessionKey(session: String) = "kotlin_acp_active_v1:" + hex(MessageDigest.getInstance("SHA-256").digest(session.toByteArray(Charsets.UTF_8)))
    private fun validateId(id: String) { require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid ACP request ID" } }
    private fun validateSession(session: String) { require(session.startsWith("acp:") && session.length in 5..256 && session.none { it.isISOControl() }) { "Invalid ACP session ID" } }
}
