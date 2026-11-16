/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.useReaderConnection
import androidx.room.Transactor
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.repository.room.AcpSessionMutationGate
import cc.ptoe.messenger.data.repository.room.RoomAcpConversationStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/**
 * All profile, MCP, capability, project and Cloud writers must honor the same lease.
 * A production fence supporting multiple retained workers must admit shared reader leases.
 * All writers must enter through the ACP owner's [RoomAcpBackgroundOwner.withConfigurationWrite].
 */
internal interface AcpConfigurationFence {
    /** Cancellation before return must not leave an acquired lease behind. */
    suspend fun acquire(binding: AcpWorkerBinding): AcpConfigurationLease
    /** Exclusive writer access is entered only after the owner drains its retained workers. */
    suspend fun <T> withExclusiveWriter(action: suspend () -> T): T = error("ACP configuration writes are unavailable")
}

/** Release is only permitted after stop proof; a failed release must retain writer exclusion. */
internal fun interface AcpConfigurationLease { suspend fun release() }

/** Supplies an in-memory snapshot; the owner independently verifies it against Room. */
internal fun interface AcpSessionConfigurationSource {
    suspend fun load(sessionId: String): Pair<AcpWorkerBinding, AcpWorkerConfiguration>
}

/** Resolves a private snapshot; Room authority validates it again before creating the worker. */
internal data class AcpNewSessionDraft(val binding: AcpWorkerBinding, val configuration: AcpWorkerConfiguration, val title: String)
internal fun interface AcpNewSessionSource {
    suspend fun load(profileId: String, projectId: String): AcpNewSessionDraft
}

/**
 * Isolated ACP background owner. The caller holds the enclosing Room owner and must ensure all
 * ACP workers use this owner, all configuration/workspace writers use [withConfigurationWrite], and
 * the worker factory's stop method proves that the process and its side effects cannot resume.
 * This does not install a platform worker or supply a production stop proof.
 */
