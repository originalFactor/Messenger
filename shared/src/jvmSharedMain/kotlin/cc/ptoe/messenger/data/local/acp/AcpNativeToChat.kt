/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.util.UUID
import kotlinx.serialization.json.*

/** Converts only non-stream replies whose content, ordering and accounting can be represented. */
internal class AcpNativeToChat(private val responseId: () -> String = { "chatcmpl_" + UUID.randomUUID().toString() }) {
    private data class Call(val id: String, val name: String, val arguments: String)
    private data class Parsed(val text: String, val reasoning: String?, val calls: List<Call>)
    private class Collector {
        private val content = StringBuilder()
        private val thought = StringBuilder()
        private val calls = mutableListOf<Call>()
        private val callIds = mutableSetOf<String>()
        private var phase = 0 // reasoning, text, tools: Chat cannot retain another order.
        private var thoughtSeen = false
        private var segments = 0
        private var bytes = 0L
        private fun account(vararg values: String) {
            require(++segments <= 10_000) { "Too many upstream output items" }
            for (value in values) {
                bytes += value.encodeToByteArray().size
                require(bytes <= 8L * 1024 * 1024) { "Upstream response exceeds limit" }
            }
        }
        fun reasoning(value: String) { require(phase == 0) { "Interleaved reasoning cannot be preserved" }; account(value); thoughtSeen = true; thought.append(value) }
        fun text(value: String) { require(phase <= 1) { "Interleaved text cannot be preserved" }; account(value); phase = 1; content.append(value) }
        fun call(value: Call) {
            require(calls.size < 128 && callIds.add(value.id)) { "Too many or duplicate tool calls" }
            account(value.id, value.name, value.arguments); phase = 2; calls += value
        }
        fun result() = Parsed(content.toString(), if (thoughtSeen) thought.toString() else null, calls.toList())
    }

    fun convert(source: String, payload: JsonObject, model: String): JsonObject {
        require(source in setOf("responses", "messages", "interactions")) { "Unsupported source format" }
        val (parsed, finish) = when (source) {
            "responses" -> responses(payload)
            "messages" -> messages(payload)
            else -> interactions(payload)
        }
        val message = mutableMapOf<String, JsonElement>("role" to str("assistant"), "content" to str(parsed.text))
        parsed.reasoning?.let { message["reasoning_content"] = str(it) }
        if (parsed.calls.isNotEmpty()) message["tool_calls"] = JsonArray(parsed.calls.map { call ->
            obj("id" to str(call.id), "type" to str("function"), "function" to obj("name" to str(call.name), "arguments" to str(call.arguments)))
        })
        val choice = obj("index" to num(0), "message" to JsonObject(message), "finish_reason" to str(finish))
        val id = responseId()
        require(id.startsWith("chatcmpl_") && id.length in 10..256 && id.none(Char::isISOControl)) { "Invalid response ID" }
        val result = mutableMapOf<String, JsonElement>("id" to str(id), "object" to str("chat.completion"), "model" to str(model), "choices" to JsonArray(listOf(choice)))
        payload.value("usage")?.let { result["usage"] = usage(source, it, parsed.reasoning != null) }
        return JsonObject(result)
    }

