/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class AcpRuntimeConnectionsTest {
    @Test fun cancelledTurnCannotDispatchInputAfterBlockingStartReturns() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = Job()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var sends = 0
        val worker = scope.async {
            entered.countDown()
            release.await()
            requireAcpTurnActive(owner) // after the uninterruptible start returns
            requireAcpTurnActive(owner) // before a frame is dispatched
            sends++
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            owner.cancel()
            release.countDown()
            try {
                worker.await()
                fail("A cancelled turn must not dispatch")
            } catch (_: CancellationException) { }
            assertEquals(0, sends)
        } finally { release.countDown(); scope.cancel() }
    }

    @Test fun cancelledTurnCannotDispatchALaterFrame() {
        val owner = Job()
        requireAcpTurnActive(owner)
        owner.cancel()
        try {
            requireAcpTurnActive(owner)
            fail("A cancelled turn must not dispatch")
        } catch (_: CancellationException) { }
    }

    @Test fun blockedInputIsStoppedBeforeWaitingForItsCompletion() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val input = scope.launch { entered.countDown(); release.await() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            finishAcpInput(input, 1_000) { release.countDown() }
            assertTrue(input.isCompleted)
        } finally { release.countDown(); scope.cancel() }
    }

    @Test fun blockedInputAfterStopIsReportedUnverified() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val input = scope.launch { entered.countDown(); release.await() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            try {
                finishAcpInput(input, 20) { }
                fail("A still-blocked Binder input cannot prove cleanup")
            } catch (failure: IllegalStateException) {
                assertTrue(failure.message.orEmpty().contains("unverified"))
            }
        } finally { release.countDown(); scope.cancel() }
    }

    @Test fun blockedStopIsReportedUnverifiedEvenAfterInputReturns() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val input = scope.launch { }
        try {
            try {
                finishAcpInput(input, 200) { entered.countDown(); release.await() }
                fail("A still-blocked Binder stop cannot prove cleanup")
            } catch (failure: IllegalStateException) {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertTrue(failure.message.orEmpty().contains("unverified"))
            }
        } finally { release.countDown(); scope.cancel() }
    }

    @Test fun disconnectOnlyFailsSessionsOnTheOriginalBinder() {
        val registry = AcpRuntimeConnections<Any>()
        val oldBinder = Any()
        val replacement = Any()
        val oldEvents = mutableListOf<Throwable?>()
        val newEvents = mutableListOf<Throwable?>()
        val old = registry.register(1, oldBinder, oldEvents::add)
        val current = registry.register(2, replacement, newEvents::add)
        registry.disconnect(oldBinder)
        assertEquals(1, oldEvents.size)
        assertTrue(oldEvents.single() is IllegalStateException)
        assertSame(oldEvents.single(), old.terminalError)
        assertTrue(newEvents.isEmpty())
        registry.closed(old)
        assertEquals(1, oldEvents.size)
        registry.closed(current)
        assertEquals(listOf(null), newEvents)
    }

    @Test fun oldCallbacksCannotEndAReplacementSessionWithTheSameId() {
        val registry = AcpRuntimeConnections<Any>()
        val events = mutableListOf<String>()
        val old = registry.register(7, Any()) { events.add("old") }
        registry.abandon(old)
        val replacement = registry.register(7, Any()) { events.add("new") }
        registry.closed(old)
        registry.lost(old)
        assertTrue(events.isEmpty())
        registry.closed(replacement)
        assertEquals(listOf("new"), events)
    }

    @Test fun confirmedExitDoesNotBecomeAFalseDisconnect() {
        val registry = AcpRuntimeConnections<Any>()
        val binder = Any()
        val events = mutableListOf<Throwable?>()
        val session = registry.register(1, binder, events::add)
        registry.closed(session)
        registry.disconnect(binder)
        assertEquals(listOf(null), events)
        assertNull(session.terminalError)
    }

    @Test fun exitAndDisconnectRaceCompletesExactlyOnce() {
        val registry = AcpRuntimeConnections<Any>()
        val binder = Any()
        val events = Collections.synchronizedList(mutableListOf<Throwable?>())
        val session = registry.register(1, binder, events::add)
        val start = CountDownLatch(1)
        val exit = Thread { start.await(); registry.closed(session) }
        val disconnect = Thread { start.await(); registry.disconnect(binder) }
        exit.start(); disconnect.start(); start.countDown()
        exit.join(TimeUnit.SECONDS.toMillis(5)); disconnect.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse(exit.isAlive || disconnect.isAlive)
        assertEquals(1, events.size)
        assertSame(events.single(), session.terminalError)
    }

    @Test fun nonzeroExitIsAnErrorUnlessStopWasRequested() {
        val registry = AcpRuntimeConnections<Any>()
        val crashed = mutableListOf<Throwable?>()
        val stopped = mutableListOf<Throwable?>()
        val unexpected = registry.register(1, Any(), crashed::add)
        val deliberate = registry.register(2, Any(), stopped::add)
        registry.closed(unexpected, 17)
        registry.beginStop(deliberate)
        registry.closed(deliberate, 137)
        assertTrue(crashed.single() is IllegalStateException)
        assertTrue(crashed.single()?.message.orEmpty().contains("17"))
        assertEquals(listOf(null), stopped)
    }

    @Test fun waitRequiresTheExactSessionExitOrLoss() = runBlocking {
        val registry = AcpRuntimeConnections<Any>()
        val old = registry.register(1, Any()) { }
        val other = registry.register(2, Any()) { }
        val waiting = async { registry.awaitEnd(old, 5_000) }
        yield()
        registry.closed(other)
        assertFalse(waiting.isCompleted)
        registry.closed(old)
        assertTrue(waiting.await())
        assertNull(old.terminalError)
    }

    @Test fun missingExitIsUnverifiedAndBinderLossEndsTheWaitWithAnError() = runBlocking {
        val registry = AcpRuntimeConnections<Any>()
        val session = registry.register(1, Any()) { }
        assertFalse(registry.awaitEnd(session, 20))
        registry.lost(session)
        assertTrue(registry.awaitEnd(session, 1_000))
        assertTrue(session.terminalError is IllegalStateException)
    }

    @Test fun acceptedStopWaitsForItsOwnCallbackAndRejectedStartNeverStopsAnId() = runBlocking {
        val registry = AcpRuntimeConnections<Any>()
        val session = registry.register(1, Any()) { }
        val other = registry.register(2, Any()) { }
        var stops = 0
        registry.stopAndAwaitEnd(session, false, 1_000) { stops++ }
        assertEquals(0, stops)
        val stopped = CompletableDeferred<Unit>()
        val cleanup = async { registry.stopAndAwaitEnd(session, true, 5_000) { stops++; stopped.complete(Unit) } }
        withTimeout(1_000) { stopped.await() }
        registry.closed(other)
        assertFalse(cleanup.isCompleted)
        registry.closed(session)
        withTimeout(1_000) { cleanup.await() }
        assertEquals(1, stops)
    }

    @Test fun stoppedProcessWithoutExitCallbackRemainsUnverified() = runBlocking {
        val registry = AcpRuntimeConnections<Any>()
        val session = registry.register(1, Any()) { }
        try {
            registry.stopAndAwaitEnd(session, true, 20) { }
            fail("Expected an unverified stop")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("callback"))
        }
    }

    @Test fun completedSessionDoesNotStopAReusedId() = runBlocking {
        val registry = AcpRuntimeConnections<Any>()
        val old = registry.register(1, Any()) { }
        registry.closed(old)
        val replacement = registry.register(1, Any()) { }
        registry.stopAndAwaitEnd(old, true, 1_000) { fail("Unexpected stop") }
        assertFalse(replacement.ended.isCompleted)
    }
}
