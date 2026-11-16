/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0. See https://www.apache.org/licenses/LICENSE-2.0
 */
package cc.ptoe.messenger.domain.chat

import cc.ptoe.messenger.data.remote.api.DirectProviderStream
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.dto.UsageDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The caller keeps the conversation, request, provider credentials and approval records local. */
enum class KotlinTurnPhase { SUMMARY_PENDING, SUMMARY_REQUESTING, READY_PROVIDER, REQUESTING_PROVIDER, WAITING_APPROVAL, APPROVAL_UNAVAILABLE, AUTO_APPROVAL_PENDING, AUTO_APPROVAL_REQUESTING, READY_TOOL, EXECUTING_TOOL, CANCELLING_TOOL, INTERRUPTED_TOOL, TITLE_PENDING, TITLE_REQUESTING, FINISHED, FAILED, CANCELLED }

@Serializable
data class KotlinCloudTurnIdentity(val accountId: String, val generation: String)

/** Immutable capability scope captured when a turn is admitted. */
@Serializable
data class KotlinRuntimeToolGrant(
    val conversationId: String,
    val projectId: String?,
    val workspaceRoot: String,
    val deviceId: String?,
    val hostIdentity: String,
    val allowedNames: List<String>,
)

/** Per-turn Core capability ceiling; Core tools do not require a workspace. */
@Serializable
data class KotlinCoreToolGrant(val conversationId: String, val allowedNames: List<String>)
@Serializable
data class KotlinDelegationParent(val turnId: String, val callId: String)

data class KotlinTurnSnapshot(
    val id: String,
    val conversationId: String,
    val phase: KotlinTurnPhase,
    val provider: Provider,
    val request: ChatCompletionRequestDto,
    val pendingTool: ToolCallData? = null,
    val cloudIdentity: KotlinCloudTurnIdentity? = null,
)

data class KotlinTitleWork(val turnId: String, val conversationId: String, val input: KotlinTitleInput, val provider: Provider, val cloudIdentity: KotlinCloudTurnIdentity?)

sealed interface KotlinTurnAdmission {
    data class Accepted(val turnId: String) : KotlinTurnAdmission
    data object ModelRequired : KotlinTurnAdmission
    data class Rejected(val reason: String) : KotlinTurnAdmission
}

/** The persistence implementation must implement all operations as atomic, durable transactions.
 * Admission validates the selected model before ANY message insert, status change or deletion,
 * including for retries and regeneration. A claim is exclusive across runner instances. It may
 * reclaim an abandoned prior-process claim only during controlled startup recovery.
 *
 * Provider completion persists the assistant/tool-call row and next phase. For a pending tool,
 * approval is persisted before WAITING_APPROVAL becomes observable; a decision must match the
 * current turn and tool. beginTool rechecks current permission/tool policy, records EXECUTING_TOOL
 * before execution, and fails closed when access has been revoked. completeTool stores the result
 * and prepares the next call or provider request atomically. Cancellation while a tool is
 * executing retains the conversation reservation until the invocation returns; interrupted tool
 * outcomes remain reserved until the platform can confirm they are no longer running.
 * fail stores both a detailed error
 * bubble and a durable UI notification; no error is silently retried. */
