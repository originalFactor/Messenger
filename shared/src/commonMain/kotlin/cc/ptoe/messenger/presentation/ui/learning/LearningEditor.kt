@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package cc.ptoe.messenger.presentation.ui.learning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.generated.resources.*
import cc.ptoe.messenger.presentation.viewmodel.LearningUiState
import cc.ptoe.messenger.presentation.viewmodel.LearningViewModel
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LearningEditor(record: LearningRecord, state: LearningUiState, vm: LearningViewModel) {
    var tagsText by remember(record.id) { mutableStateOf(record.tags.joinToString(", ")) }
    var dateField by remember { mutableStateOf<String?>(null) }
    val filePicker = cc.ptoe.messenger.presentation.platform.rememberAcpFilePicker(
        onPicked = { vm.importMaterial(it.bytes, it.mimeType, it.name) }, onError = vm::reportError,
    )
    val imagePicker = cc.ptoe.messenger.presentation.platform.rememberImagePicker { vm.importMaterial(it.bytes, it.mimeType, record.title) }
    val numeric = setOf("position", "targetAt", "dueAt")
    val invalidNumber = numeric.any { key -> record.field(key).let { it.isNotBlank() && it.toLongOrNull() == null } }
    ModalBottomSheet(onDismissRequest = vm::closeEditor) {
        Column(Modifier.fillMaxWidth().heightIn(max = 780.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (record.version == 0L) Res.string.learning_add else Res.string.learning_edit), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(record.title, { vm.draft(record.copy(title = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.learning_name)) }, enabled = !state.busy)
            if (record.kind != "course") LearningPicker(
                stringResource(Res.string.learning_course), record.courseId, state.courses.filter { !it.archived }.map { it.id to it.title }, enabled = !state.busy,
                onSelect = { vm.draft(record.copy(courseId = it, knowledgePointIds = emptyList(), content = JsonObject(record.content - "parentId"))) },
            )
            if (record.kind == "knowledgePoint") LearningPicker(stringResource(Res.string.learning_parent), record.field("parentId"),
                state.points.filter { it.courseId == record.courseId && it.id != record.id }.map { it.id to it.title }, enabled = !state.busy,
                onSelect = { vm.draft(record.copy(content = JsonObject(if (it == null) record.content - "parentId" else record.content + ("parentId" to JsonPrimitive(it))))) })
            if (record.kind in listOf("item", "material", "plan", "task")) {
                val points = state.points.filter { it.courseId == record.courseId && !it.archived }
                if (points.isNotEmpty()) {
                    Text(stringResource(Res.string.learning_points), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        points.forEach { point -> FilterChip(point.id in record.knowledgePointIds,
                            { vm.draft(record.copy(knowledgePointIds = if (point.id in record.knowledgePointIds) record.knowledgePointIds - point.id else record.knowledgePointIds + point.id)) },
                            label = { Text(point.title) }, enabled = !state.busy) }
                    }
                }
                OutlinedTextField(tagsText, { tagsText = it; vm.draft(record.copy(tags = it.split(',').map(String::trim).filter(String::isNotBlank).distinct())) },
                    Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.learning_tags)) }, enabled = !state.busy)
            }
            if (record.kind == "item") LearningPicker(stringResource(Res.string.learning_type), record.field("itemKind"),
                listOf("mistake" to stringResource(Res.string.learning_mistake), "note" to stringResource(Res.string.learning_note), "exercise" to stringResource(Res.string.learning_exercise)),
                nullable = false, enabled = !state.busy, onSelect = { vm.draft(record.withField("itemKind", it!!)) })
            if (record.kind == "material") LearningPicker(stringResource(Res.string.learning_type), record.field("materialKind"),
                listOf("text" to stringResource(Res.string.learning_text), "link" to stringResource(Res.string.learning_link), "image" to stringResource(Res.string.learning_image), "file" to stringResource(Res.string.learning_file), "artifact" to stringResource(Res.string.learning_artifact)),
                nullable = false, enabled = !state.busy, onSelect = { vm.draft(record.withField("materialKind", it!!)) })
            if (record.kind == "task") {
                LearningPicker(stringResource(Res.string.learning_plan), record.field("planId"), state.plans.filter { it.courseId == record.courseId }.map { it.id to it.title }, nullable = false, enabled = !state.busy,
                    onSelect = { vm.draft(record.withField("planId", it!!)) })
                LearningPicker(stringResource(Res.string.learning_status), record.field("status"), listOf(
                    "pending" to stringResource(Res.string.learning_pending), "inProgress" to stringResource(Res.string.learning_in_progress), "completed" to stringResource(Res.string.learning_completed)),
                    nullable = false, enabled = !state.busy, onSelect = { vm.draft(record.withField("status", it!!)) })
            }
            if (record.kind == "material" && record.field("materialKind") in listOf("image", "file")) {
                OutlinedButton(onClick = { if (record.field("materialKind") == "image") imagePicker.launch() else filePicker.launch() }, enabled = !state.busy) {
                    Text(stringResource(Res.string.learning_choose_file))
                }
                if (record.field("assetId").isNotBlank()) Text(stringResource(Res.string.learning_offline_available))
            }
            if (record.kind == "material" && record.field("materialKind") == "artifact") {
                LearningPicker(stringResource(Res.string.learning_artifact), record.field("artifactId"), state.artifacts.map { it.id to it.title }, nullable = false, enabled = !state.busy,
                    onSelect = { vm.draft(record.withField("artifactId", it!!)) })
            }
            detailFields(record.kind).filter { (key, _) ->
                if (record.kind != "material") true else when (key) {
                    "url" -> record.field("materialKind") == "link"
                    "assetId" -> false
                    "artifactId" -> false
                    else -> true
                }
            }.forEach { (key, label) ->
                if (key in listOf("targetAt", "dueAt")) {
                    Row {
                        OutlinedButton(onClick = { dateField = key }, enabled = !state.busy) { Text(stringResource(label) + record.field(key).toLongOrNull()?.let { ": ${learningDate(it)}" }.orEmpty()) }
                        TextButton(onClick = { vm.draft(record.copy(content = JsonObject(record.content - key))) }, enabled = !state.busy) { Text(stringResource(Res.string.learning_clear)) }
                    }
                } else OutlinedTextField(record.field(key), { value ->
                    val element = if (key in numeric) value.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value) else JsonPrimitive(value)
                    vm.draft(record.copy(content = JsonObject(if (key in numeric && value.isBlank()) record.content - key else record.content + (key to element))))
                }, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) }, enabled = !state.busy,
                    isError = key in numeric && record.field(key).let { it.isNotBlank() && it.toLongOrNull() == null },
                    minLines = if (key in listOf("question", "explanation", "text", "description", "goal")) 3 else 1)
            }
            record.source?.assetIds?.forEach { AssetImage(it, Modifier.fillMaxWidth().heightIn(max = 240.dp)) }
            record.source?.let { source ->
                OutlinedTextField(source.snapshot, { vm.draft(record.copy(source = source.copy(snapshot = it))) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.learning_source)) }, enabled = !state.busy, minLines = 3)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.save(record) }, enabled = !state.busy && record.title.isNotBlank() && !invalidNumber && (record.kind != "knowledgePoint" || record.courseId != null) && (record.kind != "task" || record.field("planId").isNotBlank())) {
                    Text(stringResource(Res.string.learning_save))
                }
                TextButton(onClick = vm::closeEditor, enabled = !state.busy) { Text(stringResource(Res.string.learning_cancel)) }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime))
        }
    }
    dateField?.let { key ->
        val date = rememberDatePickerState(initialSelectedDateMillis = record.field(key).toLongOrNull())
        DatePickerDialog(onDismissRequest = { dateField = null }, confirmButton = {
            TextButton(onClick = {
                date.selectedDateMillis?.let { vm.draft(record.copy(content = JsonObject(record.content + (key to JsonPrimitive(it))))) }
                dateField = null
            }) { Text(stringResource(Res.string.learning_save)) }
        }, dismissButton = { TextButton(onClick = { dateField = null }) { Text(stringResource(Res.string.learning_cancel)) } }) { DatePicker(date) }
    }
}
