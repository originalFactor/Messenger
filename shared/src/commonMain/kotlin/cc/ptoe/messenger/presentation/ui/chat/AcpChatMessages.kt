package cc.ptoe.messenger.presentation.ui.chat

import cc.ptoe.messenger.domain.model.ContentPart
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.presentation.ui.acp.objects
import cc.ptoe.messenger.presentation.ui.acp.text
import kotlinx.serialization.json.*

/** Project ACP transcripts into the same bubble/tool/think model as ordinary chats. */
internal fun acpChatMessages(sessionId: String, rows: List<JsonObject>, fallbackTimestamp: Long = 0L): List<Message> = rows.flatMap { row ->
    val id = row.text("id")
    val timestamp = (row["timestamp"] as? JsonPrimitive)?.longOrNull ?: fallbackTimestamp
    val status = when (row.text("status")) {
        "error" -> MessageStatus.ERROR
        "sending" -> MessageStatus.SENDING
        else -> MessageStatus.SENT
    }
    if (row.text("role") == "user") {
        listOf(Message(id, sessionId, MessageRole.USER, row.text("text"), timestamp = timestamp, status = status))
    } else {
        val thought = StringBuilder()
        val tools = linkedMapOf<String, JsonObject>()
        row.objects("events").forEach { event ->
            when (event.text("type")) {
                "ThoughtDelta" -> thought.append(event.text("text"))
                "ToolUpdate" -> {
                    val update = event["update"] as? JsonObject ?: return@forEach
                    val toolId = update.text("toolCallId")
                    if (toolId.isNotBlank()) {
                        // Tool updates are patches: omitted title/input/content must survive.
                        tools[toolId] = JsonObject(tools[toolId].orEmpty() + update.filterValues { it !is JsonNull })
                    }
                }
            }
        }
        val thinking = if (thought.isEmpty()) "" else "<think>$thought</think>\n"
        buildList {
            if (tools.isNotEmpty()) {
                add(Message("$id-tools", sessionId, MessageRole.ASSISTANT, thinking,
                    parts = tools.map { (toolId, tool) -> ContentPart.ToolCall(toolId, tool.text("title").ifBlank { toolId }, tool["rawInput"]?.toString() ?: "{}") },
                    timestamp = timestamp, status = MessageStatus.SENT))
                tools.forEach { (toolId, tool) ->
                    if (tool.text("status") in listOf("completed", "failed")) {
                        val output = tool["content"]?.let(::acpContentText).orEmpty().ifBlank { tool["rawOutput"]?.toString().orEmpty() }
                        add(Message("$id-tool-$toolId", sessionId, MessageRole.TOOL, output,
                            parts = listOf(ContentPart.ToolResult(toolId, tool.text("title").ifBlank { toolId }, output, tool.text("status") == "failed")),
                            timestamp = timestamp, status = MessageStatus.SENT))
                    }
                }
            }
            add(Message(id, sessionId, MessageRole.ASSISTANT, (if (tools.isEmpty()) thinking else "") + row.text("text"),
                timestamp = timestamp, status = status, errorMessage = if (status == MessageStatus.ERROR) row.text("text") else null))
        }
    }
}

internal fun acpContentText(content: JsonElement): String = when (content) {
    is JsonArray -> content.joinToString("\n\n") { acpContentText(it) }
    is JsonObject -> when (content.text("type")) {
        "text" -> content.text("text")
        "diff" -> "`${content.text("path")}`\n```diff\n" +
            content.text("oldText").lines().joinToString("\n") { "-$it" } + "\n" +
            content.text("newText").lines().joinToString("\n") { "+$it" } + "\n```"
        else -> content["content"]?.let { acpContentText(it) } ?: content.text("text")
    }
    else -> (content as? JsonPrimitive)?.contentOrNull.orEmpty()
}
