/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.ui.acp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.core.AcpReplaySnapshot
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.repository.exportAcpReplay
import cc.ptoe.messenger.data.util.ioDispatcher
import cc.ptoe.messenger.generated.resources.*
import cc.ptoe.messenger.presentation.platform.BackHandler
import cc.ptoe.messenger.presentation.platform.rememberLearningExportLauncher
import cc.ptoe.messenger.presentation.utils.DateTimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AcpReplaySnapshotsScreen(core: CoreBridge, sessionId: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var snapshots by remember(sessionId) { mutableStateOf<List<AcpReplaySnapshot>>(emptyList()) }
    var selected by remember(sessionId) { mutableStateOf<AcpReplaySnapshot?>(null) }
    var events by remember(sessionId) { mutableStateOf<List<JsonElement>>(emptyList()) }
    var next by remember(sessionId) { mutableStateOf<Int?>(null) }
    var busy by remember(sessionId) { mutableStateOf(false) }
    var confirmDelete by remember(sessionId) { mutableStateOf(false) }
    var error by remember(sessionId) { mutableStateOf<String?>(null) }
    val saved = stringResource(Res.string.acp_replay_exported)
    val title = stringResource(Res.string.acp_replay_title)
    val exporter = rememberLearningExportLauncher(onError = { error = it },
        onSaved = { scope.launch { snackbar.showSnackbar(saved) } }, pickerTitle = title)

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: failure.toString() }
            finally { busy = false }
        }
    }
    fun open(snapshot: AcpReplaySnapshot) = run {
        val page = core.readAcpReplaySnapshot(sessionId, snapshot.id, 0)
        check(page.next == null || page.next == page.events.size) { "Invalid ACP replay cursor" }
        selected = snapshot; events = page.events; next = page.next
    }
    fun back() {
        if (busy) return
        if (selected != null) { selected = null; events = emptyList(); next = null } else onDismiss()
    }
    LaunchedEffect(sessionId) {
        busy = true
        try { snapshots = core.listAcpReplaySnapshots(sessionId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: failure.toString() }
        finally { busy = false }
    }
    BackHandler(enabled = true, onBack = ::back) {}
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = ::back, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back)) }
        }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(Res.string.acp_replay_explanation), style = MaterialTheme.typography.bodyMedium) }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error)
                if (selected == null) TextButton(enabled = !busy, onClick = { run { snapshots = core.listAcpReplaySnapshots(sessionId) } }) {
                    Text(stringResource(Res.string.action_retry))
                }
            } }
            val current = selected
            if (current == null) {
                if (!busy && error == null && snapshots.isEmpty()) item { Text(stringResource(Res.string.acp_replay_empty)) }
                itemsIndexed(snapshots, key = { _, value -> value.id }) { _, snapshot ->
                    ElevatedCard(onClick = { open(snapshot) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            val day = Instant.fromEpochMilliseconds(snapshot.capturedAt).toLocalDateTime(TimeZone.currentSystemDefault()).date
                            Text("$day ${DateTimeUtils.formatMessageTime(snapshot.capturedAt)}", style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(Res.string.acp_replay_count, snapshot.eventCount, snapshot.byteCount), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = { run {
                            val bytes = withContext(ioDispatcher) { exportAcpReplay(core, sessionId, current) }
                            exporter.launch("messenger-acp-replay-${current.id.take(12)}.json", bytes)
                        } }) { Text(stringResource(Res.string.acp_replay_export)) }
                        TextButton(enabled = !busy && core.supportsAcpReplayDeletion, onClick = { confirmDelete = true }) {
                            Text(stringResource(Res.string.acp_replay_delete))
                        }
                    }
                    if (!core.supportsAcpReplayDeletion) Text(stringResource(Res.string.acp_replay_delete_unavailable), style = MaterialTheme.typography.bodySmall)
                }
                itemsIndexed(events) { index, event -> ReplayEvent(index, event) }
                next?.let { cursor -> item {
                    TextButton(enabled = !busy, onClick = { run {
                        val page = core.readAcpReplaySnapshot(sessionId, current.id, cursor)
                        check(page.events.isNotEmpty() && (page.next == null || page.next == cursor + page.events.size)) { "Invalid ACP replay cursor" }
                        events = events + page.events; next = page.next
                    } }) { Text(stringResource(Res.string.acp_replay_load_more)) }
                } }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(Res.string.acp_replay_delete)) },
        text = { Text(stringResource(Res.string.acp_replay_delete_confirm)) },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            selected?.let { snapshot -> run {
                core.deleteAcpReplaySnapshot(sessionId, snapshot.id)
                snapshots = core.listAcpReplaySnapshots(sessionId)
                selected = null; events = emptyList(); next = null
            } }
        }) { Text(stringResource(Res.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
private fun ReplayEvent(index: Int, value: JsonElement) {
    var expanded by remember(value, index) { mutableStateOf(false) }
    val event = value as? JsonObject
    val kind = (event?.get("sessionUpdate") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    val content = event?.get("content") as? JsonObject
    val text = (content?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text(kind ?: stringResource(Res.string.acp_replay_update), style = MaterialTheme.typography.titleSmall)
        if (!text.isNullOrEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) Res.string.acp_replay_hide_details else Res.string.acp_replay_show_details))
        }
        if (expanded) SelectionContainer { Text(value.toString(), style = MaterialTheme.typography.bodySmall) }
    } }
}
