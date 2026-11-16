/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import cc.ptoe.messenger.domain.chat.TurnFailureNotice
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.chat_failure_generic
import cc.ptoe.messenger.generated.resources.chat_failure_named
import cc.ptoe.messenger.generated.resources.chat_failure_open
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TurnFailureSnackbarTest {
    @Test fun namedFailureOpensItsOwnConversation(): Unit = runBlocking {
        val snackbar = SnackbarHostState()
        var opened: String? = null
        val detail = "HTTP 429: quota exceeded"
        val notice = TurnFailureNotice("notice", "turn", detail, "conversation-a", "Research")
        val task = launch { presentTurnFailureSnackbar(notice, snackbar) { opened = it } }
        val visible = withTimeout(5_000) {
            while (snackbar.currentSnackbarData == null) yield()
            assertNotNull(snackbar.currentSnackbarData)
        }
        assertEquals(getString(Res.string.chat_failure_named, "Research", detail), visible.visuals.message)
        assertEquals(getString(Res.string.chat_failure_open), visible.visuals.actionLabel)
        assertEquals(SnackbarDuration.Long, visible.visuals.duration)
        assertEquals(true, visible.visuals.withDismissAction)
        visible.performAction()
        withTimeout(5_000) { task.join() }
        assertEquals("conversation-a", opened)
    }

    @Test fun missingConversationStillDisplaysDetailWithoutOpenAction(): Unit = runBlocking {
        val snackbar = SnackbarHostState()
        var opened: String? = null
        val detail = "Provider failed: details"
        val task = launch { presentTurnFailureSnackbar(TurnFailureNotice("notice", "turn", detail), snackbar) { opened = it } }
        val visible = withTimeout(5_000) {
            while (snackbar.currentSnackbarData == null) yield()
            assertNotNull(snackbar.currentSnackbarData)
        }
        assertEquals(getString(Res.string.chat_failure_generic, detail), visible.visuals.message)
        assertNull(visible.visuals.actionLabel)
        visible.dismiss()
        withTimeout(5_000) { task.join() }
        assertNull(opened)
    }
}
