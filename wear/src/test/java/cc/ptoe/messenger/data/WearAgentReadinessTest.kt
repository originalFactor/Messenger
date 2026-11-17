/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearAgentReadinessTest {
    @Test fun existingChatCanUsePhoneOverrideWhileNewChatStillRequiresReadyAgent() {
        val agent = WearAgent("a", "Agent", null, false, isReady = false, hasDefaultModel = true)
        assertFalse(agent.isReady)
        assertTrue(agent.canSendExistingChat())
        assertFalse(agent.copy(hasDefaultModel = false).canSendExistingChat())
        assertFalse(agent.copy(hasDefaultModel = null).canSendExistingChat())
        assertTrue(agent.copy(isReady = true, hasDefaultModel = null).canSendExistingChat())
        assertFalse((null as WearAgent?).canSendExistingChat())
    }
}
