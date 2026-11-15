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
import android.util.Log
import cc.ptoe.messenger.core.CoreBridge

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
        core.subscribe { kind, ids ->
            val action = taskNotificationAction(kind, ids) ?: return@subscribe
            if (action is TaskNotificationAction.Clear) {
                manager.cancel(action.tag, NOTIFICATION_ID)
                return@subscribe
            }
            action as TaskNotificationAction.Show
            val text = when (action.status) {
                "approval" -> R.string.task_update_approval
                "completed" -> R.string.task_update_completed
                "failed" -> R.string.task_update_failed
                else -> return@subscribe
            }
            if (!manager.areNotificationsEnabled() ||
                (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ) return@subscribe

            val open = PendingIntent.getActivity(
                app, 0,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
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
    }
}