interface KotlinTurnStore {
    suspend fun admit(command: KotlinTurnCommand): KotlinTurnAdmission
    suspend fun claim(turnId: String, recovery: Boolean): Boolean
    suspend fun release(turnId: String)
    suspend fun snapshot(turnId: String): KotlinTurnSnapshot
    fun providerStream(turn: KotlinTurnSnapshot, provider: KotlinTurnProvider): Flow<ChatStreamEvent> = provider.stream(turn.provider, turn.request)
    fun observe(turnId: String): Flow<KotlinTurnSnapshot>
    suspend fun beginProvider(turnId: String): KotlinTurnSnapshot
    suspend fun beginSummary(turnId: String): KotlinSummaryWork? = error("Context summarization unavailable")
    fun summaryStream(work: KotlinSummaryWork, provider: KotlinTurnProvider): Flow<ChatStreamEvent> = provider.complete(work.turn.provider, work.request)
    suspend fun completeSummary(turnId: String, result: KotlinSummaryResult) { error("Context summarization unavailable") }
    suspend fun interruptedSummary(turnId: String) { error("Context summarization unavailable") }
    suspend fun appendContent(turnId: String, content: String)
    suspend fun setReasoningFormat(turnId: String, format: String)
    suspend fun completeProvider(turnId: String, calls: List<ToolCallData>, usage: UsageDto?)
    /** Persist TITLE_REQUESTING before dispatch; a recovered in-flight title must not be retried. */
    suspend fun beginTitle(turnId: String): KotlinTitleWork? = error("Title generation unavailable")
    fun titleStream(work: KotlinTitleWork, request: ChatCompletionRequestDto, provider: KotlinTurnProvider): Flow<ChatStreamEvent> = provider.complete(work.provider, request)
    suspend fun completeTitle(turnId: String, result: KotlinTitleResult) { error("Title generation unavailable") }
    suspend fun interruptedTitle(turnId: String) { error("Title generation unavailable") }
    suspend fun decideApproval(turnId: String, callId: String, allow: Boolean)
    suspend fun expireApproval(turnId: String, callId: String) = decideApproval(turnId, callId, false)
    suspend fun rejectUnavailableApproval(turnId: String) { error("Tool approval unavailable") }
    suspend fun beginAutomaticApproval(turnId: String): KotlinApprovalWork? = error("Automatic approval unavailable")
    fun automaticApprovalStream(work: KotlinApprovalWork, request: ChatCompletionRequestDto, provider: KotlinTurnProvider): Flow<ChatStreamEvent> = provider.complete(work.turn.provider, request)
    suspend fun completeAutomaticApproval(turnId: String, callId: String, result: KotlinApprovalResult) { error("Automatic approval unavailable") }
    suspend fun interruptedAutomaticApproval(turnId: String) { error("Automatic approval unavailable") }
    suspend fun decideRemoteApproval(scope: KotlinRemoteControlScope, conversationId: String, turnId: String, callId: String, allow: Boolean) { error("Remote approval unavailable") }
    suspend fun beginTool(turnId: String): ToolCallData
    suspend fun completeTool(turnId: String, call: ToolCallData, result: ToolExecutionResult)
    suspend fun fail(turnId: String, detail: String)
    suspend fun cancel(turnId: String)
    suspend fun cancelRemote(scope: KotlinRemoteControlScope, conversationId: String, turnId: String) { error("Remote cancellation unavailable") }
}

/** No state mutation is allowed while merely constructing a command. */
sealed interface KotlinTurnCommand {
    val conversationId: String
    val disableTools: Boolean
    val supportsToolApproval: Boolean
    val summarizePrompt: String
    val remoteScope: KotlinRemoteControlScope?
    val scheduledRun: Boolean
    val delegatedRun: Boolean
    val delegationParent: KotlinDelegationParent?
    data class Send(override val conversationId: String, val content: String, val partsJson: String?, override val disableTools: Boolean = false, override val supportsToolApproval: Boolean = true, override val summarizePrompt: String = "Summarize the conversation while preserving decisions, facts, and open tasks.", override val remoteScope: KotlinRemoteControlScope? = null, override val scheduledRun: Boolean = false, override val delegatedRun: Boolean = false, override val delegationParent: KotlinDelegationParent? = null) : KotlinTurnCommand
    data class Retry(override val conversationId: String, val messageId: String, override val disableTools: Boolean = false, override val supportsToolApproval: Boolean = true, override val summarizePrompt: String = "Summarize the conversation while preserving decisions, facts, and open tasks.", override val remoteScope: KotlinRemoteControlScope? = null, override val scheduledRun: Boolean = false, override val delegatedRun: Boolean = false, override val delegationParent: KotlinDelegationParent? = null) : KotlinTurnCommand
    data class Regenerate(override val conversationId: String, val messageId: String, val messageGroup: List<String>? = null, override val disableTools: Boolean = false, override val supportsToolApproval: Boolean = true, override val summarizePrompt: String = "Summarize the conversation while preserving decisions, facts, and open tasks.", override val remoteScope: KotlinRemoteControlScope? = null, override val scheduledRun: Boolean = false, override val delegatedRun: Boolean = false, override val delegationParent: KotlinDelegationParent? = null) : KotlinTurnCommand
}

fun interface KotlinTurnProvider {
    fun stream(provider: Provider, request: ChatCompletionRequestDto): Flow<ChatStreamEvent>
    /** Auxiliary summary/title calls are one-shot non-streaming requests. */
    fun complete(provider: Provider, request: ChatCompletionRequestDto): Flow<ChatStreamEvent> = stream(provider, request.copy(stream = false))
}

/** Executor must return only after its underlying operation has stopped; never retry an invocation. */
fun interface KotlinTurnToolExecutor {
    suspend fun execute(turnId: String, call: ToolCallData): ToolExecutionResult
}

/** Process lifecycle hooks must never contain message contents, credentials or tool arguments. */
interface KotlinTurnLifecycle {
    fun begin(conversationId: String) {}
    fun status(conversationId: String, status: String) {}
    fun end(conversationId: String) {}
}

