/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinRoomBridgeCompositionTest {
    @Test fun usesOneOwnerAndDrainsDiscoveryAndObserversBeforeItCanClose(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-bridge-composition")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val release = CountDownLatch(1)
        val entered = Channel<Unit>(Channel.UNLIMITED)
        server.executor = executor
        server.createContext("/catalog") { exchange ->
            val bytes = """{"model":{"contextWindow":1234}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/models") { exchange ->
            entered.trySend(Unit)
            try {
                exchange.sendResponseHeaders(200, 0)
                while (!release.await(50, TimeUnit.MILLISECONDS)) {
                    exchange.responseBody.write(' '.code)
                    exchange.responseBody.flush()
                }
            } catch (_: java.io.IOException) { } finally { exchange.close() }
        }
        server.start()
        val host = "http://127.0.0.1:${server.address.port}"
        val lifecycle = RoomManagedShutdown { }
        val events = Channel<String>(Channel.UNLIMITED)
        try {
            lifecycle.enter()
            val composition = KotlinRoomBridgeComposition.attach(db, lifecycle, "$host/catalog", 10_000)
            val bridge = composition.bridge
            bridge.subscribe { kind, _ -> events.trySend(kind) }
            assertEquals("Other", withTimeout(5_000) { events.receive() })
            bridge.setAgentDelegationEnabled(true)
            assertEquals("Other", withTimeout(5_000) { events.receive() })
            assertTrue(bridge.agentDelegationEnabled())
            assertTrue(bridge.learningRequest("GET", "/tools/core").contains("messenger_learning"))
            assertNotNull(bridge.modelsDevMetadataJson("model"))
            assertNotNull(db.rows().getValue("models_dev_body"))
            assertFailsWith<IllegalStateException> { bridge.cloudSyncBuiltinModels(true) }

            val request = async { bridge.discoverProviderModelsJson("$host/v1", "key", "chat") }
            withTimeout(5_000) { entered.receive() }
            withTimeout(5_000) { lifecycle.stopAndAwait() }
            request.join()
            assertTrue(request.isCancelled)
            assertFailsWith<IllegalStateException> { bridge.modelsDevMetadataJson("model") }
            assertFailsWith<IllegalStateException> { bridge.subscribe { _, _ -> } }
            assertEquals("true", db.rows().getValue("mcp_agent_delegation_enabled")?.value)
            db.rows().upsertValues(listOf(cc.ptoe.messenger.data.local.room.KeyValueRow("learning_tools_enabled", "true")))
            assertNull(withTimeoutOrNull(200) { events.receive() })
        } finally {
            release.countDown()
            db.close()
            server.stop(0)
            executor.shutdownNow()
            dir.toFile().deleteRecursively()
        }
    }
}
