/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.wear

import android.system.Os
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.CoreBridgeRegistry
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomShutdownState
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.repository.room.KotlinRoomBackendSlice
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.di.AppBackend
import cc.ptoe.messenger.di.AppContainer
import cc.ptoe.messenger.di.AppDirs
import cc.ptoe.messenger.di.LocalStores
import cc.ptoe.messenger.domain.chat.KotlinTurnProvider
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.MessageImage
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.repository.CloudFacade
import java.nio.file.Files
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobileWearChatHandlerDeviceTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }

    @Test fun realRoomHandlerPreservesModelErrorsFramesAndCancelledSync() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = Files.createTempDirectory(context.filesDir.toPath(), "wear-handler-")
        Os.chmod(dir.toString(), 448)
        val assets = Files.createDirectory(dir.resolve("assets")); Os.chmod(assets.toString(), 448)
        val db = Room.databaseBuilder<LocalDatabase>(context, dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val owner = RoomManagedShutdown { }
        val previous = CoreBridgeRegistry.bridge
        val entered = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val changes = AtomicInteger()
        var app: AppContainer? = null
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Default", systemPrompt = "", defaultModelId = "m", temperature = 0.7f,
                topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            owner.enter()
            val preferences = MemoryPreferences()
            val prefs = AppPreferences(preferences)
            val theme = ThemePreferences(preferences)
            val provider = KotlinTurnProvider { _, request -> flow {
                assertTrue(request.tools.isNullOrEmpty())
                when (calls.incrementAndGet()) {
                    1 -> { emit(ChatStreamEvent.Content("answer")); emit(ChatStreamEvent.Done("stop")) }
                    2 -> { emit(ChatStreamEvent.Content("partial")); emit(ChatStreamEvent.Error("HTTP 503: unavailable")) }
                    4 -> { emit(ChatStreamEvent.Content("override answer")); emit(ChatStreamEvent.Done("stop")) }
                    else -> { entered.complete(Unit); awaitCancellation() }
                }
            } }
            val slice = KotlinRoomBackendSlice.attach(db, owner, prefs, theme, assets, dir.resolve("avatars"), dir.resolve("images"), turnProvider = provider)
            val original = slice.asAppBackend()
            val bridge = object : CoreBridge by original.coreBridge {
                override suspend fun runScopedTurn(config: TurnConfigBridge, toolExecutor: suspend (String, String) -> Pair<String, Boolean>, onEventJson: (String) -> Unit) {
                    if (config.conversationId == "truncated") onEventJson("""{"type":"TextDelta","text":"partial"}""")
                    else original.coreBridge.runScopedTurn(config, toolExecutor, onEventJson)
                }
            }
            val cloud = object : CloudFacade by original.stores.cloud {
                override fun requestLocalChange(type: String, id: String, deleted: Boolean) {
                    changes.incrementAndGet(); original.stores.cloud.requestLocalChange(type, id, deleted)
                }
            }
            val backend = AppBackend(LocalStores(cloud, prefs, theme), bridge, original.providers, original.models, original.agents,
                original.conversations, original.projects, original.messages, original.currentAgent, original.learning,
                original.shutdown, original.clearLocalData, platformToolsEnabled = false, turnFailureNotices = original.turnFailureNotices)
            CoreBridgeRegistry.bridge = bridge
            val images = object : ChatImageStore {
                override suspend fun importImage(bytes: ByteArray, extension: String): MessageImage = error("unused")
                override fun deleteIfExists(path: String?) = Unit
            }
            val container = AppContainer(AppDirs(dir.toString().toPath(), dir.resolve("cache").toString().toPath()), images, backend)
            app = container
            container.providerRepository.insert(Provider("p", "Provider", "https://example.test/v1", "key", createdAt = 1, updatedAt = 1))
            container.modelRepository.insert(ChatModel("m", "p", "model", "Model", true, createdAt = 1))
            val noModel = Agent("missing", "Missing", systemPrompt = "", defaultModelId = null, temperature = 0.7f,
                topP = 1f, maxTokens = null, followDefaultModel = true, createdAt = 1, updatedAt = 1)
            container.agentRepository.insert(noModel)
            fun conversation(id: String, agent: String = "a", override: String? = null) =
                Conversation(id, "Chat", "p", agent, overrideModelId = override, createdAt = 1, updatedAt = 1, lastMessage = null)
            container.conversationRepository.insert(conversation("missing", "missing", "m"))
            container.conversationRepository.insert(conversation("success"))
            container.conversationRepository.insert(conversation("fail"))
            container.conversationRepository.insert(conversation("cancel"))
            container.conversationRepository.insert(conversation("truncated"))
            val handler = MobileWearChatHandler(container)
            fun payload(id: String) = JSONObject().put("requestId", id).put("conversationId", id).put("text", "hello").toString().encodeToByteArray()
            suspend fun frames(id: String): List<JSONObject> {
                val result = mutableListOf<JSONObject>()
                handler.handleChatRequestStreaming(payload(id)) { result.add(it) }
                return result
            }
            assertFalse(handler.isAgentReady(noModel))
            assertTrue(handler.isAgentReady(container.agentRepository.getById("a").first()!!))
            assertFalse(handler.isAgentReady(noModel.copy(defaultModelId = "absent", followDefaultModel = false)))
            assertTrue(handler.isAgentReady(noModel.copy(defaultModelId = "absent", followDefaultModel = true)))
            val missing = frames("missing")
            assertEquals("chat_error", missing.single().getString("type"))
            assertEquals("model_required", missing.single().getString("code"))
            assertTrue(container.messageRepository.getByConversationId("missing").first().isEmpty())
            assertEquals(0, calls.get())
            assertEquals(0, changes.get())

            val success = withTimeout(10_000) { frames("success") }
            assertEquals(listOf("chat_accepted", "chat_accepted", "chat_delta", "chat_done"), success.map { it.getString("type") })
            val messages = container.messageRepository.getByConversationId("success").first()
            assertEquals(messages.single { it.role.name == "USER" }.id, success.last().getString("userMessageId"))
            assertEquals(messages.single { it.role.name == "USER" }.id, success.first().getString("userMessageId"))
            assertEquals(messages.single { it.role.name == "ASSISTANT" }.id, success.last().getString("assistantMessageId"))
            assertEquals(messages.single { it.role.name == "ASSISTANT" }.id, success[1].getString("assistantMessageId"))
            assertEquals("answer", success.last().getString("content"))
            assertEquals(1, calls.get())
            assertEquals(1, changes.get())

            val failure = withTimeout(10_000) { frames("fail") }
            assertEquals(listOf("chat_accepted", "chat_accepted", "chat_delta", "chat_error"), failure.map { it.getString("type") })
            assertTrue(failure.last().getString("error").contains("503"))
            assertFalse(failure.last().has("code"))
            assertEquals(2, calls.get())
            assertEquals(2, changes.get())
            assertEquals(2, container.messageRepository.getByConversationId("fail").first().size)

            val truncated = withTimeout(10_000) { frames("truncated") }
            assertEquals(listOf("chat_delta", "chat_error"), truncated.map { it.getString("type") })
            assertTrue(truncated.last().getString("error").contains("before completion"))
            assertEquals(3, changes.get())

            val cancel = async { frames("cancel") }
            withTimeout(10_000) { entered.await() }
            cancel.cancelAndJoin()
            assertEquals(3, calls.get())
            assertEquals(4, changes.get())
            assertEquals(2, container.messageRepository.getByConversationId("cancel").first().size)

            container.agentRepository.update(container.agentRepository.getDefaultAgent().first()!!.copy(defaultModelId = null))
            val following = noModel.copy(id = "follow", defaultModelId = "m", followDefaultModel = true)
            container.agentRepository.insert(following)
            container.conversationRepository.insert(conversation("inherited-missing", "follow"))
            container.conversationRepository.insert(conversation("valid-override", "follow", "m"))
            val before = container.conversationRepository.getById("inherited-missing").first()
            val missingInherited = frames("inherited-missing")
            assertEquals("chat_error", missingInherited.single().getString("type"))
            assertEquals("model_required", missingInherited.single().getString("code"))
            assertEquals(before, container.conversationRepository.getById("inherited-missing").first())
            assertTrue(container.messageRepository.getByConversationId("inherited-missing").first().isEmpty())
            assertEquals(3, calls.get())
            val overridden = withTimeout(10_000) { frames("valid-override") }
            assertEquals(listOf("chat_accepted", "chat_accepted", "chat_delta", "chat_done"), overridden.map { it.getString("type") })
            assertEquals("override answer", overridden.last().getString("content"))
            assertEquals(4, calls.get())
        } finally {
            app?.shutdown()
            if (owner.state.value == RoomShutdownState.Running) owner.stopAndAwait()
            CoreBridgeRegistry.bridge = previous
            db.close()
            Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) } }
        }
    }
}