    private fun responses(payload: JsonObject): Pair<Parsed, String> {
        keys(payload, setOf("id", "object", "created_at", "model", "status", "output", "error", "incomplete_details", "usage"), "Responses response")
        payload.value("error")?.let { error("Upstream response contains an error") }
        stringMetadata(payload, "id", "model")
        payload.value("created_at")?.let { count(it, "created_at") }
        payload.value("object")?.let { require(it.string() == "response") { "Invalid Responses object" } }
        val status = payload.required("status")
        val reason = when (status) {
            "completed" -> { require(payload.value("incomplete_details") == null) { "Unexpected incomplete details" }; "stop" }
            "incomplete" -> { incomplete(payload.value("incomplete_details")); "length" }
            else -> error("Unsupported Responses status")
        }
        val items = payload["output"] as? JsonArray ?: error("Responses output is required")
        val collector = Collector()
        var messageItems = 0
        for (value in items) {
            val item = value as? JsonObject ?: error("Invalid Responses output item")
            stringMetadata(item, "id")
            when (item.required("type")) {
                "reasoning" -> {
                    keys(item, setOf("type", "id", "status", "summary", "encrypted_content"), "reasoning")
                    require(item.value("encrypted_content") == null) { "Encrypted reasoning cannot be preserved" }
                    completedStatus(item, status)
                    val summary = item["summary"] as? JsonArray ?: error("Reasoning summary is required")
                    if (summary.isEmpty()) collector.reasoning("")
                    for (raw in summary) {
                        val section = raw as? JsonObject ?: error("Invalid reasoning summary")
                        keys(section, setOf("type", "text"), "reasoning summary")
                        require(section.required("type") == "summary_text") { "Unsupported reasoning summary" }
                        collector.reasoning(section.text("text"))
                    }
                }
                "message" -> {
                    require(++messageItems == 1) { "Multiple assistant messages cannot be preserved" }
                    keys(item, setOf("type", "id", "status", "role", "content"), "Responses message")
                    require(item.required("role") == "assistant") { "Unsupported Responses role" }
                    completedStatus(item, status)
                    val content = item["content"] as? JsonArray ?: error("Responses message content is required")
                    if (content.isEmpty()) collector.text("")
                    for (raw in content) {
                        val part = raw as? JsonObject ?: error("Invalid Responses content")
                        keys(part, setOf("type", "text", "annotations", "logprobs"), "Responses text")
                        require(part.required("type") == "output_text") { "Unsupported Responses content" }
                        emptyArray(part, "annotations"); emptyArray(part, "logprobs")
                        collector.text(part.text("text"))
                    }
                }
                "function_call" -> {
                    keys(item, setOf("type", "id", "call_id", "name", "arguments", "status"), "Responses tool call")
                    require(item.value("status") == null || item["status"].string() == "completed") { "Incomplete function call" }
                    collector.call(Call(item.required("call_id"), item.required("name"), item["arguments"].string() ?: error("Function arguments must be text")))
                }
                else -> error("Unsupported Responses output item")
            }
        }
        val parsed = collector.result()
        require(reason != "length" || parsed.calls.isEmpty()) { "Incomplete tool call outcome cannot be preserved" }
        return parsed to if (parsed.calls.isNotEmpty()) "tool_calls" else reason
    }

    private fun messages(payload: JsonObject): Pair<Parsed, String> {
        keys(payload, setOf("id", "type", "role", "model", "content", "stop_reason", "stop_sequence", "usage"), "Messages response")
        stringMetadata(payload, "id", "model")
        payload.value("type")?.let { require(it.string() == "message") { "Invalid Messages type" } }
        require(payload.required("role") == "assistant") { "Unsupported Messages role" }
        require(payload.value("stop_sequence") == null) { "Stop sequence cannot be preserved" }
        val reason = when (payload.required("stop_reason")) {
            "end_turn" -> "stop"
            "tool_use" -> "tool_calls"
            "max_tokens" -> "length"
            else -> error("Unsupported Messages stop reason")
        }
        val parts = payload["content"] as? JsonArray ?: error("Messages content is required")
        val collector = Collector()
        for (value in parts) {
            val part = value as? JsonObject ?: error("Invalid Messages content")
            when (part.required("type")) {
                "thinking" -> {
                    keys(part, setOf("type", "thinking", "signature"), "thinking")
                    require(part.value("signature") == null) { "Thinking signatures cannot be preserved" }
                    collector.reasoning(part.text("thinking"))
                }
                "text" -> {
                    keys(part, setOf("type", "text", "citations"), "Messages text")
                    emptyArray(part, "citations")
                    collector.text(part.text("text"))
                }
                "tool_use" -> {
                    keys(part, setOf("type", "id", "name", "input"), "Messages tool call")
                    val args = part["input"] as? JsonObject ?: error("Tool input must be an object")
                    collector.call(Call(part.required("id"), part.required("name"), args.toString()))
                }
                else -> error("Unsupported Messages content")
            }
        }
        val parsed = collector.result()
        require((reason == "tool_calls") == parsed.calls.isNotEmpty()) { "Messages stop reason conflicts with tool calls" }
        return parsed to reason
    }

    private fun interactions(payload: JsonObject): Pair<Parsed, String> {
        keys(payload, setOf("id", "object", "created", "updated", "model", "status", "steps", "outputs", "error", "incomplete_details", "usage"), "Interactions response")
        payload.value("error")?.let { error("Upstream response contains an error") }
        stringMetadata(payload, "id", "model", "created", "updated")
        payload.value("object")?.let { require(it.string() == "interaction") { "Invalid Interactions object" } }
        val status = payload.required("status")
        val reason = when (status) {
            "completed" -> { require(payload.value("incomplete_details") == null) { "Unexpected incomplete details" }; "stop" }
            "requires_action" -> { require(payload.value("incomplete_details") == null) { "Unexpected incomplete details" }; "tool_calls" }
            "incomplete" -> { incomplete(payload.value("incomplete_details")); "length" }
            else -> error("Unsupported Interactions status")
        }
        val steps = payload.value("steps")?.let { it as? JsonArray ?: error("Invalid Interactions steps") }
        val outputs = payload.value("outputs")?.let { it as? JsonArray ?: error("Invalid Interactions outputs") }
        require(steps != null || outputs != null) { "Interactions steps or outputs are required" }
        val first = steps?.let(::interactionItems)
        val second = outputs?.let(::interactionItems)
        require(first == null || second == null || equivalent(first, second)) { "Conflicting Interactions steps and outputs" }
        val parsed = first ?: checkNotNull(second)
        require(reason != "tool_calls" || parsed.calls.isNotEmpty()) { "Interactions action lacks a complete tool call" }
        require(reason != "length" || parsed.calls.isEmpty()) { "Incomplete tool call outcome cannot be preserved" }
        return parsed to if (parsed.calls.isNotEmpty()) "tool_calls" else reason
    }

