/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.wear

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WearRequestLifetimeTest {
    @Test fun ownerDrainRejectsNewWorkAndWaitsForRequestCleanup(): Unit = runBlocking {
        val registry = WearRequestRegistry()
        val stops = AtomicInteger()
        val owner = WearRequestLifetime { stops.incrementAndGet() }
        assertTrue(registry.register(owner))
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        assertTrue(owner.launch {
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
        })
        withTimeout(5_000) { entered.await() }
        val drain = async(start = CoroutineStart.UNDISPATCHED) { registry.stopAndJoin() }
        withTimeout(5_000) { cleaning.await() }
        assertFalse(drain.isCompleted)
        assertFalse(owner.launch { error("must not start") })
        assertFalse(registry.register(WearRequestLifetime { }))
        assertEquals(1, stops.get())
        release.complete(Unit)
        withTimeout(5_000) { drain.await() }
        registry.stopAndJoin()
        assertEquals(1, stops.get())
    }

    @Test fun destroyedServiceRemainsOwnedUntilItsRequestsFinish(): Unit = runBlocking {
        val registry = WearRequestRegistry()
        val first = WearRequestLifetime { }
        val second = WearRequestLifetime { }
        assertTrue(registry.register(first))
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        assertTrue(first.launch {
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
        })
        withTimeout(5_000) { entered.await() }
        registry.retire(first)
        withTimeout(5_000) { cleaning.await() }
        assertTrue(registry.register(second))
        val drain = async(start = CoroutineStart.UNDISPATCHED) { registry.stopAndJoin() }
        assertFalse(drain.isCompleted)
        assertFalse(second.accepting)
        release.complete(Unit)
        withTimeout(5_000) { drain.await() }
    }

    @Test fun replacementWaitsForPreviousSocketShutdown(): Unit = runBlocking {
        val registry = WearRequestRegistry()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        registry.cleanupTransport { cleanupStarted.complete(Unit); releaseCleanup.await() }
        withTimeout(5_000) { cleanupStarted.await() }
        val barrier = async(start = CoroutineStart.UNDISPATCHED) { registry.awaitTransportCleanup() }
        assertFalse(barrier.isCompleted)
        val replacement = WearRequestLifetime { }
        assertTrue(registry.register(replacement))
        assertTrue(replacement.launch { registry.awaitTransportCleanup(); started.complete(Unit) })
        assertFalse(started.isCompleted)
        releaseCleanup.complete(Unit)
        withTimeout(5_000) { barrier.await(); started.await() }
        registry.stopAndJoin()
    }

    @Test fun failedSocketShutdownPreventsReplacementBinding(): Unit = runBlocking {
        val registry = WearRequestRegistry()
        val started = CompletableDeferred<Unit>()
        registry.cleanupTransport { error("socket stop failed") }
        val replacement = WearRequestLifetime { }
        assertTrue(registry.register(replacement))
        val failed = CompletableDeferred<String>()
        assertTrue(replacement.launch {
            try { registry.awaitTransportCleanup(); started.complete(Unit) }
            catch (failure: Exception) { failed.complete(failure.message.orEmpty()) }
        })
        assertEquals("socket stop failed", withTimeout(5_000) { failed.await() })
        assertFalse(started.isCompleted)
        registry.stopAndJoin()
    }
}
