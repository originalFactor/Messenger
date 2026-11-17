/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*

/** Stateless Messages / Interactions -> Chat conversion; rejects fields that would be lost. */
internal object AcpMessagesInteractionsToChat {
    fun convert(source: String, body: JsonObject, upstreamModel: String): JsonObject {
        require(source == "messages" || source == "interactions") { "Unsupported source format" }
        val messagesSource = source == "messages"
        val inputKey = if (messagesSource) "messages" else "input"
        val systemKey = if (messagesSource) "system" else "system_instruction"
        keys(body, setOf("model", inputKey, systemKey, "tools", "temperature", "top_p", "max_tokens", "stream"), "request")
        val streamValue = body["stream"]
        require(streamValue == null || streamValue == JsonNull || streamValue.boolean() != null) { "stream must be a boolean or null" }
        val stream = streamValue.boolean() ?: false
        for (key in listOf("temperature", "top_p")) body.value(key)?.let { raw ->
            val value = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            require(value != null && value.isFinite()) { "$key must be a finite number" }
        }
        body.value("max_tokens")?.let { raw ->
            val value = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            require(value != null && value > 0) { "max_tokens must be a positive integer" }
        }
        val messages = mutableListOf<JsonObject>()
        body.value(systemKey)?.let { system -> messages += obj("role" to str("system"), "content" to str(text(system))) }
        val input = body[inputKey] ?: error("Missing request input")
        val inputString = input.string()
        if (inputString != null) messages += obj("role" to str("user"), "content" to str(inputString))
        else {
            val items = input as? JsonArray ?: error("Request input must be text or an array")
            for (rawItem in items) {
                val item = rawItem as? JsonObject ?: error("Invalid request message")
                val kind = item["type"].string()
                if (!messagesSource && kind == "function_call") {
                    keys(item, setOf("type", "id", "name", "arguments"), "function call")
                    val args = item["arguments"] as? JsonObject ?: error("Function arguments must be an object")
                    messages += obj("role" to str("assistant"), "content" to JsonNull, "tool_calls" to JsonArray(listOf(
                        functionCall(item.required("id"), item.required("name"), args))))
                    continue
                }
                if (!messagesSource && kind == "function_result") {
                    keys(item, setOf("type", "call_id", "result"), "function result")
                    val content = item["result"] ?: error("Function result is required")
                    messages += obj("role" to str("tool"), "tool_call_id" to str(item.required("call_id")), "content" to str(text(content)))
                    continue
                }
                val role = if (messagesSource) {
                    keys(item, setOf("role", "content"), "message")
                    val value = item.required("role")
                    require(value == "user" || value == "assistant") { "Unsupported Messages role" }
                    value
                } else {
                    keys(item, setOf("type", "content"), "interaction")
                    when (kind) { "user_input" -> "user"; "model_output" -> "assistant"; else -> error("Unsupported Interactions input") }
                }
                val content = item["content"] ?: error("Message content is required")
                val plain = content.string()
                if (plain != null) {
                    messages += obj("role" to str(role), "content" to str(plain))
                    continue
                }
                val parts = content as? JsonArray ?: error("Message content must be text or an array")
                val combined = StringBuilder()
                val calls = mutableListOf<JsonObject>()
                var textOrCallSeen = false
                var callsSeen = false
                for (rawPart in parts) {
                    val part = rawPart as? JsonObject ?: error("Invalid message content block")
                    when (part.required("type")) {
                        "text" -> {
                            keys(part, setOf("type", "text"), "text block")
                            val value = part["text"].string() ?: error("Text block requires text")
                            require(!callsSeen || value.isEmpty()) { "Text after a tool call cannot be converted without reordering" }
                            combined.append(value)
                            if (value.isNotEmpty()) textOrCallSeen = true
                        }
                        "tool_use" -> {
                            require(role == "assistant") { "Tool calls require an assistant message" }
                            keys(part, setOf("type", "id", "name", "input"), "tool call")
                            val args = part["input"] as? JsonObject ?: error("Tool arguments must be an object")
                            calls += functionCall(part.required("id"), part.required("name"), args)
                            callsSeen = true; textOrCallSeen = true
                        }
                        "tool_result" -> {
                            require(role == "user") { "Tool results require a user message" }
                            require(!textOrCallSeen) { "Tool result after text cannot be converted without reordering" }
                            keys(part, setOf("type", "tool_use_id", "content", "is_error"), "tool result")
                            val flag = part["is_error"]
                            require(flag == null || flag == JsonNull || flag.boolean() != null) { "is_error must be a boolean or null" }
                            require(flag.boolean() != true) { "Error tool results cannot be represented without loss" }
                            val result = part["content"] ?: error("Tool result content is required")
                            messages += obj("role" to str("tool"), "tool_call_id" to str(part.required("tool_use_id")), "content" to str(text(result)))
                        }
                        else -> error("Unsupported content block")
                    }
                }
                // Preserve an explicitly empty message; tool-only result blocks already produced messages.
                if (combined.isNotEmpty() || calls.isNotEmpty() || parts.isEmpty() || parts.all { (it as? JsonObject)?.get("type").string() == "text" }) {
                    val message = mutableMapOf<String, JsonElement>("role" to str(role), "content" to str(combined.toString()))
                    if (calls.isNotEmpty()) message["tool_calls"] = JsonArray(calls)
                    messages += JsonObject(message)
                }
            }
        }
        val result = mutableMapOf<String, JsonElement>("messages" to JsonArray(messages), "model" to str(upstreamModel), "stream" to JsonPrimitive(stream))
        for (key in listOf("temperature", "top_p", "max_tokens")) body[key]?.let { result[key] = it }
        body["tools"]?.let { raw ->
            val tools = raw as? JsonArray ?: error("tools must be an array")
            result["tools"] = JsonArray(tools.map { value ->
                val tool = value as? JsonObject ?: error("Invalid tool definition")
                val paramsKey = if (messagesSource) "input_schema" else "parameters"
                keys(tool, setOf("type", "name", "description", paramsKey), "tool definition")
                tool.value("type")?.let { require(it.string() == "function") { "Unsupported hosted tool" } }
                val name = tool.required("name")
                val description = tool["description"] ?: str("")
                require(description == JsonNull || description.string() != null) { "Tool description must be text" }
                val parameters = tool[paramsKey] as? JsonObject ?: error("Tool parameters must be an object")
                obj("type" to str("function"), "function" to obj("name" to str(name), "description" to description, "parameters" to parameters))
            })
        }
        if (stream) result["stream_options"] = obj("include_usage" to JsonPrimitive(true))
        return JsonObject(result)
    }

