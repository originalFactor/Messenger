/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.cloud.RoomDeviceControlGrantStore
import cc.ptoe.messenger.data.cloud.RoomCloudProviderGate
import cc.ptoe.messenger.data.cloud.SyncSession
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.model.Agent
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class KotlinRemoteTurnAuthorizationTest {
    private val scope = KotlinRemoteControlScope("account", "generation", "https://cloud.example", "device", grantEpoch = 1)
    private val enabled = """{"enabled":true}"""
    private val disabled = """{"enabled":false}"""
    private suspend fun seed(db: LocalDatabase) {
        val rows = db.rows()
        rows.upsertValues(listOf(
            KeyValueRow("kotlin_sync_session", """["account","generation"]"""),
            KeyValueRow("device_id", "device"), KeyValueRow("cloud_session", "messenger_session=secret"),
            KeyValueRow("cloud_session_host", "https://cloud.example"), KeyValueRow("cloud_user_json", """{"id":"account"}"""),
            KeyValueRow("kotlin_device_control_grant_epoch_v1", "1"), KeyValueRow("device_control_grant", """["https://cloud.example","account"]"""), KeyValueRow("device_control_config", enabled),
        ))
        rows.upsertProviders(listOf(ProviderRow("p", "provider", "https://example.test", "key", "chat", false, 1, 1)))
        rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 1000, null, null, null, null, "text", "text", true, false, false, false, 1, null, null)))
        RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "system", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
        rows.upsertProjects(listOf(ProjectRow("project", "Project", "/workspace", "device", 1, 1), ProjectRow("foreign", "Foreign", "/elsewhere", "other", 1, 1)))
        fun conversation(id: String, project: String?) = ConversationRow(id, id, "p", "a", project, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)
        rows.upsertConversations(listOf(conversation("c", "project"), conversation("foreign", "foreign"), conversation("local", null)))
    }
    private val prepare = object : KotlinTurnRequestPreparer {
        override fun prepare(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow, model: ModelRow, provider: ProviderRow, history: List<MessageRow>) =
            ChatCompletionRequestDto(model.modelId, emptyList(), tools = listOf(ToolSpecDto("function", ToolSpecFunctionDto("write", "write", buildJsonObject {}))))
        override fun toolGrant() = KotlinRuntimeToolGrant("c", "project", "/workspace", "device", "host", listOf("write"))
    }
    private fun store(db: LocalDatabase) = RoomKotlinTurnStore(db, prepare, KotlinTurnToolPolicy { _, _, _ -> true })

    @Test fun authenticatedCancelRequiresExactOwnerCookieEpochAndUnexpiredSession(): Unit = runBlocking {
        val dir = Files.createTempDirectory("remote-cancel-authority")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val gate = RoomCloudProviderGate.forDao(db.cloudSync())
        try {
            seed(db)
            val owner = RoomKotlinTurnStore(db, prepare, KotlinTurnToolPolicy { _, _, _ -> true }, gate)
            val other = RoomKotlinTurnStore(db, prepare, KotlinTurnToolPolicy { _, _, _ -> true }, gate)
            val id = assertIs<KotlinTurnAdmission.Accepted>(owner.admit(KotlinTurnCommand.Send("c", "hello", null))).turnId
            assertTrue(owner.claim(id, false)); owner.beginProvider(id)
            assertFailsWith<IllegalStateException> { other.cancelRemoteAuthenticated(scope, "messenger_session=secret", "c", id) }
            assertFailsWith<IllegalStateException> { owner.cancelRemoteAuthenticated(scope, "messenger_session=other", "c", id) }
            assertFailsWith<IllegalStateException> { owner.cancelRemoteAuthenticated(scope.copy(grantEpoch = 2), "messenger_session=secret", "c", id) }
            assertFailsWith<IllegalStateException> { owner.cancelRemoteAuthenticated(scope, "messenger_session=secret", "c", "stale") }
            gate.invalidate(SyncSession("account", "generation"))
            assertFailsWith<IllegalStateException> { owner.cancelRemoteAuthenticated(scope, "messenger_session=secret", "c", id) }
            assertEquals(KotlinTurnPhase.REQUESTING_PROVIDER, owner.snapshot(id).phase)
            owner.cancel(id); owner.release(id)
        } finally { gate.shutdown(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun authenticatedDecisionRequiresExactOwnerCookieEpochAndUnexpiredSession(): Unit = runBlocking {
        for (expire in listOf(false, true)) {
            val dir = Files.createTempDirectory("remote-approval-authority")
            val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
            val gate = RoomCloudProviderGate.forDao(db.cloudSync())
            try {
                seed(db)
                val owner = RoomKotlinTurnStore(db, prepare, KotlinTurnToolPolicy { _, _, _ -> true }, gate)
                val other = RoomKotlinTurnStore(db, prepare, KotlinTurnToolPolicy { _, _, _ -> true }, gate)
                val id = assertIs<KotlinTurnAdmission.Accepted>(owner.admit(KotlinTurnCommand.Send("c", "hello", null))).turnId
                assertTrue(owner.claim(id, false)); owner.beginProvider(id)
                owner.completeProvider(id, listOf(ToolCallData("call", "write", "{}")), null)
                assertFailsWith<IllegalStateException> { other.decideRemoteApprovalAuthenticated(scope, "messenger_session=secret", "c", id, "call", true) }
                assertFailsWith<IllegalStateException> { owner.decideRemoteApprovalAuthenticated(scope, "messenger_session=other", "c", id, "call", true) }
                assertFailsWith<IllegalStateException> { owner.decideRemoteApprovalAuthenticated(scope.copy(grantEpoch = 2), "messenger_session=secret", "c", id, "call", true) }
                assertFailsWith<IllegalStateException> { owner.decideRemoteApprovalAuthenticated(scope, "messenger_session=secret", "c", "stale", "call", true) }
                assertFailsWith<IllegalArgumentException> { owner.decideRemoteApprovalAuthenticated(scope, "messenger_session=secret", "c", id, "other", true) }
                if (expire) {
                    gate.invalidate(SyncSession("account", "generation"))
                    assertFailsWith<IllegalStateException> { owner.decideRemoteApprovalAuthenticated(scope, "messenger_session=secret", "c", id, "call", true) }
                    assertEquals(KotlinTurnPhase.WAITING_APPROVAL, owner.snapshot(id).phase)
                } else {
                    owner.decideRemoteApprovalAuthenticated(scope, "messenger_session=secret", "c", id, "call", false)
                    assertTrue(db.rows().messagesFor(listOf("c")).any { it.role == "tool" && it.content.contains("denied") })
                }
                owner.cancel(id); owner.release(id)
            } finally { gate.shutdown(); db.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun revokeThenReenableNeverRestoresOldTurnOrApproval() = runBlocking {
        val dir = Files.createTempDirectory("remote-turn-epoch")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            val store = store(db)
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "hello", null, remoteScope = scope))).turnId
            assertTrue(store.claim(id, false)); store.beginProvider(id)
            store.completeProvider(id, listOf(ToolCallData("call", "write", "{}")), null)
            val settings = RoomDeviceControlGrantStore(db)
            settings.save(scope, 1, buildJsonObject { put("enabled", false) })
            settings.save(scope, 2, buildJsonObject { put("enabled", true) })
            assertFailsWith<IllegalStateException> { store.decideRemoteApproval(scope, "c", id, "call", true) }
            assertFailsWith<IllegalStateException> { store.decideRemoteApproval(scope.copy(grantEpoch = 3), "c", id, "call", true) }
            assertEquals(KotlinTurnPhase.WAITING_APPROVAL, store.snapshot(id).phase)
            store.cancel(id); store.release(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun admissionChecksGrantAndOwnedProjectBeforeMutation() = runBlocking {
        val dir = Files.createTempDirectory("remote-turn-admission")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            val store = store(db)
            assertFailsWith<IllegalStateException> { store.admit(KotlinTurnCommand.Send("c", "hello", null, remoteScope = scope.copy(generation = "stale"))) }
            assertFailsWith<IllegalStateException> { store.admit(KotlinTurnCommand.Send("foreign", "hello", null, remoteScope = scope)) }
            assertFailsWith<IllegalStateException> { store.admit(KotlinTurnCommand.Send("local", "hello", null, remoteScope = scope)) }
            assertTrue(db.rows().messagesFor(listOf("c", "foreign", "local")).isEmpty())
            assertNull(db.rows().getValue("kotlin_turn_active_v1:c"))
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "hello", null, remoteScope = scope))).turnId
            store.cancel(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun approvalRejectsRevokedGrantAndStaleTurnOrCall() = runBlocking {
        val dir = Files.createTempDirectory("remote-turn-approval")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            val store = store(db)
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "hello", null, remoteScope = scope))).turnId
            assertTrue(store.claim(id, false)); store.beginProvider(id)
            val call = ToolCallData("call", "write", "{}")
            store.completeProvider(id, listOf(call), null)
            assertFailsWith<IllegalStateException> { store.decideRemoteApproval(scope, "c", "stale", "call", true) }
            assertFailsWith<IllegalArgumentException> { store.decideRemoteApproval(scope, "c", id, "other", true) }
            db.rows().upsertValues(listOf(KeyValueRow("device_control_config", disabled)))
            assertFailsWith<IllegalStateException> { store.decideRemoteApproval(scope, "c", id, "call", true) }
            assertEquals(KotlinTurnPhase.WAITING_APPROVAL, store.snapshot(id).phase)
            db.rows().upsertValues(listOf(KeyValueRow("device_control_config", enabled)))
            store.decideRemoteApproval(scope, "c", id, "call", false)
            assertTrue(db.rows().messagesFor(listOf("c")).any { it.role == "tool" && it.content.contains("denied") })
            store.cancel(id); store.release(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun revokedGrantCannotStartToolOrCancelAnotherTurn() = runBlocking {
        val dir = Files.createTempDirectory("remote-turn-cancel")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val workerScope = CoroutineScope(Dispatchers.Default + Job())
        try {
            seed(db)
            val store = store(db)
            val started = CompletableDeferred<Unit>()
            val never = CompletableDeferred<Unit>()
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> flow { started.complete(Unit); never.await() } }, KotlinTurnToolExecutor { _, _ -> error("not expected") }, workerScope)
            val id = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(KotlinTurnCommand.Send("c", "hello", null, remoteScope = scope))).turnId
            withTimeout(5000) { started.await() }
            assertFailsWith<IllegalStateException> { runner.cancelRemoteAndAwait(scope, "c", "stale") }
            db.rows().upsertValues(listOf(KeyValueRow("device_control_config", disabled)))
            assertFailsWith<IllegalStateException> { runner.cancelRemoteAndAwait(scope, "c", id) }
            assertEquals(KotlinTurnPhase.REQUESTING_PROVIDER, store.snapshot(id).phase)
            db.rows().upsertValues(listOf(KeyValueRow("device_control_config", enabled)))
            withTimeout(5000) { runner.cancelRemoteAndAwait(scope, "c", id) }
            assertEquals(KotlinTurnPhase.CANCELLED, store.snapshot(id).phase)
        } finally { workerScope.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun toolInvocationIsRecheckedAfterApproval() = runBlocking {
        val dir = Files.createTempDirectory("remote-turn-tool")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            val store = store(db)
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "hello", null, remoteScope = scope))).turnId
            assertTrue(store.claim(id, false)); store.beginProvider(id)
            val call = ToolCallData("call", "write", "{}")
            store.completeProvider(id, listOf(call), null)
            store.decideRemoteApproval(scope, "c", id, "call", true)
            assertEquals(call, store.beginTool(id))
            db.rows().upsertValues(listOf(KeyValueRow("device_control_config", disabled)))
            assertFailsWith<IllegalStateException> { store.claimToolInvocation(id, call) }
            store.cancel(id); store.release(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun revocationPreventsProviderAndTitleRequestsBeforeDispatch() = runBlocking {
        val dir = Files.createTempDirectory("remote-turn-provider")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            val rows = db.rows()
            rows.upsertAgents(listOf(checkNotNull(rows.agent("a")).copy(id = "title-agent", isDefault = false, role = "title")))
            rows.upsertConversations(listOf(checkNotNull(rows.conversation("c")).copy(title = "New Chat")))
            val store = store(db)
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "hello", null, remoteScope = scope))).turnId
            assertTrue(store.claim(id, false))
            rows.upsertValues(listOf(KeyValueRow("device_control_config", disabled)))
            assertFailsWith<IllegalStateException> { store.beginProvider(id) }
            assertEquals(KotlinTurnPhase.READY_PROVIDER, store.snapshot(id).phase)
            rows.upsertValues(listOf(KeyValueRow("device_control_config", enabled)))
            store.beginProvider(id); store.appendContent(id, "answer"); store.completeProvider(id, emptyList(), null)
            assertEquals(KotlinTurnPhase.TITLE_PENDING, store.snapshot(id).phase)
            rows.upsertValues(listOf(KeyValueRow("device_control_config", disabled)))
            assertFailsWith<IllegalStateException> { store.beginTitle(id) }
            assertEquals(KotlinTurnPhase.TITLE_PENDING, store.snapshot(id).phase)
            store.cancel(id); store.release(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun revocationPreventsSummaryRequestBeforeDispatch() = runBlocking {
        val dir = Files.createTempDirectory("remote-turn-summary")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            val rows = db.rows()
            rows.upsertModels(listOf(checkNotNull(rows.model("m")).copy(contextWindow = 100)))
            rows.upsertMessages((1..15).map { i -> MessageRow("m$i", "c", "user", "message $i with sufficient content", null, i.toLong(), "sent", null) })
            val store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false })
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "hello", null, disableTools = true, remoteScope = scope))).turnId
            assertTrue(store.claim(id, false))
            assertEquals(KotlinTurnPhase.SUMMARY_PENDING, store.snapshot(id).phase)
            rows.upsertValues(listOf(KeyValueRow("device_control_config", disabled)))
            assertFailsWith<IllegalStateException> { store.beginSummary(id) }
            assertEquals(KotlinTurnPhase.SUMMARY_PENDING, store.snapshot(id).phase)
            store.cancel(id); store.release(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
