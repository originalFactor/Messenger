/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.chat.KotlinBackgroundTurnBridge
import cc.ptoe.messenger.data.chat.KotlinProviderRequestPreparer
import cc.ptoe.messenger.data.chat.KotlinTurnToolPolicy
import cc.ptoe.messenger.data.chat.RoomKotlinTurnStore
import cc.ptoe.messenger.data.chat.LocalMcpWorkerExclusion
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.cloud.RoomCloudProviderGate
import cc.ptoe.messenger.data.cloud.RoomRemoteTaskCancel
import cc.ptoe.messenger.data.cloud.RoomRemoteTaskDecision
import cc.ptoe.messenger.data.cloud.RoomRemotePrompt
import cc.ptoe.messenger.data.cloud.RoomRemoteTurnOperations
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import cc.ptoe.messenger.domain.chat.KotlinRemotePromptIdentity
import cc.ptoe.messenger.data.local.artifact.RoomArtifactService
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.repository.LearningOwnership
import cc.ptoe.messenger.domain.chat.KotlinChatTurnRunner
import cc.ptoe.messenger.domain.chat.KotlinTurnPhase
import cc.ptoe.messenger.domain.chat.KotlinTurnToolExecutor
import cc.ptoe.messenger.domain.chat.KotlinTurnProvider
import cc.ptoe.messenger.domain.chat.KotlinTurnLifecycle
import cc.ptoe.messenger.domain.mcp.McpManager
import cc.ptoe.messenger.domain.mcp.McpInventorySnapshot
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Process-local turn scopes; capability grants are frozen separately for each admission. */
internal class IsolatedCoreTurnBridge(
    private val delegate: CoreBridge,
    private val db: LocalDatabase,
    private val learning: LearningOwnership,
    private val artifacts: RoomArtifactService,
    private val cloudGate: RoomCloudProviderGate,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val imageTool: IsolatedModelImageTool? = null,
    private val provider: KotlinTurnProvider? = null,
    private val lifecycle: KotlinTurnLifecycle = object : KotlinTurnLifecycle {},
    private val mcpManager: () -> McpManager? = { null },
    private val afterMcpSelection: suspend () -> Unit = {},
    private val afterActivitySnapshot: suspend () -> Unit = {},
    private val afterCancellationCapture: suspend () -> Unit = {},
    private val afterLegacyCancellationCapture: suspend () -> Unit = {},
    private val afterLegacyCancellationComplete: () -> Unit = {},
    private val beforeLegacyAdmission: suspend () -> Unit = {},
    private val beforeLegacyWorkerStart: suspend () -> Unit = {},
) : CoreBridge by delegate {
    private data class Runtime(val store: RoomKotlinTurnStore, val runner: KotlinChatTurnRunner, val bridge: KotlinBackgroundTurnBridge)
    private data class McpSelection(val manager: McpManager, val inventory: McpInventorySnapshot, val names: Set<String>)
    private val lock = Mutex()
    private val runtimes = mutableMapOf<String, Runtime>()
    private val legacy = mutableSetOf<String>()
    @Volatile private var currentLegacyInvocation: KotlinBackgroundTurnBridge.LegacyInvocation? = null
    private var generation = 0L
    internal val adminStore = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false }, cloudGate)
    private val adminBridge = KotlinBackgroundTurnBridge(delegate, adminStore,
        KotlinChatTurnRunner(adminStore, KotlinTurnToolExecutor { _, _ -> error("Tool is unavailable") }, scope), scope)

    private suspend fun reserve(id: String) {
        requireNotNull(db.rows().conversation(id)) { "Conversation not found" }
        if (id in runtimes || runtimes.size < 1024) return
        val iterator = runtimes.iterator()
        while (iterator.hasNext() && runtimes.size >= 1024) {
            val entry = iterator.next()
            if (entry.key !in legacy && !entry.value.runner.hasRunningJobs() && entry.value.store.reservedTurn(entry.key) == null) {
                iterator.remove()
                generation++
            }
        }
        check(runtimes.size < 1024) { "Chat scope limit reached" }
    }

    private suspend fun newRuntime(conversationId: String, mcp: McpSelection? = null): Runtime {
        require(conversationId.isNotBlank()) { "Conversation ID is required" }
        val names = IsolatedCoreTools.supportedNames + if (imageTool != null) setOf(IsolatedCoreTools.imageName) else emptySet()
        val bundle = IsolatedCoreTools.create(db, conversationId, learning, artifacts, names, now, imageTool,
            mcp?.manager, mcp?.inventory, mcp?.names.orEmpty())
        val store = RoomKotlinTurnStore(db, bundle.preparer, bundle.tools, cloudGate)
        bundle.bind(store)
        val runner = provider?.let { KotlinChatTurnRunner(store, it, bundle.tools, scope, lifecycle) }
            ?: KotlinChatTurnRunner(store, bundle.tools, scope, lifecycle)
        return Runtime(store, runner, KotlinBackgroundTurnBridge(delegate, store, runner, scope))
    }

    private suspend fun readRuntime(id: String): Runtime = lock.withLock {
        runtimes[id] ?: run { reserve(id); newRuntime(id).also { runtimes[id] = it; generation++ } }
    }

    private suspend fun requireSupported(config: TurnConfigBridge): McpSelection? {
        if (!config.disableTools) {
            require(!config.hasWorkspace) {
                "Runtime, workspace and external tools are unavailable"
            }
            if (config.toolNames.isNotEmpty() || config.tools.isNotEmpty()) {
                val manager = requireNotNull(mcpManager()) { "External tools are unavailable" }
                val inventory = manager.inventorySnapshot()
                require(config.toolNames.distinct().size == config.toolNames.size &&
                    config.tools.map { it.name }.distinct().size == config.tools.size &&
                    config.toolNames.toSet() == config.tools.map { it.name }.toSet() && config.tools.all { selected ->
                        inventory.tools.singleOrNull { it.name == selected.name }?.let { tool ->
                            tool.description == selected.description &&
                                runCatching { Json.parseToJsonElement(selected.parametersJson) }.getOrNull() == tool.parameters
                        } == true
                    }) { "External tool configuration changed" }
                return McpSelection(manager, inventory, config.toolNames.toSet())
            }
        }
        return null
    }

    override suspend fun startBackgroundTurn(config: TurnConfigBridge) {
        val selected = requireSupported(config)
        if (selected != null) {
            afterMcpSelection()
            check(selected.manager.isInventoryCurrent(selected.inventory)) { "MCP inventory changed" }
        }
        lock.withLock {
            check(config.conversationId !in legacy) { "A legacy turn is already active" }
            reserve(config.conversationId)
            val old = runtimes[config.conversationId]
            check(old?.runner?.hasRunningJobs() != true) { "A turn worker is still stopping" }
            check(adminStore.reservedTurn(config.conversationId) == null) { "A turn is already active" }
            val next = newRuntime(config.conversationId, selected)
            runtimes[config.conversationId] = next
            generation++
            next.bridge.startBackgroundTurn(config)
        }
    }

    override suspend fun backgroundTurnActive(conversationId: String) = readRuntime(conversationId).bridge.backgroundTurnActive(conversationId)
    override suspend fun observeBackgroundTurn(conversationId: String, onEventJson: (String) -> Unit) =
        readRuntime(conversationId).bridge.observeBackgroundTurn(conversationId, onEventJson)
    override suspend fun cancelBackgroundTurn(conversationId: String) {
        val target = lock.withLock {
            val runtime = runtimes[conversationId] ?: run {
                check(adminStore.activeTurn(conversationId) == null) { "Active turn belongs to another owner" }
                return
            }
            val turnId = runtime.store.activeTurn(conversationId) ?: return
            check(runtime.runner.ownsRunningJob(turnId)) { "Active turn belongs to another owner" }
            runtime.runner to turnId
        }
        afterCancellationCapture()
        target.first.cancel(target.second)
    }

    /** Locate the same runner that admitted the task; the store rechecks all authority. */
    val remoteCancel = RoomRemoteTaskCancel { scope: KotlinRemoteControlScope, cookie: String, conversationId: String, turnId: String ->
        require(!conversationId.startsWith("acp:")) { "ACP task cancellation is unavailable" }
        val runner = lock.withLock {
            val runtime = runtimes[conversationId] ?: error("Active turn belongs to another owner")
            check(runtime.runner.ownsRunningJob(turnId)) { "Active turn belongs to another owner" }
            runtime.runner
        }
        runner.cancelRemoteAuthenticatedAndAwait(scope, cookie, conversationId, turnId)
    }

    /** Bind the decision to the exact runner; transactionally recheck relay authority and call. */
    val remoteDecision = RoomRemoteTaskDecision { scope, cookie, conversationId, turnId, option ->
        require(!conversationId.startsWith("acp:")) { "ACP task decisions are unavailable" }
        val (verb, callId) = option.split(':', limit = 2).let { require(it.size == 2 && it[1].isNotBlank()); it[0] to it[1] }
        val allow = when (verb) { "approve" -> true; "deny" -> false; else -> error("Invalid approval option") }
        val runtime = lock.withLock {
            val owner = runtimes[conversationId] ?: error("Active turn belongs to another owner")
            check(owner.runner.ownsRunningJob(turnId)) { "Active turn belongs to another owner" }
            owner
        }
        runtime.store.decideRemoteApprovalAuthenticated(scope, cookie, conversationId, turnId, callId, allow)
    }

    /** A scoped remote prompt owns the same per-conversation runner as local chat. */
    val remotePrompt = RoomRemotePrompt { authority, cookie, requestId, conversationId, text, permissions, update ->
        val candidate = lock.withLock {
            check(conversationId !in legacy) { "A legacy turn is already active" }
            reserve(conversationId)
            val old = runtimes[conversationId]
            check(old?.runner?.hasRunningJobs() != true && adminStore.reservedTurn(conversationId) == null) { "A turn is already active" }
            newRuntime(conversationId).also { runtimes[conversationId] = it; generation++ }
        }
        RoomRemoteTurnOperations(candidate.store, candidate.runner).prompt(authority, conversationId, text, permissions, update,
            KotlinRemotePromptIdentity(requestId, cookie)) { action -> lock.withLock {
                check(runtimes[conversationId] === candidate && conversationId !in legacy) { "Remote turn owner changed" }
                action()
            } }
    }

    override suspend fun respondToolApproval(callId: String, approved: Boolean) {
        val runtime = lock.withLock {
            val matching = adminStore.observeActiveTurns().first().filter {
                it.phase == KotlinTurnPhase.WAITING_APPROVAL && it.pendingTool?.callId == callId
            }
            require(matching.size == 1) { "Approval is missing or ambiguous" }
            runtimes[matching.single().conversationId] ?: error("Approval belongs to a previous process")
        }
        runtime.bridge.respondToolApproval(callId, approved)
    }

    override suspend fun toolApprovalSettings(method: String, body: String?): String =
        adminBridge.toolApprovalSettings(method, body)

    override val supportsUncertainMcpCalls: Boolean get() = true

    override suspend fun uncertainMcpCall(conversationId: String): cc.ptoe.messenger.domain.chat.UncertainMcpCall? {
        val runtime = lock.withLock { runtimes[conversationId] } ?: return adminStore.uncertainMcpCall(conversationId)
        val call = runtime.store.uncertainMcpCall(conversationId) ?: return null
        if (!call.canAcknowledge) return call
        runtime.runner.awaitStopped(call.turnId)
        return runtime.store.uncertainMcpCall(conversationId)?.takeIf { it == call }
    }

    override suspend fun acknowledgeUncertainMcpCall(conversationId: String, turnId: String, callId: String) {
        val runtime = lock.withLock { runtimes[conversationId] }
        val store = runtime?.store ?: adminStore
        val call = store.uncertainMcpCall(conversationId) ?: error("Uncertain MCP call is unavailable")
        check(call.canAcknowledge && call.turnId == turnId && call.callId == callId) { "Uncertain MCP call changed" }
        runtime?.runner?.awaitStopped(turnId)
        store.acknowledgeUncertainMcpCall(conversationId, turnId, callId)
    }

    /** Called only after this bridge's process job has actually been cancelled and joined. */
    internal suspend fun recordStoppedMcpWorkers(shutdown: RoomManagedShutdown, job: Job) {
        check(job.isCompleted) { "Local chat workers are still running" }
        val snapshot = lock.withLock { runtimes.toMap() }
        for ((conversationId, runtime) in snapshot) {
            runtime.store.localMcpExclusionKey(conversationId)?.let { key ->
                LocalMcpWorkerExclusion.record(db, key, job, shutdown)
            }
        }
    }

    override suspend fun hasActiveTasks(): Boolean {
        // Never hold the bridge lock while querying workers or Room: cancellation and cleanup
        // must continue even when a caller checks whether it is safe to leave.
        while (true) {
            val (snapshotGeneration, legacyActive, runners) = lock.withLock {
                Triple(generation, legacy.isNotEmpty(), runtimes.values.map { it.runner })
            }
            afterActivitySnapshot()
            if (legacyActive || runners.any { it.hasRunningJobs() } || adminStore.observeActiveTurns().first().isNotEmpty()) return true
            if (lock.withLock { generation == snapshotGeneration }) return false
        }
    }

    override suspend fun runTurn(config: TurnConfigBridge, toolExecutor: suspend (String, String) -> Pair<String, Boolean>,
        onEventJson: (String) -> Unit) = runLegacy(config, onEventJson, scoped = false)

    override suspend fun runScopedTurn(config: TurnConfigBridge, toolExecutor: suspend (String, String) -> Pair<String, Boolean>,
        onEventJson: (String) -> Unit) = runLegacy(config, onEventJson, scoped = true)

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun runLegacy(config: TurnConfigBridge, onEventJson: (String) -> Unit, scoped: Boolean) {
        require(config.disableTools) { "Legacy turns require tools to be disabled" }
        val (runtime, invocation) = lock.withLock {
            check(config.conversationId !in legacy) { "A legacy turn is already active for this conversation" }
            if (!scoped) check(currentLegacyInvocation == null) { "A legacy turn is already active" }
            reserve(config.conversationId)
            val old = runtimes[config.conversationId]
            check(old?.runner?.hasRunningJobs() != true) { "A turn worker is still stopping" }
            check(adminStore.reservedTurn(config.conversationId) == null) { "A turn is already active" }
            val next = newRuntime(config.conversationId)
            val token = next.bridge.newLegacyInvocation()
            runtimes[config.conversationId] = next
            legacy.add(config.conversationId)
            if (!scoped) currentLegacyInvocation = token
            generation++
            next to token
        }
        val cancellation = if (scoped) checkNotNull(currentCoroutineContext()[Job]).invokeOnCompletion(
            onCancelling = true, invokeImmediately = true,
        ) { failure ->
            if (failure != null) invocation.requestCancellation()?.let { cancel -> scope.launch { cancel() } }
        } else null
        try {
            beforeLegacyAdmission()
            runtime.bridge.runLegacyTurn(config, onEventJson, invocation, beforeLegacyWorkerStart, awaitTerminalWorker = scoped)
        }
        finally { withContext(NonCancellable) { lock.withLock {
            cancellation?.dispose()
            invocation.retire()
            if (currentLegacyInvocation === invocation) currentLegacyInvocation = null
            if (legacy.remove(config.conversationId)) generation++
        } } }
    }

    override fun cancelTurn() {
        val cancel = currentLegacyInvocation?.requestCancellation() ?: return
        scope.launch {
            afterLegacyCancellationCapture()
            try { cancel() } finally { afterLegacyCancellationComplete() }
        }
    }
}
