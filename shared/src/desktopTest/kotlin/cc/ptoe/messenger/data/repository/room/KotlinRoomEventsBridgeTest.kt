/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KotlinRoomEventsBridgeTest {
    @Test fun settingsChangesNotifySubscribersUntilShutdown(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-event-bridge")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val delegate = CoreBridge::class.java.cast(Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, _, _ -> error("Unexpected delegate call") })
        val bridge = KotlinRoomEventsBridge(delegate, db)
        val events = Channel<String>(Channel.UNLIMITED)
        try {
            bridge.subscribe { kind, _ -> events.trySend(kind) }
            assertEquals("Other", withTimeout(5_000) { events.receive() })
            db.rows().upsertValues(listOf(KeyValueRow("learning_tools_enabled", "true")))
            assertEquals("Other", withTimeout(5_000) { events.receive() })
            bridge.shutdown()
            db.rows().upsertValues(listOf(KeyValueRow("learning_tools_enabled", "false")))
            assertNull(withTimeoutOrNull(200) { events.receive() })
            assertFailsWith<IllegalStateException> { bridge.subscribe { _, _ -> } }
        } finally { bridge.shutdown(); db.close(); dir.toFile().deleteRecursively() }
    }
}
