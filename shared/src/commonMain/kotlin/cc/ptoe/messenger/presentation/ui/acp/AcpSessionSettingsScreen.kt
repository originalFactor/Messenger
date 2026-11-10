package cc.ptoe.messenger.presentation.ui.acp

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

/** ACP select options may be flat or grouped; IDs are opaque, never inferred from labels. */
internal fun acpSettingChoices(options: JsonArray): List<AcpSettingChoice> = options.flatMap { element ->
    val option = element.jsonObject
    val grouped = option["options"] as? JsonArray
    if (grouped != null) acpSettingChoices(grouped).map { it.copy(group = option.text("name")) }
    else listOfNotNull(option["value"]?.let { AcpSettingChoice(it, option.text("name"), option.text("description")) })
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
    modifier: Modifier = Modifier,
) {
    val options = settings.objects("configOptions").filter { it.text("type") in listOf("select", "boolean") }.toMutableList()
    val modes = settings["modes"] as? JsonObject
    val modeLabel = stringResource(Res.string.acp_session_mode)
    if (modes != null && options.none { it.text("category") == "mode" }) options += buildJsonObject {
        put("id", "legacy-mode"); put("name", modeLabel); put("type", "select")
        put("currentValue", modes["currentModeId"] ?: JsonNull)
        put("options", buildJsonArray { modes.objects("availableModes").forEach { mode -> add(buildJsonObject {
            put("value", mode.text("id")); put("name", mode.text("name")); put("description", mode.text("description"))
        }) } })
    }
    var choosing by remember { mutableStateOf<String?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Bound corner interpolation: a spring may overshoot zero-radius connected corners.
    val motion = MaterialTheme.motionScheme
    val boundedMotion = remember(motion) { object : MotionScheme by motion {
        override fun <T> fastSpatialSpec(): androidx.compose.animation.core.FiniteAnimationSpec<T> = androidx.compose.animation.core.tween(180)
    } }
    MaterialTheme(motionScheme = boundedMotion) {
        Scaffold(
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
                    if (!errorMessage.isNullOrBlank()) Surface(shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                        Text(errorMessage, Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!busy && options.isEmpty()) Text(stringResource(Res.string.acp_no_session_settings), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        options.forEachIndexed { index, option -> key(option.text("id")) {
                            val boolean = option.text("type") == "boolean"
                            val checked = (option["currentValue"] as? JsonPrimitive)?.booleanOrNull == true
                            val choices = acpSettingChoices(option["options"] as? JsonArray ?: JsonArray(emptyList()))
                            val current = choices.firstOrNull { it.value == option["currentValue"] }?.name ?: option.text("currentValue")
                            ListItem(
                                selected = false, enabled = !busy && (boolean || choices.isNotEmpty()),
                                onClick = {
                                    if (boolean) onChange(buildJsonObject { put("configId", option.text("id")); put("value", !checked) })
                                    else choosing = option.text("id")
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics(mergeDescendants = true) {
                                    if (boolean) { role = Role.Switch; toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
                                },
                                shapes = connectedSettingShapes(index, options.size),
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                                leadingContent = { Icon(if (boolean) Icons.Default.Tune else Icons.Default.Settings, null) },
                                supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (!boolean) Text(current, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                                    if (option.text("description").isNotBlank()) Text(option.text("description"), style = MaterialTheme.typography.bodyMedium)
                                } },
                                trailingContent = {
                                    if (boolean) Switch(checked, null, enabled = !busy, modifier = Modifier.clearAndSetSemantics {}, thumbContent = {
                                        Icon(if (checked) Icons.Default.Check else Icons.Default.Close, null, Modifier.size(SwitchDefaults.IconSize))
                                    }) else Icon(Icons.Default.ExpandMore, null)
                                },
                                content = { Text(option.text("name"), style = MaterialTheme.typography.titleMedium) },
                            )
                        } }
                    }
                }
            }
        }
        options.firstOrNull { it.text("id") == choosing }?.let { option ->
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
                            ListItem(
                                selected = selectedChoice, enabled = !busy,
                                onClick = {
                                    choosing = null
                                    onChange(buildJsonObject {
                                        if (option.text("id") == "legacy-mode") put("modeId", choice.value)
                                        else { put("configId", option.text("id")); put("value", choice.value) }
                                    })
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) { role = Role.RadioButton; selected = selectedChoice },
                                shapes = connectedSettingShapes(index, choices.size),
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright,
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedSupportingContentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                                supportingContent = choice.description.takeIf { it.isNotBlank() }?.let { description -> { Text(description) } },
                                trailingContent = { RadioButton(selectedChoice, null, enabled = !busy, modifier = Modifier.clearAndSetSemantics {}) },
                                content = { Text(choice.name, style = MaterialTheme.typography.titleMedium) },
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { choosing = null }) { Text(stringResource(Res.string.action_close)) } },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun connectedSettingShapes(index: Int, count: Int): ListItemShapes {
    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall
    val shape = outer.copy(
        topStart = if (index == 0) outer.topStart else inner.topStart,
        topEnd = if (index == 0) outer.topEnd else inner.topEnd,
        bottomStart = if (index == count - 1) outer.bottomStart else inner.bottomStart,
        bottomEnd = if (index == count - 1) outer.bottomEnd else inner.bottomEnd,
    )
    return ListItemDefaults.shapes(shape = shape, pressedShape = outer,
        selectedShape = shape, focusedShape = shape, hoveredShape = shape)
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
