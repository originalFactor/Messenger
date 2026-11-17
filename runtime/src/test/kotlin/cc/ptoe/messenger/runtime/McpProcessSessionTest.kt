/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

class McpProcessSessionTest {
    @Test fun fatalPostExecFailureStopsChildBeforeRethrowing() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("windows"))
        val process = ProcessBuilder("/bin/sh", "-c", "exec sleep 5").start()
        val failure = AssertionError("reader thread could not start")
        try {
            try { TermuxRuntime.cleanupFailedMcpLaunch(process, failure); fail("Expected original failure") }
            catch (actual: AssertionError) { assertSame(failure, actual) }
            assertFalse(process.isAlive)
        } finally { process.destroyForcibly() }
    }

    @Test fun concurrentSendsPreserveCompleteLines() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("windows"))
        val process = ProcessBuilder("/bin/cat").start()
        val session = TermuxRuntime.McpProcessSession(process, process.outputStream.bufferedWriter())
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val reader = Thread { process.inputStream.bufferedReader().useLines { lines.addAll(it.toList()) } }
        val first = "A".repeat(256)
        val second = "B".repeat(256)
        val senderA = Thread { repeat(100) { check(session.sendLine(first)) } }
        val senderB = Thread { repeat(100) { check(session.sendLine(second)) } }
        try {
            reader.start(); senderA.start(); senderB.start()
            senderA.join(5_000); senderB.join(5_000)
            assertFalse(senderA.isAlive || senderB.isAlive)
            process.outputStream.close()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            reader.join(5_000)
            assertFalse(reader.isAlive)
            assertEquals(100, lines.count { it == first })
            assertEquals(100, lines.count { it == second })
            assertEquals(200, lines.size)
        } finally { process.destroyForcibly(); reader.join(5_000) }
    }

    @Test fun stopKillsChildBeforeClosingItsBlockedWriter() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("windows"))
        val process = ProcessBuilder("/bin/sh", "-c", "exec sleep 5").start()
        val session = TermuxRuntime.McpProcessSession(process, process.outputStream.bufferedWriter())
        val entered = CountDownLatch(1)
        val writer = Thread { entered.countDown(); session.sendLine("x".repeat(4 * 1024 * 1024)) }
        val closed = CountDownLatch(1)
        val closeFailed = AtomicBoolean(false)
        val closer = Thread {
            try { session.close() } catch (_: Exception) { closeFailed.set(true) } finally { closed.countDown() }
        }
        try {
            writer.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            Thread.sleep(200)
            assertTrue("the child does not read its full stdin pipe", writer.isAlive)
            closer.start()
            val stoppedEarly = closed.await(2, TimeUnit.SECONDS)
            if (!stoppedEarly) process.destroyForcibly()
            assertTrue(closed.await(7, TimeUnit.SECONDS))
            writer.join(2_000)
            assertTrue("close must terminate the child before waiting for the writer", stoppedEarly)
            assertFalse(closeFailed.get())
            assertFalse(writer.isAlive)
            assertFalse(process.isAlive)
        } finally {
            process.destroyForcibly()
            writer.join(7_000)
            if (closer.isAlive) closer.join(7_000)
        }
    }
}
