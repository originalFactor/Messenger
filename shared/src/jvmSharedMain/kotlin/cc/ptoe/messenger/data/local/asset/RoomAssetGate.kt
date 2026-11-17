/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One asset gate shared by all services belonging to one Room owner. The platform must also
 * exclude every other process and Room owner; this mutex alone cannot provide that guarantee.
 */
internal class RoomAssetGate : CoreAssetGate {
    private val closing = AtomicBoolean(false)
    private val mutex = Mutex()

    override suspend fun <T> withExclusiveAssets(block: suspend () -> T): T = mutex.withLock {
        check(!closing.get()) { "Asset owner is shutting down" }
        block()
    }

    /** The caller must independently prove all asset users have stopped before offline recovery. */
    override suspend fun <T> withRecoveryAssets(block: suspend () -> T): T = mutex.withLock {
        check(closing.get()) { "Stop the asset gate before recovery" }
        block()
    }

    /** Reject new operations and wait for the admitted operation; call outside an asset block. */
    suspend fun shutdown() = withContext(NonCancellable) {
        closing.set(true)
        mutex.withLock { }
    }
}
