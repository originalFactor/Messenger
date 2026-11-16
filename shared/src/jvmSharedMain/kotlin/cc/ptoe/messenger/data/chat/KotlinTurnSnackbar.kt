/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** Install once at the application scaffold when Kotlin turns own the production lifecycle.
 * A notification is acknowledged only after the snackbar finishes displaying; cancellation
 * leaves it available to the next observer. Error bubbles are persisted as message rows. */
@Composable
fun ObserveKotlinTurnErrors(store: RoomKotlinTurnStore, snackbar: SnackbarHostState) {
    LaunchedEffect(store, snackbar) {
        store.observeFailures().collect { failures ->
            for (failure in failures) {
                snackbar.showSnackbar(failure.detail)
                store.acknowledgeFailure(failure.turnId)
            }
        }
    }
}
