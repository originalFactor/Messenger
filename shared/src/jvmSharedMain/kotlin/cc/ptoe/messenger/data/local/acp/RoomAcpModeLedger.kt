/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.util.randomUuid
import java.security.MessageDigest
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.*

/** A pending mode change is never retried; the user must explicitly inspect the agent's state. */
internal class RoomAcpModeLedger(private val db: LocalDatabase) {
    data class Pending(val id: String, val configId: String?)
    private fun key(sessionId: String): String = "kotlin_acp_mode_pending_v1:" +
        MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private suspend fun pendingValue(sessionId: String): JsonObject? {
        val raw = db.rows().getValue(key(sessionId))?.value ?: return null
        val value = Json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid pending ACP mode change")
        check((value["sessionId"] as? JsonPrimitive)?.contentOrNull == sessionId &&
            (value["id"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true) { "Invalid pending ACP mode change" }
        return value
    }

    suspend fun pending(sessionId: String): Boolean = pendingValue(sessionId) != null

    suspend fun begin(sessionId: String, mode: String, configId: String? = null): String = db.useWriterConnection { connection ->
        val databasePath = connection.usePrepared("PRAGMA database_list") { statement ->
            check(statement.step() && statement.getText(1) == "main") { "ACP database is unavailable" }
            statement.getText(2).takeIf { it.isNotBlank() } ?: error("ACP mode changes require a persistent database")
        }
        connection.usePrepared("PRAGMA synchronous=FULL") { it.step() }
        connection.usePrepared("PRAGMA synchronous") { check(it.step() && it.getLong(0) == 2L) { "ACP mode durability is unavailable" } }
        val id = connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            check(pendingValue(sessionId) == null) { "Check the previous ACP mode change before trying again" }
            val id = randomUuid()
            val value = buildJsonObject { put("id", id); put("sessionId", sessionId); put("modeId", mode); if (configId != null) put("configId", configId) }
            db.rows().upsertValues(listOf(KeyValueRow(key(sessionId), value.toString())))
            id
        }
        val directory = requireNotNull(Paths.get(databasePath).toAbsolutePath().normalize().parent)
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        id
    }

    suspend fun clear(sessionId: String, id: String) = db.useWriterConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val value = pendingValue(sessionId) ?: error("Pending ACP mode change is unavailable")
            check((value["id"] as? JsonPrimitive)?.contentOrNull == id) { "ACP mode change changed" }
            db.rows().deleteValue(key(sessionId))
        }
    }

    suspend fun pendingId(sessionId: String): String? = (pendingValue(sessionId)?.get("id") as? JsonPrimitive)?.contentOrNull

    suspend fun pendingRecord(sessionId: String): Pending? {
        val value = pendingValue(sessionId) ?: return null
        val id = (value["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("Invalid pending ACP mode change")
        val configId = value["configId"]?.let { (it as? JsonPrimitive)?.takeIf { field -> field.isString }?.contentOrNull
            ?.takeIf { field -> field.isNotBlank() } ?: error("Invalid pending ACP configuration") }
        return Pending(id, configId)
    }
}
