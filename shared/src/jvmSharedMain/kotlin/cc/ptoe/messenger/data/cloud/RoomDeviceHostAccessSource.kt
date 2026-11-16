/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Transactor
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads session and its device-control grant together under one Room snapshot.
 * Own [watch] with the backend lifetime and stop it before closing Room.
 */
internal class RoomDeviceHostAccessSource(
    private val db: LocalDatabase,
    private val defaultName: (deviceId: String) -> String,
) {
    suspend fun snapshot(): RoomDeviceHostAccess? = db.useReaderConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
            val rows = db.rows()
            try {
                val sessionValues = rows.getValue("kotlin_sync_session")?.value?.let { Json.parseToJsonElement(it) as? JsonArray } ?: return@withTransaction null
                if (sessionValues.size != 2) return@withTransaction null
                fun text(value: kotlinx.serialization.json.JsonElement?) = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                val account = text(sessionValues[0]) ?: return@withTransaction null
                val generation = text(sessionValues[1]) ?: return@withTransaction null
                val device = rows.getValue("device_id")?.value ?: return@withTransaction null
                val host = rows.getValue("cloud_session_host")?.value?.let(::canonicalCloudBase) ?: return@withTransaction null
                val cookie = rows.getValue("cloud_session")?.value ?: return@withTransaction null
                if (!cookie.startsWith("messenger_session=") || cookie.length <= "messenger_session=".length ||
                    cookie.any { it == ';' || it.isWhitespace() || it.isISOControl() }) return@withTransaction null
                val user = rows.getValue("cloud_user_json")?.value?.let { NetworkClient.json.decodeFromString(CloudUser.serializer(), it) } ?: return@withTransaction null
                val epoch = rows.getValue(REMOTE_CONTROL_EPOCH_KEY)?.value?.toLongOrNull()?.takeIf { it > 0 }
                val scope = KotlinRemoteControlScope(account, generation, host, device, grantEpoch = epoch)
                rows.requireCloudSession(scope)
                val grant = try {
                    rows.requireRemoteControl(scope)
                    val config = rows.getValue("device_control_config")?.value?.let { Json.parseToJsonElement(it) as? JsonObject } ?: error("Invalid control configuration")
                    val name = (text(config["name"])?.trim()?.takeIf(String::isNotEmpty) ?: defaultName(device))
                        .takeIf { it.isNotBlank() && it.length <= 120 && it.none(Char::isISOControl) }
                    if (name != null && epoch != null) RoomDeviceControlGrant(name, epoch) else null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IllegalArgumentException) { null }
                catch (_: IllegalStateException) { null }
                RoomDeviceHostAccess(StoredCloudSession(SyncSession(account, generation), cookie, host, user), grant)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalArgumentException) { null }
            catch (_: IllegalStateException) { null }
        }
    }

    /** Seed once before exposing a fresh install; never replace a stored device identity. */
    suspend fun ensureDeviceId(): String = db.useWriterConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val rows = db.rows()
            val existing = rows.getValue("device_id")?.value
            if (existing != null) {
                check(existing.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Stored device identity is invalid" }
                existing
            } else randomUuid().also { rows.upsertValues(listOf(KeyValueRow("device_id", it))) }
        }
    }

    suspend fun watch(scope: CoroutineScope): StateFlow<RoomDeviceHostAccess?> {
        ensureDeviceId()
        // Room invalidates this query on any KV change; snapshot re-reads all fields atomically.
        return db.rows().observeValue("device_control_config").map { snapshot() }.stateIn(scope, SharingStarted.Eagerly, null)
    }

    suspend fun stillCurrent(expected: StoredCloudSession): Boolean {
        val actual = snapshot()?.session ?: return false
        return actual.session == expected.session && actual.cookie == expected.cookie && canonicalCloudBase(actual.host) == canonicalCloudBase(expected.host)
    }
}
