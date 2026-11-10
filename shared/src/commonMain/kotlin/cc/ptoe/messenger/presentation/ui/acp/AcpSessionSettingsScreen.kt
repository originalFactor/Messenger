package cc.ptoe.messenger.presentation.ui.acp

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AcpSessionSettingsScreen(
    settings: JsonObject,
    busy: Boolean,
    onDismiss: () -> Unit,
    onChange: (JsonObject) -> Unit,
    errorMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    val options = settings.objects("configOptions")
    val modes = settings["modes"] as? JsonObject
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.conversation_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (busy) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ContainedLoadingIndicator()
                }
                if (!errorMessage.isNullOrBlank()) Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(errorMessage, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
                if (!busy && options.isEmpty() && modes == null) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Text(stringResource(Res.string.acp_no_session_settings), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                options.forEach { option -> key(option.text("id")) {
                    when (option.text("type")) {
                        "boolean" -> {
                            val checked = (option["currentValue"] as? JsonPrimitive)?.booleanOrNull == true
                            Card(shape = MaterialTheme.shapes.large,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                                ListItem(
                                    modifier = Modifier.toggleable(value = checked, enabled = !busy, role = Role.Switch) {
                                        onChange(buildJsonObject { put("configId", option.text("id")); put("value", it) })
                                    },
                                    headlineContent = { Text(option.text("name")) },
                                    supportingContent = option.text("description").takeIf { it.isNotBlank() }?.let { description -> { Text(description) } },
                                    trailingContent = {
                                        Switch(checked = checked, onCheckedChange = null, enabled = !busy,
                                            thumbContent = if (checked) ({ Icon(Icons.Default.Check, null, Modifier.size(SwitchDefaults.IconSize)) }) else null)
                                    },
                                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                )
                            }
                        }
                        "select" -> AcpSettingsField(option, !busy, onChange)
                    }
                } }
                // Legacy modes remain available when no replacement mode config was supplied.
                if (modes != null && options.none { it.text("category") == "mode" }) {
                    val modeOption = buildJsonObject {
                        put("id", "legacy-mode"); put("name", stringResource(Res.string.acp_session_mode))
                        put("currentValue", modes["currentModeId"] ?: JsonNull)
                        put("options", buildJsonArray { modes.objects("availableModes").forEach { mode -> add(buildJsonObject {
                            put("value", mode.text("id")); put("name", mode.text("name")); put("description", mode.text("description"))
                        }) } })
                    }
                    AcpSettingsField(modeOption, !busy) { change -> onChange(buildJsonObject { put("modeId", change["value"] ?: JsonNull) }) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AcpSettingsField(option: JsonObject, enabled: Boolean, onChange: (JsonObject) -> Unit) {
    var expanded by remember(option.text("id")) { mutableStateOf(false) }
    val choices = acpSettingChoices(option["options"] as? JsonArray ?: JsonArray(emptyList()))
    val current = choices.firstOrNull { it.value == option["currentValue"] }?.name ?: option.text("currentValue")
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = current, onValueChange = {}, readOnly = true, enabled = enabled && choices.isNotEmpty(),
            label = { Text(option.text("name")) },
            supportingText = option.text("description").takeIf { it.isNotBlank() }?.let { description -> { Text(description) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled && choices.isNotEmpty()),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            var previousGroup = ""
            choices.forEach { choice ->
                if (choice.group.isNotBlank() && choice.group != previousGroup) {
                    Text(choice.group, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                previousGroup = choice.group
                DropdownMenuItem(
                    modifier = Modifier.semantics { selected = choice.value == option["currentValue"] },
                    text = { Column {
                        Text(choice.name, style = MaterialTheme.typography.bodyLarge)
                        if (choice.description.isNotBlank()) Text(choice.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } },
                    trailingIcon = if (choice.value == option["currentValue"]) ({ Icon(Icons.Default.Check, null) }) else null,
                    onClick = {
                        expanded = false
                        onChange(buildJsonObject { put("configId", option.text("id")); put("value", choice.value) })
                    },
                )
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
