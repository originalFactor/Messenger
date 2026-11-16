/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.domain.model.ApiFormat
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpHandler
import java.net.InetSocketAddress
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class KotlinModelDiscoveryTest {
    @Test fun slowResponsesTimeOutAndCatalogFallsBackToStaleCache() = runBlocking {
        val dir = Files.createTempDirectory("model-discovery-timeout")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val stop = CountDownLatch(1)
        val started = Channel<Unit>(Channel.UNLIMITED)
        val requests = AtomicInteger()
        server.executor = executor
        val drip = HttpHandler { exchange ->
            requests.incrementAndGet()
            started.trySend(Unit)
            try {
                exchange.sendResponseHeaders(200, 0)
                while (!stop.await(50, TimeUnit.MILLISECONDS)) {
                    exchange.responseBody.write(' '.code)
                    exchange.responseBody.flush()
                }
            } catch (_: java.io.IOException) { } finally { exchange.close() }
        }
        server.createContext("/v1/models", drip)
        server.createContext("/catalog", drip)
        server.start()
        val host = "http://127.0.0.1:${server.address.port}"
        val discovery = KotlinModelDiscovery(db, "$host/catalog", now = { 100_000_000L }, requestTimeoutMillis = 400)
        val cancellable = KotlinModelDiscovery(db, "$host/catalog", requestTimeoutMillis = 5_000)
        try {
            db.rows().upsertValues(listOf(KeyValueRow("models_dev_body", """{"model":{"contextWindow":1234}}"""),
                KeyValueRow("models_dev_fetched_at", "1")))
            val error = assertFailsWith<IllegalStateException> {
                withTimeout(5_000) { discovery.discover("$host/v1", "key", ApiFormat.CHAT) }
            }
            assertTrue(error.message.orEmpty().contains("timed out"))
            assertEquals(1234L, withTimeout(5_000) { discovery.metadata("model") }?.contextWindow)
            assertEquals(2, requests.get())
            val job = async { cancellable.discover("$host/v1", "key", ApiFormat.CHAT) }
            withTimeout(5_000) { repeat(3) { started.receive() }; job.cancelAndJoin() }
            assertTrue(job.isCancelled, "caller cancellation must propagate")
        } finally {
            stop.countDown(); cancellable.close(); discovery.close(); db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun modelDiscoveryUsesProviderExtensionsAndCachesPublicCatalogWithoutCredentials() = runBlocking {
        val dir = Files.createTempDirectory("model-discovery")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val providerCalls = AtomicInteger()
        val catalogCalls = AtomicInteger()
        val mode = AtomicReference("ok")
        val catalogMode = AtomicReference("ok")
        val clock = AtomicLong(1000)
        server.createContext("/v1/models") { exchange ->
            providerCalls.incrementAndGet()
            assertEquals("Bearer private-key", exchange.requestHeaders.getFirst("Authorization"))
            val body = if (mode.get() == "fail") """{"error":{"message":"invalid private-key"}}""" else """
                {"data":[{"id":"model-one","display_name":"Preferred","displayName":"Ignored","context_window":8000,"input_rate":0.0,"metadata":{"inputModalities":["text","image"],"reasoning":true}}],
                 "models":[{"name":"models/gemini-test"}]}
            """.trimIndent()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (mode.get() == "fail") 429 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/catalog") { exchange ->
            catalogCalls.incrementAndGet()
            assertEquals(null, exchange.requestHeaders.getFirst("Authorization"))
            val bytes = """{"vendor/model-one":{"limit":{"context":100000},"cost":{"cache_read":0.0},"modalities":{"input":["audio","text"],"output":["text"]},"tool_call":true,"reasoning":true},"gemini-test":{"contextWindow":2000,"vision":"supported","functionCall":true}}""".toByteArray()
            exchange.sendResponseHeaders(if (catalogMode.get() == "ok") 200 else 503, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val host = "http://127.0.0.1:${server.address.port}"
        val discovery = KotlinModelDiscovery(db, "$host/catalog", now = clock::get)
        try {
            val delegate = CoreBridge::class.java.cast(Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, _, _ -> error("Unexpected delegate call") })
            val bridge = KotlinModelDiscoveryBridge(delegate, discovery) { force -> if (force) 7L else 0L }
            assertEquals(7L, bridge.cloudSyncBuiltinModels(true))
            val models = discovery.discover("$host/v1", "private-key", ApiFormat.CHAT)
            assertEquals(listOf("model-one", "gemini-test"), models.map { it.modelId })
            assertEquals("Preferred", models[0].displayName)
            assertEquals(8000L, models[0].contextWindow)
            assertEquals(0.0, models[0].inputRate)
            assertEquals(0.0, models[0].cacheReadRate)
            assertEquals("audio,text", models[0].inputModalities)
            assertTrue(models[0].supportsThinking && models[0].supportsToolCalling)
            assertEquals(2000L, models[1].contextWindow)
            assertEquals("image", models[1].inputModalities)
            assertNotNull(discovery.metadata("model-one"))
            assertEquals(1, catalogCalls.get())
            clock.set(86_401_001)
            catalogMode.set("fail")
            assertNotNull(discovery.metadata("model-one"), "stale metadata is retained when the catalog is unavailable")
            assertEquals(2, catalogCalls.get())
            mode.set("fail")
            val failure = assertFailsWith<IllegalStateException> { discovery.discover("$host/v1", "private-key", ApiFormat.CHAT) }
            assertTrue(failure.message.orEmpty().contains("HTTP 429"))
            assertFalse(failure.message.orEmpty().contains("private-key"))
            assertEquals(2, providerCalls.get(), "provider errors must not be retried")
            assertEquals(2, catalogCalls.get())
        } finally {
            discovery.close(); db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun malformedCatalogAndUnknownModelAreHandled() {
        assertEquals(null, KotlinModelDiscoveryCodec.catalog("not json"))
        val catalog = checkNotNull(KotlinModelDiscoveryCodec.catalog("""{"provider/model":{"input":{"types":["text","audio"]},"output":[{"type":"image"}],"structured_output":true}}"""))
        val model = KotlinModelDiscoveryCodec.enrich(KotlinModelDiscoveryCodec.providerModels("""{"data":[{"id":"model"}]}""").single(), catalog)
        assertEquals("audio,text", model.inputModalities)
        assertEquals("image", model.outputModalities)
        assertTrue(model.supportsJsonOutput)
        assertEquals(null, KotlinModelDiscoveryCodec.lookup("missing", catalog))
    }
}
