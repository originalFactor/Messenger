package cc.ptoe.messenger.presentation.ui.chat

import androidx.compose.foundation.background
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import cc.ptoe.messenger.presentation.platform.*
import kotlin.io.encoding.Base64
import io.ktor.http.encodeURLPath
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    val clipboard = LocalClipboardManager.current
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
    var attachments by remember { mutableStateOf(emptyList<AcpAttachment>()) }
    data class Queued(val text: String, val attachments: List<AcpAttachment>)
    var queue by remember { mutableStateOf(emptyList<Queued>()) }
    var queuePaused by remember { mutableStateOf(false) }
    var steering by remember { mutableStateOf(false) }
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
        readSettings()
    }
    fun contentFor(text: String, files: List<AcpAttachment>) = promptContent(text, files)
    fun addAttachments(files: List<AcpAttachment>) {
        if ((attachments + files).sumOf { it.bytes.size.toLong() } > 16 * 1024 * 1024) report("Attachments exceed 16 MB")
        else attachments = attachments + files
    }
    val filePicker = rememberAcpFilePicker({ addAttachments(listOf(it)) }, ::report)
    val imagePicker = rememberImagePicker { addAttachments(listOf(AcpAttachment("Image.${it.extension}", it.mimeType, it.bytes))) }
    fun pasteAttachments(onText: () -> Unit = {}) { scope.launch {
        try { val files = readAcpClipboardAttachments(); if (files.isEmpty()) onText() else addAttachments(files) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { report(failure.message.orEmpty()) }
    } }
    fun steer(text: String, files: List<AcpAttachment>, onAccepted: () -> Unit) {
        if ((settings["steering"] as? JsonObject)?.get("supported") != JsonPrimitive(true)) { report("This Agent does not support steering"); return }
        if (steering || (text.isBlank() && files.isEmpty())) return
        steering = true
        scope.launch {
            try {
                val result = requestAcp(core, "steer") { put("sessionId", sessionId); put("content", contentFor(text, files)) }
                when ((result["result"] as? JsonObject)?.text("outcome")) {
                    "injected" -> { onAccepted(); history = history + buildJsonObject { put("id", randomUuid()); put("role", "user"); put("text", text); put("timestamp", currentTimeMillis()) } }
                    "promptRequired" -> { onAccepted(); queue = queue + Queued(text, files) }
                    else -> report("Steering was not accepted")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure.message.orEmpty()) }
            finally { steering = false }
        }
    }
    val sendPrompt: (Queued, Boolean) -> Unit = { input, clearDraft ->
                            if (!loading && !settingsBusy && active.isEmpty() && (input.text.isNotBlank() || input.attachments.isNotEmpty())) {
                                val text = input.text.ifBlank { input.attachments.joinToString { it.name } }
                                val content = promptContent(input.text, input.attachments)
                                if (clearDraft) { draft = TextFieldValue(""); attachments = emptyList() }
                                val requestId = randomUuid()
                                active = requestId
                                error = null; partial = ""; events = emptyList()
                                scope.launch {
                                    var terminal = false
                                    var admitted = false
                                    try {
                                        core.controlCommand(requestId, buildJsonObject { put("action", "prompt"); put("sessionId", sessionId); put("text", text); put("content", content) }) { event ->
                                            (event["settings"] as? JsonObject)?.let { settings = it }
                                            when (event.text("type")) {
                                                "TurnStarted" -> {
                                                    admitted = true
                                                    liveTimestamp = currentTimeMillis()
                                                    liveId = randomUuid()
                                                    history = history + buildJsonObject { put("id", randomUuid()); put("role", "user"); put("text", text); put("content", content); put("status", "sent"); put("timestamp", liveTimestamp) }
                                                }
                                                "TextDelta" -> partial += event.text("text")
                                                "ThoughtDelta", "ToolUpdate" -> events = events + event
                                                "Permission" -> permission = event
                                                "Finished" -> terminal = true
                                                "Cancelled" -> { terminal = true; queuePaused = true }
                                                "Error" -> { terminal = true; queuePaused = true; if (!admitted) queue = listOf(input) + queue; report(event.errorDetail()) }
                                            }
                                        }
                                        if (!terminal) { queuePaused = true; report(endedEarly) }
                                        loadHistory()
                                        liveId = null
                                    } catch (cancelled: CancellationException) { throw cancelled
                                    } catch (failure: Exception) { queuePaused = true; if (!admitted) queue = listOf(input) + queue; report(failure.message.orEmpty())
                                    } finally { active = ""; permission = null }
                                }
                            }
    }
    LaunchedEffect(active, queue, loading, settingsBusy, queuePaused) {
        if (active.isEmpty() && !loading && !settingsBusy && !queuePaused && queue.isNotEmpty()) {
            val next = queue.first(); queue = queue.drop(1)
            sendPrompt(next, false)
        }
    }
    val agent = remember(agentName) { Agent("acp", agentName, systemPrompt = "", defaultModelId = null, temperature = 1f, topP = 1f, maxTokens = null, createdAt = 0, updatedAt = 0) }
    val rows = history + listOfNotNull(liveId?.let { id -> buildJsonObject {
        put("id", id); put("role", "assistant"); put("text", partial); put("status", if (active.isEmpty()) "sent" else "sending")
        put("timestamp", liveTimestamp); put("events", JsonArray(events))
    } }, error?.takeUnless { local -> history.lastOrNull()?.let { it.text("status") == "error" && it.text("text") == local.text("text") } == true })
    val messages = remember(rows, historyTimestamp) { acpChatMessages(sessionId, rows, historyTimestamp) }
    val chatItems = remember(messages) { buildChatItems(messages) }
    val liveContent = messages.firstOrNull { it.id == liveId }?.content
    val originalToolbar = LocalTextToolbar.current
    val attachmentToolbar = object : TextToolbar {
        override val status get() = originalToolbar.status
        override fun hide() = originalToolbar.hide()
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            originalToolbar.showMenu(rect, onCopyRequested, { pasteAttachments { onPasteRequested?.invoke() } }, onCutRequested, onSelectAllRequested)
        }
    }
    CompositionLocalProvider(LocalTextToolbar provides attachmentToolbar) {
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
                    if (settingsBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (queuePaused && queue.isNotEmpty()) TextButton(onClick = { queuePaused = false }) { Text(stringResource(Res.string.acp_resume_queue)) }
                    queue.forEach { queued -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(queued.text.ifBlank { queued.attachments.joinToString { it.name } }, Modifier.weight(1f), maxLines = 2)
                        TextButton(enabled = active.isNotEmpty() && !steering, onClick = { steer(queued.text, queued.attachments) { queue = queue - queued } }) { Text(stringResource(Res.string.acp_steer)) }
                        TextButton(onClick = { queue = queue - queued }) { Text("×") }
                    } }
                    attachments.forEach { attachment -> TextButton(onClick = { attachments = attachments - attachment }) { Text("${attachment.name} ×") } }
                    if (draft.text.startsWith("/")) settings.objects("availableCommands").filter { it.text("name").startsWith(draft.text.removePrefix("/")) }.take(6).forEach { command ->
                        TextButton(onClick = { val value = "/${command.text("name")} "; draft = TextFieldValue(value, TextRange(value.length)) }) { Text("/${command.text("name")} — ${command.text("description")}") }
                    }
                    ChatInputBar(text = draft, onTextChange = { draft = it }, isGenerating = active.isNotEmpty(), showFunctionPanel = true, sendWhileGenerating = true, hasAttachments = attachments.isNotEmpty(),
                        onAddClick = { imagePicker.launch() },
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Tab && draft.text.startsWith("/")) {
                                val command = settings.objects("availableCommands").firstOrNull { it.text("name").startsWith(draft.text.removePrefix("/")) }
                                if (command != null) { val text = "/${command.text("name")} "; draft = TextFieldValue(text, TextRange(text.length)) }
                                command != null
                            } else if (event.type == KeyEventType.KeyDown && event.key == Key.V && (event.isCtrlPressed || event.isMetaPressed)) {
                                pasteAttachments {
                                    clipboard.getText()?.text?.let { pasted ->
                                        val start = draft.selection.min; val end = draft.selection.max
                                        draft = TextFieldValue(draft.text.take(start) + pasted + draft.text.drop(end), TextRange(start + pasted.length))
                                    }
                                }; true
                            } else false
                        },
                        panelContent = { Column {
                            Row { TextButton(onClick = { filePicker.launch() }) { Text(stringResource(Res.string.acp_add_file)) }; TextButton(onClick = { pasteAttachments() }) { Text(stringResource(Res.string.acp_paste_attachments)) } }
                            AcpSessionControls(settings, settingsBusy || loading || active.isNotEmpty(), ::readSettings)
                        } },
                        beforeSend = {
                            val usage = settings["usage"] as? JsonObject
                            val used = (usage?.get("used") as? JsonPrimitive)?.longOrNull
                            val size = (usage?.get("size") as? JsonPrimitive)?.longOrNull
                            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above), state = rememberTooltipState(), tooltip = { PlainTooltip { Text(if (used != null && size != null) "$used / $size tokens" else stringResource(Res.string.acp_context_unavailable)) } }) {
                                CircularProgressIndicator(progress = { if (used != null && size != null && size > 0) (used.toFloat() / size).coerceIn(0f, 1f) else 0f }, modifier = Modifier.padding(8.dp).size(24.dp).semantics { contentDescription = if (used != null && size != null) "Context: $used / $size tokens" else "Context usage unavailable" }, strokeWidth = 3.dp)
                            }
                        },
                        onSendLongClick = {
                            val original = draft; val files = attachments
                            if (active.isNotEmpty()) steer(original.text, files) {
                                if (draft == original) draft = TextFieldValue("")
                                attachments = attachments.filterNot { it in files }
                            } else sendPrompt(Queued(original.text, files), true)
                        },
                        onStopClick = { scope.launch { try { core.controlCancel(active) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { report(failure.message.orEmpty()) } } },
                        onSendClick = {
                            if (active.isNotEmpty()) {
                                if (draft.text.isNotBlank() || attachments.isNotEmpty()) {
                                    queue = queue + Queued(draft.text, attachments)
                                    draft = TextFieldValue(""); attachments = emptyList()
                                }
                            } else sendPrompt(Queued(draft.text, attachments), true)
                        }
                        )
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
        cc.ptoe.messenger.presentation.platform.BackHandler(enabled = showSettings, onBack = { showSettings = false }) {}
        if (showSettings) AcpSessionSettingsScreen(settings, settingsBusy, { showSettings = false }, ::readSettings, settingsError)
    }
}

}

private fun promptContent(text: String, attachments: List<AcpAttachment>): JsonArray = buildJsonArray {
    if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
    attachments.forEach { attachment -> add(buildJsonObject {
        if (attachment.mimeType.startsWith("image/")) {
            put("type", "image"); put("mimeType", attachment.mimeType); put("data", Base64.encode(attachment.bytes))
        } else {
            put("type", "resource"); putJsonObject("resource") {
                put("uri", "attachment:///${attachment.name.encodeURLPath()}"); put("mimeType", attachment.mimeType)
                if (attachment.mimeType.startsWith("text/")) put("text", attachment.bytes.decodeToString()) else put("blob", Base64.encode(attachment.bytes))
            }
        }
    }) }
}
