/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.model.Agent
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KotlinBackgroundTurnBridgeTest {
    private val unusedBridge = Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, method, _ ->
        error("Unexpected delegated call: ${method.name}")
    } as CoreBridge
    private fun config() = TurnConfigBridge(conversationId = "c", modelId = "model", baseUrl = "", apiKey = "", apiFormat = "chat",
        systemPrompt = "", temperature = null, topP = null, maxTokens = null, reasoningEffort = null, toolNames = emptyList(), writable = false,
        contextWindow = 0, summarizePrompt = "", titleAgentId = null, titleAgentSystemPrompt = null, titleAgentModelId = null,
        hasWorkspace = false, workspaceNote = "", operation = "send", content = "hello", disableTools = true)

    private suspend fun seed(db: LocalDatabase) {
        val rows = db.rows()
        rows.upsertProviders(listOf(ProviderRow("p", "provider", "https://example.test", "secret", "chat", false, 1, 1)))
        rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 100, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
        RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
        rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
    }
    private suspend fun seedTitleAgent(db: LocalDatabase) {
        val rows = db.rows()
        rows.upsertProviders(listOf(checkNotNull(rows.provider("p")).copy(id = "title-provider")))
        rows.upsertModels(listOf(checkNotNull(rows.model("m")).copy(id = "title-model-row", providerId = "title-provider", modelId = "title-model")))
        rows.upsertAgents(listOf(checkNotNull(rows.agent("a")).copy(id = "title-agent", isDefault = false, role = "title", defaultModelId = "title-model-row")))
        rows.upsertConversations(listOf(checkNotNull(rows.conversation("c")).copy(title = "New Chat")))
    }
    private val preparer = KotlinTurnRequestPreparer { _, _, _, model, _, _ -> ChatCompletionRequestDto(model.modelId, emptyList()) }
    private fun eventType(text: String) = Json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content

    @Test fun detachedObserverReplaysTextAndReceivesDurableTerminalEvent() = runBlocking {
        val dir = Files.createTempDirectory("turn-bridge")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            seed(db)
            val finish = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val store = RoomKotlinTurnStore(db, preparer, KotlinTurnToolPolicy { _, _, _ -> false })
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> flow {
                emit(ChatStreamEvent.Content("first")); started.complete(Unit); finish.await()
                emit(ChatStreamEvent.Content(" second")); emit(ChatStreamEvent.Done("stop"))
            } }, KotlinTurnToolExecutor { _, _ -> error("tools disabled") }, scope)
            val bridge = KotlinBackgroundTurnBridge(unusedBridge, store, runner, scope)
            bridge.startBackgroundTurn(config())
            withTimeout(5000) { started.await() }
            assertEquals("hello", db.rows().conversation("c")?.lastMessage)
            val received = CompletableDeferred<Unit>()
            val firstObserver = launch { bridge.observeBackgroundTurn("c") { if (eventType(it) == "TextDelta") received.complete(Unit) } }
            withTimeout(5000) { received.await() }
            firstObserver.cancel(); firstObserver.join()
            assertTrue(bridge.backgroundTurnActive("c"))
            val events = mutableListOf<String>()
            val observer = launch { bridge.observeBackgroundTurn("c") { events.add(it) } }
            finish.complete(Unit)
            withTimeout(5000) { observer.join() }
            assertEquals("Finished", eventType(events.last()))
            assertEquals("first second", events.filter { eventType(it) == "TextDelta" }.joinToString("") { Json.parseToJsonElement(it).jsonObject.getValue("text").jsonPrimitive.content })
            assertEquals("first second", db.rows().conversation("c")?.lastMessage)
            assertEquals(db.rows().messagesFor(listOf("c")).last().timestamp, db.rows().conversation("c")?.updatedAt)
            assertFalse(bridge.backgroundTurnActive("c"))
            withTimeout(5000) { runner.awaitStopped(checkNotNull(store.latestTurn("c"))) }
            val replay = mutableListOf<String>()
            bridge.observeBackgroundTurn("c") { replay.add(it) }
            assertEquals("Finished", eventType(replay.last()))
        } finally { scope.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun explicitCancellationPersistsAndObserversSeeTerminalCancellation() = runBlocking {
        val dir = Files.createTempDirectory("turn-bridge-cancel")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            seed(db)
            val started = CompletableDeferred<Unit>()
            val never = CompletableDeferred<Unit>()
            val store = RoomKotlinTurnStore(db, preparer, KotlinTurnToolPolicy { _, _, _ -> false })
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> flow { emit(ChatStreamEvent.Content("partial")); started.complete(Unit); never.await() } }, KotlinTurnToolExecutor { _, _ -> error("tools disabled") }, scope)
            val bridge = KotlinBackgroundTurnBridge(unusedBridge, store, runner, scope)
            bridge.startBackgroundTurn(config())
            withTimeout(5000) { started.await() }
            bridge.cancelBackgroundTurn("c")
            val events = mutableListOf<String>()
            withTimeout(5000) { bridge.observeBackgroundTurn("c") { events.add(it) } }
            assertEquals("Cancelled", eventType(events.last()))
            assertEquals("partial", db.rows().conversation("c")?.lastMessage)
            assertEquals(db.rows().messagesFor(listOf("c")).last().timestamp, db.rows().conversation("c")?.updatedAt)
            assertFalse(bridge.backgroundTurnActive("c"))
            withTimeout(5000) { runner.awaitStopped(checkNotNull(store.latestTurn("c"))) }
        } finally { scope.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun wearAdmissionDisablesToolsBeforePersistingAnything() = runBlocking {
        val dir = Files.createTempDirectory("turn-bridge-no-tools")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            val store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false })
            val result = store.admit(KotlinTurnCommand.Send("c", "hello", null, disableTools = true, supportsToolApproval = false))
            val id = assertIs<KotlinTurnAdmission.Accepted>(result).turnId
            assertTrue(store.snapshot(id).request.tools.isNullOrEmpty())
            store.cancel(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun regenerationRequiresTheCompleteUnchangedMessageGroup() = runBlocking {
        val dir = Files.createTempDirectory("turn-bridge-group")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            val rows = db.rows()
            rows.upsertMessages(listOf(
                MessageRow("user", "c", "user", "question", null, 1, "sent", null),
                MessageRow("round", "c", "assistant", "", null, 2, "sent", null),
                MessageRow("tool", "c", "tool", "result", null, 3, "sent", null),
                MessageRow("answer", "c", "assistant", "answer", null, 4, "sent", null),
            ))
            val store = RoomKotlinTurnStore(db, preparer, KotlinTurnToolPolicy { _, _, _ -> false })
            val bad = store.admit(KotlinTurnCommand.Regenerate("c", "answer", listOf("round", "answer")))
            assertIs<KotlinTurnAdmission.Rejected>(bad)
            assertEquals(listOf("user", "round", "tool", "answer"), rows.messagesFor(listOf("c")).map { it.id })
            // UI groups tool calls and tool results independently; their ids need not be chronological.
            val good = store.admit(KotlinTurnCommand.Regenerate("c", "answer", listOf("round", "answer", "tool")))
            val id = assertIs<KotlinTurnAdmission.Accepted>(good).turnId
            assertEquals(listOf("user", "assistant"), rows.messagesFor(listOf("c")).map { it.role })
            store.cancel(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun approvalSurvivesDetachAndDenialNeverRunsTool() = runBlocking {
        val dir = Files.createTempDirectory("turn-bridge-approval")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            seed(db)
            val scoped = object : KotlinTurnRequestPreparer {
                override fun prepare(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow, model: ModelRow, provider: ProviderRow, history: List<MessageRow>) =
                    ChatCompletionRequestDto(model.modelId, emptyList(), tools = listOf(ToolSpecDto("function", ToolSpecFunctionDto("write", "write", buildJsonObject {}))))
                override fun toolGrant() = KotlinRuntimeToolGrant("c", null, "/workspace", null, "host", listOf("write"))
            }
            val store = RoomKotlinTurnStore(db, scoped, KotlinTurnToolPolicy { _, _, _ -> true })
            var count = 0
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> flow {
                if (count++ == 0) emit(ChatStreamEvent.Done("tool_calls", toolCalls = listOf(ToolCallData("call", "write", "{}"))))
                else { emit(ChatStreamEvent.Content("done")); emit(ChatStreamEvent.Done("stop")) }
            } }, KotlinTurnToolExecutor { _, _ -> error("Denied tool must not run") }, scope)
            val bridge = KotlinBackgroundTurnBridge(unusedBridge, store, runner, scope)
            bridge.startBackgroundTurn(config().copy(disableTools = false, supportsToolApproval = true))
            val approved = CompletableDeferred<Unit>()
            val first = launch { bridge.observeBackgroundTurn("c") { if (eventType(it) == "ToolApprovalRequested") approved.complete(Unit) } }
            withTimeout(5000) { approved.await() }
            first.cancel(); first.join()
            val replayed = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val second = launch { bridge.observeBackgroundTurn("c") { events.add(it); if (eventType(it) == "ToolApprovalRequested") replayed.complete(Unit) } }
            withTimeout(5000) { replayed.await() }
            bridge.respondToolApproval("call", false)
            withTimeout(5000) { second.join() }
            assertTrue(events.any { eventType(it) == "ToolApprovalResolved" })
            assertEquals("Finished", eventType(events.last()))
            assertTrue(db.rows().messagesFor(listOf("c")).any { it.role == "tool" && it.content.contains("denied") })
            withTimeout(5000) { runner.awaitStopped(checkNotNull(store.latestTurn("c"))) }
        } finally { scope.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun providerFailureIsReplayedAndPersistedInAnErrorBubble() = runBlocking {
        val dir = Files.createTempDirectory("turn-bridge-error")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            seed(db)
            val store = RoomKotlinTurnStore(db, preparer, KotlinTurnToolPolicy { _, _, _ -> false })
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> flow { emit(ChatStreamEvent.Content("partial")); emit(ChatStreamEvent.Error("HTTP 429: rate limit")) } }, KotlinTurnToolExecutor { _, _ -> error("tools disabled") }, scope)
            val bridge = KotlinBackgroundTurnBridge(unusedBridge, store, runner, scope)
            bridge.startBackgroundTurn(config())
            val events = mutableListOf<String>()
            withTimeout(5000) { bridge.observeBackgroundTurn("c") { events.add(it) } }
            val error = Json.parseToJsonElement(events.last()).jsonObject
            assertEquals("Error", error.getValue("type").jsonPrimitive.content)
            assertEquals("HTTP 429: rate limit", error.getValue("message").jsonPrimitive.content)
            assertTrue(db.rows().messagesFor(listOf("c")).any { it.role == "assistant" && it.status == "error" && it.errorMessage == "HTTP 429: rate limit" })
            assertEquals("partial", db.rows().conversation("c")?.lastMessage)
            assertEquals(db.rows().messagesFor(listOf("c")).last().timestamp, db.rows().conversation("c")?.updatedAt)
            withTimeout(5000) { runner.awaitStopped(checkNotNull(store.latestTurn("c"))) }
        } finally { scope.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun titleRequestKeepsReservationAndDoesNotOverwriteManualRename() = runBlocking {
        val dir = Files.createTempDirectory("turn-title-race")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            seed(db); seedTitleAgent(db)
            val started = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val requests = mutableListOf<Pair<String, String>>()
            val store = RoomKotlinTurnStore(db, preparer, KotlinTurnToolPolicy { _, _, _ -> false })
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { provider, request -> flow {
                requests.add(provider.id to request.model)
                if (request.model == "title-model") {
                    assertTrue(request.tools.isNullOrEmpty()); assertFalse(provider.nativeWebSearch)
                    started.complete(Unit); proceed.await(); emit(ChatStreamEvent.Content("Generated title"))
                } else emit(ChatStreamEvent.Content("answer"))
                emit(ChatStreamEvent.Done("stop"))
            } }, KotlinTurnToolExecutor { _, _ -> error("tools disabled") }, scope)
            val bridge = KotlinBackgroundTurnBridge(unusedBridge, store, runner, scope)
            bridge.startBackgroundTurn(config())
            withTimeout(5000) { started.await() }
            assertTrue(bridge.backgroundTurnActive("c"))
            assertIs<KotlinTurnAdmission.Rejected>(store.admit(KotlinTurnCommand.Send("c", "another", null, disableTools = true)))
            db.rows().upsertConversations(listOf(checkNotNull(db.rows().conversation("c")).copy(title = "Manual title")))
            proceed.complete(Unit)
            val events = mutableListOf<String>()
            withTimeout(5000) { bridge.observeBackgroundTurn("c") { events.add(it) } }
            assertEquals("Finished", eventType(events.last()))
            assertEquals("Manual title", db.rows().conversation("c")?.title)
            assertEquals("answer", db.rows().conversation("c")?.lastMessage)
            assertEquals(listOf("p" to "model", "title-provider" to "title-model"), requests)
            withTimeout(5000) { runner.awaitStopped(checkNotNull(store.latestTurn("c"))) }
        } finally { scope.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun titleFailureFallsBackAndIsReportedWithoutLosingAnswer() = runBlocking {
        val dir = Files.createTempDirectory("turn-title-error")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            seed(db); seedTitleAgent(db)
            val store = RoomKotlinTurnStore(db, preparer, KotlinTurnToolPolicy { _, _, _ -> false })
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, request -> flow {
                if (request.model == "title-model") emit(ChatStreamEvent.Error("HTTP 429: title rate limit"))
                else { emit(ChatStreamEvent.Content("answer")); emit(ChatStreamEvent.Done("stop")) }
            } }, KotlinTurnToolExecutor { _, _ -> error("tools disabled") }, scope)
            val bridge = KotlinBackgroundTurnBridge(unusedBridge, store, runner, scope)
            bridge.startBackgroundTurn(config())
            val events = mutableListOf<String>()
            withTimeout(5000) { bridge.observeBackgroundTurn("c") { events.add(it) } }
            assertEquals("Finished", eventType(events.last()))
            assertTrue(events.any { eventType(it) == "TitleFailed" && it.contains("rate limit") })
            assertEquals("hello", db.rows().conversation("c")?.title)
            assertTrue(db.rows().messagesFor(listOf("c")).any { it.role == "assistant" && it.content == "answer" && it.status == "sent" })
            withTimeout(5000) { runner.awaitStopped(checkNotNull(store.latestTurn("c"))) }
        } finally { scope.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun interruptedTitleIsNeverResentOnRecovery() = runBlocking {
        val dir = Files.createTempDirectory("turn-title-recovery")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            seed(db); seedTitleAgent(db)
            val previous = RoomKotlinTurnStore(db, preparer, KotlinTurnToolPolicy { _, _, _ -> false })
            val id = assertIs<KotlinTurnAdmission.Accepted>(previous.admit(KotlinTurnCommand.Send("c", "hello", null, disableTools = true))).turnId
            assertTrue(previous.claim(id, false)); previous.beginProvider(id); previous.appendContent(id, "answer")
            previous.completeProvider(id, emptyList(), null)
            assertEquals(KotlinTurnPhase.TITLE_PENDING, previous.snapshot(id).phase)
            assertEquals("title-model", checkNotNull(previous.beginTitle(id)).input.model)
            previous.release(id)
            val recovered = RoomKotlinTurnStore(db, preparer, KotlinTurnToolPolicy { _, _, _ -> false })
            val runner = KotlinChatTurnRunner(recovered, KotlinTurnProvider { _, _ -> error("Title must not be resent") }, KotlinTurnToolExecutor { _, _ -> error("tools disabled") }, scope)
            runner.recover(id)
            withTimeout(5000) { runner.awaitStopped(id) }
            assertEquals(KotlinTurnPhase.FINISHED, recovered.snapshot(id).phase)
            assertEquals("hello", db.rows().conversation("c")?.title)
            val events = mutableListOf<String>()
            KotlinBackgroundTurnBridge(unusedBridge, recovered, runner, scope).observeBackgroundTurn("c") { events.add(it) }
            assertTrue(events.any { eventType(it) == "TitleFailed" && it.contains("interrupted") })
        } finally { scope.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }
}
