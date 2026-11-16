/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.util.UUID
import kotlinx.serialization.json.*

/** Non-streaming Chat -> Responses projection; unrepresentable upstream data fails explicitly. */
internal class AcpChatToResponses(
    private val responseId: () -> String = { "resp_" + UUID.randomUUID().toString().replace("-", "") },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun convert(payload: JsonObject, model: String, customTools: Set<String>): JsonObject {
        keys(payload, setOf("id", "object", "created", "model", "choices", "usage"), "upstream response")
        for (key in listOf("id", "object", "model")) payload.value(key)?.let { require(it.string() != null) { "Invalid upstream $key" } }
        payload.value("object")?.let { require(it.string() == "chat.completion") { "Invalid upstream object" } }
        payload.value("created")?.let { nonnegativeLong(it, "created") }
        val choices = payload["choices"] as? JsonArray ?: error("Upstream response lacks choices")
        require(choices.size == 1) { "Expected exactly one upstream choice" }
        val choice = choices.single() as? JsonObject ?: error("Invalid upstream choice")
        keys(choice, setOf("index", "message", "finish_reason"), "upstream choice")
        choice.value("index")?.let { nonnegativeLong(it, "choice index") }
        val reason = choice["finish_reason"].string() ?: error("Upstream ended without a finish reason")
        require(reason in setOf("stop", "tool_calls", "length")) { "Unsupported upstream finish reason: $reason" }
        val message = choice["message"] as? JsonObject ?: error("Upstream response lacks message")
        keys(message, setOf("role", "content", "reasoning_content", "reasoning", "tool_calls", "refusal", "annotations"), "upstream message")
        message.value("role")?.let { require(it.string() == "assistant") { "Upstream returned an unsupported role" } }
        require(message.value("refusal") == null) { "Upstream refusal cannot be converted" }
        message.value("annotations")?.let { raw ->
            val annotations = raw as? JsonArray ?: error("Upstream annotations must be an array")
            require(annotations.isEmpty()) { "Upstream annotations cannot be converted" }
        }
        val reasoningContent = message.value("reasoning_content")
        val reasoningAlternative = message.value("reasoning")
        require(reasoningContent == null || reasoningAlternative == null) { "Conflicting upstream reasoning fields" }
        val reasoning = (reasoningContent ?: reasoningAlternative)?.let { it.string() ?: error("Upstream reasoning is not text") }
        val text = message.value("content")?.let { it.string() ?: error("Upstream content is not text") }
        val id = responseId()
        require(id.startsWith("resp_") && id.length in 6..256 && id.none { it.isISOControl() }) { "Invalid generated response ID" }
        val created = nowSeconds()
        require(created >= 0) { "Invalid response timestamp" }
        val output = mutableListOf<JsonObject>()
        if (!reasoning.isNullOrEmpty()) output += obj(
            "type" to JsonPrimitive("reasoning"), "id" to JsonPrimitive("rs_${id}_${output.size}"),
            "summary" to JsonArray(listOf(obj("type" to JsonPrimitive("summary_text"), "text" to JsonPrimitive(reasoning))))
        )
        if (!text.isNullOrEmpty()) output += obj(
            "id" to JsonPrimitive("msg_${id}_${output.size}"), "type" to JsonPrimitive("message"),
            "role" to JsonPrimitive("assistant"), "status" to JsonPrimitive("completed"),
            "content" to JsonArray(listOf(obj("type" to JsonPrimitive("output_text"), "text" to JsonPrimitive(text), "annotations" to JsonArray(emptyList()))))
        )
        message.value("tool_calls")?.let { raw ->
            val calls = raw as? JsonArray ?: error("Invalid upstream tool calls")
            val frozenCustom = customTools.toSet()
            for (rawCall in calls) {
                val call = rawCall as? JsonObject ?: error("Invalid upstream tool call")
                keys(call, setOf("id", "type", "function", "index"), "upstream tool call")
                call.value("index")?.let { nonnegativeLong(it, "tool call index") }
                call.value("type")?.let { require(it.string() == "function") { "Upstream returned an unsupported tool call type" } }
                val callId = call.requiredString("id")
                val function = call["function"] as? JsonObject ?: error("Invalid upstream function call")
                keys(function, setOf("name", "arguments"), "upstream function call")
                val name = function.requiredString("name")
                val args = function["arguments"].string() ?: error("Upstream function arguments are not text")
                val itemId = JsonPrimitive("fc_${id}_${output.size}")
                if (name in frozenCustom) output += obj(
                    "type" to JsonPrimitive("custom_tool_call"), "id" to itemId, "call_id" to JsonPrimitive(callId),
                    "name" to JsonPrimitive(name), "input" to JsonPrimitive(customInput(args))
                ) else output += obj(
                    "type" to JsonPrimitive("function_call"), "id" to itemId, "call_id" to JsonPrimitive(callId),
                    "name" to JsonPrimitive(name), "arguments" to JsonPrimitive(args), "status" to JsonPrimitive("completed")
                )
            }
        }
        val status = if (reason == "length") "incomplete" else "completed"
        val result = mutableMapOf<String, JsonElement>(
            "id" to JsonPrimitive(id), "object" to JsonPrimitive("response"), "created_at" to JsonPrimitive(created),
            "status" to JsonPrimitive(status), "model" to JsonPrimitive(model), "output" to JsonArray(output),
            "error" to JsonNull, "incomplete_details" to if (reason == "length") obj("reason" to JsonPrimitive("max_output_tokens")) else JsonNull,
        )
        payload.value("usage")?.let { result["usage"] = usage(it) }
        return JsonObject(result)
    }

    private fun usage(value: JsonElement): JsonObject {
        val data = value as? JsonObject ?: error("Invalid upstream usage")
        keys(data, setOf("prompt_tokens", "completion_tokens", "total_tokens", "prompt_tokens_details", "completion_tokens_details"), "upstream usage")
        val input = data.value("prompt_tokens")?.let { nonnegativeLong(it, "prompt tokens") } ?: 0L
        val output = data.value("completion_tokens")?.let { nonnegativeLong(it, "completion tokens") } ?: 0L
        val total = data.value("total_tokens")?.let { nonnegativeLong(it, "total tokens") } ?: Math.addExact(input, output)
        fun detail(key: String, field: String): JsonElement {
            val value = data.value(key) ?: return JsonPrimitive(0)
            val details = value as? JsonObject ?: error("Invalid upstream usage details")
            keys(details, setOf(field), "upstream usage details")
            val entry = details[field] ?: return JsonPrimitive(0)
            return if (entry == JsonNull) JsonNull else JsonPrimitive(nonnegativeLong(entry, field))
        }
        return obj("input_tokens" to JsonPrimitive(input), "output_tokens" to JsonPrimitive(output), "total_tokens" to JsonPrimitive(total),
            "input_tokens_details" to obj("cached_tokens" to detail("prompt_tokens_details", "cached_tokens")),
            "output_tokens_details" to obj("reasoning_tokens" to detail("completion_tokens_details", "reasoning_tokens")))
    }

    /** Exact one-property grammar prevents silently discarding unknown or duplicate tool arguments. */
    private fun customInput(raw: String): String {
        var index = 0
        fun space() { while (index < raw.length && raw[index] in " \t\r\n") index++ }
        fun take(c: Char) { space(); require(index < raw.length && raw[index] == c) { "Invalid custom tool arguments" }; index++ }
        fun text(): String {
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
        take('{')
        require(text() == "input") { "Custom tool arguments require input text" }
        take(':'); val input = text(); take('}'); space()
        require(index == raw.length) { "Invalid custom tool arguments" }
        return input
    }

    private fun keys(value: JsonObject, allowed: Set<String>, context: String) {
        for ((key, item) in value) require(item == JsonNull || key in allowed) { "Unsupported $context field: $key" }
    }
    private fun JsonObject.requiredString(key: String): String = this[key].string()?.takeIf { it.isNotEmpty() } ?: error("Expected a non-empty $key")
    private fun JsonObject.value(key: String): JsonElement? = this[key]?.takeUnless { it == JsonNull }
    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun nonnegativeLong(value: JsonElement, name: String): Long =
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 } ?: error("Invalid upstream $name")
    private fun obj(vararg values: Pair<String, JsonElement>) = JsonObject(mapOf(*values))
}
