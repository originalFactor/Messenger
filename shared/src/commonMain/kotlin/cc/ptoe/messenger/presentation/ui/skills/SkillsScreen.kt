package cc.ptoe.messenger.presentation.ui.skills

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.domain.model.SkillPackage
import cc.ptoe.messenger.generated.resources.*
import cc.ptoe.messenger.presentation.platform.BackHandler
import cc.ptoe.messenger.presentation.platform.rememberSkillImportPicker
import cc.ptoe.messenger.presentation.ui.components.*
import cc.ptoe.messenger.presentation.viewmodel.SkillsViewModel
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SkillsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SkillsViewModel = viewModel(
        factory = SkillsViewModel.provideFactory(AppContainerHolder.instance.coreBridge)
    ),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var deleting by remember { mutableStateOf<SkillPackage?>(null) }
    var remoteImport by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.editing) { if (state.editing != null) remoteImport = false }
    val picker = rememberSkillImportPicker(onPicked = viewModel::importFiles, onError = viewModel::reportError)
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it) } }

    val editing = state.editing
    if (editing != null) {
        SkillEditor(
            skill = editing,
            files = state.draftFiles,
            onFilesChange = viewModel::updateDraftFiles,
            imported = state.imported,
            busy = state.busy,
            error = state.error,
            installedNames = state.skills.map { it.name }.toSet(),
            snackbar = snackbar,
            onBackClick = viewModel::closeEditor,
            onSave = { name, files, enabled -> viewModel.save(name, files, enabled) },
            modifier = modifier,
        )
    } else {
        ExpressiveScaffold(
            modifier = modifier,
            topBar = {
                ExpressiveTopAppBar(title = { Text(stringResource(Res.string.skills_title)) }, navigationIcon = {
                    FilledTonalIconButton(onClick = onBackClick, enabled = !state.busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back))
                    }
                })
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (!state.loading && !state.busy) ExtendedFloatingActionButton(
                    onClick = { if (!state.busy) viewModel.create() },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(Res.string.skills_create)) },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { Text(stringResource(Res.string.skills_usage_note), Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium) }
                    item {
                        SettingsGroup {
                            item {
                                ExpressiveRow(onClick = { picker.launch() }, enabled = !state.busy && !state.loading) {
                                    Icon(Icons.Default.Add, null, Modifier.padding(end = 16.dp))
                                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                        Text(stringResource(Res.string.skills_import), style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(Res.string.skills_import_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            item {
                                ExpressiveRow(onClick = {
                                    viewModel.clearRemoteCatalog()
                                    remoteImport = true
                                }, enabled = !state.busy && !state.loading) {
                                    Icon(Icons.Default.Add, null, Modifier.padding(end = 16.dp))
                                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                        Text(stringResource(Res.string.skills_remote_import), style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(Res.string.skills_remote_import_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    if (state.error != null) item {
                        Column {
                            Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = viewModel::refresh, enabled = !state.loading && !state.busy) {
                                Text(stringResource(Res.string.action_retry))
                            }
                        }
                    }
                    if (!state.loading && state.skills.isEmpty() && state.error == null) item {
                        Text(stringResource(Res.string.skills_empty), Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                    settingsItems(state.skills, key = { it.name }, contentType = "skill") { skill ->
                        SettingsGroup {
                            item {
                                ExpressiveRow(onClick = { viewModel.open(skill.name) }, enabled = !state.busy) {
                                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                        Text(skill.name, style = MaterialTheme.typography.titleMedium)
                                        Text(skill.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { viewModel.open(skill.name) }, enabled = !state.busy) {
                                        Icon(Icons.Default.Edit, stringResource(Res.string.action_edit))
                                    }
                                    IconButton(onClick = { deleting = skill }, enabled = !state.busy) {
                                        Icon(Icons.Default.Delete, stringResource(Res.string.action_delete))
                                    }
                                }
                            }
                            item {
                                ExpressiveSwitchRow(
                                    title = stringResource(Res.string.skills_enabled), checked = skill.enabled,
                                    onCheckedChange = { viewModel.setEnabled(skill, it) }, enabled = !state.busy,
                                )
                            }
                        }
                    }
                }
                if (state.loading || state.busy) LoadingIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
    if (remoteImport && editing == null) RemoteSkillImportDialog(
        catalog = state.remoteCatalog, busy = state.busy, error = state.error,
        onSourceChanged = viewModel::clearRemoteCatalog,
        onDiscover = viewModel::discoverRemote,
        onSelect = viewModel::previewRemote,
        onDismiss = { remoteImport = false; viewModel.clearRemoteCatalog() },
    )
    deleting?.let { skill ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) deleting = null },
            title = { Text(stringResource(Res.string.skills_delete_title)) },
            text = { Text(stringResource(Res.string.skills_delete_message, skill.name)) },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = { deleting = null; viewModel.delete(skill.name) }) {
                    Text(stringResource(Res.string.action_delete))
                }
            },
            dismissButton = { TextButton(enabled = !state.busy, onClick = { deleting = null }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SkillEditor(
    skill: SkillPackage,
    files: Map<String, String>,
    onFilesChange: (Map<String, String>) -> Unit,
    imported: Boolean,
    busy: Boolean,
    error: String?,
    installedNames: Set<String>,
    snackbar: SnackbarHostState,
    onBackClick: () -> Unit,
    onSave: (String, Map<String, String>, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable(skill.name) { mutableStateOf(skill.name.ifBlank { "example-skill" }) }
    val initialFiles = skill.files
    // Resource contents live in the ViewModel, surviving recreation without filling a saved-state Bundle.
    var enabled by rememberSaveable(skill.name) { mutableStateOf(skill.enabled) }
    var selectedPath by rememberSaveable(skill.name) { mutableStateOf("SKILL.md") }
    var newPath by rememberSaveable(skill.name) { mutableStateOf("") }
    var replacing by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    val dirty = files != initialFiles || name != skill.name.ifBlank { "example-skill" } || enabled != skill.enabled || newPath.isNotBlank()
    val requestBack: () -> Unit = { if (dirty) discarding = true else onBackClick() }
    val path = newPath.trim()
    val validPath = path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') &&
        path.split('/').none { it.isBlank() || it == "." || it == ".." } && path !in files && path !in skill.binaryFiles
    val conflict = (imported || skill.name.isBlank()) && name.trim() in installedNames

    BackHandler(onBack = { if (!busy) requestBack() }) {
        ExpressiveScaffold(
            modifier = modifier,
            topBar = {
                ExpressiveTopAppBar(title = { Text(stringResource(if (skill.name.isBlank()) Res.string.skills_create else Res.string.skills_edit)) }, navigationIcon = {
                    FilledTonalIconButton(onClick = requestBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back))
                    }
                }, actions = {
                    TextButton(enabled = !busy && name.isNotBlank() && "SKILL.md" in files, onClick = {
                        if (conflict) replacing = true else onSave(name.trim(), files, enabled)
                    }) { Text(stringResource(Res.string.action_save)) }
                })
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    Modifier.widthIn(max = 840.dp).fillMaxWidth().imePadding(),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item {
                        OutlinedTextField(
                            value = name, onValueChange = { next ->
                                onFilesChange(files + ("SKILL.md" to updateFrontmatterName(files["SKILL.md"].orEmpty(), next)))
                                name = next
                            },
                            readOnly = skill.name.isNotBlank() && !imported, enabled = !busy,
                            label = { Text(stringResource(Res.string.skills_name)) },
                            supportingText = { Text(stringResource(Res.string.skills_name_hint)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        SettingsGroup {
                            item { ExpressiveSwitchRow(title = stringResource(Res.string.skills_enabled), checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy) }
                        }
                    }
                    if (conflict) item { Text(stringResource(Res.string.skills_replace_warning), style = MaterialTheme.typography.bodyMedium) }
                    item { Text(stringResource(Res.string.skills_editor_note), style = MaterialTheme.typography.bodyMedium) }
                    if (error != null) item { Text(error, color = MaterialTheme.colorScheme.error) }
                    if (skill.license != null || skill.compatibility != null || skill.allowedTools != null || skill.metadata.isNotEmpty()) item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            skill.license?.let { Text(stringResource(Res.string.skills_license, it), style = MaterialTheme.typography.bodySmall) }
                            skill.compatibility?.let { Text(stringResource(Res.string.skills_compatibility, it), style = MaterialTheme.typography.bodySmall) }
                            skill.allowedTools?.let { Text(stringResource(Res.string.skills_allowed_tools, it), style = MaterialTheme.typography.bodySmall) }
                            skill.metadata.forEach { (key, value) -> Text("$key: $value", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    item { SectionHeader(title = stringResource(Res.string.skills_files)) }
                    settingsItems(files.keys.sortedWith(compareBy<String> { it != "SKILL.md" }.thenBy { it }), key = { it }, contentType = "skill-file") { filePath ->
                        ExpressiveRow(onClick = { selectedPath = filePath }, selected = selectedPath == filePath, enabled = !busy) {
                            Text(filePath, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            if (filePath != "SKILL.md") IconButton(enabled = !busy, onClick = {
                                onFilesChange(files - filePath)
                                if (selectedPath == filePath) selectedPath = "SKILL.md"
                            }) { Icon(Icons.Default.Delete, stringResource(Res.string.skills_remove_file, filePath)) }
                        }
                    }
                    if (skill.binaryFiles.isNotEmpty()) {
                        item { SectionHeader(title = stringResource(Res.string.skills_binary_files)) }
                        settingsItems(skill.binaryFiles.keys.sorted(), key = { "binary:$it" }, contentType = "skill-binary-file") { filePath ->
                            ExpressiveRow(onClick = {}, enabled = false) {
                                Text(filePath, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        item { Text(stringResource(Res.string.skills_binary_files_note), style = MaterialTheme.typography.bodySmall) }
                    }
                    item {
                        OutlinedTextField(
                            newPath, { newPath = it }, label = { Text(stringResource(Res.string.skills_file_path)) },
                            supportingText = { Text(stringResource(Res.string.skills_file_path_hint)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                        )
                        TextButton(enabled = !busy && validPath, onClick = {
                            onFilesChange(files + (path to ""))
                            selectedPath = path
                            newPath = ""
                        }) { Text(stringResource(Res.string.skills_add_file)) }
                    }
                    item {
                        OutlinedTextField(
                            value = files[selectedPath].orEmpty(),
                            onValueChange = { onFilesChange(files + (selectedPath to it)) },
                            // Large resource text must not enter Android's saved text-field state.
                            label = { Text(selectedPath) }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
                if (busy) LoadingIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
    if (discarding) AlertDialog(
        onDismissRequest = { discarding = false },
        title = { Text(stringResource(Res.string.skills_discard_title)) },
        text = { Text(stringResource(Res.string.skills_discard_message)) },
        confirmButton = { TextButton(enabled = !busy, onClick = { discarding = false; onBackClick() }) { Text(stringResource(Res.string.skills_discard)) } },
        dismissButton = { TextButton(onClick = { discarding = false }) { Text(stringResource(Res.string.action_cancel)) } },
    )
    if (replacing) AlertDialog(
        onDismissRequest = { if (!busy) replacing = false },
        title = { Text(stringResource(Res.string.skills_replace_title)) },
        text = { Text(stringResource(Res.string.skills_replace_message, name.trim())) },
        confirmButton = { TextButton(enabled = !busy, onClick = { replacing = false; onSave(name.trim(), files, enabled) }) { Text(stringResource(Res.string.action_save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { replacing = false }) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/** Update only YAML frontmatter; leave instructions and bundled resources untouched. */
private fun updateFrontmatterName(markdown: String, name: String): String {
    if (!markdown.startsWith("---\n") && !markdown.startsWith("---\r\n")) return markdown
    val end = Regex("(?m)^---\\r?$").find(markdown, startIndex = 4)?.range?.first ?: return markdown
    val frontmatter = markdown.substring(0, end)
    return frontmatter.replace(Regex("(?m)^name:[^\\r\\n]*")) { "name: $name" } + markdown.substring(end)
}
