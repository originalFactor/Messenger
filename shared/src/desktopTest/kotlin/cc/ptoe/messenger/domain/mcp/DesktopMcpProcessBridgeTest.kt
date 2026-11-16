/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeFalse
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMcpProcessBridgeTest {
    @Test fun closingKillsAChildBeforeJoiningItsBlockedPipeWrite(): Unit = runBlocking {
        assumeFalse(System.getProperty("os.name").lowercase().contains("windows"))
        val bridge = DesktopMcpProcessBridge()
        assertTrue(bridge.startProcess("exec sleep 5", emptyMap(), {}, {}, {}))
        try {
            val write = async(Dispatchers.IO) { bridge.sendLine("x".repeat(4 * 1024 * 1024)) }
            delay(200)
            assertFalse(write.isCompleted, "the child does not read its full stdin pipe")
            val close = async(Dispatchers.IO) { bridge.close() }
            val stoppedEarly = withTimeoutOrNull(2_000) { close.await(); true } ?: false
            // The child exits naturally after five seconds if a regression blocks close.
            withTimeout(7_000) { close.await(); write.await() }
            assertTrue(stoppedEarly, "close must terminate the child before waiting for the writer")
        } finally { bridge.close() }
    }
}
