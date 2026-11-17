/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.model.Agent
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class RoomAcpTakeoverScopeTest {
    @Test fun snapshotExcludesCloudAndRevokesChangedModelsAndProviderCredentials(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-takeover-scope")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val provider = ProviderRow("p", "BYOK", "https://provider.test/v1", "PRIVATE-KEY", "auto", false, 1, 1)
            val cloud = ProviderRow(BUILTIN_PROVIDER_ID, "Cloud", "https://cloud.test/v1", "CLOUD-SECRET", "chat", false, 1, 1)
            db.rows().upsertProviders(listOf(provider, cloud))
            fun model(id: String, owner: String) = ModelRow(id, owner, "upstream", id, true, 0, null, null, null, null,
                "text", "text", false, false, false, false, 1, null, null)
            val byok = model("byok", "p")
            db.rows().upsertModels(listOf(byok, model("cloud", BUILTIN_PROVIDER_ID)))
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "", defaultModelId = "byok",
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = false, createdAt = 1, updatedAt = 1))
            val scope = RoomAcpTakeoverScope.capture(db)
            assertEquals(listOf("byok"), scope.models.map { it.id })
            assertEquals("byok", scope.preferredModelId)
            assertFalse(scope.toString().contains("PRIVATE-KEY"))
            assertNull(scope.find("cloud"))
            assertEquals("PRIVATE-KEY", scope.find("byok")!!.apiKey)
            val defaultAgent = db.rows().defaultAgents().single()
            db.rows().upsertAgents(listOf(defaultAgent.copy(defaultModelId = "cloud")))
            assertFails { RoomAcpTakeoverScope.capture(db) }
            db.rows().upsertAgents(listOf(defaultAgent.copy(defaultModelId = null)))
            assertFails { RoomAcpTakeoverScope.capture(db) }
            db.rows().upsertAgents(listOf(defaultAgent.copy(defaultModelId = "missing")))
            assertFails { RoomAcpTakeoverScope.capture(db) }
            db.rows().upsertAgents(listOf(defaultAgent))
            db.rows().upsertModels(listOf(byok.copy(isEnabled = false)))
            assertFails { RoomAcpTakeoverScope.capture(db) }
            db.rows().upsertModels(listOf(byok))
            db.rows().upsertProviders(listOf(provider.copy(apiFormat = "unsupported")))
            assertFails { RoomAcpTakeoverScope.capture(db) }
            db.rows().upsertProviders(listOf(provider))
            db.rows().upsertProviders(listOf(provider.copy(apiKey = "NEW-KEY")))
            assertNull(scope.find("byok"))
            db.rows().upsertProviders(listOf(provider.copy(apiFormat = "chat")))
            assertNull(scope.find("byok")) // auto and chat normalize equally but are distinct saved settings.
            db.rows().upsertProviders(listOf(provider))
            db.rows().upsertModels(listOf(byok.copy(modelId = "different")))
            assertNull(scope.find("byok"))
            db.rows().upsertModels(listOf(byok))
            db.rows().upsertValues(listOf(KeyValueRow(CLOUD_REPLACEMENT_LOCK_KEY, "locked")))
            assertFails { scope.find("byok") }
            assertFails { RoomAcpTakeoverScope.capture(db) }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