/** Process-owned runner: observers may detach without cancelling; explicit stop cancels the job.
 * This is an isolated migration component, not yet the production chat path. */
class KotlinChatTurnRunner(
    private val store: KotlinTurnStore,
    private val provider: KotlinTurnProvider,
    private val tools: KotlinTurnToolExecutor,
    private val scope: CoroutineScope,
    private val lifecycle: KotlinTurnLifecycle = object : KotlinTurnLifecycle {},
    private val approvalTimeoutMillis: Long = 300_000,
) {
    constructor(store: KotlinTurnStore, tools: KotlinTurnToolExecutor, scope: CoroutineScope, lifecycle: KotlinTurnLifecycle = object : KotlinTurnLifecycle {}) :
        this(store, object : KotlinTurnProvider {
            private val direct = DirectProviderStream()
            override fun stream(provider: Provider, request: ChatCompletionRequestDto) = direct.stream(provider, request)
            override fun complete(provider: Provider, request: ChatCompletionRequestDto) = direct.complete(provider, request)
        }, tools, scope, lifecycle)

    private val lock = Mutex()
    private val running = mutableMapOf<String, Job>()

    suspend fun submit(command: KotlinTurnCommand): KotlinTurnAdmission = submit(command) { }

    /** The callback records the admitted identity before worker startup; it must not throw. */
    suspend fun submit(command: KotlinTurnCommand, onAdmitted: (String) -> Unit): KotlinTurnAdmission {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val result = store.admit(command)
            if (result is KotlinTurnAdmission.Accepted) {
                try {
                    onAdmitted(result.turnId)
                    start(result.turnId, recovery = false)
                } catch (failure: Throwable) {
                    try { store.fail(result.turnId, failure.message ?: "Chat worker could not start") }
                    catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }
            result
        }
    }

    /** Call only after the previous process and its workers have stopped. */
    suspend fun recover(turnId: String) = start(turnId, recovery = true)

    suspend fun approve(turnId: String, callId: String, allow: Boolean) = store.decideApproval(turnId, callId, allow)

    suspend fun respondRemoteApproval(scope: KotlinRemoteControlScope, conversationId: String, expectedTurnId: String, callId: String, approved: Boolean) =
        store.decideRemoteApproval(scope, conversationId, expectedTurnId, callId, approved)

    /** Recheck grant and target atomically, then stop and join the exact authorized worker. */
    suspend fun cancelRemoteAndAwait(scope: KotlinRemoteControlScope, conversationId: String, expectedTurnId: String) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            store.cancelRemote(scope, conversationId, expectedTurnId)
            lock.withLock { running[expectedTurnId]?.cancel() }
            awaitStopped(expectedTurnId)
        }
    }

    suspend fun cancel(turnId: String) {
        // Persist first so a detached observer and recovery both see cancellation.
        try {
            store.cancel(turnId)
        } finally {
            lock.withLock { running[turnId]?.cancel() }
        }
    }

    /** Wait for the provider/tool worker and its lifecycle cleanup, not merely a terminal row. */
    suspend fun awaitStopped(turnId: String) {
        val job = lock.withLock { running[turnId] }
        job?.join()
    }

    /** The caller may hold a reservation until the worker acknowledges cancellation. */
    suspend fun cancelAndAwait(turnId: String) {
        try { cancel(turnId) } finally { withContext(NonCancellable) { awaitStopped(turnId) } }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun start(turnId: String, recovery: Boolean) = lock.withLock {
        if (running[turnId]?.isActive == true) return@withLock
        if (!store.claim(turnId, recovery)) return@withLock
        val conversationId = try { store.snapshot(turnId).conversationId } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { if (failure is Exception && failure !is CancellationException) store.fail(turnId, failure.message ?: "Chat turn failed") }
                finally { store.release(turnId) }
            }
            throw failure
        }
        // Atomic start guarantees the cleanup body runs even when the process scope was already
        // cancelled or is cancelled before dispatch. Never leave an admitted turn without a worker.
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            var began = false
            try {
                currentCoroutineContext().ensureActive()
                lifecycle.begin(conversationId)
                began = true
                run(turnId)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { store.cancel(turnId) }
                throw cancelled
            } catch (failure: Exception) {
                withContext(NonCancellable) { store.fail(turnId, failure.message ?: "Chat turn failed") }
            } finally {
                val worker = currentCoroutineContext()[Job]
                withContext(NonCancellable) {
                    try {
                        store.release(turnId)
                        val phase = store.snapshot(turnId).phase
                        val status = when (phase) {
                            KotlinTurnPhase.FINISHED -> "completed"
                            KotlinTurnPhase.FAILED, KotlinTurnPhase.INTERRUPTED_TOOL -> "failed"
                            KotlinTurnPhase.CANCELLED -> "cancelled"
                            else -> null
                        }
                        if (status != null) lifecycle.status(conversationId, status)
                    } finally {
                        try { if (began) lifecycle.end(conversationId) }
                        finally { lock.withLock { if (running[turnId] === worker) running.remove(turnId) } }
                    }
                }
            }
        }
        running[turnId] = job
    }

    private suspend fun run(turnId: String) {
        while (true) {
            val snapshot = store.snapshot(turnId)
            when (snapshot.phase) {
                KotlinTurnPhase.SUMMARY_PENDING -> {
                    val work = store.beginSummary(turnId)
                    if (work != null) store.completeSummary(turnId, KotlinConversationSummary.generate(work) { store.summaryStream(it, provider) })
                }
                KotlinTurnPhase.SUMMARY_REQUESTING -> store.interruptedSummary(turnId)
                KotlinTurnPhase.READY_PROVIDER -> requestProvider(store.beginProvider(turnId))
                KotlinTurnPhase.TITLE_PENDING -> {
                    val work = store.beginTitle(turnId)
                    if (work != null) {
                        val result = KotlinConversationTitle.generate(work.input) { store.titleStream(work, it, provider) }
                        store.completeTitle(turnId, result)
                    }
                }
                KotlinTurnPhase.WAITING_APPROVAL -> {
                    lifecycle.status(snapshot.conversationId, "approval")
                    val changed = withTimeoutOrNull(approvalTimeoutMillis) {
                        store.observe(turnId).first { it.phase != KotlinTurnPhase.WAITING_APPROVAL || it.pendingTool?.callId != snapshot.pendingTool?.callId }
                    }
                    if (changed == null) snapshot.pendingTool?.let { store.expireApproval(turnId, it.callId) }
                    lifecycle.status(snapshot.conversationId, "approvalResolved")
                }
                KotlinTurnPhase.APPROVAL_UNAVAILABLE -> store.rejectUnavailableApproval(turnId)
                KotlinTurnPhase.AUTO_APPROVAL_PENDING -> {
                    val work = store.beginAutomaticApproval(turnId)
                    if (work != null) store.completeAutomaticApproval(turnId, work.call.callId,
                        KotlinToolApproval.review(work) { request -> store.automaticApprovalStream(work, request, provider) })
                }
                KotlinTurnPhase.AUTO_APPROVAL_REQUESTING -> store.interruptedAutomaticApproval(turnId)
                KotlinTurnPhase.READY_TOOL -> {
                    val call = store.beginTool(turnId)
                    // Some platform operations cannot acknowledge cancellation. Wait for the
                    // invocation to return before releasing its conversation reservation.
                    currentCoroutineContext().ensureActive()
                    val result = withContext(NonCancellable) { tools.execute(turnId, call) }
                    currentCoroutineContext().ensureActive()
                    store.completeTool(turnId, call, result)
                }
                // The prior process may have sent the request or started a side effect. Never repeat it.
                KotlinTurnPhase.REQUESTING_PROVIDER -> error("Provider request was interrupted; retry manually")
                KotlinTurnPhase.TITLE_REQUESTING -> store.interruptedTitle(turnId)
                KotlinTurnPhase.EXECUTING_TOOL, KotlinTurnPhase.CANCELLING_TOOL -> error("Tool execution was interrupted; its outcome is unknown")
                KotlinTurnPhase.INTERRUPTED_TOOL, KotlinTurnPhase.FINISHED, KotlinTurnPhase.FAILED, KotlinTurnPhase.CANCELLED -> return
            }
        }
    }

    private suspend fun requestProvider(round: KotlinTurnSnapshot) {
        check(round.phase == KotlinTurnPhase.REQUESTING_PROVIDER)
        var terminal = false
        store.providerStream(round, provider).transformWhile { event ->
            emit(event)
            event !is ChatStreamEvent.Done && event !is ChatStreamEvent.Error
        }.collect { event ->
            when (event) {
                is ChatStreamEvent.Content -> store.appendContent(round.id, event.text)
                is ChatStreamEvent.ReasoningDetected -> store.setReasoningFormat(round.id, event.format)
                is ChatStreamEvent.Done -> { terminal = true; store.completeProvider(round.id, event.toolCalls, event.usage) }
                is ChatStreamEvent.Error -> { terminal = true; store.fail(round.id, event.message) }
            }
        }
        if (!terminal) store.fail(round.id, "Provider stream ended without a completion event")
    }
}
