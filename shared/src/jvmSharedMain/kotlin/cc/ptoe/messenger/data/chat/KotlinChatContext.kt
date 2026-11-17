/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.local.ContentPartCodec
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.MessageRow
import cc.ptoe.messenger.domain.model.ContentPart
import kotlinx.serialization.Serializable

/** Frozen before admission, so a resumed turn never silently re-sends an ambiguous request. */
@Serializable
internal data class StoredSummaryPlan(
    val cutoff: Long,
    val transcript: String,
    val summaryPrompt: String,
    val systemPrompt: String,
    val keptTokens: Long,
    val previousSummary: String?,
    val previousCutoff: Long,
)

internal object KotlinChatContext {
    fun estimateText(text: String): Long {
        var cjk = 0L
        var other = 0L
        var index = 0
        while (index < text.length) {
            val point = Character.codePointAt(text, index)
            if (point > 0x2E7F) cjk++ else other++
            index += Character.charCount(point)
        }
        return cjk + (other + 3) / 4
    }

    private fun parts(message: MessageRow): List<ContentPart> =
        if (message.partsJson.isNullOrBlank()) emptyList() else ContentPartCodec.decode(message.partsJson)

    fun estimateMessage(message: MessageRow): Long {
        val parts = parts(message)
        val text = parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }.ifBlank { message.content }
        return estimateText(text) + parts.count { it is ContentPart.Image }.toLong() * 800 + 4
    }

    fun estimate(conversation: ConversationRow, history: List<MessageRow>, prompt: String): Long {
        val sent = history.filter { it.status.equals("sent", ignoreCase = true) && it.id != "context-summary" }
        return if (conversation.contextTokens > 0) conversation.contextTokens + sent.filter { it.timestamp > conversation.contextTokensAt }.sumOf(::estimateMessage)
        else estimateText(prompt) + estimateText(conversation.contextSummary.orEmpty()) +
            sent.filter { it.timestamp >= conversation.contextSummaryUntil }.sumOf(::estimateMessage)
    }

    fun plan(conversation: ConversationRow, history: List<MessageRow>, prompt: String, summaryPrompt: String, contextWindow: Long): StoredSummaryPlan? {
        if (contextWindow <= 0) return null
        val threshold = contextWindow / 5 * 4 + (contextWindow % 5) * 4 / 5
        if (estimate(conversation, history, prompt) < threshold) return null
        val sent = history.filter { it.status.equals("sent", ignoreCase = true) }
        if (sent.size <= 10) return null
        val cutoff = sent[sent.size - 10].timestamp
        val older = sent.filter { it.timestamp < cutoff && it.id != "context-summary" }
        if (older.isEmpty()) return null
        val transcript = buildString {
            if (conversation.contextSummaryUntil > 0 && !conversation.contextSummary.isNullOrBlank()) {
                append("[Earlier summary]\n"); append(conversation.contextSummary); append("\n\n[Conversation since then]\n")
            }
            older.forEach { message ->
                val parts = parts(message)
                val text = parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }.ifBlank { message.content }
                val images = parts.count { it is ContentPart.Image }
                val label = when (message.role) { "user" -> "User"; "assistant" -> "Assistant"; else -> "System" }
                append(label); append(": "); append(text)
                if (images > 0) { if (text.isNotBlank()) append('\n'); append("[image ×$images]") }
                append('\n')
            }
        }
        return StoredSummaryPlan(cutoff, transcript, summaryPrompt, prompt,
            sent.filter { it.timestamp >= cutoff }.sumOf(::estimateMessage), conversation.contextSummary, conversation.contextSummaryUntil)
    }
}