    private fun functionCall(id: String, name: String, arguments: JsonObject): JsonObject = obj(
        "id" to str(id), "type" to str("function"),
        "function" to obj("name" to str(name), "arguments" to str(arguments.toString())))

    private fun text(value: JsonElement): String {
        value.string()?.let { return it }
        val parts = value as? JsonArray ?: error("Expected text or text blocks")
        return buildString {
            for (raw in parts) {
                val part = raw as? JsonObject ?: error("Invalid text block")
                keys(part, setOf("type", "text"), "text block")
                require(part.required("type") in setOf("text", "input_text", "output_text")) { "Unsupported content block" }
                append(part["text"].string() ?: error("Expected text"))
            }
        }
    }

    private fun keys(value: JsonObject, allowed: Set<String>, context: String) {
        for ((key, item) in value) require(item == JsonNull || key in allowed) { "Unsupported $context field: $key" }
    }
    private fun JsonObject.required(key: String): String = this[key].string()?.takeIf { it.isNotEmpty() } ?: error("Expected a non-empty $key")
    private fun JsonObject.value(key: String): JsonElement? = this[key]?.takeUnless { it == JsonNull }
    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.boolean(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun obj(vararg values: Pair<String, JsonElement>) = JsonObject(mapOf(*values))
    private fun str(value: String) = JsonPrimitive(value)
}
