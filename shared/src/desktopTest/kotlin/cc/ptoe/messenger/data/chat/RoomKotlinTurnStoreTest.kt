/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import kotlinx.serialization.json.buildJsonObject
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.data.repository.room.RoomConversationRepository
import cc.ptoe.messenger.data.repository.room.RoomMessageRepository
import cc.ptoe.messenger.data.repository.room.RoomProviderRepository
import cc.ptoe.messenger.data.repository.room.AcpConversationStore
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.model.Agent
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomKotlinTurnStoreTest {
    private fun open(path: String) = Room.databaseBuilder<LocalDatabase>(name = path).setDriver(BundledSQLiteDriver()).build()
    private val preparer = object : KotlinTurnRequestPreparer {
        override fun prepare(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow, model: ModelRow, provider: ProviderRow, history: List<MessageRow>) =
            ChatCompletionRequestDto(model.modelId, emptyList(), tools = listOf(ToolSpecDto("function", ToolSpecFunctionDto("write", "write", buildJsonObject {}))))
        override fun toolGrant() = KotlinRuntimeToolGrant("c", null, "/workspace", null, "test-host", listOf("write"))
    }
    private val policy = KotlinTurnToolPolicy { _, _, _ -> true }
    private suspend fun seed(db: LocalDatabase, model: Boolean) {
        val rows = db.rows()
        rows.upsertProviders(listOf(ProviderRow("p", "provider", "https://example.test", "secret", "chat", false, 1, 1)))
        rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 100, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
        RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = if (model) "m" else null, temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
        rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
    }

    @Test fun multipleFailuresOfOneTurnRemainQueuedAndLegacyAcknowledgementIsConditional() = runBlocking {
        val dir = Files.createTempDirectory("turn-error-queue")
        val db = open(dir.resolve("store.db").toString())
        try {
            seed(db, model = true)
            val store = RoomKotlinTurnStore(db, preparer, policy)
            store.setToolApprovalMode("automatic")
            val turn = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "hello", null))).turnId
            assertTrue(store.claim(turn, false)); store.beginProvider(turn)
            val call = ToolCallData("call", "write", "{}")
            store.completeProvider(turn, listOf(call), null)
            assertNotNull(store.beginAutomaticApproval(turn))
            store.completeAutomaticApproval(turn, call.callId, KotlinApprovalResult(false, "review failed"))
            val notices = TurnFailureNotices(store.observeFailures().map { failures ->
                failures.map { TurnFailureNotice(it.noticeId, it.turnId, it.detail) }
            }, { store.acknowledgeFailure(it.noticeId, it.detail) })
            val shown = Channel<String>(Channel.UNLIMITED)
            val releaseFirst = CompletableDeferred<Unit>()
            var count = 0
            val presenter = launch {
                notices.present { detail ->
                    shown.send(detail.detail)
                    if (count++ == 0) releaseFirst.await()
                }
            }
            try {
                assertTrue(withTimeout(5_000) { shown.receive() }.contains("review failed"))
                store.fail(turn, "provider failed")
                val queued = store.observeFailures().first()
                assertEquals(2, queued.size)
                assertEquals(2, queued.map { it.noticeId }.distinct().size)
                assertTrue(queued.all { it.turnId == turn })
                assertTrue(queued.all { it.conversationId == "c" && it.conversationTitle == "Chat" })
                releaseFirst.complete(Unit)
                assertEquals("provider failed", withTimeout(5_000) { shown.receive() })
                withTimeout(5_000) { store.observeFailures().first { it.isEmpty() } }
            } finally { releaseFirst.complete(Unit); presenter.cancelAndJoin(); shown.close() }

            db.rows().upsertValues(listOf(KeyValueRow("kotlin_turn_error_v1:old-turn", "old error")))
            val legacy = store.observeFailures().first().single()
            assertEquals("old-turn", legacy.turnId)
            db.rows().upsertValues(listOf(KeyValueRow(legacy.noticeId, "new error")))
            store.acknowledgeFailure(legacy.noticeId, legacy.detail)
            assertEquals("new error", db.rows().getValue(legacy.noticeId)?.value)
            store.acknowledgeFailure(legacy.noticeId, "new error")
            assertNull(db.rows().getValue(legacy.noticeId))

            // Existing v1 and earlier v2 notices have no embedded conversation ID.
            db.rows().upsertValues(listOf(
                KeyValueRow("kotlin_turn_error_v1:$turn", "legacy failure"),
                KeyValueRow("kotlin_turn_error_v2:old", """{"turnId":"$turn","detail":"old v2 failure"}"""),
                KeyValueRow("kotlin_turn_error_v1:broken", "broken turn failure"),
                KeyValueRow("kotlin_turn_v1:broken", "not a turn"),
            ))
            val attributed = store.observeFailures().first().associateBy { it.detail }
            assertEquals("c", attributed.getValue("legacy failure").conversationId)
            assertEquals("Chat", attributed.getValue("old v2 failure").conversationTitle)
            assertNull(attributed.getValue("broken turn failure").conversationId)
            db.rows().deleteConversation("c")
            val deleted = store.observeFailures().first()
            assertEquals(3, deleted.size)
            assertTrue(deleted.all { it.conversationId == null && it.conversationTitle == null })
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun admittedTurnStartsAfterSubmittingCallerIsCancelled() = runBlocking {
        val dir = Files.createTempDirectory("turn-admission-cancellation")
        val db = open(dir.resolve("store.db").toString())
        val workerScope = CoroutineScope(Dispatchers.Default + Job())
        try {
            seed(db, model = true)
            val store = RoomKotlinTurnStore(db, preparer, policy)
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> flow {
                started.complete(Unit); finish.await(); emit(ChatStreamEvent.Done("stop"))
            } }, KotlinTurnToolExecutor { _, _ -> error("unexpected tool") }, workerScope)
            var turnId: String? = null
            lateinit var caller: Job
            caller = launch { runner.submit(KotlinTurnCommand.Send("c", "hi", null)) { turnId = it; caller.cancel() } }
            caller.join()
            withTimeout(5000) { started.await() }
            finish.complete(Unit)
            val id = checkNotNull(turnId)
            withTimeout(5000) { runner.awaitStopped(id) }
            assertEquals(KotlinTurnPhase.FINISHED, store.snapshot(id).phase)
            assertNull(db.rows().getValue("kotlin_turn_active_v1:c"))
        } finally { workerScope.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun cancelledWorkerScopeCancelsAdmittedTurn() = runBlocking {
        val dir = Files.createTempDirectory("turn-cancelled-scope")
        val db = open(dir.resolve("store.db").toString())
        try {
            seed(db, model = true)
            val workerScope = CoroutineScope(Dispatchers.Default + Job().apply { cancel() })
            val store = RoomKotlinTurnStore(db, preparer, policy)
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> error("provider must not start") },
                KotlinTurnToolExecutor { _, _ -> error("tool must not start") }, workerScope)
            val id = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(KotlinTurnCommand.Send("c", "hi", null))).turnId
            withTimeout(5000) { runner.awaitStopped(id) }
            assertEquals(KotlinTurnPhase.CANCELLED, store.snapshot(id).phase)
            assertNull(db.rows().getValue("kotlin_turn_active_v1:c"))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun toolRowsStayOrderedWhenClockMovesBackward() = runBlocking {
        val dir = Files.createTempDirectory("turn-timestamps")
        val db = open(dir.resolve("store.db").toString())
        try {
            seed(db, model = true)
            db.rows().upsertMessages(listOf(MessageRow("earlier", "c", "user", "before", null, 1000, "sent", null)))
            val store = RoomKotlinTurnStore(db, preparer, policy) { 1L }
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "hi", null))).turnId
            assertTrue(store.claim(id, false))
            store.beginProvider(id)
            val calls = listOf(ToolCallData("first", "write", "{}"), ToolCallData("second", "write", "{}"))
            store.completeProvider(id, calls, null)
            calls.forEach { store.decideApproval(id, it.callId, false) }
            val messages = db.rows().messagesFor(listOf("c"))
            assertEquals(listOf("user", "user", "assistant", "tool", "tool", "assistant"), messages.map { it.role })
            assertTrue(messages.zipWithNext().all { (a, b) -> a.timestamp < b.timestamp })
            store.cancel(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun activeTurnProtectsMessagesAndProvidersUntilItStops() = runBlocking {
        val dir = Files.createTempDirectory("turn-write-guards")
        val db = open(dir.resolve("store.db").toString())
        try {
            seed(db, model = true)
            val store = RoomKotlinTurnStore(db, preparer, policy)
            val providers = RoomProviderRepository(db)
            val messages = RoomMessageRepository(db)
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "hi", null))).turnId
            val assistant = messages.getByConversationId("c").first().single { it.role.name == "ASSISTANT" }
            val provider = checkNotNull(providers.getById("p").first())
            assertFailsWith<IllegalStateException> { messages.update(assistant.copy(content = "corrupt")) }
            assertFailsWith<IllegalStateException> { messages.insert(assistant.copy(id = "new")) }
            assertFailsWith<IllegalStateException> { messages.delete(assistant.id) }
            assertFailsWith<IllegalStateException> { messages.deleteByConversationId("c") }
            assertFailsWith<IllegalStateException> { providers.update(provider.copy(apiKey = "changed")) }
            assertFailsWith<IllegalStateException> { providers.delete(provider.id) }
            assertEquals("", db.rows().message(assistant.id)?.content)
            assertEquals("secret", db.rows().provider("p")?.apiKey)
            store.cancel(id)
            messages.delete(assistant.id)
            providers.update(provider.copy(apiKey = "changed"))
            assertNull(db.rows().message(assistant.id))
            assertEquals("changed", db.rows().provider("p")?.apiKey)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun providerUpdateAndAdmissionAreSerialized() = runBlocking {
        val dir = Files.createTempDirectory("turn-provider-race")
        val db = open(dir.resolve("store.db").toString())
        try {
            seed(db, model = true)
            val store = RoomKotlinTurnStore(db, preparer, policy)
            val providers = RoomProviderRepository(db)
            repeat(4) { iteration ->
                val before = checkNotNull(providers.getById("p").first())
                val next = before.copy(apiKey = "race-$iteration")
                val gate = CompletableDeferred<Unit>()
                val admission = async(Dispatchers.Default) { gate.await(); store.admit(KotlinTurnCommand.Send("c", "hi", null)) }
                val update = async(Dispatchers.Default) { gate.await(); runCatching { providers.update(next) } }
                gate.complete(Unit)
                val id = assertIs<KotlinTurnAdmission.Accepted>(admission.await()).turnId
                val result = update.await()
                val changed = result.isSuccess
                if (!changed) assertIs<IllegalStateException>(result.exceptionOrNull())
                assertEquals(if (changed) next.apiKey else before.apiKey, store.snapshot(id).provider.apiKey)
                store.cancel(id)
            }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun expiredCloudSessionRevokesKeyAndDefersProviderDeletionUntilTurnStops() = runBlocking {
        val dir = Files.createTempDirectory("turn-cloud-expiry")
        val db = open(dir.resolve("store.db").toString())
        try {
            seed(db, model = true)
            val rows = db.rows()
            val builtin = BUILTIN_PROVIDER_ID
            rows.upsertProviders(listOf(checkNotNull(rows.provider("p")).copy(id = builtin, apiKey = "cloud-secret")))
            rows.upsertModels(listOf(checkNotNull(rows.model("m")).copy(id = "cloud-model", providerId = builtin)))
            rows.upsertConversations(listOf(checkNotNull(rows.conversation("c")).copy(id = "c2")))
            val session = cc.ptoe.messenger.data.cloud.SyncSession("account", "generation")
            val sync = db.cloudSync()
            sync.activate(session)
            sync.writeValue(KeyValueRow("cloud_session", "messenger_session=secret"))
            val store = RoomKotlinTurnStore(db, preparer, policy)
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "hi", null))).turnId
            assertTrue(store.claim(id, false))
            store.beginProvider(id)
            val call = ToolCallData("call", "write", "{}")
            store.completeProvider(id, listOf(call), null)
            // Model an incomplete turn persisted by an earlier prototype version.
            val turnKey = "kotlin_turn_v1:$id"
            val saved = checkNotNull(rows.getValue(turnKey))
            val rewritten = saved.value.replace("\"providerId\":\"p\"", "\"providerId\":\"$builtin\"")
            assertTrue(rewritten != saved.value)
            rows.upsertValues(listOf(saved.copy(value = rewritten)))
            rows.upsertAgents(listOf(checkNotNull(rows.agent("a")).copy(defaultModelId = "cloud-model")))
            assertTrue(sync.clearCredentialsIfCurrent(session))
            assertNull(sync.currentSession())
            assertNull(sync.value("cloud_session"))
            assertEquals("", rows.provider(builtin)?.apiKey)
            assertNotNull(sync.value(DEFERRED_BUILTIN_PROVIDER_CLEANUP))
            assertIs<KotlinTurnAdmission.Rejected>(store.admit(KotlinTurnCommand.Send("c2", "hi", null)))
            store.decideApproval(id, call.callId, false)
            assertFailsWith<IllegalStateException> { store.beginProvider(id) }
            store.fail(id, "Cloud session expired; sign in again")
            assertNull(rows.provider(builtin))
            assertNull(sync.value(DEFERRED_BUILTIN_PROVIDER_CLEANUP))
            assertNull(rows.getValue("kotlin_turn_active_v1:c"))
            assertEquals(KotlinTurnPhase.FAILED, store.snapshot(id).phase)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun cloudLogoutOrSessionReplacementRevokesAnApprovedToolBeforeExecution() = runBlocking {
        for (replaceAfterBegin in listOf(false, true)) {
            val dir = Files.createTempDirectory("turn-cloud-tool-revoke")
            val db = open(dir.resolve("store.db").toString())
            try {
                seed(db, model = true)
                val rows = db.rows()
                val sync = db.cloudSync()
                val session = cc.ptoe.messenger.data.cloud.SyncSession("account", "generation")
                sync.activate(session)
                sync.writeValue(KeyValueRow("cloud_session_host", "https://cloud.test"))
                rows.upsertProviders(listOf(checkNotNull(rows.provider("p")).copy(
                    id = BUILTIN_PROVIDER_ID, baseUrl = "https://cloud.test/v1", apiKey = "cloud-secret")))
                rows.upsertModels(listOf(checkNotNull(rows.model("m")).copy(id = "cloud-model", providerId = BUILTIN_PROVIDER_ID)))
                rows.upsertAgents(listOf(checkNotNull(rows.agent("a")).copy(defaultModelId = "cloud-model")))
                val gate = cc.ptoe.messenger.data.cloud.RoomCloudProviderGate.forDao(sync)
                val store = RoomKotlinTurnStore(db, preparer, policy, gate)
                val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "hi", null))).turnId
                assertTrue(store.claim(id, false)); store.beginProvider(id)
                val call = ToolCallData("call", "write", "{}")
                store.completeProvider(id, listOf(call), null)
                store.decideApproval(id, call.callId, true)
                if (replaceAfterBegin) {
                    assertEquals(call, store.beginTool(id))
                    sync.activate(cc.ptoe.messenger.data.cloud.SyncSession("account", "replacement"))
                    assertFailsWith<IllegalStateException> { store.claimToolInvocation(id, call) }
                    assertFalse(store.coreToolInvocationStarted(id, call))
                } else {
                    assertTrue(sync.clearCredentialsIfCurrent(session))
                    assertFailsWith<IllegalStateException> { store.beginTool(id) }
                    assertEquals(KotlinTurnPhase.READY_TOOL, store.snapshot(id).phase)
                }
                store.cancel(id); store.release(id)
            } finally { db.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun cloudErrorCanBeRetriedAndActiveTurnPreventsDeletion() = runBlocking {
        val dir = Files.createTempDirectory("turn-delete-guard")
        val db = open(dir.resolve("store.db").toString())
        try {
            seed(db, model = true)
            val agents = RoomAgentRepository(db)
            agents.insert(Agent("other", "Other", systemPrompt = "prompt", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, createdAt = 1, updatedAt = 1))
            db.rows().upsertConversations(listOf(checkNotNull(db.rows().conversation("c")).copy(agentId = "other")))
            db.rows().upsertMessages(listOf(MessageRow("failed", "c", "assistant", "failed", null, 1, "ERROR", "failure")))
            val acp = object : AcpConversationStore {
                override fun observeAll() = flowOf(emptyList<cc.ptoe.messenger.domain.model.Conversation>())
                override suspend fun update(conversation: cc.ptoe.messenger.domain.model.Conversation) = error("Unexpected ACP call")
                override suspend fun delete(id: String) = error("Unexpected ACP call")
            }
            val conversations = RoomConversationRepository(db, acp)
            val store = RoomKotlinTurnStore(db, preparer, policy)
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Retry("c", "failed"))).turnId
            assertFailsWith<IllegalStateException> { conversations.delete("c") }
            assertFailsWith<IllegalStateException> { agents.delete("other") }
            assertNotNull(db.rows().conversation("c"))
            assertNotNull(db.rows().agent("other"))
            assertNotNull(db.rows().getValue("kotlin_turn_active_v1:c"))
            store.cancel(id)
            agents.delete("other")
            assertNull(db.rows().conversation("c"))
            assertNull(db.rows().agent("other"))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun missingModelLeavesRetryRegenerateAndSendUnchanged() = runBlocking {
        val dir = Files.createTempDirectory("turn-model")
        val db = open(dir.resolve("store.db").toString())
        try {
            seed(db, model = false)
            val original = MessageRow("msg", "c", "assistant", "failed", null, 1, "error", "detail")
            db.rows().upsertMessages(listOf(original))
            val store = RoomKotlinTurnStore(db, preparer, policy)
            val commands = listOf(KotlinTurnCommand.Send("c", "hi", null), KotlinTurnCommand.Retry("c", "msg"), KotlinTurnCommand.Regenerate("c", "msg"))
            commands.forEach { assertEquals(KotlinTurnAdmission.ModelRequired, store.admit(it)) }
            assertEquals(listOf(original), db.rows().messagesFor(listOf("c")))
            assertNull(db.rows().getValue("kotlin_turn_active_v1:c"))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun concurrentAdmissionIsExclusiveAndFailureSurvivesReopen() = runBlocking {
        val dir = Files.createTempDirectory("turn-admission")
        val path = dir.resolve("store.db").toString()
        var db = open(path)
        try {
            seed(db, model = true)
            val store = RoomKotlinTurnStore(db, preparer, policy)
            val attempts = (1..2).map { async { store.admit(KotlinTurnCommand.Send("c", "hi", null)) } }.awaitAll()
            val accepted = attempts.filterIsInstance<KotlinTurnAdmission.Accepted>().single()
            assertEquals(1, attempts.count { it is KotlinTurnAdmission.Rejected })
            assertEquals(2, db.rows().messagesFor(listOf("c")).size)
            assertTrue(store.claim(accepted.turnId, false))
            store.beginProvider(accepted.turnId)
            store.appendContent(accepted.turnId, "partial")
            store.fail(accepted.turnId, "HTTP 429: quota")
            db.close()
            db = open(path)
            val restored = RoomKotlinTurnStore(db, preparer, policy)
            assertEquals(KotlinTurnPhase.FAILED, restored.snapshot(accepted.turnId).phase)
            assertEquals("HTTP 429: quota", restored.observeFailure(accepted.turnId).first())
            val assistant = db.rows().messagesFor(listOf("c")).single { it.role == "assistant" }
            assertEquals("error", assistant.status)
            assertEquals("HTTP 429: quota", assistant.errorMessage)
            assertEquals("partial", assistant.content)
            assertNull(db.rows().getValue("kotlin_turn_active_v1:c"))
            db.rows().deleteValue("kotlin_turn_v1:${accepted.turnId}")
            val persistedNotice = restored.observeFailures().first().single()
            assertEquals("c", persistedNotice.conversationId)
            assertEquals("Chat", persistedNotice.conversationTitle)
            val notices = TurnFailureNotices(restored.observeFailures().map { failures ->
                failures.map { TurnFailureNotice(it.noticeId, it.turnId, it.detail) }
            }, { restored.acknowledgeFailure(it.noticeId, it.detail) })
            val displayed = CompletableDeferred<String>()
            val hold = CompletableDeferred<Unit>()
            val firstHost = launch { notices.present { displayed.complete(it.detail); hold.await() } }
            assertEquals("HTTP 429: quota", withTimeout(5000) { displayed.await() })
            val secondDisplay = CompletableDeferred<String>()
            val secondHost = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                notices.present { secondDisplay.complete(it.detail) }
            }
            assertFalse(secondDisplay.isCompleted)
            firstHost.cancelAndJoin() // Interrupted presentation must remain pending.
            assertEquals("HTTP 429: quota", withTimeout(5000) { secondDisplay.await() })
            withTimeout(5000) { restored.observeFailure(accepted.turnId).first { it == null } }
            secondHost.cancelAndJoin()
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun cancellationRetainsReservationUntilToolReturns() = runBlocking {
        val dir = Files.createTempDirectory("turn-cancel")
        val db = open(dir.resolve("store.db").toString())
        try {
            seed(db, model = true)
            val store = RoomKotlinTurnStore(db, preparer, policy)
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "hi", null))).turnId
            assertTrue(store.claim(id, false))
            store.beginProvider(id)
            val call = ToolCallData("tool-id", "write", "{}")
            store.completeProvider(id, listOf(call), null)
            store.decideApproval(id, call.callId, true)
            store.beginTool(id)
            store.cancel(id)
            assertEquals(KotlinTurnPhase.CANCELLING_TOOL, store.snapshot(id).phase)
            assertIs<KotlinTurnAdmission.Rejected>(store.admit(KotlinTurnCommand.Send("c", "too soon", null)))
            store.release(id) // Called only after the executor invocation has returned.
            assertEquals(KotlinTurnPhase.CANCELLED, store.snapshot(id).phase)
            assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "after return", null)))
        } finally { db.close(); dir.toFile().deleteRecursively() }
        Unit
    }

    @Test fun approvalSurvivesReopenAndAnInFlightToolIsNotReclaimed() = runBlocking {
        val dir = Files.createTempDirectory("turn-recovery")
        val path = dir.resolve("store.db").toString()
        var db = open(path)
        try {
            seed(db, model = true)
            var store = RoomKotlinTurnStore(db, preparer, policy)
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "hi", null))).turnId
            assertTrue(store.claim(id, false))
            store.beginProvider(id)
            val call = ToolCallData("tool-id", "write", "{}")
            store.completeProvider(id, listOf(call), null)
            db.close()
            db = open(path)
            store = RoomKotlinTurnStore(db, preparer, policy)
            assertFalse(store.claim(id, false))
            assertTrue(store.claim(id, true))
            assertEquals(KotlinTurnPhase.WAITING_APPROVAL, store.snapshot(id).phase)
            assertEquals(call, store.snapshot(id).pendingTool)
            store.decideApproval(id, call.callId, true)
            assertEquals(call, store.beginTool(id))
            db.close()
            db = open(path)
            store = RoomKotlinTurnStore(db, preparer, policy)
            assertTrue(store.claim(id, true))
            assertEquals(KotlinTurnPhase.EXECUTING_TOOL, store.snapshot(id).phase)
            store.fail(id, "Tool execution was interrupted; its outcome is unknown")
            assertNotNull(store.observeFailure(id).first())
            assertEquals(KotlinTurnPhase.INTERRUPTED_TOOL, store.snapshot(id).phase)
            assertNotNull(db.rows().getValue("kotlin_turn_active_v1:c"))
            assertIs<KotlinTurnAdmission.Rejected>(store.admit(KotlinTurnCommand.Send("c", "do not reenter", null)))
            var status = TrackedWorkspaceStatus.UNKNOWN
            val host = object : TrackedWorkspaceHost {
                override val protocolAvailable = true
                override val supportedOperations = setOf("glob", "read", "edit", "create")
                override suspend fun execute(token: String, workspace: String, call: ToolCallData): cc.ptoe.messenger.domain.tool.ToolExecutionResult = error("Must not rerun")
                override suspend fun status(token: String) = status
            }
            assertFalse(store.reconcileInterruptedTool(id, host))
            status = TrackedWorkspaceStatus.RUNNING
            assertFalse(store.reconcileInterruptedTool(id, host))
            status = TrackedWorkspaceStatus.STOPPED
            assertTrue(store.reconcileInterruptedTool(id, host))
            assertEquals(KotlinTurnPhase.FAILED, store.snapshot(id).phase)
            assertNull(db.rows().getValue("kotlin_turn_active_v1:c"))
        } finally { db.close(); dir.toFile().deleteRecursively() }
        Unit
    }
}
