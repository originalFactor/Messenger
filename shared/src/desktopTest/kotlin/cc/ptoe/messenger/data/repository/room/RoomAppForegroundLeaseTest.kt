/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.room.Transactor
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridgeRegistry
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomShutdownState
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.di.AppContainer
import cc.ptoe.messenger.di.AppDirs
import cc.ptoe.messenger.domain.chat.KotlinTurnProvider
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.presentation.platform.ForegroundTaskRegistry
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomAppForegroundLeaseTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test fun admissionAndConcurrentWorkersKeepLeasesThroughCleanup(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-app-foreground")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val lifecycle = RoomManagedShutdown { }
        val previousBridge = CoreBridgeRegistry.bridge
        val previousBegin = ForegroundTaskRegistry.begin
        val previousEnd = ForegroundTaskRegistry.end
        val active = AtomicInteger()
        val changes = ConcurrentLinkedQueue<Int>()
        val writerHeld = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val providerCalls = AtomicInteger()
        var app: AppContainer? = null
        try {
            if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) return@runBlocking
            ForegroundTaskRegistry.begin = { changes.add(active.incrementAndGet()) }
            ForegroundTaskRegistry.end = { changes.add(active.decrementAndGet()) }
            val assets = Files.createDirectory(dir.resolve("assets"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            lifecycle.enter()
            val prefs = MemoryPreferences()
            val provider = KotlinTurnProvider { _, _ -> flow {
                when (providerCalls.incrementAndGet()) {
                    1 -> try { firstStarted.complete(Unit); emit(ChatStreamEvent.Content("first")); awaitCancellation() }
                        finally { withContext(NonCancellable) { cleanupStarted.complete(Unit); releaseCleanup.await() } }
                    2 -> { secondStarted.complete(Unit); awaitCancellation() }
                    else -> emit(ChatStreamEvent.Error("HTTP 503: unavailable"))
                }
            } }
            val slice = KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs),
                assets, dir.resolve("avatars"), dir.resolve("images"), turnProvider = provider)
            val backend = slice.asAppBackend()
            CoreBridgeRegistry.bridge = backend.coreBridge
            val images = ChatImageStore::class.java.cast(Proxy.newProxyInstance(ChatImageStore::class.java.classLoader,
                arrayOf(ChatImageStore::class.java)) { _, _, _ -> null })
            val container = AppContainer(AppDirs(dir.toString().toPath(), dir.resolve("cache").toString().toPath()), images, backend)
            app = container
            container.providerRepository.insert(Provider("p", "Provider", "https://example.test/v1", "key", createdAt = 1, updatedAt = 1))
            container.modelRepository.insert(ChatModel("m", "p", "model", "Model", true, createdAt = 1))
            for (id in listOf("one", "two")) container.conversationRepository.insert(
                Conversation(id, "Chat", "p", "a", createdAt = 1, updatedAt = 1, lastMessage = null))
            fun config(id: String) = TurnConfigBridge(id, "model", "", "", "chat", "", null, null, null, null,
                emptyList(), false, 0, "", null, null, null, false, "", operation = "send", content = "hello", disableTools = true)

            assertFailsWith<IllegalStateException> { container.coreBridge.startBackgroundTurn(config("one")) }
            assertEquals(0, active.get())
            assertEquals(listOf(1, 0), changes.toList())
            assertEquals(0, providerCalls.get())
            changes.clear()
            container.agentRepository.update(container.agentRepository.getDefaultAgent().first()!!.copy(defaultModelId = "m"))

            val locked = async(Dispatchers.IO, start = CoroutineStart.DEFAULT) {
                db.useWriterConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    writerHeld.complete(Unit)
                    releaseWriter.await()
                } }
            }
            withTimeout(5_000) { writerHeld.await() }
            val admission = async(Dispatchers.IO) { container.coreBridge.startBackgroundTurn(config("one")) }
            withTimeout(5_000) { while (active.get() != 1) yield() }
            assertFalse(admission.isCompleted)
            releaseWriter.complete(Unit)
            withTimeout(10_000) { locked.await(); admission.await(); firstStarted.await() }
            assertEquals(1, active.get())
            assertEquals(listOf(1, 2, 1), changes.toList())

            container.coreBridge.startBackgroundTurn(config("two"))
            withTimeout(10_000) { secondStarted.await() }
            assertEquals(2, active.get())
            container.coreBridge.cancelBackgroundTurn("one")
            withTimeout(10_000) { cleanupStarted.await() }
            assertEquals(2, active.get(), "the first lease must cover blocked provider cleanup")
            container.coreBridge.cancelBackgroundTurn("two")
            withTimeout(10_000) { while (active.get() != 1) yield() }
            assertTrue(container.coreBridge.hasActiveTasks())
            releaseCleanup.complete(Unit)
            withTimeout(10_000) { while (active.get() != 0 || container.coreBridge.hasActiveTasks()) yield() }
            assertTrue(changes.toList().dropLast(1).all { it > 0 }, "the lease must never drop between admission and cleanup")
            changes.clear()
            container.coreBridge.startBackgroundTurn(config("one"))
            withTimeout(10_000) { while (providerCalls.get() < 3 || container.coreBridge.hasActiveTasks()) yield() }
            assertEquals(0, active.get())
            assertEquals(MessageStatus.ERROR, container.messageRepository.getByConversationId("one").first().last().status)
            assertTrue(changes.toList().dropLast(1).all { it > 0 })
            changes.clear()
            withTimeout(10_000) { container.coreBridge.runTurn(config("one"), { _, _ -> error("tools disabled") }) { } }
            withTimeout(10_000) { while (container.coreBridge.hasActiveTasks()) yield() }
            assertEquals(4, providerCalls.get())
            assertEquals(0, active.get())
            assertTrue(changes.toList().dropLast(1).all { it > 0 })
            container.shutdown()
        } finally {
            releaseWriter.complete(Unit)
            releaseCleanup.complete(Unit)
            app?.shutdown()
            if (lifecycle.state.value == RoomShutdownState.Running) lifecycle.stopAndAwait()
            ForegroundTaskRegistry.begin = previousBegin
            ForegroundTaskRegistry.end = previousEnd
            CoreBridgeRegistry.bridge = previousBridge
            db.close()
            dir.toFile().deleteRecursively()
        }
    }
}
