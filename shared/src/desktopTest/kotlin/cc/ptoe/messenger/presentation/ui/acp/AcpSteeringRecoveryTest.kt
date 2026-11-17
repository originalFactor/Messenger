/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.ui.acp

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.input.TextFieldValue
import cc.ptoe.messenger.presentation.ui.chat.ChatInputBar
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AcpSteeringRecoveryTest {
    private fun pending(canAcknowledge: Boolean) = buildJsonObject {
        put("requestId", "request"); put("turnId", "turn"); put("canAcknowledge", canAcknowledge)
    }

    @Test fun malformedStatusCannotBeInterpretedAsClear() {
        assertTrue(isValidAcpSteeringStatus(buildJsonObject { put("type", "SteeringStatus") }))
        assertTrue(isValidAcpSteeringStatus(buildJsonObject { put("type", "SteeringStatus"); put("pending", pending(false)) }))
        assertFalse(isValidAcpSteeringStatus(buildJsonObject { put("type", "SteeringStatus"); put("pending", "bad") }))
        assertFalse(isValidAcpSteeringStatus(buildJsonObject { put("type", "SteeringStatus"); put("pending", buildJsonObject {
            put("requestId", "request"); put("turnId", "turn"); put("canAcknowledge", "true")
        }) }))
    }

    @Test fun onlyExplicitConfirmationOfStoppedWorkerAcknowledgesExactMarker() = runComposeUiTest {
        val current = mutableStateOf(pending(false))
        var acknowledged: Pair<String, String>? = null
        setContent { MaterialTheme { AcpSteeringRecovery(current.value, false, false, false, {}, { request, turn -> acknowledged = request to turn }) } }
        onNodeWithText("This device cannot verify that the previous worker stopped. The result remains blocked.").assertExists()
        onNodeWithText("Acknowledge unknown result").assertDoesNotExist()
        runOnIdle { current.value = pending(true) }
        onNodeWithText("Acknowledge unknown result").performClick()
        assertNull(acknowledged)
        onNodeWithText("Cancel").performClick()
        assertNull(acknowledged)
        onNodeWithText("Acknowledge unknown result").performClick()
        onAllNodesWithText("Acknowledge unknown result").onLast().performClick()
        assertEquals("request" to "turn", acknowledged)
    }

    @Test fun staleClearStatusCannotSendDraftWhileSteeringIsStillInFlight() = runComposeUiTest {
        val steering = mutableStateOf(true)
        val checking = mutableStateOf(false) // An older clear status arrives before admission.
        val current = mutableStateOf<JsonObject?>(null)
        var sent = 0
        setContent { MaterialTheme { ChatInputBar(TextFieldValue("keep this draft"), {}, { sent++ }, {}, false,
            sendEnabled = !acpSteeringBlocksInput(steering.value, true, checking.value, false, current.value)) } }
        onNodeWithContentDescription("Send").assertIsNotEnabled()
        onNodeWithText("keep this draft").assertExists()
        runOnIdle { checking.value = true; steering.value = false }
        onNodeWithContentDescription("Send").assertIsNotEnabled()
        runOnIdle { current.value = pending(false); checking.value = false }
        onNodeWithContentDescription("Send").assertIsNotEnabled()
        assertEquals(0, sent)
        runOnIdle { current.value = null }
        onNodeWithContentDescription("Send").performClick()
        assertEquals(1, sent)
    }
}
