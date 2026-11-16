/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.viewmodel

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.repository.room.AcpConversationStore
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.data.repository.room.RoomConversationRepository
import cc.ptoe.messenger.data.repository.room.RoomMessageRepository
import cc.ptoe.messenger.data.repository.room.RoomModelRepository
import cc.ptoe.messenger.data.repository.room.RoomProjectRepository
import cc.ptoe.messenger.data.repository.room.RoomProviderRepository
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.MessageImage
import cc.ptoe.messenger.domain.model.Project
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.repository.ModelRepository
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatWorkspaceCapabilityTest {
    @Test fun sendsUseOnlyTheBackendWorkspaceCapability() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val dir = Files.createTempDirectory("chat-workspace-capability")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val viewModels = ViewModelStore()
        try {
            val agents = RoomAgentRepository(db)
            val providers = RoomProviderRepository(db)
            val models = RoomModelRepository(db)
            val projects = RoomProjectRepository(db)
            val acp = object : AcpConversationStore {
                override fun observeAll(): Flow<List<Conversation>> = flowOf(emptyList())
                override suspend fun update(conversation: Conversation) = error("ACP unavailable")
                override suspend fun delete(id: String) = error("ACP unavailable")
            }
            val conversations = RoomConversationRepository(db, acp)
            val messages = RoomMessageRepository(db)
            providers.insert(Provider("p", "Provider", "https://example.test", "key", createdAt = 1, updatedAt = 1))
            val model = ChatModel("m", "p", "model", "Model", true, createdAt = 1)
            models.insert(model)
            val modelRepository = object : ModelRepository by models {
                override fun getAll() = flowOf(listOf(model))
            }
            agents.ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = "m",
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
            projects.insert(Project("project", "Project", "/workspace", 1, 1))
            conversations.insert(Conversation("plain", "Chat", "p", "a", createdAt = 1, updatedAt = 1, lastMessage = null))
            conversations.insert(Conversation("project-chat", "Chat", "p", "a", projectId = "project", createdAt = 1, updatedAt = 1, lastMessage = null))

            val sent = Channel<TurnConfigBridge>(Channel.UNLIMITED)
            val stub = Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, _, _ ->
                error("Unexpected Core call")
            } as CoreBridge
            val bridge = object : CoreBridge by stub {
                override suspend fun backgroundTurnActive(conversationId: String) = false
                override suspend fun startBackgroundTurn(config: TurnConfigBridge) { sent.send(config) }
                override suspend fun observeBackgroundTurn(conversationId: String, onEventJson: (String) -> Unit) {
                    onEventJson("""{"type":"Finished"}""")
                }
            }
            val images = object : ChatImageStore {
                override suspend fun importImage(bytes: ByteArray, extension: String): MessageImage = error("Unexpected image")
                override fun deleteIfExists(path: String?) = Unit
            }
            suspend fun send(id: String, platformToolsEnabled: Boolean): TurnConfigBridge {
                val vm = ChatViewModel(messages, conversations, agents, modelRepository, providers, images,
                    builtinTools = { emptyList() }, projectRepository = projects, coreBridge = bridge,
                    platformToolsEnabled = platformToolsEnabled)
                viewModels.put("$id-$platformToolsEnabled", vm)
                vm.loadConversation(id)
                val collector = launch { vm.agent.collect() }
                try {
                    withTimeout(5_000) { vm.agent.first { it?.id == "a" } }
                    vm.sendMessage("hello")
                    return withTimeout(5_000) { sent.receive() }
                } finally { collector.cancel() }
            }

            for (id in listOf("plain", "project-chat")) {
                val config = send(id, platformToolsEnabled = false)
                assertFalse(config.hasWorkspace)
                assertNull(config.workspacePath)
                assertEquals("", config.workspaceNote)
                assertFalse(config.disableTools) // Core-owned tools remain available.
            }
            val production = send("project-chat", platformToolsEnabled = true)
            assertTrue(production.hasWorkspace)
            assertTrue(production.workspaceNote.contains("/workspace"))
            assertFalse(production.disableTools)
        } finally {
            viewModels.clear()
            db.close()
            dir.toFile().deleteRecursively()
            Dispatchers.resetMain()
        }
    }
}
