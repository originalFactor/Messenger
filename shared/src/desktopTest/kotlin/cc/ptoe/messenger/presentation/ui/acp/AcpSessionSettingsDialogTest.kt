package cc.ptoe.messenger.presentation.ui.acp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AcpSessionSettingsDialogTest {
    private val settings = Json.parseToJsonElement("""{
        "configOptions":[{"id":"access-policy","name":"Permissions","type":"select","currentValue":"sandbox",
            "options":[{"group":"access","name":"Access","options":[
                {"value":"sandbox","name":"Read only"},
                {"value":"project-write","name":"Workspace write","description":"Edit files in this project"}]}]}]
    }""").jsonObject

    @Test
    fun groupedSelectionUsesTheAgentValueWithoutOptimisticallyReplacingItsCurrentValue() = runComposeUiTest {
        var submitted: JsonObject? = null
        setContent { MaterialTheme { AcpSessionSettingsDialog(settings, false, {}, { submitted = it }) } }
        onNodeWithText("Access").assertExists()
        onNodeWithText("Workspace write").performClick()
        assertEquals("access-policy", submitted?.text("configId"))
        assertEquals("project-write", submitted?.text("value"))
        // The current radio selection remains the agent-confirmed sandbox value.
        onAllNodes(isSelected()).assertCountEquals(1)
        assertEquals("sandbox", settings.objects("configOptions").single().text("currentValue"))
    }

    @Test
    fun aPendingSettingRequestDisablesItsSelectionControls() = runComposeUiTest {
        setContent { MaterialTheme { AcpSessionSettingsDialog(settings, true, {}, {}) } }
        onAllNodes(isSelectable()).assertCountEquals(2)
        onAllNodes(isSelectable())[0].assertIsNotEnabled()
        onAllNodes(isSelectable())[1].assertIsNotEnabled()
    }
}
