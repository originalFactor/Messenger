/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Transactor
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.CLOUD_REPLACEMENT_LOCK_KEY
import cc.ptoe.messenger.data.migration.syncRoomPublicationDirectory
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import java.nio.ByteBuffer
import java.nio.file.Paths
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.serialization.json.*

/** Opaque identifiers are not a worker-stop proof or authorization to execute. */
internal class WorkspaceInvocationRef internal constructor(internal val key: String, internal val owner: String) {
    override fun toString(): String = "WorkspaceInvocationRef(redacted)"
}

/** A real platform barrier must exclude competing starts and prove this exact worker and all
 * descendants stopped for the full duration of [action]. A disconnected transport is not proof. */
internal interface WorkspaceInvocationStopBarrier {
    suspend fun <T> withStopped(ref: WorkspaceInvocationRef, action: suspend () -> T): T
}

internal sealed interface WorkspaceInvocationAdmission {
    class Admitted(val ref: WorkspaceInvocationRef, val grant: RoomInboundWorkspaceGrant) : WorkspaceInvocationAdmission
    data object Duplicate : WorkspaceInvocationAdmission
    data object Busy : WorkspaceInvocationAdmission
}

/**
 * Unconnected durable at-most-once ledger, not an executor or a complete authorization fence.
 * Caller must hold an exclusive Room owner and the same all-writer authority fence from before
 * admission through verified worker stop. In-memory Cloud 401 invalidation, Runtime ownership,
 * process-tree exclusion, file operations and actual user acknowledgement remain unimplemented.
 * Never install this in a production workspace relay without those independent guarantees.
 */
internal class RoomWorkspaceInvocationLedger(private val db: LocalDatabase) {
    companion object {
        private const val PREFIX = "kotlin_workspace_invocation_v1:"
        private const val ACTIVE = "kotlin_workspace_invocation_active_v1"
        private const val MAX_RECORDS = 10_000
    }
    private val random = SecureRandom()

    /** Reject a repeated sender/call identity even after a Cloud session generation changes. */
    suspend fun admit(scope: KotlinRemoteControlScope, cookie: String, call: RoomInboundWorkspaceCall): WorkspaceInvocationAdmission {
        validateWorkspaceCall(scope, call)
        require(cookie.isNotBlank() && cookie.length <= 4_096) { "Invalid Cloud session" }
        val key = PREFIX + digest(canonicalCloudBase(scope.baseUrl), scope.accountId, scope.deviceId, call.fromDeviceId, call.callId)
        return durableTransaction { connection ->
            val rows = db.rows()
            val grant = rows.workspaceGrant(scope, call)
            check(rows.getValue("cloud_session")?.value == cookie) { "Cloud session changed" }
            check(rows.getValue(CLOUD_REPLACEMENT_LOCK_KEY) == null) { "Cloud replacement requires completion or recovery" }
            val active = consistentPending(connection)
            if (rows.getValue(key) != null) return@durableTransaction WorkspaceInvocationAdmission.Duplicate
            if (active != null) return@durableTransaction WorkspaceInvocationAdmission.Busy
            connection.usePrepared("SELECT COUNT(*) FROM kv WHERE key GLOB ?") { row ->
                row.bindText(1, "$PREFIX*")
                check(row.step() && row.getLong(0) < MAX_RECORDS) { "Workspace request history is full" }
            }
            val owner = UUID.randomUUID().toString()
            val salt = ByteArray(32).also(random::nextBytes)
            val binding = hex(salt) + ":" + digest(hex(salt), canonicalCloudBase(scope.baseUrl), scope.accountId,
                scope.generation, cookie, scope.deviceId, scope.grantEpoch?.toString() ?: "", call.fromDeviceId,
                call.targetDeviceId, call.callId, call.projectId, call.conversationId, call.name,
                call.argumentsJson, grant.workspace)
            val record = buildJsonObject { put("owner", owner); put("binding", binding); put("state", "admitted") }
            val index = buildJsonObject { put("key", key); put("owner", owner) }
            rows.upsertValues(listOf(KeyValueRow(key, record.toString()), KeyValueRow(ACTIVE, index.toString())))
            WorkspaceInvocationAdmission.Admitted(WorkspaceInvocationRef(key, owner), grant)
        }
    }

