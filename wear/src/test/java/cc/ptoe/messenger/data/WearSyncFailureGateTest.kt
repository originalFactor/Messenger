/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WearSyncFailureGateTest {
    @Test fun failedSyncBlocksQueuedAndFuturePollsUntilUserRetries() = runBlocking {
        val gate = WearSyncFailureGate()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var requests = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run { requests++; entered.complete(Unit); finish.await(); error("Database unavailable") }
        }
        entered.await()
        val queued = async(start = CoroutineStart.UNDISPATCHED) { gate.run { requests++ } }
        finish.complete(Unit)
        assertFalse(first.await())
        assertFalse(queued.await())
        assertEquals("Database unavailable", gate.error.value)
        assertFalse(gate.run { requests++ })
        assertEquals(1, requests)
        assertFalse(gate.run(explicitRetry = true) { requests++; error("Still unavailable") })
        assertEquals("Still unavailable", gate.error.value)
        assertTrue(gate.run(explicitRetry = true) { requests++ })
        assertNull(gate.error.value)
        assertTrue(gate.run { requests++ })
        assertEquals(4, requests)
    }

    @Test fun cancellationDoesNotCreateOrClearAnError() = runBlocking {
        val gate = WearSyncFailureGate()
        assertFalse(gate.run { error("Original failure") })
        val entered = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run(explicitRetry = true) { entered.complete(Unit); CompletableDeferred<Unit>().await() }
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals("Original failure", gate.error.value)
        assertFalse(gate.run { error("Must not run") })
    }
}
