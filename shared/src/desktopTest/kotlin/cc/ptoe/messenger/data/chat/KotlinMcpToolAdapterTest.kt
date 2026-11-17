/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.cloud.*
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.local.room.BUILTIN_PROVIDER_ID
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.mcp.*
import cc.ptoe.messenger.domain.model.Agent
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class KotlinMcpToolAdapterTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    @Test fun realRoomTurnUsesOneMcpCallAndRetainsUnknownOutcome(): Unit = runBlocking {
        for (scenario in listOf("success", "disconnect", "unknown", "cancel", "before-receipt-stop", "policy", "config", "cloud401", "remote401", "fence-recovered", "pull-default", "pull-replacement", "invalid-json", "invalid-array")) {
            val dir = Files.createTempDirectory("room-mcp-call")
            val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
            val preferences = AppPreferences(MemoryPreferences())
            val config = McpServerConfig(id = "server-id", name = "server", transportType = McpTransportType.STDIO, command = "server")
            preferences.setMcpServersJson(Json.encodeToString(ListSerializer(McpServerConfig.serializer()), listOf(config)))
            var reply: ((String) -> Unit)? = null
            var disconnect: ((Int) -> Unit)? = null
            val calls = AtomicInteger()
            val enteredSend = CompletableDeferred<Unit>()
            val releaseSend = CompletableDeferred<Unit>()
            val enteredBeforeReceipt = CompletableDeferred<Unit>()
            val releaseBeforeReceipt = CompletableDeferred<Unit>()
            val manager = McpManager(preferences) { object : McpProcessBridge {
                override suspend fun startProcess(command: String, env: Map<String, String>, onLine: (String) -> Unit,
                    onError: (String) -> Unit, onClose: (Int) -> Unit): Boolean {
                    reply = onLine
                    disconnect = onClose
                    return true
                }
                override suspend fun sendLine(line: String): Boolean {
                    val message = Json.decodeFromString<JsonRpcRequest>(line)
                    val id = message.id ?: return true
                    when (message.method) {
                        "initialize" -> reply?.invoke("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""")
                        "tools/list" -> reply?.invoke("""{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"search","inputSchema":{"type":"object"}}]}}""")
                        "tools/call" -> {
                            calls.incrementAndGet()
                            val result = if (scenario == "unknown") """{"content":[{"type":"text"}]}""" else """{"content":[{"type":"text","text":"found"}]}"""
                            reply?.invoke("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                        }
                    }
                    return true
                }
                override suspend fun close() {}
            } }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            var cloudGate: RoomCloudProviderGate? = null
            try {
                val rows = db.rows()
                rows.upsertProviders(listOf(ProviderRow("p", "provider", "https://example.test", "secret", "chat", false, 1, 1)))
                val session = SyncSession("account", "generation")
                val providerId = if (scenario == "cloud401") {
                    val user = CloudUser(id = "account", aiApiKey = "cloud-secret")
                    db.cloudSync().saveCredentials(StoredCloudSession(session, "messenger_session=cookie", "https://cloud.example", user))
                    db.cloudSync().updateCloudUser(session, user, "https://cloud.example")
                    BUILTIN_PROVIDER_ID
                } else "p"
                rows.upsertModels(listOf(ModelRow("m", providerId, "model", "Model", true, 100, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
                RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "", defaultModelId = "m",
                    temperature = 0.7f, topP = 1f, maxTokens = null, toolsEnabled = true, isDefault = true, createdAt = 1, updatedAt = 1))
                val followsDefault = scenario == "pull-default" || scenario == "pull-replacement"
                val remoteScope = if (scenario == "remote401") KotlinRemoteControlScope("account", "generation", "https://cloud.example", "device", 1) else null
                if (remoteScope != null) {
                    rows.upsertValues(listOf(
                        KeyValueRow("kotlin_sync_session", """["account","generation"]"""),
                        KeyValueRow("device_id", "device"), KeyValueRow("cloud_session", "messenger_session=secret"),
                        KeyValueRow("cloud_session_host", "https://cloud.example"), KeyValueRow("cloud_user_json", """{"id":"account"}"""),
                        KeyValueRow("kotlin_device_control_grant_epoch_v1", "1"), KeyValueRow("device_control_grant", """["https://cloud.example","account"]"""),
                        KeyValueRow("device_control_config", """{"enabled":true}""")))
                    rows.upsertProjects(listOf(ProjectRow("project", "Project", "/workspace", "device", 1, 1)))
                }
                if (followsDefault) {
                    rows.upsertAgents(listOf(checkNotNull(rows.agent("a")).copy(id = "following", isDefault = false,
                        toolsEnabled = false, toolsFollowDefault = true)))
                    db.cloudSync().activate(session)
                }
                rows.upsertConversations(listOf(ConversationRow("c", "Chat", providerId, if (followsDefault) "following" else "a", if (remoteScope != null) "project" else null, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
                withTimeout(5_000) { manager.toolInventoryReady.first { it } }
                val inventory = manager.inventorySnapshot()
                if (scenario in setOf("cancel", "policy", "config", "cloud401", "remote401", "fence-recovered", "pull-default", "pull-replacement")) {
                    checkNotNull(inventory.binding("server-id")).first.beforeToolSend = { enteredSend.complete(Unit); releaseSend.await() }
                }
                val call = ToolCallData("call", "server_search", when (scenario) { "invalid-json" -> "{"; "invalid-array" -> "[]"; else -> "{}" })
                lateinit var store: RoomKotlinTurnStore
                val adapter = KotlinMcpToolAdapter(db, "c", manager, inventory) { store }
                val requests = AtomicInteger()
                val provider = KotlinTurnProvider { _, _ -> flow {
                    if (requests.getAndIncrement() == 0) emit(ChatStreamEvent.Done("tool_calls", toolCalls = listOf(call)))
                    else emit(ChatStreamEvent.Done("stop"))
                } }
                cloudGate = if (scenario == "cloud401" || scenario == "remote401") RoomCloudProviderGate.forTesting(db.cloudSync(), provider) else null
                store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(adapter), adapter, cloudGate)
                val runnerStore: KotlinTurnStore = if (scenario == "before-receipt-stop") object : KotlinTurnStore by store {
                    override suspend fun beginTool(turnId: String): ToolCallData {
                        val admitted = store.beginTool(turnId)
                        enteredBeforeReceipt.complete(Unit)
                        withContext(NonCancellable) { releaseBeforeReceipt.await() }
                        return admitted
                    }
                } else store
                val runner = KotlinChatTurnRunner(runnerStore, provider, adapter, scope)
                val id = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(KotlinTurnCommand.Send("c", "hello", null, remoteScope = remoteScope))).turnId
                withTimeout(5_000) { store.observe(id).first { it.phase == KotlinTurnPhase.WAITING_APPROVAL } }
                if (scenario == "disconnect") checkNotNull(disconnect).invoke(1)
                store.decideApproval(id, call.callId, true)
                if (scenario == "before-receipt-stop") {
                    withTimeout(5_000) { enteredBeforeReceipt.await() }
                    runner.cancel(id)
                    releaseBeforeReceipt.complete(Unit)
                }
                if (scenario in setOf("cancel", "policy", "config", "cloud401", "remote401", "fence-recovered", "pull-default", "pull-replacement")) {
                    withTimeout(5_000) { enteredSend.await() }
                    when (scenario) {
                        "cancel" -> runner.cancel(id)
                        "policy" -> RuntimeWorkspaceFence.forDatabase(db).mutate {
                            rows.upsertAgents(listOf(checkNotNull(rows.agent("a")).copy(toolsEnabled = false)))
                        }
                        "config" -> manager.saveServers(emptyList())
                        "cloud401", "remote401" -> checkNotNull(cloudGate).invalidate(session)
                        "fence-recovered" -> {
                            val fence = RuntimeWorkspaceFence.forDatabase(db)
                            fence.invalidateAdmissions()
                            fence.recover(object : TrackedWorkspaceHost {
                                override val protocolAvailable = true
                                override val supportedOperations = emptySet<String>()
                                override suspend fun execute(token: String, workspace: String, call: ToolCallData): cc.ptoe.messenger.domain.tool.ToolExecutionResult = error("unused")
                                override suspend fun status(token: String) = TrackedWorkspaceStatus.STOPPED
                            })
                        }
                        "pull-default", "pull-replacement" -> {
                            val id = if (scenario == "pull-default") "a" else "another-default"
                            val document = Json.parseToJsonElement("""{"_id":"$id","isDefault":true,"toolsEnabled":false}""").jsonObject
                            assertFailsWith<IllegalStateException> {
                                db.cloudSync().applyPull(session, PulledCloudDelta(mapOf(SyncCollection.AGENT to listOf(document)), 7), false)
                            }
                            assertEquals("a", rows.defaultAgents().single().id)
                        }
                    }
                    releaseSend.complete(Unit)
                }
                withTimeout(5_000) { runner.awaitStopped(id) }
                val expected = when (scenario) {
                    "success", "pull-default", "pull-replacement" -> KotlinTurnPhase.FINISHED
                    "cancel", "before-receipt-stop" -> KotlinTurnPhase.CANCELLED
                    "disconnect", "policy", "config", "cloud401", "remote401", "fence-recovered", "invalid-json", "invalid-array" -> KotlinTurnPhase.FAILED
                    else -> KotlinTurnPhase.INTERRUPTED_TOOL
                }
                assertEquals(expected, store.snapshot(id).phase)
                assertEquals(if (scenario in setOf("success", "unknown", "pull-default", "pull-replacement")) 1 else 0, calls.get())
                if (scenario == "before-receipt-stop") assertNull(rows.getValue("kotlin_turn_active_v1:c"))
                if (scenario == "unknown") assertNotNull(rows.getValue("kotlin_turn_active_v1:c"))
            } finally {
                releaseSend.complete(Unit)
                releaseBeforeReceipt.complete(Unit)
                scope.cancel(); scope.coroutineContext[Job]?.join()
                cloudGate?.shutdown()
                manager.shutdown(); db.close(); dir.toFile().deleteRecursively()
            }
        }
    }
}
