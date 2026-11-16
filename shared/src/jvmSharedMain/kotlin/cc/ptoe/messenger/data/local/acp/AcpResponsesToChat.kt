/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*

/** Metadata is required to reconstruct custom-tool output in the Responses reply. */
internal class AcpResponsesChatRequest(body: JsonObject, val stream: Boolean, customTools: Set<String>) {
    val body = body
    val customTools = customTools.toSet()
    override fun toString(): String = "AcpResponsesChatRequest(redacted)"
}

/** Stateless Responses -> Chat conversion; no I/O or request retries. */
internal object AcpResponsesToChat {
    fun convert(body: JsonObject, upstreamModel: String): AcpResponsesChatRequest {
        keys(body, setOf("model", "input", "instructions", "stream", "store", "parallel_tool_calls",
            "temperature", "top_p", "max_output_tokens", "tool_choice", "tools", "reasoning", "include",
            "text", "metadata", "service_tier", "prompt_cache_key", "safety_identifier"), "Responses request")
        for (key in listOf("stream", "store")) {
            val value = body[key]
            require(value == null || value == JsonNull || value.boolean() != null) { "$key must be a boolean or null" }
        }
        require(body["store"].boolean() != true) { "Stored Responses are not supported by a Chat Completions provider" }
        if ("include" in body) {
            val include = body["include"] as? JsonArray ?: error("include must be an array")
            require(include.isEmpty()) { "The requested Responses include field is not supported" }
        }
        val messages = mutableListOf<JsonObject>()
        body.value("instructions")?.let { instructions ->
            val value = instructions.stringOrNull() ?: error("instructions must be text")
            if (value.isNotEmpty()) messages += obj("role" to JsonPrimitive("system"), "content" to JsonPrimitive(value))
        }
        val input = body["input"] ?: error("Responses input is required")
        val inputText = input.stringOrNull()
        if (inputText != null) messages += obj("role" to JsonPrimitive("user"), "content" to JsonPrimitive(inputText))
        else {
            val items = input as? JsonArray ?: error("Responses input must be text or an array")
            for (item in items) {
                val row = item as? JsonObject ?: error("Expected a JSON object")
                when (val kind = row["type"].stringOrNull() ?: "message") {
                    "message" -> {
                        keys(row, setOf("type", "id", "status", "role", "content"), "message")
                        val role = when (val role = row.requiredString("role")) {
                            "developer", "system" -> "system"
                            "user", "assistant" -> role
                            else -> error("Unsupported Responses role: $role")
                        }
                        val content = parts(row["content"] ?: error("Message content is required"))
                        val last = messages.lastOrNull()
                        if (role == "assistant" && last != null && "reasoning_content" in last && last["content"] == JsonNull && "tool_calls" !in last) {
                            messages[messages.lastIndex] = JsonObject(last + ("content" to content))
                        } else messages += obj("role" to JsonPrimitive(role), "content" to content)
                    }
                    "reasoning" -> {
                        keys(row, setOf("type", "id", "summary", "encrypted_content", "status"), "reasoning item")
                        require(row.value("encrypted_content") == null) { "Encrypted reasoning cannot be converted to Chat Completions" }
                        val summary = row["summary"] as? JsonArray ?: error("Reasoning summary must be an array")
                        val text = buildString {
                            for (part in summary) {
                                val section = part as? JsonObject ?: error("Expected a JSON object")
                                keys(section, setOf("type", "text"), "reasoning summary")
                                require(section.requiredString("type") == "summary_text") { "Unsupported reasoning summary" }
                                append(section["text"].stringOrNull() ?: error("Reasoning summary must be text"))
                            }
                        }
                        if (text.isNotEmpty()) messages += obj("role" to JsonPrimitive("assistant"), "content" to JsonNull, "reasoning_content" to JsonPrimitive(text))
                    }
                    "function_call", "custom_tool_call" -> {
                        keys(row, setOf("type", "id", "call_id", "name", "arguments", "input", "status"), "tool call")
                        val id = row.requiredString("call_id")
                        val name = row.requiredString("name")
                        val arguments = if (kind == "custom_tool_call") {
                            obj("input" to JsonPrimitive(row["input"].stringOrNull() ?: error("Custom tool input must be text"))).toString()
                        } else row["arguments"].stringOrNull() ?: error("Tool arguments must be text")
                        val call = obj("id" to JsonPrimitive(id), "type" to JsonPrimitive("function"),
                            "function" to obj("name" to JsonPrimitive(name), "arguments" to JsonPrimitive(arguments)))
                        val last = messages.lastOrNull()
                        if (last?.get("role").stringOrNull() == "assistant") {
                            val calls = (last?.get("tool_calls") as? JsonArray).orEmpty() + call
                            messages[messages.lastIndex] = JsonObject(last!! + ("tool_calls" to JsonArray(calls)))
                        } else messages += obj("role" to JsonPrimitive("assistant"), "content" to JsonNull, "tool_calls" to JsonArray(listOf(call)))
                    }
                    "function_call_output", "custom_tool_call_output" -> {
                        keys(row, setOf("type", "id", "call_id", "output", "status"), "tool output")
                        val output = row["output"].stringOrNull() ?: error("Tool output must be text")
                        messages += obj("role" to JsonPrimitive("tool"), "tool_call_id" to JsonPrimitive(row.requiredString("call_id")), "content" to JsonPrimitive(output))
                    }
                    else -> error("Unsupported Responses input item: $kind")
                }
            }
        }
        val stream = body["stream"].boolean() ?: false
        val result = mutableMapOf<String, JsonElement>("model" to JsonPrimitive(upstreamModel), "messages" to JsonArray(messages), "stream" to JsonPrimitive(stream))
        body.value("text")?.let { raw ->
            val text = raw as? JsonObject ?: error("Expected a JSON object")
            keys(text, setOf("format", "verbosity"), "text")
            text.value("verbosity")?.let { result["verbosity"] = it }
            text.value("format")?.let { value ->
                val format = value as? JsonObject ?: error("Expected a JSON object")
                val kind = format.requiredString("type")
                result["response_format"] = when (kind) {
                    "text", "json_object" -> { keys(format, setOf("type"), "text format"); obj("type" to JsonPrimitive(kind)) }
                    "json_schema" -> {
                        keys(format, setOf("type", "name", "description", "schema", "strict"), "JSON schema")
                        obj("type" to JsonPrimitive("json_schema"), "json_schema" to JsonObject(format - "type"))
                    }
                    else -> error("Unsupported Responses text format: $kind")
                }
            }
        }
        for (key in listOf("temperature", "top_p", "parallel_tool_calls", "metadata", "service_tier", "prompt_cache_key", "safety_identifier")) {
            body.value(key)?.let { result[key] = it }
        }
        body.value("max_output_tokens")?.let { result["max_tokens"] = it }
        body.value("reasoning")?.let { raw ->
            val reasoning = raw as? JsonObject ?: error("Expected a JSON object")
            keys(reasoning, setOf("effort", "summary"), "reasoning")
            require(reasoning.value("summary") == null) { "Reasoning summary settings cannot be converted" }
            reasoning.value("effort")?.let { result["reasoning_effort"] = it }
        }
        val customTools = mutableSetOf<String>()
        body.value("tools")?.let { raw ->
            val tools = raw as? JsonArray ?: error("tools must be an array")
            val converted = tools.map { value ->
                val tool = value as? JsonObject ?: error("Expected a JSON object")
                val name = tool.requiredString("name")
                val kind = tool.requiredString("type")
                val function = when (kind) {
                    "function" -> {
                        keys(tool, setOf("type", "name", "description", "parameters", "strict"), "function tool")
                        val mapped = mutableMapOf<String, JsonElement>("name" to JsonPrimitive(name), "parameters" to (tool["parameters"] ?: JsonObject(emptyMap())))
                        for (key in listOf("description", "strict")) tool[key]?.let { mapped[key] = it }
                        JsonObject(mapped)
                    }
                    "custom" -> {
                        keys(tool, setOf("type", "name", "description", "format"), "custom tool")
                        tool.value("format")?.let { value ->
                            val format = value as? JsonObject ?: error("Custom tool format must be an object")
                            keys(format, setOf("type"), "custom tool format")
                            require(format["type"].stringOrNull() == "text") { "Grammar-constrained custom tools cannot be converted" }
                        }
                        customTools += name
                        val mapped = mutableMapOf<String, JsonElement>("name" to JsonPrimitive(name), "parameters" to obj(
                            "type" to JsonPrimitive("object"), "properties" to obj("input" to obj("type" to JsonPrimitive("string"))),
                            "required" to JsonArray(listOf(JsonPrimitive("input"))), "additionalProperties" to JsonPrimitive(false)))
                        tool["description"]?.let { mapped["description"] = it }
                        JsonObject(mapped)
                    }
                    else -> error("Unsupported Responses tool type: $kind")
                }
                obj("type" to JsonPrimitive("function"), "function" to function)
            }
            if (converted.isNotEmpty()) result["tools"] = JsonArray(converted)
        }
        body.value("tool_choice")?.let { choice ->
            result["tool_choice"] = if (choice.stringOrNull() != null) choice else {
                val selected = choice as? JsonObject ?: error("Expected a JSON object")
                keys(selected, setOf("type", "name"), "tool choice")
                val kind = selected.requiredString("type")
                require(kind == "function" || kind == "custom") { "Unsupported tool choice: $kind" }
                obj("type" to JsonPrimitive("function"), "function" to obj("name" to JsonPrimitive(selected.requiredString("name"))))
            }
        }
        if (stream) result["stream_options"] = obj("include_usage" to JsonPrimitive(true))
        return AcpResponsesChatRequest(JsonObject(result), stream, customTools)
    }

