/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoomCloudDeviceHostManagerTest {
    private fun session(generation: String) = StoredCloudSession(SyncSession("account", generation), "messenger_session=secret", "https://cloud.example", CloudUser(id = "account"))
    private suspend fun eventually(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }

    @Test fun reconnectsOnlyConnectionAndWaitsForOldHostOnSessionSwitch(): Unit = runBlocking {
        val access = MutableStateFlow<RoomDeviceHostAccess?>(RoomDeviceHostAccess(session("one"), RoomDeviceControlGrant("Phone", 1)))
        val runs = AtomicInteger()
        val creations = AtomicInteger()
        val disconnects = AtomicInteger()
        val stopping = CompletableDeferred<Unit>()
        val allowStop = CompletableDeferred<Unit>()
        val secondStopped = CompletableDeferred<Unit>()
        val activeGrant = java.util.concurrent.atomic.AtomicReference<StateFlow<RoomDeviceControlGrant?>>()
        val manager = RoomCloudDeviceHostManager(access, { it.session == access.value?.session?.session }, { _, grant ->
            activeGrant.set(grant)
            val ordinal = creations.incrementAndGet()
            object : ManagedRoomDeviceHost {
                override suspend fun runOnce() {
                    val n = runs.incrementAndGet()
                    if (ordinal == 1 && n == 1) throw IOException("connection lost")
                    awaitCancellation()
                }
                override suspend fun shutdown() {
                    if (ordinal == 1) { stopping.complete(Unit); allowStop.await() }
                    else secondStopped.complete(Unit)
                }
            }
        }, { disconnects.incrementAndGet() }, initialDelayMs = 10, maxDelayMs = 20)
        val running = async { runCatching { manager.run() } }
        try {
            eventually { runs.get() >= 2 }
            assertEquals(1, creations.get())
            assertEquals(1, disconnects.get())
            access.value = RoomDeviceHostAccess(session("one"), RoomDeviceControlGrant("Renamed", 1))
            eventually { activeGrant.get()?.value?.name == "Renamed" }
            assertEquals(1, creations.get())
            access.value = RoomDeviceHostAccess(session("two"), RoomDeviceControlGrant("Phone", 1))
            withTimeout(5_000) { stopping.await() }
            delay(50)
            assertEquals(1, creations.get())
            allowStop.complete(Unit)
            eventually { creations.get() == 2 }
            eventually { runs.get() >= 3 }
            access.value = RoomDeviceHostAccess(session("two"), null)
            eventually { activeGrant.get()?.value == null }
            assertTrue(!secondStopped.isCompleted)
            access.value = null
            withTimeout(5_000) { secondStopped.await() }
        } finally { allowStop.complete(Unit); manager.shutdown(); running.await() }
    }

    @Test fun expiredSessionDoesNotReconnectAndCleanupFailureRemainsVisible(): Unit = runBlocking {
        val session = session("one")
        val access = MutableStateFlow<RoomDeviceHostAccess?>(RoomDeviceHostAccess(session, RoomDeviceControlGrant("Phone", 1)))
        val runs = AtomicInteger()
        val expired = CompletableDeferred<Unit>()
        val failedStop = CompletableDeferred<Unit>()
        val manager = RoomCloudDeviceHostManager(access, { true }, { _, _ -> object : ManagedRoomDeviceHost {
            override suspend fun runOnce() { runs.incrementAndGet(); throw CloudSessionExpired() }
            override suspend fun shutdown() { failedStop.complete(Unit); throw IOException("worker still running") }
        } }, { expired.complete(Unit) }, initialDelayMs = 10, maxDelayMs = 20)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val running = scope.async { runCatching { manager.run() } }
        try {
            withTimeout(5_000) { expired.await(); failedStop.await(); running.await() }
            delay(50)
            assertEquals(1, runs.get())
            val failure = assertFailsWith<IllegalStateException> { manager.shutdown() }
            assertTrue(failure.message.orEmpty().contains("shutdown"))
        } finally { scope.cancel() }
    }
}
