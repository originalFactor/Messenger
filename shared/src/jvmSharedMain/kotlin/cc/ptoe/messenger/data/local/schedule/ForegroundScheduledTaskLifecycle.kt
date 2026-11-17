/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.schedule

import cc.ptoe.messenger.presentation.platform.ForegroundTaskRegistry
import cc.ptoe.messenger.presentation.platform.KotlinScheduledTaskEvents

/** Shell installs a foreground lease and notification callback; both are optional on desktop. */
internal object ForegroundScheduledTaskLifecycle : ScheduledTaskLifecycle {
    override fun begin(taskId: String) { ForegroundTaskRegistry.begin?.invoke() }
    override fun end(taskId: String) { ForegroundTaskRegistry.end?.invoke() }
    override fun status(taskId: String, status: String) { KotlinScheduledTaskEvents.onEvent?.invoke(status, taskId) }
    override fun schedulerFailed(message: String) { KotlinScheduledTaskEvents.onFailure?.invoke(message) }
}