internal class RoomAcpBackgroundOwner private constructor(
    private val db: LocalDatabase,
    private val exclusive: AcpExclusiveOwner,
    private val ledger: RoomAcpRequestLedger,
    private val manager: AcpWorkerManager,
    private val coordinator: AcpTurnCoordinator,
    private val authority: AcpNewSessionAuthority,
    private val configurationFence: AcpConfigurationFence,
    private val source: AcpSessionConfigurationSource,
    private val now: () -> Long,
    private val retainReusableWorkers: Boolean,
    private val afterQuiescentCommit: suspend () -> Unit,
) {
    private class Task(val id: String, val sessionId: String) {
        lateinit var job: Job
        @Volatile var finished = false
        @Volatile var warning: String? = null
        @Volatile var cancelled = false
    }

    private val lock = Any()
    private val admissions = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calls = mutableSetOf<Job>()
    private val liveTasks = mutableSetOf<Job>()
    private val tasks = linkedMapOf<String, Task>()
    private val sessions = mutableSetOf<String>()
    private val leases = mutableMapOf<String, AcpConfigurationLease>()
    private val retained = mutableSetOf<String>()
    private var pendingConfigurationWriters = 0
    private var closing = false
    private val observation = AcpUiObservationBridge(coordinator, manager)

    /** ACP session deletion and updates must use this gate, not a separate manager or DB. */
    val mutationGate: AcpSessionMutationGate = object : AcpSessionMutationGate {
        override suspend fun <T> withInactiveSession(id: String, deleting: Boolean, action: suspend () -> T): T = admitted {
            admissions.withLock {
                synchronized(lock) { check(pendingConfigurationWriters == 0) { "ACP configuration is being changed" } }
                exclusive.withOwner {
                    check(ledger.activeRequest(id) == null) { "ACP session has an unresolved request" }
                    if (synchronized(lock) { id in retained }) {
                        withContext(NonCancellable) { manager.retireAndAwait(id); releaseLease(id) }
                    }
                    manager.withInactiveSession(id, deleting, action)
                }
            }
        }
    }

    private suspend fun <T> admitted(block: suspend () -> T): T = coroutineScope {
        val job = checkNotNull(currentCoroutineContext()[Job])
        synchronized(lock) {
            check(!closing) { "ACP owner is shutting down" }
            calls.add(job)
        }
        job.invokeOnCompletion { synchronized(lock) { calls.remove(job) } }
        block()
    }

    private suspend fun acquireLease(binding: AcpWorkerBinding) {
        check(synchronized(lock) { binding.conversationId !in leases }) { "ACP worker stop is unresolved" }
        val lease = configurationFence.acquire(binding)
        val installed = synchronized(lock) {
            if (binding.conversationId in leases) false else { leases[binding.conversationId] = lease; true }
        }
        if (!installed) {
            withContext(NonCancellable) { lease.release() }
            error("ACP worker stop is unresolved")
        }
    }

    /** A retained worker already holds this exact session's configuration lease. */
    private suspend fun acquireOrReuseLease(binding: AcpWorkerBinding) {
        if (synchronized(lock) { binding.conversationId in retained && binding.conversationId in leases }) return
        acquireLease(binding)
    }

    /** Call only after the manager has proved this session's worker stopped. */
    private suspend fun releaseLease(sessionId: String) {
        val lease = synchronized(lock) { leases[sessionId] } ?: return
        lease.release()
        synchronized(lock) {
            if (leases[sessionId] === lease) { leases.remove(sessionId); retained.remove(sessionId) }
        }
    }

    /** Creates a local session only after the live project/profile and MCP snapshot are verified. */
    suspend fun create(draft: AcpWorkerBinding, configuration: AcpWorkerConfiguration, title: String): AcpWorkerBinding = admitted {
        admissions.withLock {
          synchronized(lock) { check(pendingConfigurationWriters == 0) { "ACP configuration is being changed" } }
          exclusive.withOwner {
            check(ledger.activeRequest(draft.conversationId) == null) { "ACP session has an unresolved request" }
            requireSupportedScope(configuration)
            synchronized(lock) { sessions.add(draft.conversationId) }
            acquireLease(draft)
            var keep = false
            try {
                val created = AcpSessionCreator(manager, RoomAcpSessionStore(db, manager), authority, now).create(draft, configuration, title)
                if (retainReusableWorkers && manager.retainCreatedWorker(draft.conversationId)) {
                    synchronized(lock) { retained.add(draft.conversationId) }
                    keep = true
                }
                created
            } finally {
                if (!keep) withContext(NonCancellable) {
                    manager.retireAndAwait(draft.conversationId)
                    releaseLease(draft.conversationId)
                }
            }
          }
        }
    }

    suspend fun createFrom(profileId: String, projectId: String, source: AcpNewSessionSource): AcpWorkerBinding = admitted {
        require(profileId.isNotBlank() && projectId.isNotBlank()) { "ACP profile and project are required" }
        val draft = source.load(profileId, projectId)
        val id = draft.binding.conversationId.removePrefix("acp:")
        check(draft.binding.conversationId.startsWith("acp:") && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) &&
            draft.binding.profileId == profileId && draft.binding.projectId == projectId && draft.binding.agentSessionId.isEmpty() && !draft.binding.delegated) {
            "ACP new-session identity changed"
        }
        create(draft.binding, draft.configuration, draft.title)
    }

    /** Admission is durable before the background job is published; detach never cancels it. */
    suspend fun start(requestId: String, sessionId: String, content: JsonArray) = admitted {
        require(runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false)) { "Invalid ACP request ID" }
        require(sessionId.startsWith("acp:") && content.isNotEmpty()) { "Invalid ACP prompt" }
        currentCoroutineContext().ensureActive()
        val frozenContent = Json.parseToJsonElement(content.toString()).jsonArray
        withContext(NonCancellable) {
            admissions.withLock {
                synchronized(lock) { check(pendingConfigurationWriters == 0) { "ACP configuration is being changed" } }
                exclusive.withOwner {
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        check(!closing) { "ACP owner is shutting down" }
                        check(tasks.values.none { it.sessionId == sessionId && !it.finished }) { "ACP session already has an active request" }
                        while (tasks.size >= 1024) {
                            val completed = tasks.entries.firstOrNull { it.value.finished && it.value.job.isCompleted } ?: error("Too many active ACP requests")
                            tasks.remove(completed.key)
                        }
                    }
                    when (val admission = ledger.admit(requestId, sessionId, frozenContent)) {
                        AcpAdmission.New -> Unit
                        is AcpAdmission.Existing -> error(if (admission.sameRequest) "ACP request already admitted; do not resubmit" else "ACP request ID was reused")
                        AcpAdmission.SessionBusy -> error("ACP session has an unresolved request")
                    }
                    val task = Task(requestId, sessionId)
                    task.job = scope.launch(start = CoroutineStart.LAZY) { runTask(task, frozenContent) }
                    synchronized(lock) {
                        tasks[requestId] = task
                        sessions.add(sessionId)
                        liveTasks.add(task.job)
                    }
                    task.job.invokeOnCompletion { synchronized(lock) { liveTasks.remove(task.job) } }
                    task.job.start()
                }
            }
        }
    }

    private suspend fun runTask(task: Task, content: JsonArray) {
        var failed = false
        var completed = false
        var keep = false
        try {
            val (binding, configuration) = source.load(task.sessionId)
            check(binding.conversationId == task.sessionId && !binding.delegated) { "ACP session binding changed" }
            requireSupportedScope(configuration)
            acquireOrReuseLease(binding)
            try {
                authority.verify(binding, configuration)
                val result = manager.prompt(binding, configuration, content, task.id)
                keep = retainReusableWorkers && (result["type"] as? JsonPrimitive)?.contentOrNull == "Finished" &&
                    manager.canReuseWorker(task.sessionId)
            } finally {
                withContext(NonCancellable) {
                    admissions.withLock {
                        keep = keep && synchronized(lock) { pendingConfigurationWriters == 0 }
                        try {
                            if (keep) ledger.complete(task.id, task.sessionId, object : AcpCompletionBarrier {
                                override suspend fun <T> withCompleted(requestId: String, sessionId: String, action: suspend () -> T): T =
                                    manager.withQuiescentTurn(sessionId, requestId, action)
                            }) else ledger.complete(task.id, task.sessionId, AcpManagerStopBarrier(manager))
                            completed = true
                            if (keep) {
                                // The hook permits testing the commit/publication boundary under the real gate.
                                try { afterQuiescentCommit() }
                                finally { synchronized(lock) { retained.add(task.sessionId) } }
                            }
                        } finally {
                            if (!keep || !completed) {
                                // Even if ledger settlement fails, a proven stopped worker permits release.
                                manager.retireAndAwait(task.sessionId)
                                releaseLease(task.sessionId)
                            }
                        }
                    }
                }
            }
        } catch (_: CancellationException) {
            task.cancelled = true
        } catch (_: Exception) {
            failed = true
        } finally {
            withContext(NonCancellable) {
                if (!completed) {
                    synchronized(lock) { retained.remove(task.sessionId) }
                    failed = true
                    try { ledger.markUncertain(task.id, task.sessionId) } catch (_: Exception) { }
                }
                if (failed) {
                    coordinator.abandonUnsettled(task.id)
                    task.warning = "ACP request failed or its outcome is uncertain; do not resubmit automatically"
                }
                task.finished = true
            }
        }
    }

    suspend fun events(requestId: String, after: Long): JsonObject = admitted {
        val task = synchronized(lock) { tasks[requestId] } ?: error("ACP request is unavailable")
        if (!coordinator.contains(requestId)) {
            require(after == 0L) { "Invalid ACP replay cursor" }
            return@admitted buildJsonObject {
                put("requestId", requestId)
                put("events", buildJsonArray {
                    task.warning?.let { warning -> add(buildJsonObject { put("type", "Error"); put("message", warning) }) }
                        ?: if (task.finished && task.cancelled) add(buildJsonObject { put("type", "Cancelled") }) else Unit
                })
                put("nextCursor", 0); put("done", task.finished)
            }
        }
        val snapshot = coordinator.snapshot(requestId, after)
        val finished = task.finished
        val firstTerminal = snapshot.events.firstOrNull {
            (it.event["type"] as? JsonPrimitive)?.contentOrNull in setOf("Finished", "Cancelled", "Error")
        }
        // Do not expose a terminal outcome until worker retirement and ledger settlement finish.
        val visible = if (!finished && firstTerminal != null) snapshot.copy(
            events = snapshot.events.takeWhile { it.sequence < firstTerminal.sequence },
            latestSequence = firstTerminal.sequence - 1,
            completed = false,
        ) else snapshot
        val batch = visible.toUiBatch(requestId)
        val warning = task.warning
        if (!finished) JsonObject(batch + ("done" to kotlinx.serialization.json.JsonPrimitive(false)))
        else if (warning == null) JsonObject(batch + ("done" to kotlinx.serialization.json.JsonPrimitive(true))) else {
            val events = (batch["events"] as? JsonArray).orEmpty().filterNot {
                ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull in setOf("Finished", "Cancelled", "Error")
            } + buildJsonObject { put("type", "Error"); put("message", warning) }
            JsonObject(batch + mapOf("events" to JsonArray(events), "done" to kotlinx.serialization.json.JsonPrimitive(true)))
        }
    }

    suspend fun bySession(sessionId: String): JsonObject? = admitted {
        val task = synchronized(lock) { tasks.values.lastOrNull { it.sessionId == sessionId && !it.finished } }
        if (task != null) return@admitted events(task.id, 0)
        check(ledger.activeRequest(sessionId) == null) { "ACP session has an unresolved request; recovery is required" }
        null
    }

    suspend fun decide(requestId: String, optionId: String): Boolean = admitted { observation.decide(requestId, optionId) }

    /** Includes completed or evicted turns whose IDs remain in the durable admission ledger. */
    suspend fun owns(requestId: String): Boolean = admitted {
        synchronized(lock) { requestId in tasks } || ledger.contains(requestId)
    }

    /** Local transcript pages preserve complete message objects, including tool events. */
    suspend fun history(sessionId: String, before: String?): JsonObject = admitted {
        require(sessionId.startsWith("acp:") && sessionId.length in 5..256) { "Invalid ACP session" }
        val root = db.rows().getValue("local_acp_sessions")?.value?.let { Json.parseToJsonElement(it) as? JsonObject }
            ?: error("ACP sessions are unavailable")
        val record = root[sessionId] as? JsonObject ?: error("ACP session not found")
        check((record["id"] as? JsonPrimitive)?.contentOrNull == sessionId) { "ACP session identity changed" }
        val rows = record["messages"] as? JsonArray ?: error("Invalid ACP transcript")
        val end = before?.let { id -> rows.indexOfFirst { (it as? JsonObject)?.get("id")?.let { field -> (field as? JsonPrimitive)?.contentOrNull } == id }.takeIf { it >= 0 } } ?: rows.size
        val page = rows.subList((end - 20).coerceAtLeast(0), end)
        val next = if (page.size < end) ((page.firstOrNull() as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull else null
        buildJsonObject { put("type", "History"); put("messages", JsonArray(page)); put("before", next?.let(::JsonPrimitive) ?: JsonNull); put("writable", JsonNull) }
    }

    /** An unproven worker retains its lease even after its coroutine exits. */
    suspend fun hasActiveTasks(): Boolean = admitted { synchronized(lock) { liveTasks.any { !it.isCompleted } || leases.isNotEmpty() } }

    /** Local catalog for the existing conversation picker; credentials never leave Room. */
    suspend fun catalog(): JsonObject = admitted {
        db.useReaderConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
            val rows = db.rows()
            val device = rows.getValue("device_id")?.value.orEmpty()
            val agents = rows.controlAgents().map { row -> buildJsonObject {
                put("id", row.id); put("name", row.name); put("kind", "messenger")
            } }
            val config = rows.getValue("device_control_config")?.value?.let {
                require(it.encodeToByteArray().size <= 1024 * 1024) { "ACP settings are too large" }
                Json.parseToJsonElement(it) as? JsonObject ?: error("Invalid ACP settings")
            }
            val profiles = (config?.get("agents") as? JsonArray).orEmpty().mapNotNull { value ->
                val profile = value as? JsonObject ?: error("Invalid ACP profile")
                val id = (profile["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("Invalid ACP profile ID")
                val enabled = profile["enabled"]?.let { (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull
                    ?: error("Invalid ACP profile setting") } ?: true
                if (!enabled) return@mapNotNull null
                val name = (profile["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("Invalid ACP profile name")
                buildJsonObject {
                    put("id", "acp:$id"); put("name", name); put("kind", "acp")
                    put("description", profile["description"] ?: JsonPrimitive("")); put("avatar", profile["avatar"] ?: JsonNull)
                }
            }
            val projects = rows.controlProjects(device).map { row -> buildJsonObject { put("id", row.id); put("name", row.name) } }
            val native = connection.usePrepared("SELECT c.id, c.title, c.projectId, c.updatedAt FROM conversations c LEFT JOIN projects p ON p.id=c.projectId WHERE c.projectId IS NULL OR (p.id IS NOT NULL AND (p.deviceId IS NULL OR p.deviceId=?)) ORDER BY c.updatedAt DESC, c.id LIMIT 100") { stmt ->
                stmt.bindText(1, device)
                buildList { while (stmt.step()) add(buildJsonObject {
                    put("id", stmt.getText(0)); put("title", stmt.getText(1).take(160)); put("kind", "messenger")
                    put("projectId", if (stmt.isNull(2)) JsonNull else JsonPrimitive(stmt.getText(2))); put("updatedAt", stmt.getLong(3))
                }) }
            }
            val stored = rows.getValue("local_acp_sessions")?.value?.let {
                require(it.encodeToByteArray().size <= 16 * 1024 * 1024) { "ACP sessions are too large" }
                Json.parseToJsonElement(it) as? JsonObject ?: error("Invalid ACP sessions")
            } ?: JsonObject(emptyMap())
            val external = stored.map { (id, value) ->
                val record = value as? JsonObject ?: error("Invalid ACP session")
                require(id.startsWith("acp:") && (record["id"] as? JsonPrimitive)?.contentOrNull == id) { "Invalid ACP session identity" }
                val updated = (record["updated_at"] as? JsonPrimitive)?.longOrNull ?: 0L
                updated to buildJsonObject {
                    put("id", id); put("title", (record["title"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(160))
                    put("kind", "acp"); put("projectId", record["project"] ?: JsonNull); put("updatedAt", updated)
                }
            }.sortedByDescending { it.first }.take(100).map { it.second }
            buildJsonObject { put("type", "Catalog"); put("agents", JsonArray((agents + profiles).take(100)))
                put("projects", JsonArray(projects)); put("sessions", JsonArray(native + external)) }
        } }
    }

    suspend fun cancel(requestId: String): Boolean = admitted {
        val task = synchronized(lock) { tasks[requestId] } ?: return@admitted false
        if (task.finished) false else { task.job.cancel(CancellationException("ACP turn cancelled")); true }
    }

    /**
     * All configuration writers use this entry point, rather than acquiring the fence directly.
     * Intent blocks later starts while previously admitted turns finish and their workers retire.
     */
    suspend fun <T> withConfigurationWrite(action: suspend () -> T): T = admitted {
        var registered = false
        try {
            admissions.withLock {
                synchronized(lock) {
                    check(!closing) { "ACP owner is shutting down" }
                    pendingConfigurationWriters++
                    registered = true
                }
            }
            val active = synchronized(lock) { liveTasks.toList() }
            active.joinAll()
            exclusive.withOwner {
                val owned = synchronized(lock) { leases.keys.toList() }
                for (id in owned) {
                    check(ledger.activeRequest(id) == null) { "ACP session has an active or unresolved request" }
                    withContext(NonCancellable) { manager.retireAndAwait(id); releaseLease(id) }
                }
                configurationFence.withExclusiveWriter(action)
            }
        } finally {
            if (registered) withContext(NonCancellable) { synchronized(lock) { pendingConfigurationWriters-- } }
        }
    }

    /** Independent recovery must prove the prior worker and its side effects cannot resume. */
    suspend fun recover(requestId: String, sessionId: String, barrier: AcpRecoveryBarrier): AcpAdmissionState = admitted {
        exclusive.withOwner {
            check(synchronized(lock) { tasks.values.none { it.sessionId == sessionId && !it.job.isCompleted } }) { "ACP worker is still active" }
            val result = ledger.recover(requestId, sessionId, barrier)
            manager.reconcileStopped(sessionId)
            manager.retireAndAwait(sessionId)
            releaseLease(sessionId)
            result
        }
    }

    /** Reject and join entry points and background tasks before proving every worker stopped. */
    suspend fun shutdown() = withContext(NonCancellable) {
        val admittedCalls = synchronized(lock) { closing = true; calls.toList() }
        admittedCalls.forEach { it.cancel() }
        admittedCalls.joinAll()
        val active = synchronized(lock) { liveTasks.toList() }
        active.forEach { it.cancel() }
        active.joinAll()
        exclusive.closeAndAwait {
            val owned = synchronized(lock) { sessions.toList() }
            for (id in owned) {
                manager.reconcileStopped(id)
                manager.retireAndAwait(id)
                releaseLease(id)
            }
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private fun requireSupportedScope(configuration: AcpWorkerConfiguration) {
        check(!configuration.allowDelegation && configuration.coreToolScope.isEmpty()) {
            "ACP delegation and Core-owned tools are not yet connected to this owner"
        }
    }

    companion object {
        /** The enclosing Room owner must already be exclusive before the ACP lock is acquired. */
        suspend fun attach(
            db: LocalDatabase,
            databasePath: Path,
            shutdown: RoomManagedShutdown,
            previousOwner: AcpPriorOwnerBarrier,
            configurationFence: AcpConfigurationFence,
            source: AcpSessionConfigurationSource,
            catalog: AcpPublishedMcpCatalog,
            backend: AcpGatewayBackend,
            needsGateway: suspend () -> Boolean,
            factory: (AcpWorkerBinding, AcpStdioEvents) -> AcpWorkerProcess,
            now: () -> Long,
            retainReusableWorkers: Boolean = false,
            afterQuiescentCommit: suspend () -> Unit = {},
        ): RoomAcpBackgroundOwner {
            db.useReaderConnection { connection -> connection.usePrepared("PRAGMA database_list") {
                check(it.step() && it.getText(1) == "main" && Files.isSameFile(Paths.get(it.getText(2)), databasePath)) { "ACP owner database does not match Room" }
            } }
            val exclusive = AcpExclusiveOwner.acquire(databasePath, previousOwner)
            try {
                lateinit var manager: AcpWorkerManager
                val guard = object : AcpActiveTurnGuard {
                    override suspend fun <T> withActiveTurn(sessionId: String, turnId: String, action: suspend () -> T): T =
                        manager.withActiveTurn(sessionId, turnId, action)
                }
                val coordinator = AcpTurnCoordinator(RoomAcpSessionJournal(db, guard), now)
                val recorded = AcpWorkerFactory { binding ->
                    val current = AtomicReference(binding)
                    val worker = factory(binding, coordinator.eventsFor { current.get() })
                    RecordingAcpWorker(binding, worker, coordinator) { current.set(it) }
                }
                manager = AcpWorkerManager(recorded, RoomAcpWorkspaceAuthority(db), backend, needsGateway,
                    holdForSettlement = retainReusableWorkers)
                val owner = RoomAcpBackgroundOwner(db, exclusive, RoomAcpRequestLedger(db), manager, coordinator,
                    RoomAcpNewSessionAuthority(db, catalog), configurationFence, source, now, retainReusableWorkers, afterQuiescentCommit)
                withContext(NonCancellable) { shutdown.registerStop { owner.shutdown() } }
                return owner
            } catch (failure: Throwable) {
                try { exclusive.closeAndAwait { } } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}
