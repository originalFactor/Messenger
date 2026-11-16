/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.schedule

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Recovery is allowed only after the caller proves every prior-process worker is stopped. */
internal fun interface ScheduledRecoveryBarrier { suspend fun awaitPreviousWorkersStopped() }

/** Host must record its conversation before sending a provider request and must not return on
 * cancellation until every provider/tool side effect owned by the turn has actually stopped. */
internal fun interface ScheduledTurnHost {
    suspend fun run(scope: ScheduledExecutionScope, recordConversation: suspend (String) -> Boolean): String
}

internal interface ScheduledTaskLifecycle {
    fun begin(taskId: String) {}
    fun end(taskId: String) {}
    fun status(taskId: String, status: String) {}
    fun schedulerFailed(message: String) {}
}

/** Unconnected process-owned loop; callers own Android/Desktop service lifetime. */
internal class RoomScheduledTaskRunner(
    private val store: RoomScheduledTasks,
    private val host: ScheduledTurnHost,
    private val availableNames: suspend () -> Set<String>,
    private val recoveryBarrier: ScheduledRecoveryBarrier,
    private val owner: CoroutineScope,
    private val now: () -> Long,
    private val lifecycle: ScheduledTaskLifecycle = object : ScheduledTaskLifecycle {},
    private val claimDue: suspend (Long) -> List<Pair<KotlinScheduledTask, ScheduledRunClaim>> = { store.claimDue(it) },
) {
    private val lock = Mutex()
    private var loop: Job? = null
    private val workers = mutableMapOf<String, Job>()

    suspend fun start(): Job = lock.withLock {
        loop?.takeIf { it.isActive }?.let { return@withLock it }
        check(workers.values.none { it.isActive }) { "Scheduled workers are still stopping" }
        recoveryBarrier.awaitPreviousWorkersStopped()
        store.recoverInterrupted()
        owner.launch {
            try {
                while (isActive) { tick(); delay(5_000) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { lifecycle.schedulerFailed(failure.message ?: "Scheduled task loop failed") }
        }.also { loop = it }
    }

    /** One process-owned poll, exposed for deterministic validation. */
    suspend fun tick() {
        val claims = claimDue(now())
        for ((_, claim) in claims) {
            val worker = owner.launch(start = CoroutineStart.LAZY) { execute(claim) }
            lock.withLock { workers[claim.token] = worker }
            worker.invokeOnCompletion { owner.launch { lock.withLock { if (workers[claim.token] === worker) workers.remove(claim.token) } } }
            worker.start()
        }
    }

    private suspend fun execute(claim: ScheduledRunClaim) {
        var failure: String? = null
        var conversationId: String? = null
        var began = false
        try {
            lifecycle.begin(claim.taskId)
            began = true
            coroutineScope {
                val execution = async {
                    val scope = store.executionScope(claim, availableNames())
                    val returnedId = host.run(scope) { id ->
                        require(id.isNotBlank()) { "Scheduled conversation ID is empty" }
                        val saved = store.recordConversation(claim, id)
                        if (saved) conversationId = id
                        saved
                    }
                    check(conversationId != null && conversationId == returnedId) { "Scheduled host did not record its conversation" }
                    returnedId
                }
                val watcher = launch {
                    store.observeActive(claim.taskId).first { it == null || it.token != claim.token || it.cancelRequested }
                    execution.cancel(CancellationException("Scheduled task was stopped"))
                }
                try { conversationId = execution.await() }
                finally { watcher.cancelAndJoin() }
            }
        } catch (cancelled: CancellationException) {
            failure = "Execution interrupted or cancelled"
            throw cancelled
        } catch (error: Exception) {
            failure = error.message ?: "Scheduled task failed"
        } finally {
            withContext(NonCancellable) {
                try {
                    val status = store.finishWithStatus(claim, failure, conversationId)
                    if (status != null) lifecycle.status(claim.taskId, status)
                } finally { if (began) lifecycle.end(claim.taskId) }
            }
        }
    }
}
