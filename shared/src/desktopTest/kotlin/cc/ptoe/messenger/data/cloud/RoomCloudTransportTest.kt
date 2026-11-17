/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoomCloudTransportTest {
    @Test fun sync401ExpiresOnHeadersBeforeBlockedBodyForReadsAndWrites(): Unit = runBlocking {
        for (mutation in listOf(null, SyncMutation.UPSERT, SyncMutation.DELETE)) {
            val requests = AtomicInteger()
            val releaseBody = CountDownLatch(1)
            val executor = Executors.newCachedThreadPool()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { this.executor = executor }
            server.createContext("/") { exchange ->
                requests.incrementAndGet()
                try {
                    exchange.requestBody.readBytes()
                    exchange.sendResponseHeaders(401, 64)
                    exchange.responseBody.flush()
                    releaseBody.await(10, TimeUnit.SECONDS)
                } finally { exchange.close() }
            }
            server.start()
            val client = RoomCloudHttpClient()
            val identity = SyncSession("account", "generation")
            val expired = CompletableDeferred<SyncSession>()
            val store = object : KotlinSyncStore {
                override suspend fun session() = identity
                override suspend fun cursor(session: SyncSession) = 0L
                override suspend fun pending(session: SyncSession) = mutation?.let {
                    listOf(PendingSyncChange(SyncCollection.AGENT, "agent", it, 1, JsonObject(emptyMap())))
                }.orEmpty()
                override suspend fun acknowledgePush(session: SyncSession, change: PendingSyncChange) { error("401 must not acknowledge") }
                override suspend fun commitPull(session: SyncSession, delta: PulledCloudDelta, replaceLocal: Boolean) { error("401 must not commit") }
                override suspend fun expireIfCurrent(session: SyncSession) { expired.complete(session) }
            }
            val base = "http://127.0.0.1:${server.address.port}"
            val engine = KotlinCloudSyncEngine(store, KtorSyncTransport(client.http, base, identity, "messenger_session=cookie"))
            try {
                val call = async(Dispatchers.IO) { runCatching { if (mutation == null) engine.sync() else engine.pushPending() } }
                assertEquals(identity, withTimeout(3_000) { expired.await() }, "401 headers must invalidate before the body arrives")
                assertEquals(1, requests.get())
                releaseBody.countDown()
                assertIs<CloudSessionExpired>(withTimeout(5_000) { call.await() }.exceptionOrNull())
            } finally {
                releaseBody.countDown()
                client.close(); server.stop(0); executor.shutdownNow()
            }
        }
    }

    @Test fun authenticated401ExpiresOnHeadersBeforeBlockedBodyButPassword401DoesNot(): Unit = runBlocking {
        val count = AtomicInteger()
        val releaseBody = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { this.executor = executor }
        server.createContext("/api/auth/me") { exchange ->
            count.incrementAndGet()
            try {
                exchange.sendResponseHeaders(401, 64)
                exchange.responseBody.flush()
                releaseBody.await(10, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.createContext("/api/auth/password") { exchange ->
            count.incrementAndGet()
            exchange.requestBody.readBytes()
            val body = "wrong password".toByteArray()
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val client = RoomCloudHttpClient()
        val expired = CompletableDeferred<SyncSession>()
        val expiryCalls = AtomicInteger()
        val identity = SyncSession("account", "generation")
        val base = "http://127.0.0.1:${server.address.port}"
        val stored = StoredCloudSession(identity, "messenger_session=cookie", base, CloudUser(id = "account"))
        val api = RoomCloudApi(client, base, stored) { expiryCalls.incrementAndGet(); expired.complete(it) }
        try {
            val call = async(Dispatchers.IO) { runCatching { api.refreshUser() } }
            assertEquals(identity, withTimeout(3_000) { expired.await() })
            assertEquals(1, count.get(), "authenticated request must not be retried")
            releaseBody.countDown()
            assertIs<CloudSessionExpired>(withTimeout(5_000) { call.await() }.exceptionOrNull())

            val passwordError = runCatching { api.changePassword("old", "new") }.exceptionOrNull()
            assertTrue(passwordError?.message.orEmpty().contains("wrong password"))
            assertEquals(2, count.get())
            assertEquals(1, expiryCalls.get(), "password rejection must not expire the session")
        } finally {
            releaseBody.countDown()
            client.close(); server.stop(0); executor.shutdownNow()
        }
    }

    @Test fun serverRetryHintsAndTimeoutResponsesDoNotRepeatRequests(): Unit = runBlocking {
        val count = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            count.incrementAndGet()
            if (exchange.requestURI.path == "/unavailable") exchange.responseHeaders.add("Retry-After", "0")
            exchange.sendResponseHeaders(if (exchange.requestURI.path == "/unavailable") 503 else 408, -1)
            exchange.close()
        }
        server.start()
        val client = RoomCloudHttpClient()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals(503, client.http.post("$base/unavailable") { setBody("mutation") }.status.value)
            assertEquals(1, count.get())
            assertEquals(408, client.http.post("$base/timeout") { setBody("mutation") }.status.value)
            assertEquals(2, count.get())
            assertEquals(503, client.http.get("$base/unavailable").status.value)
            assertEquals(3, count.get())
        } finally { client.close(); server.stop(0) }
    }
}
