/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Supplied only by a trusted executor after all work and children are known to have stopped. */
internal sealed interface WorkspaceExecutionOutcome {
    data class Stopped(val output: String, val isError: Boolean) : WorkspaceExecutionOutcome
    data object Unknown : WorkspaceExecutionOutcome
}

internal fun interface WorkspaceExecutionRunner {
    suspend fun executeAndAwaitStop(grant: RoomInboundWorkspaceGrant): WorkspaceExecutionOutcome
}

/**
 * Prototype admission and all-writer fence for exactly one Room owner. The owner must
 * route every session/project/workspace/conversation mutation through [mutate] and
 * retain this instance for its full lifetime. Uncertain stop permanently poisons this
 * instance. Process recovery needs an independent durable ledger and stop proof.
 */
internal class RoomWorkspaceExecutionFence private constructor(db: LocalDatabase) {
    companion object {
        private val instances = WeakHashMap<LocalDatabase, WeakReference<RoomWorkspaceExecutionFence>>()
        fun forDatabase(db: LocalDatabase): RoomWorkspaceExecutionFence = synchronized(instances) {
            instances[db]?.get() ?: RoomWorkspaceExecutionFence(db).also { instances[db] = WeakReference(it) }
        }
    }
    private val authority = RoomWorkspaceRelayAuthority(db)
    private val mutex = Mutex()
    private val seen = mutableSetOf<Pair<String, String>>()
    private var uncertain = false

    suspend fun <T> mutate(block: suspend () -> T): T = mutex.withLock {
        check(!uncertain) { "Workspace execution requires recovery" }
        block()
    }

    suspend fun execute(scope: KotlinRemoteControlScope, call: RoomInboundWorkspaceCall, runner: WorkspaceExecutionRunner): WorkspaceExecutionOutcome.Stopped = mutex.withLock {
        check(!uncertain) { "Workspace execution requires recovery" }
        val key = call.fromDeviceId to call.callId
        check(key !in seen && seen.size < 10_000) { "Workspace request was already admitted or the replay limit was reached" }
        val grant = authority.prepare(scope, call)
        seen.add(key)
        val result = try { runner.executeAndAwaitStop(grant) } catch (failure: Throwable) {
            uncertain = true
            throw failure
        }
        when (result) {
            is WorkspaceExecutionOutcome.Stopped -> result
            WorkspaceExecutionOutcome.Unknown -> {
                uncertain = true
                error("Workspace execution outcome is unknown; recovery is required")
            }
        }
    }
}
