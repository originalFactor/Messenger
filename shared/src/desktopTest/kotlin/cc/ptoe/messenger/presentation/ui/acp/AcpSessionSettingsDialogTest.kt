package cc.ptoe.messenger.presentation.ui.acp

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
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
        onNodeWithText("Permissions: Read only").performClick()
        onNodeWithText("Access").assertExists()
        onNodeWithText("Workspace write").performClick()
        assertEquals("access-policy", submitted?.text("configId"))
        assertEquals("project-write", submitted?.text("value"))
        // Keep showing the confirmed value until the Agent replies.
        onNodeWithText("Permissions: Read only").assertExists()
        assertEquals("sandbox", settings.objects("configOptions").single().text("currentValue"))
    }

    @Test
    fun aPendingSettingRequestDisablesItsSelectionControls() = runComposeUiTest {
        setContent { MaterialTheme { AcpSessionSettingsDialog(settings, true, {}, {}) } }
        onNodeWithText("Permissions: Read only").assertIsNotEnabled()
    }

    @Test
    fun modelAndEffortControlsFollowTheAgentsReplacementSnapshot() = runComposeUiTest {
        val initial = Json.parseToJsonElement("""{"configOptions":[
            {"id":"model","name":"Model","category":"model","type":"select","currentValue":"first",
                "options":[{"value":"first","name":"First"},{"value":"second","name":"Second"}]},
            {"id":"reasoning_effort","name":"Effort","category":"thought_level","type":"select","currentValue":"low",
                "options":[{"value":"low","name":"Low"},{"value":"high","name":"High"}]}
        ]}""").jsonObject
        val snapshot = mutableStateOf(initial)
        var submitted: JsonObject? = null
        setContent { MaterialTheme { AcpSessionControls(snapshot.value, false, { submitted = it }) } }
        onNodeWithText("Model: First").performClick()
        onNodeWithText("Second").performClick()
        assertEquals("model", submitted?.text("configId"))
        assertEquals("second", submitted?.text("value"))
        onNodeWithText("Model: First").assertExists()
        runOnIdle {
            snapshot.value = Json.parseToJsonElement(initial.toString()
                .replace("\"currentValue\":\"first\"", "\"currentValue\":\"second\"")
                .replace("\"value\":\"high\",\"name\":\"High\"", "\"value\":\"medium\",\"name\":\"Medium\"" )).jsonObject
        }
        onNodeWithText("Model: Second").assertExists()
        onNodeWithText("Effort: Low").performClick()
        onNodeWithText("High").assertDoesNotExist()
        onNodeWithText("Medium").performClick()
        assertEquals("reasoning_effort", submitted?.text("configId"))
        assertEquals("medium", submitted?.text("value"))
    }

}
