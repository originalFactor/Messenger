/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Transactor
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.LocalRowsDao
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal data class RoomDeviceControlSettings(val config: JsonObject, val epoch: Long?)
internal data class RoomControlSettingsSnapshot(
    val config: JsonObject, val epoch: Long?, val identity: List<String?>,
    val session: KotlinRemoteControlScope?, val templatesSeeded: Boolean,
) {
    override fun toString(): String = "RoomControlSettingsSnapshot(epoch=$epoch, identity=redacted)"
}

private fun validEpoch(value: String?): Long? = value?.let {
    it.toLongOrNull()?.takeIf { epoch -> epoch > 0 } ?: error("Invalid remote control epoch")
}

/** Every configuration change invalidates existing remote work in the same write transaction. */
internal class RoomDeviceControlGrantStore(private val db: LocalDatabase) {
    private val fence = RoomDeviceControlFence.forDao(db.cloudSync())

    private suspend fun LocalRowsDao.settingsIdentity() = listOf(
        getValue("kotlin_sync_session")?.value, getValue("cloud_session")?.value,
        getValue("cloud_session_host")?.value, getValue("cloud_user_json")?.value, getValue("device_id")?.value,
    )

    private suspend fun LocalRowsDao.settingsSession(identity: List<String?>): KotlinRemoteControlScope? {
        if (identity.any { it == null }) return null
        return try {
            val session = Json.parseToJsonElement(identity[0]!!) as? JsonArray ?: return null
            if (session.size != 2) return null
            fun field(index: Int) = (session[index] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val scope = KotlinRemoteControlScope(field(0) ?: return null, field(1) ?: return null, identity[2]!!, identity[4]!!)
            requireCloudSession(scope)
            scope
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { null } catch (_: IllegalStateException) { null }
    }

    suspend fun readForBridge(): RoomControlSettingsSnapshot = db.useReaderConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
            val rows = db.rows()
            val identity = rows.settingsIdentity()
            val session = rows.settingsSession(identity)
            val epoch = validEpoch(rows.getValue(REMOTE_CONTROL_EPOCH_KEY)?.value)
            val config = rows.getValue("device_control_config")?.value?.let { Json.parseToJsonElement(it) as? JsonObject ?: error("Invalid remote control configuration") }
                ?: JsonObject(mapOf("enabled" to JsonPrimitive(false), "name" to JsonPrimitive(""), "agents" to JsonArray(emptyList())))
            val allowed = if (session == null || epoch == null) false else try {
                rows.requireRemoteControl(session.copy(grantEpoch = epoch)); true
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: IllegalStateException) { false }
            RoomControlSettingsSnapshot(JsonObject(config + ("enabled" to JsonPrimitive(allowed))), epoch, identity, session,
                rows.getValue("acp_templates_seeded") != null)
        }
    }

    suspend fun saveForBridge(snapshot: RoomControlSettingsSnapshot, config: JsonObject): Long = fence.mutate {
        db.useWriterConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val rows = db.rows()
            check(rows.settingsIdentity() == snapshot.identity && (rows.getValue("acp_templates_seeded") != null) == snapshot.templatesSeeded) { "Cloud session or settings changed" }
            val session = rows.settingsSession(snapshot.identity)
            check(session == snapshot.session) { "Cloud session changed" }
            val enabled = (config["enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("Invalid remote control configuration")
            if (enabled) check(session != null) { "Sign in to Messenger Cloud to enable remote control" }
            require(config.toString().encodeToByteArray().size <= 256_000) { "Remote control configuration is too large" }
            val oldEpoch = validEpoch(rows.getValue(REMOTE_CONTROL_EPOCH_KEY)?.value)
            check(oldEpoch == snapshot.epoch) { "Remote control configuration changed" }
            val current = oldEpoch ?: 0L
            check(current < Long.MAX_VALUE) { "Remote control epoch exhausted" }
            val next = current + 1
            val values = mutableListOf(KeyValueRow("device_control_config", config.toString()), KeyValueRow(REMOTE_CONTROL_EPOCH_KEY, next.toString()), KeyValueRow("acp_templates_seeded", "1"))
            if (enabled) {
                val authorized = checkNotNull(session)
                values += KeyValueRow("device_control_grant", JsonArray(listOf(JsonPrimitive(canonicalCloudBase(authorized.baseUrl)), JsonPrimitive(authorized.accountId))).toString())
            } else rows.deleteValue("device_control_grant")
            rows.upsertValues(values)
            next
        } }
    }
    suspend fun read(scope: KotlinRemoteControlScope): RoomDeviceControlSettings = db.useReaderConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
            val rows = db.rows()
            rows.requireCloudSession(scope)
            val raw = rows.getValue("device_control_config")?.value
            val config = if (raw == null) JsonObject(mapOf("enabled" to JsonPrimitive(false), "agents" to JsonArray(emptyList())))
                else Json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid remote control configuration")
            RoomDeviceControlSettings(config, validEpoch(rows.getValue(REMOTE_CONTROL_EPOCH_KEY)?.value))
        }
    }

    suspend fun save(scope: KotlinRemoteControlScope, expectedEpoch: Long?, config: JsonObject): Long = fence.mutate { db.useWriterConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val rows = db.rows()
            rows.requireCloudSession(scope)
            val enabled = (config["enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: error("Invalid remote control configuration")
            require(config.toString().encodeToByteArray().size <= 256_000) { "Remote control configuration is too large" }
            val oldEpoch = validEpoch(rows.getValue(REMOTE_CONTROL_EPOCH_KEY)?.value)
            check(oldEpoch == expectedEpoch) { "Remote control configuration changed" }
            val current = oldEpoch ?: 0L
            check(current < Long.MAX_VALUE) { "Remote control epoch exhausted" }
            val next = current + 1
            val values = mutableListOf(KeyValueRow("device_control_config", config.toString()), KeyValueRow(REMOTE_CONTROL_EPOCH_KEY, next.toString()))
            if (enabled) values += KeyValueRow("device_control_grant", JsonArray(listOf(JsonPrimitive(canonicalCloudBase(scope.baseUrl)), JsonPrimitive(scope.accountId))).toString())
            else rows.deleteValue("device_control_grant")
            rows.upsertValues(values)
            next
        }
    } }
}
