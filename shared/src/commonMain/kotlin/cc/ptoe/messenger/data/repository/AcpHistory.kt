/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Strict projection of the existing ACP transcript; unknown fields stay in the original store. */
internal fun projectAcpHistory(sessionId: String, rows: JsonArray): List<Message> {
    require(sessionId.startsWith("acp:")) { "Invalid ACP session" }
    val ids = mutableSetOf<String>()
    return rows.map { value ->
        val row = value as? JsonObject ?: error("Invalid ACP transcript row")
        fun text(name: String): String = (row[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: error("Invalid ACP transcript $name")
        val id = text("id").also { require(it.isNotBlank() && ids.add(it)) { "Invalid ACP message identity" } }
        val role = when (text("role")) {
            "user" -> MessageRole.USER
            "assistant" -> MessageRole.ASSISTANT
            else -> error("Unsupported ACP message role")
        }
        val status = when (text("status")) {
            "sent" -> MessageStatus.SENT
            "sending" -> MessageStatus.SENDING
            "error" -> MessageStatus.ERROR
            else -> error("Unsupported ACP message status")
        }
        val timestamp = (row["timestamp"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?: error("Invalid ACP message timestamp")
        val plainText = text("text")
        val blocks = row["content"]?.let { it as? JsonArray ?: error("Invalid ACP message content") }.orEmpty()
        val types = blocks.map { block ->
            val obj = block as? JsonObject ?: error("Invalid ACP content block")
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: error("Invalid ACP content type")
            require(type in setOf("text", "image", "resource", "audio", "resource_link")) { "Unsupported ACP content type" }
            type
        }
        val events = row["events"]?.let { it as? JsonArray ?: error("Invalid ACP message events") }.orEmpty()
        val eventTypes = events.map { event ->
            val obj = event as? JsonObject ?: error("Invalid ACP message event")
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: error("Invalid ACP message event type")
            type to obj
        }
        val content = plainText.ifBlank {
            when {
                "image" in types -> "[图片]"
                "audio" in types -> "[音频]"
                types.any { it == "resource" || it == "resource_link" } -> "[附件]"
                eventTypes.any { (type, event) -> type == "ToolUpdate" &&
                    ((event["update"] as? JsonObject)?.get("status") as? JsonPrimitive)?.contentOrNull == "failed" } -> "[工具调用失败]"
                eventTypes.any { it.first == "ToolUpdate" } -> "[工具调用]"
                eventTypes.any { it.first == "ThoughtDelta" } -> "[思考过程]"
                eventTypes.isNotEmpty() -> "[ACP 活动]"
                else -> plainText
            }
        }
        Message(id, sessionId, role, content, timestamp = timestamp, status = status,
            errorMessage = content.takeIf { status == MessageStatus.ERROR })
    }
}

/** Read every ACP page through the existing local history command without starting a worker. */
internal suspend fun readAcpHistory(core: CoreBridge, sessionId: String): List<Message> {
    var before: String? = null
    val seenCursors = mutableSetOf<String>()
    val pages = mutableListOf<JsonArray>()
    while (true) {
        val result = core.localAcpHistory(sessionId, before)
        val type = (result["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        check(type == "History") { "ACP history request failed" }
        val rows = result["messages"] as? JsonArray ?: error("Invalid ACP history page")
        pages.add(rows)
        val cursor = when (val raw = result["before"]) {
            null -> error("Missing ACP history cursor")
            JsonNull -> null
            is JsonPrimitive -> raw.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("Invalid ACP history cursor")
            else -> error("Invalid ACP history cursor")
        }
        if (cursor == null) break
        check(rows.isNotEmpty() && seenCursors.add(cursor)) { "ACP history cursor did not advance" }
        val first = (rows.first() as? JsonObject)?.get("id") as? JsonPrimitive
        check(first?.isString == true && first.content == cursor) { "Invalid ACP history cursor" }
        before = cursor
    }
    return projectAcpHistory(sessionId, JsonArray(pages.asReversed().flatMap { it }))
}
