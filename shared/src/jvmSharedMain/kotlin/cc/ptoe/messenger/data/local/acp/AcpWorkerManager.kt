/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import cc.ptoe.messenger.data.repository.room.AcpSessionMutationGate
import cc.ptoe.messenger.data.util.randomUuid
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

internal data class AcpWorkerBinding(
    val conversationId: String, val profileId: String, val projectId: String,
    val workspace: String, val agentSessionId: String, val delegated: Boolean,
)
internal data class AcpWorkerReady(val skillsGatewayAvailable: Boolean, val agentSessionId: String? = null)

/** Startup configuration may contain credentials; never persist or format it in diagnostics. */
internal class AcpWorkerConfiguration(
    val command: String, arguments: List<String>, environment: Map<String, String>,
    mcpServers: JsonArray, val allowDelegation: Boolean, coreToolScope: Set<String>,
) {
    val arguments: List<String> = arguments.toList()
    val environment: Map<String, String> = environment.toMap()
    val mcpServers: JsonArray = kotlinx.serialization.json.Json.parseToJsonElement(mcpServers.toString()) as JsonArray
    val coreToolScope: Set<String> = coreToolScope.toSet()
    init {
        require(command.isNotBlank() && '\u0000' !in command && this.arguments.none { '\u0000' in it } &&
            this.environment.all { (key, value) -> key.isNotEmpty() && '=' !in key && '\u0000' !in key && '\u0000' !in value }) { "Invalid ACP worker configuration" }
    }
    override fun toString() = "AcpWorkerConfiguration(redacted)"

    internal fun sameAs(other: AcpWorkerConfiguration): Boolean =
        command == other.command && arguments == other.arguments && environment == other.environment &&
            mcpServers == other.mcpServers && allowDelegation == other.allowDelegation && coreToolScope == other.coreToolScope
}

/** Creating a handle performs no I/O; stopAndAwait must prove the process and its side effects stopped. */
internal interface AcpWorkerProcess {
    suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore): AcpWorkerReady
    suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker): JsonObject
    /** Return true only after the agent acknowledges cancellation and the request has finished. */
    suspend fun cancelAndConfirm(): Boolean
    suspend fun stopAndAwait()
}

/** Agent-reported settings only; this interface does not mutate a session. */
internal interface AcpWorkerSettings {
    suspend fun sessionSettings(): JsonObject
    /** Invoke permit immediately before the first write; a passed permit means outcome may be unknown. */
    suspend fun setMode(modeId: String, permit: () -> Unit): JsonObject = error("ACP mode changes are unavailable")
    suspend fun setModeOption(configId: String, value: String, permit: () -> Unit): JsonObject = error("ACP mode changes are unavailable")
    suspend fun setBooleanOption(configId: String, value: Boolean, permit: () -> Unit): JsonObject = error("ACP boolean changes are unavailable")
}

/**
 * Optional platform-provided proof for an ACP process which can remain alive between turns.
 * Both checks must establish that its prior work, descendants and external side effects have
 * stopped and cannot resume until a subsequent prompt. An ACP response or cancellation
 * acknowledgement alone is not such a proof. The factory is a trusted platform boundary.
 */
internal interface AcpReusableWorkerProof {
    val supportsReusableTurns: Boolean
    suspend fun requireInitialQuiescence()
    suspend fun requireTurnQuiescence(turnId: String)
}
/** Optional recording adapter finalizes only after gateway calls and worker side effects are stopped. */
internal interface AcpTurnFinalizer {
    suspend fun finishTurn(turnId: String, result: JsonObject?, error: String?)
}
/** A new worker must bind the real agent session ID before it can be cached. */
internal fun interface AcpNewSessionBinding { fun bindCreatedSession(binding: AcpWorkerBinding) }
internal fun interface AcpNewSessionAuthority { suspend fun verify(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration) }

internal fun interface AcpWorkerFactory { fun create(binding: AcpWorkerBinding): AcpWorkerProcess }
internal fun interface AcpWorkspaceAuthority { suspend fun requireLocalUnchanged(binding: AcpWorkerBinding) }

