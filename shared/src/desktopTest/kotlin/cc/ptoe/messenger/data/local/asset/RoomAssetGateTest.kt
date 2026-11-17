/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RoomAssetGateTest {
    @Test fun shutdownRejectsQueuedWorkAndWaitsForAdmittedOperation(): Unit = runBlocking {
        val gate = RoomAssetGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = async {
            gate.withExclusiveAssets { entered.complete(Unit); release.await(); "finished" }
        }
        withTimeout(5_000) { entered.await() }
        val shutdown = async(start = CoroutineStart.UNDISPATCHED) { gate.shutdown() }
        assertFalse(shutdown.isCompleted)
        val rejected = async { assertFailsWith<IllegalStateException> { gate.withExclusiveAssets { } } }
        release.complete(Unit)
        withTimeout(5_000) { assertEquals("finished", active.await()); shutdown.await(); rejected.await() }
        assertFailsWith<IllegalStateException> { gate.withExclusiveAssets { } }
        gate.shutdown()
    }
}
