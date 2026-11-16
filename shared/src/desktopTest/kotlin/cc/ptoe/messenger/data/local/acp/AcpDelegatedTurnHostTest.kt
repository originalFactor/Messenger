/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.chat.*
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.*

class AcpDelegatedTurnHostTest {
    @Test fun delegatedAcpReturnsRecordedAnswerAndStopsWorker() = runBlocking { execute(permission = false) }
    @Test fun delegatedAcpNeverAutoApprovesAndStopsWorker() = runBlocking { execute(permission = true) }

    private suspend fun execute(permission: Boolean) {
        val dir = Files.createTempDirectory("delegated-acp")
        val workspace = Files.createDirectory(dir.resolve("workspace"))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        var stopped = 0
        try {
            val rows = db.rows()
            rows.upsertProviders(listOf(ProviderRow("p", "provider", "https://example.test", "key", "chat", false, 1, 1)))
            rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", true, false, false, false, 1, null, null)))
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Parent", systemPrompt = "system", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
            rows.upsertProjects(listOf(ProjectRow("project", "Project", workspace.toString(), "device", 1, 1)))
            rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", "project", null, null, null, null, null, null, null, true, 1, 1, null, null, null, 0, 0, 0)))
            rows.upsertValues(listOf(KeyValueRow("device_id", "device"), KeyValueRow("mcp_agent_delegation_enabled", "true"),
                KeyValueRow("device_control_config", """{"agents":[{"id":"profile","name":"ACP","command":"agent"}]}""")))
            lateinit var manager: AcpWorkerManager
            val guard = object : AcpActiveTurnGuard {
                override suspend fun <T> withActiveTurn(sessionId: String, turnId: String, action: suspend () -> T): T = manager.withActiveTurn(sessionId, turnId, action)
            }
            val coordinator = AcpTurnCoordinator(RoomAcpSessionJournal(db, guard)) { 1000 }
            val backend = object : AcpGatewayBackend {
                override suspend fun liveTools() = emptyList<AcpGatewayTool>()
                override suspend fun execute(turnId: String, name: String, arguments: JsonObject) = ToolExecutionResult("unexpected", isError = true)
            }
            val factory = AcpWorkerFactory { initial ->
                val current = AtomicReference(initial)
                val fake = object : AcpWorkerProcess, AcpNewSessionBinding {
                    override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore): AcpWorkerReady {
                        assertTrue(binding.delegated); assertFalse(configuration.allowDelegation); assertTrue(configuration.coreToolScope.isEmpty())
                        return AcpWorkerReady(true, "agent-session")
                    }
                    override fun bindCreatedSession(binding: AcpWorkerBinding) { current.set(binding) }
                    override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker): JsonObject {
                        val events = coordinator.eventsFor { current.get() }
                        if (permission) {
                            permissions.request(turnId, listOf(AcpPermissionOption("allow", "Allow", "allow_once")), "Write", "file") { events.permission(turnId, it) }
                        } else events.update(turnId, buildJsonObject { put("sessionUpdate", "agent_message_chunk"); put("content", buildJsonObject { put("text", "ACP answer") }) })
                        return buildJsonObject { put("type", "Finished") }
                    }
                    override suspend fun cancelAndConfirm() = true
                    override suspend fun stopAndAwait() { stopped++ }
                }
                RecordingAcpWorker(initial, fake, coordinator) { current.set(it) }
            }
            manager = AcpWorkerManager(factory, RoomAcpWorkspaceAuthority(db), backend, { false })
            val sessionStore = RoomAcpSessionStore(db, manager)
            val authority = RoomAcpNewSessionAuthority(db, AcpPublishedMcpCatalog { emptyList() })
            lateinit var parentStore: RoomKotlinTurnStore
            val host = AcpDelegatedTurnHost(db, parentStore = { parentStore }, workers = manager, sessions = sessionStore,
                authority = authority, coordinator = coordinator, configurations = AcpDelegationConfiguration { _, _ ->
                    AcpWorkerConfiguration("agent", emptyList(), emptyMap(), JsonArray(emptyList()), false, emptySet())
                }, now = { 1000 })
            val native = KotlinDelegatedTurnHost(db, KotlinDelegatedRuntimeFactory { _, _ -> error("native not expected") })
            val tool = coreAgentTool(db, "c", { parentStore }, native, host) { 1000 }
            parentStore = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(tool), tool)
            val call = ToolCallData("call", "messenger_agents", """{"action":"call","agentId":"acp:profile","task":"Investigate"}""")
            val id = assertIs<KotlinTurnAdmission.Accepted>(parentStore.admit(KotlinTurnCommand.Send("c", "delegate", null))).turnId
            assertTrue(parentStore.claim(id, false)); parentStore.beginProvider(id); parentStore.completeProvider(id, listOf(call), null)
            parentStore.decideApproval(id, call.callId, true); parentStore.beginTool(id)
            val result = withTimeout(5000) { tool.execute(id, call) }
            if (permission) assertTrue(result.isError && result.output.contains("requested permission"), result.output)
            else { assertFalse(result.isError, result.output); assertEquals("ACP answer", result.output) }
            assertTrue(stopped > 0, "delegated worker must be stopped before the tool returns")
            parentStore.completeTool(id, call, result); parentStore.cancel(id); parentStore.release(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
