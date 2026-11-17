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
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.chat.UncertainMcpCall
import cc.ptoe.messenger.presentation.platform.PickedImage
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ChatWorkspaceCapabilityTest {
    @Test fun draftRemainsUntilAdmissionAcrossPendingUncertaintyAndRepositoryRace(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val dir = Files.createTempDirectory("chat-uncertain-draft")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val viewModels = ViewModelStore()
        val releaseRead = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()
        val releaseLateCheck = CompletableDeferred<Unit>()
        val releaseAcknowledgement = CompletableDeferred<Unit>()
        val releaseAdmission = CompletableDeferred<Unit>()
        try {
            val agents = RoomAgentRepository(db)
            val providers = RoomProviderRepository(db)
            val models = RoomModelRepository(db)
            val acp = object : AcpConversationStore {
                override fun observeAll(): Flow<List<Conversation>> = flowOf(emptyList())
                override suspend fun update(conversation: Conversation) = error("ACP unavailable")
                override suspend fun delete(id: String) = error("ACP unavailable")
            }
            val actual = RoomConversationRepository(db, acp)
            val holdRead = AtomicBoolean(false)
            val readEntered = CompletableDeferred<Unit>()
            val conversations = object : ConversationRepository by actual {
                override fun getById(id: String): Flow<Conversation?> = if (!holdRead.get()) actual.getById(id) else flow {
                    readEntered.complete(Unit); releaseRead.await(); emitAll(actual.getById(id))
                }
            }
            providers.insert(Provider("p", "Provider", "https://example.test", "key", createdAt = 1, updatedAt = 1))
            val model = ChatModel("m", "p", "model", "Model", true, createdAt = 1)
            models.insert(model)
            agents.ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "", defaultModelId = "m", temperature = 0.7f,
                topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            actual.insert(Conversation("c", "Chat", "p", "a", createdAt = 1, updatedAt = 1, lastMessage = null))
            actual.insert(Conversation("old", "Old", "p", "a", createdAt = 1, updatedAt = 1, lastMessage = null))
            val calls = AtomicInteger()
            val rejectAdmission = AtomicBoolean(true)
            val admissionEntered = CompletableDeferred<Unit>()
            val checks = AtomicInteger()
            val checkEntered = CompletableDeferred<Unit>()
            val lateCheckEntered = CompletableDeferred<Unit>()
            val acknowledgementEntered = CompletableDeferred<Unit>()
            val uncertain = UncertainMcpCall("turn", "call", "server_work", "{}", false, true)
            val oldUncertain = uncertain.copy(argumentsJson = "{\"path\":\"local\"}", canAcknowledge = false)
            val stub = Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, _, _ -> error("Unexpected Core call") } as CoreBridge
            val bridge = object : CoreBridge by stub {
                override val supportsUncertainMcpCalls = true
                override suspend fun backgroundTurnActive(conversationId: String) = false
                override suspend fun uncertainMcpCall(conversationId: String): UncertainMcpCall? {
                    if (conversationId == "old") return oldUncertain
                    return when (checks.incrementAndGet()) {
                        1 -> null
                        2 -> { checkEntered.complete(Unit); releaseCheck.await(); uncertain }
                        else -> { lateCheckEntered.complete(Unit); releaseLateCheck.await(); uncertain }
                    }
                }
                override suspend fun acknowledgeUncertainMcpCall(conversationId: String, turnId: String, callId: String) {
                    acknowledgementEntered.complete(Unit); releaseAcknowledgement.await()
                }
                override suspend fun startBackgroundTurn(config: TurnConfigBridge) {
                    calls.incrementAndGet()
                    if (rejectAdmission.get()) error("admission rejected")
                    admissionEntered.complete(Unit); releaseAdmission.await()
                }
                override suspend fun observeBackgroundTurn(conversationId: String, onEventJson: (String) -> Unit) {
                    onEventJson("""{"type":"TurnStarted"}""")
                    onEventJson("""{"type":"Finished"}""")
                }
            }
            val images = object : ChatImageStore {
                override suspend fun importImage(bytes: ByteArray, extension: String): MessageImage =
                    MessageImage("data:image/png;base64,AQ==", "image-${bytes.single()}")
                override fun deleteIfExists(path: String?) = Unit
            }
            val modelRepository = object : ModelRepository by models { override fun getAll() = flowOf(listOf(model)) }
            val vm = ChatViewModel(RoomMessageRepository(db), conversations, agents, modelRepository, providers, images,
                builtinTools = { emptyList() }, coreBridge = bridge, platformToolsEnabled = false)
            viewModels.put("chat", vm)
            vm.loadConversation("c")
            val collector = launch { vm.agent.collect() }
            try {
                withTimeout(5_000) { vm.agent.first { it?.id == "a" }; vm.uncertainMcpChecking.first { !it } }
                holdRead.set(true)
                var draft = "keep this draft"
                assertTrue(vm.sendMessage(draft) { draft = "" })
                withTimeout(5_000) { readEntered.await() }
                vm.loadConversation("c")
                withTimeout(5_000) { checkEntered.await(); vm.uncertainMcpChecking.first { it } }
                assertFalse(vm.sendMessage(draft) { draft = "" })
                vm.retrySend("missing"); vm.regenerateMessage("missing")
                releaseRead.complete(Unit)
                withTimeout(5_000) { vm.sendPreparing.first { !it } }
                assertEquals("keep this draft", draft)
                assertEquals(0, calls.get())
                releaseCheck.complete(Unit)
                withTimeout(5_000) { vm.uncertainMcpCall.first { it == uncertain } }
                assertFalse(vm.sendMessage(draft) { draft = "" })
                assertEquals("keep this draft", draft)
                vm.acknowledgeUncertainMcpCall()
                withTimeout(5_000) { acknowledgementEntered.await() }
                vm.retryUncertainMcpCheck()
                withTimeout(5_000) { lateCheckEntered.await() }
                releaseAcknowledgement.complete(Unit)
                withTimeout(5_000) { vm.uncertainMcpCall.first { it == null } }
                releaseLateCheck.complete(Unit)
                kotlinx.coroutines.yield()
                assertNull(vm.uncertainMcpCall.value)
                assertFalse(vm.uncertainMcpChecking.value)
                assertTrue(vm.sendMessage(draft) { draft = "" })
                withTimeout(5_000) { while (calls.get() < 1) kotlinx.coroutines.yield(); vm.isGenerating.first { !it }; vm.sendPreparing.first { !it } }
                assertEquals("keep this draft", draft) // Rejected backend admission must not clear it.
                rejectAdmission.set(false)
                vm.attachImage(PickedImage(byteArrayOf(1), "png", "image/png"))
                withTimeout(5_000) { vm.pendingImages.first { it.size == 1 } }
                val submitted = draft
                assertTrue(vm.sendMessage(submitted) { if (draft == submitted) draft = "" })
                withTimeout(5_000) { admissionEntered.await() }
                draft = "newer edit"
                vm.attachImage(PickedImage(byteArrayOf(2), "png", "image/png"))
                withTimeout(5_000) { vm.pendingImages.first { it.size == 2 } }
                releaseAdmission.complete(Unit)
                withTimeout(5_000) { vm.isGenerating.first { !it } }
                assertEquals("newer edit", draft)
                assertEquals(listOf("image-2"), vm.pendingImages.value.map { it.localPath })
                vm.loadConversation("old")
                withTimeout(5_000) { vm.uncertainMcpCall.first { it == oldUncertain } }
                assertFalse(vm.sendMessage(draft) { draft = "" })
                vm.acknowledgeUncertainMcpCall()
                assertFalse(vm.uncertainMcpBusy.value)
                assertEquals("{\"path\":\"local\"}", vm.uncertainMcpCall.value?.argumentsJson)
                assertEquals("newer edit", draft)
            } finally { collector.cancel() }
        } finally {
            releaseRead.complete(Unit); releaseCheck.complete(Unit); releaseLateCheck.complete(Unit); releaseAcknowledgement.complete(Unit); releaseAdmission.complete(Unit)
            viewModels.clear(); db.close(); dir.toFile().deleteRecursively(); Dispatchers.resetMain()
        }
    }

    @Test fun sendsUseOnlyTheBackendWorkspaceCapability(): Unit = runBlocking {
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
            val observed = Channel<Unit>(Channel.UNLIMITED)
            val titleAtDelivery = Channel<String?>(Channel.UNLIMITED)
            var currentError: () -> String? = { null }
            var turnEvents = listOf("""{"type":"Finished"}""")
            val stub = Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, _, _ ->
                error("Unexpected Core call")
            } as CoreBridge
            val bridge = object : CoreBridge by stub {
                override val supportsUncertainMcpCalls = false
                override suspend fun backgroundTurnActive(conversationId: String) = false
                override suspend fun startBackgroundTurn(config: TurnConfigBridge) { sent.send(config) }
                override suspend fun observeBackgroundTurn(conversationId: String, onEventJson: (String) -> Unit) {
                    for (event in turnEvents) {
                        onEventJson(event)
                        if (event.contains("\"type\":\"TitleFailed\"")) titleAtDelivery.send(currentError())
                    }
                    observed.send(Unit)
                }
            }
            val images = object : ChatImageStore {
                override suspend fun importImage(bytes: ByteArray, extension: String): MessageImage = error("Unexpected image")
                override fun deleteIfExists(path: String?) = Unit
            }
            suspend fun send(id: String, platformToolsEnabled: Boolean, durableNotices: Boolean = false,
                events: List<String> = listOf("""{"type":"Finished"}"""), expectedError: String? = null): TurnConfigBridge {
                turnEvents = events
                val vm = ChatViewModel(messages, conversations, agents, modelRepository, providers, images,
                    builtinTools = { emptyList() }, projectRepository = projects, coreBridge = bridge,
                    platformToolsEnabled = platformToolsEnabled, durableTurnFailureNotices = durableNotices)
                currentError = { vm.errorMessage.value }
                viewModels.put("$id-$platformToolsEnabled", vm)
                vm.loadConversation(id)
                val collector = launch { vm.agent.collect() }
                try {
                    withTimeout(5_000) { vm.agent.first { it?.id == "a" } }
                    vm.sendMessage("hello")
                    val config = withTimeout(5_000) { sent.receive() }
                    withTimeout(5_000) { observed.receive() }
                    if (events.any { it.contains("\"type\":\"TitleFailed\"") }) {
                        assertEquals(expectedError, withTimeout(5_000) { titleAtDelivery.receive() })
                    }
                    assertEquals(expectedError, vm.errorMessage.value)
                    return config
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
            for (type in listOf("SummaryFailed", "ApprovalFailed", "Error")) {
                val events = listOf("""{"type":"StreamingStarted","messageId":"assistant"}""",
                    """{"type":"$type","message":"failure detail"}""", """{"type":"Finished"}""")
                send("plain", false, durableNotices = true, events = events)
                send("plain", false, events = events, expectedError = "failure detail")
            }
            send("plain", false, durableNotices = true, events = listOf(
                """{"type":"TitleFailed","code":"title warning","durableNotice":true}""", """{"type":"Finished"}"""))
            send("plain", false, durableNotices = true, events = listOf(
                """{"type":"TitleFailed","code":"title warning"}""", """{"type":"Finished"}"""), expectedError = "title warning")
        } finally {
            viewModels.clear()
            db.close()
            dir.toFile().deleteRecursively()
            Dispatchers.resetMain()
        }
    }
}
