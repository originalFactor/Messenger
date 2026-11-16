/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.cloud.*
import cc.ptoe.messenger.data.chat.RoomKotlinTurnStore
import cc.ptoe.messenger.domain.chat.KotlinTurnAdmission
import cc.ptoe.messenger.domain.chat.KotlinTurnCommand
import cc.ptoe.messenger.data.repository.room.RoomProjectRepository
import cc.ptoe.messenger.domain.model.Project
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import okio.Path.Companion.toPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoomCloudReplacementTest {
    @Test fun plansChecksAndExecutesReplacementThroughHttpAndRoom(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val version = AtomicLong(10)
        val remote = mutableMapOf(
            "agents" to linkedMapOf("cloud-default" to Json.parseToJsonElement("""{"_id":"cloud-default","isDefault":true}""").jsonObject,
                "obsolete" to Json.parseToJsonElement("""{"_id":"obsolete","isDefault":false}""").jsonObject),
            "providers" to linkedMapOf(), "projects" to linkedMapOf(), "conversations" to linkedMapOf(),
        )
        val writes = mutableListOf<String>()
        val deleteStarted = CountDownLatch(1)
        val releaseDelete = CountDownLatch(1)
        val holdDelete = AtomicBoolean(true)
        val failDelete = AtomicBoolean(false)
        fun reply(exchange: com.sun.net.httpserver.HttpExchange, body: String, status: Int = 200) {
            val bytes = body.toByteArray(); exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/auth/me") { exchange -> reply(exchange, """{"user":{"id":"account","syncVersion":${version.get()}}}""") }
        server.createContext("/api/sync") { exchange ->
            val collection = exchange.requestURI.query.split('&').first { it.startsWith("collection=") }.substringAfter('=')
            val docs = JsonArray(remote.getValue(collection).values.toList())
            reply(exchange, """{"collection":"$collection","documents":$docs,"hasMore":false,"nextCursor":null,"latestVersion":${version.get()}}""")
        }
        for (collection in listOf("agents", "conversations", "projects", "providers")) {
            server.createContext("/api/$collection/") { exchange ->
                val id = exchange.requestURI.path.substringAfterLast('/')
                writes += "${exchange.requestMethod}:$collection:$id"
                if (exchange.requestMethod == "DELETE") {
                    if (holdDelete.get()) { deleteStarted.countDown(); releaseDelete.await(10, TimeUnit.SECONDS) }
                    if (failDelete.get()) { reply(exchange, "failed", 503); return@createContext }
                    remote.getValue(collection)[id] = JsonObject(mapOf("_id" to JsonPrimitive(id), "deleted" to JsonPrimitive(true)))
                } else {
                    val body = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                    remote.getValue(collection)[id] = JsonObject(body + ("_id" to JsonPrimitive(id)))
                }
                reply(exchange, """{"version":${version.incrementAndGet()}}""")
            }
        }
        server.start()
        val dir = Files.createTempDirectory("room-cloud-replacement")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val client = RoomCloudHttpClient()
        try {
            val dao = db.cloudSync(); val identity = SyncSession("account", "generation")
            val url = "http://127.0.0.1:${server.address.port}"
            val credentials = StoredCloudSession(identity, "messenger_session=token", url, CloudUser(id = "account"))
            dao.saveCredentials(credentials)
            dao.saveAgent(Json.parseToJsonElement("""{"_id":"local-default","name":"Default","isDefault":true}""").jsonObject.agentRow(null))
            dao.saveConversation(Json.parseToJsonElement("""{"_id":"chat","title":"Chat","agentId":"local-default","messages":[]}""").jsonObject.conversationRow())
            val api = RoomCloudApi(client, url, credentials) { }
            val transport = KtorSyncTransport(client.http, url, identity, credentials.cookie)
            val replacement = RoomCloudReplacement(db, api, transport, identity, cc.ptoe.messenger.data.cloud.CloudImageRehydrator(dir.resolve("chat_images"))) { }
            val stale = replacement.plan()
            if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
                val original = Files.getPosixFilePermissions(dir)
                try {
                    Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
                    assertFailsWith<IllegalStateException> { replacement.execute(stale) }
                    assertTrue(writes.isEmpty())
                } finally { Files.setPosixFilePermissions(dir, original) }
            } else {
                assertFailsWith<IllegalStateException> { replacement.execute(stale) }
                assertTrue(writes.isEmpty())
                return@runBlocking
            }
            version.incrementAndGet()
            val conflict = assertFailsWith<CloudReplacementFailure> { replacement.execute(stale) }
            assertTrue(conflict.completedDeletions.isEmpty()); assertTrue(writes.isEmpty())
            val plan = replacement.plan()
            val coordinator = RoomCloudCoordinator(db, client, url, RoomAvatarCache(dir.resolve("avatars").toString().toPath()), CloudImageRehydrator(dir.resolve("chat_images")))
            coordinator.restore()
            val entered = CompletableDeferred<Unit>()
            val releaseOperation = CompletableDeferred<Unit>()
            val otherOperation = async(Dispatchers.Default) {
                coordinator.withSession { _, _ -> entered.complete(Unit); releaseOperation.await() }
            }
            entered.await()
            try {
                assertFailsWith<IllegalStateException> { replacement.execute(plan) }
                assertTrue(writes.isEmpty())
            } finally { releaseOperation.complete(Unit) }
            otherOperation.await()
            dao.writeValue(KeyValueRow("kotlin_turn_active_v1:chat", "turn"))
            assertFailsWith<IllegalStateException> { replacement.execute(plan) }
            assertTrue(writes.isEmpty())
            dao.deleteValue("kotlin_turn_active_v1:chat")
            val pending = async(Dispatchers.Default) { replacement.execute(plan) }
            assertTrue(withContext(Dispatchers.IO) { deleteStarted.await(5, TimeUnit.SECONDS) })
            val turns = RoomKotlinTurnStore(db, { _, _, _, _, _, _ -> error("must not prepare") }, { _, _, _ -> true })
            val blocked = turns.admit(KotlinTurnCommand.Send("chat", "hello", null))
            assertTrue(blocked is KotlinTurnAdmission.Rejected && blocked.reason.contains("replacement"))
            assertTrue(dao.value(CLOUD_REPLACEMENT_LOCK_KEY) != null)
            val metaBefore = dao.allSyncMeta()
            assertFailsWith<IllegalStateException> { dao.applyPull(identity, PulledCloudDelta(emptyMap(), 99), false) }
            assertFailsWith<IllegalStateException> { dao.pendingSnapshots(identity) }
            assertFailsWith<IllegalStateException> { RoomProjectRepository(db).insert(Project("blocked", "Blocked", "/tmp", 1, 1)) }
            assertEquals(metaBefore, dao.allSyncMeta())
            assertEquals(null, dao.projectRow("blocked"))
            holdDelete.set(false)
            releaseDelete.countDown()
            val result = pending.await()
            assertEquals(null, dao.value(CLOUD_REPLACEMENT_LOCK_KEY))
            assertEquals("cloud-default", dao.replacementDefaults().single().id)
            assertEquals("cloud-default", dao.conversationRow("chat")!!.agentId)
            assertTrue(dao.pending(identity).isEmpty())
            assertTrue(writes.contains("DELETE:agents:obsolete"))
            assertTrue(writes.contains("PUT:agents:cloud-default"))
            assertTrue(writes.contains("PUT:conversations:chat"))
            assertEquals(version.get(), result.latestVersion)
            remote.getValue("agents")["new-obsolete"] = Json.parseToJsonElement("""{"_id":"new-obsolete","isDefault":false}""").jsonObject
            version.incrementAndGet()
            val failurePlan = replacement.plan()
            failDelete.set(true)
            assertFailsWith<CloudReplacementFailure> { replacement.execute(failurePlan) }
            assertTrue(dao.value(CLOUD_REPLACEMENT_LOCK_KEY) != null)
            assertFailsWith<IllegalStateException> { replacement.execute(failurePlan) }
            assertTrue(turns.admit(KotlinTurnCommand.Send("chat", "hello", null)) is KotlinTurnAdmission.Rejected)
        } finally { releaseDelete.countDown(); client.close(); db.close(); server.stop(0); dir.toFile().deleteRecursively() }
    }
}
