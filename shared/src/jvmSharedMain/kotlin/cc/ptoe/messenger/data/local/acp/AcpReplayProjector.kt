/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import cc.ptoe.messenger.data.util.randomUuid
import kotlinx.serialization.json.*

/** Reconstructs ACP session/load events without touching current history until load succeeds. */
internal class AcpReplayProjector(private val now: () -> Long) {
    private val messages = mutableListOf<JsonObject>()
    private var size = 0L
    private var updates = 0
    fun accept(update: JsonObject) {
        val encoded = update.toString().encodeToByteArray().size
        size += encoded
        require(size <= 16 * 1024 * 1024 && ++updates <= 10_000 && messages.size <= 10_000) { "ACP replay exceeds limit" }
        val kind = (update["sessionUpdate"] as? JsonPrimitive)?.contentOrNull ?: return
        val text = ((update["content"] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val role = when (kind) {
            "user_message_chunk" -> "user"
            "agent_message_chunk", "agent_thought_chunk", "tool_call", "tool_call_update" -> "assistant"
            else -> return
        }
        val providedId = (update["messageId"] as? JsonPrimitive)?.contentOrNull
        var row = messages.lastOrNull()?.takeIf { previous ->
            previous["role"]?.jsonPrimitive?.contentOrNull == role && (providedId == null || previous["id"]?.jsonPrimitive?.contentOrNull == providedId)
        }
        if (row == null) {
            row = buildJsonObject { put("id", providedId ?: randomUuid()); put("role", role); put("text", ""); put("status", "sent"); put("timestamp", now()); if (role == "assistant") put("events", JsonArray(emptyList())) }
            messages.add(row)
        }
        val extra = when (kind) {
            "agent_message_chunk" -> buildJsonObject { put("type", "TextDelta"); put("text", text) }
            "agent_thought_chunk" -> buildJsonObject { put("type", "ThoughtDelta"); put("text", text) }
            "tool_call", "tool_call_update" -> buildJsonObject { put("type", "ToolUpdate"); put("update", update) }
            else -> null
        }
        val currentText = row["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val nextText = if (kind == "user_message_chunk" || kind == "agent_message_chunk") currentText + text else currentText
        val entries = (row["events"] as? JsonArray).orEmpty() + listOfNotNull(extra)
        messages[messages.lastIndex] = JsonObject(row + mapOf("text" to JsonPrimitive(nextText)) + if (role == "assistant") mapOf("events" to JsonArray(entries)) else emptyMap())
    }
    fun snapshot(): JsonArray = JsonArray(messages.toList())
}
