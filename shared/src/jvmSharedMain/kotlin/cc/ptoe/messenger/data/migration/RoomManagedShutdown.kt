/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface RoomShutdownState {
    data object Ready : RoomShutdownState
    data object Running : RoomShutdownState
    data object Stopping : RoomShutdownState
    data class RecoveryRequired(val cause: Throwable) : RoomShutdownState
    data object Stopped : RoomShutdownState
}

/**
 * Single-use lifecycle for an isolated Room host. stopAndJoin must block new work and prove every
 * Room user and worker (including Cloud and ACP) has stopped and cannot resume. It must support
 * retries after partial failure and may only return when the proof holds. Additional participants
 * register before starting their workers; each successful action must permanently prevent those
 * workers from resuming. Actions run in reverse registration order, even when one fails; only
 * failed actions are retried.
 * The platform must supply the implementations; this class does not itself establish that proof.
 *
 * On failure the original startup remains suspended with Room, the Core source lease and the
 * ownership block live. A separate recovery coordinator may retry the same stop operation. Process
 * death releases OS locks; a durable owner must then require separate fenced offline recovery.
 */
internal class RoomManagedShutdown(private val stopAndJoin: suspend () -> Unit) {
    private data class StopAction(val action: suspend () -> Unit, var completed: Boolean = false)
    private val mutex = Mutex()
    private val actions = mutableListOf(StopAction(stopAndJoin))
    private val stopped = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow<RoomShutdownState>(RoomShutdownState.Ready)
    val state: StateFlow<RoomShutdownState> = mutableState.asStateFlow()

    /** Register before starting the corresponding worker or exposing it to other code. */
    suspend fun registerStop(action: suspend () -> Unit) = mutex.withLock {
        check(mutableState.value == RoomShutdownState.Ready || mutableState.value == RoomShutdownState.Running) {
            "Room shutdown has begun"
        }
        actions.add(StopAction(action))
        Unit
    }

    internal suspend fun enter() = mutex.withLock {
        check(mutableState.value == RoomShutdownState.Ready) { "Room shutdown lifecycle is single-use" }
        mutableState.value = RoomShutdownState.Running
    }

    internal suspend fun stopAndAwait() = withContext(NonCancellable) {
        mutex.withLock {
            when (mutableState.value) {
                RoomShutdownState.Running -> attemptStop()
                RoomShutdownState.Stopped, is RoomShutdownState.RecoveryRequired -> Unit
                else -> error("Room shutdown lifecycle has not entered")
            }
        }
        stopped.await()
    }

    /** Must be called from an independent recovery scope while startup remains suspended. */
    suspend fun retryStop() = withContext(NonCancellable) {
        mutex.withLock {
            check(mutableState.value is RoomShutdownState.RecoveryRequired) { "Room shutdown is not awaiting recovery" }
            attemptStop()
        }
    }

    /** Keep ownership held throughout recovery and serialize it with final shutdown/release. */
    internal suspend fun <T> withRecovery(block: suspend () -> T): T = withContext(NonCancellable) {
        mutex.withLock {
            check(mutableState.value is RoomShutdownState.RecoveryRequired) { "Room shutdown is not awaiting recovery" }
            block()
        }
    }

    private suspend fun attemptStop() {
        mutableState.value = RoomShutdownState.Stopping
        var failed: Throwable? = null
        for (entry in actions.asReversed()) {
            if (entry.completed) continue
            try {
                entry.action()
                entry.completed = true
            } catch (failure: Throwable) {
                if (failed == null) failed = failure else if (failed !== failure) failed.addSuppressed(failure)
            }
        }
        if (failed == null) {
            mutableState.value = RoomShutdownState.Stopped
            stopped.complete(Unit)
        } else mutableState.value = RoomShutdownState.RecoveryRequired(failed)
    }
}
