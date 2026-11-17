/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ProjectRow
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.repository.room.AcpSessionMutationGate
import cc.ptoe.messenger.data.repository.room.RoomAcpConversationStore
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomAcpBackgroundOwnerTest {
    private class Fence : AcpConfigurationFence {
        private val mutex = Mutex()
        override suspend fun acquire(binding: AcpWorkerBinding): AcpConfigurationLease {
            mutex.lock()
            return AcpConfigurationLease { mutex.unlock() }
        }
        override suspend fun <T> withExclusiveWriter(action: suspend () -> T): T = mutex.withLock { action() }
        suspend fun <T> mutate(action: suspend () -> T): T = mutex.withLock { action() }
        fun held() = mutex.isLocked
    }

    private class Worker(
        private val events: AcpStdioEvents,
        private val waiting: CompletableDeferred<Unit> = CompletableDeferred(),
        private val stopEntered: CompletableDeferred<Unit>? = null,
        private val allowStop: CompletableDeferred<Unit>? = null,
        private val holdPrompt: Boolean = stopEntered != null,
        private val failFirstStop: Boolean = false,
    ) : AcpWorkerProcess, AcpNewSessionBinding {
        var stopped = false
        private var stopAttempts = 0
        override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore) =
            AcpWorkerReady(skillsGatewayAvailable = true, agentSessionId = binding.agentSessionId.ifEmpty { "new-session" })
        override fun bindCreatedSession(binding: AcpWorkerBinding) { check(binding.agentSessionId == "new-session") }
        override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker): JsonObject {
            waiting.complete(Unit)
            if (holdPrompt) awaitCancellation()
            val selected = permissions.request(turnId, listOf(AcpPermissionOption("allow", "Allow", "allow_once")), "Read", "test") {
                events.permission(turnId, it)
            }
            check(selected == "allow")
            events.update(turnId, buildJsonObject { put("sessionUpdate", "agent_message_chunk"); put("content", buildJsonObject { put("text", "done") }) })
            return buildJsonObject { put("type", "Finished") }
        }
        override suspend fun cancelAndConfirm() = false
        override suspend fun stopAndAwait() {
            stopEntered?.complete(Unit); allowStop?.await()
            if (failFirstStop && stopAttempts++ == 0) error("stop proof unavailable")
            stopped = true
        }
    }

    private suspend fun fixture(dir: Path): Pair<LocalDatabase, AcpWorkerBinding> {
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val workspace = Files.createDirectory(dir.resolve("project")).toString()
        db.rows().upsertValues(listOf(KeyValueRow("device_id", "device"), KeyValueRow("device_control_config",
            """{"agents":[{"id":"agent","name":"Agent","enabled":true,"command":"fake","args":[],"env":{}}]}""")))
        db.rows().upsertProjects(listOf(ProjectRow("project", "Project", workspace, "device", 1, 1)))
        val binding = AcpWorkerBinding("acp:one", "agent", "project", workspace, "session", false)
        val setup = object : AcpSessionMutationGate {
            override suspend fun <T> withInactiveSession(id: String, deleting: Boolean, action: suspend () -> T): T = action()
        }
        RoomAcpSessionStore(db, setup).create(binding, "Agent", 1)
        return db to binding
    }

    private val config = AcpWorkerConfiguration("fake", emptyList(), emptyMap(), JsonArray(emptyList()), false, emptySet())
    private val backend = object : AcpGatewayBackend {
        override suspend fun liveTools() = emptyList<AcpGatewayTool>()
        override suspend fun execute(turnId: String, name: String, arguments: JsonObject) = ToolExecutionResult("unavailable", isError = true)
    }
    private fun content() = JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "hello") }))
    private class Foreground {
        val begins = AtomicInteger()
        val ends = AtomicInteger()
        fun acquire(): () -> Unit { begins.incrementAndGet(); return { ends.incrementAndGet(); Unit } }
    }

    private suspend fun owner(db: LocalDatabase, binding: AcpWorkerBinding, lifecycle: RoomManagedShutdown,
        factory: (AcpWorkerBinding, AcpStdioEvents) -> AcpWorkerProcess, source: AcpSessionConfigurationSource = AcpSessionConfigurationSource { binding to config },
        fence: Fence = Fence(), reuse: Boolean = false, afterCommit: suspend () -> Unit = {},
        foreground: () -> (() -> Unit) = { {} }, afterAdmission: suspend () -> Unit = {}, beforeHandoff: suspend () -> Unit = {}) =
        RoomAcpBackgroundOwner.attach(db, Path.of(db.openHelperPathForTest()), lifecycle, AcpPriorOwnerBarrier { }, fence, source,
            AcpPublishedMcpCatalog { emptyList() }, backend, { false }, factory, now = { 1000 }, retainReusableWorkers = reuse,
            afterQuiescentCommit = afterCommit, foreground = foreground, afterAdmission = afterAdmission, beforeHandoff = beforeHandoff)

    private suspend fun LocalDatabase.openHelperPathForTest(): String = useReaderConnection { connection ->
        connection.usePrepared("PRAGMA database_list") { check(it.step()); it.getText(2) }
    }

    @Test fun cancellationDuringAdmissionRetiresUnstartedRequestAndForegroundLease(): Unit = runBlocking {
        checkCancelledAdmission(afterInitialCheck = false)
    }

    @Test fun cancellationBeforeHandoffCannotStartAWorker(): Unit = runBlocking {
        checkCancelledAdmission(afterInitialCheck = true)
    }

    private suspend fun checkCancelledAdmission(afterInitialCheck: Boolean) = kotlinx.coroutines.coroutineScope {
        val dir = Files.createTempDirectory("acp-admission-foreground")
        val (db, binding) = fixture(dir)
        val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
        val admitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val foreground = Foreground()
        val starts = AtomicInteger()
        try {
            val owner = owner(db, binding, lifecycle, { _, events -> starts.incrementAndGet(); Worker(events) },
                foreground = foreground::acquire,
                afterAdmission = { if (!afterInitialCheck) { admitted.complete(Unit); release.await() } },
                beforeHandoff = { if (afterInitialCheck) { admitted.complete(Unit); release.await() } })
            val id = UUID.randomUUID().toString()
            val call = async { owner.start(id, binding.conversationId, content()) }
            withTimeout(5_000) { admitted.await() }
            assertEquals(1, foreground.begins.get())
            assertEquals(0, foreground.ends.get())
            call.cancel()
            release.complete(Unit)
            withTimeout(5_000) { call.join() }
            assertEquals(0, starts.get())
            assertEquals(1, foreground.ends.get())
            assertEquals(AcpAdmission.Existing(AcpAdmissionState.COMPLETED, true), RoomAcpRequestLedger(db).admit(id, binding.conversationId, content()))
            assertEquals(null, RoomAcpRequestLedger(db).activeRequest(binding.conversationId))
            lifecycle.stopAndAwait()
            assertEquals(1, foreground.ends.get())
        } finally { release.complete(Unit); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun detachedObserverCanApproveAndReplayOneDurableTurn(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-owner-turn")
        val (db, binding) = fixture(dir)
        val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
        val foreground = Foreground()
        lateinit var worker: Worker
        try {
            val owner = owner(db, binding, lifecycle, { _, events -> Worker(events).also { worker = it } }, foreground = foreground::acquire)
            var delegated = 0
            val delegate = CoreBridge::class.java.cast(Proxy.newProxyInstance(CoreBridge::class.java.classLoader,
                arrayOf(CoreBridge::class.java)) { _, _, _ -> delegated++; error("Unexpected delegate call") })
            val createdId = "acp:${UUID.randomUUID()}"
            val bridge = KotlinAcpBackgroundBridge(delegate, owner, AcpNewSessionSource { profile, project ->
                AcpNewSessionDraft(binding.copy(conversationId = createdId, profileId = profile, projectId = project, agentSessionId = ""), config, "Agent")
            })
            var catalog: JsonObject? = null
            bridge.controlCommand(UUID.randomUUID().toString(), buildJsonObject { put("action", "catalog") }) { catalog = it }
            assertTrue(checkNotNull(catalog).getValue("agents").jsonArray.any { it.jsonObject["id"]?.jsonPrimitive?.content == "acp:agent" })
            val id = UUID.randomUUID().toString()
            bridge.startBackgroundAcp(id, buildJsonObject { put("action", "prompt"); put("sessionId", binding.conversationId); put("content", content()) })
            val permission = withTimeout(5_000) {
                var found: JsonObject? = null
                while (found == null) {
                    val batch = owner.events(id, 0)
                    found = batch.getValue("events").jsonArray.map { it.jsonObject }.firstOrNull { it["type"]?.jsonPrimitive?.content == "Permission" }
                    if (found == null) delay(10)
                }
                checkNotNull(found)
            }
            assertEquals(id, owner.bySession(binding.conversationId)?.get("requestId")?.jsonPrimitive?.content)
            val option = permission.getValue("options").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
            assertFalse(owner.decide(id, "stale"))
            assertTrue(owner.decide(id, option))
            val done = withTimeout(5_000) {
                var found: JsonObject? = null
                while (found == null) { val batch = owner.events(id, 0); if (batch.getValue("done").jsonPrimitive.boolean) found = batch else delay(10) }
                checkNotNull(found)
            }
            assertTrue(done.getValue("events").jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "Finished" })
            assertEquals(null, owner.bySession(binding.conversationId))
            assertTrue(worker.stopped)
            assertEquals(1, foreground.begins.get())
            assertEquals(1, foreground.ends.get())
            assertFailsWith<IllegalStateException> { owner.start(id, binding.conversationId, content()) }
            assertFailsWith<IllegalStateException> { bridge.controlCancel(id) }
            assertFailsWith<IllegalStateException> { bridge.controlPermission(id, option) }
            assertEquals(0, delegated)
            val messages = Json.parseToJsonElement(db.rows().getValue("local_acp_sessions")!!.value).jsonObject.getValue("acp:one").jsonObject.getValue("messages").jsonArray
            assertEquals(listOf("hello", "done"), messages.map { it.jsonObject.getValue("text").jsonPrimitive.content })
            var history: JsonObject? = null
            bridge.controlCommand(UUID.randomUUID().toString(), buildJsonObject { put("action", "history"); put("sessionId", binding.conversationId) }) { history = it }
            assertEquals(messages, checkNotNull(history).getValue("messages").jsonArray)
            var created: JsonObject? = null
            bridge.controlCommand(UUID.randomUUID().toString(), buildJsonObject {
                put("action", "create"); put("agentId", "acp:agent"); put("projectId", "project")
            }) { created = it }
            assertEquals(createdId, checkNotNull(created).getValue("sessionId").jsonPrimitive.content)
            lifecycle.stopAndAwait()
            assertFailsWith<IllegalStateException> { owner.events(id, 0) }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun shutdownWaitsForWorkerStopAndRetainsExclusiveOwner(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-owner-stop")
        val (db, binding) = fixture(dir)
        val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
        val entered = CompletableDeferred<Unit>(); val stopEntered = CompletableDeferred<Unit>(); val allowStop = CompletableDeferred<Unit>()
        val foreground = Foreground()
        try {
            val owner = owner(db, binding, lifecycle, { _, events -> Worker(events, entered, stopEntered, allowStop) }, foreground = foreground::acquire)
            val id = UUID.randomUUID().toString()
            owner.start(id, binding.conversationId, content())
            withTimeout(5_000) { entered.await() }
            val stopping = async { lifecycle.stopAndAwait() }
            withTimeout(5_000) { stopEntered.await() }
            assertEquals(1, foreground.begins.get())
            assertEquals(0, foreground.ends.get())
            assertFalse(stopping.isCompleted)
            assertFailsWith<IllegalStateException> { AcpExclusiveOwner.acquire(dir.resolve("room.db"), AcpPriorOwnerBarrier { }) }
            assertFailsWith<IllegalStateException> { owner.start(UUID.randomUUID().toString(), binding.conversationId, content()) }
            allowStop.complete(Unit)
            withTimeout(5_000) { stopping.await() }
            assertEquals(1, foreground.ends.get())
            val replacement = AcpExclusiveOwner.acquire(dir.resolve("room.db"), AcpPriorOwnerBarrier { })
            replacement.closeAndAwait { }
            assertFailsWith<IllegalStateException> { owner.events(id, 0) }
        } finally { allowStop.complete(Unit); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun creatingASessionCannotReuseAnUnresolvedLedgerIdentity(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-owner-create")
        val (db, binding) = fixture(dir)
        val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
        var starts = 0
        try {
            val orphan = binding.copy(conversationId = "acp:orphan", agentSessionId = "")
            assertEquals(AcpAdmission.New, RoomAcpRequestLedger(db).admit(UUID.randomUUID().toString(), orphan.conversationId, content()))
            val owner = owner(db, binding, lifecycle, { _, events ->
                starts++
                Worker(events)
            })
            assertFailsWith<IllegalStateException> { owner.create(orphan, config, "New") }
            assertEquals(0, starts)
            lifecycle.stopAndAwait()
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun creationHoldsConfigurationUntilItsWorkerStops(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-create-fence")
        val (db, binding) = fixture(dir)
        val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
        val stopEntered = CompletableDeferred<Unit>(); val allowStop = CompletableDeferred<Unit>()
        val fence = Fence()
        try {
            // Opting in does not retain a worker that supplies no quiescence proof.
            val owner = owner(db, binding, lifecycle, { _, events -> Worker(events, stopEntered = stopEntered, allowStop = allowStop) }, fence = fence, reuse = true)
            val draft = binding.copy(conversationId = "acp:${UUID.randomUUID()}", agentSessionId = "")
            val creating = async { owner.create(draft, config, "New") }
            withTimeout(5_000) { stopEntered.await() }
            val writer = async(start = CoroutineStart.UNDISPATCHED) { fence.mutate { db.rows().upsertProjects(listOf(db.rows().project("project")!!.copy(deviceId = "other"))) } }
            assertFalse(writer.isCompleted)
            assertFalse(creating.isCompleted)
            allowStop.complete(Unit)
            withTimeout(5_000) { creating.await(); writer.await() }
            assertEquals("other", db.rows().project("project")!!.deviceId)
            lifecycle.stopAndAwait()
        } finally { allowStop.complete(Unit); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun promptHoldsConfigurationUntilItsWorkerStops(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-prompt-fence")
        val (db, binding) = fixture(dir)
        val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
        val stopEntered = CompletableDeferred<Unit>(); val allowStop = CompletableDeferred<Unit>()
        val fence = Fence()
        try {
            val owner = owner(db, binding, lifecycle, { _, events -> Worker(events, stopEntered = stopEntered, allowStop = allowStop, holdPrompt = false) }, fence = fence)
            val id = UUID.randomUUID().toString()
            owner.start(id, binding.conversationId, content())
            val permission = withTimeout(5_000) {
                var found: JsonObject? = null
                while (found == null) {
                    found = owner.events(id, 0).getValue("events").jsonArray.map { it.jsonObject }
                        .firstOrNull { it["type"]?.jsonPrimitive?.content == "Permission" }
                    if (found == null) delay(10)
                }
                checkNotNull(found)
            }
            val option = permission.getValue("options").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
            assertTrue(owner.decide(id, option))
            withTimeout(5_000) { stopEntered.await() }
            val writer = async(start = CoroutineStart.UNDISPATCHED) { fence.mutate { true } }
            assertFalse(writer.isCompleted)
            val pending = owner.events(id, 0)
            assertFalse(pending.getValue("done").jsonPrimitive.boolean)
            assertTrue(pending.getValue("events").jsonArray.none { it.jsonObject["type"]?.jsonPrimitive?.content == "Finished" })
            allowStop.complete(Unit)
            withTimeout(5_000) { writer.await() }
            withTimeout(5_000) { while (!owner.events(id, 0).getValue("done").jsonPrimitive.boolean) delay(10) }
            lifecycle.stopAndAwait()
        } finally { allowStop.complete(Unit); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun failedStopKeepsConfigurationFencedUntilShutdownProvesStop(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-failed-stop-fence")
        val (db, binding) = fixture(dir)
        val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
        val fence = Fence()
        try {
            lateinit var worker: Worker
            val owner = owner(db, binding, lifecycle, { _, events -> Worker(events, failFirstStop = true).also { worker = it } }, fence = fence)
            val draft = binding.copy(conversationId = "acp:${UUID.randomUUID()}", agentSessionId = "")
            assertFailsWith<IllegalStateException> { owner.create(draft, config, "New") }
            assertFalse(worker.stopped)
            val writer = async(start = CoroutineStart.UNDISPATCHED) { fence.mutate { true } }
            assertFalse(writer.isCompleted)
            withTimeout(5_000) { lifecycle.stopAndAwait(); writer.await() }
            assertTrue(worker.stopped)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun failedCancellationStopTerminatesReplayWithoutReleasingUncertainRequest(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-unsettled-replay")
        val (db, binding) = fixture(dir)
        val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
        val entered = CompletableDeferred<Unit>()
        val fence = Fence()
        var starts = 0
        lateinit var worker: Worker
        val foreground = Foreground()
        try {
            val owner = owner(db, binding, lifecycle, { _, events ->
                starts++
                Worker(events, waiting = entered, holdPrompt = true, failFirstStop = true).also { worker = it }
            }, fence = fence, foreground = foreground::acquire)
            val id = UUID.randomUUID().toString()
            owner.start(id, binding.conversationId, content())
            withTimeout(5_000) { entered.await() }
            assertTrue(owner.cancel(id))
            val terminal = withTimeout(5_000) {
                var batch = owner.events(id, 0)
                while (!batch.getValue("done").jsonPrimitive.boolean) { delay(10); batch = owner.events(id, 0) }
                batch
            }
            assertTrue(terminal.getValue("events").jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "Error" })
            assertEquals(AcpAdmission.Existing(AcpAdmissionState.UNCERTAIN, true), RoomAcpRequestLedger(db).admit(id, binding.conversationId, content()))
            assertEquals(AcpAdmission.SessionBusy, RoomAcpRequestLedger(db).admit(UUID.randomUUID().toString(), binding.conversationId, content()))
            assertFailsWith<IllegalStateException> { owner.start(id, binding.conversationId, content()) }
            assertEquals(1, starts)
            val writer = async(start = CoroutineStart.UNDISPATCHED) { fence.mutate { true } }
            assertFalse(writer.isCompleted)
            assertTrue(owner.hasActiveTasks())
            assertEquals(1, foreground.begins.get())
            assertEquals(0, foreground.ends.get())
            val barrier = object : AcpRecoveryBarrier {
                override suspend fun <T> withStopped(requestId: String, sessionId: String, action: suspend () -> T): T {
                    worker.stopAndAwait()
                    return action()
                }
            }
            withTimeout(5_000) {
                while (true) {
                    try { assertEquals(AcpAdmissionState.INTERRUPTED, owner.recover(id, binding.conversationId, barrier)); break }
                    catch (failure: IllegalStateException) {
                        if (failure.message != "ACP worker is still active") throw failure
                        delay(10)
                    }
                }
                writer.await()
            }
            assertFalse(owner.hasActiveTasks())
            assertEquals(1, foreground.ends.get())
            assertEquals(1, starts)
            lifecycle.stopAndAwait()
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun verifiedReusableWorkerKeepsNewSessionAcrossTurnsWithoutSessionLoad(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-reusable-session")
        val (db, original) = fixture(dir)
        val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
        val fence = Fence()
        var bound: AcpWorkerBinding? = null
        var starts = 0
        var prompts = 0
        var initialProofs = 0
        var turnProofs = 0
        var stops = 0
        try {
            val owner = owner(db, original, lifecycle, { _, events ->
                object : AcpWorkerProcess, AcpNewSessionBinding, AcpReusableWorkerProof {
                    override val supportsReusableTurns = true
                    override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore): AcpWorkerReady {
                        check(binding.agentSessionId.isEmpty()) { "This agent cannot load an existing session" }
                        starts++
                        return AcpWorkerReady(true, "new-session")
                    }
                    override fun bindCreatedSession(binding: AcpWorkerBinding) { bound = binding }
                    override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker): JsonObject {
                        prompts++
                        events.update(turnId, buildJsonObject { put("sessionUpdate", "agent_message_chunk"); put("content", buildJsonObject { put("text", "answer-$prompts") }) })
                        return buildJsonObject { put("type", "Finished") }
                    }
                    override suspend fun requireInitialQuiescence() { initialProofs++ }
                    override suspend fun requireTurnQuiescence(turnId: String) { turnProofs++ }
                    override suspend fun cancelAndConfirm() = false
                    override suspend fun stopAndAwait() { stops++ }
                }
            }, source = AcpSessionConfigurationSource { checkNotNull(bound) to config }, fence = fence, reuse = true)
            val draft = original.copy(conversationId = "acp:${UUID.randomUUID()}", agentSessionId = "")
            val created = owner.create(draft, config, "Agent")
            assertEquals(created, bound)
            assertEquals(1, starts)
            assertEquals(1, initialProofs)
            assertEquals(0, stops)
            assertTrue(fence.held())
            repeat(2) { index ->
                val id = UUID.randomUUID().toString()
                owner.start(id, created.conversationId, content())
                val done = withTimeout(5_000) {
                    var batch = owner.events(id, 0)
                    while (!batch.getValue("done").jsonPrimitive.boolean) { delay(10); batch = owner.events(id, 0) }
                    batch
                }
                assertTrue(done.getValue("events").jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "Finished" })
                assertEquals(index + 1, turnProofs)
                assertTrue(fence.held())
            }
            assertEquals(1, starts)
            assertEquals(2, prompts)
            assertEquals(0, stops)
            assertTrue(owner.hasActiveTasks()) // An idle worker still requires its process lifetime.
            assertTrue(withTimeout(5_000) { owner.withConfigurationWrite { true } })
            assertEquals(1, stops)
            assertFalse(owner.hasActiveTasks())
            lifecycle.stopAndAwait()
            assertEquals(1, stops)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun waitingConfigurationWriterPreventsActiveTurnFromRetainingWorker(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-writer-active-turn")
        val (db, binding) = fixture(dir)
        val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var proofs = 0
        var stops = 0
        try {
            val owner = owner(db, binding, lifecycle, { _, _ ->
                object : AcpWorkerProcess, AcpReusableWorkerProof {
                    override val supportsReusableTurns = true
                    override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore) = AcpWorkerReady(true)
                    override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker): JsonObject {
                        started.complete(Unit)
                        finish.await()
                        return buildJsonObject { put("type", "Finished") }
                    }
                    override suspend fun requireInitialQuiescence() = Unit
                    override suspend fun requireTurnQuiescence(turnId: String) { proofs++ }
                    override suspend fun cancelAndConfirm() = false
                    override suspend fun stopAndAwait() { stops++ }
                }
            }, reuse = true)
            val id = UUID.randomUUID().toString()
            owner.start(id, binding.conversationId, content())
            withTimeout(5_000) { started.await() }
            val writer = async(start = CoroutineStart.UNDISPATCHED) { owner.withConfigurationWrite { true } }
            assertFalse(writer.isCompleted)
            val failure = assertFailsWith<IllegalStateException> { owner.start(UUID.randomUUID().toString(), binding.conversationId, content()) }
            assertEquals("ACP configuration is being changed", failure.message)
            finish.complete(Unit)
            assertTrue(withTimeout(5_000) { writer.await() })
            assertEquals(0, proofs)
            assertEquals(1, stops)
            assertEquals(null, RoomAcpRequestLedger(db).activeRequest(binding.conversationId))
            assertFalse(owner.hasActiveTasks())
            lifecycle.stopAndAwait()
        } finally { finish.complete(Unit); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun deleteWaitsForRetainedWorkerPublicationAfterLedgerCommit(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-delete-after-commit")
        val (db, binding) = fixture(dir)
        val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
        val committed = CompletableDeferred<Unit>()
        val publish = CompletableDeferred<Unit>()
        var stops = 0
        try {
            val owner = owner(db, binding, lifecycle, { _, _ ->
                object : AcpWorkerProcess, AcpReusableWorkerProof {
                    override val supportsReusableTurns = true
                    override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore) = AcpWorkerReady(true)
                    override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker) = buildJsonObject { put("type", "Finished") }
                    override suspend fun requireInitialQuiescence() = Unit
                    override suspend fun requireTurnQuiescence(turnId: String) = Unit
                    override suspend fun cancelAndConfirm() = false
                    override suspend fun stopAndAwait() { stops++ }
                }
            }, reuse = true, afterCommit = { committed.complete(Unit); publish.await() })
            owner.start(UUID.randomUUID().toString(), binding.conversationId, content())
            withTimeout(5_000) { committed.await() }
            assertEquals(null, RoomAcpRequestLedger(db).activeRequest(binding.conversationId))
            val delete = async(start = CoroutineStart.UNDISPATCHED) { RoomAcpConversationStore(db, owner.mutationGate).delete(binding.conversationId) }
            assertFalse(delete.isCompleted)
            assertEquals(0, stops)
            publish.complete(Unit)
            withTimeout(5_000) { delete.await() }
            assertEquals(1, stops)
            val sessions = Json.parseToJsonElement(checkNotNull(db.rows().getValue("local_acp_sessions")).value).jsonObject
            assertFalse(binding.conversationId in sessions)
            lifecycle.stopAndAwait()
        } finally { publish.complete(Unit); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun failedTurnQuiescenceRetainsLeaseAndUncertainRequestUntilProvenStop(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-reusable-proof-failure")
        val (db, binding) = fixture(dir)
        val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
        val fence = Fence()
        var starts = 0
        var stops = 0
        lateinit var worker: AcpWorkerProcess
        try {
            val owner = owner(db, binding, lifecycle, { _, _ ->
                object : AcpWorkerProcess, AcpReusableWorkerProof {
                    override val supportsReusableTurns = true
                    override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore): AcpWorkerReady { starts++; return AcpWorkerReady(true) }
                    override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker) = buildJsonObject { put("type", "Finished") }
                    override suspend fun requireInitialQuiescence() = Unit
                    override suspend fun requireTurnQuiescence(turnId: String) { error("side effects cannot be proven stopped") }
                    override suspend fun cancelAndConfirm() = false
                    override suspend fun stopAndAwait() { stops++ }
                }.also { worker = it }
            }, fence = fence, reuse = true)
            val id = UUID.randomUUID().toString()
            owner.start(id, binding.conversationId, content())
            val done = withTimeout(5_000) {
                var batch = owner.events(id, 0)
                while (!batch.getValue("done").jsonPrimitive.boolean) { delay(10); batch = owner.events(id, 0) }
                batch
            }
            assertTrue(done.getValue("events").jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "Error" })
            assertEquals(AcpAdmission.Existing(AcpAdmissionState.UNCERTAIN, true), RoomAcpRequestLedger(db).admit(id, binding.conversationId, content()))
            assertEquals(AcpAdmission.SessionBusy, RoomAcpRequestLedger(db).admit(UUID.randomUUID().toString(), binding.conversationId, content()))
            val writer = async(start = CoroutineStart.UNDISPATCHED) { fence.mutate { true } }
            assertFalse(writer.isCompleted)
            assertTrue(owner.hasActiveTasks())
            assertEquals(1, starts)
            assertEquals(0, stops)
            val barrier = object : AcpRecoveryBarrier {
                override suspend fun <T> withStopped(requestId: String, sessionId: String, action: suspend () -> T): T {
                    worker.stopAndAwait()
                    return action()
                }
            }
            withTimeout(5_000) {
                while (true) {
                    try { assertEquals(AcpAdmissionState.INTERRUPTED, owner.recover(id, binding.conversationId, barrier)); break }
                    catch (failure: IllegalStateException) {
                        if (failure.message != "ACP worker is still active") throw failure
                        delay(10)
                    }
                }
                writer.await()
            }
            assertEquals(2, stops)
            assertFalse(owner.hasActiveTasks())
            lifecycle.stopAndAwait()
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
