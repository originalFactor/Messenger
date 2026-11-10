package cc.ptoe.messenger.presentation.ui.acp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AcpSessionSettingsDialog(
    settings: JsonObject,
    busy: Boolean,
    onDismiss: () -> Unit,
    onChange: (JsonObject) -> Unit,
    errorMessage: String? = null,
    asPage: Boolean = false,
) {
    val options = settings.objects("configOptions")
    val modes = settings["modes"] as? JsonObject
    val content: @Composable () -> Unit = {
            Column(Modifier.then(if (asPage) Modifier.fillMaxSize() else Modifier.heightIn(max = 480.dp)).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            AcpSettingSelector(option, !busy, onChange)
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
    }
    if (asPage) {
        Scaffold(topBar = { androidx.compose.material3.TopAppBar(
            title = { Text(stringResource(Res.string.conversation_settings_title)) },
            navigationIcon = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_back)) } }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).padding(16.dp)) { content() }
        }
    } else AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.conversation_settings_title)) }, text = content,
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

/** Agent-owned choices are shown next to the composer, including model-dependent effort options. */
@Composable
internal fun AcpSessionControls(settings: JsonObject, busy: Boolean, onChange: (JsonObject) -> Unit) {
    val options = settings.objects("configOptions")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.sortedBy { when (it.text("category")) { "model" -> 0; "thought_level" -> 1; else -> 2 } }.forEach { option ->
            key(option.text("id")) {
                when (option.text("type")) {
                    "select" -> AcpSettingSelector(option, !busy, onChange)
                    "boolean" -> FilterChip(
                        selected = (option["currentValue"] as? JsonPrimitive)?.booleanOrNull == true,
                        enabled = !busy,
                        onClick = { onChange(buildJsonObject {
                            put("configId", option.text("id"))
                            put("value", (option["currentValue"] as? JsonPrimitive)?.booleanOrNull != true)
                        }) },
                        label = { Text(option.text("name"), maxLines = 1) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AcpSettingSelector(option: JsonObject, enabled: Boolean, onChange: (JsonObject) -> Unit) {
    var expanded by remember(option.text("id")) { mutableStateOf(false) }
    val choices = acpSettingChoices(option["options"] as? JsonArray ?: JsonArray(emptyList()))
    val current = choices.firstOrNull { it.value == option["currentValue"] }?.name ?: option.text("currentValue")
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        AssistChip(enabled = enabled && choices.isNotEmpty(), onClick = { expanded = true },
            label = { Text("${option.text("name")}: $current", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp)) })
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp).widthIn(max = 360.dp)) {
            var previousGroup = ""
            choices.forEach { choice ->
                if (choice.group.isNotBlank() && choice.group != previousGroup) {
                    Text(choice.group, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                }
                previousGroup = choice.group
                DropdownMenuItem(text = {
                    Column {
                        Text(choice.name, fontWeight = if (choice.value == option["currentValue"]) androidx.compose.ui.text.font.FontWeight.Bold else null)
                        if (choice.description.isNotBlank()) Text(choice.description, style = MaterialTheme.typography.bodySmall)
                    }
                }, onClick = {
                    expanded = false
                    onChange(buildJsonObject { put("configId", option.text("id")); put("value", choice.value) })
                })
            }
        }
    }
}
