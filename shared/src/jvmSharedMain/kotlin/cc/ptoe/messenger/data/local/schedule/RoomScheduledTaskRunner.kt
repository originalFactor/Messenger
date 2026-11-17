/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.schedule

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Recovery is allowed only after the caller proves every prior-process worker is stopped. */
internal fun interface ScheduledRecoveryBarrier { suspend fun awaitPreviousWorkersStopped() }

/** Host must record its conversation before sending a provider request and must not return on
 * cancellation until every provider/tool side effect owned by the turn has actually stopped. */
internal fun interface ScheduledTurnHost {
    suspend fun run(scope: ScheduledExecutionScope, recordConversation: suspend (String) -> Boolean): String
}

internal interface ScheduledTaskLifecycle {
    /** Hold foreground ownership until every committed claim is handed off or retired. */
    fun beginAdmission(): () -> Unit = {}
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
    private val workers = ConcurrentHashMap<String, Job>()
    private var activeTicks = 0

    suspend fun start(): Job = lock.withLock {
        loop?.let {
            if (it.isActive) return@withLock it
            check(it.isCompleted) { "Scheduled loop is still stopping" }
        }
        check(activeTicks == 0) { "Scheduled claim handoff is still stopping" }
        check(workers.values.all { it.isCompleted }) { "Scheduled workers are still stopping" }
        val ownerJob = owner.coroutineContext[Job]
        check(ownerJob?.isActive != false) { "Scheduled owner is stopping" }
        recoveryBarrier.awaitPreviousWorkersStopped()
        check(ownerJob?.isActive != false) { "Scheduled owner is stopping" }
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
        currentCoroutineContext().ensureActive()
        lock.withLock { activeTicks++ }
        try { tickAdmitted() }
        finally { withContext(NonCancellable) { lock.withLock { activeTicks-- } } }
    }

    private suspend fun tickAdmitted() {
        val caller = currentCoroutineContext()[Job]
        val ownerJob = owner.coroutineContext[Job]
        val timestamp = now()
        require(timestamp > 0) { "Invalid scheduled task time" }
        // Do not start a foreground service on every idle poll. A task that becomes
        // due after this snapshot will be claimed by the next poll.
        if (store.list().none { it.enabled && it.nextRunAt <= timestamp }) return
        withContext(NonCancellable) {
            if (caller?.isActive == false || ownerJob?.isActive == false) return@withContext
            val endAdmission = lifecycle.beginAdmission()
            try {
                // A committed batch must be handed to workers or retired before cancellation returns.
                val claims = claimDue(timestamp)
                var failed: Throwable? = null
                for ((_, claim) in claims) {
                    try {
                        if (caller?.isActive == false || ownerJob?.isActive == false) {
                            retireUnstarted(claim)
                            continue
                        }
                        val started = CompletableDeferred<Boolean>()
                        val worker = owner.launch(start = CoroutineStart.LAZY) {
                            execute(claim) { started.complete(true) }
                        }
                        lock.withLock { workers[claim.token] = worker }
                        worker.invokeOnCompletion {
                            started.complete(false)
                            workers.remove(claim.token, worker)
                        }
                        worker.start()
                        // A cancelled lazy job may never enter its body or run execute's finally.
                        if (!started.await()) retireUnstarted(claim)
                    } catch (failure: Throwable) {
                        if (failed == null) failed = failure else if (failed !== failure) failed.addSuppressed(failure)
                    }
                }
                failed?.let { throw it }
            } finally { endAdmission() }
        }
    }

    private suspend fun retireUnstarted(claim: ScheduledRunClaim) {
        val status = store.finishWithStatus(claim, "Execution interrupted or cancelled", null)
        if (status != null) lifecycle.status(claim.taskId, status)
    }

    private suspend fun execute(claim: ScheduledRunClaim, onStarted: () -> Unit) {
        var failure: String? = null
        var conversationId: String? = null
        var began = false
        try {
            lifecycle.begin(claim.taskId)
            began = true
            onStarted()
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
                    val status = try { store.finishWithStatus(claim, failure, conversationId) }
                    catch (error: Exception) { lifecycle.schedulerFailed(error.message ?: "Scheduled task cleanup requires recovery"); throw error }
                    if (status != null) lifecycle.status(claim.taskId, status)
                } finally { if (began) lifecycle.end(claim.taskId) }
            }
        }
    }
}
