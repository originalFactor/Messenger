/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RoomDeviceControlGrantStoreTest {
    @Test fun settingsChangesInvalidateOldEpochAndRejectStaleEditorsAndSessions() = runBlocking {
        val dir = Files.createTempDirectory("control-grant-epoch")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val rows = db.rows()
            rows.upsertValues(listOf(
                KeyValueRow("kotlin_sync_session", """["account","one"]"""), KeyValueRow("device_id", "device"),
                KeyValueRow("cloud_session", "messenger_session=first"), KeyValueRow("cloud_session_host", "https://cloud.example"),
                KeyValueRow("cloud_user_json", """{"id":"account"}"""),
            ))
            val scope = KotlinRemoteControlScope("account", "one", "https://cloud.example", "device")
            val store = RoomDeviceControlGrantStore(db)
            fun config(enabled: Boolean): JsonObject = buildJsonObject { put("enabled", enabled); put("name", "Phone"); put("agents", JsonArray(emptyList())) }
            assertNull(store.read(scope).epoch)
            assertEquals(1L, store.save(scope, null, config(true)))
            rows.requireRemoteControl(scope.copy(grantEpoch = 1))
            assertFailsWith<IllegalStateException> { rows.requireRemoteControl(scope) }
            assertEquals(2L, store.save(scope, 1, config(false)))
            assertEquals(3L, store.save(scope, 2, config(true)))
            assertFailsWith<IllegalStateException> { rows.requireRemoteControl(scope.copy(grantEpoch = 1)) }
            rows.requireRemoteControl(scope.copy(grantEpoch = 3))
            assertFailsWith<IllegalStateException> { store.save(scope, 1, config(false)) }
            assertEquals(3L, store.read(scope).epoch)
            rows.upsertValues(listOf(KeyValueRow("kotlin_sync_session", """["account","two"]"""), KeyValueRow("cloud_session", "messenger_session=second")))
            assertFailsWith<IllegalStateException> { store.save(scope, 3, config(true)) }
            assertFailsWith<IllegalStateException> { rows.requireRemoteControl(scope.copy(grantEpoch = 3)) }
            assertEquals(4L, store.save(scope.copy(generation = "two"), 3, config(false)))
            assertNull(rows.getValue("device_control_grant"))
            rows.upsertValues(listOf(KeyValueRow(REMOTE_CONTROL_EPOCH_KEY, "damaged")))
            assertFailsWith<IllegalStateException> { store.read(scope.copy(generation = "two")) }
            assertFailsWith<IllegalStateException> { store.save(scope.copy(generation = "two"), null, config(true)) }
            assertEquals("damaged", rows.getValue(REMOTE_CONTROL_EPOCH_KEY)?.value)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
