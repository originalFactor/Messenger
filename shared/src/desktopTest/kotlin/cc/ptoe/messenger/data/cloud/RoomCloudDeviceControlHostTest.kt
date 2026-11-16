/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomCloudDeviceControlHostTest {
    private val session = SyncSession("account", "generation")
    private class Relay : AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newCachedThreadPool()
        private val release = CountDownLatch(1)
        private val output = AtomicReference<OutputStream>()
        val packets = java.util.concurrent.ConcurrentLinkedQueue<JsonObject>()
        val cookies = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val status = AtomicInteger(202)
        val replyStatus = AtomicInteger(0)
        val connection = UUID.randomUUID().toString()
        val host get() = "http://127.0.0.1:${server.address.port}"
        init {
            server.executor = executor
            server.createContext("/api/remote/events") { exchange ->
                cookies.add(exchange.requestHeaders.getFirst("Cookie").orEmpty())
                check(exchange.requestURI.rawQuery.contains("deviceId=device-1") && exchange.requestURI.rawQuery.contains("control=host"))
                exchange.sendResponseHeaders(200, 0)
                output.set(exchange.responseBody)
                send("controlReady", """{"connectionId":"$connection"}""")
                release.await(15, TimeUnit.SECONDS)
                exchange.close()
            }
            server.createContext("/api/remote/control") { exchange ->
                cookies.add(exchange.requestHeaders.getFirst("Cookie").orEmpty())
                val packet = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()) as JsonObject
                packets.add(packet)
                val code = if (packet["kind"]?.jsonPrimitive?.content == "reply" && replyStatus.get() != 0) replyStatus.get() else status.get()
                if (code == 503) exchange.responseHeaders.add("Retry-After", "0")
                exchange.sendResponseHeaders(code, -1); exchange.close()
            }
            server.start()
        }
        fun send(event: String, value: String) = synchronized(output) {
            output.get().write("event: $event\ndata: $value\n\n".toByteArray())
            output.get().flush()
        }
        fun disconnect() = synchronized(output) { output.get().close() }
        override fun close() { release.countDown(); server.stop(0); executor.shutdownNow() }
    }
    private val passReply = RoomDeviceReplyGate { _, send -> send() }
    private fun identity(host: String) = StoredCloudSession(session, "messenger_session=secret", host, CloudUser(id = "account"))
    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test fun advertisedHostForwardsUpdatesAndPermissionResponses(): Unit = runBlocking {
        Relay().use { relay ->
            val grant = MutableStateFlow<RoomDeviceControlGrant?>(RoomDeviceControlGrant("Phone", 1))
            val finished = CompletableDeferred<String>()
            val host = RoomCloudDeviceControlHost(identity(relay.host), "device-1", grant, { it == session }, {}, passReply,
                RoomDeviceCommandDispatcher { _, command, permissions, update ->
                    assertEquals("prompt", command.getValue("action").jsonPrimitive.content)
                    update(buildJsonObject { put("type", "Permission") })
                    val selected = permissions.receive()
                    finished.complete(selected)
                    buildJsonObject { put("type", "Finished") }
                })
            val running = async { runCatching { host.runOnce() } }
            try {
                awaitCondition { relay.packets.any { it["kind"]?.jsonPrimitive?.content == "advertise" } }
                val id = UUID.randomUUID().toString()
                relay.send("controlRequest", """{"id":"$id","payload":{"action":"prompt"}}""")
                awaitCondition { relay.packets.any { it["kind"]?.jsonPrimitive?.content == "reply" } }
                relay.send("controlSignal", """{"id":"$id","optionId":"approve"}""")
                assertEquals("approve", withTimeout(5_000) { finished.await() })
                awaitCondition { relay.packets.count { it["kind"]?.jsonPrimitive?.content == "reply" } == 2 }
                val replies = relay.packets.filter { it["kind"]?.jsonPrimitive?.content == "reply" }
                assertEquals(listOf("false", "true"), replies.map { it.getValue("done").jsonPrimitive.content })
                assertTrue(replies.all { it.getValue("id").jsonPrimitive.content == id })
                assertTrue(relay.cookies.all { it == "messenger_session=secret" })
            } finally { host.shutdown(); running.await() }
        }
    }

    @Test fun disablingGrantCancelsRunningCommandAndStopsAdvertising(): Unit = runBlocking {
        Relay().use { relay ->
            val grant = MutableStateFlow<RoomDeviceControlGrant?>(RoomDeviceControlGrant("Phone", 1))
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val host = RoomCloudDeviceControlHost(identity(relay.host), "device-1", grant, { true }, {}, passReply,
                RoomDeviceCommandDispatcher { _, _, _, _ ->
                    try { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
                    finally { stopped.complete(Unit) }
                })
            val running = async { runCatching { host.runOnce() } }
            try {
                awaitCondition { relay.packets.isNotEmpty() }
                relay.send("controlRequest", """{"id":"${UUID.randomUUID()}","payload":{"action":"prompt"}}""")
                withTimeout(5_000) { entered.await() }
                grant.value = null
                withTimeout(5_000) { stopped.await() }
                awaitCondition { relay.packets.any { it["kind"]?.jsonPrimitive?.content == "advertise" && it["enabled"]?.jsonPrimitive?.content == "false" } }
                assertFalse(relay.packets.any { it["kind"]?.jsonPrimitive?.content == "reply" })
            } finally { host.shutdown(); running.await() }
        }
    }

    @Test fun changedEpochWaitsForOldWorkBeforeAdvertisingAgain(): Unit = runBlocking {
        Relay().use { relay ->
            val grant = MutableStateFlow<RoomDeviceControlGrant?>(RoomDeviceControlGrant("Phone", 1))
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val host = RoomCloudDeviceControlHost(identity(relay.host), "device-1", grant, { true }, {}, passReply,
                RoomDeviceCommandDispatcher { _, _, _, _ ->
                    try { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
                    finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { cleaning.complete(Unit); release.await() } }
                })
            val running = async { runCatching { host.runOnce() } }
            try {
                awaitCondition { relay.packets.any { it["kind"]?.jsonPrimitive?.content == "advertise" && it["enabled"]?.jsonPrimitive?.content == "true" } }
                relay.send("controlRequest", """{"id":"${UUID.randomUUID()}","payload":{"action":"prompt"}}""")
                withTimeout(5_000) { entered.await() }
                val previous = relay.packets.count { it["kind"]?.jsonPrimitive?.content == "advertise" && it["enabled"]?.jsonPrimitive?.content == "true" }
                // Simulates StateFlow conflating a disable (epoch 2) and immediate enable (epoch 3).
                grant.value = RoomDeviceControlGrant("Phone", 3)
                withTimeout(5_000) { cleaning.await() }
                delay(150)
                assertEquals(previous, relay.packets.count { it["kind"]?.jsonPrimitive?.content == "advertise" && it["enabled"]?.jsonPrimitive?.content == "true" })
                release.complete(Unit)
                awaitCondition { relay.packets.count { it["kind"]?.jsonPrimitive?.content == "advertise" && it["enabled"]?.jsonPrimitive?.content == "true" } > previous }
                assertFalse(relay.packets.any { it["kind"]?.jsonPrimitive?.content == "reply" })
            } finally { release.complete(Unit); host.shutdown(); running.await() }
        }
    }

    @Test fun failedAdvertisementDoesNotRetryOrDispatch(): Unit = runBlocking {
        Relay().use { relay ->
            relay.status.set(503)
            val invoked = AtomicInteger()
            val host = RoomCloudDeviceControlHost(identity(relay.host), "device-1", MutableStateFlow(RoomDeviceControlGrant("Phone", 1)), { true }, {}, passReply,
                RoomDeviceCommandDispatcher { _, _, _, _ -> invoked.incrementAndGet(); buildJsonObject { put("type", "Finished") } })
            try {
                assertTrue(runCatching { withTimeout(5_000) { host.runOnce() } }.isFailure)
                assertEquals(1, relay.packets.size)
                assertEquals(0, invoked.get())
            } finally { host.shutdown() }
        }
    }

    @Test fun replyUnauthorizedReleasesFenceBeforeSessionRevocation(): Unit = runBlocking {
        Relay().use { relay ->
            val dir = Files.createTempDirectory("control-401-fence")
            val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
            val provider = RoomCloudProviderGate.forDao(db.cloudSync())
            val stored = identity(relay.host)
            try {
                db.rows().upsertValues(listOf(
                    KeyValueRow("kotlin_sync_session", """["account","generation"]"""), KeyValueRow("device_id", "device-1"),
                    KeyValueRow("cloud_session", stored.cookie), KeyValueRow("cloud_session_host", stored.host),
                    KeyValueRow("cloud_user_json", """{"id":"account"}"""),
                ))
                RoomDeviceControlGrantStore(db).save(KotlinRemoteControlScope("account", "generation", stored.host, "device-1"), null,
                    buildJsonObject { put("enabled", true) })
                val fence = RoomDeviceControlFence.forDao(db.cloudSync())
                val host = RoomCloudDeviceControlHost(stored, "device-1", MutableStateFlow(RoomDeviceControlGrant("Phone", 1)), { true },
                    { provider.revoke(it) }, fence.replies(db, stored, "device-1"),
                    RoomDeviceCommandDispatcher { _, _, _, _ -> buildJsonObject { put("type", "Finished") } })
                val running = async { runCatching { host.runOnce() } }
                try {
                    awaitCondition { relay.packets.any { it["kind"]?.jsonPrimitive?.content == "advertise" } }
                    relay.replyStatus.set(401)
                    relay.send("controlRequest", """{"id":"${UUID.randomUUID()}","payload":{"action":"prompt"}}""")
                    val failure = withTimeout(5_000) { running.await() }.exceptionOrNull()
                    assertTrue(failure is CloudSessionExpired, "Expected CloudSessionExpired, got $failure")
                    assertEquals(1, relay.packets.count { it["kind"]?.jsonPrimitive?.content == "reply" })
                    assertEquals(null, db.rows().getValue("cloud_session"))
                } finally { host.shutdown(); running.await() }
            } finally { provider.shutdown(); db.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun remoteCancellationWaitsForDispatcherCleanupBeforeTerminalReply(): Unit = runBlocking {
        Relay().use { relay ->
            val entered = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val allowCleanup = CompletableDeferred<Unit>()
            val host = RoomCloudDeviceControlHost(identity(relay.host), "device-1", MutableStateFlow(RoomDeviceControlGrant("Phone", 1)), { true }, {}, passReply,
                RoomDeviceCommandDispatcher { _, _, _, _ ->
                    try { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
                    finally {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { allowCleanup.await(); cleanup.complete(Unit) }
                    }
                })
            val running = async { runCatching { host.runOnce() } }
            try {
                awaitCondition { relay.packets.isNotEmpty() }
                val id = UUID.randomUUID().toString()
                relay.send("controlRequest", """{"id":"$id","payload":{"action":"prompt"}}""")
                withTimeout(5_000) { entered.await() }
                relay.send("controlSignal", """{"id":"$id"}""")
                delay(100)
                assertFalse(relay.packets.any { it["kind"]?.jsonPrimitive?.content == "reply" })
                allowCleanup.complete(Unit)
                withTimeout(5_000) { cleanup.await() }
                awaitCondition { relay.packets.any { it["kind"]?.jsonPrimitive?.content == "reply" } }
                val reply = relay.packets.single { it["kind"]?.jsonPrimitive?.content == "reply" }
                assertEquals("true", reply.getValue("done").jsonPrimitive.content)
                assertEquals("Cancelled", (reply.getValue("payload") as JsonObject).getValue("type").jsonPrimitive.content)
            } finally { allowCleanup.complete(Unit); host.shutdown(); running.await() }
        }
    }

    @Test fun reconnectRejectsPreviouslyExecutedRequestIdentity(): Unit = runBlocking {
        Relay().use { relay ->
            val invoked = AtomicInteger()
            val host = RoomCloudDeviceControlHost(identity(relay.host), "device-1", MutableStateFlow(RoomDeviceControlGrant("Phone", 1)), { true }, {}, passReply,
                RoomDeviceCommandDispatcher { _, _, _, _ -> invoked.incrementAndGet(); buildJsonObject { put("type", "Finished") } })
            val id = UUID.randomUUID().toString()
            try {
                val first = async { runCatching { host.runOnce() } }
                awaitCondition { relay.packets.any { it["kind"]?.jsonPrimitive?.content == "advertise" } }
                relay.send("controlRequest", """{"id":"$id","payload":{"action":"prompt"}}""")
                awaitCondition { relay.packets.any { it["kind"]?.jsonPrimitive?.content == "reply" } }
                assertEquals(1, invoked.get())
                relay.disconnect()
                assertTrue(withTimeout(5_000) { first.await() }.isFailure)
                val advertised = relay.packets.count { it["kind"]?.jsonPrimitive?.content == "advertise" }
                val second = async { runCatching { host.runOnce() } }
                awaitCondition { relay.packets.count { it["kind"]?.jsonPrimitive?.content == "advertise" } > advertised }
                relay.send("controlRequest", """{"id":"$id","payload":{"action":"prompt"}}""")
                assertTrue(withTimeout(5_000) { second.await() }.isFailure)
                assertEquals(1, invoked.get())
            } finally { host.shutdown() }
        }
    }

    @Test fun workspacePresenceContinuesWhenRemoteControlIsDisabled(): Unit = runBlocking {
        Relay().use { relay ->
            val seen = CompletableDeferred<JsonObject>()
            val disconnected = CompletableDeferred<Unit>()
            val workspace = object : RoomDeviceWorkspaceRelay {
                override suspend fun event(kind: String, payload: JsonObject) { if (kind == "presence") seen.complete(payload) }
                override suspend fun disconnected() { disconnected.complete(Unit) }
            }
            val host = RoomCloudDeviceControlHost(identity(relay.host), "device-1", MutableStateFlow(null), { true }, {}, passReply,
                RoomDeviceCommandDispatcher { _, _, _, _ -> error("Control must remain disabled") }, workspace)
            val running = async { runCatching { host.runOnce() } }
            try {
                awaitCondition { relay.packets.any { it["kind"]?.jsonPrimitive?.content == "advertise" } }
                assertEquals("false", relay.packets.first { it["kind"]?.jsonPrimitive?.content == "advertise" }.getValue("enabled").jsonPrimitive.content)
                relay.send("presence", """{"devices":["device-1","other"]}""")
                assertTrue(withTimeout(5_000) { seen.await() }.toString().contains("other"))
            } finally { host.shutdown(); running.await() }
            assertTrue(disconnected.isCompleted)
        }
    }
}
