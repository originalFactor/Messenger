/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearChatFrameRegistryTest {
    @Test fun immediateRepliesAndLargeBurstsRemainOrderedAndKeepTerminal() = runBlocking {
        val registry = WearChatFrameRegistry()
        val socket = Any()
        val frames = registry.register("first")
        assertTrue(registry.bind("first", socket))
        registry.deliver(socket, WearChatFrame.Accepted("first", "user"))
        repeat(1_024) { registry.deliver(socket, WearChatFrame.Delta("first", "$it")) }
        registry.deliver(socket, WearChatFrame.Accepted("first", "user", "assistant"))
        registry.deliver(socket, WearChatFrame.Done("first", "complete", "user", "assistant"))

        assertEquals(WearChatFrame.Accepted("first", "user"), frames.receive())
        repeat(1_024) { assertEquals(WearChatFrame.Delta("first", "$it"), frames.receive()) }
        assertEquals(WearChatFrame.Accepted("first", "user", "assistant"), frames.receive())
        assertEquals(WearChatFrame.Done("first", "complete", "user", "assistant"), frames.receive())
        registry.unregister("first")
        assertTrue(frames.receiveCatching().isClosed)
    }

    @Test fun socketCloseOnlyFailsItsOwnRequestsAndPreservesQueuedFrames() = runBlocking {
        val registry = WearChatFrameRegistry()
        val oldSocket = Any()
        val newSocket = Any()
        val first = registry.register("first")
        val second = registry.register("second")
        assertTrue(registry.bind("first", oldSocket))
        assertTrue(registry.bind("second", newSocket))
        registry.deliver(oldSocket, WearChatFrame.Accepted("first", "user"))
        registry.deliver(oldSocket, WearChatFrame.Error("second", "wrong socket"))
        registry.disconnected(oldSocket, "disconnected")
        assertEquals(WearChatFrame.Accepted("first", "user"), first.receive())
        assertTrue(first.receiveCatching().exceptionOrNull() is IOException)
        assertFalse(second.tryReceive().isSuccess)
        registry.deliver(newSocket, WearChatFrame.Error("second", "rejected", "model_required"))
        assertEquals(WearChatFrame.Error("second", "rejected", "model_required"), second.receive())
        registry.unregister("second")
        assertTrue(second.receiveCatching().isClosed)
    }

    @Test fun overflowingTheBoundedQueueReportsFailureInsteadOfDroppingFrames() = runBlocking {
        val registry = WearChatFrameRegistry()
        val socket = Any()
        val frames = registry.register("first")
        assertTrue(registry.bind("first", socket))
        repeat(4_097) { registry.deliver(socket, WearChatFrame.Delta("first", "$it")) }
        repeat(4_096) { assertEquals(WearChatFrame.Delta("first", "$it"), frames.receive()) }
        assertTrue(frames.receiveCatching().exceptionOrNull() is IOException)
    }
}
