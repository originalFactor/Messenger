/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.util.UUID
import kotlinx.serialization.json.*

/** One sequentially used instance per upstream SSE stream. Input values are already framed. */
internal class AcpResponsesStreamToChat(
    private val model: String,
    private val decoder: AcpNativeToChat = AcpNativeToChat(),
    responseId: () -> String = { "chatcmpl_" + UUID.randomUUID().toString() },
) {
    private enum class Kind { REASONING, MESSAGE, FUNCTION }
    private class Item(val index: Long, val kind: Kind) {
        var id: String? = null
        var callId: String? = null
        var name: String? = null
        val value = StringBuilder()
        var argumentsSeen = false
        var announced = false
        var done = false
    }
    private val id = responseId().also { require(it.startsWith("chatcmpl_") && it.length in 10..256 && it.none(Char::isISOControl)) { "Invalid response ID" } }
    private val items = sortedMapOf<Long, Item>()
    private val itemIds = mutableMapOf<String, Long>()
    private val thought = StringBuilder()
    private val text = StringBuilder()
    private var phase = 0
    private var totalBytes = 0L
    private var lastSequence: Long? = null
    private var eventCount = 0
    private var upstreamId: String? = null
    private var upstreamModel: String? = null
    private var createdSeen = false
    private var terminal = false
    private var broken = false
    val isFinished: Boolean get() = terminal

    /** Exceptions poison this instance. The caller must then send fail() rather than resume it. */
    fun feed(data: String): String {
        check(!terminal && !broken) { "Responses stream is already finished or invalid" }
        return try {
            require(data.encodeToByteArray().size <= 8 * 1024 * 1024) { "Upstream SSE event exceeds limit" }
            require(++eventCount <= 65_536) { "Too many Responses events" }
            require(data != "[DONE]") { "Upstream ended without a Responses terminal event" }
            val event = try { AcpRelayStrictJson.parse(data) as? JsonObject } catch (_: Exception) { null } ?: error("Invalid upstream SSE JSON")
            event.value("sequence_number")?.let {
                val sequence = integer(it, "sequence number")
                require(lastSequence == null || sequence > checkNotNull(lastSequence)) { "Duplicate or out-of-order Responses event" }
                lastSequence = sequence
            }
            when (val type = event.required("type")) {
                "response.created", "response.queued", "response.in_progress" -> {
                    eventKeys(event, "response"); if (type == "response.created") { check(!createdSeen) { "Duplicate Responses creation" }; createdSeen = true }
                    initial(event["response"]); ""
                }
                "response.output_item.added" -> { eventKeys(event, "output_index", "item"); itemAdded(event); "" }
                "response.output_item.done" -> { eventKeys(event, "output_index", "item"); itemDone(event) }
                "response.content_part.added", "response.content_part.done" -> {
                    eventKeys(event, "item_id", "output_index", "content_index", "part")
                    partEvent(event, Kind.MESSAGE, type.endsWith(".done"))
                }
                "response.reasoning_summary_part.added", "response.reasoning_summary_part.done" -> {
                    eventKeys(event, "item_id", "output_index", "summary_index", "part")
                    partEvent(event, Kind.REASONING, type.endsWith(".done"))
                }
                "response.output_text.delta" -> {
                    eventKeys(event, "item_id", "output_index", "content_index", "delta", "logprobs")
                    emptyArray(event, "logprobs"); appendValue(event, Kind.MESSAGE, event.text("delta"))
                }
                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                    eventKeys(event, "item_id", "output_index", "summary_index", "delta")
                    appendValue(event, Kind.REASONING, event.text("delta"))
                }
                "response.output_text.done" -> {
                    eventKeys(event, "item_id", "output_index", "content_index", "text", "logprobs")
                    emptyArray(event, "logprobs"); reconcileValue(event, Kind.MESSAGE, event.text("text"))
                }
                "response.reasoning_summary_text.done", "response.reasoning_text.done" -> {
                    eventKeys(event, "item_id", "output_index", "summary_index", "text")
                    reconcileValue(event, Kind.REASONING, event.text("text"))
                }
                "response.function_call_arguments.delta" -> {
                    eventKeys(event, "item_id", "output_index", "delta")
                    val item = item(event, Kind.FUNCTION); require(!item.done) { "Arguments arrived after item completion" }
                    val delta = event.text("delta"); account(item.value, delta); if (delta.isNotEmpty()) item.argumentsSeen = true; ""
                }
                "response.function_call_arguments.done" -> {
                    eventKeys(event, "item_id", "output_index", "arguments")
                    val item = item(event, Kind.FUNCTION); require(!item.done) { "Arguments arrived after item completion" }
                    reconcileArguments(item, event.text("arguments")); ""
                }
                "response.completed", "response.incomplete" -> {
                    eventKeys(event, "response"); finish(event["response"], type == "response.incomplete")
                }
                "response.failed", "error" -> error("Upstream Responses stream failed")
                else -> error("Unsupported Responses event")
            }
        } catch (failure: Exception) { broken = true; throw failure }
    }

    fun endOfStream(): String = if (terminal) "" else fail("Upstream stream ended before its terminal event")
    /** The caller must pass a sanitized message. No successful terminal is emitted on failure. */
    fun fail(message: String): String {
        if (terminal) return ""
        terminal = true
        return "data: " + obj("error" to obj("code" to str("upstream_error"), "message" to str(message.take(2048)))) + "\n\n"
    }

    private fun initial(raw: JsonElement?) {
        val response = raw as? JsonObject ?: error("Responses event lacks response")
        require(items.isEmpty()) { "Responses initial event arrived after output" }
        keys(response, setOf("id", "object", "created_at", "model", "status", "output", "error", "incomplete_details", "usage"), "Responses initial response")
        trackResponse(response)
        require(response.value("error") == null && response.value("incomplete_details") == null && response.value("usage") == null) { "Unexpected Responses initial state" }
        response.value("status")?.let { require(it.string() in setOf("queued", "in_progress")) { "Unexpected Responses initial status" } }
        response.value("output")?.let { require(it is JsonArray && it.isEmpty()) { "Initial output cannot be preserved" } }
    }

    private fun trackResponse(response: JsonObject) {
        response.value("id")?.let { raw ->
            val value = raw.string()?.takeIf { it.isNotEmpty() && it.length <= 1024 } ?: error("Invalid response ID")
            require(upstreamId == null || upstreamId == value) { "Responses ID changed" }; upstreamId = value
        }
        response.value("model")?.let { raw ->
            val value = raw.string()?.takeIf { it.isNotEmpty() && it.length <= 1024 } ?: error("Invalid response model")
            require(upstreamModel == null || upstreamModel == value) { "Responses model changed" }; upstreamModel = value
        }
        response.value("object")?.let { require(it.string() == "response") { "Invalid Responses object" } }
        response.value("created_at")?.let { integer(it, "created_at") }
    }

    private fun itemAdded(event: JsonObject) {
        val raw = event["item"] as? JsonObject ?: error("Responses event lacks item")
        val index = index(event)
        val kind = kind(raw.required("type"))
        val item = itemAt(index, kind)
        require(!item.announced && !item.done) { "Duplicate output item" }
        item.announced = true
        matchId(item, raw.value("id"))
        when (kind) {
            Kind.FUNCTION -> {
                keys(raw, setOf("type", "id", "call_id", "name", "arguments", "status"), "function item")
                raw.value("status")?.let { require(it.string() == "in_progress") { "Invalid function item status" } }
                item.callId = raw.value("call_id")?.let { it.string()?.takeIf { value -> value.isNotEmpty() && value.length <= 1024 } ?: error("Invalid call ID") }
                item.name = raw.value("name")?.let { it.string()?.takeIf { value -> value.isNotEmpty() && value.length <= 1024 } ?: error("Invalid function name") }
                raw.value("arguments")?.let { value ->
                    val arguments = value.string() ?: error("Invalid function arguments")
                    account(item.value, arguments); if (arguments.isNotEmpty()) item.argumentsSeen = true
                }
            }
            Kind.MESSAGE -> {
                keys(raw, setOf("type", "id", "role", "content", "status"), "message item")
                require(raw.required("role") == "assistant") { "Unsupported Responses role" }
                raw.value("status")?.let { require(it.string() == "in_progress") { "Invalid message item status" } }
                raw.value("content")?.let { require(it is JsonArray && it.isEmpty()) { "Initial message content cannot be preserved" } }
            }
            Kind.REASONING -> {
                keys(raw, setOf("type", "id", "status", "summary", "encrypted_content"), "reasoning item")
                require(raw.value("encrypted_content") == null) { "Encrypted reasoning cannot be preserved" }
                raw.value("status")?.let { require(it.string() == "in_progress") { "Invalid reasoning item status" } }
                raw.value("summary")?.let { require(it is JsonArray && it.isEmpty()) { "Initial reasoning cannot be preserved" } }
            }
        }
    }

    private fun itemDone(event: JsonObject): String {
        val raw = event["item"] as? JsonObject ?: error("Responses event lacks item")
        val index = index(event); val kind = kind(raw.required("type")); val item = itemAt(index, kind)
        require(!item.done) { "Duplicate completed item" }
        matchId(item, raw.value("id"))
        val decoded = decodeItem(raw)
        val message = decoded.getValue("choices").jsonArray.single().jsonObject.getValue("message").jsonObject
        val out = when (kind) {
            Kind.MESSAGE -> mergeFull(item, Kind.MESSAGE, message.getValue("content").jsonPrimitive.content)
            Kind.REASONING -> mergeFull(item, Kind.REASONING, message["reasoning_content"].string() ?: "")
            Kind.FUNCTION -> {
                val tool = (message["tool_calls"] as? JsonArray)?.singleOrNull() as? JsonObject ?: error("Invalid completed function call")
                reconcileCall(item, tool); ""
            }
        }
        item.done = true
        return out
    }

    private fun decodeItem(raw: JsonObject): JsonObject {
        val incomplete = raw.value("status").string() == "incomplete"
        val response = mutableMapOf<String, JsonElement>("status" to str(if (incomplete) "incomplete" else "completed"), "output" to JsonArray(listOf(raw)))
        if (incomplete) response["incomplete_details"] = obj("reason" to str("max_output_tokens"))
        return decoder.convert("responses", JsonObject(response), model)
    }

    private fun partEvent(event: JsonObject, kind: Kind, done: Boolean): String {
        val part = event["part"] as? JsonObject ?: error("Responses event lacks part")
        val partType = if (kind == Kind.MESSAGE) "output_text" else "summary_text"
        val allowed = if (kind == Kind.MESSAGE) setOf("type", "text", "annotations", "logprobs") else setOf("type", "text")
        keys(part, allowed, "Responses part"); require(part.required("type") == partType) { "Unsupported Responses part" }
        if (kind == Kind.MESSAGE) { emptyArray(part, "annotations"); emptyArray(part, "logprobs") }
        val value = part.text("text")
        if (!done) { require(value.isEmpty()) { "Initial content cannot be preserved" }; item(event, kind); return "" }
        return reconcileValue(event, kind, value)
    }

    private fun appendValue(event: JsonObject, kind: Kind, value: String): String {
        val item = item(event, kind)
        require(!item.done) { "Content arrived after item completion" }
        return addText(item, kind, value)
    }
    private fun reconcileValue(event: JsonObject, kind: Kind, value: String): String = mergeFull(item(event, kind), kind, value)
    private fun mergeFull(item: Item, kind: Kind, value: String): String {
        val current = item.value.toString()
        require(value.startsWith(current) && (!item.done || value == current)) { "Responses snapshot conflicts with streamed content" }
        return addText(item, kind, value.substring(current.length))
    }
    private fun addText(item: Item, kind: Kind, value: String): String {
        if (value.isEmpty()) return ""
        if (kind == Kind.REASONING) require(phase == 0) { "Interleaved reasoning cannot be preserved" }
        else { require(phase <= 1) { "Interleaved text cannot be preserved" }; phase = 1 }
        account(item.value, value)
        val all = if (kind == Kind.REASONING) thought else text
        all.append(value)
        val name = if (kind == Kind.REASONING) "reasoning_content" else "content"
        return chunk(obj(name to str(value)), null, null)
    }
    private fun reconcileArguments(item: Item, value: String) {
        val current = item.value.toString()
        if (item.argumentsSeen) require(current == value) { "Function arguments conflict with streamed fragments" }
        else { account(item.value, value); item.argumentsSeen = true }
    }
    private fun reconcileCall(item: Item, tool: JsonObject) {
        val id = tool.required("id"); val function = tool["function"] as? JsonObject ?: error("Invalid function")
        val name = function.required("name"); val arguments = function.text("arguments")
        require(id.length <= 1024 && name.length <= 1024) { "Function identity exceeds limit" }
        require(arguments.encodeToByteArray().size <= 1024 * 1024) { "Function arguments exceed limit" }
        val parsed = try { AcpRelayStrictJson.parse(arguments) } catch (_: Exception) { error("Invalid function arguments") }
        require(parsed is JsonObject) { "Function arguments must be a JSON object" }
        require(item.callId == null || item.callId == id) { "Function call ID changed" }
        require(item.name == null || item.name == name) { "Function name changed" }
        item.callId = id; item.name = name
        reconcileArguments(item, arguments)
    }

    private fun finish(raw: JsonElement?, incomplete: Boolean): String {
        val response = raw as? JsonObject ?: error("Responses terminal lacks response")
        trackResponse(response)
        require(response["status"].string() == if (incomplete) "incomplete" else "completed") { "Responses terminal status conflicts" }
        val decoded = decoder.convert("responses", response, model)
        val output = response["output"] as? JsonArray ?: error("Responses output is required")
        val message = decoded.getValue("choices").jsonArray.single().jsonObject.getValue("message").jsonObject
        val out = StringBuilder()
        for ((index, value) in output.withIndex()) {
            val rawItem = value as? JsonObject ?: error("Invalid final output item")
            val kind = kind(rawItem.required("type")); val item = itemAt(index.toLong(), kind)
            matchId(item, rawItem.value("id"))
            when (kind) {
                Kind.REASONING -> {
                    val parts = rawItem["summary"] as? JsonArray ?: error("Missing reasoning summary")
                    val full = parts.joinToString("") { (it as? JsonObject)?.text("text") ?: error("Invalid reasoning summary") }
                    out.append(mergeFull(item, kind, full))
                }
                Kind.MESSAGE -> {
                    val parts = rawItem["content"] as? JsonArray ?: error("Missing message content")
                    val full = parts.joinToString("") { (it as? JsonObject)?.text("text") ?: error("Invalid message content") }
                    out.append(mergeFull(item, kind, full))
                }
                Kind.FUNCTION -> {
                    val final = obj("id" to str(rawItem.required("call_id")), "function" to obj("name" to str(rawItem.required("name")), "arguments" to str(rawItem.text("arguments"))))
                    reconcileCall(item, final)
                }
            }
        }
        require(items.keys.all { it < output.size.toLong() }) { "Responses snapshot omitted an output item" }
        require(message["content"].string() == text.toString()) { "Responses text snapshot conflicts" }
        val finalReasoning = message["reasoning_content"].string()
        require((finalReasoning ?: "") == thought.toString()) { "Responses reasoning snapshot conflicts" }
        val tools = message["tool_calls"] as? JsonArray
        if (tools != null && tools.isNotEmpty()) {
            val mapped = tools.mapIndexed { index, value -> JsonObject(value.jsonObject + ("index" to JsonPrimitive(index))) }
            out.append(chunk(obj("tool_calls" to JsonArray(mapped)), null, null))
        }
        val choice = decoded.getValue("choices").jsonArray.single().jsonObject
        val finish = choice.required("finish_reason")
        out.append(chunk(JsonObject(emptyMap()), finish, decoded["usage"] as? JsonObject))
        out.append("data: [DONE]\n\n")
        terminal = true
        return out.toString()
    }
    private fun item(event: JsonObject, kind: Kind): Item {
        val partKey = if (kind == Kind.MESSAGE) "content_index" else "summary_index"
        event.value(partKey)?.let { require(integer(it, partKey) == 0L) { "Multiple content parts cannot be preserved" } }
        val item = itemAt(index(event), kind)
        matchId(item, event.value("item_id"))
        return item
    }
    private fun itemAt(index: Long, kind: Kind): Item {
        items[index]?.let { require(it.kind == kind) { "Responses item type changed" }; return it }
        require(items.size < 256 && index < 10_000) { "Too many Responses items" }
        when (kind) {
            Kind.REASONING -> require(phase == 0) { "Interleaved reasoning cannot be preserved" }
            Kind.MESSAGE -> { require(phase <= 1) { "Interleaved text cannot be preserved" }; phase = 1 }
            Kind.FUNCTION -> phase = 2
        }
        return Item(index, kind).also { items[index] = it }
    }
    private fun matchId(item: Item, raw: JsonElement?) {
        if (raw == null || raw == JsonNull) return
        val id = raw.string()?.takeIf { it.isNotEmpty() && it.length <= 1024 } ?: error("Invalid item ID")
        require(item.id == null || item.id == id) { "Responses item ID changed" }
        val existing = itemIds[id]
        require(existing == null || existing == item.index) { "Duplicate Responses item ID" }
        itemIds[id] = item.index; item.id = id
    }
    private fun kind(type: String) = when (type) {
        "reasoning" -> Kind.REASONING
        "message" -> Kind.MESSAGE
        "function_call" -> Kind.FUNCTION
        else -> error("Unsupported Responses output item")
    }
    private fun index(event: JsonObject) = event.value("output_index")?.let { integer(it, "output index") } ?: 0L
    private fun account(target: StringBuilder, value: String) {
        val bytes = value.encodeToByteArray().size
        require(value.length <= 4 * 1024 * 1024 && target.length.toLong() + value.length <= 4L * 1024 * 1024 &&
            totalBytes + bytes <= 8L * 1024 * 1024) { "Responses stream exceeds limit" }
        target.append(value); totalBytes += bytes
    }
    private fun chunk(delta: JsonObject, finish: String?, usage: JsonObject?): String {
        val choice = obj("index" to num(0), "delta" to delta, "finish_reason" to (finish?.let(::str) ?: JsonNull))
        val body = mutableMapOf<String, JsonElement>("id" to str(id), "object" to str("chat.completion.chunk"), "model" to str(model), "choices" to JsonArray(listOf(choice)))
        usage?.let { body["usage"] = it }
        return "data: ${JsonObject(body)}\n\n"
    }
    private fun eventKeys(event: JsonObject, vararg fields: String) = keys(event, setOf("type", "sequence_number") + fields, "Responses event")
    private fun emptyArray(obj: JsonObject, key: String) {
        obj.value(key)?.let { raw -> val array = raw as? JsonArray ?: error("$key must be an array"); require(array.isEmpty()) { "$key cannot be preserved" } }
    }
    /** Bound nesting and reject duplicate names before the JSON parser could discard them. */
    private fun keys(value: JsonObject, allowed: Set<String>, context: String) {
        for ((key, item) in value) require(item == JsonNull || key in allowed) { "Unsupported $context field: $key" }
    }
    private fun JsonObject.required(key: String): String = this[key].string()?.takeIf { it.isNotEmpty() } ?: error("Expected a non-empty $key")
    private fun JsonObject.text(key: String): String = this[key].string() ?: error("Expected text in $key")
    private fun JsonObject.value(key: String): JsonElement? = this[key]?.takeUnless { it == JsonNull }
    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun integer(value: JsonElement, name: String): Long = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 } ?: error("Invalid $name")
    private fun obj(vararg values: Pair<String, JsonElement>) = JsonObject(mapOf(*values))
    private fun str(value: String) = JsonPrimitive(value)
    private fun num(value: Int) = JsonPrimitive(value)
}
