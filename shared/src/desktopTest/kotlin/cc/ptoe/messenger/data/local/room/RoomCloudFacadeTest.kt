/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.room

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.cloud.CloudImageRehydrator
import cc.ptoe.messenger.data.cloud.RoomAvatarCache
import cc.ptoe.messenger.data.cloud.RoomCloudFacade
import cc.ptoe.messenger.data.cloud.RoomCloudHttpClient
import cc.ptoe.messenger.data.cloud.SyncCollection
import cc.ptoe.messenger.data.cloud.SyncMutation
import cc.ptoe.messenger.data.cloud.isCloudAvatarUrl
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.repository.room.row
import cc.ptoe.messenger.domain.model.Agent
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomCloudFacadeTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }
    @Test fun registersCleanupBeforeInitializationCanFail(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-cloud-register")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        var registered: RoomCloudFacade? = null
        try {
            assertFailsWith<IllegalStateException> {
                RoomCloudFacade.open(db, AppPreferences(MemoryPreferences()), RoomCloudHttpClient(),
                    RoomAvatarCache(dir.resolve("avatars").toString().toPath()), CloudImageRehydrator(dir.resolve("images")),
                    onCreated = { registered = it; error("registration failed") })
            }
            assertFailsWith<IllegalStateException> { checkNotNull(registered).pendingLoginChoice() }
            // Retrying cleanup through the registered handle must be safe.
            checkNotNull(registered).shutdown()
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
    @Test fun shutdownCancelsAnInFlightAvatarAndRejectsNewCalls(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.executor = executor
        server.createContext("/api/avatars/user") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(byteArrayOf(1))
            exchange.responseBody.flush()
            release.await(10, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        val dir = Files.createTempDirectory("room-cloud-avatar-shutdown")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val base = "http://127.0.0.1:${server.address.port}"
        val facade = RoomCloudFacade.open(db, AppPreferences(MemoryPreferences()), RoomCloudHttpClient(),
            RoomAvatarCache(dir.resolve("avatars").toString().toPath()), CloudImageRehydrator(dir.resolve("images")), base)
        try {
            val request = async { runCatching {
                facade.avatarHttpClient.prepareGet("$base/api/avatars/user").execute { response ->
                    entered.complete(Unit)
                    response.bodyAsBytes()
                }
            } }
            withTimeout(5_000) { entered.await() }
            withTimeout(5_000) { facade.shutdown() }
            assertTrue(withTimeout(5_000) { request.await() }.isFailure)
            assertFailsWith<Exception> { facade.avatarHttpClient.get("$base/api/avatars/user") }
        } finally {
            release.countDown()
            facade.close()
            db.close()
            server.stop(0)
            executor.shutdownNow()
            dir.toFile().deleteRecursively()
        }
    }
    @Test fun shutdownWaitsForAnAdmittedRequestBeforeRoomCanClose(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.executor = executor
        fun com.sun.net.httpserver.HttpExchange.reply(body: String) {
            val bytes = body.toByteArray()
            sendResponseHeaders(200, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/auth/login") { exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("Set-Cookie", "messenger_session=account; Path=/; HttpOnly")
            exchange.reply("""{"user":{"id":"account","email":"a@example.test","syncVersion":3}}""")
        }
        server.createContext("/api/market/agents") { exchange ->
            entered.complete(Unit)
            release.await(10, TimeUnit.SECONDS)
            exchange.reply("""{"agents":[],"nextCursor":null}""")
        }
        server.start()
        val dir = Files.createTempDirectory("room-cloud-shutdown")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val facade = RoomCloudFacade.open(db, AppPreferences(MemoryPreferences()), RoomCloudHttpClient(),
            RoomAvatarCache(dir.resolve("avatars").toString().toPath()), CloudImageRehydrator(dir.resolve("images")),
            "http://127.0.0.1:${server.address.port}")
        try {
            facade.login("a@example.test", "password")
            val request = async { facade.listMarketAgents("test") }
            withTimeout(5_000) { entered.await() }
            val shutdown = async { facade.shutdown() }
            delay(100)
            assertFalse(shutdown.isCompleted)
            release.countDown()
            withTimeout(5_000) { request.await(); shutdown.await() }
            assertFailsWith<IllegalStateException> { facade.listMarketAgents("after") }
            assertNotNull(db.cloudSync().currentSession())
        } finally {
            release.countDown()
            facade.close()
            db.close()
            server.stop(0)
            executor.shutdownNow()
            dir.toFile().deleteRecursively()
        }
    }
    @Test fun shutdownWaitsForRemoteCallbackToFinish(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val entered = CompletableDeferred<Unit>()
        val callbackRelease = CountDownLatch(1)
        val serverRelease = CountDownLatch(1)
        val events = AtomicReference<java.io.OutputStream>()
        val requestId = UUID.randomUUID().toString()
        val connectionId = UUID.randomUUID().toString()
        server.executor = executor
        fun com.sun.net.httpserver.HttpExchange.reply(body: String) {
            val bytes = body.toByteArray()
            sendResponseHeaders(200, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/auth/login") { exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("Set-Cookie", "messenger_session=account; Path=/; HttpOnly")
            exchange.reply("""{"user":{"id":"account","email":"a@example.test","syncVersion":3}}""")
        }
        server.createContext("/api/remote/events") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            events.set(exchange.responseBody)
            exchange.responseBody.write("event: controlReady\ndata: {\"connectionId\":\"$connectionId\"}\n\n".toByteArray())
            exchange.responseBody.flush()
            serverRelease.await(10, TimeUnit.SECONDS)
            exchange.close()
        }
        server.createContext("/api/remote/control") { exchange ->
            exchange.requestBody.readBytes()
            exchange.reply("{}")
            events.get().write("event: controlReply\ndata: {\"id\":\"$requestId\",\"payload\":{\"type\":\"Permission\"},\"done\":false}\n\n".toByteArray())
            events.get().flush()
        }
        server.start()
        val dir = Files.createTempDirectory("room-cloud-remote-shutdown")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val facade = RoomCloudFacade.open(db, AppPreferences(MemoryPreferences()), RoomCloudHttpClient(),
            RoomAvatarCache(dir.resolve("avatars").toString().toPath()), CloudImageRehydrator(dir.resolve("images")),
            "http://127.0.0.1:${server.address.port}")
        try {
            facade.login("a@example.test", "password")
            val request = async(Dispatchers.IO) {
                runCatching {
                    facade.remoteControlCommand(requestId, "desktop", kotlinx.serialization.json.buildJsonObject {
                        put("action", kotlinx.serialization.json.JsonPrimitive("prompt"))
                    }) {
                        entered.complete(Unit)
                        callbackRelease.await(10, TimeUnit.SECONDS)
                    }
                }
            }
            withTimeout(5_000) { entered.await() }
            val shutdown = async { facade.shutdown() }
            delay(100)
            assertFalse(shutdown.isCompleted)
            callbackRelease.countDown()
            withTimeout(5_000) { request.await(); shutdown.await() }
            assertNotNull(db.cloudSync().currentSession())
        } finally {
            callbackRelease.countDown()
            serverRelease.countDown()
            facade.close()
            db.close()
            server.stop(0)
            executor.shutdownNow()
            dir.toFile().deleteRecursively()
        }
    }
    @Test fun sessionMarketQueueAvatarAndExpiryReachExistingFacadeContract(): Unit = runBlocking {
        val received = ConcurrentLinkedQueue<String>()
        val avatarExpired = AtomicBoolean(false)
        val failPush = AtomicBoolean(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        fun com.sun.net.httpserver.HttpExchange.reply(status: Int, body: String) {
            val bytes = body.toByteArray()
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/auth/login") { exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("Set-Cookie", "messenger_session=account; Path=/; HttpOnly")
            exchange.reply(200, """{"user":{"id":"account","email":"a@example.test","syncVersion":3}}""")
        }
        server.createContext("/api/auth/logout") { it.reply(200, "{}") }
        server.createContext("/api/market/agents") { exchange ->
            received.add("market:${exchange.requestHeaders.getFirst("Cookie")}")
            exchange.reply(200, """{"agents":[],"nextCursor":null}""")
        }
        server.createContext("/api/avatars/user") { exchange ->
            received.add("avatar:${exchange.requestHeaders.getFirst("Cookie")}")
            exchange.reply(if (avatarExpired.get()) 401 else 200, "avatar")
        }
        server.createContext("/public") { exchange ->
            received.add("public:${exchange.requestHeaders.getFirst("Cookie")}")
            exchange.reply(200, "public")
        }
        server.createContext("/api/agents/") { exchange ->
            received.add("mutation:${exchange.requestMethod}:${exchange.requestURI.path}")
            exchange.requestBody.readBytes()
            exchange.reply(if (failPush.get()) 503 else 200, """{"version":4}""")
        }
        server.createContext("/api/push/events") { exchange ->
            val raw = exchange.requestBody.bufferedReader().readText()
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
            val target = obj?.get("target") as? kotlinx.serialization.json.JsonObject
            val valid = obj != null && obj.keys.all { it in setOf("event", "title", "target") } &&
                (target == null || target.keys == setOf("kind", "conversationId") || target.keys == setOf("kind", "conversationId", "deviceId"))
            received.add("push:$raw")
            exchange.reply(if (valid) 200 else 400, "{}")
        }
        server.createContext("/api/sync") { exchange ->
            val collection = exchange.requestURI.query.orEmpty().split('&').first { it.startsWith("collection=") }.substringAfter('=')
            received.add("sync:$collection")
            val documents = if (collection == "agents") """[{"_id":"default","name":"Default","isDefault":true}]""" else "[]"
            exchange.reply(200, """{"collection":"$collection","documents":$documents,"hasMore":false,"latestVersion":4}""")
        }
        server.start()
        val dir = Files.createTempDirectory("room-cloud-facade")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val transport = RoomCloudHttpClient()
        val base = "http://127.0.0.1:${server.address.port}"
        val prefs = AppPreferences(MemoryPreferences())
        var facade: RoomCloudFacade? = null
        try {
            val seed = Agent("default", Agent.DEFAULT_AGENT_NAME, systemPrompt = Agent.DEFAULT_AGENT_PROMPT,
                description = Agent.DEFAULT_AGENT_DESCRIPTION, defaultModelId = null, temperature = 0.7f, topP = 1f,
                maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1)
            db.rows().upsertAgents(listOf(seed.row()))
            var instance = RoomCloudFacade.open(db, prefs, transport, RoomAvatarCache(dir.resolve("avatars").toString().toPath()), CloudImageRehydrator(dir.resolve("images")), base)
            facade = instance
            val login = instance.login("a@example.test", "password")
            assertEquals("account", login.user.id)
            assertFalse(login.hasLocalData)
            assertEquals("account", instance.user.first()?.id)
            assertTrue(db.cloudSync().loginChoicePending())
            // Restart between sign-in and user choice must remain blocked.
            instance.close()
            instance = RoomCloudFacade.open(db, prefs, RoomCloudHttpClient(), RoomAvatarCache(dir.resolve("avatars").toString().toPath()), CloudImageRehydrator(dir.resolve("images")), base)
            facade = instance
            assertEquals("account", instance.user.first()?.id)
            val recoveredChoice = assertNotNull(instance.pendingLoginChoice())
            assertEquals("account", recoveredChoice.user.id)
            assertFalse(recoveredChoice.hasLocalData)
            instance.requestLocalSync()
            delay(1000)
            assertTrue(received.none { it.startsWith("sync:") || it.startsWith("mutation:") })
            assertFailsWith<IllegalStateException> { instance.sync() }
            instance.completeLogin(useLocalData = false)
            assertFalse(db.cloudSync().loginChoicePending())
            assertNull(instance.pendingLoginChoice())
            instance.ensureBuiltinTitleAgent()
            assertEquals(1, db.cloudSync().titleCount())
            assertEquals(1, db.rows().defaultAgents().size)
            instance.sendPushEvent("completed", "Task", "remote", "acp:1", "desktop-1")
            val pushed = kotlinx.serialization.json.Json.parseToJsonElement(received.first { it.startsWith("push:") }.removePrefix("push:"))
            val expected = kotlinx.serialization.json.Json.parseToJsonElement("""{"event":"completed","title":"Task","target":{"kind":"remote","conversationId":"acp:1","deviceId":"desktop-1"}}""")
            assertEquals(expected, pushed)
            instance.listMarketAgents("test")
            assertTrue(received.contains("market:messenger_session=account"))
            instance.avatarHttpClient.get("$base/api/avatars/user")
            instance.avatarHttpClient.get("$base/public")
            assertTrue(received.contains("avatar:messenger_session=account"))
            assertTrue(received.contains("public:null"))
            val pullCount = received.count { it.startsWith("sync:") }
            db.cloudSync().enqueueLocal(SyncCollection.AGENT, "removed", SyncMutation.DELETE)
            instance.requestLocalChange("agent", "removed", true)
            withTimeout(10_000) { while (received.none { it == "mutation:DELETE:/api/agents/removed" }) delay(25) }
            assertEquals(pullCount, received.count { it.startsWith("sync:") })
            failPush.set(true)
            db.cloudSync().enqueueLocal(SyncCollection.AGENT, "uncertain", SyncMutation.DELETE)
            instance.requestLocalChange("agent", "uncertain", true)
            withTimeout(10_000) { while (instance.syncError.value == null) delay(25) }
            assertTrue(db.cloudSync().automaticPushUncertain())
            assertEquals(1, received.count { it == "mutation:DELETE:/api/agents/uncertain" })
            // A restart must not automatically repeat the request with an unknown outcome.
            instance.close()
            instance = RoomCloudFacade.open(db, prefs, RoomCloudHttpClient(), RoomAvatarCache(dir.resolve("avatars").toString().toPath()), CloudImageRehydrator(dir.resolve("images")), base)
            facade = instance
            failPush.set(false)
            instance.requestLocalSync()
            delay(1000)
            assertEquals(1, received.count { it == "mutation:DELETE:/api/agents/uncertain" })
            instance.sync() // explicit user retry
            assertEquals(2, received.count { it == "mutation:DELETE:/api/agents/uncertain" })
            assertFalse(db.cloudSync().automaticPushUncertain())
            instance.cancelPendingLocalSync()
            avatarExpired.set(true)
            instance.avatarHttpClient.get("$base/api/avatars/user")
            assertTrue(instance.sessionExpired.value)
            assertNull(instance.user.first())
            assertNull(db.cloudSync().currentSession())
            instance.acknowledgeSessionExpired()
            assertFalse(instance.sessionExpired.value)
            instance.setServerUrl("$base/other")
            assertEquals("$base/other", instance.serverUrl.first())
            assertEquals("$base/other", db.rows().getValue("cloud_server_url")?.value)
        } finally {
            facade?.close()
            db.close()
            server.stop(0)
            executor.shutdownNow()
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun avatarCookiesAreBoundToExactServerAndKnownRoutes() {
        assertTrue(isCloudAvatarUrl("https://example.com/tenant", "https://example.com/tenant/api/avatars/user?version=1"))
        assertTrue(isCloudAvatarUrl("https://example.com/tenant", "https://example.com/tenant/api/market/agents/a-1/avatar"))
        for (url in listOf("https://evil.example.com/tenant/api/avatars/user", "https://example.com/api/avatars/user",
            "http://example.com/tenant/api/avatars/user", "https://example.com/tenant/api/avatars/agents/%2e%2e",
            "https://example.com/tenant/api/avatars/agents/a/extra", "https://user@example.com/tenant/api/avatars/user")) {
            assertFalse(isCloudAvatarUrl("https://example.com/tenant", url), url)
        }
    }
    @Test fun projectAndCustomizedDefaultAlwaysCountAsLocalData(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/auth/login") { exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("Set-Cookie", "messenger_session=account; Path=/; HttpOnly")
            val body = """{"user":{"id":"account","email":"a@example.test","syncVersion":3}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/api/auth/logout") { it.sendResponseHeaders(200, -1); it.close() }
        server.start()
        val dir = Files.createTempDirectory("cloud-local-baseline")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val base = "http://127.0.0.1:${server.address.port}"
        val facade = RoomCloudFacade.open(db, AppPreferences(MemoryPreferences()), RoomCloudHttpClient(), RoomAvatarCache(dir.resolve("avatars").toString().toPath()), CloudImageRehydrator(dir.resolve("images")), base)
        try {
            val seed = Agent("default", Agent.DEFAULT_AGENT_NAME, systemPrompt = Agent.DEFAULT_AGENT_PROMPT,
                description = Agent.DEFAULT_AGENT_DESCRIPTION, defaultModelId = null, temperature = 0.7f, topP = 1f,
                maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1)
            db.rows().upsertAgents(listOf(seed.row()))
            db.rows().upsertProjects(listOf(ProjectRow("p", "project", "workspace", null, 1, 1)))
            assertTrue(facade.login("a@example.test", "password").hasLocalData)
            facade.logout()
            db.rows().deleteProject("p")
            db.rows().upsertAgents(listOf(seed.copy(name = "My assistant").row()))
            assertTrue(facade.login("a@example.test", "password").hasLocalData)
            facade.logout()
            db.rows().upsertAgents(listOf(seed.copy(description = "", systemPrompt = "Custom instructions").row()))
            assertTrue(facade.login("a@example.test", "password").hasLocalData)
        } finally { facade.close(); db.close(); server.stop(0); dir.toFile().deleteRecursively() }
    }

}
