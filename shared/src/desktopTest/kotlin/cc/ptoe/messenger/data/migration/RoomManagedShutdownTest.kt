/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomManagedShutdownTest {
    @Test fun failedActionsRetryInReverseOrderWithoutRepeatingSuccessfulActions(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        var canStop = false
        val shutdown = RoomManagedShutdown { calls += "host" }
        shutdown.registerStop { calls += "transport" }
        shutdown.registerStop { calls += "cloud"; check(canStop) }
        shutdown.enter()
        val finishing = async { shutdown.stopAndAwait() }
        withTimeout(5_000) { shutdown.state.first { it is RoomShutdownState.RecoveryRequired } }
        assertEquals(listOf("cloud", "transport", "host"), calls)
        assertFalse(finishing.isCompleted)
        assertFails { shutdown.registerStop { } }
        shutdown.retryStop()
        assertTrue(shutdown.state.value is RoomShutdownState.RecoveryRequired)
        assertEquals(listOf("cloud", "transport", "host", "cloud"), calls)
        canStop = true
        shutdown.retryStop()
        finishing.await()
        assertEquals(listOf("cloud", "transport", "host", "cloud", "cloud"), calls)
        assertEquals(RoomShutdownState.Stopped, shutdown.state.value)
        assertFails { shutdown.retryStop() }
        assertFails { shutdown.enter() }
    }

    @Test fun sharedFailureObjectCannotReleaseResources(): Unit = runBlocking {
        val sharedFailure = IllegalStateException("both components still active")
        var safe = false
        val shutdown = RoomManagedShutdown { if (!safe) throw sharedFailure }
        shutdown.registerStop { if (!safe) throw sharedFailure }
        shutdown.enter()
        val finishing = async { shutdown.stopAndAwait() }
        withTimeout(5_000) { shutdown.state.first { it is RoomShutdownState.RecoveryRequired } }
        assertFalse(finishing.isCompleted)
        assertTrue((shutdown.state.value as RoomShutdownState.RecoveryRequired).cause === sharedFailure)
        safe = true
        shutdown.retryStop()
        finishing.await()
        assertEquals(RoomShutdownState.Stopped, shutdown.state.value)
    }

}
