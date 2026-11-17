/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoomDeviceHostAccessSourceTest {
    @Test fun freshDeviceIdentityIsAtomicAndInvalidIdentityIsNeverOverwritten(): Unit = runBlocking {
        val dir = Files.createTempDirectory("device-host-identity")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val source = RoomDeviceHostAccessSource(db) { "Messenger · $it" }
            val ids = (1..8).map { async(Dispatchers.Default) { source.ensureDeviceId() } }.awaitAll()
            assertEquals(1, ids.distinct().size)
            assertEquals(ids.first(), db.rows().getValue("device_id")?.value)
            db.rows().upsertValues(listOf(KeyValueRow("device_id", "invalid identity")))
            assertFailsWith<IllegalStateException> { source.ensureDeviceId() }
            assertEquals("invalid identity", db.rows().getValue("device_id")?.value)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun observesAccountScopedGrantAndNeverCarriesItAcrossSessions(): Unit = runBlocking {
        val dir = Files.createTempDirectory("device-host-access")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            db.rows().upsertValues(listOf(
                KeyValueRow("kotlin_sync_session", """["first","generation-one"]"""), KeyValueRow("device_id", "device"),
                KeyValueRow("cloud_session", "messenger_session=one"), KeyValueRow("cloud_session_host", "https://cloud.example"),
                KeyValueRow("cloud_user_json", """{"id":"first"}"""), KeyValueRow("device_control_grant", """["https://cloud.example","first"]"""),
                KeyValueRow("device_control_config", """{"enabled":true,"name":"Phone"}"""), KeyValueRow(REMOTE_CONTROL_EPOCH_KEY, "1"),
            ))
            val source = RoomDeviceHostAccessSource(db) { "Messenger · $it" }
            val state = source.watch(owner)
            val first = withTimeout(5_000) { state.first { it != null } }!!
            assertEquals("first", first.session.session.accountId)
            assertEquals("Phone", first.grant?.name)
            assertTrue(source.stillCurrent(first.session))
            val settings = RoomDeviceControlGrantStore(db)
            val scope = KotlinRemoteControlScope("first", "generation-one", "https://cloud.example", "device")
            settings.save(scope, 1, buildJsonObject { put("enabled", false); put("name", "Phone") })
            settings.save(scope, 2, buildJsonObject { put("enabled", true); put("name", "Phone") })
            withTimeout(5_000) { state.first { it?.grant?.epoch == 3L } }
            assertTrue(source.stillCurrent(first.session))
            db.rows().upsertValues(listOf(
                KeyValueRow("kotlin_sync_session", """["second","generation-two"]"""),
                KeyValueRow("cloud_session", "messenger_session=two"), KeyValueRow("cloud_user_json", """{"id":"second"}"""),
            ))
            withTimeout(5_000) { state.first { it?.session?.session?.accountId == "second" && it?.grant?.name == null } }
            assertFalse(source.stillCurrent(first.session))
            db.rows().upsertValues(listOf(KeyValueRow("device_control_grant", """["https://cloud.example","second"]""")))
            val second = withTimeout(5_000) { state.first { it?.session?.session?.accountId == "second" } }!!
            assertTrue(source.stillCurrent(second.session))
            db.rows().upsertValues(listOf(KeyValueRow("device_control_config", """{"enabled":true,"name":""}""")))
            assertEquals("Messenger · device", withTimeout(5_000) { state.first { it?.grant?.name == "Messenger · device" } }?.grant?.name)
            db.rows().upsertValues(listOf(KeyValueRow("cloud_session", "messenger_session=two; leaked=bad")))
            withTimeout(5_000) { state.first { it == null } }
            assertFalse(source.stillCurrent(second.session))
        } finally { owner.cancel(); owner.coroutineContext[Job]?.join(); db.close(); dir.toFile().deleteRecursively() }
    }
}
