@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cc.ptoe.messenger.presentation.ui.learning

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.generated.resources.*
import cc.ptoe.messenger.presentation.platform.BackHandler
import cc.ptoe.messenger.presentation.ui.components.ExpressiveRow
import cc.ptoe.messenger.presentation.ui.components.ExpressiveScaffold
import cc.ptoe.messenger.presentation.ui.components.ExpressiveTopAppBar
import cc.ptoe.messenger.presentation.viewmodel.LearningUiState
import cc.ptoe.messenger.presentation.viewmodel.LearningViewModel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*

internal fun learningDate(timestamp: Long): String = kotlin.time.Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

private val learningTabs = listOf(
    "today" to Res.string.learning_today, "course" to Res.string.learning_courses,
    "knowledgePoint" to Res.string.learning_points, "item" to Res.string.learning_items,
    "material" to Res.string.learning_materials, "plan" to Res.string.learning_plans,
    "task" to Res.string.learning_tasks, "reviewCard" to Res.string.learning_cards,
    "media" to Res.string.learning_media,
)

@Composable
fun LearningScreen(
    modifier: Modifier = Modifier,
    onOpenConversation: ((String) -> Unit)? = null,
    viewModel: LearningViewModel = viewModel(factory = LearningViewModel.factory(AppContainerHolder.instance.learningRepository)),
) {
    val state by viewModel.state.collectAsState()
    val revision by AppContainerHolder.instance.learningRevision.collectAsState()
    LaunchedEffect(revision) { if (revision > 0) viewModel.refresh() }
    val snackbar = remember { SnackbarHostState() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<LearningRecord?>(null) }
    var showActions by remember { mutableStateOf(false) }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(selectedId, state.page.items, state.busy) {
        if (!state.busy) {
            viewModel.loadRecord(selectedId)
            if (selectedId != null && state.page.items.none { it.id == selectedId }) selectedId = null
        }
    }
    BackHandler(enabled = state.reviewCard != null || selectedId != null, onBack = {
        if (state.reviewCard != null) viewModel.stopReview() else selectedId = null
    }) {}
    ExpressiveScaffold(
        modifier = modifier,
        topBar = {
            ExpressiveTopAppBar(
                title = { Text(stringResource(Res.string.learning_title)) },
                actions = {
                    Box {
                        IconButton(onClick = { showActions = true }, enabled = !state.busy) { Icon(Icons.Default.MoreVert, stringResource(Res.string.learning_actions)) }
                        DropdownMenu(showActions, { showActions = false }) {
                            DropdownMenuItem(text = { Text(stringResource(Res.string.learning_refresh)) }, onClick = { showActions = false; viewModel.refresh() })
                            DropdownMenuItem(text = { Text(stringResource(Res.string.learning_export)) }, onClick = { showActions = false; viewModel.exportData() })
                            DropdownMenuItem(text = { Text(stringResource(Res.string.learning_import)) }, onClick = { showActions = false; viewModel.transfer("") })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.tab !in listOf("today", "media", "reviewCard")) FloatingActionButton(
                onClick = { viewModel.create(state.tab) },
            ) { Icon(Icons.Default.Add, stringResource(Res.string.learning_add)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                learningTabs.forEach { (tab, label) -> FilterChip(
                    selected = state.tab == tab, enabled = !state.busy,
                    onClick = { selectedId = null; viewModel.selectTab(tab) }, label = { Text(stringResource(label)) },
                ) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            if (state.reviewCard != null) {
                ReviewPane(state, viewModel, Modifier.weight(1f))
            } else if (state.tab == "media") {
                LearningMediaPanel(modifier = Modifier.fillMaxSize())
            } else BoxWithConstraints(Modifier.weight(1f)) {
                val dualPane = maxWidth >= 600.dp
                Row(Modifier.fillMaxSize()) {
                    if (dualPane || selectedId == null) {
                        LearningList(state, viewModel,
                            onSelect = { selectedId = it.id },
                            modifier = if (dualPane) Modifier.weight(0.45f) else Modifier.fillMaxSize())
                    }
                    if (dualPane) VerticalDivider()
                    if (dualPane || selectedId != null) {
                        Column(if (dualPane) Modifier.weight(0.55f) else Modifier.fillMaxSize()) {
                            if (!dualPane) TextButton(onClick = { selectedId = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text(stringResource(Res.string.learning_back))
                            }
                            state.selected?.let { record -> LearningDetail(record, state, viewModel, onDelete = { deleteTarget = record }, onOpenConversation = onOpenConversation) }
                                ?: Text(stringResource(Res.string.learning_select), Modifier.padding(24.dp))
                        }
                    }
                }
            }
        }
    }
    state.editing?.let { LearningEditor(it, state, viewModel) }
    state.transfer?.let { TransferDialog(state, viewModel) }
    deleteTarget?.let { record -> AlertDialog(
        onDismissRequest = { deleteTarget = null },
        title = { Text(stringResource(Res.string.learning_delete)) },
        text = { Text(stringResource(Res.string.learning_delete_confirm, record.title)) },
        confirmButton = { TextButton(onClick = { viewModel.delete(record); deleteTarget = null; selectedId = null }, enabled = !state.busy) { Text(stringResource(Res.string.learning_delete)) } },
        dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(Res.string.learning_cancel)) } },
    ) }
}

@Composable
private fun LearningList(state: LearningUiState, vm: LearningViewModel, onSelect: (LearningRecord) -> Unit, modifier: Modifier = Modifier) {
    var search by rememberSaveable(state.tab) { mutableStateOf(state.search) }
    var tag by rememberSaveable(state.tab) { mutableStateOf(state.tag) }
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Text(stringResource(Res.string.learning_local_only), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
            if (state.tab == "today") {
                Text(stringResource(Res.string.learning_progress, state.overview.completedTasks, state.overview.pendingTasks, state.overview.attempts), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(Res.string.learning_due, state.overview.dueReviews), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
                Button(onClick = { state.page.items.firstOrNull()?.let(vm::startReview) }, enabled = !state.busy && state.page.items.isNotEmpty()) {
                    Text(stringResource(Res.string.learning_start_review))
                }
            } else {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(search, { search = it }, Modifier.weight(1f), label = { Text(stringResource(Res.string.learning_search)) }, singleLine = true)
                    TextButton(onClick = { vm.filter(state.courseId, search, state.showArchived, tag = tag) }, enabled = !state.busy) { Text(stringResource(Res.string.learning_apply)) }
                }
                if (state.tab != "course") LearningPicker(
                    label = stringResource(Res.string.learning_course), value = state.courseId,
                    options = state.courses.map { it.id to it.title }, enabled = !state.busy,
                    onSelect = { vm.filter(it, search, state.showArchived) },
                )
                if (state.tab in listOf("item", "material", "task", "plan")) {
                    LearningPicker(stringResource(Res.string.learning_points), state.knowledgePointId,
                        state.points.filter { state.courseId == null || it.courseId == state.courseId }.map { it.id to it.title }, enabled = !state.busy,
                        onSelect = { vm.filter(state.courseId, search, state.showArchived, knowledgePointId = it, tag = tag) })
                    OutlinedTextField(tag, { tag = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(Res.string.learning_filter_tag)) })
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(state.showArchived, { vm.filter(state.courseId, search, it) }, enabled = !state.busy)
                    Text(stringResource(Res.string.learning_archived))
                }
            }
        }
        if (state.page.items.isEmpty() && !state.busy) item { Text(stringResource(Res.string.learning_empty), Modifier.padding(vertical = 24.dp)) }
        items(state.page.items, key = { it.id }) { record ->
            ExpressiveRow(onClick = { if (state.tab == "today") vm.startReview(record) else onSelect(record) }, enabled = !state.busy,
                selected = state.selected?.id == record.id, index = state.page.items.indexOf(record), count = state.page.items.size) {
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(record.title, style = MaterialTheme.typography.titleMedium)
                    val subtitle = when (record.kind) {
                        "item" -> record.field("question")
                        "task" -> stringResource(if (record.field("status") == "completed") Res.string.learning_completed else Res.string.learning_pending)
                        "reviewCard" -> stringResource(if (record.field("paused") == "true") Res.string.learning_paused else Res.string.learning_scheduled)
                        "plan" -> record.field("goal")
                        "material" -> record.field("text").ifBlank { record.field("url") }
                        else -> record.field("description")
                    }
                    if (subtitle.isNotBlank()) Text(subtitle, maxLines = 2, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (record.tags.isNotEmpty()) Text(record.tags.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (state.page.items.size < state.page.total && state.tab != "today") item {
            TextButton(onClick = vm::more, enabled = !state.busy) { Text(stringResource(Res.string.learning_more)) }
        }
    }
}

@Composable
private fun LearningDetail(record: LearningRecord, state: LearningUiState, vm: LearningViewModel, onDelete: () -> Unit, onOpenConversation: ((String) -> Unit)?) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var openArtifact by remember(record.id) { mutableStateOf(false) }
    val exporter = cc.ptoe.messenger.presentation.platform.rememberLearningExportLauncher(onError = vm::reportError)
    if (openArtifact) ArtifactDialog(record.field("artifactId"), onDismiss = { openArtifact = false })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(record.title, style = MaterialTheme.typography.headlineMedium)
        if (record.kind == "material" && record.field("materialKind") == "image" && record.field("assetId").isNotBlank()) {
            AssetImage(record.field("assetId"), Modifier.fillMaxWidth().heightIn(max = 320.dp))
        }
        record.source?.assetIds?.forEach { AssetImage(it, Modifier.fillMaxWidth().heightIn(max = 320.dp)) }
        state.courses.find { it.id == record.courseId }?.let { Text(it.title, style = MaterialTheme.typography.labelLarge) }
        SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            detailFields(record.kind).forEach { (key, label) ->
                record.field(key).takeIf { it.isNotBlank() }?.let { value ->
                    Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
                    if (key in listOf("targetAt", "dueAt", "position", "assetId", "artifactId", "url")) Text(if (key in listOf("targetAt", "dueAt")) value.toLongOrNull()?.let(::learningDate) ?: value else value)
                    else cc.ptoe.messenger.presentation.ui.chat.ChatMarkdownText(value, isStreaming = false, modifier = Modifier.fillMaxWidth())
                }
            }
            if (record.knowledgePointIds.isNotEmpty()) Text(state.points.filter { it.id in record.knowledgePointIds }.joinToString(" · ") { it.title })
            if (record.tags.isNotEmpty()) Text(record.tags.joinToString(" · "))
            record.source?.let {
                Text(stringResource(Res.string.learning_source), style = MaterialTheme.typography.titleSmall)
                cc.ptoe.messenger.presentation.ui.chat.ChatMarkdownText(it.snapshot, isStreaming = false, modifier = Modifier.fillMaxWidth())
            }
        } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.edit(record) }, enabled = !state.busy) { Text(stringResource(Res.string.learning_edit)) }
            TextButton(onClick = { vm.archive(record) }, enabled = !state.busy) { Text(stringResource(if (record.archived) Res.string.learning_restore else Res.string.learning_archive)) }
            TextButton(onClick = onDelete, enabled = !state.busy) { Text(stringResource(Res.string.learning_delete)) }
            record.source?.conversationId?.let { id ->
                if (onOpenConversation != null) TextButton(onClick = { onOpenConversation(id) }) { Text(stringResource(Res.string.learning_open_source)) }
            }
            when (record.kind) {
                "material" -> when (record.field("materialKind")) {
                    "file" -> FilledTonalButton(onClick = { vm.exportMaterial(record) { bytes, mime -> exporter.launch(record.title, bytes, mime) } }, enabled = !state.busy) { Text(stringResource(Res.string.learning_save_file)) }
                    "link" -> FilledTonalButton(onClick = {
                        try { uriHandler.openUri(record.field("url")) }
                        catch (failure: Exception) { vm.reportError(failure.message ?: failure.toString()) }
                    }) { Text(stringResource(Res.string.learning_open_link)) }
                    "artifact" -> FilledTonalButton(onClick = { openArtifact = true }) { Text(stringResource(Res.string.learning_open_artifact)) }
                }
                "item" -> FilledTonalButton(onClick = { vm.addReview(record) }, enabled = !state.busy) { Text(stringResource(Res.string.learning_add_review)) }
                "task" -> FilledTonalButton(onClick = { vm.complete(record) }, enabled = !state.busy) { Text(stringResource(if (record.field("status") == "completed") Res.string.learning_reopen else Res.string.learning_complete)) }
                "reviewCard" -> {
                    FilledTonalButton(onClick = { vm.startReview(record) }, enabled = !state.busy) { Text(stringResource(Res.string.learning_start_review)) }
                    TextButton(onClick = { vm.pause(record) }, enabled = !state.busy) { Text(stringResource(if (record.field("paused") == "true") Res.string.learning_resume else Res.string.learning_pause)) }
                }
            }
        }
    }
}

