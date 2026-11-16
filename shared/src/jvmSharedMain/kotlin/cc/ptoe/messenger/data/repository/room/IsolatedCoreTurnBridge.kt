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
) : CoreBridge by delegate {
    private data class Runtime(val store: RoomKotlinTurnStore, val bridge: KotlinBackgroundTurnBridge)
    private val lock = Mutex()
    private val runtimes = mutableMapOf<String, Runtime>()
    private val legacy = mutableSetOf<String>()
    private val adminStore = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false }, cloudGate)
    private val adminBridge = KotlinBackgroundTurnBridge(delegate, adminStore,
        KotlinChatTurnRunner(adminStore, KotlinTurnToolExecutor { _, _ -> error("Tool is unavailable") }, scope), scope)

    private suspend fun reserve(id: String) {
        requireNotNull(db.rows().conversation(id)) { "Conversation not found" }
        if (id in runtimes || runtimes.size < 1024) return
        val iterator = runtimes.iterator()
        while (iterator.hasNext() && runtimes.size >= 1024) {
            val entry = iterator.next()
            if (entry.key !in legacy && entry.value.store.activeTurn(entry.key) == null) iterator.remove()
        }
        check(runtimes.size < 1024) { "Chat scope limit reached" }
    }

    private suspend fun newRuntime(conversationId: String): Runtime {
        require(conversationId.isNotBlank()) { "Conversation ID is required" }
        val names = IsolatedCoreTools.supportedNames + if (imageTool != null) setOf(IsolatedCoreTools.imageName) else emptySet()
        val bundle = IsolatedCoreTools.create(db, conversationId, learning, artifacts, names, now, imageTool)
        val store = RoomKotlinTurnStore(db, bundle.preparer, bundle.tools, cloudGate)
        bundle.bind(store)
        val runner = KotlinChatTurnRunner(store, bundle.tools, scope)
        return Runtime(store, KotlinBackgroundTurnBridge(delegate, store, runner, scope))
    }

    private suspend fun readRuntime(id: String): Runtime = lock.withLock {
        runtimes[id] ?: run { reserve(id); newRuntime(id).also { runtimes[id] = it } }
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
            check(old?.store?.activeTurn(config.conversationId) == null) { "A turn is already active" }
            val next = newRuntime(config.conversationId)
            runtimes[config.conversationId] = next
            next.bridge.startBackgroundTurn(config)
        }
    }

    override suspend fun backgroundTurnActive(conversationId: String) = readRuntime(conversationId).bridge.backgroundTurnActive(conversationId)
    override suspend fun observeBackgroundTurn(conversationId: String, onEventJson: (String) -> Unit) =
        readRuntime(conversationId).bridge.observeBackgroundTurn(conversationId, onEventJson)
    override suspend fun cancelBackgroundTurn(conversationId: String) = readRuntime(conversationId).bridge.cancelBackgroundTurn(conversationId)

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

    override suspend fun hasActiveTasks(): Boolean =
        adminStore.observeActiveTurns().first().isNotEmpty()

    override suspend fun runTurn(config: TurnConfigBridge, toolExecutor: suspend (String, String) -> Pair<String, Boolean>,
        onEventJson: (String) -> Unit) {
        require(config.disableTools) { "Legacy turns require tools to be disabled" }
        val runtime = lock.withLock {
            check(legacy.isEmpty()) { "A legacy turn is already active" }
            reserve(config.conversationId)
            val old = runtimes[config.conversationId]
            check(old?.store?.activeTurn(config.conversationId) == null) { "A turn is already active" }
            newRuntime(config.conversationId).also { runtimes[config.conversationId] = it; legacy.add(config.conversationId) }
        }
        try { runtime.bridge.runTurn(config, toolExecutor, onEventJson) }
        finally { withContext(NonCancellable) { lock.withLock { legacy.remove(config.conversationId) } } }
    }

    override fun cancelTurn() {
        scope.launch {
            val runtime = lock.withLock { legacy.singleOrNull()?.let { runtimes[it] } }
            runtime?.bridge?.cancelTurn()
        }
    }
}
