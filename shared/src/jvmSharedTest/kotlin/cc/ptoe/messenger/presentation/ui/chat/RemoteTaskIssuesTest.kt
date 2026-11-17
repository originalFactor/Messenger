/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.ui.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteTaskIssuesTest {
    @Test fun warningsAndErrorsKeepTheirDetailsAndIgnoreRepeatedEvents() {
        val summary = buildJsonObject { put("type", "SummaryFailed"); put("message", "provider unavailable") }
        val title = buildJsonObject { put("type", "TitleFailed"); put("code", "error_title_generate_failed:timeout") }
        val error = buildJsonObject { put("type", "Error"); put("message", "HTTP 429"); put("detail", "quota exhausted") }
        val approval = buildJsonObject { put("type", "ApprovalFailed"); put("message", "review unavailable") }
        val result = remoteTaskIssues(listOf(summary, summary, title, approval, approval, error), "Summary failed", "Title failed", "Task failed", approvalLabel = "Approval failed")
        assertEquals(listOf("Summary failed: provider unavailable", "Title failed: timeout", "Approval failed: review unavailable", "HTTP 429 quota exhausted"), result.map { it.message })
        assertEquals(listOf("SummaryFailed", "TitleFailed", "ApprovalFailed", "Error"), result.map { it.type })
    }

    @Test fun completedErrorRowRemainsVisibleWhenTheRelayHasNoErrorEvent() {
        val row = buildJsonObject { put("role", "assistant"); put("status", "error"); put("text", "network failed") }
        assertEquals("network failed", remoteTaskIssues(emptyList(), "Summary", "Title", "Task", listOf(row)).single().message)
        assertEquals(emptyList(), remoteTaskIssues(emptyList(), "Summary", "Title", "Task", listOf(row), active = true))
    }
}
