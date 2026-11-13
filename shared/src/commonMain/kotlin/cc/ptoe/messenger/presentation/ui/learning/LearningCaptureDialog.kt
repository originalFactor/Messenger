package cc.ptoe.messenger.presentation.ui.learning

import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.generated.resources.*
import cc.ptoe.messenger.presentation.viewmodel.LearningViewModel
import org.jetbrains.compose.resources.stringResource

/** Copies image bytes to the independent asset store before presenting an editable source draft. */
@Composable
fun LearningCaptureDialog(messages: List<Message>, onDismiss: () -> Unit, onError: (String) -> Unit, initialMessageId: String = messages.last().id) {
    val vm: LearningViewModel = viewModel(key = "learning-capture-$initialMessageId", factory = LearningViewModel.factory(AppContainerHolder.instance.learningRepository))
    val state by vm.state.collectAsState()
    var started by remember { mutableStateOf(false) }
    var draftShown by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf(initialMessageId)) }
    LaunchedEffect(state.busy, state.editing) {
        if (state.editing != null) draftShown = true
        if (draftShown && state.editing == null && !state.busy) onDismiss()
    }
    LaunchedEffect(state.error) { state.error?.let(onError) }
    state.editing?.let { LearningEditor(it, state, vm) } ?: AlertDialog(
        onDismissRequest = { if (!state.busy) vm.discardCapture(onDismiss) },
        title = { Text(stringResource(Res.string.learning_save_source)) },
        text = {
            if (!started) LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(messages, key = { it.id }) { message ->
                    Row {
                        Checkbox(message.id in selected, { selected = if (it) selected + message.id else selected - message.id })
                        Text(message.content.ifBlank { stringResource(Res.string.learning_image) }, maxLines = 4)
                    }
                }
            } else state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                ?: cc.ptoe.messenger.presentation.ui.components.LoadingIndicator()
        },
        confirmButton = { TextButton(onClick = { started = true; vm.capture(messages.filter { it.id in selected }) }, enabled = !state.busy && selected.isNotEmpty()) { Text(stringResource(Res.string.learning_save_source)) } },
        dismissButton = { TextButton(onClick = { vm.discardCapture(onDismiss) }, enabled = !state.busy) { Text(stringResource(Res.string.learning_cancel)) } },
    )
}
