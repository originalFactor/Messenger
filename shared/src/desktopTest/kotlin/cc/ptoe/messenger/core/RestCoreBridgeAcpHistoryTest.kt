/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.domain.mcp.McpManager
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RestCoreBridgeAcpHistoryTest {
    @Test fun localHistoryDoesNotWaitForMcpCatalogOrEventFeed(): Unit = runBlocking {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/control/commands/") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            check(body.contains("\"action\":\"history\""))
            calls.incrementAndGet()
            val bytes = "data: {\"type\":\"History\",\"messages\":[],\"before\":null}\n\n".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val preferences = object : DataStore<Preferences> {
            override val data = MutableStateFlow<Preferences>(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val manager = McpManager(AppPreferences(preferences), enabled = false)
        try {
            val bridge = RestCoreBridge("127.0.0.1:${server.address.port}", "token")
            // A manager with no event feed would make ordinary controlCommand wait for publication.
            bridge.javaClass.getDeclaredField("mcpManager").apply { isAccessible = true }.set(bridge, manager)
            val result = withTimeout(3_000) { bridge.localAcpHistory("acp:test", null) }
            assertEquals("History", result.getValue("type").jsonPrimitive.content)
            assertEquals(1, calls.get())
        } finally { manager.shutdown(); server.stop(0) }
    }
}
