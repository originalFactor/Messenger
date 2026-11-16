/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvatarCallTrackerTest {
    @Test fun admissionIsBoundedAndReleasedByRequestCompletion(): Unit = runBlocking {
        val tracker = AvatarCallTracker()
        val jobs = List(64) { SupervisorJob().also { tracker.admit(it) } }
        assertFailsWith<IllegalStateException> { tracker.admit(SupervisorJob()) }
        jobs.first().cancel()
        val replacement = SupervisorJob()
        tracker.admit(replacement)
        tracker.stop()
        withTimeout(5_000) { tracker.awaitStopped() }
        assertTrue(replacement.isCancelled)
    }
    @Test fun stopCancelsCallsAndWaitsForSessionCallbacks(): Unit = runBlocking {
        val tracker = AvatarCallTracker()
        val request = SupervisorJob()
        tracker.admit(request)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val callback = async { tracker.callback { entered.complete(Unit); release.await() } }
        entered.await()
        tracker.stop()
        val shutdown = async { tracker.awaitStopped() }
        delay(50)
        assertFalse(shutdown.isCompleted)
        assertTrue(request.isCancelled)
        assertFailsWith<IllegalStateException> { tracker.admit(SupervisorJob()) }
        assertFailsWith<IllegalStateException> { tracker.callback {} }
        release.complete(Unit)
        withTimeout(5_000) { callback.await(); shutdown.await() }
    }
}
