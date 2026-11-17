/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.model.Agent
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.*

class KotlinCoreAgentToolTest {
    private suspend fun seed(db: LocalDatabase) {
        val rows = db.rows()
        rows.upsertProviders(listOf(ProviderRow("p", "provider", "https://example.test", "key", "chat", false, 1, 1)))
        rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", true, false, false, false, 1, null, null)))
        val agents = RoomAgentRepository(db)
        agents.ensureDefaultAgent(Agent("a", "Parent", systemPrompt = "system", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
        agents.insert(Agent("child", "Child", systemPrompt = "child", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, toolsEnabled = false, createdAt = 2, updatedAt = 2))
        rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
        rows.upsertValues(listOf(KeyValueRow("mcp_agent_delegation_enabled", "true")))
    }

    @Test fun delegatesToRealChildTurnAndPreventsRecursiveDelegation() = runBlocking {
        val dir = Files.createTempDirectory("core-delegation")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            seed(db)
            val childRequests = mutableListOf<cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto>()
            val native = KotlinDelegatedTurnHost(db, KotlinDelegatedRuntimeFactory { _, _ ->
                KotlinDelegatedRuntime(KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false },
                    KotlinTurnToolExecutor { _, _ -> error("no child tools") }, KotlinTurnProvider { _, request -> flow {
                        childRequests.add(request); emit(ChatStreamEvent.Content("subanswer")); emit(ChatStreamEvent.Done("stop"))
                    } }, scope)
            })
            lateinit var store: RoomKotlinTurnStore
            val tools = coreAgentTool(db, "c", { store }, native, null) { 1000 }
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(tools), tools)
            val call = ToolCallData("call", "messenger_agents", """{"action":"call","agentId":"child","task":"Investigate"}""")
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "delegate", null))).turnId
            assertTrue(store.claim(id, false)); store.beginProvider(id); store.completeProvider(id, listOf(call), null)
            store.decideApproval(id, "call", true); store.beginTool(id)
            val result = withTimeout(5000) { tools.execute(id, call) }
            assertFalse(result.isError, result.output)
            assertEquals("subanswer", result.output)
            assertTrue(childRequests.single().tools.isNullOrEmpty())
            val child = db.rows().observeAllConversations().first().single { it.agentId == "child" }
            assertTrue(db.rows().messagesFor(listOf(child.id)).any { it.role == "assistant" && it.content == "subanswer" })
            store.completeTool(id, call, result); store.cancel(id); store.release(id)
        } finally { scope.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun cancellationWaitsForDelegatedChildToStop() = runBlocking {
        val dir = Files.createTempDirectory("core-delegation-cancel")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            seed(db)
            val started = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
            val native = KotlinDelegatedTurnHost(db, KotlinDelegatedRuntimeFactory { _, _ ->
                KotlinDelegatedRuntime(KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false },
                    KotlinTurnToolExecutor { _, _ -> error("no child tools") }, KotlinTurnProvider { _, _ -> flow {
                        try { started.complete(Unit); awaitCancellation() } finally { stopped.complete(Unit) }
                    } }, scope)
            })
            lateinit var store: RoomKotlinTurnStore
            val tools = coreAgentTool(db, "c", { store }, native, null) { 1000 }
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(tools), tools)
            val call = ToolCallData("call", "messenger_agents", """{"action":"call","agentId":"child","task":"Investigate"}""")
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> flow { emit(ChatStreamEvent.Done("tool_calls", toolCalls = listOf(call))) } }, tools, scope)
            val id = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(KotlinTurnCommand.Send("c", "delegate", null))).turnId
            withTimeout(5000) { store.observe(id).first { it.phase == KotlinTurnPhase.WAITING_APPROVAL } }
            runner.approve(id, "call", true)
            withTimeout(5000) { started.await() }
            withTimeout(5000) { runner.cancelAndAwait(id); stopped.await() }
            assertEquals(KotlinTurnPhase.CANCELLED, store.snapshot(id).phase)
        } finally { scope.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun coreActionFailureRollsBackItsWritesButRetainsTheInvocationClaim() = runBlocking {
        val dir = Files.createTempDirectory("core-action-rollback")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            val spec = ToolSpecDto("function", ToolSpecFunctionDto("messenger_agents", "test", buildJsonObject {}))
            lateinit var store: RoomKotlinTurnStore
            val tool = KotlinCoreToolAdapter.create(db, "c", { store }, spec, "mcp_agent_delegation_enabled") { id, call ->
                store.executeRoomCoreTool(id, call) { db.rows().upsertValues(listOf(KeyValueRow("should_rollback", "yes"))); error("fail after write") }
            }
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(tool), tool)
            val call = ToolCallData("call", "messenger_agents", "{}")
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "test", null))).turnId
            assertTrue(store.claim(id, false)); store.beginProvider(id); store.completeProvider(id, listOf(call), null)
            store.decideApproval(id, "call", true); store.beginTool(id)
            val result = tool.execute(id, call)
            assertTrue(result.isError)
            assertNull(db.rows().getValue("should_rollback"))
            assertTrue(store.coreToolInvocationStarted(id, call))
            store.completeTool(id, call, result); store.cancel(id); store.release(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
