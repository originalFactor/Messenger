/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AcpExclusiveOwnerTest {
    private val proven = AcpPriorOwnerBarrier { }
    private fun supported() = assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().containsAll(setOf("unix", "posix")))
    private fun tryOtherProcess(marker: Path): Int = ProcessBuilder("python3", "-c", """
import fcntl, sys
with open(sys.argv[1], 'r+') as lock:
    try:
        fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        sys.exit(1)
""".trimIndent(), marker.toString()).start().waitFor()

    @Test fun ownerIsExclusiveAcrossProcessesAndShutdownFailureKeepsLock(): Unit = runBlocking {
        supported()
        val dir = Files.createTempDirectory("acp-owner")
        val db = Files.createFile(dir.resolve("room.db"))
        val marker = dir.resolve(".room.db.acp-owner.lock")
        try {
            var checked = false
            val owner = AcpExclusiveOwner.acquire(db, AcpPriorOwnerBarrier { checked = true })
            assertTrue(checked)
            assertEquals("ok", owner.withOwner { "ok" })
            assertFailsWith<IllegalStateException> { AcpExclusiveOwner.acquire(db, proven) }
            assertEquals(1, tryOtherProcess(marker))
            assertFailsWith<IllegalStateException> { owner.closeAndAwait { error("Cannot prove shutdown") } }
            assertEquals(1, tryOtherProcess(marker))
            assertFailsWith<IllegalStateException> { owner.withOwner { error("Must not admit work while stopping") } }
            owner.closeAndAwait { }
            assertEquals(0, tryOtherProcess(marker))
            assertFailsWith<IllegalStateException> { owner.withOwner { } }
            val next = AcpExclusiveOwner.acquire(db, proven)
            next.closeAndAwait { }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun failedRecoveryProofAndDatabaseAliasesFailClosed(): Unit = runBlocking {
        supported()
        val dir = Files.createTempDirectory("acp-owner-proof")
        val db = Files.createFile(dir.resolve("room.db"))
        try {
            assertFailsWith<IllegalStateException> { AcpExclusiveOwner.acquire(db, AcpPriorOwnerBarrier { error("Old workers may be alive") }) }
            val owner = AcpExclusiveOwner.acquire(db, proven)
            owner.closeAndAwait { }
            val alias = Files.createLink(dir.resolve("alias.db"), db)
            assertFailsWith<IllegalArgumentException> { AcpExclusiveOwner.acquire(db, proven) }
            Files.delete(alias)
            val symlink = Files.createSymbolicLink(dir.resolve("symlink.db"), db)
            assertFailsWith<IllegalArgumentException> { AcpExclusiveOwner.acquire(symlink, proven) }
        } finally { dir.toFile().deleteRecursively() }
    }
}
