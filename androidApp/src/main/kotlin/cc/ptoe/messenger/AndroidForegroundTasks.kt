/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.presentation.platform.BackgroundTaskPersistence
import cc.ptoe.messenger.presentation.platform.BackgroundTaskPersistenceRegistry
import cc.ptoe.messenger.presentation.platform.ForegroundTaskRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Coordinates overlapping local clients with Core-owned background work. */
object AndroidForegroundTasks : BackgroundTaskPersistence {
    private const val TAG = "ForegroundTasks"
    private const val PREFS = "background_tasks"
    private const val KEEP_ALIVE = "keep_alive"
    private lateinit var app: Context
    private val state = MutableStateFlow(false)
    override val enabled: StateFlow<Boolean> = state
    val notificationPermissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var clients = 0
    private var coreActive = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun install(context: Context, core: CoreBridge) {
        app = context.applicationContext
        state.value = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEEP_ALIVE, false)
        BackgroundTaskPersistenceRegistry.controller = this
        ForegroundTaskRegistry.begin = ::beginTask
        ForegroundTaskRegistry.end = ::endTask
        ForegroundTaskRegistry.coreActive = ::setCoreActive
        scope.launch {
            while (isActive) {
                try {
                    setCoreActive(core.hasActiveTasks())
                } catch (failure: Exception) {
                    // Preserve the last known state while Core is temporarily unavailable.
                    Log.w(TAG, "Unable to read task activity", failure)
                }
                delay(2_000)
            }
        }
    }

    /** Called from a visible Activity, where Android permits starting a foreground service. */
    @Synchronized fun restoreFromActivity() {
        if (state.value && !notificationsAvailable()) {
            state.value = false
            save(false)
        }
        if (shouldRun() && !startService() && state.value) {
            state.value = false
            save(false)
        }
    }

    @Synchronized override fun setEnabled(enabled: Boolean): Boolean {
        if (enabled == state.value) return true
        if (enabled && !notificationsAvailable()) return false
        state.value = enabled
        if (enabled && !startService()) {
            state.value = false
            return false
        }
        save(enabled)
        if (!enabled) reconcile()
        return true
    }

    @Synchronized fun beginTask() {
        if (!notificationsAvailable()) notificationPermissionRequests.tryEmit(Unit)
        clients++
        reconcile()
    }

    @Synchronized fun endTask() {
        clients = (clients - 1).coerceAtLeast(0)
        reconcile()
    }

    @Synchronized fun setCoreActive(active: Boolean) {
        if (coreActive == active) return
        coreActive = active
        reconcile()
    }

    @Synchronized fun hasTasks(): Boolean = clients > 0 || coreActive
    @Synchronized fun shouldRun(): Boolean = state.value || hasTasks()

    private fun reconcile() {
        if (shouldRun()) startService() else app.stopService(Intent(app, TaskForegroundService::class.java))
    }

    private fun startService(): Boolean = try {
        app.startForegroundService(Intent(app, TaskForegroundService::class.java))
        true
    } catch (failure: Exception) {
        // Background starts can be rejected by Android; never abort the task itself.
        Log.w(TAG, "Unable to start task foreground service", failure)
        false
    }

    private fun notificationsAvailable(): Boolean {
        val manager = app.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = manager.getNotificationChannel(TaskForegroundService.CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    private fun save(value: Boolean) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEEP_ALIVE, value).apply()
    }
}