internal fun detailFields(kind: String): List<Pair<String, StringResource>> = when (kind) {
    "course", "knowledgePoint" -> listOf("description" to Res.string.learning_description)
    "item" -> listOf("question" to Res.string.learning_question, "originalAnswer" to Res.string.learning_original_answer, "correctAnswer" to Res.string.learning_correct_answer, "explanation" to Res.string.learning_explanation, "errorReason" to Res.string.learning_error_reason)
    "material" -> listOf("text" to Res.string.learning_text, "url" to Res.string.learning_url, "assetId" to Res.string.learning_asset, "artifactId" to Res.string.learning_artifact)
    "plan" -> listOf("goal" to Res.string.learning_goal, "targetAt" to Res.string.learning_target)
    "task" -> listOf("position" to Res.string.learning_position, "dueAt" to Res.string.learning_target)
    else -> emptyList()
}

@Composable
internal fun LearningPicker(label: String, value: String?, options: List<Pair<String, String>>, enabled: Boolean = true, nullable: Boolean = true, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text("$label: ${options.find { it.first == value }?.second ?: stringResource(Res.string.learning_none)}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            if (nullable) DropdownMenuItem(text = { Text(stringResource(Res.string.learning_none)) }, onClick = { expanded = false; onSelect(null) })
            options.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(id) }) }
        }
    }
}

