/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.core

import cc.ptoe.messenger.data.migration.RoomDirectoryOwnership
import cc.ptoe.messenger.data.migration.RoomWritersStoppedProof
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomDirectoryOwnershipTest {
    private val stopped = RoomWritersStoppedProof { }
    private fun supported() = assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().containsAll(setOf("unix", "posix")))
    private fun process(path: Path, code: String): Int = ProcessBuilder("python3", "-c", code, path.toString()).start().waitFor()
    private fun tryCreate(path: Path) = process(path, """
import os, sys
try: os.mkdir(sys.argv[1], 0o700)
except FileExistsError: sys.exit(1)
""".trimIndent())

    @Test fun callbackFailureRetainsDirectoryDespiteExtraDescriptors(): Unit = runBlocking {
        supported()
        val dir = Files.createTempDirectory("room-directory-owner")
        val target = dir.resolve("room.db")
        val marker = dir.resolve(".room.db.room-owner.active")
        try {
            var proven = false
            val owner = RoomDirectoryOwnership.acquire(target, RoomWritersStoppedProof { proven = true }, stopped)
            assertFailsWith<IllegalStateException> {
                owner.withExclusiveOwnership {
                    assertEquals(1, tryCreate(marker))
                    FileChannel.open(marker, StandardOpenOption.READ).use { it.force(true) }
                    assertEquals(1, tryCreate(marker))
                    error("shutdown failed")
                }
            }
            assertTrue(proven)
            assertTrue(Files.isDirectory(marker))
            assertFailsWith<Exception> { RoomDirectoryOwnership.acquire(target, stopped, stopped) }
            assertFailsWith<IllegalStateException> { owner.recoverAndRelease(RoomWritersStoppedProof { error("writer alive") }) }
            assertTrue(Files.isDirectory(marker))
            owner.recoverAndRelease(stopped)
            assertFalse(Files.exists(marker))
            val next = RoomDirectoryOwnership.acquire(target, stopped, stopped)
            assertEquals("ok", next.withExclusiveOwnership { "ok" })
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun crashedOwnerNeedsExclusiveProvenRecovery(): Unit = runBlocking {
        supported()
        val dir = Files.createTempDirectory("room-directory-crash")
        val target = dir.resolve("room.db")
        val marker = dir.resolve(".room.db.room-owner.active")
        val guard = dir.resolve(".room.db.room-owner.recovery")
        try {
            Files.writeString(target, "preserve")
            assertEquals(0, process(marker, """
import os, sys
os.mkdir(sys.argv[1], 0o700)
fd = os.open(os.path.dirname(sys.argv[1]), os.O_RDONLY)
os.fsync(fd)
os._exit(0)
""".trimIndent()))
            assertFailsWith<Exception> { RoomDirectoryOwnership.acquire(target, stopped, stopped) }
            val recovery = RoomDirectoryOwnership.acquireRecovery(target)
            assertTrue(Files.isDirectory(guard))
            assertFailsWith<IllegalStateException> { recovery.recoverAndRelease(RoomWritersStoppedProof { error("owner may resume") }) }
            assertTrue(Files.exists(marker))
            assertFailsWith<Exception> { RoomDirectoryOwnership.acquire(target, stopped, stopped) }
            recovery.recoverAndRelease(stopped)
            assertFalse(Files.exists(marker))
            assertFalse(Files.exists(guard))
            assertEquals("preserve", Files.readString(target))
            RoomDirectoryOwnership.acquire(target, stopped, stopped).withExclusiveOwnership { }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun symlinkedSentinelAndIncompleteRecoveryFailClosed(): Unit = runBlocking {
        supported()
        val dir = Files.createTempDirectory("room-directory-link")
        val target = dir.resolve("room.db")
        val marker = dir.resolve(".room.db.room-owner.active")
        val guard = dir.resolve(".room.db.room-owner.recovery")
        try {
            Files.createSymbolicLink(marker, dir)
            assertFailsWith<Exception> { RoomDirectoryOwnership.acquire(target, stopped, stopped) }
            Files.delete(marker)
            Files.createDirectory(guard)
            assertFailsWith<IllegalStateException> { RoomDirectoryOwnership.acquire(target, stopped, stopped) }
            assertTrue(Files.isDirectory(guard))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun replacingTheActiveDirectoryPreventsWork(): Unit = runBlocking {
        supported()
        val dir = Files.createTempDirectory("room-directory-replaced")
        val target = dir.resolve("room.db")
        val marker = dir.resolve(".room.db.room-owner.active")
        try {
            val owner = RoomDirectoryOwnership.acquire(target, stopped, stopped)
            // Keep the original inode alive so the replacement cannot reuse its identity.
            Files.move(marker, dir.resolve("old-owner"))
            Files.createDirectory(marker)
            var entered = false
            assertFailsWith<Exception> { owner.withExclusiveOwnership { entered = true } }
            assertFalse(entered)
            assertTrue(Files.isDirectory(marker))
            assertTrue(Files.isDirectory(dir.resolve("old-owner")))
        } finally { dir.toFile().deleteRecursively() }
    }
}
