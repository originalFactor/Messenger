/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearAgentModelReadinessTest {
    @Test fun existingChatsUseRawModelFlagWhileNewChatsUseAgentReadiness() {
        val wire = """[{"id":"override","name":"Override","isReady":false,"hasDefaultModel":true},{"id":"missing","name":"Missing","isReady":false,"hasDefaultModel":false},{"id":"old","name":"Old phone","isReady":false}]"""
        val agents = WearChatJsonCodec.decodeAgentsFromSync(wire, emptyMap()).associateBy { it.id }
        val override = agents.getValue("override")
        val missing = agents.getValue("missing")
        val old = agents.getValue("old")

        // A conversation override can be usable even when this Agent cannot start a new chat.
        assertFalse(override.isReady)
        assertTrue(override.canSendExistingChat())
        // A known missing raw model must be rejected before any optimistic message is inserted.
        assertFalse(missing.canSendExistingChat())
        // Older phones and saved snapshots have no raw-model field; the phone validates the send.
        assertNull(old.hasDefaultModel)
        assertTrue(old.canSendExistingChat())

        val restored = WearChatJsonCodec.decodeAgents(WearChatJsonCodec.encodeAgents(agents.values.toList())).associateBy { it.id }
        assertEquals(true, restored.getValue("override").hasDefaultModel)
        assertEquals(false, restored.getValue("missing").hasDefaultModel)
        assertNull(restored.getValue("old").hasDefaultModel)
        assertTrue(restored.getValue("override").canSendExistingChat())
        assertFalse(restored.getValue("missing").canSendExistingChat())
    }
}
