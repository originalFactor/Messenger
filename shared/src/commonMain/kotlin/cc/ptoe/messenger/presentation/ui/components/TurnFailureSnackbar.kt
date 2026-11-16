/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import cc.ptoe.messenger.domain.chat.TurnFailureNotice
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.chat_failure_generic
import cc.ptoe.messenger.generated.resources.chat_failure_named
import cc.ptoe.messenger.generated.resources.chat_failure_open
import org.jetbrains.compose.resources.getString

internal suspend fun presentTurnFailureSnackbar(
    notice: TurnFailureNotice,
    snackbar: SnackbarHostState,
    openConversation: (String) -> Unit,
) {
    val title = notice.conversationTitle?.takeIf { it.isNotBlank() }
    val message = if (title == null) getString(Res.string.chat_failure_generic, notice.detail)
        else getString(Res.string.chat_failure_named, title, notice.detail)
    val action = notice.conversationId?.let { getString(Res.string.chat_failure_open) }
    if (snackbar.showSnackbar(message, actionLabel = action, withDismissAction = action != null,
            duration = if (action == null) SnackbarDuration.Short else SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
        notice.conversationId?.let(openConversation)
    }
}
