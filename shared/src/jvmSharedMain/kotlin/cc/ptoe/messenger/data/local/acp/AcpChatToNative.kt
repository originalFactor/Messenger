/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.net.URI
import java.util.Base64
import kotlinx.serialization.json.*

/** Chat -> Responses / Messages / Interactions for only the losslessly representable subset. */
internal object AcpChatToNative {
    private sealed interface Part {
        data class Text(val value: String) : Part
        data class Image(val url: String, val detail: String?) : Part
    }
    private data class Call(val id: String, val name: String, val rawArguments: String, val arguments: JsonObject)
    private data class Message(val role: String, val parts: List<Part>?, val calls: List<Call>, val toolId: String?)

    fun convert(target: String, body: JsonObject, upstreamModel: String): JsonObject {
        require(target in setOf("responses", "messages", "interactions")) { "Unsupported target format" }
        keys(body, setOf("model", "messages", "temperature", "top_p", "max_tokens", "reasoning_effort", "tools", "stream", "stream_options"), "Chat request")
        val stream = body["stream"]
        require(stream == null || stream == JsonNull || stream.boolean() != null) { "stream must be a boolean or null" }
        val enabled = stream.boolean() ?: false
        body.value("stream_options")?.let { value ->
            val options = value as? JsonObject ?: error("Invalid stream options")
            keys(options, setOf("include_usage"), "stream options")
            require(enabled && options["include_usage"].boolean() == true) { "Stream options cannot be preserved" }
        }
        for (name in listOf("temperature", "top_p")) body.value(name)?.let { raw ->
            val value = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            require(value != null && value.isFinite()) { "$name must be a finite number" }
            require(target != "interactions") { "$name cannot be preserved for Interactions" }
        }
        val maxTokens = body.value("max_tokens")?.let { value ->
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 } ?: error("max_tokens must be a positive integer")
        }
        require(target != "messages" || maxTokens != null) { "Messages requires an explicit max_tokens" }
        val effort = body.value("reasoning_effort")?.let { raw ->
            val value = raw.string() ?: error("reasoning_effort must be text")
            require(target != "messages") { "reasoning_effort cannot be preserved for Messages" }
            require(value in setOf("minimal", "low", "medium", "high")) { "reasoning_effort cannot be preserved" }
            value
        }
        val rawMessages = body["messages"] as? JsonArray ?: error("messages must be an array")
        var system: String? = null
        var seenTurn = false
        val messages = mutableListOf<Message>()
        for (value in rawMessages) {
            val raw = value as? JsonObject ?: error("Invalid Chat message")
            keys(raw, setOf("role", "content", "tool_calls", "tool_call_id"), "Chat message")
            val role = raw.required("role")
            require(role in setOf("system", "user", "assistant", "tool")) { "Unsupported Chat role" }
            if (role == "system") {
                require(!seenTurn && system == null) { "System messages cannot be reordered or merged" }
                require(raw.value("tool_calls") == null && raw.value("tool_call_id") == null) { "Invalid system message" }
                system = raw["content"].string() ?: error("System content must be text")
                continue
            }
            seenTurn = true
            if (role == "tool") {
                require(raw.value("tool_calls") == null) { "Invalid tool message" }
                val toolId = raw.required("tool_call_id")
                val content = raw["content"].string() ?: error("Tool content must be text")
                messages += Message(role, listOf(Part.Text(content)), emptyList(), toolId)
                continue
            }
            require(raw.value("tool_call_id") == null) { "Unexpected tool call ID" }
            val calls = raw.value("tool_calls")?.let { list ->
                require(role == "assistant") { "Tool calls require an assistant message" }
                val calls = list as? JsonArray ?: error("Invalid Chat tool calls")
                val parsed = calls.map { call(it) }
                require(parsed.map { it.id }.distinct().size == parsed.size) { "Duplicate Chat tool call ID" }
                parsed
            } ?: emptyList()
            val content = raw["content"]
            val parts = if (content == null || content == JsonNull) null else parts(content, target, role)
            require(parts != null || calls.isNotEmpty()) { "Chat message has no representable content" }
            val meaningful = parts?.any { it is Part.Image || it is Part.Text && it.value.isNotEmpty() } == true
            require(calls.isEmpty() || !meaningful) { "Assistant text and tool calls have ambiguous ordering" }
            messages += Message(role, parts, calls, null)
        }
        val result = mutableMapOf<String, JsonElement>("model" to str(upstreamModel), "stream" to JsonPrimitive(enabled))
        when (target) {
            "responses" -> {
                system?.let { result["instructions"] = str(it) }
                val input = mutableListOf<JsonObject>()
                for (message in messages) {
                    if (message.role == "tool") {
                        input += obj("type" to str("function_call_output"), "call_id" to str(checkNotNull(message.toolId)),
                            "output" to str((checkNotNull(message.parts).single() as Part.Text).value))
                    } else {
                        if (message.calls.isNotEmpty()) message.calls.forEach { call ->
                            input += obj("type" to str("function_call"), "id" to str(call.id), "call_id" to str(call.id), "name" to str(call.name), "arguments" to str(call.rawArguments))
                        } else input += obj("type" to str("message"), "role" to str(message.role), "content" to JsonArray(checkNotNull(message.parts).map { part -> responsePart(part, message.role) }))
                    }
                }
                result["input"] = JsonArray(input)
                body.value("temperature")?.let { result["temperature"] = it }
                body.value("top_p")?.let { result["top_p"] = it }
                maxTokens?.let { result["max_output_tokens"] = JsonPrimitive(it) }
                effort?.let { result["reasoning"] = obj("effort" to str(it)) }
            }
            "messages" -> {
                system?.let { result["system"] = str(it) }
                val native = mutableListOf<JsonObject>()
                val pending = mutableListOf<JsonObject>()
                fun flush() { if (pending.isNotEmpty()) { native += obj("role" to str("user"), "content" to JsonArray(pending.toList())); pending.clear() } }
                for (message in messages) {
                    if (message.role == "tool") {
                        pending += obj("type" to str("tool_result"), "tool_use_id" to str(checkNotNull(message.toolId)), "content" to str((checkNotNull(message.parts).single() as Part.Text).value))
                    } else {
                        flush()
                        val content = if (message.calls.isNotEmpty()) message.calls.map { call ->
                            obj("type" to str("tool_use"), "id" to str(call.id), "name" to str(call.name), "input" to call.arguments)
                        } else checkNotNull(message.parts).map(::messagePart)
                        native += obj("role" to str(message.role), "content" to JsonArray(content))
                    }
                }
                flush(); result["messages"] = JsonArray(native)
                result["max_tokens"] = JsonPrimitive(checkNotNull(maxTokens))
                body.value("temperature")?.let { result["temperature"] = it }
                body.value("top_p")?.let { result["top_p"] = it }
            }
            else -> {
                system?.let { result["system_instruction"] = str(it) }
                val input = mutableListOf<JsonObject>()
                for (message in messages) {
                    when {
                        message.role == "tool" -> input += obj("type" to str("function_result"), "call_id" to str(checkNotNull(message.toolId)),
                            "result" to str((checkNotNull(message.parts).single() as Part.Text).value))
                        message.calls.isNotEmpty() -> message.calls.forEach { call ->
                            input += obj("type" to str("function_call"), "id" to str(call.id), "name" to str(call.name), "arguments" to call.arguments)
                        }
                        else -> input += obj("type" to str(if (message.role == "assistant") "model_output" else "user_input"), "content" to JsonArray(checkNotNull(message.parts).map(::interactionPart)))
                    }
                }
                result["input"] = JsonArray(input); result["store"] = JsonPrimitive(false)
                val generation = mutableMapOf<String, JsonElement>()
                maxTokens?.let { generation["max_output_tokens"] = JsonPrimitive(it) }
                effort?.let { generation["thinking_level"] = str(it) }
                if (generation.isNotEmpty()) result["generation_config"] = JsonObject(generation)
            }
        }
        body.value("tools")?.let { raw ->
            val tools = raw as? JsonArray ?: error("tools must be an array")
            result["tools"] = JsonArray(tools.map { tool(it, target) })
        }
        return JsonObject(result)
    }

    private fun parts(value: JsonElement, target: String, role: String): List<Part> {
        value.string()?.let { return listOf(Part.Text(it)) }
        val values = value as? JsonArray ?: error("Chat content must be text or an array")
        require(values.isNotEmpty()) { "Empty content block array cannot be represented" }
        return values.map { raw ->
            val item = raw as? JsonObject ?: error("Invalid Chat content block")
            when (item.required("type")) {
                "text" -> { keys(item, setOf("type", "text"), "text block"); Part.Text(item["text"].string() ?: error("Text block requires text")) }
                "image_url" -> {
                    require(role == "user") { "Assistant images cannot be represented" }
                    keys(item, setOf("type", "image_url"), "image block")
                    val image = item["image_url"] as? JsonObject ?: error("Invalid image URL")
                    keys(image, setOf("url", "detail"), "image URL")
                    val url = image.required("url")
                    val detail = image.value("detail")?.let { it.string() ?: error("Invalid image detail") }
                    if (detail != null) {
                        require(detail in setOf("auto", "low", "high")) { "Invalid image detail" }
                        require(target == "responses") { "Image detail cannot be preserved for the target" }
                    }
                    if (!url.startsWith("data:")) {
                        val uri = try { URI(url) } catch (_: Exception) { error("Invalid image URL") }
                        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Invalid image URL" }
                    } else if (target != "responses") dataUrl(url)
                    Part.Image(url, detail)
                }
                else -> error("Unsupported Chat content block")
            }
        }
    }
    private fun responsePart(part: Part, role: String): JsonObject = when (part) {
        is Part.Text -> obj("type" to str(if (role == "assistant") "output_text" else "input_text"), "text" to str(part.value))
        is Part.Image -> JsonObject(obj("type" to str("input_image"), "image_url" to str(part.url)) + (part.detail?.let { mapOf("detail" to str(it)) } ?: emptyMap()))
    }
    private fun messagePart(part: Part): JsonObject = when (part) {
        is Part.Text -> obj("type" to str("text"), "text" to str(part.value))
        is Part.Image -> {
            val source = if (part.url.startsWith("data:")) { val (mime, data) = dataUrl(part.url); obj("type" to str("base64"), "media_type" to str(mime), "data" to str(data)) }
                else obj("type" to str("url"), "url" to str(part.url))
            obj("type" to str("image"), "source" to source)
        }
    }
    private fun interactionPart(part: Part): JsonObject = when (part) {
        is Part.Text -> obj("type" to str("text"), "text" to str(part.value))
        is Part.Image -> if (part.url.startsWith("data:")) {
            val (mime, data) = dataUrl(part.url); obj("type" to str("image"), "data" to str(data), "mime_type" to str(mime))
        } else obj("type" to str("image"), "uri" to str(part.url))
    }
    private fun dataUrl(url: String): Pair<String, String> {
        val match = Regex("^data:(image/[A-Za-z0-9.+-]+);base64,([A-Za-z0-9+/]*={0,2})$").matchEntire(url) ?: error("Unsupported image data URL")
        val data = match.groupValues[2]
        require(data.isNotEmpty() && data.length <= 16 * 1024 * 1024) { "Invalid image data" }
        try { Base64.getDecoder().decode(data) } catch (_: IllegalArgumentException) { error("Invalid image base64") }
        return match.groupValues[1] to data
    }
    private fun call(value: JsonElement): Call {
        val call = value as? JsonObject ?: error("Invalid Chat tool call")
        keys(call, setOf("id", "type", "function"), "tool call")
        require(call["type"].string() == "function") { "Unsupported Chat tool call" }
        val function = call["function"] as? JsonObject ?: error("Invalid function call")
        keys(function, setOf("name", "arguments"), "function call")
        val args = function["arguments"].string() ?: error("Function arguments must be text")
        val parsed = parseArguments(args)
        return Call(call.required("id"), function.required("name"), args, parsed)
    }
    private fun tool(value: JsonElement, target: String): JsonObject {
        val tool = value as? JsonObject ?: error("Invalid Chat tool")
        keys(tool, setOf("type", "function"), "tool")
        require(tool["type"].string() == "function") { "Unsupported hosted tool" }
        val function = tool["function"] as? JsonObject ?: error("Invalid function tool")
        keys(function, setOf("name", "description", "parameters", "strict"), "function tool")
        val description = function["description"]
        require(description == null || description == JsonNull || description.string() != null) { "Invalid function description" }
        val schema = function["parameters"] as? JsonObject ?: error("Function parameters must be an object")
        val strict = function.value("strict")
        if (strict != null) {
            require(strict.boolean() != null) { "strict must be a boolean" }
            require(target == "responses") { "Function strictness cannot be preserved for the target" }
        }
        val result = mutableMapOf<String, JsonElement>("name" to str(function.required("name")))
        description?.let { result["description"] = it }
        when (target) { "responses" -> { result["type"] = str("function"); result["parameters"] = schema; strict?.let { result["strict"] = it } }
            "messages" -> result["input_schema"] = schema
            else -> { result["type"] = str("function"); result["parameters"] = schema }
        }
        return JsonObject(result)
    }
    private fun parseArguments(raw: String): JsonObject {
        require(raw.encodeToByteArray().size <= 8 * 1024 * 1024) { "Function arguments exceed limit" }
        return try { AcpRelayStrictJson.parse(raw) as? JsonObject ?: error("Function arguments must be an object") }
        catch (_: Exception) { error("Invalid function arguments") }
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
