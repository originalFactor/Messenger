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
import kotlinx.coroutines.launch

internal const val IDLE_NANOS = 5L * 60 * 1_000_000_000

internal fun shouldPushTaskEvent(focused: Boolean, idleNanos: Long): Boolean =
    !focused || idleNanos >= IDLE_NANOS

/** Sends only generic event types; conversation content stays on the device. */
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
                        task["lastError"]?.jsonPrimitive?.contentOrNull)
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
                        container.cloud.sendPushEvent(event)
                    } catch (failure: Exception) {
                        System.err.println("Messenger task push failed: ${failure.message}")
                    }
                }
            }
        }
    }
}
