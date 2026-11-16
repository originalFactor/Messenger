/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class IsolatedRoomDeviceHostTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private class Relay(private val rejectEvents: Boolean = false) : AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newCachedThreadPool()
        private val releaseStreams = CountDownLatch(1)
        val releaseReply = CountDownLatch(1)
        val blockReply = AtomicBoolean(false)
        val replyEntered = CompletableDeferred<Unit>()
        val packets = ConcurrentLinkedQueue<Pair<String, JsonObject>>()
        val connections = ConcurrentLinkedQueue<String>()
        private val streams = java.util.concurrent.ConcurrentHashMap<String, OutputStream>()
        val base get() = "http://127.0.0.1:${server.address.port}"
        init {
            server.executor = executor
            server.createContext("/api/remote/events") { exchange ->
                val cookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()
                connections.add(cookie)
                if (rejectEvents) { exchange.sendResponseHeaders(401, -1); exchange.close() }
                else {
                    try {
                        exchange.sendResponseHeaders(200, 0)
                        streams[cookie] = exchange.responseBody
                        send(cookie, "controlReady", """{"connectionId":"${UUID.randomUUID()}"}""")
                        releaseStreams.await(20, TimeUnit.SECONDS)
                    } catch (_: Exception) { } finally { exchange.close() }
                }
            }
            server.createContext("/api/remote/control") { exchange ->
                try {
                    val cookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()
                    val packet = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                    packets.add(cookie to packet)
                    if (packet["kind"]?.jsonPrimitive?.content == "reply" && blockReply.get()) {
                        replyEntered.complete(Unit)
                        releaseReply.await(15, TimeUnit.SECONDS)
                    }
                    exchange.sendResponseHeaders(202, -1)
                } catch (_: Exception) { } finally { exchange.close() }
            }
            server.createContext("/api/auth/login") { exchange ->
                val body = """{"user":{"id":"account"}}""".toByteArray()
                exchange.responseHeaders.add("Set-Cookie", "messenger_session=two; Path=/")
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
        }
        fun send(cookie: String, event: String, data: String) {
            val out = checkNotNull(streams[cookie])
            synchronized(out) { out.write("event: $event\ndata: $data\n\n".toByteArray()); out.flush() }
        }
        fun command(cookie: String, action: String): String = UUID.randomUUID().toString().also {
            send(cookie, "controlRequest", """{"id":"$it","payload":{"action":"$action"}}""")
        }
        fun reply(id: String) = packets.firstOrNull { it.second["id"]?.jsonPrimitive?.content == id }?.second
        override fun close() { releaseReply.countDown(); releaseStreams.countDown(); server.stop(0); executor.shutdownNow() }
    }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(8_000) { while (!condition()) delay(10) }
    private fun config(enabled: Boolean) = buildJsonObject { put("enabled", enabled); put("name", "Phone") }

    private suspend fun open(db: LocalDatabase, dir: java.nio.file.Path, relay: Relay, lifecycle: RoomManagedShutdown): RoomCloudFacade {
        db.rows().upsertValues(listOf(
            KeyValueRow("cloud_session", "messenger_session=one"), KeyValueRow("cloud_session_host", relay.base),
            KeyValueRow("cloud_user_json", """{"id":"account"}"""), KeyValueRow("device_id", "device-1"),
            KeyValueRow("device_control_grant", """["${relay.base}","account"]"""),
            KeyValueRow("device_control_config", config(true).toString()), KeyValueRow(REMOTE_CONTROL_EPOCH_KEY, "1"),
        ))
        val transport = RoomCloudHttpClient()
        lifecycle.registerStop { transport.close() }
        return RoomCloudFacade.open(db, AppPreferences(MemoryPreferences()), transport,
            RoomAvatarCache(dir.resolve("avatars").toString().toPath()), CloudImageRehydrator(dir.resolve("images")), relay.base,
            onCreated = { facade -> lifecycle.registerStop { facade.shutdown() } })
    }

    @Test fun revokeFencesRepliesAndSessionReplacementAndShutdownStopTheOldHost(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-device-composition")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val lifecycle = RoomManagedShutdown { }
        Relay().use { relay ->
            try {
                lifecycle.enter()
                val cloud = open(db, dir, relay, lifecycle)
                val other = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("other.db").toString()).setDriver(BundledSQLiteDriver()).build()
                try {
                    assertFailsWith<IllegalStateException> {
                        IsolatedRoomDeviceHost.attach(other, cloud, lifecycle, IsolatedRoomDeviceHostConfig { it })
                    }
                } finally { other.close() }
                IsolatedRoomDeviceHost.attach(db, cloud, lifecycle, IsolatedRoomDeviceHostConfig { "Messenger $it" })
                eventually { relay.packets.any { it.first == "messenger_session=one" && it.second["enabled"]?.jsonPrimitive?.content == "true" } }
                val unsupported = relay.command("messenger_session=one", "create")
                eventually { relay.reply(unsupported) != null }
                assertEquals("unavailable", relay.reply(unsupported)!!.getValue("payload").jsonObject.getValue("code").jsonPrimitive.content)

                relay.blockReply.set(true)
                relay.command("messenger_session=one", "catalog")
                withTimeout(8_000) { relay.replyEntered.await() }
                val session = checkNotNull(db.cloudSync().currentSession())
                val grant = RoomDeviceControlGrantStore(db)
                val scope = KotlinRemoteControlScope("account", session.generation, relay.base, "device-1")
                val revoke = async { grant.save(scope, 1, config(false)) }
                delay(100)
                assertFalse(revoke.isCompleted)
                relay.releaseReply.countDown()
                assertEquals(2L, withTimeout(8_000) { revoke.await() })
                eventually { relay.packets.any { it.first == "messenger_session=one" && it.second["enabled"]?.jsonPrimitive?.content == "false" } }
                grant.save(scope, 2, config(true))
                eventually { relay.packets.count { it.first == "messenger_session=one" && it.second["enabled"]?.jsonPrimitive?.content == "true" } >= 2 }
                relay.blockReply.set(false)
                cloud.login("account@example.com", "password", null)
                eventually { relay.packets.any { it.first == "messenger_session=two" && it.second["enabled"]?.jsonPrimitive?.content == "true" } }
                val replacement = relay.command("messenger_session=two", "catalog")
                eventually { relay.reply(replacement) != null }
                assertEquals("Catalog", relay.reply(replacement)!!.getValue("payload").jsonObject.getValue("type").jsonPrimitive.content)
                withTimeout(8_000) { lifecycle.stopAndAwait() }
                val oldCount = relay.packets.size
                runCatching { relay.command("messenger_session=two", "catalog") }
                delay(200)
                assertEquals(oldCount, relay.packets.size)
                assertEquals(1, relay.connections.count { it == "messenger_session=one" })
                assertEquals(1, relay.connections.count { it == "messenger_session=two" })
            } finally {
                relay.releaseReply.countDown()
                if (lifecycle.state.value == cc.ptoe.messenger.data.migration.RoomShutdownState.Running) lifecycle.stopAndAwait()
                db.close(); dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun relayUnauthorizedExpiresTheSameFacadeSessionAndDoesNotReconnect(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-device-expiry")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val lifecycle = RoomManagedShutdown { }
        Relay(rejectEvents = true).use { relay ->
            try {
                lifecycle.enter()
                val cloud = open(db, dir, relay, lifecycle)
                IsolatedRoomDeviceHost.attach(db, cloud, lifecycle, IsolatedRoomDeviceHostConfig { it })
                withTimeout(8_000) { cloud.sessionExpired.first { it } }
                assertNull(cloud.user.first())
                assertNull(db.cloudSync().currentSession())
                delay(200)
                assertEquals(listOf("messenger_session=one"), relay.connections.toList())
                withTimeout(8_000) { lifecycle.stopAndAwait() }
            } finally {
                if (lifecycle.state.value == cc.ptoe.messenger.data.migration.RoomShutdownState.Running) lifecycle.stopAndAwait()
                db.close(); dir.toFile().deleteRecursively()
            }
        }
    }
}
