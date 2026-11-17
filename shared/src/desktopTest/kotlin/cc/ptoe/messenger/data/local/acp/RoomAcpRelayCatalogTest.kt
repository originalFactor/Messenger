/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RoomAcpRelayCatalogTest {
    @Test fun providerCredentialsStayIsolatedAndEnabledStateIsLive(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-relay-db")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            db.rows().upsertProviders(listOf(
                ProviderRow("p1", "One", "https://one.test/v1", "secret-one", "chat", false, 1, 1),
                ProviderRow("p2", "Two", "https://two.test/v1", "secret-two", "responses", false, 1, 1),
            ))
            fun row(id: String, provider: String) = ModelRow(id = id, providerId = provider, modelId = "same-upstream", displayName = id,
                isEnabled = true, contextWindow = 0, inputRate = null, outputRate = null, cacheReadRate = null, cacheWriteRate = null,
                inputModalities = "text", outputModalities = "text", supportsToolCalling = false,
                supportsThinking = false, supportsJsonOutput = false, supportsTemperature = false,
                createdAt = 1, metadata = null, metadataOverrides = null)
            db.rows().upsertModels(listOf(row("m1", "p1"), row("m2", "p2")))
            val catalog = RoomAcpRelayCatalog(db)
            val first = catalog.find("m1")!!
            val second = catalog.find("m2")!!
            assertEquals("same-upstream", first.upstreamModel)
            assertEquals("same-upstream", second.upstreamModel)
            assertEquals("secret-one", first.apiKey)
            assertEquals("secret-two", second.apiKey)
            assertEquals(AcpRelayFormat.CHAT, first.format)
            assertEquals(AcpRelayFormat.RESPONSES, second.format)
            assertFalse(first.toString().contains("secret"))
            db.rows().upsertModels(listOf(row("m1", "p1").copy(isEnabled = false)))
            assertFalse(catalog.find("m1")!!.enabled)
            assertNull(catalog.find("missing"))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
