package cc.ptoe.messenger.presentation.ui.acp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import cc.ptoe.messenger.generated.resources.*
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class AcpProfileDialogTest {
    @Test
    fun editingDescriptionPreservesStdioArgumentsAndEnvironment() = runComposeUiTest {
        val original = AcpProfile("profile", "Agent", "Old description", "agent-acp",
            listOf("--workspace", "path with spaces"), mapOf("AGENT_ENV" to "value with spaces"))
        var saved: AcpProfile? = null
        setContent { MaterialTheme { AcpProfileDialog(original, false, {}, { saved = it }) } }
        onNodeWithText(getString(Res.string.acp_description)).performTextReplacement("My project assistant")
        onNodeWithText(getString(Res.string.action_save)).performClick()
        assertEquals(original.copy(description = "My project assistant"), saved)
    }

    @Test
    fun invalidArgumentJsonDoesNotSaveAProfile() = runComposeUiTest {
        var saved: AcpProfile? = null
        val original = AcpProfile("profile", "Agent", command = "agent-acp")
        setContent { MaterialTheme { AcpProfileDialog(original, false, {}, { saved = it }) } }
        onNodeWithText(getString(Res.string.acp_args_json)).performTextReplacement("not JSON")
        onNodeWithText(getString(Res.string.action_save)).performClick()
        assertNull(saved)
    }
}
