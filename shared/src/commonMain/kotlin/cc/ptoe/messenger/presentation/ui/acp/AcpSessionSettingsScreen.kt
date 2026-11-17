package cc.ptoe.messenger.presentation.ui.acp

import cc.ptoe.messenger.presentation.ui.components.ExpressiveScaffold

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
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
private data class AcpSettingRow(val option: JsonObject, val legacyMode: Boolean)

/** Older backends omit this field and retain their existing supported behavior. */
internal fun acpBackendAllows(settings: JsonObject, capability: String): Boolean =
    ((settings["_messengerCapabilities"] as? JsonObject)?.get(capability) as? JsonPrimitive)?.booleanOrNull != false

internal fun acpSupportsSteering(settings: JsonObject): Boolean = acpBackendAllows(settings, "steering") &&
    (settings["steering"] as? JsonObject)?.get("supported") == JsonPrimitive(true)

private fun acpConfigWritable(settings: JsonObject, option: JsonObject): Boolean {
    val capabilities = settings["_messengerCapabilities"] as? JsonObject
    if ((capabilities?.get("modePending") as? JsonPrimitive)?.booleanOrNull == true) return false
    if (acpBackendAllows(settings, "settingsWritable")) return true
    if (option.text("type") != "select") return false
    val ids = capabilities?.get("writableConfigIds") as? JsonArray
    if (ids != null) return ids.any { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull == option.text("id") }
    return (capabilities?.get("modeWritable") as? JsonPrimitive)?.booleanOrNull == true && option.text("category") == "mode"
}

/** ACP select options may be flat or grouped; IDs are opaque, never inferred from labels. */
internal fun acpSettingChoices(options: JsonArray): List<AcpSettingChoice> {
    val choices = mutableListOf<AcpSettingChoice>()
    fun visit(items: JsonArray, depth: Int, group: String) {
        if (depth > 16 || choices.size >= 1_000) return
        for (element in items) {
            if (choices.size >= 1_000) return
            val option = element as? JsonObject ?: continue
            val grouped = option["options"] as? JsonArray
            if (grouped != null) visit(grouped, depth + 1, option.text("name"))
            else option["value"]?.let { choices.add(AcpSettingChoice(it, option.text("name"), option.text("description"), group)) }
        }
    }
    visit(options, 0, "")
    return choices
}

