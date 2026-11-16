/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

class McpProcessSessionTest {
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
