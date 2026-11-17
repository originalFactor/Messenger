/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Keeps an explicitly requested scheduler or active work visible to the user. */
class TaskForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "messenger_tasks"
        private const val NOTIFICATION_ID = 1002
        private const val WAKE_LOCK_MS = 10 * 60_000L
        private const val REFRESH_MS = 8 * 60_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val refreshWakeLock = object : Runnable {
        override fun run() {
            updateWakeLock()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.task_service_channel), NotificationManager.IMPORTANCE_LOW)
        )
        handler.post(refreshWakeLock)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.task_service_title))
            .setContentText(getString(if (AndroidForegroundTasks.hasTasks()) R.string.task_service_running else R.string.task_service_waiting))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        updateWakeLock()
        if (!AndroidForegroundTasks.shouldRun()) stopSelf(startId)
        return if (AndroidForegroundTasks.enabled.value) START_STICKY else START_NOT_STICKY
    }

    private fun updateWakeLock() {
        if (AndroidForegroundTasks.hasTasks()) {
            val lock = wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:tasks").also {
                    it.setReferenceCounted(false)
                    wakeLock = it
                }
            // Bounded and renewed only while work is active.
            lock.acquire(WAKE_LOCK_MS)
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
