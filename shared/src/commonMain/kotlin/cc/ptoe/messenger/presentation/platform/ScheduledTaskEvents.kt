/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.platform

/** Local events contain only status and task ID, never prompts or provider credentials. */
object KotlinScheduledTaskEvents {
    var onEvent: ((status: String, taskId: String) -> Unit)? = null
    var onFailure: ((message: String) -> Unit)? = null
}
