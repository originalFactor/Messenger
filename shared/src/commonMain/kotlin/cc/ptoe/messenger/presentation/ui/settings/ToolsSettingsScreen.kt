/*
 * Copyright 2026 originalFactor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.ptoe.messenger.presentation.ui.settings

import cc.ptoe.messenger.presentation.ui.components.settingsItems

import cc.ptoe.messenger.presentation.ui.components.ExpressiveTopAppBar

import cc.ptoe.messenger.presentation.ui.components.ExpressiveScaffold

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.presentation.ui.acp.IntegrationFab
import cc.ptoe.messenger.presentation.ui.acp.IntegrationStoreDialog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.domain.mcp.McpManager
import cc.ptoe.messenger.domain.mcp.McpServerConfig
import cc.ptoe.messenger.domain.mcp.McpTransportType
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.tools_approval_mode
import cc.ptoe.messenger.generated.resources.tools_approval_manual
import cc.ptoe.messenger.generated.resources.tools_approval_automatic
import cc.ptoe.messenger.generated.resources.tools_approval_description
import cc.ptoe.messenger.generated.resources.tools_approval_load_error
import cc.ptoe.messenger.generated.resources.action_cancel
import cc.ptoe.messenger.generated.resources.action_confirm
import cc.ptoe.messenger.generated.resources.action_save
import cc.ptoe.messenger.generated.resources.settings_tools
import cc.ptoe.messenger.generated.resources.tools_mcp_add
import cc.ptoe.messenger.generated.resources.tools_mcp_args
import cc.ptoe.messenger.generated.resources.tools_mcp_args_hint
import cc.ptoe.messenger.generated.resources.tools_mcp_command
import cc.ptoe.messenger.generated.resources.tools_mcp_config_error
import cc.ptoe.messenger.generated.resources.tools_mcp_command_hint
import cc.ptoe.messenger.generated.resources.tools_mcp_delete
import cc.ptoe.messenger.generated.resources.tools_mcp_delete_confirm
import cc.ptoe.messenger.generated.resources.tools_mcp_edit
import cc.ptoe.messenger.generated.resources.tools_mcp_empty
import cc.ptoe.messenger.generated.resources.tools_mcp_env
import cc.ptoe.messenger.generated.resources.tools_mcp_env_hint
import cc.ptoe.messenger.generated.resources.tools_mcp_headers
import cc.ptoe.messenger.generated.resources.tools_mcp_headers_hint
import cc.ptoe.messenger.generated.resources.tools_mcp_name
import cc.ptoe.messenger.generated.resources.tools_mcp_runtime_note
import cc.ptoe.messenger.generated.resources.tools_mcp_type
import cc.ptoe.messenger.generated.resources.tools_mcp_url
import cc.ptoe.messenger.generated.resources.tools_mcp_url_hint
import cc.ptoe.messenger.generated.resources.tools_settings_title
import cc.ptoe.messenger.generated.resources.tools_agent_delegation
import cc.ptoe.messenger.generated.resources.tools_agent_delegation_desc
import cc.ptoe.messenger.generated.resources.tools_schedule_task
import cc.ptoe.messenger.generated.resources.tools_schedule_task_desc
import cc.ptoe.messenger.generated.resources.tools_schedule_task_load_error
import cc.ptoe.messenger.generated.resources.action_retry
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.presentation.ui.components.ExpressiveSwitchRow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.CancellationException
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Serializable
private data class ScheduleToolSettings(val enabled: Boolean)
@Serializable
private data class ToolApprovalSettings(val mode: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsSettingsScreen(
    onBackClick: () -> Unit,
    mcpManager: McpManager,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val catalogJson = remember { Json { ignoreUnknownKeys = true } }
    val snackbar = remember { SnackbarHostState() }
    fun changeServer(onSuccess: () -> Unit = {}, operation: suspend () -> Unit) {
        scope.launch {
            try { operation(); onSuccess() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { snackbar.showSnackbar(failure.message ?: failure.toString()) }
        }
    }
    val mcpServers by mcpManager.servers.collectAsState()
    val mcpConfigurationError by mcpManager.configurationError.collectAsState()
    val agentDelegationEnabled by mcpManager.agentDelegationEnabled.collectAsState()
    var scheduleEnabled by remember { mutableStateOf(false) }
    var scheduleLoaded by remember { mutableStateOf(false) }
    var scheduleBusy by remember { mutableStateOf(false) }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    suspend fun loadScheduleSetting() {
        scheduleBusy = true
        try {
            val response = AppContainerHolder.instance.coreBridge.scheduledTaskRequest("GET", "/scheduled-tasks/tool-settings")
            scheduleEnabled = catalogJson.decodeFromString<ScheduleToolSettings>(response).enabled
            scheduleLoaded = true
            scheduleError = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { scheduleError = failure.message ?: failure.toString() }
        finally { scheduleBusy = false }
    }
    var approvalMode by remember { mutableStateOf("manual") }
    var approvalLoaded by remember { mutableStateOf(false) }
    var approvalBusy by remember { mutableStateOf(false) }
    var approvalError by remember { mutableStateOf<String?>(null) }
    var showApprovalChoice by remember { mutableStateOf(false) }
    suspend fun loadApprovalSetting() {
        approvalBusy = true
        try {
            approvalMode = catalogJson.decodeFromString<ToolApprovalSettings>(
                AppContainerHolder.instance.coreBridge.toolApprovalSettings("GET")
            ).mode
            approvalLoaded = true
            approvalError = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { approvalError = failure.message ?: failure.toString() }
        finally { approvalBusy = false }
    }
    LaunchedEffect(Unit) { loadScheduleSetting(); loadApprovalSetting() }

    var fabExpanded by remember { mutableStateOf(false) }
    var showStore by remember { mutableStateOf(false) }
    var storeDraft by remember { mutableStateOf<McpServerConfig?>(null) }
    var editingServer by remember { mutableStateOf<McpServerConfig?>(null) }
    var isAddingServer by remember { mutableStateOf(false) }
    var serverToDelete by remember { mutableStateOf<McpServerConfig?>(null) }

    ExpressiveScaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            ExpressiveTopAppBar(
                title = { Text(text = stringResource(Res.string.tools_settings_title)) },
                navigationIcon = {
                    androidx.compose.material3.FilledTonalIconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_cancel)
                        )
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            IntegrationFab(fabExpanded, { fabExpanded = it }, { isAddingServer = true }, { showStore = true })
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 80.dp)
            ) {
                item {
                    cc.ptoe.messenger.presentation.ui.components.ExpressiveRow(
                        onClick = { if (approvalLoaded && !approvalBusy) showApprovalChoice = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(Res.string.tools_approval_mode), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(if (approvalMode == "automatic") Res.string.tools_approval_automatic else Res.string.tools_approval_manual), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(Res.string.tools_approval_description), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                approvalError?.let { message ->
                    item {
                        Text(stringResource(Res.string.tools_approval_load_error, message), modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { scope.launch { loadApprovalSetting() } }, enabled = !approvalBusy) { Text(stringResource(Res.string.action_retry)) }
                    }
                }
                item {
                    cc.ptoe.messenger.presentation.ui.components.ExpressiveRow(
                        onClick = { changeServer { mcpManager.setAgentDelegationEnabled(!agentDelegationEnabled) } },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(Res.string.tools_agent_delegation), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(Res.string.tools_agent_delegation_desc), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = agentDelegationEnabled, onCheckedChange = { enabled ->
                            changeServer { mcpManager.setAgentDelegationEnabled(enabled) }
                        })
                    }
                }
                item {
                    ExpressiveSwitchRow(
                        title = stringResource(Res.string.tools_schedule_task),
                        subtitle = stringResource(Res.string.tools_schedule_task_desc),
                        checked = scheduleEnabled,
                        enabled = scheduleLoaded && !scheduleBusy,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        onCheckedChange = { enabled ->
                            scope.launch {
                                scheduleBusy = true
                                try {
                                    AppContainerHolder.instance.coreBridge.scheduledTaskRequest(
                                        "PUT", "/scheduled-tasks/tool-settings", catalogJson.encodeToString(ScheduleToolSettings(enabled))
                                    )
                                    scheduleEnabled = enabled
                                    scheduleError = null
                                    AppContainerHolder.instance.refreshCoreToolCatalog()
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { snackbar.showSnackbar(failure.message ?: failure.toString()) }
                                finally { scheduleBusy = false }
                            }
                        },
                    )
                }
                scheduleError?.let { message ->
                    item {
                        Text(stringResource(Res.string.tools_schedule_task_load_error, message),
                            modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { scope.launch { loadScheduleSetting() } }, enabled = !scheduleBusy) {
                            Text(stringResource(Res.string.action_retry))
                        }
                    }
                }
                if (mcpConfigurationError) {
                    item {
                        Text(
                            text = stringResource(Res.string.tools_mcp_config_error),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                if (mcpServers.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(Res.string.tools_mcp_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    settingsItems(mcpServers, key = { it.id }, contentType = "mcp") { server ->
                        cc.ptoe.messenger.presentation.ui.components.ExpressiveRow(
                            onClick = { editingServer = server },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = server.name,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    val detail = if (server.transportType == McpTransportType.STDIO) {
                                        "${server.command} ${server.args.joinToString(" ")}"
                                    } else {
                                        server.url
                                    }
                                    Text(
                                        text = "${server.transportType.name}: $detail",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                                IconButton(onClick = { editingServer = server }) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = stringResource(Res.string.tools_mcp_edit),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = { serverToDelete = server }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = stringResource(Res.string.tools_mcp_delete),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                                Switch(
                                    checked = server.isEnabled,
                                    onCheckedChange = { checked ->
                                        changeServer { mcpManager.toggleServer(server.id, checked) }
                                    }
                                )
                        }
                    }
                }
            }
            if (fabExpanded) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { fabExpanded = false } })
        }
    }

    if (showApprovalChoice) {
        val manual = stringResource(Res.string.tools_approval_manual)
        val automatic = stringResource(Res.string.tools_approval_automatic)
        cc.ptoe.messenger.presentation.ui.components.SingleChoiceDialog(
            title = stringResource(Res.string.tools_approval_mode),
            items = listOf("manual", "automatic"), initialSelectedId = approvalMode,
            itemId = { it }, itemLabel = { if (it == "automatic") automatic else manual },
            confirmButtonText = stringResource(Res.string.action_save), dismissButtonText = stringResource(Res.string.action_cancel),
            onDismiss = { showApprovalChoice = false }, onConfirm = { selected ->
                showApprovalChoice = false
                if (selected != null && selected != approvalMode) scope.launch {
                    approvalBusy = true
                    try {
                        val response = AppContainerHolder.instance.coreBridge.toolApprovalSettings("PUT", catalogJson.encodeToString(ToolApprovalSettings(selected)))
                        approvalMode = catalogJson.decodeFromString<ToolApprovalSettings>(response).mode
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { snackbar.showSnackbar(failure.message ?: failure.toString()) }
                    finally { approvalBusy = false }
                }
            },
        )
    }

    if (showStore) IntegrationStoreDialog("mcp", { showStore = false }) { row ->
        try {
            storeDraft = catalogJson.decodeFromJsonElement<McpServerConfig>(row).copy(id = randomUuid())
            showStore = false
        } catch (failure: Exception) { scope.launch { snackbar.showSnackbar(failure.message ?: failure.toString()) } }
    }
    storeDraft?.let { draft ->
        McpServerEditDialog(server = draft, isNew = true, onDismiss = { storeDraft = null }, onSave = { config ->
            changeServer(onSuccess = { storeDraft = null }) { mcpManager.addServer(config) }
        })
    }

    if (isAddingServer) {
        McpServerEditDialog(
            server = null,
            onDismiss = { isAddingServer = false },
            onSave = { newServer ->
                changeServer(onSuccess = { isAddingServer = false }) { mcpManager.addServer(newServer) }
            }
        )
    }

    editingServer?.let { server ->
        McpServerEditDialog(
            server = server,
            onDismiss = { editingServer = null },
            onSave = { updated ->
                changeServer(onSuccess = { editingServer = null }) { mcpManager.updateServer(updated) }
            }
        )
    }

    serverToDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { serverToDelete = null },
            title = { Text(stringResource(Res.string.tools_mcp_delete)) },
            text = { Text(stringResource(Res.string.tools_mcp_delete_confirm, server.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        changeServer(onSuccess = { serverToDelete = null }) { mcpManager.removeServer(server.id) }
                    }
                ) {
                    Text(stringResource(Res.string.action_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { serverToDelete = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun McpServerEditDialog(
    server: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
    isNew: Boolean = server == null
) {
    var name by remember { mutableStateOf(server?.name.orEmpty()) }
    var transportType by remember { mutableStateOf(server?.transportType ?: McpTransportType.STDIO) }
    var command by remember { mutableStateOf(server?.command.orEmpty()) }
    var argsText by remember { mutableStateOf(server?.args?.joinToString(" ").orEmpty()) }
    var envText by remember {
        mutableStateOf(server?.env?.map { "${it.key}=${it.value}" }?.joinToString("\n").orEmpty())
    }
    var url by remember { mutableStateOf(server?.url.orEmpty()) }
    var headersText by remember {
        mutableStateOf(server?.headers?.map { "${it.key}: ${it.value}" }?.joinToString("\n").orEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isNew) stringResource(Res.string.tools_mcp_add)
                else stringResource(Res.string.tools_mcp_edit)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.tools_mcp_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(Res.string.tools_mcp_type),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = transportType == McpTransportType.STDIO,
                        onClick = { transportType = McpTransportType.STDIO }
                    )
                    Text("STDIO", modifier = Modifier.padding(end = 16.dp))
                    RadioButton(
                        selected = transportType == McpTransportType.SSE,
                        onClick = { transportType = McpTransportType.SSE }
                    )
                    Text("HTTP")
                }

                if (transportType == McpTransportType.STDIO) {
                    Text(
                        text = stringResource(Res.string.tools_mcp_runtime_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text(stringResource(Res.string.tools_mcp_command)) },
                        placeholder = { Text(stringResource(Res.string.tools_mcp_command_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = argsText,
                        onValueChange = { argsText = it },
                        label = { Text(stringResource(Res.string.tools_mcp_args)) },
                        placeholder = { Text(stringResource(Res.string.tools_mcp_args_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = envText,
                        onValueChange = { envText = it },
                        label = { Text(stringResource(Res.string.tools_mcp_env)) },
                        placeholder = { Text(stringResource(Res.string.tools_mcp_env_hint)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(Res.string.tools_mcp_url)) },
                        placeholder = { Text(stringResource(Res.string.tools_mcp_url_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        label = { Text(stringResource(Res.string.tools_mcp_headers)) },
                        placeholder = { Text(stringResource(Res.string.tools_mcp_headers_hint)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) return@TextButton
                    val parsedArgs = argsText.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                    val parsedEnv = envText.lines().mapNotNull { line ->
                        val parts = line.split("=", limit = 2)
                        if (parts.size == 2 && parts[0].isNotBlank()) parts[0].trim() to parts[1].trim() else null
                    }.toMap()
                    val parsedHeaders = headersText.lines().mapNotNull { line ->
                        val parts = line.split(":", limit = 2)
                        if (parts.size == 2 && parts[0].isNotBlank()) parts[0].trim() to parts[1].trim() else null
                    }.toMap()

                    val config = McpServerConfig(
                        id = server?.id ?: randomUuid(),
                        name = name.trim(),
                        transportType = transportType,
                        isEnabled = server?.isEnabled ?: true,
                        command = command.trim(),
                        args = parsedArgs,
                        env = parsedEnv,
                        url = url.trim(),
                        headers = parsedHeaders
                    )
                    onSave(config)
                }
            ) {
                Text(stringResource(Res.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        }
    )
}
