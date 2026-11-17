/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.util.UUID
import kotlinx.serialization.json.*

/** One instance per upstream stream, used serially. Input is an already-framed SSE data value. */
internal class AcpChatResponsesStream(
    private val model: String,
    customTools: Set<String>,
    responseId: () -> String = { "resp_" + UUID.randomUUID().toString().replace("-", "") },
    nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private class Call { val id = StringBuilder(); val name = StringBuilder(); val arguments = StringBuilder() }
    private data class FinalCall(val id: String, val name: String, val arguments: String, val customInput: String?)
    private val custom = customTools.toSet()
    private val id = responseId().also { require(it.startsWith("resp_") && it.length in 6..256 && it.none(Char::isISOControl)) { "Invalid response ID" } }
    private val created = nowSeconds().also { require(it >= 0) { "Invalid response timestamp" } }
    private val calls = sortedMapOf<Long, Call>()
    private val output = mutableListOf<JsonObject>()
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private var textOpen = false
    private var reasoningOpen = false
    private var totalBytes = 0L
    private var usage: JsonObject? = null
    private var reason: String? = null
    private var sequence = 0L
    private var started = false
    private var terminal = false
    private var broken = false
    val isFinished: Boolean get() = terminal

    fun start(): String {
        check(!started && !terminal) { "ACP relay stream already started" }
        started = true
        return event("response.created", obj("response" to response("in_progress")))
    }

    /** On an exception the caller must emit fail(); this instance cannot resume or succeed. */
    fun feed(data: String): String {
        if (terminal) return ""
        check(started) { "ACP relay stream has not started" }
        check(!broken) { "ACP relay stream is invalid" }
        return try {
            require(data.encodeToByteArray().size <= 8 * 1024 * 1024) { "Upstream SSE event exceeds limit" }
            if (data == "[DONE]") finish() else feedChunk(data)
        } catch (failure: Exception) {
            broken = true
            throw failure
        }
    }

    /** An EOF without [DONE] is always an error, even after finish_reason. */
    fun endOfStream(): String = fail("Upstream stream ended before its terminal event")

    /** Emits at most one error terminal; the caller should pass a sanitized message. */
    fun fail(message: String): String {
        if (terminal) return ""
        check(started) { "ACP relay stream has not started" }
        terminal = true
        val failed = JsonObject(response("failed") + ("error" to obj("code" to JsonPrimitive("upstream_error"), "message" to JsonPrimitive(message.take(2048)))))
        return event("response.failed", obj("response" to failed))
    }

    private fun feedChunk(data: String): String {
        val chunk = try { AcpRelayStrictJson.parse(data) as? JsonObject } catch (_: Exception) { null }
            ?: error("Invalid upstream SSE JSON")
        keys(chunk, setOf("id", "object", "created", "model", "choices", "usage", "error"), "upstream chunk")
        if (chunk.value("error") != null) error("Upstream stream error")
        for (key in listOf("id", "object", "model")) chunk.value(key)?.let { require(it.string() != null) { "Invalid upstream $key" } }
        chunk.value("object")?.let { require(it.string() == "chat.completion.chunk") { "Invalid upstream object" } }
        chunk.value("created")?.let { integer(it, "created") }
        chunk.value("usage")?.let { usage = parseUsage(it) }
        val choices = chunk["choices"] as? JsonArray ?: error("Upstream chunk lacks choices")
        require(choices.size <= 1) { "Multiple upstream choices cannot be converted" }
        val choice = choices.firstOrNull() ?: return ""
        val row = choice as? JsonObject ?: error("Invalid upstream choice")
        keys(row, setOf("index", "delta", "finish_reason"), "upstream choice")
        row.value("index")?.let { integer(it, "choice index") }
        val finish = row.value("finish_reason")?.let { it.string() ?: error("Invalid finish reason") }
        if (finish != null) {
            require(finish in setOf("stop", "tool_calls", "length")) { "Unsupported upstream finish reason: $finish" }
            require(reason == null || reason == finish) { "Conflicting upstream finish reasons" }
        }
        val delta = row["delta"] as? JsonObject ?: error("Upstream chunk lacks delta")
        keys(delta, setOf("role", "content", "reasoning_content", "reasoning", "tool_calls", "refusal", "annotations"), "upstream delta")
        delta.value("role")?.let { require(it.string() == "assistant") { "Upstream returned an unsupported role" } }
        require(delta.value("refusal") == null) { "Upstream refusal cannot be converted" }
        delta.value("annotations")?.let { raw ->
            val annotations = raw as? JsonArray ?: error("Upstream annotations must be an array")
            require(annotations.isEmpty()) { "Upstream annotations cannot be converted" }
        }
        val firstReasoning = delta.value("reasoning_content")
        val secondReasoning = delta.value("reasoning")
        require(firstReasoning == null || secondReasoning == null) { "Conflicting upstream reasoning fields" }
        val newReasoning = (firstReasoning ?: secondReasoning)?.let { it.string() ?: error("Upstream reasoning delta is not text") }
        val newText = delta.value("content")?.let { it.string() ?: error("Upstream content delta is not text") }
        val newCalls = delta.value("tool_calls")?.let { it as? JsonArray ?: error("Invalid upstream tool calls") }
        if (reason != null) require(newReasoning.isNullOrEmpty() && newText.isNullOrEmpty() && newCalls.isNullOrEmpty()) { "Upstream emitted content after finish reason" }
        val out = StringBuilder()
        if (!newReasoning.isNullOrEmpty()) out.append(reasoningDelta(newReasoning))
        if (!newText.isNullOrEmpty()) out.append(textDelta(newText))
        newCalls?.let { list -> for (entry in list) appendCall(entry) }
        if (finish != null) reason = finish
        return out.toString()
    }

    private fun appendCall(value: JsonElement) {
        val call = value as? JsonObject ?: error("Invalid upstream tool call")
        keys(call, setOf("index", "id", "type", "function"), "upstream tool call")
        call.value("type")?.let { require(it.string() == "function") { "Upstream returned an unsupported tool call type" } }
        val index = call["index"]?.let { integer(it, "tool call index") } ?: error("Missing tool call index")
        if (index !in calls) require(calls.size < 128) { "Too many upstream tool calls" }
        val target = calls.getOrPut(index) { Call() }
        call.value("id")?.let { appendBounded(target.id, it.string() ?: error("Invalid tool call ID"), 1024) }
        call.value("function")?.let { value ->
            val function = value as? JsonObject ?: error("Invalid upstream function call")
            keys(function, setOf("name", "arguments"), "upstream function call")
            function.value("name")?.let { appendBounded(target.name, it.string() ?: error("Invalid tool name"), 1024) }
            function.value("arguments")?.let { appendBounded(target.arguments, it.string() ?: error("Invalid tool arguments"), 1024 * 1024) }
        }
    }

    private fun appendBounded(target: StringBuilder, value: String, max: Int) {
        val bytes = value.encodeToByteArray().size
        require(target.length.toLong() + value.length <= max && totalBytes + bytes <= 8L * 1024 * 1024) { "Upstream response exceeds limit" }
        target.append(value); totalBytes += bytes
    }

    private fun reasoningDelta(value: String): String {
        val out = StringBuilder(closeText())
        val index = output.size
        val itemId = "rs_${id}_$index"
        if (!reasoningOpen) {
            reasoningOpen = true
            out.append(event("response.output_item.added", obj("output_index" to num(index), "item" to obj("id" to str(itemId), "type" to str("reasoning"), "summary" to JsonArray(emptyList())))))
            out.append(event("response.reasoning_summary_part.added", obj("item_id" to str(itemId), "output_index" to num(index), "summary_index" to num(0), "part" to obj("type" to str("summary_text"), "text" to str("")))))
        }
        appendBounded(reasoning, value, 4 * 1024 * 1024)
        out.append(event("response.reasoning_summary_text.delta", obj("item_id" to str(itemId), "output_index" to num(index), "summary_index" to num(0), "delta" to str(value))))
        return out.toString()
    }

    private fun textDelta(value: String): String {
        val out = StringBuilder(closeReasoning())
        val index = output.size
        val itemId = "msg_${id}_$index"
        if (!textOpen) {
            textOpen = true
            out.append(event("response.output_item.added", obj("output_index" to num(index), "item" to obj("id" to str(itemId), "type" to str("message"), "role" to str("assistant"), "status" to str("in_progress"), "content" to JsonArray(emptyList())))))
            out.append(event("response.content_part.added", obj("item_id" to str(itemId), "output_index" to num(index), "content_index" to num(0), "part" to obj("type" to str("output_text"), "text" to str(""), "annotations" to JsonArray(emptyList())))))
        }
        appendBounded(text, value, 4 * 1024 * 1024)
        out.append(event("response.output_text.delta", obj("item_id" to str(itemId), "output_index" to num(index), "content_index" to num(0), "delta" to str(value), "logprobs" to JsonArray(emptyList()))))
        return out.toString()
    }

    private fun closeReasoning(): String {
        if (!reasoningOpen) return ""
        require(output.size < 10_000) { "Too many upstream output items" }
        reasoningOpen = false
        val index = output.size; val itemId = "rs_${id}_$index"; val value = reasoning.toString(); reasoning.setLength(0)
        val part = obj("type" to str("summary_text"), "text" to str(value))
        val item = obj("type" to str("reasoning"), "id" to str(itemId), "summary" to JsonArray(listOf(part)))
        val out = event("response.reasoning_summary_text.done", obj("item_id" to str(itemId), "output_index" to num(index), "summary_index" to num(0), "text" to str(value))) +
            event("response.reasoning_summary_part.done", obj("item_id" to str(itemId), "output_index" to num(index), "summary_index" to num(0), "part" to part)) +
            event("response.output_item.done", obj("output_index" to num(index), "item" to item))
        output += item
        return out
    }

    private fun closeText(): String {
        if (!textOpen) return ""
        require(output.size < 10_000) { "Too many upstream output items" }
        textOpen = false
        val index = output.size; val itemId = "msg_${id}_$index"; val value = text.toString(); text.setLength(0)
        val part = obj("type" to str("output_text"), "text" to str(value), "annotations" to JsonArray(emptyList()))
        val item = obj("id" to str(itemId), "type" to str("message"), "role" to str("assistant"), "status" to str("completed"), "content" to JsonArray(listOf(part)))
        val out = event("response.output_text.done", obj("item_id" to str(itemId), "output_index" to num(index), "content_index" to num(0), "text" to str(value))) +
            event("response.content_part.done", obj("item_id" to str(itemId), "output_index" to num(index), "content_index" to num(0), "part" to part)) +
            event("response.output_item.done", obj("output_index" to num(index), "item" to item))
        output += item
        return out
    }

    private fun finish(): String {
        val reason = reason ?: error("Upstream ended without a finish reason")
        val completeCalls = calls.values.map { call ->
            val callId = call.id.toString(); val name = call.name.toString(); val args = call.arguments.toString()
            require(callId.isNotEmpty() && name.isNotEmpty()) { "Incomplete upstream tool call" }
            FinalCall(callId, name, args, if (name in custom) customInput(args) else null)
        }
        val out = StringBuilder(closeReasoning()).append(closeText())
        for (call in completeCalls) {
            require(output.size < 10_000) { "Too many upstream output items" }
            val index = output.size; val itemId = "fc_${id}_$index"; val isCustom = call.name in custom
            val item = if (isCustom) obj("type" to str("custom_tool_call"), "id" to str(itemId), "call_id" to str(call.id), "name" to str(call.name), "input" to str(checkNotNull(call.customInput)))
                else obj("type" to str("function_call"), "id" to str(itemId), "call_id" to str(call.id), "name" to str(call.name), "arguments" to str(call.arguments), "status" to str("completed"))
            val initial = if (isCustom) JsonObject(item + ("input" to str(""))) else JsonObject(item + mapOf("arguments" to str(""), "status" to str("in_progress")))
            out.append(event("response.output_item.added", obj("output_index" to num(index), "item" to initial)))
            if (isCustom) {
                out.append(event("response.custom_tool_call_input.delta", obj("item_id" to str(itemId), "output_index" to num(index), "delta" to str(checkNotNull(call.customInput)))))
                out.append(event("response.custom_tool_call_input.done", obj("item_id" to str(itemId), "output_index" to num(index), "input" to str(checkNotNull(call.customInput)))))
            } else {
                out.append(event("response.function_call_arguments.delta", obj("item_id" to str(itemId), "output_index" to num(index), "delta" to str(call.arguments))))
                out.append(event("response.function_call_arguments.done", obj("item_id" to str(itemId), "output_index" to num(index), "arguments" to str(call.arguments))))
            }
            out.append(event("response.output_item.done", obj("output_index" to num(index), "item" to item))); output += item
        }
        val status = if (reason == "length") "incomplete" else "completed"
        out.append(event(if (status == "incomplete") "response.incomplete" else "response.completed", obj("response" to response(status))))
        terminal = true
        return out.toString()
    }

    private fun response(status: String): JsonObject {
        val values = mutableMapOf<String, JsonElement>("id" to str(id), "object" to str("response"), "created_at" to JsonPrimitive(created), "status" to str(status), "model" to str(model),
            "output" to JsonArray(output.toList()), "error" to JsonNull, "incomplete_details" to if (status == "incomplete") obj("reason" to str("max_output_tokens")) else JsonNull)
        usage?.let { values["usage"] = it }
        return JsonObject(values)
    }
    private fun event(type: String, fields: JsonObject): String {
        check(sequence < 65_536L || type == "response.failed") { "Too many upstream response events" }
        val data = JsonObject(fields + mapOf("type" to str(type), "sequence_number" to JsonPrimitive(sequence++)))
        return "event: $type\ndata: $data\n\n"
    }

    private fun parseUsage(value: JsonElement): JsonObject {
        val data = value as? JsonObject ?: error("Invalid upstream usage")
        keys(data, setOf("prompt_tokens", "completion_tokens", "total_tokens", "prompt_tokens_details", "completion_tokens_details"), "upstream usage")
        val input = data.value("prompt_tokens")?.let { integer(it, "prompt tokens") } ?: 0L
        val output = data.value("completion_tokens")?.let { integer(it, "completion tokens") } ?: 0L
        val total = data.value("total_tokens")?.let { integer(it, "total tokens") } ?: Math.addExact(input, output)
        fun detail(key: String, field: String): JsonElement {
            val raw = data.value(key) ?: return num(0)
            val details = raw as? JsonObject ?: error("Invalid upstream usage details")
            keys(details, setOf(field), "upstream usage details")
            val entry = details[field] ?: return num(0)
            return if (entry == JsonNull) JsonNull else JsonPrimitive(integer(entry, field))
        }
        return obj("input_tokens" to JsonPrimitive(input), "output_tokens" to JsonPrimitive(output), "total_tokens" to JsonPrimitive(total),
            "input_tokens_details" to obj("cached_tokens" to detail("prompt_tokens_details", "cached_tokens")),
            "output_tokens_details" to obj("reasoning_tokens" to detail("completion_tokens_details", "reasoning_tokens")))
    }
    private fun customInput(raw: String): String {
        var index = 0
        fun space() { while (index < raw.length && raw[index] in " \t\r\n") index++ }
        fun take(c: Char) { space(); require(index < raw.length && raw[index] == c) { "Invalid custom tool arguments" }; index++ }
        fun quoted(): String {
            space(); val start = index
            require(index < raw.length && raw[index++] == '"') { "Custom tool arguments require input text" }
            while (index < raw.length) {
                val c = raw[index++]
                if (c == '\\') { require(index < raw.length) { "Invalid custom tool arguments" }; index++ }
                else if (c == '"') {
                    val parsed = try { Json.parseToJsonElement(raw.substring(start, index)) } catch (_: Exception) { error("Invalid custom tool arguments") }
                    return parsed.string() ?: error("Custom tool arguments require input text")
                }
            }
            error("Invalid custom tool arguments")
        }
        take('{'); require(quoted() == "input") { "Custom tool arguments require input text" }; take(':')
        val result = quoted(); take('}'); space(); require(index == raw.length) { "Invalid custom tool arguments" }
        return result
    }
    /** Bound nesting and reject duplicate names before the JSON parser could discard them. */
    private fun keys(value: JsonObject, allowed: Set<String>, context: String) {
        for ((key, item) in value) require(item == JsonNull || key in allowed) { "Unsupported $context field: $key" }
    }
    private fun JsonObject.value(key: String): JsonElement? = this[key]?.takeUnless { it == JsonNull }
    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun integer(value: JsonElement, name: String): Long = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 } ?: error("Invalid upstream $name")
    private fun obj(vararg values: Pair<String, JsonElement>) = JsonObject(mapOf(*values))
    private fun str(value: String) = JsonPrimitive(value)
    private fun num(value: Int) = JsonPrimitive(value)
}
