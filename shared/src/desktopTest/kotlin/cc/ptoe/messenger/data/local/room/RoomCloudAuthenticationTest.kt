/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.cloud.RoomCloudAuthentication
import cc.ptoe.messenger.data.cloud.RoomCloudApi
import cc.ptoe.messenger.data.cloud.RoomCloudCoordinator
import cc.ptoe.messenger.data.cloud.RoomAvatarCache
import okio.Path.Companion.toPath
import cc.ptoe.messenger.data.cloud.RoomCloudMarket
import cc.ptoe.messenger.data.cloud.RoomBuiltinModels
import cc.ptoe.messenger.data.cloud.BUILTIN_PROVIDER_ID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import cc.ptoe.messenger.data.cloud.CloudSessionExpired
import cc.ptoe.messenger.data.cloud.RoomCloudHttpClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomCloudAuthenticationTest {
    @Test fun loginRestoreFailureAndExpiryRespectSessionIdentity(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.executor = executor
        val redirectTarget = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val redirectRequests = java.util.concurrent.atomic.AtomicInteger()
        redirectTarget.createContext("/steal") { exchange ->
            redirectRequests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close()
        }
        redirectTarget.createContext("/api/avatars/user") { exchange ->
            val bytes = byteArrayOf(9, 8, 7)
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        redirectTarget.start()
        val redirectAvatar = java.util.concurrent.atomic.AtomicBoolean(false)
        val redirectModels = java.util.concurrent.atomic.AtomicBoolean(false)
        val slowAvatar = java.util.concurrent.atomic.AtomicBoolean(false)
        val avatarStarted = java.util.concurrent.CountDownLatch(1)
        val avatarRelease = java.util.concurrent.CountDownLatch(1)
        val received = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val cloudVersion = java.util.concurrent.atomic.AtomicLong()
        server.createContext("/api/market/agents") { exchange ->
            received.add("${exchange.requestMethod} ${exchange.requestURI} ${exchange.requestHeaders.getFirst("Cookie")}")
            val body = when {
                exchange.requestMethod == "GET" && exchange.requestURI.path.endsWith("/market-1") -> """{"agent":{"id":"market-1","name":"Market","systemPrompt":"prompt","version":3}}"""
                exchange.requestMethod == "GET" -> """{"agents":[],"nextCursor":null}"""
                exchange.requestMethod == "DELETE" -> """{"success":true}"""
                else -> """{"agent":{"id":"market-1","name":"Market","systemPrompt":"prompt","version":3}}"""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/push/device") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            received.add("${exchange.requestMethod} ${exchange.requestURI} ${exchange.requestHeaders.getFirst("Cookie")} $body")
            val bytes = "{}".toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/avatars/agents") { exchange ->
            val content = exchange.requestBody.readBytes().decodeToString()
            received.add("${exchange.requestMethod} ${exchange.requestURI} ${exchange.requestHeaders.getFirst("Cookie")} $content")
            val bytes = """{"url":"/avatar"}""".toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/models") { exchange ->
            if (redirectModels.get()) {
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${redirectTarget.address.port}/steal")
                exchange.sendResponseHeaders(302, -1); exchange.close(); return@createContext
            }
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            received.add("${exchange.requestMethod} ${exchange.requestURI} $authorization cookie=${exchange.requestHeaders.getFirst("Cookie")}")
            val bad = authorization == "Bearer bad-key"
            val bytes = """{"data":[{"id":"m1","display_name":"Model","reasoning":true,"input_modalities":["text","image"],"cache_write_rate":1.5}]}""".toByteArray()
            exchange.sendResponseHeaders(if (bad) 401 else 200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/agents/") { exchange ->
            val request = exchange.requestBody.bufferedReader().readText()
            received.add("${exchange.requestMethod} ${exchange.requestURI} ${exchange.requestHeaders.getFirst("Cookie")} $request")
            val version = cloudVersion.incrementAndGet()
            val bytes = """{"version":$version}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/sync") { exchange ->
            val query = exchange.requestURI.query.orEmpty()
            val collection = query.split('&').first { it.startsWith("collection=") }.substringAfter('=')
            received.add("${exchange.requestMethod} ${exchange.requestURI} ${exchange.requestHeaders.getFirst("Cookie")}")
            val bytes = """{"collection":"$collection","documents":[],"hasMore":false,"nextCursor":null,"latestVersion":${cloudVersion.get()}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/auth/login") { exchange ->
            val input = exchange.requestBody.bufferedReader().readText()
            val bad = input.contains("bad-password")
            val account = if (input.contains("second")) "second" else "first"
            val avatarField = if (input.contains("slow")) ",\"avatarUrl\":\"/api/avatars/user\"" else ""
            val body = if (bad) """{"message":"bad credentials"}""" else """{"user":{"id":"$account","email":"$account@example.test"$avatarField}}"""
            if (!bad) exchange.responseHeaders.add("Set-Cookie", "messenger_session=$account; Path=/; HttpOnly")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (bad) 401 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/avatars/user") { exchange ->
            if (redirectAvatar.get()) {
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${redirectTarget.address.port}/steal")
                exchange.sendResponseHeaders(302, -1); exchange.close(); return@createContext
            }
            received.add("${exchange.requestMethod} ${exchange.requestURI} ${exchange.requestHeaders.getFirst("Cookie")} auth=${exchange.requestHeaders.getFirst("Authorization")}")
            if (slowAvatar.get()) { avatarStarted.countDown(); avatarRelease.await() }
            val bytes = byteArrayOf(1, 2, 3); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/auth/me") { exchange ->
            val bytes = "expired".toByteArray()
            exchange.sendResponseHeaders(401, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/auth/password") { exchange ->
            val bytes = "wrong password".toByteArray()
            exchange.sendResponseHeaders(401, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/auth/account") { exchange ->
            received.add("${exchange.requestMethod} ${exchange.requestURI} ${exchange.requestHeaders.getFirst("Cookie")}")
            val bytes = """{"success":true}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/auth/logout") { exchange -> exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.start()
        val dir = Files.createTempDirectory("room-cloud-auth")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val client = RoomCloudHttpClient()
        try {
            val auth = RoomCloudAuthentication(db.cloudSync(), client)
            val url = "http://127.0.0.1:${server.address.port}"
            val first = auth.login(url, "first", "password")
            db.cloudSync().writeValue(KeyValueRow("cloud_session_host", "$url/subpath"))
            assertNull(auth.restore(url))
            db.cloudSync().writeValue(KeyValueRow("cloud_session_host", url))
            val restored = auth.restore(url)!!
            assertEquals(first.session, restored.session)
            assertEquals("messenger_session=first", restored.cookie)
            assertNull(auth.restore("http://127.0.0.1:${server.address.port + 1}"))
            assertNull(auth.restore("$url/another-base"))
            assertFailsWith<IllegalArgumentException> { RoomCloudApi(client, "$url/another-base", first, auth::expireIfCurrent) }
            assertFailsWith<IllegalStateException> { auth.login(url, "second", "bad-password") }
            assertEquals(first.session, auth.restore(url)!!.session)
            val second = auth.login(url, "second", "password")
            assertNotEquals(first.session, second.session)
            auth.expireIfCurrent(first.session)
            assertEquals(second.session, auth.restore(url)!!.session)
            assertFalse(auth.sessionExpired.value)
            auth.expireIfCurrent(second.session)
            assertNull(auth.restore(url))
            assertTrue(auth.sessionExpired.value)
            auth.acknowledgeSessionExpired()
            assertFalse(auth.sessionExpired.value)
            val third = auth.login(url, "first", "password")
            val api = RoomCloudApi(client, url, third, auth::expireIfCurrent)
            db.cloudSync().saveAgent(Json.parseToJsonElement("""{"_id":"default","name":"Default","isDefault":true}""").jsonObject.agentRow(null))
            val avatars = RoomAvatarCache(dir.resolve("avatars").toString().toPath())
            val coordinator = RoomCloudCoordinator(db, client, url, avatars, cc.ptoe.messenger.data.cloud.CloudImageRehydrator(dir.resolve("chat_images")))
            val avatar = avatars.get(api, third.session, "user", null, "/api/avatars/user", 1)!!
            assertEquals(avatar, avatars.get(api, third.session, "user", null, "/api/avatars/user", 1))
            val otherBase = "http://127.0.0.1:${redirectTarget.address.port}"
            val otherServerApi = RoomCloudApi(client, otherBase, third.copy(host = otherBase), auth::expireIfCurrent)
            val otherAvatar = avatars.get(otherServerApi, third.session, "user", null, "/api/avatars/user", 1)!!
            assertTrue(otherAvatar != avatar)
            assertTrue(Files.readAllBytes(java.nio.file.Path.of(otherAvatar)).contentEquals(byteArrayOf(9, 8, 7)))
            assertEquals(otherAvatar, avatars.cached(otherBase, third.session, "user", null, "/api/avatars/user", 1))
            assertEquals(avatar, avatars.cached(url, third.session, "user", null, "/api/avatars/user", 1))
            assertEquals(1, received.count { it.startsWith("GET /api/avatars/user ") })
            assertTrue(received.filter { it.startsWith("GET /api/avatars/user ") }.all { it.endsWith("auth=null") })
            val other = third.copy(session = third.session.copy(accountId = "other"), cookie = "messenger_session=other")
            val otherApi = RoomCloudApi(client, url, other, auth::expireIfCurrent)
            assertNotEquals(avatar, avatars.get(otherApi, other.session, "user", null, "/api/avatars/user", 1))
            assertFailsWith<IllegalArgumentException> { avatars.get(api, other.session, "user", null, "/api/avatars/user", 2) }
            redirectAvatar.set(true)
            assertFailsWith<IllegalStateException> { avatars.get(api, third.session, "user", null, "malicious/../../x", 3) }
            assertEquals(0, redirectRequests.get())
            redirectAvatar.set(false)
            assertTrue(dir.resolve("avatars").toFile().listFiles().orEmpty().all { it.name.matches(Regex("[0-9a-f]{64}\\.avatar")) })
            assertEquals("first", coordinator.restore()!!.id)
            assertEquals(0, coordinator.sync().latestVersion)
            assertTrue(received.count { it.startsWith("GET /api/sync?") && it.contains("messenger_session=first") } == 4)
            assertEquals(1, coordinator.upload().latestVersion)
            assertTrue(received.any { it.startsWith("PUT /api/agents/default messenger_session=first ") && it.contains("\"isDefault\":true") })
            api.listMarket("hello world", "next")
            api.registerPushDevice("registration")
            val dao = db.cloudSync()
            dao.updateCloudUser(third.session, third.user.copy(aiApiKey = "sk-model"), url)
            val models = RoomBuiltinModels(dao, api, third.session)
            assertEquals(1, models.sync(force = true))
            val model = dao.providerModels(BUILTIN_PROVIDER_ID).single()
            assertEquals("Model", model.displayName)
            assertEquals(1.5, model.cacheWriteRate)
            assertTrue(model.supportsThinking)
            dao.saveModels(listOf(model.copy(isEnabled = false)))
            assertEquals(1, models.sync(force = true))
            assertFalse(dao.providerModels(BUILTIN_PROVIDER_ID).single().isEnabled)
            assertTrue(received.any { it.startsWith("GET /v1/models Bearer sk-model") && it.endsWith("cookie=null") })
            assertFailsWith<IllegalStateException> { api.builtinModels("bad-key") }
            redirectModels.set(true)
            assertFailsWith<IllegalStateException> { api.builtinModels("sk-model") }
            assertEquals(0, redirectRequests.get())
            redirectModels.set(false)
            assertEquals(third.session, auth.restore(url)!!.session)
            val localAgent = Json.parseToJsonElement("""{"_id":"local","name":"Local","systemPrompt":"prompt"}""").jsonObject.agentRow(null)
            dao.saveAgent(localAgent)
            val market = RoomCloudMarket(dao, api, third.session, avatars)
            assertEquals("market-1", market.publish("local").id)
            assertEquals("publisher", dao.agentRow("local")!!.marketAgentRole)
            val imported = market.import("market-1")
            assertEquals("importer", imported.marketAgentRole)
            val changed = market.applyUpdate(imported.id, market.checkUpdate(imported.id).agent.copy(version = 4, name = "Updated"))
            assertEquals("Updated", changed.name)
            market.removePublished("local")
            assertNull(dao.agentRow("local")!!.marketAgentId)
            assertTrue(dao.pending(third.session).any { it.id == imported.id })
            assertEquals("/avatar", api.avatar("agent-1", "a.png", byteArrayOf(1, 2), "image/png").url)
            assertTrue(received.any { it.startsWith("GET /api/market/agents?") && it.contains("query=hello") && it.contains("cursor=next") && it.contains("messenger_session=first") })
            assertTrue(received.any { it.startsWith("PUT /api/push/device ") && it.contains("messenger_session=first") && it.contains("registration") })
            assertTrue(received.any { it.startsWith("PUT /api/avatars/agents/agent-1 ") && it.contains("filename=\"a.png\"") && it.contains("messenger_session=first") })
            assertFailsWith<IllegalStateException> { coordinator.changePassword("wrong", "new") }
            assertEquals(third.session, auth.restore(url)!!.session)
            assertFalse(auth.sessionExpired.value)
            assertFailsWith<CloudSessionExpired> { coordinator.refreshUser() }
            assertNull(coordinator.user.value)
            assertNull(auth.restore(url))
            assertTrue(coordinator.sessionExpired.value)
            coordinator.acknowledgeSessionExpired()
            auth.login(url, "first", "password")
            auth.logout(url)
            assertNull(auth.restore(url))
            assertNull(dao.agentRow(imported.id)!!.marketAgentId)
            assertNull(dao.providerRow(BUILTIN_PROVIDER_ID))
            slowAvatar.set(true)
            val pendingLogin = async(Dispatchers.IO) { runCatching { coordinator.login("slow", "password") } }
            assertTrue(withContext(Dispatchers.IO) { avatarStarted.await(10, java.util.concurrent.TimeUnit.SECONDS) })
            assertFailsWith<IllegalStateException> { auth.login(url, "second", "password") }
            assertFailsWith<IllegalStateException> { auth.login(url, "first", "password") }
            avatarRelease.countDown()
            assertTrue(pendingLogin.await().isSuccess)
            val latest = auth.login(url, "first", "password")
            assertEquals(latest.session, auth.restore(url)!!.session)
            assertEquals("first", coordinator.restore()!!.id)
            coordinator.deleteAccount("password")
            assertNull(coordinator.user.value)
            assertNull(auth.restore(url))
            assertTrue(received.any { it.startsWith("DELETE /api/auth/account messenger_session=first") })
        } finally {
            avatarRelease.countDown(); client.close(); db.close(); server.stop(0); redirectTarget.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively()
        }
    }
}
