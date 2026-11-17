/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.chat

import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.dto.ChatMessageDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.presentation.utils.stripThinkBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.json.JsonPrimitive

/** Title requests have no tools and use the title Agent's own model and parameters. */
data class KotlinTitleInput(
    val model: String,
    val systemPrompt: String,
    val firstUser: String,
    val firstAssistant: String,
    val temperature: Float?,
    val topP: Float?,
    val maxTokens: Int?,
)

/** [failure] is surfaced separately; title failures never discard the completed assistant reply. */
data class KotlinTitleResult(val title: String, val failure: String? = null)

/**
 * Creates exactly one provider request. The caller must hold the turn reservation while this
 * runs, route Cloud requests through its session gate, and commit the title only if the
 * conversation is still untitled. A cancelled generation must not send another request.
 */
object KotlinConversationTitle {
    fun isUntitled(title: String): Boolean = title.isBlank() || title == "新对话" || title == "New Chat"

    fun sanitize(raw: String): String {
        val line = stripThinkBlock(raw).trim().trim('"', '“', '”', '「', '」', '『', '』', '\'', '《', '》')
            .trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val (head, truncated) = clipCodePoints(line, 30)
        return head + if (truncated) "..." else ""
    }

    suspend fun generate(input: KotlinTitleInput, stream: (ChatCompletionRequestDto) -> Flow<ChatStreamEvent>): KotlinTitleResult {
        val user = clipCodePoints(input.firstUser, 2_000).first
        val assistant = clipCodePoints(input.firstAssistant, 2_000).first
        val request = ChatCompletionRequestDto(
            model = input.model,
            messages = listOf(
                ChatMessageDto(role = "system", content = JsonPrimitive(input.systemPrompt)),
                ChatMessageDto(role = "user", content = JsonPrimitive("User: $user\nAssistant: $assistant\n")),
            ),
            temperature = input.temperature,
            topP = input.topP,
            maxTokens = input.maxTokens,
            tools = null,
            stream = false,
        )
        val fallback = sanitize(input.firstUser)
        val text = StringBuilder()
        var completed = false
        var failure: String? = null
        try {
            stream(request).transformWhile { event ->
                emit(event)
                event !is ChatStreamEvent.Done && event !is ChatStreamEvent.Error
            }.collect { event ->
                when (event) {
                    is ChatStreamEvent.Content -> {
                        check(text.length + event.text.length <= 16_384) { "Title response exceeded size limit" }
                        text.append(event.text)
                    }
                    is ChatStreamEvent.Done -> {
                        check(event.toolCalls.isEmpty()) { "Title request returned unexpected tool calls" }
                        completed = true
                    }
                    is ChatStreamEvent.Error -> failure = event.message
                    is ChatStreamEvent.ReasoningDetected -> Unit
                }
            }
            if (!completed && failure == null) failure = "Title stream ended without a completion event"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error.message ?: "Title generation failed"
        }
        val title = if (failure == null) sanitize(text.toString()).ifBlank { fallback } else fallback
        return KotlinTitleResult(title, failure)
    }

    private fun clipCodePoints(value: String, limit: Int): Pair<String, Boolean> {
        var index = 0
        var points = 0
        while (index < value.length && points < limit) {
            if (value[index].isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) index++
            index++
            points++
        }
        return value.substring(0, index) to (index < value.length)
    }
}
