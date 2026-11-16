/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.mcp

import cc.ptoe.messenger.domain.tool.ShellResult
import cc.ptoe.messenger.domain.tool.ShellRuntimeBridge
import cc.ptoe.messenger.domain.tool.ShellRuntimeRegistry
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMcpProcessBridgeDeviceTest {
    private class FakeRuntime : ShellRuntimeBridge {
        var closed: (Int) -> Unit = {}
        var lost: () -> Unit = {}
        var startResult = true
        var loseDuringStart = false
        var start: suspend () -> Unit = {}
        var stopCalls = 0
        var stop: suspend () -> Unit = {}

        override suspend fun startMcpProcess(sessionId: Int, command: String, envJson: String?,
            onOutput: (String) -> Unit, onError: (String) -> Unit, onClosed: (Int) -> Unit,
            onConnectionLost: () -> Unit): Boolean {
            closed = onClosed
            lost = onConnectionLost
            start()
            if (loseDuringStart) lost()
            return startResult
        }
        override suspend fun stopMcpProcess(sessionId: Int) { stopCalls++; stop() }
        override suspend fun sendMcpInput(sessionId: Int, line: String) = true
        override fun isInstalled() = true
        override suspend fun temporaryChatWorkspace(conversationId: String): String = error("unused")
        override suspend fun execute(command: String, timeoutMs: Long, workingDir: String?, onOutput: ((String) -> Unit)?): ShellResult? = error("unused")
        override suspend fun workspaceGlob(root: String, pattern: String, maxResults: Int): ToolExecutionResult = error("unused")
        override suspend fun workspaceGrep(root: String, pattern: String, path: String, fileGlob: String?, caseSensitive: Boolean,
            fixedString: Boolean, maxResults: Int): ToolExecutionResult = error("unused")
        override suspend fun workspaceRead(root: String, path: String, startLine: Int, maxLines: Int): ToolExecutionResult = error("unused")
        override suspend fun workspaceEdit(root: String, path: String, oldText: String, newText: String, replaceAll: Boolean): ToolExecutionResult = error("unused")
        override suspend fun workspaceCreate(root: String, path: String, content: String, overwrite: Boolean): ToolExecutionResult = error("unused")
    }

    @Test fun lostRuntimeDuringStartIsAnUnverifiedStopEvenWhenStartReturnsFalse(): Unit = runBlocking {
        val fake = FakeRuntime().apply { startResult = false; loseDuringStart = true }
        val previous = ShellRuntimeRegistry.bridge
        ShellRuntimeRegistry.bridge = fake
        try {
            val bridge = AndroidMcpProcessBridge(50)
            assertFalse(bridge.startProcess("test", emptyMap(), {}, {}, {}))
            val failure = runCatching { bridge.close() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException && failure.message.orEmpty().contains("unverified"))
            assertEquals(0, fake.stopCalls)
        } finally { ShellRuntimeRegistry.bridge = previous }
    }

    @Test fun lostRuntimeAfterStartDoesNotPretendTheProcessExited(): Unit = runBlocking {
        val fake = FakeRuntime()
        val previous = ShellRuntimeRegistry.bridge
        ShellRuntimeRegistry.bridge = fake
        try {
            val bridge = AndroidMcpProcessBridge(50)
            assertTrue(bridge.startProcess("test", emptyMap(), {}, {}, {}))
            fake.lost()
            val failure = runCatching { bridge.close() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException && failure.message.orEmpty().contains("unverified"))
        } finally { ShellRuntimeRegistry.bridge = previous }
    }

    @Test fun sessionStopUsesTheRuntimeThatStartedIt(): Unit = runBlocking {
        val original = FakeRuntime()
        val replacement = FakeRuntime()
        original.stop = { original.closed(0) }
        val previous = ShellRuntimeRegistry.bridge
        ShellRuntimeRegistry.bridge = original
        try {
            val bridge = AndroidMcpProcessBridge(500)
            assertTrue(bridge.startProcess("test", emptyMap(), {}, {}, {}))
            ShellRuntimeRegistry.bridge = replacement
            withTimeout(1_000) { bridge.close() }
            assertEquals(1, original.stopCalls)
            assertEquals(0, replacement.stopCalls)
        } finally { ShellRuntimeRegistry.bridge = previous }
    }

    @Test fun missingCallbackAndBlockedStopBothRemainUnverifiedUntilActualCompletion(): Unit = runBlocking {
        val fake = FakeRuntime()
        val stopEntered = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        fake.stop = { stopEntered.complete(Unit); releaseStop.await() }
        val previous = ShellRuntimeRegistry.bridge
        ShellRuntimeRegistry.bridge = fake
        try {
            val bridge = AndroidMcpProcessBridge(50)
            assertTrue(bridge.startProcess("test", emptyMap(), {}, {}, {}))
            val first = runCatching { bridge.close() }.exceptionOrNull()
            assertTrue(first is IllegalStateException && first.message.orEmpty().contains("unverified"))
            withTimeout(1_000) { stopEntered.await() }
            fake.closed(0)
            val second = runCatching { bridge.close() }.exceptionOrNull()
            assertTrue(second is IllegalStateException && second.message.orEmpty().contains("unverified"))
            assertEquals(1, fake.stopCalls)
            releaseStop.complete(Unit)
            withTimeout(1_000) { bridge.close() }
            assertEquals(1, fake.stopCalls)
        } finally { releaseStop.complete(Unit); ShellRuntimeRegistry.bridge = previous }
    }

    @Test fun cancelledCallerCannotHideAStartTransactionThatFinishesLater(): Unit = runBlocking {
        val fake = FakeRuntime()
        val entered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        fake.start = { entered.complete(Unit); releaseStart.await() }
        fake.stop = { fake.closed(0) }
        val previous = ShellRuntimeRegistry.bridge
        ShellRuntimeRegistry.bridge = fake
        try {
            val bridge = AndroidMcpProcessBridge(50)
            val starting = async { bridge.startProcess("test", emptyMap(), {}, {}, {}) }
            withTimeout(1_000) { entered.await() }
            starting.cancelAndJoin()
            val failure = runCatching { bridge.close() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException && failure.message.orEmpty().contains("unverified"))
            assertEquals(0, fake.stopCalls)
            releaseStart.complete(Unit)
            withTimeout(1_000) { bridge.close() }
            assertEquals(1, fake.stopCalls)
        } finally { releaseStart.complete(Unit); ShellRuntimeRegistry.bridge = previous }
    }
}
