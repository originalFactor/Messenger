package cc.ptoe.messenger.presentation.ui.acp

import cc.ptoe.messenger.presentation.ui.components.settingsItems

import cc.ptoe.messenger.presentation.ui.components.ExpressiveTopAppBar

import cc.ptoe.messenger.presentation.ui.components.ExpressiveScaffold

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.generated.resources.*
import cc.ptoe.messenger.presentation.ui.components.ConfirmationDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcpSettingsScreen(onBackClick: () -> Unit) {
    val core = AppContainerHolder.instance.coreBridge
    val scope = rememberCoroutineScope()
    val json = remember { Json { ignoreUnknownKeys = true } }
    var profiles by remember { mutableStateOf(emptyList<AcpProfile>()) }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AcpProfile?>(null) }
    var deleting by remember { mutableStateOf<AcpProfile?>(null) }
    var store by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    suspend fun reload() {
        profiles = json.decodeFromJsonElement(core.controlSettings()["agents"] ?: JsonArray(emptyList()))
        ready = true
    }
    fun save(next: List<AcpProfile>, completed: () -> Unit = {}) {
        busy = true
        scope.launch {
            try {
                val current = core.controlSettings()
                core.saveControlSettings(JsonObject(current + ("agents" to json.encodeToJsonElement(next))))
                reload()
                completed()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { snackbar.showSnackbar(failure.message.orEmpty())
            } finally { busy = false }
        }
    }
    LaunchedEffect(core) {
        try { reload() } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { snackbar.showSnackbar(failure.message.orEmpty()) }
    }
    ExpressiveScaffold(topBar = { ExpressiveTopAppBar(title = { Text(stringResource(Res.string.acp_settings)) }, navigationIcon = {
        androidx.compose.material3.FilledTonalIconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back)) }
    }) }, snackbarHost = { SnackbarHost(snackbar) }, floatingActionButton = {
        if (ready && !busy) IntegrationFab(expanded, { expanded = it }, { adding = true }, { store = true })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item { Text(stringResource(Res.string.acp_config_note), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                if (ready && profiles.isEmpty()) item { Text(stringResource(Res.string.integrations_empty), Modifier.padding(32.dp)) }
                settingsItems(profiles, key = { it.id }, contentType = "acp") { profile ->
                    cc.ptoe.messenger.presentation.ui.components.ExpressiveRow(
                        onClick = { editing = profile }, enabled = !busy,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    ) {
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                                if (profile.description.isNotBlank()) Text(profile.description, style = MaterialTheme.typography.bodyMedium)
                                Text("${profile.command} ${profile.args.joinToString(" ")}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                            IconButton(enabled = !busy, onClick = { editing = profile }) { Icon(Icons.Default.Edit, stringResource(Res.string.action_edit)) }
                            IconButton(enabled = !busy, onClick = { deleting = profile }) { Icon(Icons.Default.Delete, stringResource(Res.string.action_delete)) }
                            Switch(profile.enabled, { enabled -> save(profiles.map { if (it.id == profile.id) it.copy(enabled = enabled) else it }) }, enabled = !busy)
                    }
                }
            }
            if (busy || !ready) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (expanded) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { expanded = false } })
        }
    }
    if (store) IntegrationStoreDialog("acp", { store = false }) { row ->
        try {
            editing = json.decodeFromJsonElement<AcpProfile>(row).copy(id = randomUuid())
            store = false
            adding = true
        } catch (failure: Exception) { scope.launch { snackbar.showSnackbar(failure.message.orEmpty()) } }
    }
    if (adding || editing != null) AcpProfileDialog(editing, busy, { adding = false; editing = null }, onSave = { profile ->
        save(profiles.filterNot { it.id == profile.id } + profile) { adding = false; editing = null }
    }, isNew = adding)
    deleting?.let { profile ->
        ConfirmationDialog(title = stringResource(Res.string.action_delete), text = profile.name,
            confirmButtonText = stringResource(Res.string.action_delete), dismissButtonText = stringResource(Res.string.action_cancel),
            onConfirm = { save(profiles.filterNot { it.id == profile.id }) { deleting = null } }, onDismiss = { deleting = null })
    }
}

@Composable
internal fun AcpProfileDialog(profile: AcpProfile?, busy: Boolean, onDismiss: () -> Unit, onSave: (AcpProfile) -> Unit, isNew: Boolean = profile == null) {
    var name by remember { mutableStateOf(profile?.name.orEmpty()) }
    var description by remember { mutableStateOf(profile?.description.orEmpty()) }
    var command by remember { mutableStateOf(profile?.command.orEmpty()) }
    var args by remember { mutableStateOf(Json.encodeToString(profile?.args ?: emptyList<String>())) }
    var env by remember { mutableStateOf(Json.encodeToString(profile?.env ?: emptyMap<String, String>())) }
    var cwd by remember { mutableStateOf(profile?.cwd.orEmpty()) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(if (isNew) Res.string.acp_add else Res.string.acp_edit)) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.tools_mcp_name)) }, singleLine = true)
            OutlinedTextField(description, { description = it }, label = { Text(stringResource(Res.string.acp_description)) })
            OutlinedTextField(command, { command = it }, label = { Text(stringResource(Res.string.tools_mcp_command)) }, singleLine = true)
            OutlinedTextField(args, { args = it }, label = { Text(stringResource(Res.string.acp_args_json)) })
            OutlinedTextField(env, { env = it }, label = { Text(stringResource(Res.string.acp_env_json)) })
            OutlinedTextField(cwd, { cwd = it }, label = { Text(stringResource(Res.string.acp_working_directory)) }, singleLine = true)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank() && command.isNotBlank(), onClick = {
            try {
                onSave(AcpProfile(profile?.id ?: randomUuid(), name.trim(), description.trim(), command.trim(),
                    Json.decodeFromString(args), Json.decodeFromString(env), cwd.trim().ifBlank { null }, profile?.enabled ?: true))
            } catch (failure: Exception) { error = failure.message.orEmpty() }
        }) { Text(stringResource(Res.string.action_save)) } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } })
}
