/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridgeRegistry
import cc.ptoe.messenger.data.cloud.CloudImageRehydrator
import cc.ptoe.messenger.data.cloud.RoomAvatarCache
import cc.ptoe.messenger.data.cloud.RoomCloudFacade
import cc.ptoe.messenger.data.cloud.RoomCloudHttpClient
import cc.ptoe.messenger.data.cloud.SyncCollection
import cc.ptoe.messenger.data.cloud.SyncMutation
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomShutdownState
import cc.ptoe.messenger.di.AppContainer
import cc.ptoe.messenger.di.AppDirs
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.presentation.navigation.prepareLocalConversation
import com.sun.net.httpserver.HttpServer
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomAppCloudSyncTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test fun startupAndNotificationPullNeverRetryOrClearUncertainPush(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val pulls = AtomicInteger()
        val pushes = AtomicInteger()
        val failPush = AtomicBoolean(false)
        val rejectSync = AtomicBoolean(false)
        val user = """{"user":{"id":"account","email":"a@example.test","syncVersion":3}}"""
        server.executor = executor
        fun com.sun.net.httpserver.HttpExchange.reply(status: Int, body: String) {
            val bytes = body.toByteArray()
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/auth/login") { exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("Set-Cookie", "messenger_session=account; Path=/; HttpOnly")
            exchange.reply(200, user)
        }
        server.createContext("/api/auth/me") { it.reply(200, user) }
        server.createContext("/api/sync") { exchange ->
            pulls.incrementAndGet()
            val collection = exchange.requestURI.query.orEmpty().split('&').first { it.startsWith("collection=") }.substringAfter('=')
            val documents = if (collection == "agents") """[{"_id":"default","name":"Default","isDefault":true}]""" else "[]"
            exchange.reply(if (rejectSync.get()) 401 else 200,
                """{"collection":"$collection","documents":$documents,"hasMore":false,"latestVersion":4}""")
        }
        server.createContext("/api/agents/") { exchange ->
            pushes.incrementAndGet()
            exchange.requestBody.readBytes()
            exchange.reply(if (failPush.get()) 503 else 200, """{"version":4}""")
        }
        server.start()
        val dir = Files.createTempDirectory("room-app-cloud-sync")
        val dbPath = dir.resolve("store.db")
        fun openDb() = Room.databaseBuilder<LocalDatabase>(name = dbPath.toString()).setDriver(BundledSQLiteDriver()).build()
        val prefs = MemoryPreferences()
        val base = "http://127.0.0.1:${server.address.port}"
        val previous = CoreBridgeRegistry.bridge
        var db: LocalDatabase? = null
        var app: AppContainer? = null
        var lifecycle: RoomManagedShutdown? = null
        try {
            if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) return@runBlocking
            val initial = openDb()
            db = initial
            RoomAgentRepository(initial).ensureDefaultAgent(Agent("default", "Default", systemPrompt = "", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            val initialTransport = RoomCloudHttpClient()
            val cloud = RoomCloudFacade.open(initial, AppPreferences(prefs), initialTransport,
                RoomAvatarCache(dir.resolve("avatars").toString().toPath()), CloudImageRehydrator(dir.resolve("images")), base)
            try {
                cloud.login("a@example.test", "password")
                cloud.completeLogin(useLocalData = false)
                initial.cloudSync().enqueueLocal(SyncCollection.AGENT, "uncertain", SyncMutation.DELETE)
                initial.cloudSync().beginAutomaticPush(checkNotNull(initial.cloudSync().currentSession()))
            } finally { cloud.shutdown(); initialTransport.close() }
            initial.close()

            val reopened = openDb()
            db = reopened
            val owner = RoomManagedShutdown { }
            lifecycle = owner
            owner.enter()
            val assets = Files.createDirectory(dir.resolve("assets"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val slice = KotlinRoomBackendSlice.attach(reopened, owner, AppPreferences(prefs), ThemePreferences(prefs),
                assets, dir.resolve("avatars"), dir.resolve("images"), base, "$base/models.json")
            val backend = slice.asAppBackend()
            CoreBridgeRegistry.bridge = backend.coreBridge
            val images = ChatImageStore::class.java.cast(Proxy.newProxyInstance(ChatImageStore::class.java.classLoader,
                arrayOf(ChatImageStore::class.java)) { _, _, _ -> null })
            val container = AppContainer(AppDirs(dir.toString().toPath(), dir.resolve("cache").toString().toPath()), images, backend)
            app = container
            val beforeStartup = pulls.get()
            container.initializeLocalAndCloudData()
            withTimeout(10_000) { while (pulls.get() < beforeStartup + 4) delay(20) }
            assertEquals(0, pushes.get())
            assertTrue(reopened.cloudSync().automaticPushUncertain())
            assertTrue(reopened.cloudSync().pendingSnapshots(checkNotNull(reopened.cloudSync().currentSession()))
                .any { it.id == "uncertain" && it.mutation == SyncMutation.DELETE })
            assertTrue(prepareLocalConversation(true, sync = { container.cloud.sync() }, exists = { true }, onSyncFailure = { throw it }))
            container.cloud.requestLocalSync()
            delay(1_000)
            assertEquals(0, pushes.get())
            assertTrue(reopened.cloudSync().automaticPushUncertain())

            failPush.set(true)
            assertFailsWith<Exception> { container.cloud.upload() }
            val afterFailedUpload = pushes.get()
            assertTrue(afterFailedUpload > 0)
            assertTrue(reopened.cloudSync().automaticPushUncertain())
            failPush.set(false)
            container.cloud.requestLocalSync()
            delay(1_000)
            assertEquals(afterFailedUpload, pushes.get())
            container.cloud.upload()
            assertTrue(pushes.get() > afterFailedUpload)
            assertFalse(reopened.cloudSync().automaticPushUncertain())

            // A new explicit upload must record uncertainty before its first network write.
            reopened.cloudSync().enqueueLocal(SyncCollection.AGENT, "next", SyncMutation.DELETE)
            failPush.set(true)
            assertFailsWith<Exception> { container.cloud.upload() }
            assertTrue(reopened.cloudSync().automaticPushUncertain())
            val afterSecondFailure = pushes.get()
            container.cloud.requestLocalSync()
            delay(1_000)
            assertEquals(afterSecondFailure, pushes.get())
            rejectSync.set(true)
            assertFailsWith<Exception> { container.cloud.sync() }
            assertNull(container.cloud.user.first())
            assertTrue(container.cloud.sessionExpired.value)
            assertTrue(container.cloud.syncError.value != null)
            assertTrue(reopened.cloudSync().automaticPushUncertain())
        } finally {
            app?.shutdown()
            lifecycle?.let { if (it.state.value == RoomShutdownState.Running) it.stopAndAwait() }
            CoreBridgeRegistry.bridge = previous
            db?.close()
            server.stop(0)
            executor.shutdownNow()
            dir.toFile().deleteRecursively()
        }
    }
}
