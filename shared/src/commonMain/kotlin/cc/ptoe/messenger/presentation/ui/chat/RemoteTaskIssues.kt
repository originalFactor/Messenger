/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.ui.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class RemoteTaskIssue(val type: String, val message: String)

/** Keep task failures visible even when a relay sends only a code or only details. */
internal fun remoteTaskIssues(
    events: List<JsonObject>, summaryLabel: String, titleLabel: String, errorLabel: String,
    history: List<JsonObject> = emptyList(), active: Boolean = false,
    approvalLabel: String = errorLabel,
): List<RemoteTaskIssue> {
    val issues = events.mapNotNull { event ->
        fun text(key: String) = (event[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty().trim()
        val type = text("type")
        if (type !in setOf("SummaryFailed", "TitleFailed", "ApprovalFailed", "Error")) return@mapNotNull null
        val rawCode = text("code")
        val code = if (rawCode == "error_title_generate_failed") "" else rawCode.removePrefix("error_title_generate_failed:")
        val detail = listOf(text("message"), text("detail")).filter(String::isNotBlank).distinct().joinToString(" ").ifBlank { code }
        val label = when (type) { "SummaryFailed" -> summaryLabel; "TitleFailed" -> titleLabel; "ApprovalFailed" -> approvalLabel; else -> errorLabel }
        val message = when {
            type == "Error" -> detail.ifBlank { label }
            detail.isBlank() -> label
            detail.startsWith(label, ignoreCase = true) -> detail
            else -> "$label: $detail"
        }
        RemoteTaskIssue(type, message)
    }.distinct()
    if (active || issues.any { it.type == "Error" }) return issues
    val last = history.lastOrNull() ?: return issues
    fun text(key: String) = (last[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty().trim()
    if (text("role") != "assistant" || text("status") != "error") return issues
    return issues + RemoteTaskIssue("Error", text("text").ifBlank { errorLabel })
}
