/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomCloudRemoteControlTest {
    private val identity = SyncSession("user", "generation")
    private fun stored(host: String) = StoredCloudSession(identity, "messenger_session=secret", host, CloudUser(id = "user"))
    private fun json(action: String): JsonObject = buildJsonObject { put("action", action) }

    private class Relay : AutoCloseable {
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val cookies = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val packets = java.util.concurrent.ConcurrentLinkedQueue<JsonObject>()
        val requests = AtomicInteger()
        val eventStatus = AtomicInteger(200)
        val postStatus = AtomicInteger(202)
        val oversized = AtomicBoolean(false)
        val responseDelay = java.util.concurrent.atomic.AtomicLong(0)
        val output = AtomicReference<OutputStream?>()
        val release = CountDownLatch(1)
        val connection = UUID.randomUUID().toString()
        val host: String get() = "http://127.0.0.1:${server.address.port}"
        init {
            server.executor = executor
            server.createContext("/api/remote/events") { exchange ->
                cookies.add(exchange.requestHeaders.getFirst("Cookie").orEmpty())
                val status = eventStatus.get()
                if (status != 200) { exchange.sendResponseHeaders(status, -1); exchange.close(); return@createContext }
                check(exchange.requestURI.rawQuery.contains("control=client") && exchange.requestURI.rawQuery.contains("controlOnly=1"))
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                output.set(exchange.responseBody)
                if (oversized.get()) write("data: " + "x".repeat(512 * 1024 + 1))
                else write("event: controlReady\r\ndata: {\"connectionId\":\"$connection\"}\r\n\r\n")
                release.await(25, TimeUnit.SECONDS)
                exchange.close()
            }
            server.createContext("/api/remote/control") { exchange ->
                cookies.add(exchange.requestHeaders.getFirst("Cookie").orEmpty())
                requests.incrementAndGet()
                val body = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()) as JsonObject
                packets.add(body)
                val status = postStatus.get()
                if (status == 503) exchange.responseHeaders.add("Retry-After", "0")
                exchange.sendResponseHeaders(status, -1); exchange.close()
                if (status !in 200..299) return@createContext
                Thread.sleep(responseDelay.get())
                val id = body.getValue("id").jsonPrimitive.content
                val kind = body.getValue("kind").jsonPrimitive.content
                if (kind == "request") {
                    val action = (body["payload"] as JsonObject).getValue("action").jsonPrimitive.content
                    if (action == "prompt") reply(id, "Permission", false)
                    else {
                        reply(UUID.randomUUID().toString(), "Ignored", true)
                        reply(id, "Session", true)
                    }
                } else if (kind == "signal") reply(id, "Finished", true)
            }
            server.start()
        }
        private fun write(value: String) = synchronized(this) { output.get()?.let { it.write(value.toByteArray()); it.flush() } }
        private fun reply(id: String, type: String, done: Boolean) = write("event: controlReply\ndata: {\"id\":\"$id\",\"payload\":{\"type\":\"$type\"},\"done\":$done}\n\n")
        override fun close() { release.countDown(); server.stop(0); executor.shutdownNow() }
    }

    @Test fun handshakeRepliesAndSignalsUseOneBoundSession(): Unit = runBlocking {
        Relay().use { relay ->
            val expired = AtomicInteger()
            val remote = RoomCloudRemoteControl(stored(relay.host), { it == identity }, { expired.incrementAndGet() })
            try {
                val id = UUID.randomUUID().toString()
                val permission = CompletableDeferred<Unit>()
                val events = mutableListOf<String>()
                val request = async {
                    remote.command(id, "desktop-1", json("prompt")) { event ->
                        val type = event.getValue("type").jsonPrimitive.content
                        events += type
                        if (type == "Permission") permission.complete(Unit)
                    }
                }
                withTimeout(5_000) { permission.await() }
                assertFailsWith<IllegalStateException> { remote.command(id, "desktop-1", json("history")) {} }
                remote.signal(id, "approve")
                withTimeout(5_000) { request.await() }
                assertEquals(listOf("Permission", "Finished"), events)
                assertEquals(listOf("request", "signal"), relay.packets.map { it.getValue("kind").jsonPrimitive.content })
                assertEquals("approve", relay.packets.last()["optionId"]!!.jsonPrimitive.content)
                assertTrue(relay.cookies.all { it == "messenger_session=secret" })
                assertEquals(0, expired.get())
                assertFailsWith<IllegalStateException> { remote.signal(id, null) }
                val direct = mutableListOf<String>()
                remote.command(UUID.randomUUID().toString(), "desktop-1", json("history")) { direct += it.getValue("type").jsonPrimitive.content }
                assertEquals(listOf("Session"), direct)
            } finally { remote.close() }
        }
    }

    @Test fun unauthorizedAndOneShotPostFailureNeverRetry(): Unit = runBlocking {
        Relay().use { relay ->
            val expired = mutableListOf<SyncSession>()
            val remote = RoomCloudRemoteControl(stored(relay.host), { true }, { expired += it })
            try {
                relay.eventStatus.set(401)
                assertFailsWith<CloudSessionExpired> { remote.command(UUID.randomUUID().toString(), "desktop", json("history")) {} }
                assertEquals(listOf(identity), expired)
                assertEquals(0, relay.requests.get())
                relay.eventStatus.set(200)
                relay.postStatus.set(503)
                assertFailsWith<Exception> { remote.command(UUID.randomUUID().toString(), "desktop", json("prompt")) {} }
                assertEquals(1, relay.requests.get())
                assertEquals(listOf(identity), expired)
                relay.postStatus.set(401)
                assertFailsWith<CloudSessionExpired> { remote.command(UUID.randomUUID().toString(), "desktop", json("history")) {} }
                assertEquals(2, relay.requests.get())
                assertEquals(listOf(identity, identity), expired)
            } finally { remote.close() }
        }
    }

    @Test fun oversizedHandshakeAndObserverFailureCloseTheLane(): Unit = runBlocking {
        Relay().use { relay ->
            val remote = RoomCloudRemoteControl(stored(relay.host), { true }, {})
            try {
                relay.oversized.set(true)
                assertFailsWith<Exception> { withTimeout(5_000) { remote.command(UUID.randomUUID().toString(), "desktop", json("history")) {} } }
                assertEquals(0, relay.requests.get())
                relay.oversized.set(false)
                val id = UUID.randomUUID().toString()
                assertFailsWith<IllegalStateException> { withTimeout(5_000) { remote.command(id, "desktop", json("history")) { error("observer failed") } } }
                assertFailsWith<IllegalStateException> { remote.signal(id, null) }
            } finally { remote.close() }
        }
    }

    @Test fun sessionChangeAndInvalidCommandsFailWithoutSending(): Unit = runBlocking {
        Relay().use { relay ->
            val active = AtomicBoolean(true)
            val remote = RoomCloudRemoteControl(stored(relay.host), { active.get() }, {})
            try {
                assertFailsWith<IllegalArgumentException> { remote.command("bad", "desktop", json("history")) {} }
                assertFailsWith<IllegalArgumentException> { remote.command(UUID.randomUUID().toString(), "../bad", json("history")) {} }
                assertFailsWith<IllegalArgumentException> { remote.command(UUID.randomUUID().toString(), "desktop", json("exec")) {} }
                assertEquals(0, relay.requests.get())
                active.set(false)
                assertFailsWith<IllegalStateException> { remote.command(UUID.randomUUID().toString(), "desktop", json("history")) {} }
                assertEquals(0, relay.requests.get())
                assertFalse(relay.cookies.isNotEmpty())
            } finally { remote.close() }
        }
    }

    @Test fun closeBetweenCallRegistrationAndEnqueuePreventsPost(): Unit = runBlocking {
        Relay().use { relay ->
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val remote = RoomCloudRemoteControl(stored(relay.host), { true }, {}, {
                reached.complete(Unit)
                release.await()
            })
            val call = async { runCatching { remote.command(UUID.randomUUID().toString(), "desktop", json("history")) {} } }
            try {
                withTimeout(5_000) { reached.await() }
                remote.close()
                release.complete(Unit)
                assertTrue(withTimeout(5_000) { call.await() }.isFailure)
                assertEquals(0, relay.requests.get())
            } finally { release.complete(Unit); remote.close() }
        }
    }
    @Test fun relayCanStayQuietLongerThanDefaultTenSecondReadTimeout(): Unit = runBlocking {
        Relay().use { relay ->
            relay.responseDelay.set(11_000)
            val remote = RoomCloudRemoteControl(stored(relay.host), { true }, {})
            try {
                val events = mutableListOf<String>()
                withTimeout(18_000) {
                    remote.command(UUID.randomUUID().toString(), "desktop", json("history")) {
                        events += it.getValue("type").jsonPrimitive.content
                    }
                }
                assertEquals(listOf("Session"), events)
                assertEquals(1, relay.requests.get())
            } finally { remote.close() }
        }
    }

}
