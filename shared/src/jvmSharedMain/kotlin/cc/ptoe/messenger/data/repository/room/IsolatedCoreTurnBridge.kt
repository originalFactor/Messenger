/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.chat.KotlinBackgroundTurnBridge
import cc.ptoe.messenger.data.chat.KotlinProviderRequestPreparer
import cc.ptoe.messenger.data.chat.KotlinTurnToolPolicy
import cc.ptoe.messenger.data.chat.RoomKotlinTurnStore
import cc.ptoe.messenger.data.cloud.RoomCloudProviderGate
import cc.ptoe.messenger.data.local.artifact.RoomArtifactService
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.repository.LearningOwnership
import cc.ptoe.messenger.domain.chat.KotlinChatTurnRunner
import cc.ptoe.messenger.domain.chat.KotlinTurnPhase
import cc.ptoe.messenger.domain.chat.KotlinTurnToolExecutor
import cc.ptoe.messenger.domain.chat.KotlinTurnProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
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
    private val afterActivitySnapshot: suspend () -> Unit = {},
    private val afterCancellationCapture: suspend () -> Unit = {},
    private val afterLegacyCancellationCapture: suspend () -> Unit = {},
    private val afterLegacyCancellationComplete: () -> Unit = {},
    private val beforeLegacyAdmission: suspend () -> Unit = {},
    private val beforeLegacyWorkerStart: suspend () -> Unit = {},
) : CoreBridge by delegate {
    private data class Runtime(val store: RoomKotlinTurnStore, val runner: KotlinChatTurnRunner, val bridge: KotlinBackgroundTurnBridge)
    private val lock = Mutex()
    private val runtimes = mutableMapOf<String, Runtime>()
    private val legacy = mutableSetOf<String>()
    @Volatile private var currentLegacyInvocation: KotlinBackgroundTurnBridge.LegacyInvocation? = null
    private var generation = 0L
    private val adminStore = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false }, cloudGate)
    private val adminBridge = KotlinBackgroundTurnBridge(delegate, adminStore,
        KotlinChatTurnRunner(adminStore, KotlinTurnToolExecutor { _, _ -> error("Tool is unavailable") }, scope), scope)

    private suspend fun reserve(id: String) {
        requireNotNull(db.rows().conversation(id)) { "Conversation not found" }
        if (id in runtimes || runtimes.size < 1024) return
        val iterator = runtimes.iterator()
        while (iterator.hasNext() && runtimes.size >= 1024) {
            val entry = iterator.next()
            if (entry.key !in legacy && !entry.value.runner.hasRunningJobs() && entry.value.store.activeTurn(entry.key) == null) {
                iterator.remove()
                generation++
            }
        }
        check(runtimes.size < 1024) { "Chat scope limit reached" }
    }

    private suspend fun newRuntime(conversationId: String): Runtime {
        require(conversationId.isNotBlank()) { "Conversation ID is required" }
        val names = IsolatedCoreTools.supportedNames + if (imageTool != null) setOf(IsolatedCoreTools.imageName) else emptySet()
        val bundle = IsolatedCoreTools.create(db, conversationId, learning, artifacts, names, now, imageTool)
        val store = RoomKotlinTurnStore(db, bundle.preparer, bundle.tools, cloudGate)
        bundle.bind(store)
        val runner = provider?.let { KotlinChatTurnRunner(store, it, bundle.tools, scope) }
            ?: KotlinChatTurnRunner(store, bundle.tools, scope)
        return Runtime(store, runner, KotlinBackgroundTurnBridge(delegate, store, runner, scope))
    }

    private suspend fun readRuntime(id: String): Runtime = lock.withLock {
        runtimes[id] ?: run { reserve(id); newRuntime(id).also { runtimes[id] = it; generation++ } }
    }

    private fun requireSupported(config: TurnConfigBridge) {
        if (!config.disableTools) {
            require(!config.hasWorkspace && config.toolNames.isEmpty() && config.tools.isEmpty()) {
                "Runtime, workspace and external tools are unavailable"
            }
        }
    }

    override suspend fun startBackgroundTurn(config: TurnConfigBridge) {
        requireSupported(config)
        lock.withLock {
            check(config.conversationId !in legacy) { "A legacy turn is already active" }
            reserve(config.conversationId)
            val old = runtimes[config.conversationId]
            check(old?.runner?.hasRunningJobs() != true) { "A turn worker is still stopping" }
            check(old?.store?.activeTurn(config.conversationId) == null) { "A turn is already active" }
            val next = newRuntime(config.conversationId)
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
        onEventJson: (String) -> Unit) {
        require(config.disableTools) { "Legacy turns require tools to be disabled" }
        val (runtime, invocation) = lock.withLock {
            check(legacy.isEmpty()) { "A legacy turn is already active" }
            reserve(config.conversationId)
            val old = runtimes[config.conversationId]
            check(old?.runner?.hasRunningJobs() != true) { "A turn worker is still stopping" }
            check(old?.store?.activeTurn(config.conversationId) == null) { "A turn is already active" }
            val next = newRuntime(config.conversationId)
            val token = next.bridge.newLegacyInvocation()
            runtimes[config.conversationId] = next
            legacy.add(config.conversationId)
            currentLegacyInvocation = token
            generation++
            next to token
        }
        try {
            beforeLegacyAdmission()
            runtime.bridge.runLegacyTurn(config, onEventJson, invocation, beforeLegacyWorkerStart)
        }
        finally { withContext(NonCancellable) { lock.withLock {
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