    private fun equivalent(first: Parsed, second: Parsed): Boolean =
        first.text == second.text && first.reasoning == second.reasoning && first.calls.size == second.calls.size &&
            first.calls.zip(second.calls).all { (a, b) -> a.id == b.id && a.name == b.name &&
                Json.parseToJsonElement(a.arguments) == Json.parseToJsonElement(b.arguments) }

    private fun interactionItems(items: JsonArray): Parsed {
        val collector = Collector()
        for (raw in items) {
            val item = raw as? JsonObject ?: error("Invalid Interactions item")
            when (item.required("type")) {
                "thought" -> {
                    keys(item, setOf("type", "summary", "signature"), "Interactions thought")
                    require(item.value("signature") == null) { "Thought signatures cannot be preserved" }
                    val summary = item["summary"] as? JsonArray ?: error("Thought summary is required")
                    if (summary.isEmpty()) collector.reasoning("")
                    for (value in summary) {
                        val section = value as? JsonObject ?: error("Invalid thought summary")
                        keys(section, setOf("type", "text"), "thought summary")
                        require(section.required("type") in setOf("text", "summary_text")) { "Unsupported thought summary" }
                        collector.reasoning(section.text("text"))
                    }
                }
                "model_output" -> {
                    keys(item, setOf("type", "content"), "Interactions output")
                    val content = item["content"] as? JsonArray ?: error("Interactions content is required")
                    if (content.isEmpty()) collector.text("")
                    for (value in content) {
                        val part = value as? JsonObject ?: error("Invalid Interactions content")
                        interactionText(part, collector)
                    }
                }
                "text" -> interactionText(item, collector)
                "function_call" -> {
                    keys(item, setOf("type", "id", "name", "arguments", "signature"), "Interactions tool call")
                    require(item.value("signature") == null) { "Tool signatures cannot be preserved" }
                    val args = item["arguments"] as? JsonObject ?: error("Tool arguments must be an object")
                    collector.call(Call(item.required("id"), item.required("name"), args.toString()))
                }
                else -> error("Unsupported Interactions output")
            }
        }
        return collector.result()
    }

    private fun interactionText(part: JsonObject, collector: Collector) {
        keys(part, setOf("type", "text", "annotations"), "Interactions text")
        require(part.required("type") == "text") { "Unsupported Interactions content" }
        emptyArray(part, "annotations")
        collector.text(part.text("text"))
    }
    private fun completedStatus(item: JsonObject, responseStatus: String) {
        item.value("status")?.let { raw ->
            val status = raw.string()
            require(status == "completed" || responseStatus == "incomplete" && status == "incomplete") { "Unsupported item status" }
        }
    }
    private fun incomplete(value: JsonElement?) {
        val detail = value as? JsonObject ?: error("Incomplete reason cannot be preserved")
        keys(detail, setOf("reason"), "incomplete details")
        require(detail.required("reason") == "max_output_tokens") { "Incomplete reason cannot be preserved" }
    }

