/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridgeRegistry
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.chat.KotlinProviderRequestPreparer
import cc.ptoe.messenger.data.chat.KotlinTurnRequestPreparer
import cc.ptoe.messenger.data.chat.KotlinTurnToolPolicy
import cc.ptoe.messenger.data.chat.PreviousChatWorkersStopped
import cc.ptoe.messenger.data.chat.RoomKotlinTurnStore
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.local.room.AgentRow
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.MessageRow
import cc.ptoe.messenger.data.local.room.ModelRow
import cc.ptoe.messenger.data.local.room.ProviderRow
import cc.ptoe.messenger.data.local.schedule.ScheduledRecoveryBarrier
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomShutdownState
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.di.AppContainer
import cc.ptoe.messenger.di.AppDirs
import cc.ptoe.messenger.domain.chat.KotlinRuntimeToolGrant
import cc.ptoe.messenger.domain.chat.KotlinTurnAdmission
import cc.ptoe.messenger.domain.chat.KotlinTurnCommand
import cc.ptoe.messenger.domain.chat.KotlinTurnPhase
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import com.sun.net.httpserver.HttpServer
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinRoomChatRestartTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private fun open(path: Path) = Room.databaseBuilder<LocalDatabase>(name = path.toString())
        .setDriver(BundledSQLiteDriver()).build()

    private fun toolPreparer(conversationId: String) = object : KotlinTurnRequestPreparer {
        override fun prepare(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow, model: ModelRow,
            provider: ProviderRow, history: List<MessageRow>) = ChatCompletionRequestDto(model.modelId, emptyList(),
                tools = listOf(ToolSpecDto("function", ToolSpecFunctionDto("write", "write", buildJsonObject {}))))
        override fun toolGrant() = KotlinRuntimeToolGrant(conversationId, null, "/workspace", null, "old-host", listOf("write"))
    }

    @Test fun scheduledAndDelegatedTurnsBlockStartupUntilTheirOwnerReconciles(): Unit = runBlocking {
        for (delegated in listOf(false, true)) for (configureScheduler in listOf(false, true)) {
            val dir = Files.createTempDirectory("room-owned-chat-restart")
            try {
                if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) continue
                val assets = Files.createDirectory(dir.resolve("assets"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                val path = dir.resolve("store.db")
                val old = open(path)
                val ids: Pair<String, String>
                try {
                    old.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "http://127.0.0.1:9/v1", "key", "chat", false, 1, 1)))
                    old.rows().upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null,
                        "text", "text", false, false, false, false, 1, null, null)))
                    RoomAgentRepository(old).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "", defaultModelId = "m",
                        temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
                    old.rows().upsertConversations(listOf("a-normal", "z-owned").map { id ->
                        ConversationRow(id, "Chat", "p", "a", null, null, null, null, null, null, null, null, false,
                            1, 1, null, null, null, 0, 0, 0)
                    })
                    val store = RoomKotlinTurnStore(old, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false })
                    val normal = assertIs<KotlinTurnAdmission.Accepted>(store.admit(
                        KotlinTurnCommand.Send("a-normal", "normal", null, disableTools = true))).turnId
                    val owned = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send(
                        "z-owned", "owned", null, disableTools = true, scheduledRun = !delegated, delegatedRun = delegated))).turnId
                    assertTrue(store.claim(owned, false))
                    store.beginProvider(owned)
                    ids = normal to owned
                } finally { old.close() }

                val db = open(path)
                val shutdown = RoomManagedShutdown { }
                try {
                    val beforeNormal = db.rows().getValue("kotlin_turn_v1:${ids.first}")
                    val beforeOwned = db.rows().getValue("kotlin_turn_v1:${ids.second}")
                    val beforeMessages = db.rows().messagesFor(listOf("a-normal", "z-owned"))
                    val schedulerProofs = AtomicInteger()
                    shutdown.enter()
                    val prefs = MemoryPreferences()
                    val failure = assertFailsWith<IllegalStateException> {
                        KotlinRoomBackendSlice.attach(db, shutdown, AppPreferences(prefs), ThemePreferences(prefs),
                            assets, dir.resolve("avatars"), dir.resolve("images"),
                            schedulerRecovery = if (configureScheduler) ScheduledRecoveryBarrier { schedulerProofs.incrementAndGet() } else null,
                            previousChatWorkersStopped = PreviousChatWorkersStopped { })
                    }
                    assertTrue(failure.message.orEmpty().contains("owning service"))
                    assertEquals(0, schedulerProofs.get(), "a supplied scheduler callback is not completed reconciliation")
                    assertEquals(beforeNormal, db.rows().getValue("kotlin_turn_v1:${ids.first}"))
                    assertEquals(beforeOwned, db.rows().getValue("kotlin_turn_v1:${ids.second}"))
                    assertEquals(beforeMessages, db.rows().messagesFor(listOf("a-normal", "z-owned")))
                    assertEquals(ids.first, db.rows().getValue("kotlin_turn_active_v1:a-normal")?.value)
                    assertEquals(ids.second, db.rows().getValue("kotlin_turn_active_v1:z-owned")?.value)
                    assertTrue(db.rows().observeTurnErrorKeys().first().isEmpty())
                } finally {
                    if (shutdown.state.value == RoomShutdownState.Running) shutdown.stopAndAwait()
                    db.close()
                }
                val reopened = open(path)
                try {
                    assertEquals(ids.first, reopened.rows().getValue("kotlin_turn_active_v1:a-normal")?.value)
                    assertEquals(ids.second, reopened.rows().getValue("kotlin_turn_active_v1:z-owned")?.value)
                } finally { reopened.close() }
            } finally { dir.toFile().deleteRecursively() }
        }
    }

    @Test fun interruptedChatIsVisibleAndManuallyRetryableWithoutReplayingTools(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-chat-restart")
        val assets = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val dbPath = dir.resolve("room.db")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        val payloads = ConcurrentLinkedQueue<String>()
        server.createContext("/v1/chat/completions") { exchange ->
            requests.incrementAndGet()
            payloads.add(exchange.requestBody.bufferedReader().readText())
            val result = ("data: {\"choices\":[{\"delta\":{\"content\":\"retry answer\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n").toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, result.size.toLong())
            exchange.responseBody.use { it.write(result) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val old = open(dbPath)
        val ids = mutableMapOf<String, String>()
        var seeded = false
        try {
            old.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "$base/v1", "key", "chat", false, 1, 1)))
            old.rows().upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null,
                "text", "text", false, false, false, false, 1, null, null)))
            val agents = RoomAgentRepository(old)
            agents.ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = "m", temperature = 0.7f,
                topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            agents.insert(Agent.builtinTitleAgent(1).copy(defaultModelId = "m"))
            agents.insert(Agent("no-model", "No model", systemPrompt = "", defaultModelId = null, temperature = 0.7f,
                topP = 1f, maxTokens = null, isDefault = false, createdAt = 1, updatedAt = 1))
            val conversations = listOf("ready", "request", "approval", "tool", "title", "no-model")
            old.rows().upsertConversations(conversations.map { id ->
                ConversationRow(id, if (id == "title") "New Chat" else "Chat", "p", if (id == "no-model") "no-model" else "a",
                    null, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)
            })
            val plain = RoomKotlinTurnStore(old, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false })
            for (id in listOf("ready", "request", "title")) {
                val turn = assertIs<KotlinTurnAdmission.Accepted>(plain.admit(KotlinTurnCommand.Send(id, "hello", null, disableTools = true))).turnId
                ids[id] = turn
                if (id != "ready") { assertTrue(plain.claim(turn, false)); plain.beginProvider(turn) }
                if (id == "title") {
                    plain.appendContent(turn, "completed answer")
                    plain.completeProvider(turn, emptyList(), null)
                    assertEquals(KotlinTurnPhase.TITLE_PENDING, plain.snapshot(turn).phase)
                    assertNotNull(plain.beginTitle(turn))
                }
            }
            for (id in listOf("approval", "tool")) {
                val store = RoomKotlinTurnStore(old, toolPreparer(id), KotlinTurnToolPolicy { _, _, _ -> true })
                val turn = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send(id, "hello", null))).turnId
                ids[id] = turn
                assertTrue(store.claim(turn, false)); store.beginProvider(turn)
                val first = ToolCallData("call-$id-first", "write", "{}")
                val second = ToolCallData("call-$id-second", "write", "{}")
                store.completeProvider(turn, listOf(first, second), null)
                assertEquals(KotlinTurnPhase.WAITING_APPROVAL, store.snapshot(turn).phase)
                store.decideApproval(turn, first.callId, true)
                assertEquals(first, store.beginTool(turn))
                store.claimToolInvocation(turn, first)
                store.completeTool(turn, first, ToolExecutionResult("first tool output"))
                assertEquals(KotlinTurnPhase.WAITING_APPROVAL, store.snapshot(turn).phase)
                if (id == "tool") {
                    store.decideApproval(turn, second.callId, true)
                    assertEquals(second, store.beginTool(turn))
                    store.claimToolInvocation(turn, second)
                }
            }
            seeded = true
        } finally {
            try { old.close() } finally { if (!seeded) { server.stop(0); dir.toFile().deleteRecursively() } }
        }

        val db = open(dbPath)
        val preferences = MemoryPreferences()
        val lifecycle = RoomManagedShutdown { }
        val previousBridge = CoreBridgeRegistry.bridge
        var container: AppContainer? = null
        try {
            lifecycle.enter()
            suspend fun attach(proof: PreviousChatWorkersStopped?) = KotlinRoomBackendSlice.attach(db, lifecycle,
                AppPreferences(preferences), ThemePreferences(preferences), assets, dir.resolve("avatars"), dir.resolve("images"),
                base, "$base/models.json", previousChatWorkersStopped = proof)
            assertFailsWith<IllegalStateException> { attach(null) }
            assertFailsWith<IllegalStateException> { attach(PreviousChatWorkersStopped { error("Previous worker may still run") }) }
            assertEquals(KotlinTurnPhase.REQUESTING_PROVIDER,
                RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false }).snapshot(ids.getValue("request")).phase)
            assertEquals(0, requests.get())

            // A corrupt later key rolls back all earlier reconciled turns.
            db.rows().upsertValues(listOf(KeyValueRow("kotlin_turn_active_v1:zz-invalid", "missing-turn")))
            assertFailsWith<IllegalStateException> { attach(PreviousChatWorkersStopped { }) }
            assertEquals(KotlinTurnPhase.READY_PROVIDER,
                RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false }).snapshot(ids.getValue("ready")).phase)
            db.rows().deleteValue("kotlin_turn_active_v1:zz-invalid")

            val proofs = AtomicInteger()
            val slice = attach(PreviousChatWorkersStopped { proofs.incrementAndGet() })
            assertEquals(1, proofs.get())
            val backend = slice.asAppBackend()
            CoreBridgeRegistry.bridge = backend.coreBridge
            val images = ChatImageStore::class.java.cast(Proxy.newProxyInstance(ChatImageStore::class.java.classLoader,
                arrayOf(ChatImageStore::class.java)) { _, _, _ -> null })
            val app = AppContainer(AppDirs(dir.toString().toPath(), dir.resolve("cache").toString().toPath()), images, backend)
            container = app
            val noticeStore = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false })
            for (id in listOf("ready", "request", "approval", "tool")) {
                val assistant = app.messageRepository.getByConversationId(id).first().last { it.role == MessageRole.ASSISTANT }
                assertEquals(MessageStatus.ERROR, assistant.status)
                assertTrue(assistant.errorMessage.orEmpty().contains("interrupted"))
                assertNotNull(noticeStore.observeFailure(ids.getValue(id)).first())
            }
            val titleAnswer = app.messageRepository.getByConversationId("title").first().last { it.role == MessageRole.ASSISTANT }
            assertEquals("completed answer", titleAnswer.content)
            assertEquals(MessageStatus.SENT, titleAnswer.status)
            assertNull(db.rows().getValue("kotlin_turn_active_v1:title"))
            assertNotNull(noticeStore.observeFailure(ids.getValue("title")).first())
            val titleEvents = mutableListOf<String>()
            withTimeout(10_000) { app.coreBridge.observeBackgroundTurn("title") { titleEvents.add(it) } }
            assertTrue(titleEvents.any { it.contains("\"type\":\"TitleFailed\"") && it.contains("\"durableNotice\":true") })
            assertEquals(KotlinTurnPhase.INTERRUPTED_TOOL,
                RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false }).snapshot(ids.getValue("tool")).phase)
            assertNotNull(db.rows().getValue("kotlin_turn_active_v1:tool"))
            assertEquals(0, requests.get(), "startup must not resend a provider request")

            fun config(conversation: String, operation: String, messageId: String? = null) = TurnConfigBridge(
                conversation, "model", "", "", "chat", "", null, null, null, null, emptyList(), false, 0, "",
                null, null, null, false, "", operation = operation, content = "retry", messageId = messageId, disableTools = true)
            val blocked = app.messageRepository.getByConversationId("tool").first().last { it.role == MessageRole.ASSISTANT }
            assertFailsWith<IllegalStateException> { app.coreBridge.startBackgroundTurn(config("tool", "retry", blocked.id)) }
            assertFailsWith<IllegalStateException> { app.coreBridge.startBackgroundTurn(config("no-model", "send")) }
            assertTrue(app.messageRepository.getByConversationId("no-model").first().isEmpty())
            val retry = app.messageRepository.getByConversationId("request").first().last { it.role == MessageRole.ASSISTANT }
            app.coreBridge.startBackgroundTurn(config("request", "retry", retry.id))
            withTimeout(10_000) { app.coreBridge.observeBackgroundTurn("request") { } }
            assertEquals(1, requests.get())
            assertTrue(app.messageRepository.getByConversationId("request").first().any { it.content == "retry answer" })
            val approvalMessages = app.messageRepository.getByConversationId("approval").first()
            assertTrue(approvalMessages.any { it.role == MessageRole.TOOL && it.content == "first tool output" })
            val approvalFailure = approvalMessages.last { it.role == MessageRole.ASSISTANT && it.status == MessageStatus.ERROR }
            app.coreBridge.startBackgroundTurn(config("approval", "retry", approvalFailure.id))
            withTimeout(10_000) { app.coreBridge.observeBackgroundTurn("approval") { } }
            val messagePayload = Json.parseToJsonElement(payloads.last()).jsonObject.getValue("messages").jsonArray
            assertTrue(messagePayload.none { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" })
            assertTrue(messagePayload.none { "tool_calls" in it.jsonObject })
            assertTrue(app.messageRepository.getByConversationId("approval").first().any { it.content == "first tool output" })
            assertEquals(2, requests.get(), "only the explicit retries may reach the provider")
            app.shutdown()
            assertEquals(RoomShutdownState.Stopped, lifecycle.state.value)
        } finally {
            container?.shutdown()
            if (lifecycle.state.value == RoomShutdownState.Running) lifecycle.stopAndAwait()
            CoreBridgeRegistry.bridge = previousBridge
            db.close(); server.stop(0); dir.toFile().deleteRecursively()
        }
    }
}
