package cc.ptoe.messenger.presentation.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.data.util.ioDispatcher
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.generated.resources.*
import cc.ptoe.messenger.presentation.ui.acp.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AcpConversationScreen(sessionId: String, onBackClick: () -> Unit, showBackButton: Boolean, modifier: Modifier = Modifier) {
    val core = AppContainerHolder.instance.coreBridge
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val list = rememberLazyListState()
    var title by remember { mutableStateOf("ACP") }
    var history by remember { mutableStateOf(emptyList<JsonObject>()) }
    var before by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var thought by remember { mutableStateOf("") }
    var thoughtExpanded by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var active by remember { mutableStateOf("") }
    var loadingOlder by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var tools by remember { mutableStateOf(emptyList<JsonObject>()) }
    var permission by remember { mutableStateOf<JsonObject?>(null) }
    val endedEarly = stringResource(Res.string.acp_stream_ended)
    fun report(message: String) {
        error = message
        scope.launch { snackbar.showSnackbar(message) }
    }
    suspend fun loadHistory(cursor: String = "") {
        val result = requestAcp(core, "history") { put("sessionId", sessionId); if (cursor.isNotEmpty()) put("before", cursor) }
        history = result.objects("messages") + if (cursor.isEmpty()) emptyList() else history
        before = result.text("before")
    }
    LaunchedEffect(sessionId) {
        try {
            withContext(ioDispatcher) { core.getConversationJson(sessionId) }?.let { title = Json.parseToJsonElement(it).jsonObject.text("title") }
            loadHistory()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { report(failure.message.orEmpty())
        } finally { loading = false }
    }
    LaunchedEffect(history.size, partial.length) {
        if (!loadingOlder && (history.isNotEmpty() || partial.isNotEmpty())) list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }
    Scaffold(modifier = modifier.fillMaxSize(), snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Column { Text(title, maxLines = 1); Text("ACP", style = MaterialTheme.typography.labelMedium) } }, navigationIcon = {
            if (showBackButton) IconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back)) }
        }) }, bottomBar = {
            ChatInputBar(text = draft, onTextChange = { draft = it }, isGenerating = active.isNotEmpty(), showFunctionPanel = false,
                onStopClick = { scope.launch { try { core.controlCancel(active) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { report(failure.message.orEmpty()) } } },
                onSendClick = {
                    if (!loading && active.isEmpty() && draft.text.isNotBlank()) {
                        val text = draft.text
                        val requestId = randomUuid()
                        active = requestId
                        error = ""; partial = ""; thought = ""; tools = emptyList()
                        scope.launch {
                            var terminal = false
                            try {
                                core.controlCommand(requestId, buildJsonObject { put("action", "prompt"); put("sessionId", sessionId); put("text", text) }) { event ->
                                    when (event.text("type")) {
                                        "TurnStarted" -> {
                                            draft = TextFieldValue("")
                                            history = history + buildJsonObject { put("id", randomUuid()); put("role", "user"); put("text", text); put("status", "sent") }
                                        }
                                        "TextDelta" -> partial += event.text("text")
                                        "ThoughtDelta" -> thought += event.text("text")
                                        "ToolUpdate" -> {
                                            val update = event["update"]?.jsonObject ?: event
                                            val id = update.text("toolCallId").ifBlank { update.text("id") }
                                            tools = if (id.isBlank()) tools + update else tools.filterNot { it.text("toolCallId").ifBlank { it.text("id") } == id } + update
                                        }
                                        "Permission" -> permission = event
                                        "Finished", "Cancelled" -> terminal = true
                                        "Error" -> { terminal = true; report(event.errorDetail()) }
                                    }
                                }
                                if (!terminal) report(endedEarly)
                                loadHistory()
                                partial = ""
                            } catch (cancelled: CancellationException) { throw cancelled
                            } catch (failure: Exception) { report(failure.message.orEmpty())
                            } finally { active = ""; permission = null }
                        }
                    }
                }, modifier = Modifier.imePadding())
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (before.isNotEmpty()) item {
                    TextButton(enabled = !loading && active.isEmpty(), onClick = {
                        loading = true
                        loadingOlder = true
                        scope.launch { try { loadHistory(before) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { report(failure.message.orEmpty()) } finally { loading = false; loadingOlder = false } }
                    }) { Text(stringResource(Res.string.acp_older_messages)) }
                }
                items(history, key = { it.text("id") }) { message ->
                    Surface(color = if (message.text("role") == "user") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                        if (message.text("status") == "error") Text(message.text("text"), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                        else ChatMarkdownText(message.text("text"), false, Modifier.padding(16.dp))
                    }
                }
                if (thought.isNotEmpty()) item {
                    Card(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { thoughtExpanded = !thoughtExpanded }) { Text(stringResource(Res.string.providers_model_supports_thinking)) }
                        if (thoughtExpanded) ChatMarkdownText(thought, active.isNotEmpty(), Modifier.padding(12.dp))
                    }
                }
                items(tools) { update ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(update.text("title").ifBlank { update.text("toolCallId") }, style = MaterialTheme.typography.titleSmall)
                        Text(update.text("status"), style = MaterialTheme.typography.labelSmall)
                        update["content"]?.let { content ->
                            val text = acpContentText(content)
                            if (text.isNotBlank()) ChatMarkdownText(text, false, Modifier.fillMaxWidth())
                        }
                    } }
                }
                if (partial.isNotEmpty()) item { ChatMarkdownText(partial, true, Modifier.fillMaxWidth()) }
                if (error.isNotEmpty()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            permission?.let { event ->
                Card(Modifier.fillMaxWidth().padding(12.dp)) { Column(Modifier.padding(12.dp)) {
                    Text(stringResource(Res.string.acp_permission), style = MaterialTheme.typography.titleMedium)
                    event["tool"]?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
                    event.objects("options").forEach { option ->
                        TextButton(onClick = { scope.launch {
                            try { core.controlPermission(active, option.text("id")); permission = null }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { report(failure.message.orEmpty()) }
                        } }) { Text(option.text("name")) }
                    }
                } }
            }
        }
    }
}

private fun acpContentText(content: JsonElement): String = when (content) {
    is JsonArray -> content.joinToString("\n\n") { acpContentText(it) }
    is JsonObject -> when (content.text("type")) {
        "text" -> content.text("text")
        "diff" -> "`${content.text("path")}`\n```diff\n" +
            content.text("oldText").lines().joinToString("\n") { "-$it" } + "\n" +
            content.text("newText").lines().joinToString("\n") { "+$it" } + "\n```"
        else -> content["content"]?.let { acpContentText(it) } ?: content.text("text")
    }
    else -> (content as? JsonPrimitive)?.contentOrNull.orEmpty()
}