@Composable
private fun ReviewPane(state: LearningUiState, vm: LearningViewModel, modifier: Modifier = Modifier) {
    val item = state.reviewItem ?: return
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(item.title, style = MaterialTheme.typography.headlineMedium)
        cc.ptoe.messenger.presentation.ui.chat.ChatMarkdownText(item.field("question"), isStreaming = false, modifier = Modifier.fillMaxWidth())
        item.source?.assetIds?.forEach { AssetImage(it, Modifier.fillMaxWidth().heightIn(max = 320.dp)) }
        OutlinedTextField(state.answer, vm::answer, Modifier.fillMaxWidth(), enabled = !state.busy && !state.revealed,
            label = { Text(stringResource(Res.string.learning_your_answer)) }, minLines = 3)
        if (!state.revealed) Button(onClick = vm::reveal, enabled = !state.busy) { Text(stringResource(Res.string.learning_reveal)) }
        else {
            listOf("correctAnswer" to Res.string.learning_correct_answer, "explanation" to Res.string.learning_explanation, "errorReason" to Res.string.learning_error_reason).forEach { (key, label) ->
                Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
                cc.ptoe.messenger.presentation.ui.chat.ChatMarkdownText(item.field(key), isStreaming = false, modifier = Modifier.fillMaxWidth())
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("again" to Res.string.learning_again, "hard" to Res.string.learning_hard, "good" to Res.string.learning_good, "easy" to Res.string.learning_easy).forEach { (rating, label) ->
                    FilledTonalButton(onClick = { vm.rate(rating) }, enabled = !state.busy) { Text(stringResource(label)) }
                }
            }
        }
        state.reviewState?.let { Text(stringResource(Res.string.learning_review_interval, it.intervalDays.toInt(), it.repetitions.toInt())) }
        TextButton(onClick = vm::stopReview, enabled = !state.busy) { Text(stringResource(Res.string.learning_close)) }
        if (state.attempts.isNotEmpty()) {
            Text(stringResource(Res.string.learning_history), style = MaterialTheme.typography.titleLarge)
            state.attempts.forEach { attempt ->
                val rating = when (attempt.submission.rating) {
                    "again" -> Res.string.learning_again; "hard" -> Res.string.learning_hard; "easy" -> Res.string.learning_easy; else -> Res.string.learning_good
                }
                Text(stringResource(rating), style = MaterialTheme.typography.titleSmall)
                Text(attempt.submission.answer)
                Text(stringResource(Res.string.learning_review_interval, attempt.state.intervalDays.toInt(), attempt.state.repetitions.toInt()), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TransferDialog(state: LearningUiState, vm: LearningViewModel) {
    val scope = rememberCoroutineScope()
    val exporter = cc.ptoe.messenger.presentation.platform.rememberLearningExportLauncher(onError = vm::reportError)
    val picker = cc.ptoe.messenger.presentation.platform.rememberLearningImportLauncher(onPicked = vm::transfer, onError = vm::reportError)
    AlertDialog(onDismissRequest = vm::closeTransfer,
        title = { Text(stringResource(Res.string.learning_transfer)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.learning_transfer_help))
            TextButton(onClick = picker::launch, enabled = !state.busy) { Text(stringResource(Res.string.learning_choose_file)) }
            TextButton(onClick = { scope.launch {
                val bytes = withContext(Dispatchers.Default) { state.transfer.orEmpty().encodeToByteArray() }
                exporter.launch("messenger-learning.json", bytes)
            } }, enabled = !state.busy && !state.transfer.isNullOrBlank()) { Text(stringResource(Res.string.learning_save_file)) }
            if (!state.transfer.isNullOrBlank()) Text(stringResource(Res.string.learning_package_loaded))
            state.importPreview?.let { raw ->
                val preview = remember(raw) { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject }.getOrNull() }
                if (preview == null) Text(stringResource(Res.string.learning_import_invalid_preview), color = MaterialTheme.colorScheme.error)
                else {
                    Text(stringResource(Res.string.learning_import_counts,
                        preview["records"]?.jsonPrimitive?.intOrNull ?: 0,
                        preview["attempts"]?.jsonPrimitive?.intOrNull ?: 0,
                        preview["assetCount"]?.jsonPrimitive?.intOrNull ?: 0,
                        preview["artifactCount"]?.jsonPrimitive?.intOrNull ?: 0))
                    (preview["warnings"] as? kotlinx.serialization.json.JsonArray)?.forEach { warning -> Text(warning.jsonPrimitive.content) }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = {
            TextButton(onClick = if (state.importPreview != null) vm::importData else vm::previewImport, enabled = !state.busy && !state.transfer.isNullOrBlank()) {
                Text(stringResource(if (state.importPreview != null) Res.string.learning_confirm_import else Res.string.learning_preview_import))
            }
        },
        dismissButton = { TextButton(onClick = vm::closeTransfer, enabled = !state.busy) { Text(stringResource(Res.string.learning_close)) } },
    )
}
