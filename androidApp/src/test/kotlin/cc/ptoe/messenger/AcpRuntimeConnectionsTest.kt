/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AcpRuntimeConnectionsTest {
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
}
