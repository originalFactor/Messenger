package cc.ptoe.messenger

import cc.ptoe.messenger.di.AppContainer
import cc.ptoe.messenger.presentation.platform.TaskPushNotifications
import java.awt.AWTEvent
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.coroutines.launch

internal const val IDLE_NANOS = 5L * 60 * 1_000_000_000

internal fun shouldPushTaskEvent(focused: Boolean, idleNanos: Long): Boolean =
    !focused || idleNanos >= IDLE_NANOS

/** Conversation title and identity accompany the generic event when available. */
internal fun installDesktopTaskPush(container: AppContainer) {
    val lastInput = AtomicLong(System.nanoTime())
    Toolkit.getDefaultToolkit().addAWTEventListener(
        { lastInput.set(System.nanoTime()) },
        AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or
            AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK,
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Observe the persisted scheduler outcome, not its in-progress sentinel.
    // The first snapshot is a baseline, so restarting the app does not resend old results.
    scope.launch {
        val json = Json { ignoreUnknownKeys = true }
        val observer = ScheduledTaskObserver()
        while (true) {
            try {
                val tasks = json.parseToJsonElement(container.coreBridge.scheduledTaskRequest("GET", "/scheduled-tasks")).jsonArray
                val snapshots = tasks.mapNotNull { element ->
                    val task = element.jsonObject
                    val id = task["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    ScheduledTaskSnapshot(id, task["lastRunAt"]?.jsonPrimitive?.longOrNull,
                        task["lastError"]?.jsonPrimitive?.contentOrNull,
                        task["lastConversationId"]?.jsonPrimitive?.contentOrNull)
                }
                observer.update(snapshots).forEach(TaskPushNotifications::notify)
            } catch (failure: Exception) {
                System.err.println("Messenger scheduled task monitoring failed: ${failure.message}")
            }
            delay(5_000)
        }
    }
    TaskPushNotifications.onEvent = { event ->
        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow != null
        if (shouldPushTaskEvent(focused, System.nanoTime() - lastInput.get())) {
            scope.launch {
                if (container.cloud.user.first() != null) {
                    try {
                        val conversationId = event.conversationId?.takeIf { it.matches(Regex("[A-Za-z0-9_:-]{1,200}")) }
                        val title = conversationId?.let { id ->
                            try {
                                container.coreBridge.getConversationJson(id)?.let { raw ->
                                    Json.parseToJsonElement(raw).jsonObject["title"]?.jsonPrimitive?.contentOrNull?.let(::pushTitle)
                                }
                            } catch (failure: Exception) {
                                System.err.println("Messenger could not load the task conversation title: ${failure.message}")
                                null
                            }
                        }
                        val remoteEnabled = conversationId?.let {
                            try {
                                container.coreBridge.controlSettings()["enabled"]?.jsonPrimitive?.booleanOrNull == true
                            } catch (failure: Exception) {
                                System.err.println("Messenger could not determine remote control availability: ${failure.message}")
                                false
                            }
                        } ?: false
                        val deviceId = conversationId?.let {
                            try {
                                container.coreBridge.remoteStatus()["deviceId"]?.jsonPrimitive?.contentOrNull
                                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
                            } catch (failure: Exception) {
                                System.err.println("Messenger could not identify the task device: ${failure.message}")
                                null
                            }
                        }
                        val targetKind = taskTargetKind(event.kind, event.event, conversationId, deviceId, remoteEnabled)
                        container.cloud.sendPushEvent(event.event, title, targetKind, conversationId, deviceId.takeIf { targetKind == "remote" })
                    } catch (failure: Exception) {
                        System.err.println("Messenger task push failed: ${failure.message}")
                    }
                }
            }
        }
    }
}

/** JPush titles are bounded and do not contain control characters. */
internal fun pushTitle(value: String): String? {
    val cleaned = value.filterNot { it.isISOControl() }.trim().take(160)
    return cleaned.dropLastWhile { it.isHighSurrogate() }.ifBlank { null }
}

internal fun taskTargetKind(taskKind: String, event: String, conversationId: String?, deviceId: String?, remoteEnabled: Boolean): String? = when {
    conversationId == null -> null
    (taskKind == "acp" || event == "approval" || remoteEnabled) && deviceId != null -> "remote"
    taskKind == "acp" || event == "approval" -> null
    taskKind == "chat" -> "local"
    else -> null
}
