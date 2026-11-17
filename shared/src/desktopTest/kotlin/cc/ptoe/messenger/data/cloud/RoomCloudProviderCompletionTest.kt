/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.domain.chat.KotlinCloudTurnIdentity
import cc.ptoe.messenger.domain.chat.KotlinTurnPhase
import cc.ptoe.messenger.domain.chat.KotlinTurnSnapshot
import cc.ptoe.messenger.domain.model.Provider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class RoomCloudProviderCompletionTest {
    @Test fun completionUsesCurrentSessionWithoutStreamingRetryOrCredentialLeak() = runBlocking {
        val dir = Files.createTempDirectory("cloud-completion")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val calls = AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            val number = calls.incrementAndGet()
            val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            val nonStreaming = body["stream"]?.jsonPrimitive?.content == "false"
            val (status, text) = when {
                !nonStreaming -> 400 to "stream must be false"
                number == 1 -> 200 to """{"choices":[{"message":{"role":"assistant","content":"summary"},"finish_reason":"stop"}]}"""
                else -> { exchange.responseHeaders.add("Retry-After", "0"); 503 to """{"message":"failed key-7ab9 cookie-45cd"}""" }
            }
            val bytes = text.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val host = "http://127.0.0.1:${server.address.port}"
        val dao = db.cloudSync()
        val gate = RoomCloudProviderGate.forDao(dao)
        try {
            val session = SyncSession("account", "generation")
            val user = CloudUser(id = "account", aiApiKey = "key-7ab9")
            dao.saveCredentials(StoredCloudSession(session, "messenger_session=cookie-45cd", host, user))
            dao.updateCloudUser(session, user, host)
            val round = KotlinTurnSnapshot("turn", "conversation", KotlinTurnPhase.SUMMARY_REQUESTING,
                Provider(BUILTIN_PROVIDER_ID, "Cloud", "$host/v1", "key-7ab9", createdAt = 1, updatedAt = 1),
                ChatCompletionRequestDto("model", emptyList()), cloudIdentity = KotlinCloudTurnIdentity(session.accountId, session.generation))
            val events = withTimeout(5000) { gate.complete(round).toList() }
            assertEquals(ChatStreamEvent.Content("summary"), events.first())
            assertIs<ChatStreamEvent.Done>(events.last())
            val failure = assertFailsWith<IllegalStateException> { withTimeout(5000) { gate.complete(round).toList() } }.message.orEmpty()
            assertTrue(failure.contains("HTTP 503"))
            assertFalse(failure.contains("key-7ab9") || failure.contains("cookie-45cd"))
            assertEquals(2, calls.get())
            assertTrue(gate.revoke(session))
            assertFailsWith<IllegalStateException> { withTimeout(5000) { gate.complete(round).toList() } }
            assertEquals(2, calls.get(), "a revoked session must not make another request")
        } finally { gate.shutdown(); db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively() }
    }
}
