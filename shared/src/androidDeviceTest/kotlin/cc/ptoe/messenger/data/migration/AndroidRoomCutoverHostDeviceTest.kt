/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import android.system.Os
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Isolated test-APK storage only; supplied proofs model the platform fences required by the host. */
class AndroidRoomCutoverHostDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: Path

    @Before fun setUp() {
        dir = Files.createTempDirectory(context.filesDir.toPath(), "android-cutover-host-")
        Os.chmod(dir.toString(), 448)
    }

    @After fun tearDown() {
        if (::dir.isInitialized && Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun eachPreviousWriterProofIsRequiredBeforeRoomOpensAndRecoveryRechecksAll(): Unit = runBlocking {
        for (failed in 0..2) {
            val root = Files.createDirectory(dir.resolve("case-$failed"))
            Os.chmod(root.toString(), 448)
            val target = root.resolve("room.db")
            val active = root.resolve(".room.db.room-owner.active")
            val checks = IntArray(4)
            var fail = failed
            var opened = false
            fun proof(index: Int) = RoomWritersStoppedProof { checks[index]++; check(fail != index) }
            val host = AndroidRoomCutoverHost.create(context, root.resolve("core.db"), target,
                AndroidRoomCutoverProofs(proof(0), proof(1), proof(2), proof(3)), {},
                { opened = true; error("Room must not open") })
            assertTrue(runCatching { host.withFreshStore { Unit } }.isFailure)
            assertFalse(opened)
            assertTrue(Files.isDirectory(active))
            assertTrue(runCatching { host.recoverFailedStart() }.isFailure)
            assertTrue(Files.isDirectory(active))
            fail = 3
            assertTrue(runCatching { host.recoverFailedStart() }.isFailure)
            assertTrue(Files.isDirectory(active))
            fail = -1
            host.recoverFailedStart()
            assertFalse(Files.exists(active))
            assertTrue(checks.all { it > 0 })
        }
    }

    @Test fun unusedHostCanReleaseItsMarkerOnlyAfterIndependentProofs(): Unit = runBlocking {
        val target = dir.resolve("room.db")
        val active = dir.resolve(".room.db.room-owner.active")
        var runtimeStopped = false
        val stopped = RoomWritersStoppedProof { }
        val host = AndroidRoomCutoverHost.create(context, dir.resolve("core.db"), target,
            AndroidRoomCutoverProofs(stopped, RoomWritersStoppedProof { check(runtimeStopped) }, stopped, stopped),
            {}, { error("Room must not open") })
        assertTrue(Files.isDirectory(active))
        assertTrue(runCatching { host.recoverFailedStart() }.isFailure)
        assertTrue(Files.isDirectory(active))
        runtimeStopped = true
        host.recoverFailedStart()
        assertFalse(Files.exists(active))
        assertTrue(runCatching { host.withFreshStore { Unit } }.isFailure)
    }

    @Test fun missingFinalWorkerProofRetainsTheMarkerForRecovery(): Unit = runBlocking {
        val target = dir.resolve("room.db")
        val active = dir.resolve(".room.db.room-owner.active")
        var verified = false
        var database: LocalDatabase? = null
        val stopped = RoomWritersStoppedProof { }
        val host = AndroidRoomCutoverHost.create(context, dir.resolve("core.db"), target,
            AndroidRoomCutoverProofs(stopped, stopped, stopped, RoomWritersStoppedProof { check(verified) }),
            {}, { path -> Room.databaseBuilder<LocalDatabase>(context, path.toString())
                .setDriver(BundledSQLiteDriver()).build() })
        assertTrue(runCatching { host.withFreshStore { database = it } }.isFailure)
        assertTrue(Files.isDirectory(active))
        assertTrue(runCatching { checkNotNull(database).rows().defaultAgents() }.isFailure)
        assertTrue(runCatching { host.recoverFailedStart() }.isFailure)
        verified = true
        host.recoverFailedStart()
        assertFalse(Files.exists(active))
    }

    @Test fun failedWorkerStopKeepsRoomAndOwnerLiveUntilRetryAndFinalProof(): Unit = runBlocking {
        val target = dir.resolve("room.db")
        val active = dir.resolve(".room.db.room-owner.active")
        val checks = IntArray(4)
        var stopAttempts = 0
        var database: LocalDatabase? = null
        fun proof(index: Int) = RoomWritersStoppedProof { checks[index]++ }
        val host = AndroidRoomCutoverHost.create(context, dir.resolve("core.db"), target,
            AndroidRoomCutoverProofs(proof(0), proof(1), proof(2), proof(3)),
            { stopAttempts++; if (stopAttempts == 1) error("worker is still active") },
            { path -> Room.databaseBuilder<LocalDatabase>(context, path.toString())
                .setDriver(BundledSQLiteDriver()).build() })
        val running = async {
            host.withFreshStore { room -> database = room; assertEquals(1, room.rows().defaultAgents().size) }
        }
        withTimeout(15_000) { host.shutdownState.first { it is RoomShutdownState.RecoveryRequired } }
        assertTrue(Files.isDirectory(active))
        assertEquals(1, checkNotNull(database).rows().defaultAgents().size)
        assertTrue(runCatching { host.recoverFailedStart() }.isFailure)
        assertEquals(0, checks[3])
        host.retryStop()
        withTimeout(15_000) { running.await() }
        assertEquals(2, stopAttempts)
        assertTrue(checks.all { it >= 1 })
        assertTrue(runCatching { checkNotNull(database).rows().defaultAgents() }.isFailure)
        assertFalse(Files.exists(active))
    }
}