/** Connected settings and selection rows follow InstallerX-Revived's expressive list pattern. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AcpSessionSettingsScreen(
    settings: JsonObject,
    busy: Boolean,
    onDismiss: () -> Unit,
    onChange: (JsonObject) -> Unit,
    errorMessage: String? = null,
    onInspectPendingMode: () -> Unit = {},
    onConfirmPendingMode: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val editable = acpBackendAllows(settings, "settingsWritable")
    val capabilities = settings["_messengerCapabilities"] as? JsonObject
    val modePending = (capabilities?.get("modePending") as? JsonPrimitive)?.booleanOrNull == true
    val modeWritable = !modePending && (capabilities?.get("modeWritable") as? JsonPrimitive)?.booleanOrNull == true
    val modeInspected = (capabilities?.get("modeInspected") as? JsonPrimitive)?.booleanOrNull == true
    val modePendingId = (capabilities?.get("modePendingId") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    val modeConfigId = (capabilities?.get("modeConfigId") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    val pendingIsMode = modeConfigId == null || settings.objects("configOptions").singleOrNull { it.text("id") == modeConfigId }?.text("category") == "mode"
    val options = settings.objects("configOptions").filter { it.text("type") in listOf("select", "boolean") }.map { AcpSettingRow(it, false) }.toMutableList()
    val modes = settings["modes"] as? JsonObject
    val modeLabel = stringResource(Res.string.acp_session_mode)
    if (modes != null && options.none { it.option.text("category") == "mode" }) options += AcpSettingRow(buildJsonObject {
        put("id", "legacy-mode"); put("name", modeLabel); put("type", "select")
        put("currentValue", modes["currentModeId"] ?: JsonNull)
        put("options", buildJsonArray { modes.objects("availableModes").forEach { mode -> add(buildJsonObject {
            put("value", mode.text("id")); put("name", mode.text("name")); put("description", mode.text("description"))
        }) } })
    }, true)
    var choosing by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(editable) { if (!editable) choosing = null }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        ExpressiveScaffold(
            modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(Res.string.conversation_settings_title)) },
                    navigationIcon = { FilledTonalIconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back))
                    } },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (busy) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
                    if (!editable) Text(stringResource(if (modeWritable || options.any { !it.legacyMode && acpConfigWritable(settings, it.option) }) Res.string.acp_settings_select_only else Res.string.acp_settings_read_only), style = MaterialTheme.typography.bodyMedium)
                    if (modePending) {
                        if (modeInspected) {
                            val value = if (modeConfigId == null) modes?.get("currentModeId") else
                                settings.objects("configOptions").singleOrNull { it.text("id") == modeConfigId }?.get("currentValue")
                            val current = ((value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull).orEmpty()
                            Text(stringResource(if (pendingIsMode) Res.string.acp_mode_current else Res.string.acp_setting_current, current), style = MaterialTheme.typography.bodyMedium)
                            TextButton(enabled = !busy && !modePendingId.isNullOrBlank() && current.isNotBlank(),
                                onClick = { onConfirmPendingMode(checkNotNull(modePendingId), current) }) { Text(stringResource(if (pendingIsMode) Res.string.acp_confirm_mode else Res.string.acp_confirm_setting)) }
                        } else {
                            Text(stringResource(if (pendingIsMode) Res.string.acp_mode_uncertain else Res.string.acp_setting_uncertain), style = MaterialTheme.typography.bodyMedium)
                            TextButton(enabled = !busy, onClick = onInspectPendingMode) { Text(stringResource(Res.string.acp_check_mode)) }
                        }
                    }
                    if (!errorMessage.isNullOrBlank()) Surface(shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                        Text(errorMessage, Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!busy && options.isEmpty()) Text(stringResource(Res.string.acp_no_session_settings), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        options.forEachIndexed { index, row -> key(row.legacyMode, row.option.text("id")) {
                            val option = row.option
                            val boolean = option.text("type") == "boolean"
                            val checked = (option["currentValue"] as? JsonPrimitive)?.booleanOrNull == true
                            val choices = acpSettingChoices(option["options"] as? JsonArray ?: JsonArray(emptyList()))
                            val current = choices.firstOrNull { it.value == option["currentValue"] }?.name ?: option.text("currentValue")
                            cc.ptoe.messenger.presentation.ui.components.SettingsItem(
                                selected = false, enabled = (if (row.legacyMode) editable || modeWritable else acpConfigWritable(settings, option)) && !busy && (boolean || choices.isNotEmpty()),
                                onClick = {
                                    if (boolean) onChange(buildJsonObject { put("configId", option.text("id")); put("value", !checked) })
                                    else choosing = option.text("id") to row.legacyMode
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics(mergeDescendants = true) {
                                    if (boolean) { role = Role.Switch; toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
                                },
                                index = index, count = options.size,
                                containerColor = MaterialTheme.colorScheme.surfaceBright,
                                leadingContent = { Icon(if (boolean) Icons.Default.Tune else Icons.Default.Settings, null) },
                                supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (!boolean) Text(current, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                                    if (option.text("description").isNotBlank()) Text(option.text("description"), style = MaterialTheme.typography.bodyMedium)
                                } },
                                trailingContent = {
                                    if (boolean) Switch(checked, null, enabled = editable && !busy, modifier = Modifier.clearAndSetSemantics {}, thumbContent = {
                                        Icon(if (checked) Icons.Default.Check else Icons.Default.Close, null, Modifier.size(SwitchDefaults.IconSize))
                                    }) else Icon(Icons.Default.ExpandMore, null)
                                },
                                headlineContent = { Text(option.text("name"), style = MaterialTheme.typography.titleMedium) },
                            )
                        } }
                    }
                }
            }
        }
        options.firstOrNull { it.option.text("id") == choosing?.first && it.legacyMode == choosing?.second }?.let { row ->
            val option = row.option
            val choices = acpSettingChoices(option["options"] as? JsonArray ?: JsonArray(emptyList()))
            AlertDialog(
                onDismissRequest = { choosing = null },
                title = { Text(option.text("name")) },
                text = {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        choices.forEachIndexed { index, choice ->
                            if (choice.group.isNotBlank() && (index == 0 || choices[index - 1].group != choice.group)) {
                                Text(choice.group, Modifier.padding(16.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            val selectedChoice = choice.value == option["currentValue"]
                            cc.ptoe.messenger.presentation.ui.components.SettingsItem(
                                selected = selectedChoice, enabled = (if (row.legacyMode) editable || modeWritable else acpConfigWritable(settings, option)) && !busy,
                                onClick = {
                                    choosing = null
                                    onChange(buildJsonObject {
                                        if (row.legacyMode) put("modeId", choice.value)
                                        else { put("configId", option.text("id")); put("value", choice.value) }
                                    })
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) { role = Role.RadioButton; selected = selectedChoice },
                                index = index, count = choices.size,
                                containerColor = MaterialTheme.colorScheme.surfaceBright,
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                supportingContent = choice.description.takeIf { it.isNotBlank() }?.let { description -> { Text(description) } },
                                trailingContent = { RadioButton(selectedChoice, null, enabled = (if (row.legacyMode) editable || modeWritable else acpConfigWritable(settings, option)) && !busy, modifier = Modifier.clearAndSetSemantics {}) },
                                headlineContent = { Text(choice.name, style = MaterialTheme.typography.titleMedium) },
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { choosing = null }) { Text(stringResource(Res.string.action_close)) } },
            )
        }
}

/** Agent-owned choices are shown next to the composer, including model-dependent effort options. */
@Composable
internal fun AcpSessionControls(settings: JsonObject, busy: Boolean, onChange: (JsonObject) -> Unit) {
    val options = settings.objects("configOptions")
    val editable = acpBackendAllows(settings, "settingsWritable")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.sortedBy { when (it.text("category")) { "model" -> 0; "thought_level" -> 1; else -> 2 } }.forEach { option ->
            key(option.text("id")) {
                when (option.text("type")) {
                    "select" -> AcpSettingSelector(option, acpConfigWritable(settings, option) && !busy, onChange)
                    "boolean" -> FilterChip(
                        selected = (option["currentValue"] as? JsonPrimitive)?.booleanOrNull == true,
                        enabled = editable && !busy,
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
