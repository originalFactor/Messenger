/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import cc.ptoe.messenger.R

@Composable
fun StorageErrorNotice(detail: String, retrying: Boolean, onRetry: () -> Unit) {
    Column {
        BannerMessage(stringResource(R.string.wear_storage_error, detail))
        Button(onClick = onRetry, enabled = !retrying, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (retrying) R.string.wear_storage_retrying else R.string.wear_storage_retry))
        }
    }
}

@Composable
fun SyncErrorNotice(detail: String, retrying: Boolean, onRetry: () -> Unit) {
    Column {
        BannerMessage(stringResource(R.string.wear_sync_error, detail))
        Button(onClick = onRetry, enabled = !retrying, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (retrying) R.string.wear_sync_retrying else R.string.wear_sync_retry))
        }
    }
}
