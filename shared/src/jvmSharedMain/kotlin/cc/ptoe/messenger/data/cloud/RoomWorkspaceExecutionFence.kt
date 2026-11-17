/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Supplied only by a trusted executor after all work and children are known to have stopped. */
internal sealed interface WorkspaceExecutionOutcome {
    data class Stopped(val output: String, val isError: Boolean) : WorkspaceExecutionOutcome
    data object Unknown : WorkspaceExecutionOutcome
}

internal fun interface WorkspaceExecutionRunner {
    suspend fun executeAndAwaitStop(grant: RoomInboundWorkspaceGrant): WorkspaceExecutionOutcome
}

/** The exact durable reference must be bound to the host invocation and stop evidence. */
internal fun interface DurableWorkspaceExecutionRunner {
    suspend fun executeAndAwaitStop(ref: WorkspaceInvocationRef, grant: RoomInboundWorkspaceGrant): WorkspaceExecutionOutcome
}

/** An output is not evidence the receiving peer obtained it; the reservation remains active. */
internal class DurableWorkspaceExecutionResult(val ref: WorkspaceInvocationRef, val outcome: WorkspaceExecutionOutcome.Stopped)

/**
 * Prototype admission and all-writer fence for exactly one Room owner. The owner must
 * route every session/project/workspace/conversation mutation through [mutate] and
 * retain this instance for its full lifetime. The older in-memory execution path is disabled
 * because it cannot prevent replay after a restart. The durable path keeps a reservation across
 * restart, but still requires a real all-writer/Cloud invalidation fence, exclusive Room
 * ownership and a platform proof for each exact worker and its descendants. Not installed.
 */
internal class RoomWorkspaceExecutionFence private constructor(db: LocalDatabase) {
    companion object {
        private val instances = WeakHashMap<LocalDatabase, WeakReference<RoomWorkspaceExecutionFence>>()
        fun forDatabase(db: LocalDatabase): RoomWorkspaceExecutionFence = synchronized(instances) {
            instances[db]?.get() ?: RoomWorkspaceExecutionFence(db).also { instances[db] = WeakReference(it) }
        }
    }
    private val ledger = RoomWorkspaceInvocationLedger(db)
    private val mutex = Mutex()
    private val invalidated = Collections.synchronizedSet(mutableSetOf<SyncSession>())
    private val inFlight = Collections.synchronizedMap(mutableMapOf<WorkspaceInvocationRef, Job>())

    /** Invalidates admission for one exact Cloud session before waiting for any worker or fence. */
    internal fun invalidateSession(accountId: String, generation: String) {
        val session = SyncSession(accountId, generation)
        val jobs = synchronized(invalidated) {
            invalidated.add(session)
            inFlight.values.toList()
        }
        jobs.forEach { it.cancel(CancellationException("Cloud session revoked")) }
    }

    private fun requireValid(scope: KotlinRemoteControlScope) {
        check(SyncSession(scope.accountId, scope.generation) !in invalidated) { "Cloud session expired" }
    }

    suspend fun <T> mutate(block: suspend () -> T): T = mutex.withLock {
        check(ledger.pending() == null && invalidated.isEmpty()) { "Workspace execution requires recovery" }
        block()
    }

    /** The former in-memory-only path cannot safely coexist with persistent replay protection. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun execute(scope: KotlinRemoteControlScope, call: RoomInboundWorkspaceCall, runner: WorkspaceExecutionRunner): WorkspaceExecutionOutcome.Stopped =
        error("In-memory workspace execution is unavailable; durable admission is required")

    /**
     * Persist the one-shot identity before handing it to a trusted runner. An exception,
     * cancellation or unknown result retains the durable reservation; it never retries.
     * A returned result also stays reserved until a caller explicitly confirms the receiver
     * obtained it. The enclosing owner must independently stop and join any worker after
     * an exception. No result payload is persisted by this prototype.
     */
    suspend fun executeDurable(scope: KotlinRemoteControlScope, cookie: String, call: RoomInboundWorkspaceCall,
        runner: DurableWorkspaceExecutionRunner): DurableWorkspaceExecutionResult = mutex.withLock {
        requireValid(scope)
        val admission = when (val result = ledger.admit(scope, cookie, call)) {
            is WorkspaceInvocationAdmission.Admitted -> result
            WorkspaceInvocationAdmission.Busy -> error("Workspace execution requires recovery")
            WorkspaceInvocationAdmission.Duplicate -> error("Workspace request was already admitted")
        }
        currentCoroutineContext().ensureActive()
        val job = checkNotNull(currentCoroutineContext()[Job])
        inFlight[admission.ref] = job
        try {
            when (val outcome = runner.executeAndAwaitStop(admission.ref, admission.grant)) {
                WorkspaceExecutionOutcome.Unknown -> error("Workspace execution outcome is unknown; recovery is required")
                is WorkspaceExecutionOutcome.Stopped -> {
                    currentCoroutineContext().ensureActive()
                    DurableWorkspaceExecutionResult(admission.ref, outcome)
                }
            }
        } finally { withContext(NonCancellable) { inFlight.remove(admission.ref, job) } }
    }

    /** Call only after the result is received. The barrier must hold exact-worker exclusion. */
    suspend fun confirmResultDelivered(ref: WorkspaceInvocationRef, barrier: WorkspaceInvocationStopBarrier) = mutex.withLock {
        check(invalidated.isEmpty()) { "Cloud session expired" }
        ledger.complete(ref, barrier)
    }

    /** Explicitly record an unknown result after independently proving that worker stopped. */
    suspend fun acknowledgeUnknown(ref: WorkspaceInvocationRef, barrier: WorkspaceInvocationStopBarrier) = mutex.withLock {
        ledger.acknowledgeUnknown(ref, barrier)
    }

    /** An opaque pending identity does not itself establish stop or disclose arguments. */
    suspend fun pendingRecovery(): WorkspaceInvocationRef? = mutex.withLock { ledger.pending() }
}
