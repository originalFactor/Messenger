/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.core

import cc.ptoe.messenger.data.migration.DurableRoomOwnership
import cc.ptoe.messenger.data.migration.RoomWritersStoppedProof
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DurableRoomOwnershipTest {
    private val stopped = RoomWritersStoppedProof { }
    private fun supported() = assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().containsAll(setOf("unix", "posix")))
    private fun otherProcess(marker: Path, body: String): Int = ProcessBuilder("python3", "-c", body, marker.toString())
        .start().waitFor()
    private fun tryOtherProcess(marker: Path): Int = otherProcess(marker, """
import fcntl, sys
with open(sys.argv[1], 'r+b') as f:
    try: fcntl.lockf(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError: sys.exit(1)
""".trimIndent())

    @Test fun failedWorkRetainsCrossProcessLockUntilVerifiedRecovery(): Unit = runBlocking {
        supported()
        val dir = Files.createTempDirectory("room-owner")
        val target = dir.resolve("room.db")
        val marker = dir.resolve(".room.db.room-owner.lock")
        val journal = dir.resolve(".room.db.room-owner.state")
        try {
            var priorProof = false
            val owner = DurableRoomOwnership.acquire(target, RoomWritersStoppedProof { priorProof = true }, stopped)
            assertFailsWith<IllegalStateException> { owner.withExclusiveOwnership { error("shutdown cannot be proven") } }
            assertTrue(priorProof)
            assertEquals(1, tryOtherProcess(marker))
            // Reading the separate journal must not release the lock file's POSIX record lock.
            assertTrue(Files.readString(journal).endsWith("ACTIVE\n"))
            assertEquals(1, tryOtherProcess(marker))
            assertFailsWith<IllegalStateException> { DurableRoomOwnership.acquire(target, stopped, stopped) }
            assertFailsWith<IllegalStateException> { owner.recoverAndRelease(RoomWritersStoppedProof { error("worker alive") }) }
            assertEquals(1, tryOtherProcess(marker))
            owner.recoverAndRelease(stopped)
            assertEquals(0, tryOtherProcess(marker))
            val next = DurableRoomOwnership.acquire(target, stopped, stopped)
            assertEquals("ok", next.withExclusiveOwnership { "ok" })
            assertTrue(Files.readString(journal).endsWith("CLEAN\n"))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun crashLeavesDurableMarkerAndRequiresExplicitRecovery(): Unit = runBlocking {
        supported()
        val dir = Files.createTempDirectory("room-owner-crash")
        val target = dir.resolve("room.db")
        val marker = dir.resolve(".room.db.room-owner.lock")
        val journal = dir.resolve(".room.db.room-owner.state")
        try {
            val initial = DurableRoomOwnership.acquire(target, stopped, stopped)
            initial.withExclusiveOwnership { }
            assertEquals(0, otherProcess(journal, """
import fcntl, os, sys
with open(sys.argv[1], 'r+b') as f:
    f.seek(0, 2)
    f.write(b'ACTIVE\n')
    f.flush()
    os.fsync(f.fileno())
    os._exit(0)
""".trimIndent()))
            val next = DurableRoomOwnership.acquire(target, stopped, stopped)
            var entered = false
            assertFailsWith<IllegalStateException> { next.withExclusiveOwnership { entered = true } }
            assertFalse(entered)
            assertEquals(1, tryOtherProcess(marker))
            next.recoverAndRelease(stopped)
            val recovered = DurableRoomOwnership.acquire(target, stopped, stopped)
            recovered.withExclusiveOwnership { entered = true }
            assertTrue(entered)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun openingTheLockInodeAgainCanReleaseTheKernelLock(): Unit = runBlocking {
        supported()
        val dir = Files.createTempDirectory("room-owner-lock-limit")
        val target = dir.resolve("room.db")
        val marker = dir.resolve(".room.db.room-owner.lock")
        val journal = dir.resolve(".room.db.room-owner.state")
        try {
            val owner = DurableRoomOwnership.acquire(target, stopped, stopped)
            assertFailsWith<IllegalStateException> {
                owner.withExclusiveOwnership {
                    assertEquals(1, tryOtherProcess(marker))
                    Files.readAllBytes(marker)
                    // POSIX process locks may silently disappear despite FileLock.isValid.
                    assertEquals(0, tryOtherProcess(marker))
                    error("ownership no longer provable")
                }
            }
            assertTrue(Files.readString(journal).endsWith("ACTIVE\n"))
            owner.recoverAndRelease(stopped)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun missingLockOrJournalNeverCreatesACleanReplacement(): Unit = runBlocking {
        supported()
        val dir = Files.createTempDirectory("room-owner-incomplete")
        val first = Files.createTempDirectory(dir, "first-")
        val second = Files.createTempDirectory(dir, "second-")
        try {
            val firstTarget = first.resolve("room.db")
            DurableRoomOwnership.acquire(firstTarget, stopped, stopped).withExclusiveOwnership { }
            val journal = first.resolve(".room.db.room-owner.state")
            Files.delete(journal)
            assertFailsWith<Exception> { DurableRoomOwnership.acquire(firstTarget, stopped, stopped) }
            assertFalse(Files.exists(journal))

            val secondTarget = second.resolve("room.db")
            DurableRoomOwnership.acquire(secondTarget, stopped, stopped).withExclusiveOwnership { }
            val marker = second.resolve(".room.db.room-owner.lock")
            Files.delete(marker)
            assertFailsWith<IllegalStateException> { DurableRoomOwnership.acquire(secondTarget, stopped, stopped) }
            assertFalse(Files.exists(marker))
        } finally { dir.toFile().deleteRecursively() }
    }
}
