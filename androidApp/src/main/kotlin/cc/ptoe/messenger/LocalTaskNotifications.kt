/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.util.Log
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.presentation.platform.KotlinTurnTaskEvents
import cc.ptoe.messenger.presentation.platform.KotlinScheduledTaskEvents

/** Presents Core task outcomes locally, independently of cloud push. */
object LocalTaskNotifications {
    private const val CHANNEL_ID = "messenger_task_updates"
    private const val NOTIFICATION_ID = 1003

    fun install(context: Context, core: CoreBridge) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, app.getString(R.string.task_update_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val handle: (String, List<String>) -> Unit = handle@{ kind, ids ->
            val action = taskNotificationAction(kind, ids) ?: return@handle
            if (action is TaskNotificationAction.Clear) {
                manager.cancel(action.tag, NOTIFICATION_ID)
                return@handle
            }
            action as TaskNotificationAction.Show
            val text = when (action.status) {
                "approval" -> R.string.task_update_approval
                "completed" -> R.string.task_update_completed
                "failed" -> R.string.task_update_failed
                else -> return@handle
            }
            if (!manager.areNotificationsEnabled() ||
                (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ) return@handle

            val openIntent = Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (action.taskKind == "chat" || action.taskKind == "acp") {
                openIntent.action = ACTION_OPEN_CONVERSATION
                openIntent.putExtra("kind", "local")
                openIntent.putExtra("conversationId", action.taskId)
                // PendingIntent identity excludes extras: assign a distinct URI per task.
                openIntent.data = Uri.Builder().scheme("messenger-notification").authority("task")
                    .appendPath(action.taskKind).appendPath(action.taskId).build()
            }
            val open = PendingIntent.getActivity(
                app, 0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(app.getString(R.string.task_update_title))
                .setContentText(app.getString(text))
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            try {
                manager.notify(action.tag, NOTIFICATION_ID, notification)
            } catch (failure: SecurityException) {
                // Permission may have been revoked after the check above.
                Log.w("TaskNotifications", "Unable to show task update", failure)
            }
        }
        core.subscribe(handle)
        KotlinTurnTaskEvents.onEvent = { status, conversationId ->
            handle("localTaskStatus", listOf(status, "chat", conversationId))
        }
        KotlinScheduledTaskEvents.onEvent = { status, taskId ->
            handle("localTaskStatus", listOf(status, "scheduled", taskId))
        }
    }
}