/** Per-session reservation and worker lifecycle. Process and ACP wire implementations are injected. */
internal class AcpWorkerManager(
    private val factory: AcpWorkerFactory,
    private val workspace: AcpWorkspaceAuthority,
    private val backend: AcpGatewayBackend,
    private val needsGateway: suspend () -> Boolean,
    private val startupTimeoutMillis: Long = 30_000,
    private val cancelTimeoutMillis: Long = 2_000,
    private val holdForSettlement: Boolean = false,
) : AcpSessionMutationGate, AcpActiveTurnGuard {
    private class Worker(val binding: AcpWorkerBinding, val process: AcpWorkerProcess, val gateway: AcpGatewayCore) {
        var ready: AcpWorkerReady? = null
        var configuration: AcpWorkerConfiguration? = null
    }
    private class Slot {
        val mutex = Mutex()
        var active: String? = null
        var activeJob: Job? = null
        var worker: Worker? = null
        var unsafe = false
        var finishedTurn: String? = null
        var stoppedTurn: Boolean = false
        var settlementPending = false
    }
    private val slots = ConcurrentHashMap<String, Slot>()
    private fun slot(id: String): Slot {
        require(id.startsWith("acp:") && id.length <= 256) { "Invalid ACP session" }
        return slots.computeIfAbsent(id) { Slot() }
    }

    override suspend fun <T> withInactiveSession(id: String, deleting: Boolean, action: suspend () -> T): T = slot(id).mutex.withLock {
        val current = slot(id)
        check(!current.unsafe && current.active == null && !current.settlementPending) { "ACP session is active or its worker has not stopped" }
        if (deleting) {
            current.worker?.let { worker ->
                // The reservation remains locked until shutdown is known to have completed.
                try { withContext(NonCancellable) {
                    worker.gateway.close(); worker.process.stopAndAwait(); current.worker = null
                    if (current.finishedTurn != null) current.stoppedTurn = true
                } }
                catch (failure: Throwable) { current.unsafe = true; throw failure }
            }
            current.worker = null
        }
        action()
    }

    /** Retire an idle worker without deleting its session; success requires platform stop proof. */
    suspend fun retireAndAwait(id: String) = withInactiveSession(id, true) { }

    /** Read only agent-reported settings while excluding turns and session mutation. */
    suspend fun sessionSettings(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration,
        afterRestore: suspend () -> Unit, action: suspend (AcpWorkerSettings) -> JsonObject = { it.sessionSettings() }): JsonObject = slot(binding.conversationId).mutex.withLock {
        val current = slot(binding.conversationId)
        check(!current.unsafe && current.active == null && !current.settlementPending) { "ACP session is active or its worker has not stopped" }
        workspace.requireLocalUnchanged(binding)
        val worker = current.worker?.also { check(it.binding == binding) { "ACP session binding changed" } } ?: run {
            val process = factory.create(binding)
            val gateway = AcpGatewayCore(backend, configuration.allowDelegation && !binding.delegated,
                if (binding.delegated) emptySet() else configuration.coreToolScope)
            Worker(binding, process, gateway).also { current.worker = it }
        }
        if (worker.ready == null) {
            worker.ready = withTimeout(startupTimeoutMillis) { worker.process.start(binding, configuration, worker.gateway) }
            worker.configuration = configuration
            check(worker.ready!!.agentSessionId == null || worker.ready!!.agentSessionId == binding.agentSessionId) { "ACP worker restored a different session" }
        } else check(worker.configuration?.sameAs(configuration) == true) { "ACP worker configuration changed; restart is required" }
        workspace.requireLocalUnchanged(binding)
        check(worker.ready!!.skillsGatewayAvailable || !needsGateway()) { "This ACP agent needs MCP SSE or HTTP support; restart with a compatible agent" }
        afterRestore()
        action(worker.process as? AcpWorkerSettings ?: error("ACP session settings are unavailable"))
    }

    override suspend fun <T> withActiveTurn(sessionId: String, turnId: String, action: suspend () -> T): T = slot(sessionId).mutex.withLock {
        val current = slot(sessionId)
        check(!current.unsafe && current.active == turnId) { "ACP turn is no longer active" }
        action()
    }

    /** Start and persist a new session while holding its reservation; never replay ambiguous startup. */
    suspend fun createNew(draft: AcpWorkerBinding, configuration: AcpWorkerConfiguration, authority: AcpNewSessionAuthority,
        persist: suspend (AcpWorkerBinding) -> Unit): AcpWorkerBinding {
        require(draft.conversationId.startsWith("acp:") && draft.agentSessionId.isEmpty() && draft.projectId.isNotBlank()) { "Invalid new ACP session" }
        val current = slot(draft.conversationId)
        val operation = "create:${randomUuid()}"
        val currentJob = currentCoroutineContext()[Job] ?: error("ACP creation has no lifecycle")
        var acquired: Worker? = null
        var adopted = false
        try {
            withContext(NonCancellable) {
                acquired = current.mutex.withLock {
                    check(!current.unsafe && current.active == null && current.worker == null && !current.settlementPending) { "ACP session already exists or is still stopping" }
                    current.active = operation; current.activeJob = currentJob
                    current.finishedTurn = null; current.stoppedTurn = false
                    try {
                        val process = factory.create(draft)
                        val gateway = AcpGatewayCore(backend, configuration.allowDelegation && !draft.delegated,
                            if (draft.delegated) emptySet() else configuration.coreToolScope)
                        Worker(draft, process, gateway).also { current.worker = it }
                    } catch (failure: Throwable) {
                        current.active = null; current.activeJob = null
                        throw failure
                    }
                }
            }
            val worker = checkNotNull(acquired)
            currentCoroutineContext().ensureActive()
            authority.verify(draft, configuration)
            val snapshot = AcpWorkerConfiguration(configuration.command, configuration.arguments, configuration.environment, configuration.mcpServers,
                configuration.allowDelegation && !draft.delegated, if (draft.delegated) emptySet() else configuration.coreToolScope)
            val ready = withTimeout(startupTimeoutMillis) { worker.process.start(draft, snapshot, worker.gateway) }
            check(ready.skillsGatewayAvailable || !needsGateway()) { "This ACP agent needs MCP SSE or HTTP support" }
            val id = ready.agentSessionId?.takeIf { it.isNotBlank() } ?: error("ACP agent returned no session ID")
            val binding = draft.copy(agentSessionId = id)
            authority.verify(draft, configuration)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                current.mutex.withLock {
                    check(current.active == operation && current.worker === worker && !current.unsafe) { "ACP creation reservation changed" }
                    (worker.process as? AcpNewSessionBinding ?: error("ACP worker cannot adopt a new session")).bindCreatedSession(binding)
                    persist(binding)
                    current.worker = Worker(binding, worker.process, worker.gateway).also { it.ready = ready; it.configuration = snapshot }
                    current.active = null; current.activeJob = null
                    adopted = true
                }
            }
            return binding
        } catch (timeout: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) throw timeout
            throw IllegalStateException("ACP session creation timed out", timeout)
        } finally {
            if (!adopted) withContext(NonCancellable) {
                val worker = acquired
                var safe = false
                try {
                    if (worker != null) coroutineScope {
                        val stopping = async { worker.process.stopAndAwait() }
                        worker.gateway.close()
                        stopping.await()
                    }
                    safe = true
                } finally {
                    current.mutex.withLock {
                        if (current.active == operation) {
                            if (safe) { current.active = null; current.activeJob = null; current.worker = null }
                            else current.unsafe = true
                        }
                    }
                }
            }
        }
    }

    /** An externally supplied ID must first be reserved by a durable, process-wide admission owner. */
    suspend fun prompt(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, content: JsonArray, turnId: String = randomUuid()): JsonObject {
        require(binding.conversationId.startsWith("acp:") && binding.profileId.isNotBlank() && binding.projectId.isNotBlank() &&
            binding.agentSessionId.isNotBlank() && Path.of(binding.workspace).isAbsolute) { "Invalid ACP session binding" }
        require(content.isNotEmpty() && content.toString().encodeToByteArray().size <= 4 * 1024 * 1024) { "Invalid ACP prompt" }
        require(runCatching { java.util.UUID.fromString(turnId).toString() == turnId }.getOrDefault(false)) { "Invalid ACP turn ID" }
        val currentJob = currentCoroutineContext()[Job] ?: error("ACP turn has no lifecycle")
        val current = slot(binding.conversationId)
        var acquired: Worker? = null
        val permissions = AcpPermissionBroker()
        var gatewayActive = false
        var retire = false
        var started = false
        var outcome: JsonObject? = null
        var errorDetail: String? = null
        try {
            withContext(NonCancellable) {
                acquired = current.mutex.withLock {
                    check(!current.unsafe && current.active == null && !current.settlementPending) { "ACP session already has an active or unresolved turn" }
                    current.active = turnId; current.activeJob = currentJob
                    current.finishedTurn = null; current.stoppedTurn = false
                    try {
                        current.worker?.also { check(it.binding == binding) { "ACP session binding changed" } } ?: run {
                            val process = factory.create(binding)
                            val gateway = AcpGatewayCore(backend, configuration.allowDelegation && !binding.delegated,
                                if (binding.delegated) emptySet() else configuration.coreToolScope)
                            Worker(binding, process, gateway).also { current.worker = it }
                        }
                    } catch (failure: Throwable) {
                        current.active = null; current.activeJob = null
                        throw failure
                    }
                }
            }
            val worker = checkNotNull(acquired)
            started = worker.ready != null
            currentCoroutineContext().ensureActive()
            workspace.requireLocalUnchanged(binding)
            if (!started) {
                // Configuration and its credentials stay process-local and are never persisted.
                val snapshot = AcpWorkerConfiguration(configuration.command, configuration.arguments.toList(), configuration.environment,
                    kotlinx.serialization.json.Json.parseToJsonElement(configuration.mcpServers.toString()) as JsonArray,
                    configuration.allowDelegation && !binding.delegated,
                    if (binding.delegated) emptySet() else configuration.coreToolScope.toSet())
                worker.ready = withTimeout(startupTimeoutMillis) { worker.process.start(binding, snapshot, worker.gateway) }
                worker.configuration = snapshot
                check(worker.ready!!.agentSessionId == null || worker.ready!!.agentSessionId == binding.agentSessionId) { "ACP worker restored a different session" }
                started = true
            } else {
                val effective = AcpWorkerConfiguration(configuration.command, configuration.arguments, configuration.environment,
                    configuration.mcpServers, configuration.allowDelegation && !binding.delegated,
                    if (binding.delegated) emptySet() else configuration.coreToolScope)
                check(worker.configuration?.sameAs(effective) == true) { "ACP worker configuration changed; restart is required" }
            }
            workspace.requireLocalUnchanged(binding)
            check(worker.ready!!.skillsGatewayAvailable || !needsGateway()) { "This ACP agent needs MCP SSE or HTTP support; restart with a compatible agent" }
            withContext(NonCancellable) { worker.gateway.beginTurn(turnId); gatewayActive = true }
            currentCoroutineContext().ensureActive()
            val result = worker.process.prompt(turnId, content, permissions)
            val type = (result["type"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            if (type == "Error") retire = true
            check(type in setOf("Finished", "Cancelled", "Error")) { "Invalid ACP prompt outcome" }
            outcome = result
            return result
        } catch (timeout: TimeoutCancellationException) {
            retire = acquired != null
            errorDetail = "ACP worker operation timed out"
            if (!currentCoroutineContext().isActive) { outcome = kotlinx.serialization.json.buildJsonObject { put("type", "Cancelled") }; errorDetail = null; throw timeout }
            throw IllegalStateException("ACP worker operation timed out", timeout)
        } catch (cancelled: CancellationException) {
            val worker = acquired ?: throw cancelled
            outcome = kotlinx.serialization.json.buildJsonObject { put("type", "Cancelled") }
            // An ACP acknowledgement does not prove spawned processes or side effects stopped.
            // Always retire the worker and retain the reservation until stopAndAwait proves it.
            retire = true
            withContext(NonCancellable) {
                permissions.close()
                coroutineScope {
                    val confirmation = async {
                        try { withTimeoutOrNull(cancelTimeoutMillis) { worker.process.cancelAndConfirm() } }
                        catch (_: Exception) { false }
                    }
                    if (gatewayActive) { worker.gateway.endTurn(turnId); gatewayActive = false }
                    confirmation.await()
                }
            }
            throw cancelled
        } catch (failure: Throwable) {
            retire = acquired != null
            errorDetail = failure.message ?: "ACP turn failed"
            throw failure
        } finally {
            withContext(NonCancellable) {
                var safe = false
                try {
                    permissions.close()
                    val worker = acquired
                    if (worker == null) { safe = true; return@withContext }
                    if (retire) {
                        coroutineScope {
                            val stopping = async { worker.process.stopAndAwait() }
                            if (gatewayActive) worker.gateway.endTurn(turnId)
                            worker.gateway.close()
                            stopping.await()
                        }
                    } else if (gatewayActive) worker.gateway.endTurn(turnId)
                    (worker.process as? AcpTurnFinalizer)?.finishTurn(turnId, outcome, errorDetail)
                    safe = true
                } finally {
                    current.mutex.withLock {
                        if (current.active == turnId) {
                            if (acquired != null) {
                                current.finishedTurn = turnId
                                current.stoppedTurn = retire && safe
                                current.settlementPending = holdForSettlement && safe
                            }
                            if (retire && safe && current.worker === acquired) current.worker = null
                            if (safe) { current.active = null; current.activeJob = null }
                            else current.unsafe = true
                        }
                    }
                }
            }
        }
    }

    /** Retry cleanup only after the owning turn has ended; keep the reservation on any failure. */
    suspend fun reconcileStopped(id: String): Boolean = slot(id).mutex.withLock {
        val current = slot(id)
        if (!current.unsafe) return@withLock false
        check(current.activeJob?.isCompleted != false) { "ACP turn is still running" }
        current.worker?.let { worker -> withContext(NonCancellable) { worker.gateway.close(); worker.process.stopAndAwait() } }
        current.worker = null; current.active = null; current.activeJob = null; current.unsafe = false
        current.settlementPending = false
        if (current.finishedTurn != null) current.stoppedTurn = true
        true
    }

    /** The in-memory receipt is valid only for this manager and is invalidated on the next turn. */
    suspend fun <T> withStoppedTurn(id: String, turnId: String, action: suspend () -> T): T {
        val current = slot(id)
        return current.mutex.withLock {
            check(current.finishedTurn == turnId) { "No stop receipt for ACP turn" }
            check(current.activeJob?.isCompleted != false) { "ACP turn is still running" }
            withContext(NonCancellable) {
                if (!current.stoppedTurn) {
                    val worker = current.worker ?: error("ACP worker stop cannot be proved")
                    try { worker.gateway.close(); worker.process.stopAndAwait() }
                    catch (failure: Throwable) { current.unsafe = true; throw failure }
                    current.worker = null; current.stoppedTurn = true
                }
                current.active = null; current.activeJob = null; current.unsafe = false
                try { action().also { current.settlementPending = false } }
                catch (failure: Throwable) { if (current.settlementPending) current.unsafe = true; throw failure }
            }
        }
    }

    /** A created worker may be retained only after its trusted platform proves startup is idle. */
    suspend fun retainCreatedWorker(id: String): Boolean = slot(id).mutex.withLock {
        val current = slot(id)
        check(!current.unsafe && current.active == null && !current.settlementPending) { "ACP worker is not idle" }
        val proof = current.worker?.process as? AcpReusableWorkerProof ?: return@withLock false
        if (!proof.supportsReusableTurns) return@withLock false
        try { proof.requireInitialQuiescence(); true }
        catch (failure: Throwable) { current.unsafe = true; throw failure }
    }

    /** The same lock excludes later turns and mutations through quiescence proof and ledger commit. */
    suspend fun <T> withQuiescentTurn(id: String, turnId: String, action: suspend () -> T): T = slot(id).mutex.withLock {
        val current = slot(id)
        check(holdForSettlement && !current.unsafe && current.active == null && current.settlementPending &&
            current.finishedTurn == turnId && !current.stoppedTurn) { "ACP turn cannot be safely reused" }
        val proof = current.worker?.process as? AcpReusableWorkerProof ?: error("ACP reusable-worker proof is unavailable")
        check(proof.supportsReusableTurns) { "ACP reusable-worker proof is unavailable" }
        withContext(NonCancellable) {
            try {
                proof.requireTurnQuiescence(turnId)
                action().also { current.settlementPending = false }
            } catch (failure: Throwable) { current.unsafe = true; throw failure }
        }
    }

    suspend fun canReuseWorker(id: String): Boolean = slot(id).mutex.withLock {
        val proof = slot(id).worker?.process as? AcpReusableWorkerProof
        proof?.supportsReusableTurns == true
    }

    suspend fun cancel(id: String): Boolean {
        val job = slot(id).mutex.withLock { slot(id).activeJob } ?: return false
        job.cancel(CancellationException("ACP turn cancelled"))
        return true
    }

    /** Bind a UI cancellation to the observed turn so stale controls cannot cancel a later turn. */
    suspend fun cancelTurn(id: String, turnId: String): Boolean {
        val current = slot(id)
        val job = current.mutex.withLock { current.activeJob?.takeIf { current.active == turnId } } ?: return false
        job.cancel(CancellationException("ACP turn cancelled"))
        return true
    }
}
