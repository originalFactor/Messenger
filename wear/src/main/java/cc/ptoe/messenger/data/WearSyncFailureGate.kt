/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stops automatic sync after an error until the user explicitly retries. */
internal class WearSyncFailureGate {
    private val mutex = Mutex()
    private val failure = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = failure

    suspend fun run(explicitRetry: Boolean = false, operation: suspend () -> Unit): Boolean = mutex.withLock {
        if (failure.value != null && !explicitRetry) return@withLock false
        try {
            operation()
            failure.value = null
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure.value = error.message?.takeIf { it.isNotBlank() } ?: "Could not synchronize with phone."
            false
        }
    }
}