    private fun usage(source: String, raw: JsonElement, reasoningPresent: Boolean): JsonObject {
        val value = raw as? JsonObject ?: error("Invalid upstream usage")
        var cached: JsonElement? = null
        var reasoning: JsonElement? = null
        val input: Long
        val output: Long
        val total: Long
        when (source) {
            "responses" -> {
                keys(value, setOf("input_tokens", "output_tokens", "total_tokens", "input_tokens_details", "output_tokens_details"), "Responses usage")
                input = value.number("input_tokens"); output = value.number("output_tokens")
                total = value.value("total_tokens")?.let { count(it, "total tokens") } ?: Math.addExact(input, output)
                require(total == Math.addExact(input, output)) { "Inconsistent Responses usage" }
                value.value("input_tokens_details")?.let { rawDetails ->
                    val details = rawDetails as? JsonObject ?: error("Invalid input token details")
                    keys(details, setOf("cached_tokens"), "input token details")
                    cached = details["cached_tokens"]
                    cached?.takeUnless { it == JsonNull }?.let { require(count(it, "cached tokens") <= input) { "Invalid cached token count" } }
                }
                value.value("output_tokens_details")?.let { rawDetails ->
                    val details = rawDetails as? JsonObject ?: error("Invalid output token details")
                    keys(details, setOf("reasoning_tokens"), "output token details")
                    reasoning = details["reasoning_tokens"]
                    reasoning?.takeUnless { it == JsonNull }?.let { require(count(it, "reasoning tokens") <= output) { "Invalid reasoning token count" } }
                }
            }
            "messages" -> {
                keys(value, setOf("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens", "cache_creation"), "Messages usage")
                val uncached = value.number("input_tokens"); output = value.number("output_tokens")
                val read = value.value("cache_read_input_tokens")?.let { count(it, "cache read tokens") } ?: 0L
                val creation = value.value("cache_creation_input_tokens")?.let { count(it, "cache creation tokens") } ?: 0L
                require(creation == 0L) { "Cache creation token usage cannot be preserved" }
                value.value("cache_creation")?.let { rawDetails ->
                    val details = rawDetails as? JsonObject ?: error("Invalid cache creation details")
                    keys(details, setOf("ephemeral_5m_input_tokens", "ephemeral_1h_input_tokens"), "cache creation details")
                    for ((_, entry) in details) if (entry != JsonNull) require(count(entry, "cache creation tokens") == 0L) { "Cache creation token usage cannot be preserved" }
                }
                input = Math.addExact(uncached, read)
                total = Math.addExact(input, output)
                if (value.value("cache_read_input_tokens") != null) cached = JsonPrimitive(read)
            }
            else -> {
                keys(value, setOf("total_input_tokens", "total_output_tokens", "total_thought_tokens", "total_cached_tokens", "total_tool_use_tokens", "total_tokens"), "Interactions usage")
                input = value.number("total_input_tokens")
                val rawOutput = value.number("total_output_tokens")
                val thoughts = value.value("total_thought_tokens")?.let { count(it, "thought tokens") }
                require(!reasoningPresent || thoughts != null) { "Thought token usage is unavailable" }
                val toolUse = value.value("total_tool_use_tokens")?.let { count(it, "tool use tokens") } ?: 0L
                require(toolUse == 0L) { "Tool-use token usage cannot be preserved" }
                output = Math.addExact(rawOutput, thoughts ?: 0L)
                total = value.value("total_tokens")?.let { count(it, "total tokens") } ?: error("Interactions total token usage is unavailable")
                require(total == Math.addExact(input, output)) { "Interactions token breakdown cannot be preserved" }
                value.value("total_cached_tokens")?.let { rawCached ->
                    val n = count(rawCached, "cached tokens"); require(n <= input) { "Invalid cached token count" }; cached = JsonPrimitive(n)
                }
                if (thoughts != null) reasoning = JsonPrimitive(thoughts)
            }
        }
        val result = mutableMapOf<String, JsonElement>("prompt_tokens" to JsonPrimitive(input), "completion_tokens" to JsonPrimitive(output), "total_tokens" to JsonPrimitive(total))
        cached?.let { result["prompt_tokens_details"] = obj("cached_tokens" to it) }
        reasoning?.let { result["completion_tokens_details"] = obj("reasoning_tokens" to it) }
        return JsonObject(result)
    }

    private fun stringMetadata(obj: JsonObject, vararg names: String) {
        for (name in names) obj.value(name)?.let { require(it.string() != null) { "Invalid $name" } }
    }
    private fun emptyArray(obj: JsonObject, key: String) {
        obj.value(key)?.let { raw ->
            val list = raw as? JsonArray ?: error("$key must be an array")
            require(list.isEmpty()) { "$key cannot be preserved" }
        }
    }
    private fun keys(value: JsonObject, allowed: Set<String>, context: String) {
        for ((key, item) in value) require(item == JsonNull || key in allowed) { "Unsupported $context field: $key" }
    }
    private fun JsonObject.required(key: String): String = this[key].string()?.takeIf { it.isNotEmpty() } ?: error("Expected a non-empty $key")
    private fun JsonObject.text(key: String): String = this[key].string() ?: error("Expected text in $key")
    private fun JsonObject.number(key: String): Long = this[key]?.let { count(it, key) } ?: error("Missing $key")
    private fun JsonObject.value(key: String): JsonElement? = this[key]?.takeUnless { it == JsonNull }
    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun count(value: JsonElement, name: String): Long = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 } ?: error("Invalid $name")
    private fun obj(vararg values: Pair<String, JsonElement>) = JsonObject(mapOf(*values))
    private fun str(value: String) = JsonPrimitive(value)
    private fun num(value: Int) = JsonPrimitive(value)
}
