package cc.ptoe.messenger.presentation.ui.acp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.generated.resources.*
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

internal data class AcpSettingChoice(val value: JsonElement, val name: String, val description: String, val group: String = "")

/** ACP select options may be flat or grouped; IDs are opaque, never inferred from labels. */
internal fun acpSettingChoices(options: JsonArray): List<AcpSettingChoice> = options.flatMap { element ->
    val option = element.jsonObject
    val grouped = option["options"] as? JsonArray
    if (grouped != null) acpSettingChoices(grouped).map { it.copy(group = option.text("name")) }
    else listOfNotNull(option["value"]?.let { AcpSettingChoice(it, option.text("name"), option.text("description")) })
}

@Composable
internal fun AcpSessionSettingsDialog(
    settings: JsonObject,
    busy: Boolean,
    onDismiss: () -> Unit,
    onChange: (JsonObject) -> Unit,
    errorMessage: String? = null,
) {
    val options = settings.objects("configOptions")
    val modes = settings["modes"] as? JsonObject
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.conversation_settings_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!errorMessage.isNullOrBlank()) Text(errorMessage, color = MaterialTheme.colorScheme.error)
                if (!busy && options.isEmpty() && modes == null) Text(stringResource(Res.string.acp_no_session_settings))
                options.forEach { option ->
                    when (option.text("type")) {
                        "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(option.text("name"), style = MaterialTheme.typography.titleSmall)
                                if (option.text("description").isNotBlank()) Text(option.text("description"), style = MaterialTheme.typography.bodySmall)
                            }
                            Switch((option["currentValue"] as? JsonPrimitive)?.booleanOrNull == true,
                                { value -> onChange(buildJsonObject { put("configId", option.text("id")); put("value", value) }) }, enabled = !busy)
                        }
                        "select" -> {
                            Text(option.text("name"), style = MaterialTheme.typography.titleSmall)
                            if (option.text("description").isNotBlank()) Text(option.text("description"), style = MaterialTheme.typography.bodySmall)
                            val choices = acpSettingChoices(option["options"] as? JsonArray ?: JsonArray(emptyList()))
                            SettingChoices(choices, option["currentValue"], !busy) { value ->
                                onChange(buildJsonObject { put("configId", option.text("id")); put("value", value) })
                            }
                        }
                    }
                }
                // New configOptions supersede the legacy modes for the same category.
                if (modes != null && options.none { it.text("category") == "mode" }) {
                    Text(stringResource(Res.string.acp_session_mode), style = MaterialTheme.typography.titleSmall)
                    SettingChoices(modes.objects("availableModes").map { AcpSettingChoice(JsonPrimitive(it.text("id")), it.text("name"), it.text("description")) }, modes["currentModeId"], !busy) { value ->
                        onChange(buildJsonObject { put("modeId", value) })
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } }
    )
}

@Composable
private fun SettingChoices(choices: List<AcpSettingChoice>, current: JsonElement?, enabled: Boolean, onSelect: (JsonElement) -> Unit) {
    var previousGroup = ""
    choices.forEach { choice ->
        if (choice.group.isNotBlank() && choice.group != previousGroup) Text(choice.group, style = MaterialTheme.typography.labelMedium)
        previousGroup = choice.group
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(choice.value) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(choice.value == current, onClick = { onSelect(choice.value) }, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(choice.name)
                if (choice.description.isNotBlank()) Text(choice.description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