    private fun parts(value: JsonElement): JsonElement {
        if (value.stringOrNull() != null) return value
        val list = value as? JsonArray ?: error("Message content must be text or an array")
        return JsonArray(list.map { part ->
            val item = part as? JsonObject ?: error("Expected a JSON object")
            when (val kind = item.requiredString("type")) {
                "input_text", "output_text", "text" -> {
                    keys(item, setOf("type", "text", "annotations", "logprobs"), "text content")
                    if ("annotations" in item) {
                        val annotations = item["annotations"] as? JsonArray ?: error("Text annotations must be an array")
                        require(annotations.isEmpty()) { "Annotated text is not supported in converted history" }
                    }
                    require(item.value("logprobs") == null) { "Text logprobs cannot be converted" }
                    obj("type" to JsonPrimitive("text"), "text" to JsonPrimitive(item["text"].stringOrNull() ?: error("Missing text")))
                }
                "input_image" -> {
                    keys(item, setOf("type", "image_url", "detail"), "image content")
                    val url = item["image_url"].stringOrNull() ?: error("Image requires image_url")
                    val image = mutableMapOf<String, JsonElement>("url" to JsonPrimitive(url))
                    item["detail"]?.let { image["detail"] = it }
                    obj("type" to JsonPrimitive("image_url"), "image_url" to JsonObject(image))
                }
                else -> error("Unsupported Responses content type: $kind")
            }
        })
    }

    private fun keys(value: JsonObject, allowed: Set<String>, context: String) {
        for ((key, item) in value) require(item == JsonNull || key in allowed) { "Unsupported $context field: $key" }
    }
    private fun JsonObject.requiredString(key: String): String = this[key].stringOrNull()?.takeIf { it.isNotEmpty() }
        ?: error("Expected a non-empty $key")
    private fun JsonObject.value(key: String): JsonElement? = this[key]?.takeUnless { it == JsonNull }
    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.boolean(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun obj(vararg values: Pair<String, JsonElement>) = JsonObject(mapOf(*values))
}
