/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.cloud.RoomCloudProviderGate
import cc.ptoe.messenger.data.chat.KotlinProviderRequestPreparer
import cc.ptoe.messenger.data.chat.KotlinTurnToolPolicy
import cc.ptoe.messenger.data.chat.RoomKotlinTurnStore
import cc.ptoe.messenger.data.local.artifact.RoomArtifactService
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.asset.RoomAssetGate
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.local.schedule.KotlinScheduledTask
import cc.ptoe.messenger.data.local.schedule.RoomScheduledTasks
import cc.ptoe.messenger.data.local.schedule.ScheduledRecoveryBarrier
import cc.ptoe.messenger.data.local.schedule.RoomScheduledTurnHost
import cc.ptoe.messenger.data.local.schedule.RoomScheduledTaskRunner
import cc.ptoe.messenger.data.local.schedule.ScheduledTurnRuntime
import cc.ptoe.messenger.data.local.schedule.ScheduledTurnRuntimeFactory
import cc.ptoe.messenger.data.local.schedule.ScheduledTaskLifecycle
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.format.ApiFormatRequests
import cc.ptoe.messenger.data.repository.LearningOwnership
import cc.ptoe.messenger.domain.model.ApiFormat
import cc.ptoe.messenger.domain.chat.KotlinTurnProvider
import cc.ptoe.messenger.domain.chat.KotlinTurnAdmission
import cc.ptoe.messenger.domain.chat.KotlinTurnCommand
import cc.ptoe.messenger.domain.chat.KotlinScheduledTurnClaim
import cc.ptoe.messenger.domain.chat.KotlinChatTurnRunner
import cc.ptoe.messenger.domain.chat.KotlinTurnToolExecutor
import cc.ptoe.messenger.domain.chat.KotlinTurnPhase
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class RoomScheduledProjectTest {
    @Test fun cleanupFailureKeepsClaimEvenIfTaskWasDeleted(): Unit = runBlocking {
        for (delete in listOf(false, true)) {
            val dir = Files.createTempDirectory("scheduled-project-claim-retention")
            val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
            val failures = Channel<String>(Channel.UNLIMITED)
            val job = SupervisorJob()
            val owner = CoroutineScope(job + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                val rows = db.rows()
                rows.upsertAgents(listOf(Json.parseToJsonElement("""{"_id":"agent","isDefault":true,"defaultModelId":"m","toolsEnabled":false}""").jsonObject.agentRow(null)))
                rows.upsertProviders(listOf(ProviderRow("p", "Provider", "https://example.test", "key", "chat", false, 1, 1)))
                rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
                rows.upsertValues(listOf(KeyValueRow("device_id", "local")))
                rows.upsertProjects(listOf(ProjectRow("project", "Project", "/workspace", "local", 1, 1)))
                val tasks = RoomScheduledTasks(db)
                val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
                tasks.create(KotlinScheduledTask(id, "Project", "hello", "agent", "project", 1, 1))
                val runtime = ScheduledTurnRuntime(KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false },
                    KotlinTurnToolExecutor { _, _ -> error("No tools") }, KotlinTurnProvider { _, _ -> flow {
                        emit(ChatStreamEvent.Content("partial")); entered.complete(Unit); release.await(); emit(ChatStreamEvent.Done("stop"))
                    } }, owner)
                val host = RoomScheduledTurnHost(db, tasks, ScheduledTurnRuntimeFactory { _, _ -> runtime }, { emptySet() }) { 1000 }
                val runner = RoomScheduledTaskRunner(tasks, host, { emptySet() }, ScheduledRecoveryBarrier { }, owner, { 1000 },
                    object : ScheduledTaskLifecycle { override fun schedulerFailed(message: String) { failures.trySend(message) } })
                runner.tick()
                withTimeout(10_000) { entered.await() }
                val conversation = assertNotNull(tasks.list().single().lastConversationId)
                val before = rows.messagesFor(listOf(conversation))
                rows.upsertValues(listOf(KeyValueRow(CLOUD_REPLACEMENT_LOCK_KEY, "replacement")))
                if (delete) tasks.delete(id)
                release.complete(Unit)
                assertTrue(withTimeout(10_000) { failures.receive() }.contains("requires recovery"))
                val retained = assertNotNull(tasks.active(id))
                assertNotNull(rows.getValue("kotlin_turn_active_v1:$conversation"))
                assertEquals(before, rows.messagesFor(listOf(conversation)))
                assertFailsWith<IllegalStateException> { tasks.recoverInterrupted() }
                assertNotNull(tasks.active(id))
                // An already-orphaned active turn also blocks cleanup and future scheduler recovery.
                rows.deleteValue("kotlin_scheduled_active_v1:$id")
                assertFailsWith<IllegalStateException> { tasks.finishWithStatus(retained, "interrupted", conversation) }
                assertFailsWith<IllegalStateException> { tasks.recoverInterrupted() }
                val activeTurn = assertNotNull(rows.getValue("kotlin_turn_active_v1:$conversation")).value
                val turnKey = "kotlin_turn_v1:$activeTurn"
                val original = Json.parseToJsonElement(assertNotNull(rows.getValue(turnKey)).value).jsonObject
                val originalClaim = original.getValue("scheduledClaim").jsonObject
                for ((field, invalid) in listOf("taskId" to JsonPrimitive("bad"), "token" to JsonPrimitive("bad"), "startedAt" to JsonPrimitive(0))) {
                    val damaged = JsonObject(original + ("scheduledClaim" to JsonObject(originalClaim + (field to invalid))))
                    rows.upsertValues(listOf(KeyValueRow(turnKey, damaged.toString())))
                    assertFailsWith<IllegalStateException> { tasks.recoverInterrupted() }
                    assertNotNull(rows.getValue("kotlin_turn_active_v1:$conversation"))
                }
                rows.upsertValues(listOf(KeyValueRow(turnKey, original.toString())))
            } finally { release.complete(Unit); job.cancelAndJoin(); db.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun replacementLockKeepsCompletedAnswerAndTurnForRecovery(): Unit = runBlocking {
        val dir = Files.createTempDirectory("scheduled-project-replacement")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val job = SupervisorJob()
        val failures = Channel<Throwable>(Channel.UNLIMITED)
        val scope = CoroutineScope(job + Dispatchers.Default + CoroutineExceptionHandler { _, error -> failures.trySend(error) })
        try {
            val rows = db.rows()
            rows.upsertAgents(listOf(
                Json.parseToJsonElement("""{"_id":"agent","isDefault":true,"defaultModelId":"m","toolsEnabled":false}""").jsonObject.agentRow(null),
                Json.parseToJsonElement("""{"_id":"title-agent","role":"title","defaultModelId":"m"}""").jsonObject.agentRow(null)))
            rows.upsertProviders(listOf(ProviderRow("p", "Provider", "https://example.test", "key", "chat", false, 1, 1)))
            rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
            rows.upsertValues(listOf(KeyValueRow("device_id", "local")))
            rows.upsertProjects(listOf(ProjectRow("project", "Project", "/workspace", "local", 1, 1)))
            val tasks = RoomScheduledTasks(db)
            val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            tasks.create(KotlinScheduledTask(id, "Project", "hello", "agent", "project", 1, 1))
            val claim = tasks.claimDue(1000).single().second
            val scheduled = tasks.executionScope(claim, emptySet())
            val conversation = Json.parseToJsonElement("""{"_id":"conversation","title":"New Chat","agentId":"agent","projectId":"project","writable":true}""").jsonObject.conversationRow()
            assertTrue(tasks.publishConversation(scheduled, "conversation") { it.upsertConversations(listOf(conversation)) })
            val store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false })
            val admitted = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("conversation", "hello", null,
                scheduledRun = true, scheduledClaim = KotlinScheduledTurnClaim(id, claim.token, claim.startedAt, "project"))))
            assertTrue(store.claim(admitted.turnId, false))
            store.beginProvider(admitted.turnId)
            store.appendContent(admitted.turnId, "completed answer")
            store.completeProvider(admitted.turnId, emptyList(), null)
            assertEquals(KotlinTurnPhase.TITLE_PENDING, store.snapshot(admitted.turnId).phase)
            store.release(admitted.turnId)
            val beforeMessages = rows.messagesFor(listOf("conversation"))
            val beforeConversation = rows.conversation("conversation")
            // Replacement takes precedence even when the claim was already cancelled.
            tasks.update(tasks.list().single().copy(enabled = false))
            rows.upsertValues(listOf(KeyValueRow(CLOUD_REPLACEMENT_LOCK_KEY, "replacement")))
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> error("Provider must not be called") },
                KotlinTurnToolExecutor { _, _ -> error("Tool must not be called") }, scope)
            runner.recover(admitted.turnId)
            withTimeout(10_000) { failures.receive() }
            withTimeout(10_000) { runner.awaitStopped(admitted.turnId) }
            assertEquals(beforeMessages, rows.messagesFor(listOf("conversation")))
            assertEquals(beforeConversation, rows.conversation("conversation"))
            assertEquals(KotlinTurnPhase.TITLE_PENDING, store.snapshot(admitted.turnId).phase)
            assertEquals(admitted.turnId, rows.getValue("kotlin_turn_active_v1:conversation")?.value)
            assertFailsWith<IllegalStateException> { store.cancel(admitted.turnId) }
            assertFailsWith<IllegalStateException> { store.fail(admitted.turnId, "failure") }
        } finally { job.cancelAndJoin(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun projectAndCloudAuthorityAreRecheckedBeforeProviderDispatch(): Unit = runBlocking {
        for (change in listOf("device", "project", "association", "replacement", "native-search")) {
            val dir = Files.createTempDirectory("scheduled-project-authority")
            val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
            try {
                val rows = db.rows()
                rows.upsertAgents(listOf(Json.parseToJsonElement("""{"_id":"agent","isDefault":true,"defaultModelId":"m","toolsEnabled":false}""").jsonObject.agentRow(null)))
                rows.upsertProviders(listOf(ProviderRow("p", "Provider", "https://example.test", "key", "chat", false, 1, 1)))
                rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
                rows.upsertValues(listOf(KeyValueRow("device_id", "local")))
                val project = ProjectRow("project", "Project", "/workspace", "local", 1, 1)
                rows.upsertProjects(listOf(project))
                val tasks = RoomScheduledTasks(db)
                val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
                tasks.create(KotlinScheduledTask(id, "Project", "hello", "agent", "project", 1, 1))
                val claim = tasks.claimDue(1000).single().second
                val scheduled = tasks.executionScope(claim, emptySet())
                val conversation = Json.parseToJsonElement("""{"_id":"conversation","agentId":"agent","projectId":"project","writable":true}""").jsonObject.conversationRow()
                assertTrue(tasks.publishConversation(scheduled, "conversation") { it.upsertConversations(listOf(conversation)) })
                val store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false })
                val admission = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("conversation", "hello", null,
                    scheduledRun = true, scheduledClaim = KotlinScheduledTurnClaim(id, claim.token, claim.startedAt, "project"))))
                assertTrue(store.claim(admission.turnId, false))
                when (change) {
                    "device" -> rows.upsertValues(listOf(KeyValueRow("device_id", "other")))
                    "project" -> rows.upsertProjects(listOf(project.copy(deviceId = "other")))
                    "association" -> rows.upsertConversations(listOf(conversation.copy(projectId = null)))
                    "replacement" -> rows.upsertValues(listOf(KeyValueRow(CLOUD_REPLACEMENT_LOCK_KEY, "replacement")))
                    else -> rows.upsertProviders(listOf(checkNotNull(rows.provider("p")).copy(apiFormat = "responses", nativeWebSearch = true)))
                }
                if (change == "replacement") assertFailsWith<IllegalStateException> { store.beginProvider(admission.turnId) }
                else assertFailsWith<CancellationException> { store.beginProvider(admission.turnId) }
            } finally { db.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun localProjectRunsWithNoToolsButForeignOrUnsupportedWorkspaceIsRejected(): Unit = runBlocking {
        for (scenario in listOf("local", "foreign", "workspace-tool", "responses-search", "messages-search")) {
            val dir = Files.createTempDirectory("scheduled-project")
            if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) {
                dir.toFile().deleteRecursively()
                return@runBlocking
            }
            val root = Files.createDirectory(dir.resolve("assets"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.Default)
            val calls = PartialChatCalls()
            val assetGate = RoomAssetGate()
            val cloudGate = RoomCloudProviderGate.forDao(db.cloudSync())
            try {
                val rows = db.rows()
                val enabled = scenario == "workspace-tool"
                rows.upsertAgents(listOf(Json.parseToJsonElement("""{"_id":"agent","isDefault":true,"defaultModelId":"m","toolsEnabled":$enabled}""").jsonObject.agentRow(null)))
                val search = scenario.endsWith("-search")
                val format = when (scenario) { "responses-search" -> "responses"; "messages-search" -> "messages"; else -> "chat" }
                if (search) {
                    // These formats add a provider-side tool even when the chat DTO has no tools.
                    val wireFormat = if (format == "responses") ApiFormat.RESPONSES else ApiFormat.MESSAGES
                    val wire = ApiFormatRequests.encode(wireFormat, ChatCompletionRequestDto("model", emptyList()), true)
                    assertTrue(wire.getValue("tools").jsonArray.any { it.jsonObject.getValue("type").jsonPrimitive.content.startsWith("web_search") })
                }
                rows.upsertProviders(listOf(ProviderRow("p", "Provider", "https://example.test", "key", format, search, 1, 1)))
                rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
                rows.upsertValues(listOf(KeyValueRow("device_id", "local")))
                rows.upsertProjects(listOf(ProjectRow("project", "Project", "/workspace", if (scenario == "foreign") "remote" else "local", 1, 1)))
                val tasks = RoomScheduledTasks(db)
                val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
                val task = KotlinScheduledTask(id, "Project task", "hello", "agent", "project", 1, 1000,
                    allowedTools = if (enabled) listOf("read") else null)
                // Import the same records Core persisted, including a foreign project and an unsupported ceiling.
                rows.upsertValues(listOf(KeyValueRow("scheduled_task:$id", Json.encodeToString(task))))
                val assets = CoreAssetService(root, db.assetMetadata(), assetGate)
                val status = Channel<String>(Channel.UNLIMITED)
                val providerCalls = AtomicInteger()
                val scheduler = IsolatedRoomScheduler(db, tasks, LearningOwnership(db, assets), RoomArtifactService(db, assets),
                    cloudGate, scope, ScheduledRecoveryBarrier { }, { 1000 }, calls,
                    taskStatus = { _, state -> status.trySend(state) }, providerOverride = KotlinTurnProvider { _, request ->
                        providerCalls.incrementAndGet()
                        assertTrue(request.tools.isNullOrEmpty())
                        flowOf(ChatStreamEvent.Content("answer"), ChatStreamEvent.Done("stop"))
                    })
                scheduler.start()
                scheduler.tick()
                val result = withTimeout(10_000) { status.receive() }
                val saved = tasks.list().single()
                if (scenario == "local") {
                    assertEquals("completed", result)
                    assertEquals(1, providerCalls.get())
                    val conversation = assertNotNull(saved.lastConversationId)
                    assertEquals("project", rows.conversation(conversation)?.projectId)
                    assertEquals(listOf("hello", "answer"), rows.messagesFor(listOf(conversation)).map { it.content })
                } else {
                    assertEquals("failed", result)
                    assertEquals(0, providerCalls.get())
                    if (search) {
                        val conversation = assertNotNull(saved.lastConversationId)
                        assertTrue(rows.messagesFor(listOf(conversation)).isEmpty())
                    } else {
                        assertNull(saved.lastConversationId)
                        assertTrue(rows.observeAllConversations().first().isEmpty())
                    }
                    assertNotNull(saved.lastError)
                }
            } finally {
                calls.stopAndJoin(); job.cancelAndJoin(); cloudGate.shutdown(); assetGate.shutdown(); db.close(); dir.toFile().deleteRecursively()
            }
        }
    }
}
