/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.schedule

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.chat.KotlinProviderRequestPreparer
import cc.ptoe.messenger.data.chat.KotlinTurnRequestPreparer
import cc.ptoe.messenger.data.chat.KotlinTurnToolPolicy
import cc.ptoe.messenger.data.chat.RoomKotlinTurnStore
import cc.ptoe.messenger.data.cloud.CloudUser
import cc.ptoe.messenger.data.cloud.RoomCloudProviderGate
import cc.ptoe.messenger.data.cloud.StoredCloudSession
import cc.ptoe.messenger.data.cloud.SyncSession
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.domain.chat.KotlinTurnProvider
import cc.ptoe.messenger.domain.chat.KotlinTurnToolExecutor
import cc.ptoe.messenger.domain.chat.KotlinTurnAdmission
import cc.ptoe.messenger.domain.chat.KotlinTurnCommand
import cc.ptoe.messenger.domain.chat.KotlinTurnPhase
import cc.ptoe.messenger.domain.chat.KotlinScheduledTurnClaim
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicInteger

class RoomScheduledTurnHostTest {
    @Test fun editingEnabledTaskAfterAdmissionKeepsTheOriginalRunSnapshot(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-edit-after-admission")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            val tasks = RoomScheduledTasks(db)
            val taskId = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            tasks.create(KotlinScheduledTask(taskId, "Reminder", "original prompt", "agent", null, 1, 1))
            val claim = tasks.claimDue(2000).single().second
            val scope = tasks.executionScope(claim, emptySet())
            val row = Json.parseToJsonElement("""{"_id":"conversation","agentId":"agent","writable":true}""").jsonObject.conversationRow()
            assertTrue(tasks.publishConversation(scope, "conversation") { it.upsertConversations(listOf(row)) })
            val store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false })
            val admitted = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("conversation", "original prompt", null,
                scheduledRun = true, scheduledClaim = KotlinScheduledTurnClaim(claim.taskId, claim.token, claim.startedAt))))
            assertTrue(store.claim(admitted.turnId, false))
            tasks.update(tasks.list().single().copy(prompt = "next run prompt"))
            assertEquals(KotlinTurnPhase.REQUESTING_PROVIDER, store.beginProvider(admitted.turnId).phase)
            assertEquals("original prompt", db.rows().messagesFor(listOf("conversation")).first().content)
            assertEquals("next run prompt", tasks.list().single().prompt)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun cancellationBeforeTitleKeepsCompletedAnswerSent(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-title-fence")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            db.rows().upsertAgents(listOf(Json.parseToJsonElement("""{"_id":"title-agent","role":"title","defaultModelId":"m"}""").jsonObject.agentRow(null)))
            val tasks = RoomScheduledTasks(db)
            val taskId = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            tasks.create(KotlinScheduledTask(taskId, "Reminder", "hello", "agent", null, 1, 1))
            val claim = tasks.claimDue(2000).single().second
            val scope = tasks.executionScope(claim, emptySet())
            val row = Json.parseToJsonElement("""{"_id":"conversation","title":"New Chat","agentId":"agent","writable":true}""").jsonObject.conversationRow()
            assertTrue(tasks.publishConversation(scope, "conversation") { it.upsertConversations(listOf(row)) })
            val store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false })
            val admitted = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("conversation", "hello", null,
                scheduledRun = true, scheduledClaim = KotlinScheduledTurnClaim(claim.taskId, claim.token, claim.startedAt))))
            assertTrue(store.claim(admitted.turnId, false))
            store.beginProvider(admitted.turnId)
            store.appendContent(admitted.turnId, "completed answer")
            store.completeProvider(admitted.turnId, emptyList(), null)
            assertEquals(KotlinTurnPhase.TITLE_PENDING, store.snapshot(admitted.turnId).phase)
            tasks.update(tasks.list().single().copy(enabled = false))
            assertEquals(null, store.beginTitle(admitted.turnId))
            assertEquals(KotlinTurnPhase.FINISHED, store.snapshot(admitted.turnId).phase)
            assertEquals("sent", db.rows().messagesFor(listOf("conversation")).last().status)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun cancellationAfterAdmissionButBeforeProviderTransitionBlocksDispatch(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-provider-fence")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            val tasks = RoomScheduledTasks(db)
            val taskId = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            tasks.create(KotlinScheduledTask(taskId, "Reminder", "hello", "agent", null, 1, 1))
            val claim = tasks.claimDue(2000).single().second
            val scope = tasks.executionScope(claim, emptySet())
            val row = Json.parseToJsonElement("""{"_id":"conversation","agentId":"agent","writable":true}""").jsonObject.conversationRow()
            assertTrue(tasks.publishConversation(scope, "conversation") { it.upsertConversations(listOf(row)) })
            val store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false })
            val admitted = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("conversation", "hello", null,
                scheduledRun = true, scheduledClaim = KotlinScheduledTurnClaim(claim.taskId, claim.token, claim.startedAt))))
            assertTrue(store.claim(admitted.turnId, false))
            tasks.update(tasks.list().single().copy(enabled = false))
            assertFailsWith<IllegalStateException> { store.beginProvider(admitted.turnId) }
            assertEquals(KotlinTurnPhase.READY_PROVIDER, store.snapshot(admitted.turnId).phase)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun cancellationOrClaimReplacementBeforeTurnAdmissionNeverStartsProvider(): Unit = runBlocking {
        for (replace in listOf(false, true)) {
            val dir = Files.createTempDirectory("schedule-turn-admission-fence")
            val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
            val ownerJob = SupervisorJob()
            val owner = CoroutineScope(ownerJob + Dispatchers.Default)
            try {
                seed(db)
                val tasks = RoomScheduledTasks(db)
                val taskId = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
                tasks.create(KotlinScheduledTask(taskId, "Reminder", "hello", "agent", null, 1, 1))
                val claim = tasks.claimDue(2000).single().second
                val scope = tasks.executionScope(claim, emptySet())
                val calls = AtomicInteger()
                val runtime = ScheduledTurnRuntime(KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false },
                    KotlinTurnToolExecutor { _, _ -> error("Unexpected tool") },
                    KotlinTurnProvider { _, _ -> calls.incrementAndGet(); flowOf(ChatStreamEvent.Done("stop")) }, owner)
                val host = RoomScheduledTurnHost(db, tasks, ScheduledTurnRuntimeFactory { _, _ -> runtime }, { emptySet() }) { 3000 }
                assertFailsWith<IllegalStateException> { host.run(scope) { conversationId ->
                    val recorded = tasks.recordConversation(claim, conversationId)
                    if (replace) db.rows().upsertValues(listOf(KeyValueRow("kotlin_scheduled_active_v1:$taskId",
                        Json.encodeToString(ScheduledRunClaim.serializer(), claim.copy(token = "new-owner")))))
                    else tasks.update(tasks.list().single().copy(enabled = false))
                    recorded
                } }
                val conversation = assertNotNull(tasks.list().single().lastConversationId)
                assertTrue(db.rows().messagesFor(listOf(conversation)).isEmpty())
                assertEquals(null, db.rows().getValue("kotlin_turn_active_v1:$conversation"))
                assertEquals(0, calls.get())
            } finally { ownerJob.cancelAndJoin(); db.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun cancelledClaimAndCloudReplacementCannotCreateAConversation(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-conversation-admission")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.Default)
        try {
            seed(db)
            val tasks = RoomScheduledTasks(db)
            val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            tasks.create(KotlinScheduledTask(id, "Reminder", "hello", "agent", null, 1, 1))
            val (_, claim) = tasks.claimDue(2000).single()
            val scope = tasks.executionScope(claim, emptySet())
            val calls = AtomicInteger()
            val runtime = ScheduledTurnRuntime(KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false },
                KotlinTurnToolExecutor { _, _ -> error("Unexpected tool") },
                KotlinTurnProvider { _, _ -> calls.incrementAndGet(); flowOf(ChatStreamEvent.Done("stop")) }, owner)
            db.rows().upsertValues(listOf(KeyValueRow(CLOUD_REPLACEMENT_LOCK_KEY, "replacement")))
            val host = RoomScheduledTurnHost(db, tasks, ScheduledTurnRuntimeFactory { _, _ -> runtime }, { emptySet() }) { 3000 }
            assertFailsWith<IllegalStateException> { host.run(scope) { tasks.recordConversation(claim, it) } }
            assertTrue(db.rows().observeAllConversations().first().isEmpty())
            assertEquals(null, tasks.list().single().lastConversationId)
            db.rows().deleteValue(CLOUD_REPLACEMENT_LOCK_KEY)
            val racingHost = RoomScheduledTurnHost(db, tasks, ScheduledTurnRuntimeFactory { _, _ ->
                tasks.update(tasks.list().single().copy(enabled = false)); runtime
            }, { emptySet() }) { 3000 }
            assertFailsWith<IllegalStateException> { racingHost.run(scope) { tasks.recordConversation(claim, it) } }
            assertTrue(db.rows().observeAllConversations().first().isEmpty())
            assertEquals(null, tasks.list().single().lastConversationId)
            assertEquals(0, calls.get())
        } finally { ownerJob.cancelAndJoin(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun handoffFailureStillLeavesCreatedConversationAttachedToClaim(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-conversation-handoff")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.Default)
        try {
            seed(db)
            val tasks = RoomScheduledTasks(db)
            val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            tasks.create(KotlinScheduledTask(id, "Reminder", "hello", "agent", null, 1, 1))
            val (_, claim) = tasks.claimDue(2000).single()
            val scope = tasks.executionScope(claim, emptySet())
            val calls = AtomicInteger()
            val runtime = ScheduledTurnRuntime(KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false },
                KotlinTurnToolExecutor { _, _ -> error("Unexpected tool") },
                KotlinTurnProvider { _, _ -> calls.incrementAndGet(); flowOf(ChatStreamEvent.Done("stop")) }, owner)
            val host = RoomScheduledTurnHost(db, tasks, ScheduledTurnRuntimeFactory { _, _ -> runtime }, { emptySet() }) { 3000 }
            assertFailsWith<IllegalStateException> { host.run(scope) { error("handoff failed") } }
            val conversation = assertNotNull(tasks.list().single().lastConversationId)
            assertNotNull(db.rows().conversation(conversation))
            assertTrue(db.rows().messagesFor(listOf(conversation)).isEmpty())
            assertEquals(0, calls.get())
        } finally { ownerJob.cancelAndJoin(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun failedConversationPublicationRollsBackConversationAndClaimLink(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-conversation-rollback")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            val tasks = RoomScheduledTasks(db)
            val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            tasks.create(KotlinScheduledTask(id, "Reminder", "hello", "agent", null, 1, 1))
            val (_, claim) = tasks.claimDue(2000).single()
            val scope = tasks.executionScope(claim, emptySet())
            val row = Json.parseToJsonElement("""{"_id":"conversation","agentId":"agent","writable":true}""").jsonObject.conversationRow()
            assertFailsWith<IllegalStateException> {
                tasks.publishConversation(scope, "conversation") { rows ->
                    rows.upsertConversations(listOf(row))
                    error("injected publication failure")
                }
            }
            assertEquals(null, db.rows().conversation("conversation"))
            assertEquals(null, tasks.list().single().lastConversationId)
            assertEquals(claim, tasks.active(id))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun rawMissingAgentModelNeverCreatesScheduledConversationAndReportsFailure(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-missing-raw-model")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.Default)
        try {
            seed(db)
            val rows = db.rows()
            val follower = checkNotNull(rows.agent("agent")).copy(id = "follower", isDefault = false,
                defaultModelId = "m", followDefaultModel = true)
            rows.upsertAgents(listOf(follower))
            val tasks = RoomScheduledTasks(db)
            val first = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            val second = "d3bc81a8-0589-49da-8cb1-8dc6a82507c7"
            tasks.create(KotlinScheduledTask(first, "First", "hello", "follower", null, 1, 1))
            val (_, claim) = tasks.claimDue(2000).single()
            val scope = tasks.executionScope(claim, emptySet())
            rows.upsertAgents(listOf(follower.copy(defaultModelId = null)))
            val providerCalls = AtomicInteger()
            val runtime = ScheduledTurnRuntime(KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false },
                KotlinTurnToolExecutor { _, _ -> error("Unexpected tool") },
                KotlinTurnProvider { _, _ -> providerCalls.incrementAndGet(); flowOf(ChatStreamEvent.Done("stop")) }, owner)
            val host = RoomScheduledTurnHost(db, tasks, ScheduledTurnRuntimeFactory { _, _ -> runtime }, { emptySet() }) { 3000 }
            val before = rows.observeAllConversations().first()
            assertFailsWith<IllegalStateException> { host.run(scope) { tasks.recordConversation(claim, it) } }
            assertEquals(before, rows.observeAllConversations().first())
            assertEquals(null, tasks.list().single().lastConversationId)
            assertTrue(tasks.finish(claim, "test cleanup", null))

            tasks.create(KotlinScheduledTask(second, "Second", "hello", "follower", null, 1, 1))
            val status = CompletableDeferred<String>()
            val runner = RoomScheduledTaskRunner(tasks, host, { emptySet() }, ScheduledRecoveryBarrier {}, owner, { 3000 },
                object : ScheduledTaskLifecycle { override fun status(taskId: String, result: String) { if (taskId == second) status.complete(result) } })
            runner.tick()
            assertEquals("failed", withTimeout(5_000) { status.await() })
            val failed = tasks.list().single { it.id == second }
            assertEquals("Scheduled Agent requires a model", failed.lastError)
            assertEquals(null, failed.lastConversationId)
            assertEquals(before, rows.observeAllConversations().first())
            assertEquals(0, providerCalls.get())
        } finally { ownerJob.cancelAndJoin(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun realTurnPersistsTheScheduledConversationAndCompletion(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-turn")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.Default)
        try {
            seed(db)
            val tasks = RoomScheduledTasks(db)
            val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            tasks.create(KotlinScheduledTask(id, "Reminder", "hello", "agent", null, 1, 1))
            val (_, claim) = tasks.claimDue(2000).single()
            val scope = tasks.executionScope(claim, emptySet())
            val runtime = ScheduledTurnRuntime(KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false },
                KotlinTurnToolExecutor { _, _ -> error("Unexpected tool") },
                KotlinTurnProvider { _, _ -> flowOf(ChatStreamEvent.Content("answer"), ChatStreamEvent.Done("stop")) }, owner)
            val host = RoomScheduledTurnHost(db, tasks, ScheduledTurnRuntimeFactory { _, _ -> runtime }, { emptySet() }) { 3000 }
            val conversation = host.run(scope) { tasks.recordConversation(claim, it) }
            assertEquals(conversation, tasks.list().single().lastConversationId)
            assertEquals(listOf("hello", "answer"), db.rows().messagesFor(listOf(conversation)).map { it.content })
            assertEquals(null, db.rows().getValue("kotlin_turn_active_v1:$conversation"))
            assertTrue(tasks.finish(claim, null, conversation))
        } finally { ownerJob.cancelAndJoin(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun cancellationWaitsUntilProviderActuallyStops(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-turn-cancel")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.Default)
        try {
            seed(db)
            val tasks = RoomScheduledTasks(db)
            val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            tasks.create(KotlinScheduledTask(id, "Reminder", "hello", "agent", null, 1, 1))
            val (_, claim) = tasks.claimDue(2000).single()
            val scope = tasks.executionScope(claim, emptySet())
            val entered = CompletableDeferred<Unit>(); val stopping = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val provider = KotlinTurnProvider { _, _ -> flow {
                entered.complete(Unit)
                try { awaitCancellation() } finally { stopping.complete(Unit); withContext(NonCancellable) { release.await() } }
            } }
            val runtime = ScheduledTurnRuntime(KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false },
                KotlinTurnToolExecutor { _, _ -> error("Unexpected tool") }, provider, owner)
            val host = RoomScheduledTurnHost(db, tasks, ScheduledTurnRuntimeFactory { _, _ -> runtime }, { emptySet() }) { 3000 }
            val run = async { host.run(scope) { tasks.recordConversation(claim, it) } }
            withTimeout(10_000) { entered.await() }
            val conversation = assertNotNull(tasks.list().single().lastConversationId)
            run.cancel()
            withTimeout(10_000) { stopping.await() }
            assertFalse(run.isCompleted)
            assertNotNull(tasks.active(id))
            release.complete(Unit)
            withTimeout(10_000) { run.join() }
            assertTrue(run.isCancelled)
            assertEquals(null, db.rows().getValue("kotlin_turn_active_v1:$conversation"))
        } finally { ownerJob.cancelAndJoin(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun recursiveToolDeclarationsFailBeforeTheProviderRequest(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-ceiling")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.Default)
        try {
            seed(db)
            val tasks = RoomScheduledTasks(db)
            val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            tasks.create(KotlinScheduledTask(id, "Reminder", "hello", "agent", null, 1, 1))
            val (_, claim) = tasks.claimDue(2000).single()
            val scope = tasks.executionScope(claim, setOf(SCHEDULE_TOOL, "messenger_agents"))
            var calls = 0
            val unsafe = KotlinTurnRequestPreparer { _, _, _, model, _, _ ->
                cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto(model.modelId, emptyList(), tools = listOf(scheduleToolDeclaration()))
            }
            val runtime = ScheduledTurnRuntime(unsafe, KotlinTurnToolPolicy { _, _, _ -> true },
                KotlinTurnToolExecutor { _, _ -> error("Unexpected tool") },
                KotlinTurnProvider { _, _ -> calls++; flowOf(ChatStreamEvent.Done("stop")) }, owner)
            val host = RoomScheduledTurnHost(db, tasks, ScheduledTurnRuntimeFactory { _, _ -> runtime }, { setOf(SCHEDULE_TOOL, "messenger_agents") }) { 3000 }
            assertFailsWith<IllegalStateException> { host.run(scope) { tasks.recordConversation(claim, it) } }
            assertEquals(0, calls)
            val conversation = assertNotNull(tasks.list().single().lastConversationId)
            assertTrue(db.rows().messagesFor(listOf(conversation)).isEmpty())
        } finally { ownerJob.cancelAndJoin(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun scheduledCloudTurnUsesTheSharedSessionGate(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-cloud-turn")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.Default)
        val calls = AtomicInteger()
        val gate = RoomCloudProviderGate.forTesting(db.cloudSync(), KotlinTurnProvider { _, _ ->
            calls.incrementAndGet()
            flowOf(ChatStreamEvent.Content("cloud answer"), ChatStreamEvent.Done("stop"))
        })
        try {
            seedCloud(db)
            val tasks = RoomScheduledTasks(db)
            tasks.create(KotlinScheduledTask("d3bc81a8-0589-49da-8cb1-8dc6a82507c6", "Reminder", "hello", "agent", null, 1, 1))
            val (_, claim) = tasks.claimDue(2000).single()
            val scheduled = tasks.executionScope(claim, emptySet())
            val runtime = ScheduledTurnRuntime(KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false },
                KotlinTurnToolExecutor { _, _ -> error("Unexpected tool") },
                KotlinTurnProvider { _, _ -> error("Built-in requests must go through the shared gate") }, owner, cloudGate = gate)
            val host = RoomScheduledTurnHost(db, tasks, ScheduledTurnRuntimeFactory { _, _ -> runtime }, { emptySet() }) { 3000 }
            val conversation = withTimeout(5000) { host.run(scheduled) { tasks.recordConversation(claim, it) } }
            assertEquals(listOf("hello", "cloud answer"), db.rows().messagesFor(listOf(conversation)).map { it.content })
            assertEquals(1, calls.get())
            assertEquals("account", db.cloudSync().currentSession()?.accountId)
        } finally { ownerJob.cancelAndJoin(); gate.shutdown(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun revokingCloudSessionStopsScheduledRequestWithoutRetry(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-cloud-revoke")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val gate = RoomCloudProviderGate.forTesting(db.cloudSync(), KotlinTurnProvider { _, _ -> flow {
            calls.incrementAndGet(); entered.complete(Unit); awaitCancellation()
        } })
        try {
            val session = seedCloud(db)
            val tasks = RoomScheduledTasks(db)
            tasks.create(KotlinScheduledTask("d3bc81a8-0589-49da-8cb1-8dc6a82507c6", "Reminder", "hello", "agent", null, 1, 1))
            val (_, claim) = tasks.claimDue(2000).single()
            val scheduled = tasks.executionScope(claim, emptySet())
            val runtime = ScheduledTurnRuntime(KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false },
                KotlinTurnToolExecutor { _, _ -> error("Unexpected tool") },
                KotlinTurnProvider { _, _ -> error("Built-in requests must go through the shared gate") }, owner, cloudGate = gate)
            val host = RoomScheduledTurnHost(db, tasks, ScheduledTurnRuntimeFactory { _, _ -> runtime }, { emptySet() }) { 3000 }
            val result = async { runCatching { host.run(scheduled) { tasks.recordConversation(claim, it) } } }
            withTimeout(5000) { entered.await() }
            assertTrue(withTimeout(5000) { gate.revoke(session) })
            assertTrue(withTimeout(5000) { result.await() }.isFailure)
            assertEquals(1, calls.get())
            assertEquals(null, db.cloudSync().currentSession())
            val conversation = assertNotNull(tasks.list().single().lastConversationId)
            assertEquals(null, db.rows().getValue("kotlin_turn_active_v1:$conversation"))
        } finally { ownerJob.cancelAndJoin(); gate.shutdown(); db.close(); dir.toFile().deleteRecursively() }
    }

    private suspend fun seedCloud(db: LocalDatabase): SyncSession {
        seed(db)
        val session = SyncSession("account", "generation")
        val user = CloudUser(id = "account", aiApiKey = "cloud-key")
        val dao = db.cloudSync()
        dao.saveCredentials(StoredCloudSession(session, "messenger_session=cookie", "https://cloud.example", user))
        dao.updateCloudUser(session, user, "https://cloud.example")
        val model = checkNotNull(db.rows().model("m"))
        db.rows().upsertModels(listOf(model.copy(providerId = BUILTIN_PROVIDER_ID)))
        return session
    }

    private suspend fun seed(db: LocalDatabase) {
        val rows = db.rows()
        rows.upsertAgents(listOf(Json.parseToJsonElement("""{"_id":"agent","isDefault":true,"defaultModelId":"m"}""").jsonObject.agentRow(null)))
        rows.upsertProviders(listOf(ProviderRow("p", "Provider", "https://provider.test", "key", "chat", false, 1, 1)))
        rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
    }
}
