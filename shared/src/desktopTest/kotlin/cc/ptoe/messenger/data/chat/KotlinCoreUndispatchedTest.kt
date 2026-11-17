/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.cloud.CloudUser
import cc.ptoe.messenger.data.cloud.RoomCloudProviderGate
import cc.ptoe.messenger.data.cloud.StoredCloudSession
import cc.ptoe.messenger.data.cloud.SyncSession
import cc.ptoe.messenger.data.local.asset.CoreAssetGate
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.data.repository.LearningOwnership
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.model.Agent
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.*

class KotlinCoreUndispatchedTest {
    private class AssetGate : CoreAssetGate {
        private val mutex = Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T): T = mutex.withLock { block() }
    }

    private suspend fun seed(db: LocalDatabase, providerId: String) {
        val rows = db.rows()
        rows.upsertModels(listOf(ModelRow("m", providerId, "model", "Model", true, 1000, null, null, null, null,
            "text", "text", true, false, false, false, 1, null, null)))
        RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "system", defaultModelId = "m",
            temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
        rows.upsertConversations(listOf(ConversationRow("c", "Chat", providerId, "a", null,
            null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
        rows.upsertValues(listOf(KeyValueRow("learning_tools_enabled", "true"), KeyValueRow("mcp_agent_delegation_enabled", "true")))
    }

    @Test fun expiredCloudSessionRejectsUnclaimedLearningWithoutMutationOrReservation(): Unit = runBlocking {
        val dir = Files.createTempDirectory("core-unclaimed-expiry")
        val assetsRoot = Files.createDirectory(dir.resolve("assets"))
        if (Files.getFileStore(assetsRoot).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(assetsRoot, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val call = ToolCallData("call", "messenger_learning", """{"action":"upsert","record":{"id":"note","title":"Notes","source":{"kind":"manual","snapshot":"source"},"content":{"kind":"item","itemKind":"note"}}}""")
        val provider = KotlinTurnProvider { _, _ -> flowOf(ChatStreamEvent.Done("tool_calls", toolCalls = listOf(call))) }
        val gate = RoomCloudProviderGate.forTesting(db.cloudSync(), provider)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val session = SyncSession("account", "generation")
            val host = "https://cloud.example"
            val user = CloudUser(id = "account", aiApiKey = "secret")
            db.cloudSync().saveCredentials(StoredCloudSession(session, "messenger_session=cookie", host, user))
            db.cloudSync().updateCloudUser(session, user, host)
            seed(db, BUILTIN_PROVIDER_ID)
            val assets = CoreAssetService(assetsRoot, db.assetMetadata(), AssetGate())
            lateinit var store: RoomKotlinTurnStore
            val tool = coreLearningTool(db, "c", { store }, LearningOwnership(db, assets)) { 1000 }
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(tool), tool, cloudGate = gate)
            val executor = KotlinTurnToolExecutor { id, pending -> entered.complete(Unit); release.await(); tool.execute(id, pending) }
            val runner = KotlinChatTurnRunner(store, provider, executor, scope)
            val turn = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(KotlinTurnCommand.Send("c", "help", null))).turnId
            withTimeout(5_000) { store.observe(turn).first { it.phase == KotlinTurnPhase.WAITING_APPROVAL } }
            runner.approve(turn, call.callId, true)
            withTimeout(5_000) { entered.await() }
            assertFalse(store.rejectUndispatchedCoreTool(turn, call.copy(callId = "other"), "mismatch"))
            assertNotNull(db.rows().getValue("kotlin_turn_active_v1:c"))
            assertTrue(gate.revoke(session))
            release.complete(Unit)
            withTimeout(5_000) { runner.awaitStopped(turn) }
            assertEquals(KotlinTurnPhase.FAILED, store.snapshot(turn).phase)
            assertNull(db.learningReview().record("note"))
            assertNull(db.rows().getValue("kotlin_turn_active_v1:c"))
            val detail = assertNotNull(store.observeFailure(turn).first())
            assertTrue(detail.contains("session", ignoreCase = true), detail)
            assertTrue(db.rows().messagesFor(listOf("c")).any { it.role == "assistant" && it.status == "error" && it.errorMessage == detail })
        } finally {
            release.complete(Unit)
            job.cancelAndJoin(); gate.shutdown(); db.close(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun failureAfterCoreClaimKeepsReservationAndIsNeverReplayed(): Unit = runBlocking {
        val dir = Files.createTempDirectory("core-claimed-failure")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        try {
            db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "https://example.test", "key", "chat", false, 1, 1)))
            seed(db, "p")
            lateinit var store: RoomKotlinTurnStore
            val spec = ToolSpecDto("function", ToolSpecFunctionDto("messenger_agents", "test", buildJsonObject {}))
            var attempts = 0
            val tool = KotlinCoreToolAdapter.create(db, "c", { store }, spec, "mcp_agent_delegation_enabled") { id, call ->
                store.claimToolInvocation(id, call)
                check(!store.rejectUndispatchedCoreTool(id, call, "must not release claimed call"))
                attempts++
                error("operation outcome unknown")
            }
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(tool), tool)
            val call = ToolCallData("call", "messenger_agents", "{}")
            val provider = KotlinTurnProvider { _, _ -> flowOf(ChatStreamEvent.Done("tool_calls", toolCalls = listOf(call))) }
            val runner = KotlinChatTurnRunner(store, provider, tool, scope)
            val turn = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(KotlinTurnCommand.Send("c", "help", null))).turnId
            withTimeout(5_000) { store.observe(turn).first { it.phase == KotlinTurnPhase.WAITING_APPROVAL } }
            runner.approve(turn, call.callId, true)
            withTimeout(5_000) { runner.awaitStopped(turn) }
            assertEquals(1, attempts)
            assertEquals(KotlinTurnPhase.INTERRUPTED_TOOL, store.snapshot(turn).phase)
            assertNotNull(db.rows().getValue("kotlin_turn_active_v1:c"))
            assertIs<KotlinTurnAdmission.Rejected>(store.admit(KotlinTurnCommand.Send("c", "do not replay", null)))
        } finally { job.cancelAndJoin(); db.close(); dir.toFile().deleteRecursively() }
    }
}
