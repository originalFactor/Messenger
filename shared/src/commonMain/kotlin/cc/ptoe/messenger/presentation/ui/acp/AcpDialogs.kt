package cc.ptoe.messenger.presentation.ui.acp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.CoreBridgeRegistry
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.generated.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.stringResource

@Composable
fun AcpSettingsDialog(onClose: () -> Unit) {
    val core = CoreBridgeRegistry.bridge ?: return
    val scope = rememberCoroutineScope()
    val formatter = remember { Json { prettyPrint = true } }
    var enabled by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var profiles by remember { mutableStateOf("[]") }
    var ready by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(core) {
        try {
            val settings = core.controlSettings()
            enabled = settings["enabled"]?.jsonPrimitive?.booleanOrNull == true
            name = settings.text("name")
            profiles = formatter.encodeToString(JsonArray.serializer(), settings["agents"]?.jsonArray ?: JsonArray(emptyList()))
            ready = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message.orEmpty()
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(Res.string.acp_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = ready && !saving)
                    Text(stringResource(Res.string.acp_enable_control))
                }
                Text(stringResource(Res.string.acp_control_description))
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.acp_device_name)) },
                    enabled = ready && !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(profiles, { profiles = it }, label = { Text(stringResource(Res.string.acp_profiles)) },
                    enabled = ready && !saving, minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth())
                Text(stringResource(Res.string.acp_profile_help), style = MaterialTheme.typography.bodySmall)
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = ready && !saving, onClick = {
                scope.launch {
                    saving = true
                    try {
                        core.saveControlSettings(buildJsonObject {
                            put("enabled", enabled)
                            put("name", name)
                            put("agents", Json.parseToJsonElement(profiles).jsonArray)
                        })
                        onClose()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message.orEmpty()
                    } finally {
                        saving = false
                    }
                }
            }) { Text(stringResource(Res.string.acp_save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.acp_close)) } },
    )
}

