/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger

internal sealed interface TaskNotificationAction {
    val tag: String
    data class Show(override val tag: String, val status: String, val taskKind: String, val taskId: String) : TaskNotificationAction
    data class Clear(override val tag: String) : TaskNotificationAction
}

/** Core IDs are scoped by task kind so independent tasks cannot overwrite each other. */
internal fun taskNotificationAction(kind: String, ids: List<String>): TaskNotificationAction? {
    if (kind != "taskStatus" && kind != "localTaskStatus") return null
    val status = ids.getOrNull(0) ?: return null
    val taskKind = ids.getOrNull(1) ?: return null
    val taskId = ids.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return null
    if (taskKind !in setOf("chat", "acp", "scheduled", "image")) return null
    val tag = "task:$taskKind:$taskId"
    return when (status) {
        "approval", "completed", "failed" -> TaskNotificationAction.Show(tag, status, taskKind, taskId)
        "approvalResolved", "cancelled" -> TaskNotificationAction.Clear(tag)
        else -> null
    }
}
