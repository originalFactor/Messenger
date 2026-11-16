/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TurnFailureNotice(
    val noticeId: String,
    val turnId: String,
    val detail: String,
    val conversationId: String? = null,
    val conversationTitle: String? = null,
)

/** One shared consumer presents durable errors; cancellation leaves the current notice pending. */
class TurnFailureNotices(
    private val pending: Flow<List<TurnFailureNotice>>,
    private val acknowledge: suspend (TurnFailureNotice) -> Unit,
) {
    private val consumer = Mutex()
    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure = mutableFailure.asStateFlow()

    /** Retry only after an explicit user action; lifecycle changes do not reset this state. */
    fun retry() { mutableFailure.value = null }

    suspend fun present(show: suspend (TurnFailureNotice) -> Unit) = consumer.withLock {
        if (mutableFailure.value != null) return@withLock
        while (true) {
            val notice = try {
                pending.first { it.isNotEmpty() }.first()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportFailure(failure)
                return@withLock
            }
            show(notice)
            try {
                acknowledge(notice)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportFailure(failure)
                return@withLock
            }
        }
    }

    private fun reportFailure(failure: Exception) {
        mutableFailure.value = failure.message?.takeIf { it.isNotBlank() } ?: failure.toString()
    }
}