@Composable
fun AcpChatDialog(onClose: () -> Unit) {
    val core = CoreBridgeRegistry.bridge ?: return
    val scope = rememberCoroutineScope()
    val endedEarly = stringResource(Res.string.acp_stream_ended)
    var agents by remember { mutableStateOf(emptyList<JsonObject>()) }
    var projects by remember { mutableStateOf(emptyList<JsonObject>()) }
    var sessions by remember { mutableStateOf(emptyList<JsonObject>()) }
    var selectedAgent by remember { mutableStateOf("") }
    var selectedProject by remember { mutableStateOf("") }
    var session by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(emptyList<JsonObject>()) }
    var before by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var partial by remember { mutableStateOf("") }
    var tool by remember { mutableStateOf("") }
    var permissionContext by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(emptyList<JsonObject>()) }
    var active by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    suspend fun request(action: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        requestAcp(core, action, endedEarly, fields)

    suspend fun loadCatalog() {
        val catalog = request("catalog")
        agents = catalog.objects("agents").filter { it.text("kind") == "acp" }
        projects = catalog.objects("projects")
        sessions = catalog.objects("sessions").filter { it.text("kind") == "acp" }
        if (selectedAgent.isEmpty()) selectedAgent = agents.firstOrNull()?.text("id").orEmpty()
    }

    suspend fun loadHistory(id: String, cursor: String = "") {
        val result = request("history") {
            put("sessionId", id)
            if (cursor.isNotEmpty()) put("before", cursor)
        }
        history = result.objects("messages") + if (cursor.isEmpty()) emptyList() else history
        before = result.text("before")
        partial = ""
    }

    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message.orEmpty()
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(core) { perform { loadCatalog() } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(Res.string.acp_chat)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AcpChoice(stringResource(Res.string.acp_choose_agent), agents, selectedAgent, !busy) { selectedAgent = it }
                AcpChoice(stringResource(Res.string.acp_no_workspace), projects, selectedProject, !busy, allowEmpty = true) { selectedProject = it }
                AcpChoice(stringResource(Res.string.acp_choose_session), sessions, session, !busy, labelKey = "title") { id ->
                    perform { session = id; loadHistory(id) }
                }
                TextButton(enabled = !busy && selectedAgent.isNotEmpty(), onClick = {
                    perform {
                        val created = request("create") {
                            put("agentId", selectedAgent)
                            if (selectedProject.isNotEmpty()) put("projectId", selectedProject)
                        }
                        session = created.text("sessionId")
                        loadCatalog()
                        loadHistory(session)
                    }
                }) { Text(stringResource(Res.string.acp_new_session)) }
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (before.isNotEmpty()) item {
                        TextButton(enabled = !busy, onClick = { perform { loadHistory(session, before) } }) {
                            Text(stringResource(Res.string.acp_older_messages))
                        }
                    }
                    items(history) { item ->
                        Text(item.text("text"), color = if (item.text("status") == "error")
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                    if (partial.isNotEmpty()) item { Text(partial) }
                    if (tool.isNotEmpty()) item { Text(tool, style = MaterialTheme.typography.labelMedium) }
                }
                if (options.isNotEmpty()) {
                    Text(stringResource(Res.string.acp_permission))
                    if (permissionContext.isNotEmpty()) Text(permissionContext)
                    options.forEach { option ->
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    core.controlPermission(active, option.text("id"))
                                    options = emptyList()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message.orEmpty()
                                }
                            }
                        }) { Text(option.text("name")) }
                    }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(draft, { draft = it }, enabled = !busy && session.isNotEmpty(), maxLines = 3,
                    modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.acp_message)) })
            }
        },
        confirmButton = {
            if (active.isNotEmpty()) {
                TextButton(onClick = { scope.launch { runCatching { core.controlCancel(active) } } }) {
                    Text(stringResource(Res.string.acp_stop))
                }
            } else {
                TextButton(enabled = !busy && session.isNotEmpty() && draft.isNotBlank(), onClick = {
                    perform {
                        val requestId = randomUuid()
                        active = requestId
                        partial = ""
                        var terminal = false
                        try {
                            core.controlCommand(requestId, buildJsonObject {
                                put("action", "prompt")
                                put("sessionId", session)
                                put("text", draft)
                            }) { event ->
                                when (event.text("type")) {
                                    "TurnStarted" -> draft = ""
                                    "TextDelta" -> partial += event.text("text")
                                    "ToolUpdate" -> tool = event["update"]?.jsonObject?.text("title").orEmpty()
                                    "Permission" -> {
                                        permissionContext = event["tool"]?.jsonObject?.let { tool ->
                                            listOf(tool.text("title"), tool.text("detail")).filter { it.isNotBlank() }.joinToString("\n")
                                        }.orEmpty()
                                        options = event.objects("options")
                                    }
                                    "Finished", "Cancelled" -> { terminal = true; draft = "" }
                                    "Error" -> { terminal = true; error = event.errorDetail() }
                                }
                            }
                            if (!terminal) error = endedEarly
                            loadHistory(session)
                        } finally {
                            active = ""
                            tool = ""
                            options = emptyList()
                        }
                    }
                }) { Text(stringResource(Res.string.acp_send)) }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.acp_close)) } },
    )
}

@Composable
private fun AcpChoice(
    label: String, choices: List<JsonObject>, selected: String, enabled: Boolean,
    allowEmpty: Boolean = false, labelKey: String = "name", onChoice: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(enabled = enabled && (choices.isNotEmpty() || allowEmpty), onClick = { expanded = true }) {
            Text(choices.firstOrNull { it.text("id") == selected }?.text(labelKey) ?: label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowEmpty) DropdownMenuItem(text = { Text(label) }, onClick = { onChoice(""); expanded = false })
            choices.forEach { row ->
                DropdownMenuItem(text = { Text(row.text(labelKey)) }, onClick = {
                    onChoice(row.text("id"))
                    expanded = false
                })
            }
        }
    }
}

private suspend fun requestAcp(
    core: CoreBridge, action: String, endedEarly: String,
    fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
): JsonObject {
    var result: JsonObject? = null
    core.controlCommand(randomUuid(), buildJsonObject { put("action", action); fields() }) { result = it }
    return checkNotNull(result) { endedEarly }.also {
        if (it.text("type") == "Error") error(it.errorDetail())
    }
}

private fun JsonObject.text(key: String): String = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonObject.objects(key: String): List<JsonObject> = get(key)?.jsonArray?.map { it.jsonObject }.orEmpty()
private fun JsonObject.errorDetail() = listOf(text("message"), text("detail")).filter { it.isNotEmpty() }.joinToString(" ")
