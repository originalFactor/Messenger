/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Cooperative in-process reader/writer gate for one exclusively owned Room instance. A worker
 * keeps a reader lease until its stop is proved; a writer blocks later readers and waits for all
 * earlier workers. This cannot fence another process, another instance, or a writer that bypasses
 * this object. Platform ownership and exhaustive writer integration are still required.
 */
internal class InProcessAcpConfigurationFence : AcpConfigurationFence {
    private val admission = Mutex()
    private val state = Mutex()
    private var readers = 0
    private var drained = CompletableDeferred<Unit>().also { it.complete(Unit) }

    override suspend fun acquire(binding: AcpWorkerBinding): AcpConfigurationLease {
        var acquired: Lease? = null
        try {
            admission.withLock {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    state.withLock {
                        check(readers < 256) { "Too many ACP configuration readers" }
                        if (readers++ == 0) drained = CompletableDeferred()
                        acquired = Lease()
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            return checkNotNull(acquired)
        } catch (failure: Throwable) {
            withContext(NonCancellable) { acquired?.release() }
            throw failure
        }
    }

    override suspend fun <T> withExclusiveWriter(action: suspend () -> T): T = admission.withLock {
        val wait = state.withLock { drained }
        wait.await()
        currentCoroutineContext().ensureActive()
        action()
    }

    private inner class Lease : AcpConfigurationLease {
        private val released = AtomicBoolean(false)
        override suspend fun release() = withContext(NonCancellable) {
            if (released.compareAndSet(false, true)) state.withLock {
                check(readers > 0) { "ACP configuration lease is invalid" }
                if (--readers == 0) drained.complete(Unit)
            }
            Unit
        }
    }
}
