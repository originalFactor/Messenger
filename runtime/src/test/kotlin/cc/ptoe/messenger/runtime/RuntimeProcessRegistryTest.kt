/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.runtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RuntimeProcessRegistryTest {
    @Test fun failedChildWaitDoesNotReportExit() {
        val exits = mutableListOf<Int>()
        notifyConfirmedChildExit({ throw IllegalStateException("wait failed") }) { exits.add(it) }
        assertTrue(exits.isEmpty())
        notifyConfirmedChildExit({ 17 }) { exits.add(it) }
        assertEquals(listOf(17), exits)
    }

    @Test fun ownedInputPinsGenerationUntilWriteReturnsAndRejectsOldOwnerAfterReuse() {
        val registry = RuntimeProcessRegistry<String> { }
        val oldOwner = Any()
        val replacementOwner = Any()
        val old = checkNotNull(registry.reserve(7, oldOwner))
        assertTrue(registry.publish(old, "old"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val write = executor.submit<Boolean> {
                registry.withOwned(7, oldOwner) { child ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    writes.add(child)
                    true
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            registry.finished(old)
            assertNull(registry.reserve(7, replacementOwner))
            release.countDown()
            assertTrue(write.get(5, TimeUnit.SECONDS))
            val replacement = checkNotNull(registry.reserve(7, replacementOwner))
            assertTrue(registry.publish(replacement, "new"))
            assertFalse(registry.withOwned(7, oldOwner) { writes.add(it); true })
            assertTrue(registry.withOwned(7, replacementOwner) { writes.add(it); true })
            assertEquals(listOf("old", "new"), writes)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun ownerBoundStopCannotStopReplacementAfterIdReuse() {
        val closed = mutableListOf<String>()
        val registry = RuntimeProcessRegistry<String> { closed.add(it) }
        val oldOwner = Any()
        val newOwner = Any()
        val old = checkNotNull(registry.reserve(7, oldOwner))
        assertTrue(registry.publish(old, "old"))
        registry.finished(old)
        val replacement = checkNotNull(registry.reserve(7, newOwner))
        assertTrue(registry.publish(replacement, "new"))
        assertFalse(registry.stopOwned(7, oldOwner))
        assertEquals("new", registry.get(7))
        assertTrue(closed.isEmpty())
        assertTrue(registry.stopOwned(7, newOwner))
        assertEquals(listOf("new"), closed)
    }

    @Test fun ownerBoundStopWaitsForItsLaunchingChild() {
        val closed = Collections.synchronizedList(mutableListOf<String>())
        val registry = RuntimeProcessRegistry<String> { closed.add(it) }
        val owner = Any()
        val entry = checkNotNull(registry.reserve(7, owner))
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertFalse(registry.stopOwned(7, Any()))
            val stop = executor.submit<Boolean> { registry.stopOwned(7, owner) }
            registry.publish(entry, "child")
            assertTrue(stop.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("child"), closed)
        } finally { executor.shutdownNow() }
    }

    @Test fun staleCompletionCannotRemoveAReusedId() {
        val closed = mutableListOf<String>()
        val registry = RuntimeProcessRegistry<String> { closed.add(it) }
        val first = checkNotNull(registry.reserve(1))
        assertTrue(registry.publish(first, "first"))
        registry.finished(first)
        val second = checkNotNull(registry.reserve(1))
        assertTrue(registry.publish(second, "second"))
        registry.finished(first)
        registry.stop(first)
        assertEquals("second", registry.get(1))
        registry.stop(1)
        registry.finished(second)
        assertEquals(listOf("second"), closed)
    }

    @Test fun duplicateCannotReplaceALaunchingOrRunningSession() {
        val closed = mutableListOf<String>()
        val registry = RuntimeProcessRegistry<String> { closed.add(it) }
        val first = checkNotNull(registry.reserve(1))
        assertNull(registry.reserve(1))
        assertTrue(registry.publish(first, "child"))
        assertNull(registry.reserve(1))
        registry.stop(1)
        registry.finished(first)
        assertEquals(listOf("child"), closed)
        assertNotNull(registry.reserve(1))
    }

    @Test fun earlyExitNeverPublishesAStaleDirectChild() {
        val closed = mutableListOf<String>()
        val registry = RuntimeProcessRegistry<String> { closed.add(it) }
        val first = checkNotNull(registry.reserve(1))
        registry.finished(first)
        assertNull(registry.reserve(1))
        assertFalse(registry.publish(first, "finished-child"))
        val second = checkNotNull(registry.reserve(1))
        assertTrue(registry.publish(second, "running-child"))
        assertEquals("running-child", registry.get(1))
        assertEquals(listOf("finished-child"), closed)
    }

    @Test fun oldCallbacksAndPendingLaunchFenceTheNextGeneration() {
        val registry = RuntimeProcessRegistry<String> { }
        val entry = checkNotNull(registry.reserve(1))
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val callback = executor.submit { registry.finished(entry) {
                callbackEntered.countDown()
                check(releaseCallback.await(5, TimeUnit.SECONDS))
            } }
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
            assertNull(registry.reserve(1))
            var lateDelivered = false
            registry.deliver(entry) { lateDelivered = true }
            assertFalse(lateDelivered)
            val draining = executor.submit { registry.stopAll(); registry.awaitDrained() }
            assertFalse(draining.isDone)
            assertFalse(registry.publish(entry, "already-exited-child"))
            assertFalse(draining.isDone)
            releaseCallback.countDown()
            callback.get(5, TimeUnit.SECONDS)
            draining.get(5, TimeUnit.SECONDS)
            assertNull(registry.reserve(1))
        } finally { releaseCallback.countDown(); executor.shutdownNow() }
    }

    @Test fun recreatedServiceIsFencedUntilPreviousDrainCompletes() {
        val gate = RuntimeSessionShutdownGate()
        assertEquals("started", gate.admit { "started" })
        gate.begin()
        assertNull(gate.admit { "unsafe" })
        gate.begin()
        gate.complete()
        assertNull(gate.admit { "unsafe" })
        gate.complete()
        assertEquals("started", gate.admit { "started" })
    }

    @Test fun failedCloseDoesNotReleaseAnUncertainSessionId() {
        val registry = RuntimeProcessRegistry<String> { error("stop failed") }
        val entry = checkNotNull(registry.reserve(1))
        assertTrue(registry.publish(entry, "child"))
        try { registry.stop(1); fail("Expected stop failure") } catch (_: IllegalStateException) { }
        registry.finished(entry)
        assertNull(registry.reserve(1))
        assertNull(registry.get(1))
    }

    @Test fun stoppedLaunchClosesChildBeforeStopReturns() {
        val closed = Collections.synchronizedList(mutableListOf<String>())
        val registry = RuntimeProcessRegistry<String> { closed.add(it) }
        val entry = checkNotNull(registry.reserve(1))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val stopping = executor.submit { registry.stop(1) }
            // Publishing either before or after the stop request must close the same child.
            registry.publish(entry, "child")
            stopping.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("child"), closed)
            assertNull(registry.get(1))
        } finally { executor.shutdownNow() }
    }

    @Test fun concurrentStopsCannotReleaseIdWhileCloseIsStillRunning() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val registry = RuntimeProcessRegistry<String> { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val entry = checkNotNull(registry.reserve(1))
        assertTrue(registry.publish(entry, "child"))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { registry.stop(1) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = executor.submit { registry.stop(1) }
            registry.finished(entry)
            assertNull(registry.reserve(1))
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertNotNull(registry.reserve(1))
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun stoppingAllRejectsNewWorkAndWaitsForLaunchingChild() {
        val closed = Collections.synchronizedList(mutableListOf<String>())
        val registry = RuntimeProcessRegistry<String> { closed.add(it) }
        val entry = checkNotNull(registry.reserve(1))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val stopping = executor.submit { registry.stopAll() }
            registry.publish(entry, "child")
            stopping.get(5, TimeUnit.SECONDS)
            assertNull(registry.reserve(2))
            assertEquals(listOf("child"), closed)
        } finally { executor.shutdownNow() }
    }
}
