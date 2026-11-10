package cc.ptoe.messenger.presentation.ui.acp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.input.TextFieldValue
import cc.ptoe.messenger.presentation.ui.chat.ChatInputBar
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AcpComposerTest {
    @Test
    fun sendingAndSteeringHaveSeparateGesturesDuringGeneration() = runComposeUiTest {
        var sent = 0
        var steered = 0
        var stopped = 0
        setContent { MaterialTheme {
            ChatInputBar(TextFieldValue("Next instruction"), {}, { sent++ }, { stopped++ }, true,
                sendWhileGenerating = true, onSendLongClick = { steered++ })
        } }
        onNodeWithContentDescription("Send").performTouchInput { click() }
        runOnIdle { assertEquals(1, sent); assertEquals(0, steered) }
        onNodeWithContentDescription("Send").performTouchInput { longClick() }
        runOnIdle { assertEquals(1, sent); assertEquals(1, steered); assertEquals(0, stopped) }
        onNodeWithContentDescription("Stop").assertExists()
        onNode(hasSetTextAction()).assertIsEnabled()
    }

    @Test
    fun attachmentOnlyDraftCanBeSent() = runComposeUiTest {
        var sent = 0
        setContent { MaterialTheme {
            ChatInputBar(TextFieldValue(""), {}, { sent++ }, {}, false, hasAttachments = true)
        } }
        onNodeWithContentDescription("Send").assertIsEnabled().performTouchInput { click() }
        runOnIdle { assertEquals(1, sent) }
    }
}