    /** A restart may recover an opaque reservation; its effects are still unknown. */
    suspend fun pending(): WorkspaceInvocationRef? = db.useReaderConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
            consistentPending(connection)
        }
    }

    /** Record a definitive result only after the caller proves the exact worker stopped. */
    suspend fun complete(ref: WorkspaceInvocationRef, barrier: WorkspaceInvocationStopBarrier) =
        settle(ref, barrier, "completed")

    /** Explicit acknowledgement records uncertainty; it does not say the side effect failed. */
    suspend fun acknowledgeUnknown(ref: WorkspaceInvocationRef, barrier: WorkspaceInvocationStopBarrier) =
        settle(ref, barrier, "unknown_acknowledged")

    private suspend fun settle(ref: WorkspaceInvocationRef, barrier: WorkspaceInvocationStopBarrier, state: String) =
        barrier.withStopped(ref) {
            durableTransaction { connection ->
                val active = consistentPending(connection) ?: error("Workspace invocation is unavailable")
                check(active.key == ref.key && active.owner == ref.owner) { "Workspace invocation changed" }
                val record = parse(db.rows().getValue(ref.key)?.value ?: error("Workspace invocation is unavailable"))
                db.rows().upsertValues(listOf(KeyValueRow(ref.key, JsonObject(record + ("state" to JsonPrimitive(state))).toString())))
                db.rows().deleteValue(ACTIVE)
            }
        }

    /** Validate all retained records before allowing work: a lost index must not hide an admitted call. */
    private suspend fun consistentPending(connection: Transactor): WorkspaceInvocationRef? {
        connection.usePrepared("SELECT COUNT(*), COALESCE(MAX(length(CAST(value AS BLOB))),0), COALESCE(MAX(length(CAST(key AS BLOB))),0) FROM kv WHERE key GLOB ?") { row ->
            row.bindText(1, "$PREFIX*")
            check(row.step() && row.getLong(0) <= MAX_RECORDS && row.getLong(1) <= 1_024 &&
                row.getLong(2) <= PREFIX.length + 64) { "Workspace invocation history is invalid" }
        }
        connection.usePrepared("SELECT length(CAST(value AS BLOB)) FROM kv WHERE key=?") { row ->
            row.bindText(1, ACTIVE)
            if (row.step()) check(row.getLong(0) <= 1_024) { "Workspace invocation index is invalid" }
        }
        val indexed = db.rows().getValue(ACTIVE)?.value?.let(::index)
        var pending: WorkspaceInvocationRef? = null
        connection.usePrepared("SELECT key,value FROM kv WHERE key GLOB ?") { row ->
            row.bindText(1, "$PREFIX*")
            while (row.step()) {
                val key = row.getText(0)
                check(key.matches(Regex("${PREFIX}[0-9a-f]{64}"))) { "Invalid workspace invocation identity" }
                val record = parse(row.getText(1))
                val owner = record.text("owner") ?: error("Invalid workspace invocation")
                check(uuid(owner) && record.text("binding")?.matches(Regex("[0-9a-f]{64}:[0-9a-f]{64}")) == true) {
                    "Invalid workspace invocation"
                }
                when (record.text("state")) {
                    "admitted" -> {
                        check(pending == null) { "Multiple workspace invocations require recovery" }
                        pending = WorkspaceInvocationRef(key, owner)
                    }
                    "completed", "unknown_acknowledged" -> Unit
                    else -> error("Invalid workspace invocation state")
                }
            }
        }
        check((indexed == null && pending == null) ||
            (indexed != null && pending != null && indexed.key == pending?.key && indexed.owner == pending?.owner)) {
            "Workspace invocation index is inconsistent"
        }
        return pending
    }

    private fun index(raw: String): WorkspaceInvocationRef {
        val value = parse(raw)
        val key = value.text("key") ?: error("Invalid workspace invocation index")
        val owner = value.text("owner") ?: error("Invalid workspace invocation index")
        require(key.matches(Regex("${PREFIX}[0-9a-f]{64}")) && uuid(owner)) { "Invalid workspace invocation index" }
        return WorkspaceInvocationRef(key, owner)
    }

    private suspend fun <T> durableTransaction(block: suspend (Transactor) -> T): T = db.useWriterConnection { connection ->
        val path = connection.usePrepared("PRAGMA database_list") { row ->
            check(row.step() && row.getText(1) == "main") { "Workspace database is unavailable" }
            row.getText(2).takeIf { it.isNotBlank() } ?: error("Workspace invocation requires a persistent database")
        }
        connection.usePrepared("PRAGMA synchronous=FULL") { it.step() }
        connection.usePrepared("PRAGMA synchronous") { check(it.step() && it.getLong(0) == 2L) { "Workspace durability is unavailable" } }
        val result = connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block(connection) }
        syncRoomPublicationDirectory(requireNotNull(Paths.get(path).toAbsolutePath().normalize().parent))
        result
    }

    private fun parse(raw: String) = Json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid workspace invocation")
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun uuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun digest(vararg values: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (value in values) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
        }
        return hex(hash.digest())
    }
}
