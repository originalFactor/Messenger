package cc.ptoe.messenger.presentation.ui.acp

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AcpSessionSettingsScreenTest {
    private val settings = Json.parseToJsonElement("""{
        "configOptions":[{"id":"access-policy","name":"Permissions","type":"select","currentValue":"sandbox",
            "options":[{"group":"access","name":"Access","options":[
                {"value":"sandbox","name":"Read only"},
                {"value":"project-write","name":"Workspace write","description":"Edit files in this project"}]}]}]
    }""").jsonObject

    @Test
    fun groupedSelectionUsesTheAgentValueWithoutOptimisticallyReplacingItsCurrentValue() = runComposeUiTest {
        var submitted: JsonObject? = null
        setContent { MaterialTheme { AcpSessionSettingsScreen(settings, false, {}, { submitted = it }) } }
        onNodeWithText("Read only").performClick()
        onNodeWithText("Access").assertExists()
        onNodeWithText("Workspace write").performClick()
        assertEquals("access-policy", submitted?.text("configId"))
        assertEquals("project-write", submitted?.text("value"))
        // Keep showing the confirmed value until the Agent replies.
        onNodeWithText("Read only").assertExists()
        assertEquals("sandbox", settings.objects("configOptions").single().text("currentValue"))
    }

    @Test
    fun aPendingSettingRequestDisablesItsSelectionControls() = runComposeUiTest {
        setContent { MaterialTheme { AcpSessionSettingsScreen(settings, true, {}, {}) } }
        onNodeWithText("Read only").assertIsNotEnabled()
    }

    @Test
    fun readOnlyBackendShowsSettingsWithoutEnablingChangesOrSteering() = runComposeUiTest {
        val readonly = JsonObject(settings + ("_messengerCapabilities" to buildJsonObject { put("settingsWritable", false); put("steering", false) }) +
            ("steering" to buildJsonObject { put("supported", true) }))
        assertEquals(false, acpSupportsSteering(readonly))
        assertEquals(true, acpSupportsSteering(JsonObject(settings + ("steering" to buildJsonObject { put("supported", true) }))))
        setContent { MaterialTheme { AcpSessionSettingsScreen(readonly, false, {}, {}) } }
        onNodeWithText("These session settings are read-only on this device.").assertExists()
        onNodeWithText("Read only").assertIsNotEnabled()
    }

    @Test
    fun readOnlyBackendDisablesComposerSettings() = runComposeUiTest {
        val readonly = JsonObject(settings + ("_messengerCapabilities" to buildJsonObject { put("settingsWritable", false) }))
        setContent { MaterialTheme { AcpSessionControls(readonly, false, {}) } }
        onNodeWithText("Permissions: Read only").assertIsNotEnabled()
    }

    @Test
    fun modeOnlyBackendAllowsModeAndKeepsOtherOptionsReadOnly() = runComposeUiTest {
        val modeSettings = JsonObject(settings + ("_messengerCapabilities" to buildJsonObject {
            put("settingsWritable", false); put("modeWritable", true); put("modePending", false)
        }) + ("modes" to Json.parseToJsonElement("""{"currentModeId":"plan","availableModes":[{"id":"plan","name":"Plan"},{"id":"code","name":"Code"}]}""")))
        var submitted: JsonObject? = null
        setContent { MaterialTheme { AcpSessionSettingsScreen(modeSettings, false, {}, { submitted = it }) } }
        onNodeWithText("Read only").assertIsNotEnabled()
        onNodeWithText("Plan").performClick()
        onNodeWithText("Code").performClick()
        assertEquals("code", submitted?.text("modeId"))
    }

    @Test
    fun uncertainModeRequiresExplicitInspection() = runComposeUiTest {
        val pending = JsonObject(settings + ("_messengerCapabilities" to buildJsonObject {
            put("settingsWritable", false); put("modeWritable", false); put("modePending", true)
        }))
        var inspections = 0
        setContent { MaterialTheme { AcpSessionSettingsScreen(pending, false, {}, {}, onInspectPendingMode = { inspections++ }) } }
        onNodeWithText("The previous mode change may have completed. Check the current settings before continuing.").assertExists()
        onNodeWithText("Check current settings").performClick()
        assertEquals(1, inspections)
    }

    @Test
    fun inspectedModeRequiresExplicitConfirmation() = runComposeUiTest {
        val inspected = Json.parseToJsonElement("""{
            "_messengerCapabilities":{"settingsWritable":false,"modeWritable":false,"modePending":true,"modeInspected":true,"modePendingId":"marker"},
            "modes":{"currentModeId":"code","availableModes":[{"id":"code","name":"Code"}]}
        }""").jsonObject
        var confirmed: Pair<String, String>? = null
        setContent { MaterialTheme { AcpSessionSettingsScreen(inspected, false, {}, {}, onConfirmPendingMode = { id, mode -> confirmed = id to mode }) } }
        onNodeWithText("Current mode: code. Confirm it before continuing.").assertExists()
        onNodeWithText("Confirm current mode").performClick()
        assertEquals("marker" to "code", confirmed)
    }

    @Test
    fun modeConfigOptionUsesItsOpaqueIdAndPendingConfirmationUsesItsValue() = runComposeUiTest {
        val snapshot = mutableStateOf(Json.parseToJsonElement("""{
            "_messengerCapabilities":{"settingsWritable":false,"modeWritable":true},
            "configOptions":[{"id":"legacy-mode","name":"Mode","category":"mode","type":"select","currentValue":"plan","options":[{"name":"Group","options":[{"value":"plan","name":"Plan"},{"value":"code","name":"Code"}]}]}]
        }""").jsonObject)
        var submitted: JsonObject? = null
        var confirmed: Pair<String, String>? = null
        setContent { MaterialTheme { AcpSessionSettingsScreen(snapshot.value, false, {}, { submitted = it }, onConfirmPendingMode = { id, mode -> confirmed = id to mode }) } }
        onNodeWithText("Plan").performClick()
        onNodeWithText("Code").performClick()
        assertEquals("legacy-mode", submitted?.text("configId"))
        assertEquals("code", submitted?.text("value"))
        runOnIdle { snapshot.value = JsonObject(snapshot.value + ("_messengerCapabilities" to buildJsonObject {
            put("settingsWritable", false); put("modeWritable", false); put("modePending", true); put("modeInspected", true)
            put("modePendingId", "marker"); put("modeConfigId", "legacy-mode")
        })) }
        onNodeWithText("Current mode: plan. Confirm it before continuing.").assertExists()
        onNodeWithText("Confirm current mode").performClick()
        assertEquals("marker" to "plan", confirmed)
    }

    @Test
    fun agentOptionNamedLegacyModeRemainsDistinctFromSyntheticMode() = runComposeUiTest {
        val collision = Json.parseToJsonElement("""{
            "configOptions":[{"id":"legacy-mode","name":"Agent option","type":"select","currentValue":"a","options":[{"value":"a","name":"Option A"},{"value":"b","name":"Option B"}]}],
            "modes":{"currentModeId":"plan","availableModes":[{"id":"plan","name":"Plan"},{"id":"code","name":"Code"}]}
        }""").jsonObject
        var submitted: JsonObject? = null
        setContent { MaterialTheme { AcpSessionSettingsScreen(collision, false, {}, { submitted = it }) } }
        onNodeWithText("Option A").performClick()
        onNodeWithText("Option B").performClick()
        assertEquals("legacy-mode", submitted?.text("configId"))
        assertEquals("b", submitted?.text("value"))
        onNodeWithText("Plan").performClick()
        onNodeWithText("Code").performClick()
        assertEquals("code", submitted?.text("modeId"))
    }

    @Test
    fun modeOnlyCapabilityDoesNotEnableAgentOptionWithSameId() = runComposeUiTest {
        val collision = Json.parseToJsonElement("""{
            "_messengerCapabilities":{"settingsWritable":false,"modeWritable":true},
            "configOptions":[{"id":"legacy-mode","name":"Agent option","type":"select","currentValue":"a","options":[{"value":"a","name":"Option A"}]}],
            "modes":{"currentModeId":"plan","availableModes":[{"id":"plan","name":"Plan"},{"id":"code","name":"Code"}]}
        }""").jsonObject
        var submitted: JsonObject? = null
        setContent { MaterialTheme { AcpSessionSettingsScreen(collision, false, {}, { submitted = it }) } }
        onNodeWithText("Option A").assertIsNotEnabled()
        onNodeWithText("Plan").performClick()
        onNodeWithText("Code").performClick()
        assertEquals("code", submitted?.text("modeId"))
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

    @Test
    fun booleanListItemHasOneToggleTargetAndKeepsTheConfirmedValue() = runComposeUiTest {
        val snapshot = Json.parseToJsonElement("""{"configOptions":[{"id":"fast","name":"Fast mode","description":"Use faster responses","type":"boolean","currentValue":false}]}""").jsonObject
        var submitted: JsonObject? = null
        setContent { MaterialTheme { AcpSessionSettingsScreen(snapshot, false, {}, { submitted = it }) } }
        onAllNodes(isToggleable()).assertCountEquals(1)
        onNode(isToggleable()).assertIsOff().performClick()
        assertEquals("fast", submitted?.text("configId"))
        assertEquals(JsonPrimitive(true), submitted?.get("value"))
        onNode(isToggleable()).assertIsOff()
    }

    @Test
    fun busyScreenKeepsNavigationAvailableAndDisablesToggle() = runComposeUiTest {
        val snapshot = Json.parseToJsonElement("""{"configOptions":[{"id":"fast","name":"Fast mode","type":"boolean","currentValue":true}]}""").jsonObject
        var back = false
        setContent { MaterialTheme { AcpSessionSettingsScreen(snapshot, true, { back = true }, {}) } }
        onNode(isToggleable()).assertIsNotEnabled()
        onNodeWithContentDescription("Back").performClick()
        assertEquals(true, back)
    }

}
