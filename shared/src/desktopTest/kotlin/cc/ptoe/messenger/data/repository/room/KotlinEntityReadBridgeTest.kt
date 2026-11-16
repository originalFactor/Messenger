/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.StoredAgentDto
import cc.ptoe.messenger.core.StoredConversationDto
import cc.ptoe.messenger.core.StoredProjectDto
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ProjectRow
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.Conversation
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KotlinEntityReadBridgeTest {
    @Test fun existingUiReadsUseRoomAndPreserveAcpSessions() = runBlocking {
        val dir = Files.createTempDirectory("room-entity-bridge")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val delegate = CoreBridge::class.java.cast(Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, _, _ -> error("Unexpected delegate call") })
        val acpConversation = Conversation("acp:session", "ACP", "", "acp:profile", projectId = "project", createdAt = 1, updatedAt = 2, lastMessage = "hello")
        val acp = object : AcpConversationStore {
            override fun observeAll() = flowOf(listOf(acpConversation))
            override suspend fun update(conversation: Conversation) = error("unexpected")
            override suspend fun delete(id: String) = error("unexpected")
        }
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("agent", "Assistant", systemPrompt = "prompt", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 2))
            db.rows().upsertProjects(listOf(ProjectRow("project", "Workspace", "/workspace", "device", 3, 4)))
            db.rows().upsertConversations(listOf(ConversationRow("conversation", "Chat", "provider", "agent", "project", null,
                null, null, null, null, null, null, false, 5, 6, "recent", null, null, 0, 0, 0)))
            val bridge = KotlinEntityReadBridge(delegate, db, acp)
            assertEquals("agent", Json.decodeFromString<List<StoredAgentDto>>(bridge.listAgentsJson()).single().id)
            assertEquals("agent", Json.decodeFromString<StoredAgentDto>(checkNotNull(bridge.getDefaultAgentJson())).id)
            assertEquals("/workspace", Json.decodeFromString<List<StoredProjectDto>>(bridge.listProjectsJson()).single().workspace)
            assertEquals("project", Json.decodeFromString<StoredProjectDto>(checkNotNull(bridge.getProjectJson("project"))).id)
            assertEquals("recent", Json.decodeFromString<StoredConversationDto>(checkNotNull(bridge.getConversationJson("conversation"))).lastMessage)
            assertEquals("project", Json.decodeFromString<StoredConversationDto>(checkNotNull(bridge.getConversationJson("acp:session"))).projectId)
            assertNull(bridge.getConversationJson("missing"))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
