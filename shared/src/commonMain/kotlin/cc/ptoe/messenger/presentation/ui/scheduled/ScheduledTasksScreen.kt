package cc.ptoe.messenger.presentation.ui.scheduled

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.core.StoredAgentDto
import cc.ptoe.messenger.core.StoredProjectDto
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.generated.resources.*
import cc.ptoe.messenger.presentation.ui.components.*
import cc.ptoe.messenger.presentation.platform.BackgroundTaskPersistenceRow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.stringResource
import cc.ptoe.messenger.data.util.randomUuid
import kotlin.time.Instant

@Serializable
private data class ScheduledTask(
    val id: String, val name: String, val prompt: String, val agentId: String,
    val projectId: String? = null, val intervalMinutes: Long, val nextRunAt: Long,
    val enabled: Boolean = true, val lastRunAt: Long? = null, val lastError: String? = null,
    val lastConversationId: String? = null,
)

private val taskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScheduledTasksScreen(onBackClick: () -> Unit, onOpenConversation: (String) -> Unit = {}) {
    val bridge = AppContainerHolder.instance.coreBridge
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<ScheduledTask>>(emptyList()) }
    var agents by remember { mutableStateOf<List<StoredAgentDto>>(emptyList()) }
    var projects by remember { mutableStateOf<List<StoredProjectDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduledTask?>(null) }
    var deleting by remember { mutableStateOf<ScheduledTask?>(null) }
    val snackbar = remember { SnackbarHostState() }
    suspend fun refresh() {
        try {
            tasks = taskJson.decodeFromString(bridge.scheduledTaskRequest("GET", "/scheduled-tasks"))
            val choices = withContext(Dispatchers.Default) {
                taskJson.decodeFromString<List<StoredAgentDto>>(bridge.listAgentsJson()).filter { it.role != "title" } to
                    taskJson.decodeFromString<List<StoredProjectDto>>(bridge.listProjectsJson())
            }
            agents = choices.first
            projects = choices.second
            error = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
    }
    fun mutate(method: String, task: ScheduledTask) {
        scope.launch {
            busy = true
            try {
                val path = if (method == "POST") "/scheduled-tasks" else "/scheduled-tasks/${task.id}"
                bridge.scheduledTaskRequest(method, path, if (method == "DELETE") null else taskJson.encodeToString(task))
                editing = null
                deleting = null
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString(); snackbar.showSnackbar(error.orEmpty()) }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(15_000)
            if (!busy && editing == null && deleting == null) refresh()
        }
    }
    ExpressiveScaffold(topBar = {
        ExpressiveTopAppBar(title = { Text(stringResource(Res.string.scheduled_title)) }, navigationIcon = {
            FilledTonalIconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back)) }
        })
    }, snackbarHost = { SnackbarHost(snackbar) }, floatingActionButton = {
        ExtendedFloatingActionButton(onClick = {
            editing = ScheduledTask(randomUuid(), "", "", agents.firstOrNull()?.id.orEmpty(), null, 60, currentTimeMillis() + 3_600_000)
        }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(Res.string.scheduled_create)) })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(Res.string.scheduled_runtime_note), Modifier.padding(vertical = 12.dp)) }
            item { BackgroundTaskPersistenceRow { message -> scope.launch { snackbar.showSnackbar(message) } } }
            error?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { refresh() } }) { Text(stringResource(Res.string.action_retry)) }
            } }
            if (tasks.isEmpty() && error == null) item { Text(stringResource(Res.string.scheduled_empty)) }
            items(tasks, key = { it.id }) { task ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(task.name, style = MaterialTheme.typography.titleMedium)
                        Text((agents.find { it.id == task.agentId }?.name ?: task.agentId) + " · " + (projects.find { it.id == task.projectId }?.name ?: stringResource(Res.string.scheduled_no_project)))
                        Text(stringResource(Res.string.scheduled_interval_value, task.intervalMinutes))
                        Text(stringResource(Res.string.scheduled_next, Instant.fromEpochMilliseconds(task.nextRunAt).toString()))
                        task.lastRunAt?.let { Text(stringResource(Res.string.scheduled_last, Instant.fromEpochMilliseconds(it).toString())) }
                        task.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editing = task }, enabled = !busy) { Text(stringResource(Res.string.scheduled_edit)) }
                            TextButton(onClick = { mutate("PUT", task.copy(enabled = !task.enabled)) }, enabled = !busy) { Text(stringResource(if (task.enabled) Res.string.scheduled_pause else Res.string.scheduled_resume)) }
                            TextButton(onClick = { deleting = task }, enabled = !busy) { Text(stringResource(Res.string.scheduled_delete)) }
                        }
                        task.lastConversationId?.let { conversationId ->
                            TextButton(onClick = { onOpenConversation(conversationId) }) { Text(stringResource(Res.string.scheduled_open_chat)) }
                        }
                        if (!task.enabled) Text(stringResource(Res.string.scheduled_paused))
                    }
                }
            }
        }
    }
    deleting?.let { task -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(Res.string.scheduled_delete)) }, text = { Text(task.name) }, confirmButton = { TextButton(onClick = { mutate("DELETE", task) }, enabled = !busy) { Text(stringResource(Res.string.scheduled_delete)) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.scheduled_cancel)) } }) }
    editing?.let { task -> TaskEditor(task, agents, projects, busy, isNew = tasks.none { it.id == task.id }, onDismiss = { editing = null }, onSave = { updated -> mutate(if (tasks.any { it.id == updated.id }) "PUT" else "POST", updated) }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditor(task: ScheduledTask, agents: List<StoredAgentDto>, projects: List<StoredProjectDto>, busy: Boolean, isNew: Boolean, onDismiss: () -> Unit, onSave: (ScheduledTask) -> Unit) {
    var name by remember(task.id) { mutableStateOf(task.name) }
    var prompt by remember(task.id) { mutableStateOf(task.prompt) }
    var interval by remember(task.id) { mutableStateOf(task.intervalMinutes.toString()) }
    var agent by remember(task.id) { mutableStateOf(task.agentId) }
    var project by remember(task.id) { mutableStateOf(task.projectId) }
    var agentMenu by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    val minutes = interval.toLongOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(Res.string.scheduled_edit)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.scheduled_name)) }, singleLine = true)
            OutlinedTextField(prompt, { prompt = it }, label = { Text(stringResource(Res.string.scheduled_prompt)) })
            Box { OutlinedButton(onClick = { agentMenu = true }) { Text(agents.find { it.id == agent }?.name ?: stringResource(Res.string.scheduled_agent)) }
                DropdownMenu(agentMenu, { agentMenu = false }) { agents.forEach { a -> DropdownMenuItem(text = { Text(a.name) }, onClick = { agent = a.id; agentMenu = false }) } } }
            Box { OutlinedButton(onClick = { projectMenu = true }) { Text(projects.find { it.id == project }?.name ?: stringResource(Res.string.scheduled_no_project)) }
                DropdownMenu(projectMenu, { projectMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.scheduled_no_project)) }, onClick = { project = null; projectMenu = false })
                    projects.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { project = p.id; projectMenu = false }) }
                } }
            OutlinedTextField(interval, { interval = it }, label = { Text(stringResource(Res.string.scheduled_interval)) }, singleLine = true)
        }
    }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank() && prompt.isNotBlank() && agents.any { it.id == agent } && minutes != null && minutes in 1L..525600L, onClick = {
        val next = if (isNew || minutes != task.intervalMinutes) currentTimeMillis() + minutes!! * 60_000 else task.nextRunAt
        onSave(task.copy(name = name.trim(), prompt = prompt.trim(), agentId = agent, projectId = project, intervalMinutes = minutes!!, nextRunAt = next))
    }) { Text(stringResource(Res.string.scheduled_save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.scheduled_cancel)) } })
}
