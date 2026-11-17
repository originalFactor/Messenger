/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WearSocketOwnerTest {
    @Test fun staleCloseFailureAndMessageCannotAffectReplacement() {
        val owner = WearSocketOwner<Any>()
        val oldSocket = Any()
        val old = owner.begin()
        assertTrue(owner.attach(old, oldSocket))
        assertTrue(owner.opened(old, oldSocket) {})
        assertTrue(owner.closed(old, oldSocket) {})
        val newSocket = Any()
        val current = owner.begin()
        assertTrue(owner.attach(current, newSocket))
        assertTrue(owner.opened(current, newSocket) {})
        var pendingFailureCount = 0
        var staleMessageCount = 0
        assertFalse(owner.closed(old, oldSocket) { pendingFailureCount++ })
        assertFalse(owner.closed(old, oldSocket) { pendingFailureCount++ })
        owner.ifOpen(old, oldSocket) { staleMessageCount++ }
        assertEquals(0, pendingFailureCount)
        assertEquals(0, staleMessageCount)
        assertSame(newSocket, owner.withOpen { it })
        assertTrue(owner.closed(current, newSocket) { pendingFailureCount++ })
        assertEquals(1, pendingFailureCount)
    }

    @Test fun timedOutOrStoppedAttemptCannotOpenLate() {
        val owner = WearSocketOwner<Any>()
        val oldSocket = Any()
        val old = owner.begin()
        assertTrue(owner.attach(old, oldSocket))
        assertNull(owner.withOpen { it })
        assertTrue(owner.cancel(old) { assertSame(oldSocket, it) })
        val nextSocket = Any()
        val next = owner.begin()
        assertTrue(owner.attach(next, nextSocket))
        assertFalse(owner.opened(old, oldSocket) { error("stale open") })
        assertTrue(owner.opened(next, nextSocket) {})
        assertSame(nextSocket, owner.withOpen { it })
        owner.stop { assertSame(nextSocket, it) }
        assertFalse(owner.opened(next, nextSocket) { error("stopped open") })

        var stopped = false
        val unbound = owner.begin { stopped = true }
        owner.stop { assertNull(it) }
        assertTrue(stopped)
        assertFalse(owner.ifActive(unbound) { error("discovery resumed after stop") })
        assertFalse(owner.attach(unbound, Any()))
    }
}
