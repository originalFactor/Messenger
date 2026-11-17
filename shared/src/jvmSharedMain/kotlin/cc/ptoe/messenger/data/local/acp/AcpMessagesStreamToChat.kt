/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.util.UUID
import kotlinx.serialization.json.*

/** One sequentially used instance per Messages stream. Input values are already framed SSE data. */
internal class AcpMessagesStreamToChat(
    private val model: String,
    private val decoder: AcpNativeToChat = AcpNativeToChat(),
    responseId: () -> String = { "chatcmpl_" + UUID.randomUUID().toString() },
) {
    private enum class Kind { THINKING, TEXT, TOOL }
    private class Block(val index: Int, val kind: Kind) {
        var id: String? = null
        var name: String? = null
        var initialInput: JsonObject? = null
        val content = StringBuilder()
        var hasFragments = false
        var closed = false
        var arguments: String? = null
        var parsed: JsonObject? = null
    }
    private val id = responseId().also { require(it.startsWith("chatcmpl_") && it.length in 10..256 && it.none(Char::isISOControl)) { "Invalid response ID" } }
    private val blocks = mutableListOf<Block>()
    private val callIds = mutableSetOf<String>()
    private val usage = mutableMapOf<String, JsonElement>()
    private var sawUsage = false
    private var phase = 0
    private var started = false
    private var active: Block? = null
    private var reason: String? = null
    private var eventCount = 0
    private var totalBytes = 0L
    private var terminal = false
    private var broken = false
    val isFinished: Boolean get() = terminal

    /** Exceptions poison this instance; the caller must emit fail() instead of resuming it. */
    fun feed(data: String): String {
        check(!terminal && !broken) { "Messages stream is already finished or invalid" }
        return try {
            require(data.encodeToByteArray().size <= 8 * 1024 * 1024) { "Upstream SSE event exceeds limit" }
            require(++eventCount <= 65_536) { "Too many Messages events" }
            require(data != "[DONE]") { "Upstream ended without a Messages terminal event" }
            val event = try { AcpRelayStrictJson.parse(data) as? JsonObject } catch (_: Exception) { null } ?: error("Invalid upstream SSE JSON")
            when (event.required("type")) {
                "message_start" -> { keys(event, setOf("type", "message"), "Messages event"); start(event["message"]); "" }
                "content_block_start" -> { keys(event, setOf("type", "index", "content_block"), "Messages event"); blockStart(event) }
                "content_block_delta" -> { keys(event, setOf("type", "index", "delta"), "Messages event"); blockDelta(event) }
                "content_block_stop" -> { keys(event, setOf("type", "index"), "Messages event"); blockStop(event); "" }
                "message_delta" -> { keys(event, setOf("type", "delta", "usage"), "Messages event"); messageDelta(event); "" }
                "message_stop" -> { keys(event, setOf("type"), "Messages event"); finish() }
                "ping" -> { keys(event, setOf("type"), "Messages event"); "" }
                "error" -> error("Upstream Messages stream failed")
                else -> error("Unsupported Messages event")
            }
        } catch (failure: Exception) { broken = true; throw failure }
    }
    fun endOfStream(): String = if (terminal) "" else fail("Upstream stream ended before its terminal event")
    /** The caller must pass a sanitized message. */
    fun fail(message: String): String {
        if (terminal) return ""
        terminal = true
        return "data: " + obj("error" to obj("code" to str("upstream_error"), "message" to str(message.take(2048)))) + "\n\n"
    }

    private fun start(raw: JsonElement?) {
        check(!started) { "Duplicate Messages start" }
        val message = raw as? JsonObject ?: error("Messages event lacks message")
        keys(message, setOf("id", "type", "role", "model", "content", "stop_reason", "stop_sequence", "usage"), "initial Messages message")
        message.value("id")?.let { require(it.string()?.isNotEmpty() == true) { "Invalid message ID" } }
        message.value("model")?.let { require(it.string()?.isNotEmpty() == true) { "Invalid message model" } }
        message.value("type")?.let { require(it.string() == "message") { "Invalid Messages type" } }
        require(message.required("role") == "assistant") { "Unsupported Messages role" }
        require(message.value("stop_reason") == null && message.value("stop_sequence") == null) { "Unexpected initial Messages finish state" }
        message.value("content")?.let { require(it is JsonArray && it.isEmpty()) { "Initial Messages content cannot be preserved" } }
        message.value("usage")?.let(::mergeUsage)
        started = true
    }
    private fun blockStart(event: JsonObject): String {
        check(started && reason == null && active == null) { "Messages block started in an invalid state" }
        val index = event.index()
        require(index == blocks.size && blocks.size < 256) { "Invalid Messages block index" }
        val raw = event["content_block"] as? JsonObject ?: error("Messages block is missing")
        val kind = when (raw.required("type")) {
            "thinking" -> { require(phase == 0) { "Interleaved thinking cannot be preserved" }; Kind.THINKING }
            "text" -> { require(phase <= 1) { "Interleaved text cannot be preserved" }; phase = 1; Kind.TEXT }
            "tool_use" -> { phase = 2; Kind.TOOL }
            else -> error("Unsupported Messages content block")
        }
        val block = Block(index, kind); blocks += block; active = block
        return when (kind) {
            Kind.THINKING -> {
                keys(raw, setOf("type", "thinking", "signature"), "thinking block")
                require(raw.value("signature") == null) { "Thinking signatures cannot be preserved" }
                val value = raw["thinking"].string() ?: error("Thinking must be text")
                if (value.isEmpty()) chunk(obj("reasoning_content" to str("")), null, null) else addText(block, value)
            }
            Kind.TEXT -> {
                keys(raw, setOf("type", "text", "citations"), "text block")
                emptyArray(raw, "citations")
                addText(block, raw["text"].string() ?: error("Text block requires text"))
            }
            Kind.TOOL -> {
                keys(raw, setOf("type", "id", "name", "input"), "tool block")
                val callId = raw.required("id"); val name = raw.required("name")
                require(callId.length <= 1024 && name.length <= 1024 && callIds.size < 128 && callIds.add(callId)) { "Too many or duplicate tool calls" }
                block.id = callId; block.name = name
                block.initialInput = raw["input"] as? JsonObject ?: error("Tool input must be an object")
                ""
            }
        }
    }
    private fun blockDelta(event: JsonObject): String {
        check(started && reason == null) { "Messages delta arrived after finish" }
        val block = active ?: error("Messages block is unavailable")
        require(event.index() == block.index && !block.closed) { "Messages block index changed" }
        val delta = event["delta"] as? JsonObject ?: error("Messages delta is missing")
        return when (delta.required("type")) {
            "text_delta" -> { keys(delta, setOf("type", "text"), "text delta"); require(block.kind == Kind.TEXT) { "Invalid text delta" }; addText(block, delta["text"].string() ?: error("Text delta is not text")) }
            "thinking_delta" -> { keys(delta, setOf("type", "thinking"), "thinking delta"); require(block.kind == Kind.THINKING) { "Invalid thinking delta" }; addText(block, delta["thinking"].string() ?: error("Thinking delta is not text")) }
            "input_json_delta" -> {
                keys(delta, setOf("type", "partial_json"), "tool delta"); require(block.kind == Kind.TOOL && checkNotNull(block.initialInput).isEmpty()) { "Invalid tool delta" }
                val value = delta["partial_json"].string() ?: error("Tool arguments are not text")
                account(block.content, value, 1024 * 1024); if (value.isNotEmpty()) block.hasFragments = true; ""
            }
            else -> error("Unsupported Messages content delta")
        }
    }
    private fun blockStop(event: JsonObject) {
        check(started && reason == null) { "Messages block stopped in an invalid state" }
        val block = active ?: error("Messages block is unavailable")
        require(event.index() == block.index && !block.closed) { "Messages block index changed" }
        if (block.kind == Kind.TOOL) {
            val raw = if (block.hasFragments) block.content.toString() else checkNotNull(block.initialInput).toString()
            require(raw.encodeToByteArray().size <= 1024 * 1024) { "Tool arguments exceed limit" }
            val value = try { AcpRelayStrictJson.parse(raw) } catch (_: Exception) { error("Invalid function arguments") }
            block.parsed = value as? JsonObject ?: error("Function arguments must be an object")
            if (!block.hasFragments) {
                totalBytes += raw.encodeToByteArray().size
                require(totalBytes <= 8L * 1024 * 1024) { "Messages stream exceeds limit" }
            }
            block.arguments = raw
        }
        block.closed = true; active = null
    }
    private fun messageDelta(event: JsonObject) {
        check(started && active == null) { "Messages finish arrived during an active block" }
        val delta = event["delta"] as? JsonObject ?: error("Messages finish delta is missing")
        keys(delta, setOf("stop_reason", "stop_sequence"), "Messages finish delta")
        require(delta.value("stop_sequence") == null) { "Stop sequence cannot be preserved" }
        delta.value("stop_reason")?.let { raw ->
            val value = raw.string() ?: error("Invalid Messages stop reason")
            require(value in setOf("end_turn", "tool_use", "max_tokens")) { "Unsupported Messages stop reason" }
            require(reason == null || reason == value) { "Conflicting Messages stop reasons" }
            reason = value
        }
        event.value("usage")?.let(::mergeUsage)
    }
    private fun mergeUsage(raw: JsonElement) {
        val value = raw as? JsonObject ?: error("Invalid Messages usage")
        keys(value, setOf("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens", "cache_creation"), "Messages usage")
        for ((key, entry) in value) {
            if (entry == JsonNull) continue
            if (key == "cache_creation") {
                val details = entry as? JsonObject ?: error("Invalid cache creation details")
                keys(details, setOf("ephemeral_5m_input_tokens", "ephemeral_1h_input_tokens"), "cache creation details")
                val merged = (usage[key] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                for ((name, count) in details) {
                    if (count == JsonNull) continue
                    val number = integer(count, name)
                    require(number == 0L) { "Cache creation token usage cannot be preserved" }
                    val old = merged[name]?.let { integer(it, name) }
                    require(old == null || number >= old) { "Messages usage decreased" }
                    merged[name] = JsonPrimitive(number)
                }
                usage[key] = JsonObject(merged)
            } else {
                val number = integer(entry, key)
                if (key == "cache_creation_input_tokens") require(number == 0L) { "Cache creation token usage cannot be preserved" }
                val old = usage[key]?.let { integer(it, key) }
                require(old == null || number >= old) { "Messages usage decreased" }
                usage[key] = JsonPrimitive(number)
            }
        }
        sawUsage = true
    }

    private fun finish(): String {
        check(started && active == null && blocks.all { it.closed }) { "Messages stream ended with an incomplete block" }
        val stopReason = reason ?: error("Messages stream ended without a stop reason")
        val content = JsonArray(blocks.map { block ->
            when (block.kind) {
                Kind.THINKING -> obj("type" to str("thinking"), "thinking" to str(block.content.toString()))
                Kind.TEXT -> obj("type" to str("text"), "text" to str(block.content.toString()))
                Kind.TOOL -> obj("type" to str("tool_use"), "id" to str(checkNotNull(block.id)), "name" to str(checkNotNull(block.name)), "input" to checkNotNull(block.parsed))
            }
        })
        val message = mutableMapOf<String, JsonElement>("role" to str("assistant"), "stop_reason" to str(stopReason), "content" to content)
        if (sawUsage) message["usage"] = JsonObject(usage)
        val decoded = decoder.convert("messages", JsonObject(message), model)
        val result = decoded.getValue("choices").jsonArray.single().jsonObject
        val finish = result.required("finish_reason")
        val tools = blocks.filter { it.kind == Kind.TOOL }
        val out = StringBuilder()
        if (tools.isNotEmpty()) {
            val values = tools.mapIndexed { index, block -> obj("index" to num(index), "id" to str(checkNotNull(block.id)), "type" to str("function"),
                "function" to obj("name" to str(checkNotNull(block.name)), "arguments" to str(checkNotNull(block.arguments)))) }
            out.append(chunk(obj("tool_calls" to JsonArray(values)), null, null))
        }
        out.append(chunk(JsonObject(emptyMap()), finish, decoded["usage"] as? JsonObject))
        out.append("data: [DONE]\n\n")
        terminal = true
        return out.toString()
    }
    private fun addText(block: Block, value: String): String {
        if (value.isEmpty()) return ""
        account(block.content, value, 4 * 1024 * 1024)
        val field = if (block.kind == Kind.THINKING) "reasoning_content" else "content"
        return chunk(obj(field to str(value)), null, null)
    }
    private fun account(target: StringBuilder, value: String, max: Int) {
        val bytes = value.encodeToByteArray().size
        require(target.length.toLong() + value.length <= max && totalBytes + bytes <= 8L * 1024 * 1024) { "Messages stream exceeds limit" }
        target.append(value); totalBytes += bytes
    }
    private fun chunk(delta: JsonObject, finish: String?, usage: JsonObject?): String {
        val choice = obj("index" to num(0), "delta" to delta, "finish_reason" to (finish?.let(::str) ?: JsonNull))
        val body = mutableMapOf<String, JsonElement>("id" to str(id), "object" to str("chat.completion.chunk"), "model" to str(model), "choices" to JsonArray(listOf(choice)))
        usage?.let { body["usage"] = it }
        return "data: ${JsonObject(body)}\n\n"
    }
    private fun JsonObject.index(): Int {
        val value = this["index"]?.let { integer(it, "block index") } ?: error("Messages block index is missing")
        require(value <= Int.MAX_VALUE) { "Invalid Messages block index" }
        return value.toInt()
    }
    private fun integer(value: JsonElement, name: String): Long = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 } ?: error("Invalid $name")
    private fun emptyArray(obj: JsonObject, key: String) {
        obj.value(key)?.let { raw -> val array = raw as? JsonArray ?: error("$key must be an array"); require(array.isEmpty()) { "$key cannot be preserved" } }
    }
    private fun keys(value: JsonObject, allowed: Set<String>, context: String) {
        for ((key, item) in value) require(item == JsonNull || key in allowed) { "Unsupported $context field: $key" }
    }
    private fun JsonObject.required(key: String): String = this[key].string()?.takeIf { it.isNotEmpty() } ?: error("Expected a non-empty $key")
    private fun JsonObject.value(key: String): JsonElement? = this[key]?.takeUnless { it == JsonNull }
    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun obj(vararg values: Pair<String, JsonElement>) = JsonObject(mapOf(*values))
    private fun str(value: String) = JsonPrimitive(value)
    private fun num(value: Int) = JsonPrimitive(value)
}
