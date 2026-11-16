/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryWorkspaceTrackerTest {
    @Test fun aReturnedCallIsStoppedButItsTokenCannotRunAgain() = runBlocking {
        val tracker = InMemoryWorkspaceTracker()
        assertEquals(TrackedWorkspaceStatus.UNKNOWN, tracker.status("missing"))
        assertEquals("result", tracker.run("token") { "result" })
        assertEquals(TrackedWorkspaceStatus.STOPPED, tracker.status("token"))
        var repeated = false
        assertFailsWith<IllegalStateException> { tracker.run("token") { repeated = true } }
        assertFalse(repeated)
        assertEquals(TrackedWorkspaceStatus.UNKNOWN, InMemoryWorkspaceTracker().status("token"))
    }

    @Test fun cancellationWaitsForTheSynchronousCallToReturn() = runBlocking {
        val tracker = InMemoryWorkspaceTracker()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val call = launch(Dispatchers.Default) {
            tracker.run("token") { started.complete(Unit); release.await(); "result" }
        }
        withTimeout(5000) { started.await() }
        assertEquals(TrackedWorkspaceStatus.RUNNING, tracker.status("token"))
        call.cancel()
        yield()
        assertFalse(call.isCompleted)
        assertEquals(TrackedWorkspaceStatus.RUNNING, tracker.status("token"))
        release.complete(Unit)
        withTimeout(5000) { call.join() }
        assertEquals(TrackedWorkspaceStatus.STOPPED, tracker.status("token"))
    }

    @Test fun cancelAndAwaitRequiresTheRealCallToReturn() = runBlocking {
        val tracker = InMemoryWorkspaceTracker()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val call = launch(Dispatchers.Default) {
            tracker.run("token") { started.complete(Unit); release.await() }
        }
        withTimeout(5000) { started.await() }
        val stop = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { tracker.cancelAndAwaitStopped("token") }
        assertFalse(stop.isCompleted)
        assertFalse(call.isCompleted)
        assertEquals(TrackedWorkspaceStatus.RUNNING, tracker.status("token"))
        release.complete(Unit)
        assertEquals(TrackedWorkspaceStatus.STOPPED, withTimeout(5000) { stop.await() })
        withTimeout(5000) { call.join() }
        assertEquals(TrackedWorkspaceStatus.UNKNOWN, tracker.cancelAndAwaitStopped("missing"))
    }

    @Test fun cancelAndAwaitReturnsUnknownOnDisconnect() = runBlocking {
        val tracker = InMemoryWorkspaceTracker()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val call = launch(Dispatchers.Default) {
            runCatching { tracker.run("token") { started.complete(Unit); release.await() } }
        }
        withTimeout(5000) { started.await() }
        val stop = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { tracker.cancelAndAwaitStopped("token") }
        tracker.disconnected()
        assertEquals(TrackedWorkspaceStatus.UNKNOWN, withTimeout(5000) { stop.await() })
        release.complete(Unit)
        withTimeout(5000) { call.join() }
        assertEquals(TrackedWorkspaceStatus.UNKNOWN, tracker.status("token"))
    }

    @Test fun disconnectAndExceptionsRemainUnknownEvenAfterALateReturn() = runBlocking {
        val tracker = InMemoryWorkspaceTracker()
        tracker.run("completed") { Unit }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async(Dispatchers.Default) {
            runCatching { tracker.run("running") { started.complete(Unit); release.await(); "late" } }
        }
        withTimeout(5000) { started.await() }
        tracker.disconnected()
        assertEquals(TrackedWorkspaceStatus.UNKNOWN, tracker.status("completed"))
        assertEquals(TrackedWorkspaceStatus.UNKNOWN, tracker.status("running"))
        release.complete(Unit)
        assertTrue(withTimeout(5000) { pending.await() }.isFailure)
        assertEquals(TrackedWorkspaceStatus.UNKNOWN, tracker.status("running"))
        assertFailsWith<IllegalStateException> { tracker.run("running") { Unit } }
        assertFailsWith<IllegalArgumentException> { tracker.run("error") { throw IllegalArgumentException("binder failure") } }
        assertEquals(TrackedWorkspaceStatus.UNKNOWN, tracker.status("error"))
    }
}
