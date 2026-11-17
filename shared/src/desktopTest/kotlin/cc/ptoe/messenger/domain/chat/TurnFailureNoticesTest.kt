/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TurnFailureNoticesTest {
    @Test fun failedAcknowledgementRequiresExplicitRetryAndRetainsNotice() = runBlocking {
        val notice = TurnFailureNotice("notice", "turn", "provider failed")
        val pending = MutableStateFlow(listOf(notice))
        var shown = 0
        var acknowledgements = 0
        val acknowledged = CompletableDeferred<Unit>()
        val notices = TurnFailureNotices(pending) {
            acknowledgements++
            if (acknowledgements == 1) error("database unavailable")
            pending.value = emptyList()
            acknowledged.complete(Unit)
        }
        notices.present { shown++ }
        assertEquals("database unavailable", notices.failure.value)
        assertEquals(listOf(notice), pending.value)
        // Recreating or resuming a UI owner does not silently retry.
        notices.present { shown++ }
        assertEquals(1, shown)
        assertEquals(1, acknowledgements)

        notices.retry()
        val nextOwner = launch { notices.present { shown++ } }
        withTimeout(5_000) { acknowledged.await() }
        nextOwner.cancelAndJoin()
        assertEquals(2, shown)
        assertEquals(2, acknowledgements)
        assertEquals(emptyList(), pending.value)
        assertNull(notices.failure.value)
    }

    @Test fun readFailureIsReportedAndCancellationIsNotAStorageFailure() = runBlocking {
        var reads = 0
        val failing = TurnFailureNotices(flow<List<TurnFailureNotice>> { reads++; error("read failed") }) { }
        failing.present { }
        assertEquals("read failed", failing.failure.value)
        failing.present { }
        assertEquals(1, reads)
        failing.retry()
        failing.present { }
        assertEquals(2, reads)

        val waiting = TurnFailureNotices(MutableStateFlow(emptyList())) { }
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { waiting.present { } }
        owner.cancelAndJoin()
        assertNull(waiting.failure.value)
    }
}
