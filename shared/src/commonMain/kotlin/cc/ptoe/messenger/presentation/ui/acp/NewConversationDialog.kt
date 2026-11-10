package cc.ptoe.messenger.presentation.ui.acp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.generated.resources.*
import cc.ptoe.messenger.presentation.utils.agentDisplayNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.stringResource

private data class ConversationAgentChoice(val id: String, val name: String, val description: String)

@Composable
fun NewConversationDialog(
    agents: List<Agent>,
    projectId: String? = null,
    initialAgentId: String? = null,
    onDismiss: () -> Unit,
    onMessengerAgent: (Agent) -> Unit,
    onAcpCreated: (String) -> Unit,
) {
    val core = AppContainerHolder.instance.coreBridge
    val scope = rememberCoroutineScope()
    val names = agentDisplayNames(agents)
    val messenger = agents.filter { it.role != Agent.ROLE_TITLE }
    var external by remember { mutableStateOf(emptyList<ConversationAgentChoice>()) }
    var selected by remember { mutableStateOf(initialAgentId ?: messenger.firstOrNull()?.id) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val choices = messenger.map { ConversationAgentChoice(it.id, names[it.id] ?: it.name, it.description) } + external
    LaunchedEffect(core, projectId) {
        external = emptyList()
        if (projectId == null) return@LaunchedEffect
        try {
            core.controlSettings() // Seed the editable templates once before listing them.
            external = requestAcp(core, "catalog").objects("agents").filter { it.text("kind") == "acp" }
                .map { ConversationAgentChoice(it.text("id"), it.text("name"), it.text("description")) }
            if (selected == null) selected = external.firstOrNull()?.id
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { error = failure.message.orEmpty() }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(Res.string.conversations_select_agent_title)) },
        text = { Column {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(choices, key = { it.id }) { choice ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = choice.id }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == choice.id, onClick = { selected = choice.id }, enabled = !busy)
                        Column(Modifier.weight(1f)) {
                            Text(choice.name, style = MaterialTheme.typography.titleSmall)
                            if (choice.id.startsWith("acp:")) Text(stringResource(Res.string.acp_agent_suffix), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            if (choice.description.isNotBlank()) Text(choice.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        } }, confirmButton = {
            TextButton(enabled = !busy && selected != null, onClick = {
                val id = selected ?: return@TextButton
                if (!id.startsWith("acp:")) messenger.firstOrNull { it.id == id }?.let(onMessengerAgent)
                else {
                    busy = true
                    scope.launch {
                        try {
                            val created = requestAcp(core, "create") { put("agentId", id); projectId?.let { put("projectId", it) } }
                            onDismiss()
                            onAcpCreated(created.text("sessionId"))
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (failure: Exception) { error = failure.message.orEmpty()
                        } finally { busy = false }
                    }
                }
            }) { Text(stringResource(Res.string.action_confirm)) }
        }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } })
}
