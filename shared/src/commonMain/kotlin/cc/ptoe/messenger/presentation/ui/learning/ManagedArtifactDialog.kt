/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.ui.learning

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.domain.artifact.ArtifactUiHost
import cc.ptoe.messenger.generated.resources.*
import cc.ptoe.messenger.presentation.platform.ManagedArtifactPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.compose.resources.stringResource

/** Optional Room-hosted preview. The existing Core dialog remains the default app path. */
@Composable
internal fun ManagedArtifactDialog(id: String, host: ArtifactUiHost, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val signal by AppContainerHolder.instance.learningRevision.collectAsState()
    var artifact by remember(id) { mutableStateOf<JsonObject?>(null) }
    var selected by remember(id) { mutableIntStateOf(-1) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var edit by remember(id) { mutableStateOf(false) }
    var save by remember(id) { mutableStateOf(false) }
    var showNetwork by remember(id) { mutableStateOf(false) }
    var confirmRun by remember(id) { mutableStateOf(false) }
    var busy by remember(id) { mutableStateOf(false) }
    var grants by remember(id) { mutableStateOf<Set<String>>(emptySet()) }
    var refresh by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(host, id) {
        try { host.observeChanges(id) { refresh++ } }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message }
    }
    LaunchedEffect(id, signal, refresh) {
        try {
            artifact = api("GET", "/artifacts/$id").obj()
            grants = artifact!!.list("approvedDomains").map { it.jsonPrimitive.content }.toSet()
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message }
    }
    val revisions = artifact?.list("revisions").orEmpty()
    val index = if (selected < 0) revisions.lastIndex else selected.coerceAtMost(revisions.lastIndex)
    val current = revisions.getOrNull(index)?.obj()
    val revision = current?.get("revision")?.jsonPrimitive?.longOrNull
    val latest = index >= 0 && index == revisions.lastIndex
    val stateVersion = artifact?.get("stateVersion")?.jsonPrimitive?.longOrNull
    var authorized by remember(id, revision, latest) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(id, revision, latest, refresh) {
        authorized = null
        if (latest && revision != null) {
            try { authorized = host.isRevisionAuthorized(id, revision) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(artifact?.text("title") ?: stringResource(Res.string.media_preview),
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.media_close)) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = index > 0, onClick = { selected = index - 1 }) { Text(stringResource(Res.string.media_previous)) }
                    Text(stringResource(Res.string.media_version, index + 1), modifier = Modifier.padding(top = 12.dp))
                    TextButton(enabled = index >= 0 && index < revisions.lastIndex,
                        onClick = { selected = if (index + 1 == revisions.lastIndex) -1 else index + 1 }) { Text(stringResource(Res.string.media_next)) }
                    TextButton(onClick = { showNetwork = !showNetwork }) { Text(stringResource(Res.string.media_network)) }
                    TextButton(enabled = artifact != null, onClick = { edit = true }) { Text(stringResource(Res.string.media_edit)) }
                    TextButton(enabled = artifact != null, onClick = { save = true }) { Text(stringResource(Res.string.learning_save)) }
                    TextButton(enabled = latest && authorized == true && revision != null && stateVersion != null && !busy, onClick = {
                        scope.launch {
                            busy = true
                            try { host.resetState(id, checkNotNull(revision), checkNotNull(stateVersion)); refresh++ }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) { error = failure.message }
                            finally { busy = false }
                        }
                    }) { Text(stringResource(Res.string.media_reset)) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (showNetwork) {
                    Text(stringResource(Res.string.media_network_description), style = MaterialTheme.typography.bodySmall)
                    val requested = artifact?.list("requestedDomains").orEmpty().map { it.jsonPrimitive.content }
                    if (requested.isEmpty()) Text(stringResource(Res.string.media_no_network))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        requested.forEach { domain -> FilterChip(selected = domain in grants,
                            onClick = { grants = if (domain in grants) grants - domain else grants + domain }, label = { Text(domain) }) }
                    }
                    TextButton(enabled = latest && revision != null && !busy, onClick = {
                        val chosen = grants.toList()
                        scope.launch {
                            busy = true
                            try { host.grantDomains(id, checkNotNull(revision), chosen); refresh++; showNetwork = false }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) { error = failure.message }
                            finally { busy = false }
                        }
                    }) { Text(stringResource(Res.string.media_apply_network)) }
                }
                when {
                    !latest && current != null -> Text(stringResource(Res.string.media_previous_preview_unavailable))
                    error != null && (revision == null || authorized == null) -> Unit
                    revision == null || authorized == null -> CircularProgressIndicator()
                    authorized == false -> Button(enabled = !busy, onClick = { confirmRun = true }) {
                        Text(stringResource(Res.string.media_run_authorize))
                    }
                    else -> ManagedArtifactPreview(host, id, revision, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
    if (confirmRun && revision != null) {
        AlertDialog(onDismissRequest = { if (!busy) confirmRun = false },
            title = { Text(stringResource(Res.string.media_run_authorize_title)) },
            text = { Text(stringResource(Res.string.media_run_authorize_message)) },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmRun = false }) { Text(stringResource(Res.string.media_cancel)) } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try { host.authorizeRevision(id, revision); authorized = true; confirmRun = false }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { error = failure.message }
                    finally { busy = false }
                }
            }) { Text(stringResource(Res.string.media_run_authorize)) } })
    }
    if (save && artifact != null) SaveMediaDialog(title = artifact!!.text("title"), artifactId = id, onDismiss = { save = false })
    if (edit && artifact != null) ArtifactEditor(existing = artifact, onDismiss = { edit = false }, onSaved = { edit = false; selected = -1; refresh++ })
}

/** Inline content never grants authorization; users must explicitly open and approve it. */
@Composable
internal fun ManagedArtifactInlinePreview(id: String, host: ArtifactUiHost, modifier: Modifier = Modifier) {
    val signal by AppContainerHolder.instance.learningRevision.collectAsState()
    var revision by remember(id) { mutableStateOf<Long?>(null) }
    var authorized by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var refresh by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(host, id) {
        try { host.observeChanges(id) { refresh++ } }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message }
    }
    LaunchedEffect(id, signal, refresh) {
        authorized = false
        error = null
        try {
            val artifact = api("GET", "/artifacts/$id").obj()
            val latest = artifact.list("revisions").lastOrNull()?.obj()?.get("revision")?.jsonPrimitive?.longOrNull
                ?: error("Application has no current version")
            revision = latest
            authorized = host.isRevisionAuthorized(id, latest)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message }
    }
    when {
        error != null -> Text(error.orEmpty(), modifier = modifier, color = MaterialTheme.colorScheme.error)
        revision == null -> CircularProgressIndicator(modifier = modifier)
        !authorized -> Text(stringResource(Res.string.media_open_to_authorize), modifier = modifier)
        else -> ManagedArtifactPreview(host, id, checkNotNull(revision), modifier)
    }
}
