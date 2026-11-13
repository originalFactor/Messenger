package cc.ptoe.messenger.presentation.ui.skills

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.domain.model.RemoteSkillCandidate
import cc.ptoe.messenger.domain.model.RemoteSkillCatalog
import cc.ptoe.messenger.generated.resources.*
import cc.ptoe.messenger.presentation.ui.components.ExpressiveRow
import cc.ptoe.messenger.presentation.ui.components.settingsItems
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RemoteSkillImportDialog(
    catalog: RemoteSkillCatalog?,
    busy: Boolean,
    error: String?,
    onSourceChanged: () -> Unit,
    onDiscover: (String, String, String) -> Unit,
    onSelect: (RemoteSkillCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    var source by rememberSaveable { mutableStateOf("") }
    var revision by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf("") }
    var options by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.skills_remote_import)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = source, onValueChange = { source = it; onSourceChanged() },
                        label = { Text(stringResource(Res.string.skills_remote_source)) },
                        supportingText = { Text(stringResource(Res.string.skills_remote_source_hint)) },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    TextButton(enabled = !busy, onClick = { options = !options }) {
                        Text(stringResource(Res.string.skills_remote_options))
                    }
                }
                if (options) {
                    item {
                        OutlinedTextField(
                            value = revision, onValueChange = { revision = it; onSourceChanged() },
                            label = { Text(stringResource(Res.string.skills_remote_ref)) },
                            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = path, onValueChange = { path = it; onSourceChanged() },
                            label = { Text(stringResource(Res.string.skills_remote_path)) },
                            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (error != null) item { Text(error, color = MaterialTheme.colorScheme.error) }
                if (busy) item { LoadingIndicator() }
                if (catalog != null) {
                    item {
                        Text(stringResource(if (catalog.items.isEmpty()) Res.string.skills_remote_empty else Res.string.skills_remote_results))
                    }
                    settingsItems(catalog.items, key = { it.path }, contentType = "remote-skill") { candidate ->
                        ExpressiveRow(onClick = { onSelect(candidate) }, enabled = !busy) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(candidate.name, style = MaterialTheme.typography.titleMedium)
                                Text(candidate.description, style = MaterialTheme.typography.bodyMedium)
                                Text(candidate.path.ifEmpty { stringResource(Res.string.skills_remote_root) }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (catalog.warnings.isNotEmpty()) {
                        item { Text(stringResource(Res.string.skills_remote_warnings), style = MaterialTheme.typography.titleSmall) }
                        settingsItems(catalog.warnings, key = { "warning:${it.path}" }, contentType = "remote-skill-warning") { warning ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(warning.path.ifEmpty { stringResource(Res.string.skills_remote_root) }, style = MaterialTheme.typography.titleSmall)
                                Text(warning.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && source.isNotBlank(), onClick = { onDiscover(source, revision, path) }) {
                Text(stringResource(Res.string.skills_remote_find))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}
