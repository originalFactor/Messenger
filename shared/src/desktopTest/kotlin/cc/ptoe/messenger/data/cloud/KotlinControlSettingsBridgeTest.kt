/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinControlSettingsBridgeTest {
    @Test fun offlineTemplatesAndProfileEditsDoNotEnableControl(): Unit = runBlocking {
        val dir = Files.createTempDirectory("control-settings-offline")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val bridge = KotlinControlSettingsBridge(delegate(), db)
            val first = bridge.controlSettings()
            assertFalse(first.getValue("enabled").jsonPrimitive.boolean)
            assertEquals(listOf("codex", "claude-code", "opencode"), first.getValue("agents").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            assertFailsWith<IllegalStateException> { bridge.saveControlSettings(JsonObject(first + ("enabled" to JsonPrimitive(true)))) }
            val edit = bridge.controlSettings()
            bridge.saveControlSettings(JsonObject(edit + ("agents" to JsonArray(edit.getValue("agents").jsonArray.drop(1)))))
            val saved = bridge.controlSettings()
            assertEquals(listOf("claude-code", "opencode"), saved.getValue("agents").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            assertNull(db.rows().getValue("device_control_grant"))
            assertFalse(db.rows().getValue("device_control_config")!!.value.contains("_roomSettingsTicket"))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun concurrentEditorsRevocationAndSessionChangesAreFenced(): Unit = runBlocking {
        val dir = Files.createTempDirectory("control-settings-online")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val bridge = KotlinControlSettingsBridge(delegate(), db)
            val offline = bridge.controlSettings()
            val device = db.rows().getValue("device_id")!!.value
            db.rows().upsertValues(listOf(
                KeyValueRow("kotlin_sync_session", "[\"account\",\"one\"]"), KeyValueRow("cloud_session", "messenger_session=first"),
                KeyValueRow("cloud_session_host", "https://cloud.example"), KeyValueRow("cloud_user_json", "{\"id\":\"account\"}"),
            ))
            assertFailsWith<IllegalStateException> { bridge.saveControlSettings(offline) }
            val stale = bridge.controlSettings()
            val enable = bridge.controlSettings()
            bridge.saveControlSettings(JsonObject(enable + ("enabled" to JsonPrimitive(true))))
            assertTrue(bridge.controlSettings().getValue("enabled").jsonPrimitive.boolean)
            assertFailsWith<IllegalStateException> { bridge.saveControlSettings(stale) }
            val oldEpoch = db.rows().getValue(REMOTE_CONTROL_EPOCH_KEY)!!.value.toLong()
            val oldScope = KotlinRemoteControlScope("account", "one", "https://cloud.example", device, oldEpoch)
            db.rows().requireRemoteControl(oldScope)
            val disable = bridge.controlSettings()
            bridge.saveControlSettings(JsonObject(disable + ("enabled" to JsonPrimitive(false))))
            val reenable = bridge.controlSettings()
            bridge.saveControlSettings(JsonObject(reenable + ("enabled" to JsonPrimitive(true))))
            assertFailsWith<IllegalStateException> { db.rows().requireRemoteControl(oldScope) }
            val newEpoch = db.rows().getValue(REMOTE_CONTROL_EPOCH_KEY)!!.value.toLong()
            assertEquals(oldEpoch + 2, newEpoch)
            val oldSession = bridge.controlSettings()
            db.rows().upsertValues(listOf(KeyValueRow("kotlin_sync_session", "[\"account\",\"two\"]"), KeyValueRow("cloud_session", "messenger_session=second")))
            assertFailsWith<IllegalStateException> { bridge.saveControlSettings(oldSession) }
            assertFailsWith<IllegalStateException> { db.rows().requireRemoteControl(oldScope) }
            val newSession = bridge.controlSettings()
            db.rows().upsertValues(listOf(KeyValueRow("kotlin_sync_session", "[\"other\",\"three\"]"), KeyValueRow("cloud_user_json", "{\"id\":\"other\"}")))
            assertFailsWith<IllegalStateException> { bridge.saveControlSettings(newSession) }
            assertFalse(bridge.controlSettings().getValue("enabled").jsonPrimitive.boolean)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    private fun delegate(): CoreBridge = CoreBridge::class.java.cast(Proxy.newProxyInstance(
        CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java),
    ) { _, _, _ -> error("Unexpected delegate call") })
}
