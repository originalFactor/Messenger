/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*

/** Projects internally generated Responses events to Messages or Interactions SSE. */
internal class AcpResponsesEventProjector(private val target: AcpRelayFormat, private val model: String) {
    init { require(target in setOf(AcpRelayFormat.CHAT, AcpRelayFormat.MESSAGES, AcpRelayFormat.INTERACTIONS)) }
    private val framer = AcpRelaySseFramer()
    private val chatToolIndexes = mutableMapOf<Long, Int>()
    private var terminal = false
    val isFinished get() = terminal

    fun project(events: String): String = buildString {
        for (data in framer.feed(events.encodeToByteArray())) {
            if (terminal) break
            val value = AcpRelayStrictJson.parse(data) as? JsonObject ?: error("Invalid internal response event")
            append(projectEvent(value))
        }
    }
    fun fail(): String {
        if (terminal) return ""
        terminal = true
        val value = if (target != AcpRelayFormat.INTERACTIONS) buildJsonObject {
            put("type", "error"); put("error", buildJsonObject { put("code", "upstream_error"); put("message", "The provider stream could not be converted") })
        } else buildJsonObject {
            put("event_type", "error"); put("error", buildJsonObject { put("code", "upstream_error"); put("message", "The provider stream could not be converted") })
        }
        return "event: error\ndata: $value\n\n"
    }
    private fun projectEvent(v: JsonObject): String {
        val kind = v["type"]?.jsonPrimitive?.contentOrNull ?: error("Missing response event type")
        val index = v["output_index"] ?: JsonPrimitive(0)
        fun obj(vararg values: Pair<String, JsonElement>) = JsonObject(mapOf(*values))
        fun str(value: String) = JsonPrimitive(value)
        fun ev(type: String, value: JsonObject) = "event: $type\ndata: $value\n\n"
        fun field(name: String) = v[name] ?: JsonNull
        if (kind == "response.failed") {
            terminal = true
            val response = v["response"] as? JsonObject
            val error = response?.get("error") ?: obj("code" to str("upstream_error"), "message" to str("Provider stream failed"))
            return ev("error", if (target != AcpRelayFormat.INTERACTIONS) obj("type" to str("error"), "error" to error)
                else obj("event_type" to str("error"), "error" to error))
        }
        if (kind == "response.completed" || kind == "response.incomplete") {
            val response = v["response"] as? JsonObject ?: error("Missing completed response")
            val usage = response["usage"] as? JsonObject
            if (usage != null && target != AcpRelayFormat.CHAT) {
                for ((section, field) in listOf("input_tokens_details" to "cached_tokens", "output_tokens_details" to "reasoning_tokens")) {
                    val count = (usage[section] as? JsonObject)?.get(field)
                    require(count is JsonPrimitive && count.longOrNull == 0L) { "Detailed usage cannot be represented" }
                }
                if (target == AcpRelayFormat.MESSAGES) {
                    val input = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: error("Invalid usage")
                    val output = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: error("Invalid usage")
                    val total = usage["total_tokens"]?.jsonPrimitive?.longOrNull ?: error("Invalid usage")
                    require(Math.addExact(input, output) == total) { "Total usage cannot be represented" }
                }
            }
            val output = response["output"] as? JsonArray ?: error("Missing response output")
            val calls = output.any { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "function_call" }
            val reason = if (kind == "response.incomplete") "length" else if (calls) "tool_calls" else "stop"
            if (target == AcpRelayFormat.CHAT) {
                val values = mutableMapOf<String, JsonElement>("id" to response.getValue("id"), "object" to str("chat.completion.chunk"),
                    "model" to str(model), "choices" to JsonArray(listOf(obj("index" to JsonPrimitive(0), "delta" to JsonObject(emptyMap()), "finish_reason" to str(reason)))))
                if (usage != null) {
                    val mapped = mutableMapOf<String, JsonElement>("prompt_tokens" to (usage["input_tokens"] ?: JsonNull),
                        "completion_tokens" to (usage["output_tokens"] ?: JsonNull), "total_tokens" to (usage["total_tokens"] ?: JsonNull))
                    usage["input_tokens_details"]?.let { mapped["prompt_tokens_details"] = it }
                    usage["output_tokens_details"]?.let { mapped["completion_tokens_details"] = it }
                    values["usage"] = JsonObject(mapped)
                }
                terminal = true
                return "data: ${JsonObject(values)}\n\ndata: [DONE]\n\n"
            }
            if (target == AcpRelayFormat.MESSAGES) {
                terminal = true
                return ev("message_delta", obj("type" to str("message_delta"),
                "delta" to obj("stop_reason" to str(when (reason) { "length" -> "max_tokens"; "tool_calls" -> "tool_use"; else -> "end_turn" }), "stop_sequence" to JsonNull),
                "usage" to obj("input_tokens" to (usage?.get("input_tokens") ?: JsonNull), "output_tokens" to (usage?.get("output_tokens") ?: JsonNull)))) +
                ev("message_stop", obj("type" to str("message_stop")))
            }
            val steps = output.map { entry ->
                val item = entry as? JsonObject ?: error("Invalid output item")
                when (item["type"]?.jsonPrimitive?.contentOrNull) {
                    "function_call" -> {
                        val args = AcpRelayStrictJson.parse(item["arguments"]!!.jsonPrimitive.content) as? JsonObject ?: error("Invalid function arguments")
                        obj("type" to str("function_call"), "id" to item.getValue("call_id"), "name" to item.getValue("name"), "arguments" to args)
                    }
                    "reasoning" -> obj("type" to str("thought"), "summary" to item.getValue("summary"))
                    "message" -> obj("type" to str("model_output"), "content" to item.getValue("content"))
                    else -> error("Unsupported output item")
                }
            }
            val interaction = obj("id" to response.getValue("id"), "model" to str(model), "status" to (if (reason == "tool_calls") str("requires_action") else response.getValue("status")), "steps" to JsonArray(steps),
                "usage" to obj("total_input_tokens" to (usage?.get("input_tokens") ?: JsonNull),
                    "total_output_tokens" to (usage?.get("output_tokens") ?: JsonNull), "total_tokens" to (usage?.get("total_tokens") ?: JsonNull)))
            terminal = true
            return ev("interaction.completed", obj("event_type" to str("interaction.completed"), "interaction" to interaction))
        }
        val item = v["item"] as? JsonObject
        val itemType = item?.get("type")?.jsonPrimitive?.contentOrNull
        if (target != AcpRelayFormat.CHAT && kind == "response.output_item.done" && itemType == "function_call") {
            val arguments = item?.get("arguments")?.jsonPrimitive?.content ?: error("Missing function arguments")
            require(AcpRelayStrictJson.parse(arguments) is JsonObject) { "Function arguments must be a JSON object" }
        }
        if (target == AcpRelayFormat.CHAT) {
            if (kind == "response.output_item.added" && itemType == "function_call") {
                val outputIndex = index.jsonPrimitive.longOrNull ?: error("Invalid output index")
                check(outputIndex >= 0 && outputIndex !in chatToolIndexes) { "Duplicate output item" }
                chatToolIndexes[outputIndex] = chatToolIndexes.size
            }
            val delta = when {
                kind == "response.output_text.delta" -> obj("content" to field("delta"))
                kind == "response.reasoning_summary_text.delta" -> obj("reasoning_content" to field("delta"))
                kind == "response.output_item.done" && itemType == "function_call" -> {
                    val outputIndex = index.jsonPrimitive.longOrNull ?: error("Invalid output index")
                    val toolIndex = chatToolIndexes[outputIndex] ?: error("Unknown output item")
                    obj("tool_calls" to JsonArray(listOf(obj("index" to JsonPrimitive(toolIndex), "id" to item!!.getValue("call_id"), "type" to str("function"),
                        "function" to obj("name" to item.getValue("name"), "arguments" to item.getValue("arguments"))))))
                }
                else -> null
            }
            if (delta == null) return ""
            val record = obj("id" to str("relay"), "object" to str("chat.completion.chunk"), "model" to str(model),
                "choices" to JsonArray(listOf(obj("index" to JsonPrimitive(0), "delta" to delta, "finish_reason" to JsonNull))))
            return "data: $record\n\n"
        }
        if (target == AcpRelayFormat.MESSAGES) return when (kind) {
            "response.created" -> {
                val response = v["response"]!!.jsonObject
                ev("message_start", obj("type" to str("message_start"), "message" to obj("id" to response.getValue("id"), "type" to str("message"),
                    "role" to str("assistant"), "model" to str(model), "content" to JsonArray(emptyList()), "stop_reason" to JsonNull,
                    "usage" to obj("input_tokens" to JsonPrimitive(0), "output_tokens" to JsonPrimitive(0)))))
            }
            "response.content_part.added" -> ev("content_block_start", obj("type" to str("content_block_start"), "index" to index, "content_block" to obj("type" to str("text"), "text" to str(""))))
            "response.output_item.added" -> when (itemType) {
                "function_call" -> ev("content_block_start", obj("type" to str("content_block_start"), "index" to index,
                    "content_block" to obj("type" to str("tool_use"), "id" to item!!.getValue("call_id"), "name" to item.getValue("name"), "input" to JsonObject(emptyMap()))))
                "reasoning" -> ev("content_block_start", obj("type" to str("content_block_start"), "index" to index,
                    "content_block" to obj("type" to str("thinking"), "thinking" to str(""))))
                "message" -> ""
                else -> error("Unsupported output item")
            }
            "response.output_text.delta", "response.function_call_arguments.delta", "response.reasoning_summary_text.delta" -> {
                val delta = when (kind) {
                    "response.output_text.delta" -> obj("type" to str("text_delta"), "text" to field("delta"))
                    "response.function_call_arguments.delta" -> obj("type" to str("input_json_delta"), "partial_json" to field("delta"))
                    else -> obj("type" to str("thinking_delta"), "thinking" to field("delta"))
                }
                ev("content_block_delta", obj("type" to str("content_block_delta"), "index" to index, "delta" to delta))
            }
            "response.output_item.done" -> ev("content_block_stop", obj("type" to str("content_block_stop"), "index" to index))
            else -> ""
        }
        return when (kind) {
            "response.created" -> ev("interaction.created", obj("event_type" to str("interaction.created"), "interaction" to obj("id" to v["response"]!!.jsonObject.getValue("id"), "model" to str(model), "status" to str("in_progress"))))
            "response.output_item.added" -> {
                val step = when (itemType) {
                    "function_call" -> obj("type" to str("function_call"), "id" to item!!.getValue("call_id"), "name" to item.getValue("name"), "arguments" to JsonObject(emptyMap()))
                    "reasoning" -> obj("type" to str("thought"), "summary" to JsonArray(emptyList()))
                    "message" -> obj("type" to str("model_output"), "content" to JsonArray(emptyList()))
                    else -> error("Unsupported output item")
                }
                ev("step.start", obj("event_type" to str("step.start"), "index" to index, "step" to step))
            }
            "response.output_text.delta", "response.function_call_arguments.delta", "response.reasoning_summary_text.delta" -> {
                val delta = when (kind) {
                    "response.output_text.delta" -> obj("type" to str("text"), "text" to field("delta"))
                    "response.function_call_arguments.delta" -> obj("type" to str("arguments_delta"), "arguments" to field("delta"))
                    else -> obj("type" to str("thought_summary"), "content" to obj("type" to str("text"), "text" to field("delta")))
                }
                ev("step.delta", obj("event_type" to str("step.delta"), "index" to index, "delta" to delta))
            }
            "response.output_item.done" -> ev("step.stop", obj("event_type" to str("step.stop"), "index" to index))
            else -> ""
        }
    }
}
