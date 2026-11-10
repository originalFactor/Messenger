package cc.ptoe.messenger.presentation.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.data.util.ioDispatcher
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.generated.resources.*
import cc.ptoe.messenger.presentation.platform.copyTextToClipboard
import cc.ptoe.messenger.presentation.platform.showPlatformToast
import cc.ptoe.messenger.presentation.ui.acp.*
import cc.ptoe.messenger.presentation.ui.components.AgentAvatar
import cc.ptoe.messenger.presentation.utils.WindowSizeClass
import cc.ptoe.messenger.presentation.utils.windowSizeClassFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AcpConversationScreen(sessionId: String, onBackClick: () -> Unit, showBackButton: Boolean, modifier: Modifier = Modifier) {
    val container = AppContainerHolder.instance
    val core = container.coreBridge
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val userAvatar by container.appPreferences.userAvatar.collectAsState(initial = null)
    var title by remember { mutableStateOf("") }
    var historyTimestamp by remember { mutableStateOf(currentTimeMillis()) }
    var agentName by remember { mutableStateOf("ACP") }
    var history by remember { mutableStateOf(emptyList<JsonObject>()) }
    var before by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var partial by remember { mutableStateOf("") }
    var events by remember { mutableStateOf(emptyList<JsonObject>()) }
    var liveId by remember { mutableStateOf<String?>(null) }
    var liveTimestamp by remember { mutableStateOf(0L) }
    var active by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<JsonObject?>(null) }
    var permission by remember { mutableStateOf<JsonObject?>(null) }
    var selectedMessage by remember { mutableStateOf<Message?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var settingsBusy by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(buildJsonObject {}) }
    val endedEarly = stringResource(Res.string.acp_stream_ended)
    val copied = stringResource(Res.string.chat_copied_toast)
    fun report(message: String) {
        error = buildJsonObject {
            put("id", "screen-error"); put("role", "assistant"); put("text", message)
            put("status", "error"); put("timestamp", currentTimeMillis())
        }
        scope.launch { snackbar.showSnackbar(message) }
    }
    suspend fun loadHistory(cursor: String = "") {
        val result = requestAcp(core, "history") { put("sessionId", sessionId); if (cursor.isNotEmpty()) put("before", cursor) }
        history = result.objects("messages") + if (cursor.isEmpty()) emptyList() else history
        before = result.text("before")
        withContext(ioDispatcher) { core.getConversationJson(sessionId) }?.let {
            val conversation = Json.parseToJsonElement(it).jsonObject
            title = conversation.text("title")
            historyTimestamp = (conversation["updated_at"] as? JsonPrimitive)?.longOrNull?.takeIf { timestamp -> timestamp > 0 } ?: historyTimestamp
        }
    }
    fun readSettings(change: JsonObject? = null) {
        settingsBusy = true
        settingsError = null
        scope.launch {
            try {
                val result = requestAcp(core, if (change == null) "sessionSettings" else "setSessionConfig") {
                    put("sessionId", sessionId)
                    change?.forEach { (key, value) -> put(key, value) }
                }
                settings = result["settings"] as? JsonObject ?: buildJsonObject {}
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                settingsError = failure.message.orEmpty()
                report(failure.message.orEmpty())
            } finally { settingsBusy = false }
        }
    }
    LaunchedEffect(sessionId) {
        try {
            loadHistory()
            val row = withContext(ioDispatcher) { core.getConversationJson(sessionId) }?.let { Json.parseToJsonElement(it).jsonObject }
            val profileId = row?.text("agent_id")?.removePrefix("acp:")
            agentName = core.controlSettings().objects("agents").firstOrNull { it.text("id") == profileId }?.text("name") ?: "ACP"
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { report(failure.message.orEmpty())
        } finally { loading = false }
    }
    val agent = remember(agentName) { Agent("acp", agentName, systemPrompt = "", defaultModelId = null, temperature = 1f, topP = 1f, maxTokens = null, createdAt = 0, updatedAt = 0) }
    val rows = history + listOfNotNull(liveId?.let { id -> buildJsonObject {
        put("id", id); put("role", "assistant"); put("text", partial); put("status", if (active.isEmpty()) "sent" else "sending")
        put("timestamp", liveTimestamp); put("events", JsonArray(events))
    } }, error?.takeUnless { local -> history.lastOrNull()?.let { it.text("status") == "error" && it.text("text") == local.text("text") } == true })
    val messages = remember(rows, historyTimestamp) { acpChatMessages(sessionId, rows, historyTimestamp) }
    val chatItems = remember(messages) { buildChatItems(messages) }
    val liveContent = messages.firstOrNull { it.id == liveId }?.content
    BoxWithConstraints(modifier.fillMaxSize()) {
        val contextMenus = windowSizeClassFor(maxWidth) != WindowSizeClass.Compact
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Row(verticalAlignment = Alignment.CenterVertically) {
                        AgentAvatar(avatar = null, size = 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(title.ifBlank { stringResource(Res.string.chat_title_default) }, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 1)
                            Text(agentName, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    } },
                    navigationIcon = { if (showBackButton) IconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back)) } },
                    actions = {
                        TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above), tooltip = { PlainTooltip { Text(stringResource(Res.string.conversation_settings_title)) } }, state = rememberTooltipState()) {
                            IconButton(enabled = active.isEmpty() && !settingsBusy && !loading, onClick = { showSettings = true; readSettings() }) {
                                Icon(Icons.Default.Settings, stringResource(Res.string.conversation_settings_title))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                Column(Modifier.background(MaterialTheme.colorScheme.surface).navigationBarsPadding().imePadding()) {
                    permission?.let { event ->
                        Card(Modifier.fillMaxWidth().padding(12.dp)) { Column(Modifier.padding(12.dp)) {
                            Text(stringResource(Res.string.acp_permission), style = MaterialTheme.typography.titleMedium)
                            (event["tool"] as? JsonObject)?.let { tool ->
                                Text(tool.text("title"), style = MaterialTheme.typography.titleSmall)
                                if (tool.text("detail").isNotBlank()) Text(tool.text("detail"), style = MaterialTheme.typography.bodySmall)
                            }
                            event.objects("options").forEach { option ->
                                TextButton(onClick = { scope.launch {
                                    try { core.controlPermission(active, option.text("id")); permission = null }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { report(failure.message.orEmpty()) }
                                } }) { Text(option.text("name")) }
                            }
                        } }
                    }
                    ChatInputBar(text = draft, onTextChange = { draft = it }, isGenerating = active.isNotEmpty(), showFunctionPanel = false,
                        onStopClick = { scope.launch { try { core.controlCancel(active) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { report(failure.message.orEmpty()) } } },
                        onSendClick = {
                            if (!loading && !settingsBusy && active.isEmpty() && draft.text.isNotBlank()) {
                                val text = draft.text
                                val requestId = randomUuid()
                                active = requestId
                                error = null; partial = ""; events = emptyList()
                                scope.launch {
                                    var terminal = false
                                    try {
                                        core.controlCommand(requestId, buildJsonObject { put("action", "prompt"); put("sessionId", sessionId); put("text", text) }) { event ->
                                            (event["settings"] as? JsonObject)?.let { settings = it }
                                            when (event.text("type")) {
                                                "TurnStarted" -> {
                                                    draft = TextFieldValue("")
                                                    liveTimestamp = currentTimeMillis()
                                                    liveId = randomUuid()
                                                    history = history + buildJsonObject { put("id", randomUuid()); put("role", "user"); put("text", text); put("status", "sent"); put("timestamp", liveTimestamp) }
                                                }
                                                "TextDelta" -> partial += event.text("text")
                                                "ThoughtDelta", "ToolUpdate" -> events = events + event
                                                "Permission" -> permission = event
                                                "Finished", "Cancelled" -> terminal = true
                                                "Error" -> { terminal = true; report(event.errorDetail()) }
                                            }
                                        }
                                        if (!terminal) report(endedEarly)
                                        loadHistory()
                                        liveId = null
                                    } catch (cancelled: CancellationException) { throw cancelled
                                    } catch (failure: Exception) { report(failure.message.orEmpty())
                                    } finally { active = ""; permission = null }
                                }
                            }
                        })
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surface)) {
                if (messages.isEmpty() && loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                else if (messages.isEmpty()) EmptyChatState(null, agentName, Modifier.align(Alignment.Center))
                else ChatMessageList(
                    chatItems = chatItems, isGenerating = active.isNotEmpty(), streamingContent = liveContent, streamingMessageId = liveId, streamingDiff = null,
                    agent = agent, userAvatar = userAvatar, enableContextMenu = contextMenus, copiedToastText = copied,
                    onRetryClick = {}, onRegenerateClick = {}, onDeleteClick = {}, allowMessageMutation = false,
                    onCopyClick = ::copyTextToClipboard, onMessageLongClick = { selectedMessage = it },
                    hasOlderMessages = before.isNotEmpty(), isLoadingHistory = loading,
                    onLoadOlderMessages = {
                        if (!loading && active.isEmpty()) {
                            loading = true
                            scope.launch { try { loadHistory(before) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { report(failure.message.orEmpty()) } finally { loading = false } }
                        }
                    }, modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (!contextMenus) MessageActionMenu(
            isVisible = selectedMessage != null, onDismiss = { selectedMessage = null }, messageRole = selectedMessage?.role ?: MessageRole.USER,
            onCopyClick = { selectedMessage?.content?.let { copyTextToClipboard(it); showPlatformToast(copied) } },
            onRegenerateClick = {}, onDeleteClick = {}, allowMessageMutation = false
        )
        if (showSettings) AcpSessionSettingsDialog(settings, settingsBusy, { showSettings = false }, ::readSettings, settingsError)
    }
}
