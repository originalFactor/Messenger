/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class WearSyncValidationTest {
    private val agents get() = JSONArray("""[{"id":"a","name":"Agent","isDefault":true,"isReady":true}]""")
    private val conversations get() = JSONArray("""[{"id":"c","agentId":"a","title":"Chat","createdAt":1,"updatedAt":1}]""")

    @Test fun completeSnapshotRequiresEveryConversationBatch() {
        assertThrows(IllegalStateException::class.java) {
            validateWearSyncSnapshot(agents, conversations, JSONObject(), true)
        }
        validateWearSyncSnapshot(agents, conversations, JSONObject("""{"c":[]}"""), true)
        validateWearSyncSnapshot(agents, conversations, JSONObject("""{"c":[{"id":"m","conversationId":"c","role":"assistant","content":"answer","timestamp":1,"isPending":false,"isError":false}]}"""), true)
        // Older phones omitted the completeness flag; missing batches remain unknown.
        validateWearSyncSnapshot(agents, conversations, JSONObject(), false)
    }

    @Test fun malformedBatchesAndRowsAreRejectedForNewAndOldPhones() {
        for (complete in listOf(false, true)) {
            for (messages in listOf(
                """{"c":{}}""",
                """{"c":[{"id":"m","role":"assistant","conversationId":"other","content":"x","timestamp":1,"isPending":false,"isError":false}]}""",
                """{"c":[{"id":"","role":"user","content":"x","timestamp":1,"isPending":false,"isError":false}]}""",
                """{"c":[{"id":"m","role":"user","content":"x","timestamp":1,"isPending":false,"isError":false},{"id":"m","role":"assistant","content":"y","timestamp":2,"isPending":false,"isError":false}]}""",
                """{"c":[{"id":"m","role":"unknown","content":"x","timestamp":1,"isPending":false,"isError":false}]}""",
                """{"c":[{"id":"m","role":"assistant"}]}""",
                """{"c":[{"id":"m","role":"assistant","content":"answer","timestamp":1,"isPending":"false","isError":false}]}"""
            )) {
                assertThrows(IllegalStateException::class.java) {
                    validateWearSyncSnapshot(agents, conversations, JSONObject(messages), complete)
                }
            }
        }
    }

    @Test fun duplicateOrMalformedAgentAndConversationIdentitiesAreRejected() {
        assertThrows(IllegalStateException::class.java) {
            validateWearSyncSnapshot(JSONArray("""[{"id":"a","name":"A","isDefault":true,"isReady":true},{"id":"a","name":"A","isDefault":true,"isReady":true}]"""), conversations, JSONObject(), false)
        }
        assertThrows(IllegalStateException::class.java) {
            validateWearSyncSnapshot(agents, JSONArray("""[{"id":"c","agentId":"a","title":"Chat","createdAt":1,"updatedAt":1},{"id":"c","agentId":"a","title":"Chat","createdAt":1,"updatedAt":1}]"""), JSONObject(), false)
        }
        assertThrows(IllegalStateException::class.java) {
            validateWearSyncSnapshot(agents, JSONArray("""[{"id":"c","agentId":"a","createdAt":1,"updatedAt":1}]"""), JSONObject("""{"c":[]}"""), true)
        }
    }
}
