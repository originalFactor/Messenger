/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.StoredAgentDto
import cc.ptoe.messenger.core.StoredConversationDto
import cc.ptoe.messenger.core.StoredModelDto
import cc.ptoe.messenger.core.StoredProviderDto
import cc.ptoe.messenger.data.local.room.CLOUD_REPLACEMENT_LOCK_KEY
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ModelRow
import cc.ptoe.messenger.data.local.room.ProviderRow
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.Conversation
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinRoomCoreBaseTest {
    @Test fun readsAndSettingsUseOnlyTheRoomOwnerAndUnsupportedCallsFailClosed(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-core-base")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val acp = object : AcpConversationStore {
            override fun observeAll() = flowOf(listOf(Conversation("acp:session", "ACP", "", "acp:profile", createdAt = 1, updatedAt = 2, lastMessage = null)))
            override suspend fun update(conversation: Conversation) = error("Unexpected update")
            override suspend fun delete(id: String) = error("Unexpected delete")
        }
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("agent", "Default", systemPrompt = "prompt", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 2))
            db.rows().upsertProviders(listOf(ProviderRow("provider", "Provider", "https://example.test", "key", "responses", true, 1, 2)))
            db.rows().upsertModels(listOf(ModelRow("model", "provider", "remote-model", "Model", true, 8192, null, null,
                null, null, "text,image", "text", true, true, false, true, 3, null, null)))
            val bridge = KotlinRoomCoreBase(db, acp)

            assertEquals("agent", Json.decodeFromString<StoredAgentDto>(checkNotNull(bridge.getDefaultAgentJson())).id)
            assertEquals("acp:profile", Json.decodeFromString<StoredConversationDto>(checkNotNull(bridge.getConversationJson("acp:session"))).agentId)
            assertEquals("responses", Json.decodeFromString<StoredProviderDto>(checkNotNull(bridge.getProviderJson("provider"))).apiFormat)
            assertEquals(listOf("remote-model"), Json.decodeFromString<List<StoredModelDto>>(bridge.listModelsByProviderJson("provider")).map { it.modelId })
            assertEquals(emptyList(), Json.decodeFromString<List<StoredModelDto>>(bridge.listModelsByProviderJson("other")))

            assertNull(bridge.kvGet("current_agent_id"))
            bridge.kvSet("current_agent_id", "agent")
            assertEquals("agent", db.rows().getValue("current_agent_id")?.value)
            db.rows().upsertValues(listOf(KeyValueRow(CLOUD_REPLACEMENT_LOCK_KEY, "held")))
            assertFailsWith<IllegalStateException> { bridge.kvDelete("current_agent_id") }
            assertEquals("agent", bridge.kvGet("current_agent_id"))
            db.rows().deleteValue(CLOUD_REPLACEMENT_LOCK_KEY)
            bridge.kvDelete("current_agent_id")
            assertNull(bridge.kvGet("current_agent_id"))
            assertFailsWith<IllegalArgumentException> { bridge.kvSet("cloud_session", "replacement") }
            assertFailsWith<IllegalArgumentException> { bridge.kvGet("cloud_session") }

            assertFalse(bridge.agentDelegationEnabled())
            bridge.setAgentDelegationEnabled(true)
            assertEquals("true", db.rows().getValue("mcp_agent_delegation_enabled")?.value)
            assertTrue(bridge.agentDelegationEnabled())
            bridge.setAgentDelegationEnabled(false)
            assertFalse(bridge.agentDelegationEnabled())

            assertFailsWith<IllegalStateException> { bridge.deleteAgent("agent") }
            assertFailsWith<IllegalStateException> { bridge.cloudLogin("user", "password") }
            assertFailsWith<IllegalStateException> { bridge.hasActiveTasks() }
            assertFailsWith<IllegalStateException> { bridge.backgroundTurnActive("conversation") }
            assertFailsWith<IllegalStateException> { bridge.subscribe { _, _ -> } }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
