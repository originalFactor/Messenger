/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.repository.RustCoreToolCatalog
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinRoomToolCatalogBridgeTest {
    @Test fun catalogTracksCapabilitiesWithoutPublishingExecutableTools(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-tool-catalog")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val delegate = CoreBridge::class.java.cast(Proxy.newProxyInstance(CoreBridge::class.java.classLoader,
            arrayOf(CoreBridge::class.java)) { _, _, _ -> error("Unexpected delegate call") })
        val bridge = KotlinRoomToolCatalogBridge(delegate, db)
        val catalog = RustCoreToolCatalog { bridge.learningRequest("GET", "/tools/core") }
        try {
            catalog.refresh()
            val initial = catalog.state.value.tools.associateBy { it.name }
            assertEquals(6, initial.size)
            assertEquals(coreLearningSpec.function.parameters.toString(), initial.getValue("messenger_learning").parametersJson)
            assertTrue(initial.getValue("messenger_skills").capabilityEnabled)
            assertFalse(initial.getValue("messenger_learning").capabilityEnabled)
            assertTrue(initial.getValue("messenger_learning").execute("{}").isError)
            db.rows().upsertValues(listOf(KeyValueRow("learning_tools_enabled", "true")))
            catalog.refresh()
            assertTrue(catalog.state.value.tools.single { it.name == "messenger_learning" }.capabilityEnabled)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
