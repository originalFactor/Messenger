/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.platform

import cc.ptoe.messenger.domain.chat.KotlinTurnLifecycle

/** The Android shell installs the local notification listener; events contain no private content. */
object KotlinTurnTaskEvents {
    var onEvent: ((status: String, conversationId: String) -> Unit)? = null
}

/** Retains the foreground lease even while the user is deciding a tool approval. */
object ForegroundKotlinTurnLifecycle : KotlinTurnLifecycle {
    override fun begin(conversationId: String) { ForegroundTaskRegistry.begin?.invoke() }
    override fun status(conversationId: String, status: String) {
        KotlinTurnTaskEvents.onEvent?.invoke(status, conversationId)
        if (status == "approval" || status == "completed" || status == "failed") {
            TaskPushNotifications.notify(TaskPushEvent(status, conversationId))
        }
    }
    override fun end(conversationId: String) { ForegroundTaskRegistry.end?.invoke() }
}
