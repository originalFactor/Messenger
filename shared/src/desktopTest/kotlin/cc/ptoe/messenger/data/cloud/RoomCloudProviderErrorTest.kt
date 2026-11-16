/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.domain.chat.KotlinCloudTurnIdentity
import cc.ptoe.messenger.domain.chat.KotlinTurnPhase
import cc.ptoe.messenger.domain.chat.KotlinTurnSnapshot
import cc.ptoe.messenger.domain.model.Provider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlin.test.*

class RoomCloudProviderErrorTest {
    @Test fun errorDetailsAreBoundedRedactedAndCancellableWithoutRetry() = runBlocking {
        val dir = Files.createTempDirectory("room-cloud-error-http")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val mode = AtomicReference("unauthorized")
        val requests = AtomicInteger()
        val slowStarted = Channel<Unit>(Channel.UNLIMITED)
        val release = CountDownLatch(1)
        server.createContext("/v1/chat/completions") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.readBytes()
            try {
                when (mode.get()) {
                    "stall" -> {
                        exchange.sendResponseHeaders(503, 0)
                        exchange.responseBody.write("waiting".toByteArray())
                        exchange.responseBody.flush()
                        slowStarted.trySend(Unit)
                        release.await(15, TimeUnit.SECONDS)
                        exchange.close()
                    }
                    else -> {
                        val (status, text) = when (mode.get()) {
                            "unauthorized" -> 401 to """{"message":"Model key key-7ab9 rejected","detail":"cookie messenger_session=cookie-45cd; Bearer foreign-credential; {\"api_key\":\"another-secret\"}"}"""
                            "quota" -> 429 to """{"error":{"message":"Quota exceeded","detail":"Try again tomorrow"}}"""
                            "plain" -> 500 to "Service failed: api_key=other-secret"
                            "large" -> 502 to ("SENSITIVE-PREFIX " + "x".repeat(100_000))
                            "deep" -> 503 to ("[".repeat(64) + "0" + "]".repeat(64))
                            "empty" -> 404 to ""
                            else -> error("unexpected test mode")
                        }
                        val body = text.toByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(status, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                }
            } catch (_: java.io.IOException) { exchange.close() }
        }
        server.start()
        val host = "http://127.0.0.1:${server.address.port}"
        val dao = db.cloudSync()
        val gate = RoomCloudProviderGate.forDao(dao)
        val client = RoomCloudHttpClient()
        try {
            val session = SyncSession("account", "generation")
            val user = CloudUser(id = "account", aiApiKey = "key-7ab9")
            dao.saveCredentials(StoredCloudSession(session, "messenger_session=cookie-45cd", host, user))
            dao.updateCloudUser(session, user, host)
            val round = KotlinTurnSnapshot("turn", "conversation", KotlinTurnPhase.REQUESTING_PROVIDER,
                Provider(BUILTIN_PROVIDER_ID, "Cloud", "$host/v1", "key-7ab9", createdAt = 1, updatedAt = 1),
                ChatCompletionRequestDto("model", emptyList()), cloudIdentity = KotlinCloudTurnIdentity(session.accountId, session.generation))
            suspend fun failed(): String = assertFailsWith<IllegalStateException> {
                withTimeout(5000) { gate.stream(round).toList() }
            }.message.orEmpty()

            val unauthorized = failed()
            assertTrue(unauthorized.contains("HTTP 401"))
            assertTrue(unauthorized.contains("Model key") && unauthorized.contains("rejected"))
            assertTrue(unauthorized.contains("cookie") && unauthorized.contains("Bearer"))
            listOf("key-7ab9", "cookie-45cd", "foreign-credential", "another-secret").forEach { assertFalse(unauthorized.contains(it)) }
            assertEquals(session, dao.currentSession(), "AI-key 401 must not expire the cloud session")
            assertEquals(1, requests.get())

            mode.set("quota")
            val quota = failed()
            assertTrue(quota.contains("HTTP 429") && quota.contains("Quota exceeded") && quota.contains("Try again tomorrow"))
            mode.set("plain")
            val plain = failed()
            assertTrue(plain.contains("HTTP 500") && plain.contains("Service failed"))
            assertFalse(plain.contains("other-secret"))
            mode.set("large")
            val large = failed()
            assertTrue(large.contains("HTTP 502") && large.contains("size limit"))
            assertFalse(large.contains("SENSITIVE-PREFIX"))
            mode.set("deep")
            assertEquals("HTTP 503", failed())
            mode.set("empty")
            assertEquals("HTTP 404", failed())
            assertEquals(6, requests.get(), "non-successful requests must not be retried")

            mode.set("stall")
            val cancelled = launch(Dispatchers.Default) { gate.stream(round).toList() }
            withTimeout(5000) { slowStarted.receive(); cancelled.cancelAndJoin() }
            assertEquals(session, dao.currentSession())
            val pending = async(Dispatchers.Default) { runCatching { gate.stream(round).toList() } }
            withTimeout(5000) { slowStarted.receive() }
            val auth = RoomCloudAuthentication(dao, client)
            withTimeout(5000) { auth.expireIfCurrent(session) }
            assertNull(dao.currentSession())
            assertIs<CloudSessionExpired>(withTimeout(5000) { pending.await() }.exceptionOrNull())
            assertEquals(8, requests.get())
        } finally {
            release.countDown()
            gate.shutdown(); client.close(); db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively()
        }
    }
}
