/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.util.UUID
import kotlinx.serialization.json.*

/** One sequentially used instance per Interactions stream. Input values are already framed SSE data. */
internal class AcpInteractionsStreamToChat(
    private val model: String,
    private val decoder: AcpNativeToChat = AcpNativeToChat(),
    responseId: () -> String = { "chatcmpl_" + UUID.randomUUID().toString() },
) {
    private enum class Kind { THOUGHT, TEXT, TOOL }
    private class Step(val index: Long, val kind: Kind) {
        var id: String? = null
        var name: String? = null
        var initialInput: JsonObject? = null
        val content = StringBuilder()
        var fragments = false
        var closed = false
        var arguments: String? = null
        var parsed: JsonObject? = null
    }
    private val id = responseId().also { require(it.startsWith("chatcmpl_") && it.length in 10..256 && it.none(Char::isISOControl)) { "Invalid response ID" } }
    private val steps = mutableListOf<Step>()
    private val callIds = mutableSetOf<String>()
    private val eventIds = mutableSetOf<String>()
    private val usage = mutableMapOf<String, JsonElement>()
    private var sawUsage = false
    private var eventIdBytes = 0L
    private var totalBytes = 0L
    private var eventCount = 0
    private var phase = 0
    private var active: Step? = null
    private var upstreamId: String? = null
    private var upstreamModel: String? = null
    private var createdSeen = false
    private var pendingStatus: String? = null
    private var terminal = false
    private var broken = false
    val isFinished: Boolean get() = terminal

    /** Exceptions poison this instance; the caller must emit fail() instead of resuming it. */
    fun feed(data: String): String {
        check(!terminal && !broken) { "Interactions stream is already finished or invalid" }
        return try {
            require(data.encodeToByteArray().size <= 8 * 1024 * 1024) { "Upstream SSE event exceeds limit" }
            require(++eventCount <= 65_536) { "Too many Interactions events" }
            require(data != "[DONE]") { "Upstream ended without an Interactions terminal event" }
            val event = try { AcpRelayStrictJson.parse(data) as? JsonObject } catch (_: Exception) { null } ?: error("Invalid upstream SSE JSON")
            val eventType = event.value("event_type")?.let { it.string() ?: error("Invalid event type") }
            val type = event.value("type")?.let { it.string() ?: error("Invalid event type") }
            require(eventType == null || type == null || eventType == type) { "Conflicting event types" }
            val kind = eventType ?: type ?: error("Interactions event type is missing")
            event.value("event_id")?.let { raw ->
                val value = raw.string()?.takeIf { it.isNotEmpty() && it.length <= 512 } ?: error("Invalid event ID")
                eventIdBytes += value.encodeToByteArray().size
                require(eventIdBytes <= 8L * 1024 * 1024 && eventIds.add(value)) { "Duplicate or excessive event IDs" }
            }
            when (kind) {
                "interaction.created", "interaction.in_progress" -> { eventKeys(event, "interaction"); initial(kind, event["interaction"]); "" }
                "interaction.status_update" -> { eventKeys(event, "interaction_id", "status", "usage", "continuation_token"); status(event); "" }
                "step.start" -> { eventKeys(event, "index", "step"); stepStart(event) }
                "step.delta" -> { eventKeys(event, "index", "delta", "usage"); event.value("usage")?.let(::mergeUsage); stepDelta(event) }
                "step.stop" -> { eventKeys(event, "index", "usage", "status"); event.value("usage")?.let(::mergeUsage); stepStop(event); "" }
                "interaction.completed" -> { eventKeys(event, "interaction"); finish(event["interaction"]) }
                "error" -> error("Upstream Interactions stream failed")
                else -> error("Unsupported Interactions event")
            }
        } catch (failure: Exception) { broken = true; throw failure }
    }
    fun endOfStream(): String = if (terminal) "" else fail("Upstream stream ended before its terminal event")
    /** The caller must pass a sanitized message; event IDs never leave this instance. */
    fun fail(message: String): String {
        if (terminal) return ""
        terminal = true
        return "data: " + obj("error" to obj("code" to str("upstream_error"), "message" to str(message.take(2048)))) + "\n\n"
    }

    private fun initial(type: String, raw: JsonElement?) {
        require(steps.isEmpty() && active == null && pendingStatus == null) { "Interactions initial event arrived after output" }
        if (type == "interaction.created") { check(!createdSeen) { "Duplicate interaction creation" }; createdSeen = true }
        val interaction = raw as? JsonObject ?: error("Interactions event lacks interaction")
        keys(interaction, setOf("id", "object", "created", "updated", "model", "status", "steps", "outputs", "usage", "continuation_token"), "initial interaction")
        require(interaction.value("continuation_token") == null) { "Interaction continuation cannot be preserved" }
        identity(interaction)
        interaction.value("status")?.let { require(it.string() in setOf("created", "in_progress")) { "Invalid initial interaction status" } }
        for (field in listOf("steps", "outputs")) interaction.value(field)?.let { require(it is JsonArray && it.isEmpty()) { "Initial interaction output cannot be preserved" } }
        interaction.value("usage")?.let(::mergeUsage)
    }
    private fun status(event: JsonObject) {
        val status = event.required("status")
        require(event.value("continuation_token") == null) { "Interaction continuation cannot be preserved" }
        trackId(event.required("interaction_id"))
        event.value("usage")?.let(::mergeUsage)
        when (status) {
            "in_progress" -> require(pendingStatus == null) { "Interaction status regressed" }
            "requires_action", "completed" -> {
                require(active == null && (pendingStatus == null || pendingStatus == status)) { "Conflicting interaction status" }
                pendingStatus = status
            }
            else -> error("Unsupported interaction status")
        }
    }
    private fun stepStart(event: JsonObject): String {
        require(active == null && pendingStatus == null) { "Interaction step started in an invalid state" }
        val index = event.index()
        require(steps.size < 256 && (steps.lastOrNull()?.index?.let { index > it } ?: true)) { "Invalid interaction step order" }
        val raw = event["step"] as? JsonObject ?: error("Interaction step is missing")
        val kind = when (raw.required("type")) {
            "thought" -> { require(phase == 0) { "Interleaved thought cannot be preserved" }; Kind.THOUGHT }
            "model_output" -> { require(phase <= 1) { "Interleaved text cannot be preserved" }; phase = 1; Kind.TEXT }
            "function_call" -> { phase = 2; Kind.TOOL }
            else -> error("Unsupported interaction step")
        }
        val step = Step(index, kind); steps += step; active = step
        return when (kind) {
            Kind.THOUGHT -> {
                keys(raw, setOf("type", "summary", "signature"), "thought step")
                require(raw.value("signature") == null) { "Thought signatures cannot be preserved" }
                raw.value("summary")?.let { require(it is JsonArray && it.isEmpty()) { "Initial thought summary cannot be preserved" } }
                chunk(obj("reasoning_content" to str("")), null, null)
            }
            Kind.TEXT -> {
                keys(raw, setOf("type", "content"), "model output step")
                raw.value("content")?.let { require(it is JsonArray && it.isEmpty()) { "Initial model content cannot be preserved" } }
                ""
            }
            Kind.TOOL -> {
                keys(raw, setOf("type", "id", "name", "arguments", "signature"), "function step")
                require(raw.value("signature") == null) { "Tool signatures cannot be preserved" }
                val callId = raw.required("id"); val name = raw.required("name")
                require(callId.length <= 1024 && name.length <= 1024 && callIds.size < 128 && callIds.add(callId)) { "Too many or duplicate tool calls" }
                step.id = callId; step.name = name
                raw.value("arguments")?.let { step.initialInput = it as? JsonObject ?: error("Function arguments must be an object") }
                ""
            }
        }
    }
    private fun stepDelta(event: JsonObject): String {
        require(pendingStatus == null) { "Interaction delta arrived after terminal status" }
        val step = active ?: error("Interaction step is unavailable")
        require(event.index() == step.index && !step.closed) { "Interaction step index changed" }
        val delta = event["delta"] as? JsonObject ?: error("Interaction delta is missing")
        return when (delta.required("type")) {
            "text" -> {
                keys(delta, setOf("type", "text", "annotations"), "text delta"); require(step.kind == Kind.TEXT) { "Invalid text delta" }
                emptyArray(delta, "annotations"); addText(step, delta.text("text"))
            }
            "thought_summary" -> {
                keys(delta, setOf("type", "content"), "thought delta"); require(step.kind == Kind.THOUGHT) { "Invalid thought delta" }
                val content = delta["content"] as? JsonObject ?: error("Thought summary is missing")
                keys(content, setOf("type", "text", "annotations"), "thought summary"); require(content.required("type") == "text") { "Unsupported thought summary" }
                emptyArray(content, "annotations"); addText(step, content.text("text"))
            }
            "arguments_delta" -> {
                keys(delta, setOf("type", "arguments"), "function delta")
                require(step.kind == Kind.TOOL && (step.initialInput == null || checkNotNull(step.initialInput).isEmpty())) { "Invalid function delta" }
                val fragment = delta.text("arguments"); account(step.content, fragment, 1024 * 1024); if (fragment.isNotEmpty()) step.fragments = true; ""
            }
            else -> error("Unsupported interaction delta")
        }
    }
    private fun stepStop(event: JsonObject) {
        event.value("status")?.let { require(it.string() in setOf("done", "completed")) { "Invalid interaction step status" } }
        require(pendingStatus == null) { "Interaction step stopped after terminal status" }
        val step = active ?: error("Interaction step is unavailable")
        require(event.index() == step.index && !step.closed) { "Interaction step index changed" }
        if (step.kind == Kind.TOOL) {
            val raw = if (step.fragments) step.content.toString() else step.initialInput?.toString() ?: error("Function arguments are unavailable")
            require(raw.encodeToByteArray().size <= 1024 * 1024) { "Function arguments exceed limit" }
            val value = try { AcpRelayStrictJson.parse(raw) } catch (_: Exception) { error("Invalid function arguments") }
            step.parsed = value as? JsonObject ?: error("Function arguments must be an object")
            if (!step.fragments) { totalBytes += raw.encodeToByteArray().size; require(totalBytes <= 8L * 1024 * 1024) { "Interactions stream exceeds limit" } }
            step.arguments = raw
        }
        step.closed = true; active = null
    }

    private fun finish(raw: JsonElement?): String {
        require(active == null && steps.all { it.closed }) { "Interaction ended with an incomplete step" }
        val interaction = raw as? JsonObject ?: error("Interactions terminal lacks interaction")
        keys(interaction, setOf("id", "object", "created", "updated", "model", "status", "steps", "outputs", "error", "incomplete_details", "usage", "continuation_token"), "terminal interaction")
        require(interaction.value("error") == null && interaction.value("continuation_token") == null) { "Interaction error or continuation cannot be preserved" }
        val terminalId = interaction.required("id"); trackId(terminalId); identity(interaction)
        interaction.value("usage")?.let(::mergeUsage)
        val status = interaction.required("status")
        require(status in setOf("completed", "requires_action", "incomplete")) { "Unsupported terminal interaction status" }
        require(pendingStatus == null || pendingStatus == status) { "Interaction terminal status conflicts" }
        val tools = steps.filter { it.kind == Kind.TOOL }
        require((status == "requires_action") == tools.isNotEmpty()) { "Interaction status conflicts with tool calls" }
        val rebuilt = JsonArray(steps.map { step ->
            when (step.kind) {
                Kind.THOUGHT -> obj("type" to str("thought"), "summary" to if (step.content.isEmpty()) JsonArray(emptyList()) else JsonArray(listOf(obj("type" to str("text"), "text" to str(step.content.toString())))))
                Kind.TEXT -> obj("type" to str("model_output"), "content" to if (step.content.isEmpty()) JsonArray(emptyList()) else JsonArray(listOf(obj("type" to str("text"), "text" to str(step.content.toString())))))
                Kind.TOOL -> obj("type" to str("function_call"), "id" to str(checkNotNull(step.id)), "name" to str(checkNotNull(step.name)), "arguments" to checkNotNull(step.parsed))
            }
        })
        val merged = interaction.toMutableMap().apply {
            remove("continuation_token")
            if (sawUsage) put("usage", JsonObject(usage))
        }
        val synthetic = merged.toMutableMap().apply { remove("outputs"); put("steps", rebuilt) }
        val expected = decoder.convert("interactions", JsonObject(synthetic), model)
        if (interaction.value("steps") != null || interaction.value("outputs") != null) {
            val actual = decoder.convert("interactions", JsonObject(merged), model)
            require(equivalent(expected, actual)) { "Interactions terminal snapshot conflicts with streamed steps" }
        }
        val choice = expected.getValue("choices").jsonArray.single().jsonObject
        val finish = choice.required("finish_reason")
        val out = StringBuilder()
        if (tools.isNotEmpty()) {
            val values = tools.mapIndexed { index, step -> obj("index" to num(index), "id" to str(checkNotNull(step.id)), "type" to str("function"),
                "function" to obj("name" to str(checkNotNull(step.name)), "arguments" to str(checkNotNull(step.arguments)))) }
            out.append(chunk(obj("tool_calls" to JsonArray(values)), null, null))
        }
        out.append(chunk(JsonObject(emptyMap()), finish, expected["usage"] as? JsonObject))
        out.append("data: [DONE]\n\n")
        terminal = true
        return out.toString()
    }
    private fun equivalent(left: JsonObject, right: JsonObject): Boolean {
        val a = left.getValue("choices").jsonArray.single().jsonObject
        val b = right.getValue("choices").jsonArray.single().jsonObject
        if (a["finish_reason"] != b["finish_reason"] || left["usage"] != right["usage"]) return false
        val x = a.getValue("message").jsonObject; val y = b.getValue("message").jsonObject
        if (x["role"] != y["role"] || x["content"] != y["content"] || x["reasoning_content"] != y["reasoning_content"]) return false
        val first = (x["tool_calls"] as? JsonArray).orEmpty(); val second = (y["tool_calls"] as? JsonArray).orEmpty()
        return first.size == second.size && first.zip(second).all { (u, v) ->
            val p = u.jsonObject; val q = v.jsonObject
            val pf = p.getValue("function").jsonObject; val qf = q.getValue("function").jsonObject
            p["id"] == q["id"] && p["type"] == q["type"] && pf["name"] == qf["name"] &&
                AcpRelayStrictJson.parse(pf.text("arguments")) == AcpRelayStrictJson.parse(qf.text("arguments"))
        }
    }
    private fun mergeUsage(raw: JsonElement) {
        val data = raw as? JsonObject ?: error("Invalid Interactions usage")
        keys(data, setOf("total_input_tokens", "total_output_tokens", "total_thought_tokens", "total_cached_tokens", "total_tool_use_tokens", "total_tokens"), "Interactions usage")
        for ((key, value) in data) {
            if (value == JsonNull) continue
            val current = integer(value, key)
            if (key == "total_tool_use_tokens") require(current == 0L) { "Tool-use token usage cannot be preserved" }
            val previous = usage[key]?.let { integer(it, key) }
            require(previous == null || current >= previous) { "Interactions usage decreased" }
            usage[key] = JsonPrimitive(current)
        }
        sawUsage = true
    }
    private fun identity(interaction: JsonObject) {
        interaction.value("id")?.let { trackId(it.string() ?: error("Invalid interaction ID")) }
        interaction.value("model")?.let { raw ->
            val value = raw.string()?.takeIf { it.isNotEmpty() && it.length <= 1024 } ?: error("Invalid interaction model")
            require(upstreamModel == null || upstreamModel == value) { "Interaction model changed" }; upstreamModel = value
        }
        interaction.value("object")?.let { require(it.string() == "interaction") { "Invalid Interactions object" } }
        for (key in listOf("created", "updated")) interaction.value(key)?.let { require(it.string() != null) { "Invalid interaction timestamp" } }
    }
    private fun trackId(value: String) {
        require(value.isNotEmpty() && value.length <= 1024) { "Invalid interaction ID" }
        require(upstreamId == null || upstreamId == value) { "Interaction ID changed" }; upstreamId = value
    }
    private fun addText(step: Step, value: String): String {
        if (value.isEmpty()) return ""
        account(step.content, value, 4 * 1024 * 1024)
        return chunk(obj((if (step.kind == Kind.THOUGHT) "reasoning_content" else "content") to str(value)), null, null)
    }
    private fun account(target: StringBuilder, value: String, max: Int) {
        val bytes = value.encodeToByteArray().size
        require(target.length.toLong() + value.length <= max && totalBytes + bytes <= 8L * 1024 * 1024) { "Interactions stream exceeds limit" }
        target.append(value); totalBytes += bytes
    }
    private fun chunk(delta: JsonObject, finish: String?, usage: JsonObject?): String {
        val choice = obj("index" to num(0), "delta" to delta, "finish_reason" to (finish?.let(::str) ?: JsonNull))
        val body = mutableMapOf<String, JsonElement>("id" to str(id), "object" to str("chat.completion.chunk"), "model" to str(model), "choices" to JsonArray(listOf(choice)))
        usage?.let { body["usage"] = it }
        return "data: ${JsonObject(body)}\n\n"
    }
    private fun JsonObject.index(): Long = this["index"]?.let { integer(it, "step index") } ?: error("Interaction step index is missing")
    private fun integer(value: JsonElement, name: String): Long = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 } ?: error("Invalid $name")
    private fun eventKeys(event: JsonObject, vararg fields: String) = keys(event, setOf("event_type", "type", "event_id") + fields, "Interactions event")
    private fun emptyArray(obj: JsonObject, key: String) {
        obj.value(key)?.let { raw -> val array = raw as? JsonArray ?: error("$key must be an array"); require(array.isEmpty()) { "$key cannot be preserved" } }
    }
    private fun keys(value: JsonObject, allowed: Set<String>, context: String) {
        for ((key, item) in value) require(item == JsonNull || key in allowed) { "Unsupported $context field: $key" }
    }
    private fun JsonObject.required(key: String): String = this[key].string()?.takeIf { it.isNotEmpty() } ?: error("Expected a non-empty $key")
    private fun JsonObject.text(key: String): String = this[key].string() ?: error("Expected text in $key")
    private fun JsonObject.value(key: String): JsonElement? = this[key]?.takeUnless { it == JsonNull }
    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun obj(vararg values: Pair<String, JsonElement>) = JsonObject(mapOf(*values))
    private fun str(value: String) = JsonPrimitive(value)
    private fun num(value: Int) = JsonPrimitive(value)
}
