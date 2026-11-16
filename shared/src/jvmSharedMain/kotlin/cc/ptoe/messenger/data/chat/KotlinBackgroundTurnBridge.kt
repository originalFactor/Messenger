/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.domain.chat.KotlinChatTurnRunner
import cc.ptoe.messenger.domain.chat.KotlinTurnAdmission
import cc.ptoe.messenger.domain.chat.KotlinTurnCommand
import cc.ptoe.messenger.domain.chat.KotlinTurnPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Public projection intentionally excludes the provider request and credentials. */
data class KotlinTurnPresentation(
    val id: String,
    val conversationId: String,
    val phase: KotlinTurnPhase,
    val messageId: String,
    val content: String,
    val detail: String?,
    val pendingTool: ToolCallData?,
    val userMessageId: String?,
    val titleFailure: String?,
    val summaryFailure: String?,
    val approvalFailure: String?,
)

/** Chat-only bridge; the caller must supply the same Room owner used by every other repository. */
class KotlinBackgroundTurnBridge(
    private val delegate: CoreBridge,
    private val store: RoomKotlinTurnStore,
    private val runner: KotlinChatTurnRunner,
    private val processScope: CoroutineScope,
) : CoreBridge by delegate {
    private val legacyLock = Mutex()
    @Volatile private var legacyInvocation: LegacyInvocation? = null

    /** One legacy call's stop request cannot be inherited by a later call. */
    internal class LegacyInvocation(private val runner: KotlinChatTurnRunner) {
        private val lock = Any()
        private var turnId: String? = null
        private var cancellationRequested = false
        private var retired = false

        fun requestCancellation(): (suspend () -> Unit)? {
            val id = synchronized(lock) {
                if (retired) return null
                cancellationRequested = true
                turnId
            }
            return id?.let { captured -> suspend { runner.cancel(captured) } }
        }

        suspend fun admitted(id: String) {
            val cancel = synchronized(lock) { turnId = id; cancellationRequested }
            if (cancel) runner.cancel(id)
        }

        suspend fun beforeWorkerStart(id: String) {
            val cancel = synchronized(lock) { cancellationRequested }
            if (cancel) runner.cancel(id)
        }

        fun retire() = synchronized(lock) { retired = true }
    }

    internal fun newLegacyInvocation() = LegacyInvocation(runner)

    override suspend fun toolApprovalSettings(method: String, body: String?): String {
        val mode = when (method.uppercase()) {
            "GET" -> store.toolApprovalMode()
            "PUT" -> {
                val input = Json.parseToJsonElement(requireNotNull(body) { "Approval settings required" }).jsonObject
                require(input.keys == setOf("mode")) { "Invalid approval settings" }
                val value = input["mode"]?.jsonPrimitive
                require(value?.isString == true && value.content in setOf("manual", "automatic")) { "Invalid approval mode" }
                store.setToolApprovalMode(value.content)
                value.content
            }
            else -> error("Unsupported approval settings method")
        }
        return buildJsonObject { put("mode", mode) }.toString()
    }

    private suspend fun submit(config: TurnConfigBridge, beforeWorkerStart: suspend (String) -> Unit = {},
        onAdmitted: suspend (String) -> Unit = {}): String {
        val command = when (config.operation) {
            "send" -> KotlinTurnCommand.Send(config.conversationId, config.content.orEmpty(), config.partsJson, config.disableTools, config.supportsToolApproval, config.summarizePrompt)
            "retry" -> KotlinTurnCommand.Retry(config.conversationId, requireNotNull(config.messageId), config.disableTools, config.supportsToolApproval, config.summarizePrompt)
            "regenerate" -> KotlinTurnCommand.Regenerate(config.conversationId, requireNotNull(config.messageId), config.messageGroup, config.disableTools, config.supportsToolApproval, config.summarizePrompt)
            else -> error("Unsupported turn operation: ${config.operation}")
        }
        return when (val result = runner.submit(command, beforeWorkerStart, onAdmitted)) {
            is KotlinTurnAdmission.Accepted -> result.turnId
            KotlinTurnAdmission.ModelRequired -> error("A model is required")
            is KotlinTurnAdmission.Rejected -> error(result.reason)
        }
    }

    override suspend fun startBackgroundTurn(config: TurnConfigBridge) { submit(config) }
    override suspend fun backgroundTurnActive(conversationId: String): Boolean = store.activeTurn(conversationId) != null

    override suspend fun observeBackgroundTurn(conversationId: String, onEventJson: (String) -> Unit) {
        val id = store.latestTurn(conversationId) ?: error("No turn exists for this conversation")
        observe(id, conversationId, onEventJson)
    }

    override suspend fun cancelBackgroundTurn(conversationId: String) {
        val id = store.activeTurn(conversationId) ?: return
        runner.cancel(id)
    }

    override suspend fun respondToolApproval(callId: String, approved: Boolean) {
        val matches = store.observeActiveTurns().first().filter {
            it.phase == KotlinTurnPhase.WAITING_APPROVAL && it.pendingTool?.callId == callId
        }
        require(matches.size == 1) { "Approval is missing or ambiguous" }
        runner.approve(matches.single().id, callId, approved)
    }

    override fun cancelTurn() {
        val cancel = legacyInvocation?.requestCancellation() ?: return
        processScope.launch { cancel() }
    }

    /** The legacy stream owns its turn and cancels it when its subscriber disconnects. */
    override suspend fun runTurn(config: TurnConfigBridge, toolExecutor: suspend (String, String) -> Pair<String, Boolean>, onEventJson: (String) -> Unit) =
        runLegacyTurn(config, onEventJson, newLegacyInvocation())

    internal suspend fun runLegacyTurn(config: TurnConfigBridge, onEventJson: (String) -> Unit, invocation: LegacyInvocation,
        beforeWorkerStart: suspend () -> Unit = {}) {
        require(config.disableTools) { "Legacy Kotlin turns require tools to be disabled" }
        legacyLock.withLock {
            legacyInvocation = invocation
            var admitted: String? = null
            var terminal = false
            try {
                val id = submit(config, beforeWorkerStart = { beforeWorkerStart(); invocation.beforeWorkerStart(it) }) {
                    admitted = it; invocation.admitted(it)
                }
                observe(id, config.conversationId) { event ->
                    onEventJson(event)
                    if (Json.parseToJsonElement(event).jsonObject["type"]?.jsonPrimitive?.content in setOf("Finished", "Cancelled", "Error")) terminal = true
                }
            } finally {
                invocation.retire()
                if (legacyInvocation === invocation) legacyInvocation = null
                if (!terminal) admitted?.let { id -> withContext(NonCancellable) { runner.cancelAndAwait(id) } }
            }
        }
    }

    private suspend fun observe(id: String, conversationId: String, emit: (String) -> Unit) {
        var previous: KotlinTurnPresentation? = null
        fun event(type: String, field: String? = null, value: String? = null) = buildJsonObject {
            put("type", type)
            if (field != null && value != null) put(field, value)
        }.toString()
        store.observePresentation(id, conversationId).transformWhile { current ->
            emit(current)
            current.phase !in setOf(KotlinTurnPhase.FINISHED, KotlinTurnPhase.FAILED, KotlinTurnPhase.CANCELLED, KotlinTurnPhase.INTERRUPTED_TOOL)
        }.collect { current ->
            val old = previous
            if (old == null) emit(event("TurnStarted"))
            if (old == null && current.userMessageId != null) emit(event("UserMessagePersisted", "messageId", current.userMessageId))
            if (old == null || old.messageId != current.messageId) {
                if (old != null) emit(event("RoundPersisted"))
                emit(event("StreamingStarted", "messageId", current.messageId))
                if (current.content.isNotEmpty()) emit(event("TextDelta", "text", current.content))
            } else if (current.content != old.content) {
                check(current.content.startsWith(old.content)) { "Streaming content changed unexpectedly" }
                emit(event("TextDelta", "text", current.content.substring(old.content.length)))
            }
            val oldApproval = old?.pendingTool?.takeIf { old.phase == KotlinTurnPhase.WAITING_APPROVAL }
            val approval = current.pendingTool?.takeIf { current.phase == KotlinTurnPhase.WAITING_APPROVAL }
            if (current.summaryFailure != null && old?.summaryFailure != current.summaryFailure) {
                emit(event("SummaryFailed", "message", current.summaryFailure))
            }
            if (current.approvalFailure != null && old?.approvalFailure != current.approvalFailure) {
                emit(event("ApprovalFailed", "message", current.approvalFailure))
            }
            if (oldApproval != null && oldApproval != approval) emit(event("ToolApprovalResolved", "callId", oldApproval.callId))
            if (approval != null && approval != oldApproval) emit(buildJsonObject {
                put("type", "ToolApprovalRequested"); put("callId", approval.callId)
                put("name", approval.name); put("argumentsJson", approval.arguments)
            }.toString())
            if (old == null) emit(event("ReplayComplete"))
            when (current.phase) {
                KotlinTurnPhase.FINISHED -> {
                    if (current.titleFailure != null) emit(event("TitleFailed", "code", "error_title_generate_failed:${current.titleFailure}"))
                    emit(event("Finished", "messageId", current.messageId))
                }
                KotlinTurnPhase.CANCELLED -> emit(event("Cancelled"))
                KotlinTurnPhase.FAILED, KotlinTurnPhase.INTERRUPTED_TOOL -> emit(event("Error", "message", current.detail ?: "Chat turn failed"))
                else -> Unit
            }
            previous = current
        }
    }
}
