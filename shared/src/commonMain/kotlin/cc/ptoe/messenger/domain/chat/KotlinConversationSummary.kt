/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.chat

import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.presentation.utils.stripThinkBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile

data class KotlinSummaryWork(val turn: KotlinTurnSnapshot, val request: ChatCompletionRequestDto)
data class KotlinSummaryResult(val summary: String? = null, val failure: String? = null)

/** Makes one request; interruption and errors are surfaced, never retried automatically. */
object KotlinConversationSummary {
    suspend fun generate(work: KotlinSummaryWork, stream: (KotlinSummaryWork) -> Flow<ChatStreamEvent>): KotlinSummaryResult {
        val text = StringBuilder()
        var completed = false
        var failure: String? = null
        try {
            stream(work).transformWhile { event ->
                emit(event)
                event !is ChatStreamEvent.Done && event !is ChatStreamEvent.Error
            }.collect { event ->
                when (event) {
                    is ChatStreamEvent.Content -> {
                        check(text.length + event.text.length <= 262_144) { "Summary response exceeded size limit" }
                        text.append(event.text)
                    }
                    is ChatStreamEvent.Done -> {
                        check(event.toolCalls.isEmpty()) { "Summary request returned unexpected tool calls" }
                        completed = true
                    }
                    is ChatStreamEvent.Error -> failure = event.message
                    is ChatStreamEvent.ReasoningDetected -> Unit
                }
            }
            if (!completed && failure == null) failure = "Summary stream ended without a completion event"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: "Summary generation failed" }
        return KotlinSummaryResult(if (failure == null) stripThinkBlock(text.toString()).trim().ifEmpty { null } else null, failure)
    }
}
