package cc.ptoe.messenger.presentation.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.generated.resources.*
import cc.ptoe.messenger.presentation.platform.copyTextToClipboard
import cc.ptoe.messenger.presentation.ui.acp.objects
import cc.ptoe.messenger.presentation.ui.acp.errorDetail
import cc.ptoe.messenger.presentation.ui.acp.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

/** Read and control the exact conversation on its owning, opted-in device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteConversationScreen(deviceId: String, sessionId: String, onBack: () -> Unit) {
    val cloud = AppContainerHolder.instance.cloud
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var snapshot by remember(deviceId, sessionId) { mutableStateOf<JsonObject?>(null) }
    var failure by remember(deviceId, sessionId) { mutableStateOf<String?>(null) }
    var actionFailure by remember(deviceId, sessionId) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var actionBusy by remember { mutableStateOf(false) }
    var olderRows by remember(deviceId, sessionId) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var before by remember(deviceId, sessionId) { mutableStateOf<String?>(null) }
    var historyInitialized by remember(deviceId, sessionId) { mutableStateOf(false) }
    var historyBusy by remember { mutableStateOf(false) }
    var warning by remember(deviceId, sessionId) { mutableStateOf<String?>(null) }
    var taskFailure by remember(deviceId, sessionId) { mutableStateOf<String?>(null) }
    var issueTask by remember(deviceId, sessionId) { mutableStateOf<String?>(null) }
    val seenIssues = remember(deviceId, sessionId) { linkedSetOf<String>() }
    val unavailable = stringResource(Res.string.remote_notification_unavailable)
    val copied = stringResource(Res.string.chat_copied_toast)
    val summaryLabel = stringResource(Res.string.remote_summary_failed)
    val titleLabel = stringResource(Res.string.remote_title_failed)
    val approvalLabel = stringResource(Res.string.remote_approval_failed)
    val taskErrorLabel = stringResource(Res.string.chat_send_failed)

    suspend fun call(action: String, optionId: String? = null, before: String? = null, taskRequestId: String? = null): JsonObject {
        var result: JsonObject? = null
        cloud.remoteControlCommand(randomUuid(), deviceId, buildJsonObject {
            put("action", action)
            put("sessionId", sessionId)
            optionId?.let { put("optionId", it) }
            before?.let { put("before", it) }
            taskRequestId?.let { put("requestId", it) }
        }) { event ->
            if (event.text("type") == "Error") throw IllegalStateException(event.errorDetail().ifBlank { unavailable })
            result = event
        }
        return result ?: throw IllegalStateException(unavailable)
    }

    LaunchedEffect(deviceId, sessionId, retry, lifecycleState) {
        if (!lifecycleState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
        while (true) {
            try {
                val result = call("notificationOpen")
                if (result.text("type") != "Session") throw IllegalStateException(unavailable)
                // Keep rows that moved out of the latest page while the conversation advanced.
                // The older-page cursor still points before the first page we saw.
                val latestIds = result.objects("messages").mapTo(mutableSetOf()) { it.text("id") }
                olderRows = (olderRows + snapshot?.objects("messages").orEmpty().filter { it.text("id") !in latestIds })
                    .distinctBy { it.text("id") }
                snapshot = result
                if (!historyInitialized) {
                    before = (result["before"] as? JsonPrimitive)?.contentOrNull
                    historyInitialized = true
                }
                failure = null
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                failure = error.message?.takeIf { it.isNotBlank() }?.let { "$unavailable\n$it" } ?: unavailable
                snackbar.showSnackbar(failure.orEmpty())
                break // A failed cloud call requires an explicit user retry.
            }
            delay(2_000)
        }
    }

    fun runAction(action: String, optionId: String? = null) {
        if (actionBusy) return
        val taskRequestId = (snapshot?.get("requestId") as? JsonPrimitive)?.contentOrNull ?: return
        actionBusy = true
        actionFailure = null
        scope.launch {
            try {
                call(action, optionId, taskRequestId = taskRequestId)
                retry++
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                actionFailure = error.message?.takeIf { it.isNotBlank() } ?: unavailable
                snackbar.showSnackbar(actionFailure.orEmpty())
            } finally { actionBusy = false }
        }
    }

    val rows = (olderRows + snapshot?.objects("messages").orEmpty()).distinctBy { it.text("id") }
    val events = snapshot?.objects("events").orEmpty()
    val running = (snapshot?.get("active") as? JsonPrimitive)?.booleanOrNull == true
    val taskId = (snapshot?.get("requestId") as? JsonPrimitive)?.contentOrNull
    val canControl = taskId != null
    val issues = remember(events, rows, running, summaryLabel, titleLabel, taskErrorLabel, approvalLabel) {
        remoteTaskIssues(events, summaryLabel, titleLabel, taskErrorLabel, rows, running, approvalLabel)
    }
    LaunchedEffect(taskId, issues) {
        if (taskId != null && taskId != issueTask) {
            taskFailure = null
            issueTask = taskId
        }
        issues.forEach { issue ->
            val key = "$taskId|${issue.type}|${issue.message}"
            if (seenIssues.add(key)) {
                if (seenIssues.size > 64) seenIssues.remove(seenIssues.first())
                if (issue.type == "Error") taskFailure = issue.message else warning = issue.message
                snackbar.showSnackbar(issue.message)
            }
        }
    }
    val permission = events.lastOrNull { it.text("type") == "Permission" }
    val mediaLabels = AcpContentLabels(stringResource(Res.string.chat_picture), stringResource(Res.string.acp_media_audio),
        stringResource(Res.string.acp_media_attachment), stringResource(Res.string.acp_details_omitted))
    val live = buildString {
        events.filter { it.text("type") == "TextDelta" }.forEach { event ->
            val label = when (event.text("contentType")) {
                "image" -> mediaLabels.image
                "audio" -> mediaLabels.audio
                "resource", "resource_link" -> mediaLabels.attachment
                else -> null
            }
            if (label == null) append(event.text("text")) else {
                if (isNotEmpty() && last() != '\n') append('\n')
                append(label).append('\n')
            }
        }
    }
    val messages = remember(sessionId, rows, live, running, taskFailure, taskId, mediaLabels) {
        val history = acpChatMessages(sessionId, rows, labels = mediaLabels).let { existing ->
            if (running && live.isNotBlank() && existing.lastOrNull()?.let { it.role == MessageRole.ASSISTANT && it.content.isBlank() } == true)
                existing.dropLast(1)
            else existing
        }
        val withLive = if (running && live.isNotBlank()) history + Message(
            id = "remote-live-$sessionId", conversationId = sessionId, role = MessageRole.ASSISTANT,
            content = live, timestamp = 0L, status = MessageStatus.SENDING,
        ) else history
        val error = taskFailure
        if (error != null && withLive.none { it.status == MessageStatus.ERROR && (it.content == error || it.errorMessage == error) }) {
            withLive + Message(id = "remote-error-$taskId", conversationId = sessionId, role = MessageRole.ASSISTANT,
                content = error, timestamp = 0L, status = MessageStatus.ERROR, errorMessage = error)
        } else withLive
    }
    val title = snapshot?.text("title").orEmpty().ifBlank { stringResource(Res.string.remote_notification_title) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back)) }
        }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(Res.string.remote_notification_read_only), style = MaterialTheme.typography.bodySmall)
                if (permission != null && running) {
                    Text(stringResource(Res.string.remote_notification_approval), style = MaterialTheme.typography.titleSmall)
                    val tool = permission["tool"] as? JsonObject
                    val label = tool?.text("title").orEmpty().ifBlank { tool?.text("name").orEmpty() }
                    if (label.isNotBlank()) Text(label)
                    val details = tool?.text("argumentsJson").orEmpty().ifBlank { tool?.text("detail").orEmpty() }
                    if (details.isNotBlank()) Text(details, maxLines = 6)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        permission.objects("options").forEach { option ->
                            val optionId = option.text("id")
                            if (optionId.isNotBlank()) TextButton(onClick = { runAction("notificationDecision", optionId) }, enabled = !actionBusy && canControl) {
                                Text(option.text("name").ifBlank { optionId })
                            }
                        }
                    }
                }
                if (running) TextButton(onClick = { runAction("notificationCancel") }, enabled = !actionBusy && canControl) {
                    Text(stringResource(Res.string.remote_notification_cancel))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            warning?.let { message ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { warning = null }) { Text(stringResource(Res.string.action_close)) }
                }
            }
            actionFailure?.let { message ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { actionFailure = null }) { Text(stringResource(Res.string.action_close)) }
                }
            }
            failure?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                TextButton(onClick = { retry++ }) { Text(stringResource(Res.string.action_retry)) }
            }
            ChatMessageList(
                chatItems = buildChatItems(messages), isGenerating = running, streamingContent = null,
                streamingMessageId = null, streamingDiff = null, agent = null, userAvatar = null,
                enableContextMenu = false, copiedToastText = copied, onRetryClick = {}, onMessageLongClick = {},
                onCopyClick = ::copyTextToClipboard, onRegenerateClick = {}, onDeleteClick = {},
                allowMessageMutation = false,
                hasOlderMessages = before != null,
                isLoadingHistory = historyBusy,
                onLoadOlderMessages = {
                    val cursor = before
                    if (cursor != null && !historyBusy) {
                        historyBusy = true
                        actionFailure = null
                        scope.launch {
                            try {
                                val result = call("notificationOpen", before = cursor)
                                if (result.text("type") != "Session") throw IllegalStateException(unavailable)
                                olderRows = (result.objects("messages") + olderRows).distinctBy { it.text("id") }
                                before = (result["before"] as? JsonPrimitive)?.contentOrNull
                            } catch (cancelled: CancellationException) { throw cancelled
                            } catch (error: Exception) {
                                actionFailure = error.message?.takeIf { it.isNotBlank() } ?: unavailable
                                snackbar.showSnackbar(actionFailure.orEmpty())
                            } finally { historyBusy = false }
                        }
                    }
                }, modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}
