/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ModelRow
import cc.ptoe.messenger.data.local.room.ProviderRow
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.KotlinChatTurnRunner
import cc.ptoe.messenger.domain.chat.KotlinTurnToolExecutor
import cc.ptoe.messenger.domain.model.Agent
import com.sun.net.httpserver.HttpServer
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinRoomChatIntegrationTest {
    @Test fun roomTurnStreamsFromProviderAndPersistsTheConversation(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-chat-http")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val requests = AtomicInteger()
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            requests.incrementAndGet()
            val payload = exchange.requestBody.bufferedReader().readText()
            assertTrue(payload.contains("hello"))
            val bytes = ("data: {\"choices\":[{\"delta\":{\"content\":\"hello back\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n").toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val rows = db.rows()
            rows.upsertProviders(listOf(ProviderRow("p", "provider", "http://127.0.0.1:${server.address.port}/v1", "test-key", "chat", false, 1, 1)))
            rows.upsertModels(listOf(ModelRow("m", "p", "test-model", "Model", true, 0, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
            val store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false })
            val runner = KotlinChatTurnRunner(store, KotlinTurnToolExecutor { _, _ -> error("Tools are disabled") }, scope)
            val delegate = CoreBridge::class.java.cast(Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, _, _ -> error("Unexpected delegate call") })
            val bridge = KotlinBackgroundTurnBridge(delegate, store, runner, scope)
            val config = TurnConfigBridge("c", "test-model", "", "", "chat", "", null, null, null, null,
                emptyList(), false, 0, "", null, null, null, false, "", operation = "send", content = "hello", disableTools = true)
            bridge.startBackgroundTurn(config)
            val events = mutableListOf<String>()
            withTimeout(10_000) { bridge.observeBackgroundTurn("c") { events += it } }
            assertTrue(events.any { Json.parseToJsonElement(it).jsonObject["type"]?.jsonPrimitive?.content == "Finished" })
            withTimeout(10_000) { runner.awaitStopped(checkNotNull(store.latestTurn("c"))) }
            assertFalse(bridge.backgroundTurnActive("c"))
            val messages = rows.messagesFor(listOf("c"))
            assertEquals(listOf("user", "assistant"), messages.map { it.role })
            assertEquals("hello back", messages.last().content)
            assertEquals("hello back", rows.conversation("c")?.lastMessage)
            assertEquals(1, requests.get())
        } finally { scope.cancel(); db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively() }
    }
}
