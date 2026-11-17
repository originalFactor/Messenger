/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_not_now
import cc.ptoe.messenger.generated.resources.action_retry
import cc.ptoe.messenger.generated.resources.chat_failure_review
import cc.ptoe.messenger.generated.resources.chat_failure_storage_error
import org.jetbrains.compose.resources.stringResource

/** A latched failure remains reachable without covering the active screen. */
@Composable
internal fun TurnFailureRecovery(failure: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var dialogOpen by rememberSaveable(failure) { mutableStateOf(true) }
    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(stringResource(Res.string.chat_failure_storage_error)) },
            text = { Text(failure) },
            confirmButton = { TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) } },
            dismissButton = { TextButton(onClick = { dialogOpen = false }) { Text(stringResource(Res.string.action_not_now)) } },
        )
    }
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        TextButton(onClick = { dialogOpen = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
            Text(stringResource(Res.string.chat_failure_review))
        }
    }
}
