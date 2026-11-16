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
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomShutdownState
import cc.ptoe.messenger.di.AppContainer
import cc.ptoe.messenger.di.AppDirs
import cc.ptoe.messenger.domain.chat.TurnFailureNotice
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.presentation.platform.TaskPushEvent
import cc.ptoe.messenger.presentation.platform.TaskPushNotifications
import com.sun.net.httpserver.HttpServer
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomAppTaskStatusTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test fun appContainerPublishesChatStatusWithoutSecretsOrReplayDuplicates(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-app-task-status")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val requests = AtomicInteger()
        val waiting = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val args = """{"action":"upsert","record":{"id":"note","title":"Notes","source":{"kind":"manual","snapshot":"source"},"content":{"kind":"item","itemKind":"note"}}}"""
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.readBytes()
            when (requests.incrementAndGet()) {
                1 -> {
                    val encoded = Json.encodeToString(args)
                    val bytes = ("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"messenger_learning\",\"arguments\":$encoded}}]},\"finish_reason\":\"tool_calls\"}]}\n\n" + "data: [DONE]\n\n").toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                2 -> {
                    val bytes = "data: {\"choices\":[{\"delta\":{\"content\":\"saved\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                3 -> {
                    val bytes = "provider temporarily unavailable".toByteArray()
                    exchange.sendResponseHeaders(503, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                else -> {
                    try {
                        exchange.responseHeaders.add("Content-Type", "text/event-stream")
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.write("data: {\"choices\":[{\"delta\":{\"content\":\"pending\"}}]}\n\n".toByteArray())
                        exchange.responseBody.flush()
                        waiting.complete(Unit)
                        release.await(10, TimeUnit.SECONDS)
                    } catch (_: Exception) { } finally { exchange.close() }
                }
            }
        }
        server.start()
        val lifecycle = RoomManagedShutdown { }
        val previous = CoreBridgeRegistry.bridge
        val previousPushHandler = TaskPushNotifications.onEvent
        var app: AppContainer? = null
        try {
            if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) return@runBlocking
            val assets = Files.createDirectory(dir.resolve("assets"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = "m",
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
            db.rows().upsertValues(listOf(KeyValueRow("learning_tools_enabled", "true")))
            lifecycle.enter()
            val prefs = MemoryPreferences()
            val base = "http://127.0.0.1:${server.address.port}"
            val slice = KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs),
                assets, dir.resolve("avatars"), dir.resolve("images"), base, "$base/models.json")
            val backend = slice.asAppBackend()
            CoreBridgeRegistry.bridge = backend.coreBridge
            val images = ChatImageStore::class.java.cast(Proxy.newProxyInstance(ChatImageStore::class.java.classLoader,
                arrayOf(ChatImageStore::class.java)) { _, _, _ -> null })
            val container = AppContainer(AppDirs(dir.toString().toPath(), dir.resolve("cache").toString().toPath()), images, backend)
            app = container
            container.providerRepository.insert(Provider("p", "Provider", "$base/v1", "secret-key", createdAt = 1, updatedAt = 1))
            container.modelRepository.insert(ChatModel("m", "p", "model", "Model", true, supportsToolCalling = true, createdAt = 1))
            container.conversationRepository.insert(Conversation("c", "Chat", "p", "a", createdAt = 1, updatedAt = 1, lastMessage = null))
            val events = Channel<List<String>>(Channel.UNLIMITED)
            val pushes = Channel<TaskPushEvent>(Channel.UNLIMITED)
            TaskPushNotifications.onEvent = { pushes.trySend(it) }
            container.coreBridge.subscribe { kind, _ -> if (kind == "taskStatus") error("subscriber failure") }
            container.coreBridge.subscribe { kind, ids -> if (kind == "taskStatus") events.trySend(ids) }
            fun config(text: String, disabled: Boolean) = TurnConfigBridge("c", "model", "", "", "chat", "", null,
                null, null, null, emptyList(), false, 0, "", null, null, null, false, "", operation = "send", content = text,
                disableTools = disabled, supportsToolApproval = true)
            suspend fun status(expected: String) = assertEquals(listOf(expected, "chat", "c"), withTimeout(10_000) { events.receive() })
            suspend fun awaitIdle() = withTimeout(10_000) { while (container.coreBridge.hasActiveTasks()) yield() }

            container.coreBridge.startBackgroundTurn(config("private prompt", false))
            status("approval")
            assertEquals(TaskPushEvent("approval", "c", "chat"), withTimeout(10_000) { pushes.receive() })
            container.coreBridge.respondToolApproval("call-1", true)
            status("approvalResolved")
            status("completed")
            assertEquals(TaskPushEvent("completed", "c", "chat"), withTimeout(10_000) { pushes.receive() })
            awaitIdle()
            repeat(2) { withTimeout(10_000) { container.coreBridge.observeBackgroundTurn("c") { } } }
            assertNull(withTimeoutOrNull(200) { events.receive() })

            container.coreBridge.startBackgroundTurn(config("failure", true))
            status("failed")
            assertEquals(TaskPushEvent("failed", "c", "chat"), withTimeout(10_000) { pushes.receive() })
            awaitIdle()
            val failed = container.messageRepository.getByConversationId("c").first().last()
            assertEquals(MessageStatus.ERROR, failed.status)
            assertTrue(failed.errorMessage.orEmpty().contains("503"))
            val notices = assertNotNull(container.turnFailureNotices)
            val firstShown = CompletableDeferred<TurnFailureNotice>()
            val firstConsumer = launch { notices.present { firstShown.complete(it); awaitCancellation() } }
            try {
                val notice = withTimeout(10_000) { firstShown.await() }
                assertTrue(notice.detail.contains("503"))
                assertEquals("c", notice.conversationId)
                assertEquals("Chat", notice.conversationTitle)
                assertEquals(1, db.rows().observeTurnErrorKeys().first().size)
            } finally { firstConsumer.cancelAndJoin() }
            assertEquals(1, db.rows().observeTurnErrorKeys().first().size)
            val shownAgain = CompletableDeferred<TurnFailureNotice>()
            val secondConsumer = launch { notices.present { shownAgain.complete(it) } }
            try {
                assertEquals(firstShown.await(), withTimeout(10_000) { shownAgain.await() })
                withTimeout(10_000) { db.rows().observeTurnErrorKeys().first { it.isEmpty() } }
            } finally { secondConsumer.cancelAndJoin() }
            container.coreBridge.startBackgroundTurn(config("cancel", true))
            withTimeout(10_000) { waiting.await() }
            container.coreBridge.cancelBackgroundTurn("c")
            status("cancelled")
            awaitIdle()
            assertEquals(4, requests.get())
            assertNull(withTimeoutOrNull(200) { pushes.receive() })
            container.shutdown()
            lifecycle.stopAndAwait()
            assertNull(withTimeoutOrNull(200) { events.receive() })
        } finally {
            release.countDown()
            app?.shutdown()
            if (lifecycle.state.value == RoomShutdownState.Running) lifecycle.stopAndAwait()
            CoreBridgeRegistry.bridge = previous
            TaskPushNotifications.onEvent = previousPushHandler
            db.close()
            server.stop(0)
            executor.shutdownNow()
            dir.toFile().deleteRecursively()
        }
    }
}
