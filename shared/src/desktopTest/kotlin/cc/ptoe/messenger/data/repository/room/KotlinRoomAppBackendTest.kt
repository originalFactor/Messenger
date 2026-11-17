/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.room.Transactor
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridgeRegistry
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.core.ToolSpecBridge
import cc.ptoe.messenger.data.chat.ACKNOWLEDGED_MCP_UNCERTAINTY
import cc.ptoe.messenger.data.chat.KotlinProviderRequestPreparer
import cc.ptoe.messenger.data.chat.KotlinTurnToolPolicy
import cc.ptoe.messenger.data.chat.RoomKotlinTurnStore
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.domain.chat.KotlinTurnProvider
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.local.acp.AcpWorkerBinding
import cc.ptoe.messenger.data.local.acp.RoomAcpReplaySnapshots
import cc.ptoe.messenger.data.repository.exportAcpReplay
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomShutdownState
import cc.ptoe.messenger.di.AppContainer
import cc.ptoe.messenger.di.AppDirs
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.mcp.McpProcessBridge
import cc.ptoe.messenger.domain.mcp.JsonRpcRequest
import cc.ptoe.messenger.presentation.platform.ForegroundTaskRegistry
import com.sun.net.httpserver.HttpServer
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KotlinRoomAppBackendTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test fun optedInRoomContainerExecutesMcpAndRequiresExplicitUnknownAcknowledgement(): Unit = runBlocking {
        for (scenario in listOf("success", "unknown", "restart-unknown", "cancel-after-send", "config-after-selection")) {
            val unknown = scenario == "unknown" || scenario == "restart-unknown"
            val cancelAfterSend = scenario == "cancel-after-send"
            val dir = Files.createTempDirectory("room-mcp-container")
            val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
            val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
            val prefs = MemoryPreferences()
            val preferences = AppPreferences(prefs)
            val calls = AtomicInteger()
            val foregroundEnds = AtomicInteger()
            val cleanupEntered = CompletableDeferred<Unit>()
            val releaseCleanup = CountDownLatch(1)
            val enteredResponse = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            val selectionEntered = CompletableDeferred<Unit>()
            val releaseSelection = CompletableDeferred<Unit>()
            val previousBegin = ForegroundTaskRegistry.begin
            val previousEnd = ForegroundTaskRegistry.end
            if (unknown) {
                ForegroundTaskRegistry.begin = {}
                ForegroundTaskRegistry.end = {
                    if (foregroundEnds.incrementAndGet() == 2) { cleanupEntered.complete(Unit); releaseCleanup.await() }
                }
            }
            val requests = AtomicInteger()
            val providerRequests = mutableListOf<cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto>()
            var reply: ((String) -> Unit)? = null
            val factory = { object : McpProcessBridge {
                override suspend fun startProcess(command: String, env: Map<String, String>, onLine: (String) -> Unit,
                    onError: (String) -> Unit, onClose: (Int) -> Unit): Boolean { reply = onLine; return true }
                override suspend fun sendLine(line: String): Boolean {
                    val request = Json.decodeFromString<JsonRpcRequest>(line)
                    val id = request.id ?: return true
                    when (request.method) {
                        "initialize" -> reply?.invoke("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""")
                        "tools/list" -> reply?.invoke("""{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"work","inputSchema":{"type":"object"}},{"name":"hidden","inputSchema":{"type":"object"}}]}}""")
                        "tools/call" -> {
                            calls.incrementAndGet()
                            if (cancelAfterSend) { enteredResponse.complete(Unit); releaseResponse.await() }
                            val result = if (unknown) """{"content":[{"type":"text"}]}""" else """{"content":[{"type":"text","text":"worked"}]}"""
                            reply?.invoke("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                        }
                    }
                    return true
                }
                override suspend fun close() {}
            } }
            val provider = KotlinTurnProvider { _, request -> flow {
                synchronized(providerRequests) { providerRequests.add(request) }
                if (requests.getAndIncrement() == 0) emit(ChatStreamEvent.Done("tool_calls", toolCalls = listOf(ToolCallData("call-1", "Server_work", """{"path":"private-test"}"""))))
                else { emit(ChatStreamEvent.Content("answer")); emit(ChatStreamEvent.Done("stop")) }
            } }
            var container: AppContainer? = null
            try {
                RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = "m",
                    temperature = 0.7f, topP = 1f, maxTokens = null, toolsEnabled = true, isDefault = true, createdAt = 1, updatedAt = 1))
                preferences.setMcpServersJson("""[{"id":"server","name":"Server","transportType":"STDIO","command":"fake"}]""")
                val slice = KotlinRoomBackendSlice.attach(db, lifecycle, preferences, ThemePreferences(prefs), Files.createDirectory(dir.resolve("assets")),
                    dir.resolve("avatars"), dir.resolve("images"), turnProvider = provider,
                    mcpConnections = IsolatedRoomMcpConfig(factory, enableExecution = true, afterSelection = {
                        if (scenario == "config-after-selection") { selectionEntered.complete(Unit); releaseSelection.await() }
                    }))
                val images = ChatImageStore::class.java.cast(Proxy.newProxyInstance(ChatImageStore::class.java.classLoader,
                    arrayOf(ChatImageStore::class.java)) { _, _, _ -> null })
                val app = AppContainer(AppDirs(dir.toString().toPath(), dir.resolve("cache").toString().toPath()), images, slice.asAppBackend())
                container = app
                app.providerRepository.insert(Provider("p", "Provider", "https://example.test", "key", createdAt = 1, updatedAt = 1))
                app.modelRepository.insert(ChatModel("m", "p", "model", "Model", true, supportsToolCalling = true, createdAt = 1))
                app.conversationRepository.insert(Conversation("c", "Chat", "p", "a", createdAt = 1, updatedAt = 1, lastMessage = null))
                withTimeout(5_000) { app.mcpManager.toolInventoryReady.first { it } }
                val tool = app.availableTools(null).single { it.name == "Server_work" }
                val config = TurnConfigBridge("c", "model", "", "", "chat", "", null, null, null, null,
                    listOf(tool.name), false, 0, "", null, null, null, false, "",
                    tools = listOf(ToolSpecBridge(tool.name, tool.description, tool.parametersJson)), operation = "send", content = "hello",
                    disableTools = false, supportsToolApproval = true)
                if (scenario == "config-after-selection") {
                    val starting = async { runCatching { app.coreBridge.startBackgroundTurn(config) } }
                    withTimeout(5_000) { selectionEntered.await() }
                    app.mcpManager.saveServers(emptyList())
                    releaseSelection.complete(Unit)
                    assertTrue(withTimeout(5_000) { starting.await() }.isFailure)
                    assertEquals(0, calls.get()); assertEquals(0, requests.get())
                    assertTrue(app.messageRepository.getByConversationId("c").first().isEmpty())
                    continue
                }
                app.coreBridge.startBackgroundTurn(config)
                val approval = CompletableDeferred<Unit>()
                val observer = async { app.coreBridge.observeBackgroundTurn("c") { event ->
                    if (Json.parseToJsonElement(event).jsonObject["type"]?.jsonPrimitive?.content == "ToolApprovalRequested") approval.complete(Unit)
                } }
                withTimeout(5_000) { approval.await() }
                assertEquals(0, calls.get())
                assertTrue(synchronized(providerRequests) { providerRequests.first().tools.orEmpty().map { it.function.name } }.contains("Server_work"))
                assertFalse(synchronized(providerRequests) { providerRequests.first().tools.orEmpty().map { it.function.name } }.contains("Server_hidden"))
                app.coreBridge.respondToolApproval("call-1", true)
                if (cancelAfterSend) {
                    withTimeout(5_000) { enteredResponse.await() }
                    app.coreBridge.cancelBackgroundTurn("c")
                    releaseResponse.complete(Unit)
                }
                withTimeout(5_000) { observer.await() }
                assertEquals(1, calls.get())
                if (unknown) {
                    withTimeout(5_000) { cleanupEntered.await() }
                    val pending = async(Dispatchers.Default) { app.coreBridge.uncertainMcpCall("c") }
                    assertNull(withTimeoutOrNull(150) { pending.await() })
                    releaseCleanup.countDown()
                    val uncertain = assertNotNull(withTimeout(5_000) { pending.await() })
                    assertEquals("Server_work", uncertain.toolName)
                    assertEquals("""{"path":"private-test"}""", uncertain.argumentsJson)
                    assertFalse(uncertain.toString().contains("private-test"))
                    assertTrue(uncertain.canAcknowledge)
                    val previousOwner = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false })
                    val oldView = assertNotNull(previousOwner.uncertainMcpCall("c"))
                    assertFalse(oldView.canAcknowledge)
                    assertEquals(uncertain.argumentsJson, oldView.argumentsJson)
                    assertFailsWith<IllegalStateException> { previousOwner.acknowledgeUncertainMcpCall("c", oldView.turnId, oldView.callId) }
                    if (scenario == "restart-unknown") {
                        app.shutdown()
                        container = null
                        val otherDb = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
                            .setDriver(BundledSQLiteDriver()).build()
                        val otherLifecycle = RoomManagedShutdown { }; otherLifecycle.enter()
                        try {
                            assertFailsWith<IllegalStateException> {
                                KotlinRoomBackendSlice.attach(otherDb, otherLifecycle, preferences, ThemePreferences(prefs),
                                    dir.resolve("assets"), dir.resolve("avatars"), dir.resolve("images"))
                            }
                        } finally { otherLifecycle.stopAndAwait(); otherDb.close() }
                        val nextLifecycle = RoomManagedShutdown { }; nextLifecycle.enter()
                        var nextContainer: AppContainer? = null
                        try {
                            val reopened = KotlinRoomBackendSlice.attach(db, nextLifecycle, preferences, ThemePreferences(prefs),
                                dir.resolve("assets"), dir.resolve("avatars"), dir.resolve("images"))
                            nextContainer = AppContainer(AppDirs(dir.toString().toPath(), dir.resolve("cache").toString().toPath()), images, reopened.asAppBackend())
                            val pendingFromPrevious = assertNotNull(nextContainer.coreBridge.uncertainMcpCall("c"))
                            assertTrue(pendingFromPrevious.canAcknowledge)
                            assertEquals(uncertain.argumentsJson, pendingFromPrevious.argumentsJson)
                            assertFailsWith<IllegalStateException> { nextContainer.coreBridge.acknowledgeUncertainMcpCall("c", uncertain.turnId, "wrong") }
                            nextContainer.coreBridge.acknowledgeUncertainMcpCall("c", uncertain.turnId, uncertain.callId)
                            assertNull(db.rows().getValue("kotlin_turn_active_v1:c"))
                            val persisted = assertNotNull(db.rows().getValue(ACKNOWLEDGED_MCP_UNCERTAINTY + uncertain.turnId))
                            assertFalse(persisted.value.contains("private-test"))
                            assertEquals(1, calls.get())
                        } finally { nextContainer?.shutdown(); nextLifecycle.stopAndAwait() }
                        continue
                    }
                    assertFailsWith<IllegalStateException> { app.coreBridge.acknowledgeUncertainMcpCall("c", uncertain.turnId, "wrong") }
                    val historyBefore = app.messageRepository.getByConversationId("c").first()
                    for (operation in listOf("send", "retry", "regenerate")) {
                        assertFailsWith<IllegalStateException> { app.coreBridge.startBackgroundTurn(config.copy(operation = operation, messageId = historyBefore.last().id)) }
                        assertEquals(uncertain, app.coreBridge.uncertainMcpCall("c"))
                    }
                    assertEquals(historyBefore, app.messageRepository.getByConversationId("c").first())
                    db.useWriterConnection { connection -> connection.usePrepared("CREATE TRIGGER reject_mcp_audit BEFORE INSERT ON kv WHEN NEW.`key` LIKE 'kotlin_mcp_uncertainty_ack_v1:%' BEGIN SELECT RAISE(ABORT, 'audit write failed'); END") { it.step() } }
                    assertFailsWith<Exception> { app.coreBridge.acknowledgeUncertainMcpCall("c", uncertain.turnId, uncertain.callId) }
                    assertEquals(uncertain, app.coreBridge.uncertainMcpCall("c"))
                    assertNull(db.rows().getValue(ACKNOWLEDGED_MCP_UNCERTAINTY + uncertain.turnId))
                    db.useWriterConnection { connection -> connection.usePrepared("DROP TRIGGER reject_mcp_audit") { it.step() } }
                    app.coreBridge.acknowledgeUncertainMcpCall("c", uncertain.turnId, uncertain.callId)
                    assertNull(app.coreBridge.uncertainMcpCall("c"))
                    val audit = assertNotNull(db.rows().getValue(ACKNOWLEDGED_MCP_UNCERTAINTY + uncertain.turnId))
                    assertTrue(audit.value.contains("Server_work"))
                    assertFalse(audit.value.contains("private-test"))
                    assertFailsWith<IllegalStateException> { app.coreBridge.acknowledgeUncertainMcpCall("c", uncertain.turnId, uncertain.callId) }
                    app.coreBridge.startBackgroundTurn(config.copy(content = "next"))
                    withTimeout(5_000) { app.coreBridge.observeBackgroundTurn("c") {} }
                    val next = synchronized(providerRequests) { providerRequests.last() }
                    assertTrue(next.messages.none { it.role == "tool" || !it.toolCalls.isNullOrEmpty() })
                    assertEquals(1, calls.get())
                } else if (cancelAfterSend) {
                    assertNull(app.coreBridge.uncertainMcpCall("c"))
                    assertTrue(app.messageRepository.getByConversationId("c").first().any { it.role == MessageRole.TOOL && it.content == "worked" })
                    assertEquals(1, requests.get())
                } else {
                    assertNull(app.coreBridge.uncertainMcpCall("c"))
                    assertTrue(app.messageRepository.getByConversationId("c").first().any { it.content == "worked" })
                    assertEquals(2, requests.get())
                    app.conversationRepository.insert(Conversation("empty", "Chat", "p", "a", createdAt = 1, updatedAt = 1, lastMessage = null))
                    app.mcpManager.shutdown()
                    app.coreBridge.startBackgroundTurn(config.copy(conversationId = "empty", toolNames = emptyList(), tools = emptyList(), content = "without MCP"))
                    withTimeout(5_000) { app.coreBridge.observeBackgroundTurn("empty") {} }
                    val withoutMcp = synchronized(providerRequests) { providerRequests.last() }
                    assertTrue(withoutMcp.tools.orEmpty().none { it.function.name.startsWith("Server_") })
                }
            } finally {
                releaseCleanup.countDown()
                releaseResponse.complete(Unit)
                releaseSelection.complete(Unit)
                container?.shutdown(); lifecycle.stopAndAwait()
                ForegroundTaskRegistry.begin = previousBegin; ForegroundTaskRegistry.end = previousEnd
                db.close(); dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun optionalMcpManagerHasOneRoomOwnerAndCannotDispatchKotlinTools(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-mcp-owner")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
        val prefs = MemoryPreferences()
        val preferences = AppPreferences(prefs)
        val closes = AtomicInteger()
        val failClose = AtomicBoolean(true)
        val starts = AtomicInteger()
        val toolCalls = AtomicInteger()
        var callback: ((String) -> Unit)? = null
        val factory = { object : McpProcessBridge {
            override suspend fun startProcess(command: String, env: Map<String, String>, onLine: (String) -> Unit,
                onError: (String) -> Unit, onClose: (Int) -> Unit): Boolean {
                starts.incrementAndGet(); callback = onLine; return true
            }
            override suspend fun sendLine(line: String): Boolean {
                val request = Json.decodeFromString<JsonRpcRequest>(line)
                when (request.method) {
                    "initialize" -> callback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""")
                    "tools/list" -> callback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":{"tools":[{"name":"work","inputSchema":{"type":"object"}}]}}""")
                    "tools/call" -> toolCalls.incrementAndGet()
                }
                return true
            }
            override suspend fun close() { closes.incrementAndGet(); check(!failClose.get()) { "MCP stop is unverified" } }
        } }
        var container: AppContainer? = null
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            preferences.setMcpServersJson("""[{"id":"server","name":"Server","transportType":"STDIO","command":"fake","env":{"TOKEN":"secret"}}]""")
            val assets = Files.createDirectory(dir.resolve("assets"))
            val slice = KotlinRoomBackendSlice.attach(db, lifecycle, preferences, ThemePreferences(prefs), assets,
                dir.resolve("avatars"), dir.resolve("images"), mcpConnections = IsolatedRoomMcpConfig(factory))
            val backend = slice.asAppBackend()
            val images = ChatImageStore::class.java.cast(Proxy.newProxyInstance(ChatImageStore::class.java.classLoader,
                arrayOf(ChatImageStore::class.java)) { _, _, _ -> null })
            val dirs = AppDirs(dir.toString().toPath(), dir.resolve("cache").toString().toPath())
            val app = AppContainer(dirs, images, backend)
            container = app
            withTimeout(5_000) { app.mcpManager.toolInventoryReady.first { it } }
            val snapshot = app.mcpManager.inventorySnapshot()
            assertEquals(listOf("Server_work"), snapshot.tools.map { it.name })
            assertFalse(snapshot.tools.toString().contains("secret"))
            assertEquals(1, starts.get())
            assertFalse(app.platformToolsEnabled)
            assertFalse(app.mcpExecutionEnabled)
            assertTrue(app.availableTools(null).isEmpty())
            assertTrue(app.allTools().none { it.name == "Server_work" })
            assertFailsWith<IllegalStateException> { AppContainer(dirs, images, backend) }
            val attempted = TurnConfigBridge("missing", "model", "", "", "chat", "", null, null, null, null,
                listOf("Server_work"), false, 0, "", null, null, null, false, "", operation = "send", content = "hi")
            assertFailsWith<IllegalArgumentException> { app.coreBridge.startBackgroundTurn(attempted) }
            assertEquals(0, toolCalls.get())
            assertFailsWith<IllegalStateException> { app.shutdown() }
            assertEquals(RoomShutdownState.Running, lifecycle.state.value)
            assertTrue(db.rows().defaultAgents().isNotEmpty())
            failClose.set(false)
            app.shutdown()
            assertEquals(RoomShutdownState.Stopped, lifecycle.state.value)
            assertEquals(2, closes.get())
            assertFalse(app.mcpManager.isInventoryCurrent(snapshot))
            assertFailsWith<IllegalStateException> { AppContainer(dirs, images, backend) }
        } finally {
            failClose.set(false)
            container?.shutdown()
            lifecycle.stopAndAwait()
            db.close(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun appContainerUsesOneRoomOwnerAndRefusesUnsupportedOperations(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-app-backend")
        val assets = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val requests = AtomicInteger()
        val mcpRequests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange ->
            mcpRequests.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.createContext("/v1/chat/completions") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.close()
            val bytes = ("data: {\"choices\":[{\"delta\":{\"content\":\"reply\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n").toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val previous = CoreBridgeRegistry.bridge
        val lifecycle = RoomManagedShutdown { }
        var container: AppContainer? = null
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            lifecycle.enter()
            val prefs = MemoryPreferences()
            val base = "http://127.0.0.1:${server.address.port}"
            val appPreferences = AppPreferences(prefs)
            appPreferences.setMcpServersJson("""[{"id":"external","name":"External","transportType":"SSE","isEnabled":true,"url":"$base/mcp"}]""")
            val slice = KotlinRoomBackendSlice.attach(db, lifecycle, appPreferences, ThemePreferences(prefs),
                assets, dir.resolve("avatars"), dir.resolve("images"), base, "$base/models.json")
            val backend = slice.asAppBackend()
            CoreBridgeRegistry.bridge = backend.coreBridge
            val images = ChatImageStore::class.java.cast(Proxy.newProxyInstance(ChatImageStore::class.java.classLoader,
                arrayOf(ChatImageStore::class.java)) { _, _, _ -> null })
            val app = AppContainer(AppDirs(dir.toString().toPath(), dir.resolve("cache").toString().toPath()), images, backend)
            container = app
            assertSame(slice.bridge, app.coreBridge)
            assertSame(slice.stores, app.stores)
            assertSame(slice.providers, app.providerRepository)
            assertTrue(app.availableTools(dir.toString()).isEmpty())
            assertFailsWith<IllegalStateException> { app.mcpManager.loadServers() }
            assertFailsWith<IllegalStateException> { app.coreBridge.startBackgroundAcp("request", buildJsonObject { }) }
            assertFailsWith<IllegalStateException> { app.coreBridge.clearLocalDataForReinit() }

            app.providerRepository.insert(Provider("p", "Provider", "$base/v1", "key", createdAt = 1, updatedAt = 1))
            app.modelRepository.insert(ChatModel("m", "p", "model", "Model", true, createdAt = 1))
            app.conversationRepository.insert(Conversation("c", "Chat", "p", "a", createdAt = 1, updatedAt = 1, lastMessage = null))
            val config = TurnConfigBridge("c", "model", "", "", "chat", "", null, null, null, null,
                emptyList(), false, 0, "", null, null, null, false, "", operation = "send", content = "hello",
                disableTools = true)
            val conversation = app.conversationRepository.getById("c").first()
            assertFailsWith<IllegalStateException> { app.coreBridge.startBackgroundTurn(config) }
            assertTrue(app.messageRepository.getByConversationId("c").first().isEmpty())
            assertEquals(conversation, app.conversationRepository.getById("c").first())
            app.messageRepository.insert(Message("failed", "c", MessageRole.ASSISTANT, "failed", timestamp = 2,
                status = MessageStatus.ERROR, errorMessage = "test"))
            val beforeRetry = app.messageRepository.getByConversationId("c").first()
            for (operation in listOf("retry", "regenerate")) {
                assertFailsWith<IllegalStateException> {
                    app.coreBridge.startBackgroundTurn(config.copy(operation = operation, messageId = "failed"))
                }
                assertEquals(beforeRetry, app.messageRepository.getByConversationId("c").first())
            }
            app.messageRepository.delete("failed")
            assertEquals(0, requests.get())
            val following = Agent("follow", "Follow", systemPrompt = "", defaultModelId = "m", temperature = 0.7f,
                topP = 1f, maxTokens = null, followDefaultModel = true, createdAt = 1, updatedAt = 1)
            app.agentRepository.insert(following)
            app.conversationRepository.insert(Conversation("override", "Chat", "p", "follow", overrideModelId = "m",
                createdAt = 1, updatedAt = 1, lastMessage = null))
            app.conversationRepository.insert(Conversation("no-override", "Chat", "p", "follow",
                createdAt = 1, updatedAt = 1, lastMessage = null))
            val noOverride = app.conversationRepository.getById("no-override").first()
            val noOverrideConfig = config.copy(conversationId = "no-override")
            assertFailsWith<IllegalStateException> { app.coreBridge.startBackgroundTurn(noOverrideConfig) }
            assertEquals(noOverride, app.conversationRepository.getById("no-override").first())
            assertTrue(app.messageRepository.getByConversationId("no-override").first().isEmpty())
            assertEquals(0, requests.get())
            app.coreBridge.startBackgroundTurn(config.copy(conversationId = "override"))
            withTimeout(10_000) { app.coreBridge.observeBackgroundTurn("override") { } }
            assertEquals(listOf("hello", "reply"), app.messageRepository.getByConversationId("override").first().map { it.content })
            assertEquals(1, requests.get())
            app.agentRepository.delete("a")
            assertEquals(listOf("a"), app.agentRepository.getAll().first().filter { it.isDefault }.map { it.id })
            val agent = app.agentRepository.getDefaultAgent().first()!!
            app.agentRepository.update(agent.copy(defaultModelId = "m"))
            assertFailsWith<IllegalArgumentException> {
                app.coreBridge.startBackgroundTurn(config.copy(hasWorkspace = true, disableTools = false))
            }
            app.coreBridge.startBackgroundTurn(config)
            withTimeout(10_000) { app.coreBridge.observeBackgroundTurn("c") { } }
            assertEquals(listOf("hello", "reply"), app.messageRepository.getByConversationId("c").first().map { it.content })
            assertEquals(2, requests.get())
            val acpRecord = """{"acp:session":{"id":"acp:session","title":"ACP","profile":"codex","updated_at":5,"messages":[{"id":"au","role":"user","text":"question","status":"sent","timestamp":2},{"id":"aa","role":"assistant","text":"failed","status":"error","timestamp":3}]}}"""
            db.rows().upsertValues(listOf(KeyValueRow("local_acp_sessions", acpRecord)))
            assertTrue(app.conversationRepository.getAllForSync().any { it.id == "acp:session" })
            val mixed = app.messageRepository.getByConversationIds(listOf("c", "acp:session"))
            assertEquals(listOf("au", "aa"), mixed.filter { it.conversationId == "acp:session" }.map { it.id })
            assertEquals(MessageStatus.ERROR, mixed.last().status)
            assertEquals("failed", mixed.last().content)
            db.rows().upsertValues(listOf(KeyValueRow("local_acp_sessions", acpRecord.replace("\"timestamp\":3", "\"timestamp\":\"invalid\""))))
            assertFailsWith<IllegalStateException> { app.messageRepository.getByConversationIds(listOf("c", "acp:session")) }
            db.rows().upsertValues(listOf(KeyValueRow("local_acp_sessions", acpRecord)))
            val transcript = (1..25).joinToString(",") { index ->
                """{"id":"m$index","role":"assistant","text":"text $index","status":"sent","timestamp":$index,"events":[{"type":"ToolUpdate","update":{"title":"read","rawInput":{"path":"file$index"}}}]}"""
            }
            val pagedRecord = """{"acp:pages":{"id":"acp:pages","title":"ACP pages","profile":"codex","project":"project","cwd":"/workspace","agent_session":"worker","updated_at":5,"messages":[$transcript]}}"""
            db.rows().upsertValues(listOf(KeyValueRow("local_acp_sessions", pagedRecord)))
            val rawReplay = JsonArray((0..104).map { index -> buildJsonObject {
                put("sessionUpdate", if (index == 104) "future_update" else "agent_message_chunk")
                put("number", index)
                if (index == 103) put("content", buildJsonObject { put("type", "image"); put("data", "aW1hZ2U="); put("mimeType", "image/png") })
            } })
            db.useWriterConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                RoomAcpReplaySnapshots.record(db.rows(), AcpWorkerBinding("acp:pages", "codex", "project", "/workspace", "worker", false), rawReplay, 7)
            } }
            assertTrue(app.coreBridge.supportsAcpReplaySnapshots)
            assertTrue(!app.coreBridge.supportsAcpReplayDeletion)
            val replay = app.coreBridge.listAcpReplaySnapshots("acp:pages").single()
            val firstReplayPage = app.coreBridge.readAcpReplaySnapshot("acp:pages", replay.id, 0)
            assertEquals(100, firstReplayPage.events.size)
            assertEquals(100, firstReplayPage.next)
            val exportedReplay = Json.parseToJsonElement(exportAcpReplay(app.coreBridge, "acp:pages", replay).decodeToString()).jsonObject
            assertEquals(rawReplay, exportedReplay.getValue("snapshot").jsonObject.getValue("events"))
            val truncatedReplay = object : CoreBridge by app.coreBridge {
                override suspend fun readAcpReplaySnapshot(sessionId: String, snapshotId: String, after: Int) =
                    app.coreBridge.readAcpReplaySnapshot(sessionId, snapshotId, after).copy(next = null)
            }
            assertFailsWith<IllegalStateException> { exportAcpReplay(truncatedReplay, "acp:pages", replay) }
            assertFailsWith<IllegalStateException> { app.coreBridge.deleteAcpReplaySnapshot("acp:pages", replay.id) }
            assertEquals(1, app.coreBridge.listAcpReplaySnapshots("acp:pages").size)
            suspend fun history(before: String? = null): kotlinx.serialization.json.JsonObject {
                var result: kotlinx.serialization.json.JsonObject? = null
                app.coreBridge.controlCommand("history", buildJsonObject {
                    put("action", "history"); put("sessionId", "acp:pages"); before?.let { put("before", it) }
                }) { result = it }
                return checkNotNull(result)
            }
            val newest = history()
            assertEquals("History", newest.getValue("type").jsonPrimitive.content)
            assertEquals("m6", newest.getValue("before").jsonPrimitive.content)
            assertEquals(JsonNull, newest.getValue("writable"))
            assertEquals((6..25).map { "m$it" }, newest.getValue("messages").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            val expectedEvent = Json.parseToJsonElement("""{"type":"ToolUpdate","update":{"title":"read","rawInput":{"path":"file25"}}}""")
            assertEquals(expectedEvent, newest.getValue("messages").jsonArray.last().jsonObject.getValue("events").jsonArray.single())
            val older = history("m6")
            assertEquals((1..5).map { "m$it" }, older.getValue("messages").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            assertEquals(JsonNull, older.getValue("before"))
            assertEquals(newest, history("unknown")) // Matches the existing ACP history cursor behavior.
            assertEquals(newest, app.coreBridge.localAcpHistory("acp:pages", null))
            var nullCursor: kotlinx.serialization.json.JsonObject? = null
            app.coreBridge.controlCommand("history", buildJsonObject {
                put("action", "history"); put("sessionId", "acp:pages"); put("before", JsonNull)
            }) { nullCursor = it }
            assertEquals(newest, nullCursor)
            assertTrue(app.coreBridge.getConversationJson("acp:pages")!!.contains("ACP pages"))
            assertFailsWith<IllegalStateException> { app.coreBridge.localAcpHistory("acp:missing", null) }
            assertFailsWith<IllegalStateException> {
                app.coreBridge.controlCommand("history", buildJsonObject {
                    put("action", "history"); put("sessionId", "acp:pages"); put("before", JsonPrimitive(3))
                }) { }
            }
            assertFailsWith<IllegalStateException> {
                app.coreBridge.controlCommand("create", buildJsonObject { put("action", "create"); put("agentId", "acp:codex") }) { }
            }
            db.rows().upsertValues(listOf(KeyValueRow("local_acp_sessions", pagedRecord.replace("\"id\":\"m6\"", "\"id\":\"\""))))
            assertFailsWith<IllegalStateException> { history() }
            db.rows().upsertValues(listOf(KeyValueRow("local_acp_sessions", pagedRecord)))
            assertEquals(2, requests.get(), "history must not start a provider or ACP worker")
            assertEquals(0, mcpRequests.get())
            assertFailsWith<IllegalStateException> { app.clearAllDataAndReinit() }
            assertEquals(listOf("hello", "reply"), app.messageRepository.getByConversationId("c").first().map { it.content })
            assertEquals("a", app.agentRepository.getDefaultAgent().first()?.id)
            app.shutdown()
            lifecycle.stopAndAwait() // The enclosing owner can also await the same stop before closing Room.
            assertEquals(RoomShutdownState.Stopped, lifecycle.state.value)
            assertFailsWith<IllegalStateException> { app.coreBridge.startBackgroundTurn(config) }
            assertFailsWith<IllegalStateException> { app.coreBridge.localAcpHistory("acp:pages", null) }
            assertTrue(!app.coreBridge.supportsAcpReplaySnapshots)
            assertFailsWith<IllegalStateException> { app.coreBridge.listAcpReplaySnapshots("acp:pages") }
            assertFailsWith<IllegalStateException> { slice.asAppBackend() }
            assertSame(backend.coreBridge, CoreBridgeRegistry.bridge)
        } finally {
            container?.shutdown()
            if (lifecycle.state.value == RoomShutdownState.Running) lifecycle.stopAndAwait()
            CoreBridgeRegistry.bridge = previous
            db.close()
            server.stop(0)
            dir.toFile().deleteRecursively()
        }
    }
}
