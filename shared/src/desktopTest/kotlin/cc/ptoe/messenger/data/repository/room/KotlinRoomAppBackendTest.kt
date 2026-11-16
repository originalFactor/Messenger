/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridgeRegistry
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomShutdownState
import cc.ptoe.messenger.di.AppContainer
import cc.ptoe.messenger.di.AppDirs
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.model.Provider
import com.sun.net.httpserver.HttpServer
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KotlinRoomAppBackendTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test fun appContainerUsesOneRoomOwnerAndRefusesUnsupportedOperations(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-app-backend")
        val assets = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val requests = AtomicInteger()
        val mcpRequests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange ->
            mcpRequests.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.createContext("/v1/chat/completions") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.close()
            val bytes = ("data: {\"choices\":[{\"delta\":{\"content\":\"reply\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n").toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val previous = CoreBridgeRegistry.bridge
        val lifecycle = RoomManagedShutdown { }
        var container: AppContainer? = null
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            lifecycle.enter()
            val prefs = MemoryPreferences()
            val base = "http://127.0.0.1:${server.address.port}"
            val appPreferences = AppPreferences(prefs)
            appPreferences.setMcpServersJson("""[{"id":"external","name":"External","transportType":"SSE","isEnabled":true,"url":"$base/mcp"}]""")
            val slice = KotlinRoomBackendSlice.attach(db, lifecycle, appPreferences, ThemePreferences(prefs),
                assets, dir.resolve("avatars"), dir.resolve("images"), base, "$base/models.json")
            val backend = slice.asAppBackend()
            CoreBridgeRegistry.bridge = backend.coreBridge
            val images = ChatImageStore::class.java.cast(Proxy.newProxyInstance(ChatImageStore::class.java.classLoader,
                arrayOf(ChatImageStore::class.java)) { _, _, _ -> null })
            val app = AppContainer(AppDirs(dir.toString().toPath(), dir.resolve("cache").toString().toPath()), images, backend)
            container = app
            assertSame(slice.bridge, app.coreBridge)
            assertSame(slice.stores, app.stores)
            assertSame(slice.providers, app.providerRepository)
            assertTrue(app.availableTools(dir.toString()).isEmpty())
            assertFailsWith<IllegalStateException> { app.mcpManager.loadServers() }
            assertFailsWith<IllegalStateException> { app.coreBridge.startBackgroundAcp("request", buildJsonObject { }) }
            assertFailsWith<IllegalStateException> { app.coreBridge.clearLocalDataForReinit() }

            app.providerRepository.insert(Provider("p", "Provider", "$base/v1", "key", createdAt = 1, updatedAt = 1))
            app.modelRepository.insert(ChatModel("m", "p", "model", "Model", true, createdAt = 1))
            app.conversationRepository.insert(Conversation("c", "Chat", "p", "a", createdAt = 1, updatedAt = 1, lastMessage = null))
            val config = TurnConfigBridge("c", "model", "", "", "chat", "", null, null, null, null,
                emptyList(), false, 0, "", null, null, null, false, "", operation = "send", content = "hello",
                disableTools = true)
            val conversation = app.conversationRepository.getById("c").first()
            assertFailsWith<IllegalStateException> { app.coreBridge.startBackgroundTurn(config) }
            assertTrue(app.messageRepository.getByConversationId("c").first().isEmpty())
            assertEquals(conversation, app.conversationRepository.getById("c").first())
            app.messageRepository.insert(Message("failed", "c", MessageRole.ASSISTANT, "failed", timestamp = 2,
                status = MessageStatus.ERROR, errorMessage = "test"))
            val beforeRetry = app.messageRepository.getByConversationId("c").first()
            for (operation in listOf("retry", "regenerate")) {
                assertFailsWith<IllegalStateException> {
                    app.coreBridge.startBackgroundTurn(config.copy(operation = operation, messageId = "failed"))
                }
                assertEquals(beforeRetry, app.messageRepository.getByConversationId("c").first())
            }
            app.messageRepository.delete("failed")
            assertEquals(0, requests.get())
            app.agentRepository.delete("a")
            assertEquals(listOf("a"), app.agentRepository.getAll().first().filter { it.isDefault }.map { it.id })
            val agent = app.agentRepository.getDefaultAgent().first()!!
            app.agentRepository.update(agent.copy(defaultModelId = "m"))
            assertFailsWith<IllegalArgumentException> {
                app.coreBridge.startBackgroundTurn(config.copy(hasWorkspace = true, disableTools = false))
            }
            app.coreBridge.startBackgroundTurn(config)
            withTimeout(10_000) { app.coreBridge.observeBackgroundTurn("c") { } }
            assertEquals(listOf("hello", "reply"), app.messageRepository.getByConversationId("c").first().map { it.content })
            assertEquals(1, requests.get())
            assertEquals(0, mcpRequests.get())
            assertFailsWith<IllegalStateException> { app.clearAllDataAndReinit() }
            assertEquals(listOf("hello", "reply"), app.messageRepository.getByConversationId("c").first().map { it.content })
            assertEquals("a", app.agentRepository.getDefaultAgent().first()?.id)
            app.shutdown()
            lifecycle.stopAndAwait() // The enclosing owner can also await the same stop before closing Room.
            assertEquals(RoomShutdownState.Stopped, lifecycle.state.value)
            assertFailsWith<IllegalStateException> { app.coreBridge.startBackgroundTurn(config) }
            assertFailsWith<IllegalStateException> { slice.asAppBackend() }
            assertEquals(null, CoreBridgeRegistry.bridge)
        } finally {
            container?.shutdown()
            if (lifecycle.state.value == RoomShutdownState.Running) lifecycle.stopAndAwait()
            CoreBridgeRegistry.bridge = previous
            db.close()
            server.stop(0)
            dir.toFile().deleteRecursively()
        }
    }
}
