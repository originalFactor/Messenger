/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomShutdownState
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.Provider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinRoomSkillsIntegrationTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test fun skillTurnsUseLiveRoomPolicyAndShutdownClosesAdmission(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-skills-integration")
        val assets = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val requests = AtomicInteger()
        val payloads = ConcurrentLinkedQueue<String>()
        val revokeEntered = CompletableDeferred<Unit>()
        val allowRevokedCall = CountDownLatch(1)
        val shutdownEntered = CompletableDeferred<Unit>()
        val allowShutdownResponse = CountDownLatch(1)
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            try {
                payloads.add(exchange.requestBody.bufferedReader().readText())
                val number = requests.incrementAndGet()
                if (number == 3) { revokeEntered.complete(Unit); allowRevokedCall.await(10, TimeUnit.SECONDS) }
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                if (number == 5) {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write("data: {\"choices\":[{\"delta\":{\"content\":\"pending\"}}]}\n\n".toByteArray())
                    exchange.responseBody.flush()
                    shutdownEntered.complete(Unit)
                    allowShutdownResponse.await(10, TimeUnit.SECONDS)
                } else {
                    val response = if (number == 1 || number == 3) {
                        val arguments = Json.encodeToString("""{"name":"study"}""")
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"skill-call-$number\",\"type\":\"function\",\"function\":{\"name\":\"messenger_skills\",\"arguments\":$arguments}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                    } else "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\n"
                    val bytes = (response + "data: [DONE]\n\n").toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            } catch (_: Exception) { } finally { exchange.close() }
        }
        server.start()
        val lifecycle = RoomManagedShutdown { }
        val prefs = MemoryPreferences()
        val enabled = """{"files":{"SKILL.md":"---\nname: study\ndescription: Study guidance\n---\nPRIVATE SKILL BODY"},"enabled":true}"""
        val disabled = enabled.replace("\"enabled\":true", "\"enabled\":false")
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = "m",
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
            lifecycle.enter()
            val base = "http://127.0.0.1:${server.address.port}"
            val backend = KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs), assets,
                dir.resolve("avatars"), dir.resolve("chat-images"), base, "$base/models.json")
            backend.providers.insert(Provider("p", "Provider", "$base/v1", "key", createdAt = 1, updatedAt = 1))
            backend.models.insert(ChatModel("m", "p", "model", "Model", true, supportsToolCalling = true, createdAt = 1))
            for (id in listOf("read", "revoke", "stop")) {
                backend.conversations.insert(Conversation(id, "Chat", "p", "a", createdAt = 1, updatedAt = 1, lastMessage = null))
            }
            backend.bridge.saveSkill("study", enabled)
            assertTrue(backend.bridge.listSkills().contains("Study guidance"))
            assertTrue(backend.bridge.getSkill("study").contains("PRIVATE SKILL BODY"))
            assertFailsWith<IllegalStateException> { backend.bridge.discoverRemoteSkills("{}") }
            assertFailsWith<IllegalStateException> { backend.bridge.previewRemoteSkill("{}") }
            fun config(id: String) = TurnConfigBridge(id, "model", "", "", "chat", "", null, null, null, null,
                emptyList(), false, 0, "", null, null, null, false, "", operation = "send", content = "help",
                disableTools = false, supportsToolApproval = true)

            backend.bridge.startBackgroundTurn(config("read"))
            val readEvents = ConcurrentLinkedQueue<String>()
            withTimeout(10_000) { backend.bridge.observeBackgroundTurn("read") { readEvents.add(it) } }
            assertEquals(2, requests.get())
            assertTrue(payloads.first().contains("Study guidance"))
            assertTrue(payloads.first().contains("messenger_skills"))
            assertFalse(payloads.first().contains("PRIVATE SKILL BODY"))
            assertTrue(payloads.last().contains("PRIVATE SKILL BODY"))
            assertTrue(db.rows().messagesFor(listOf("read")).any { it.role == "tool" && it.content.contains("PRIVATE SKILL BODY") })
            assertFalse(readEvents.any { it.contains("ToolApprovalRequested") })

            backend.bridge.startBackgroundTurn(config("revoke"))
            val observer = async { backend.bridge.observeBackgroundTurn("revoke") { } }
            withTimeout(10_000) { revokeEntered.await() }
            backend.bridge.saveSkill("study", disabled)
            allowRevokedCall.countDown()
            withTimeout(10_000) { observer.await() }
            assertEquals(4, requests.get())
            assertTrue(payloads.last().contains("Skill is disabled"))
            assertFalse(payloads.last().contains("PRIVATE SKILL BODY"))
            assertFalse(db.rows().messagesFor(listOf("revoke")).any { it.content.contains("PRIVATE SKILL BODY") })

            backend.bridge.saveSkill("study", enabled)
            backend.bridge.startBackgroundTurn(config("stop"))
            withTimeout(10_000) { shutdownEntered.await() }
            withTimeout(10_000) { lifecycle.stopAndAwait() }
            assertEquals(5, requests.get())
            assertFailsWith<IllegalStateException> { backend.bridge.listSkills() }
            assertFailsWith<IllegalStateException> { backend.bridge.getSkill("study") }
            assertFailsWith<IllegalStateException> { backend.bridge.saveSkill("study", enabled) }
            assertFailsWith<IllegalStateException> { backend.bridge.startBackgroundTurn(config("stop")) }
        } finally {
            allowRevokedCall.countDown(); allowShutdownResponse.countDown()
            if (lifecycle.state.value == RoomShutdownState.Running) lifecycle.stopAndAwait()
            db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively()
        }
    }
}
