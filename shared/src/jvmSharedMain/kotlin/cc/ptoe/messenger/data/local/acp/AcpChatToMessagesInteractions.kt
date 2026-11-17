/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.util.UUID
import kotlinx.serialization.json.*

/** Non-streaming Chat -> Messages / Interactions; rejects output that cannot be represented. */
internal class AcpChatToMessagesInteractions(private val nextId: () -> String = { UUID.randomUUID().toString().replace("-", "") }) {
    fun convert(payload: JsonObject, target: AcpRelayFormat, model: String): JsonObject {
        require(target == AcpRelayFormat.MESSAGES || target == AcpRelayFormat.INTERACTIONS)
        val projected = AcpChatToResponses().convert(payload, model, emptySet())
        val reason = payload["choices"]!!.jsonArray.single().jsonObject["finish_reason"]!!.jsonPrimitive.content
        if (target == AcpRelayFormat.INTERACTIONS) require(reason != "length") { "Incomplete output cannot be represented in Interactions" }
        val content = mutableListOf<JsonObject>()
        for (entry in projected["output"]!!.jsonArray) {
            val item = entry.jsonObject
            when (item["type"]!!.jsonPrimitive.content) {
                "reasoning" -> error("Reasoning output cannot be represented")
                "message" -> for (part in item["content"]!!.jsonArray) {
                    val block = part.jsonObject
                    content += buildJsonObject { put("type", "text"); put("text", block["text"]!!) }
                }
                "function_call" -> {
                    val args = try { AcpRelayStrictJson.parse(item["arguments"]!!.jsonPrimitive.content) as? JsonObject }
                        catch (_: Exception) { null } ?: error("Function arguments must be an unambiguous JSON object")
                    content += buildJsonObject {
                        put("type", if (target == AcpRelayFormat.MESSAGES) "tool_use" else "function_call")
                        put("id", item["call_id"]!!); put("name", item["name"]!!)
                        put(if (target == AcpRelayFormat.MESSAGES) "input" else "arguments", args)
                    }
                }
                else -> error("Upstream output cannot be converted")
            }
        }
        val usage = projected["usage"] as? JsonObject
        if (usage != null) {
            val input = usage["input_tokens"]!!.jsonPrimitive.long
            val output = usage["output_tokens"]!!.jsonPrimitive.long
            val total = usage["total_tokens"]!!.jsonPrimitive.long
            if (target == AcpRelayFormat.MESSAGES) require(total == Math.addExact(input, output)) { "Total usage cannot be represented" }
            for ((section, field) in listOf("input_tokens_details" to "cached_tokens", "output_tokens_details" to "reasoning_tokens")) {
                val value = usage[section]!!.jsonObject[field]
                require(value is JsonPrimitive && value.longOrNull == 0L) { "Detailed usage cannot be represented" }
            }
        }
        val id = nextId()
        require(id.isNotEmpty() && id.length <= 256 && id.none(Char::isISOControl))
        return if (target == AcpRelayFormat.MESSAGES) buildJsonObject {
            put("id", "msg_$id"); put("type", "message"); put("role", "assistant"); put("model", model)
            put("content", JsonArray(content)); put("stop_reason", when (reason) { "tool_calls" -> "tool_use"; "length" -> "max_tokens"; else -> "end_turn" })
            put("usage", buildJsonObject { put("input_tokens", usage?.get("input_tokens") ?: JsonNull); put("output_tokens", usage?.get("output_tokens") ?: JsonNull) })
        } else buildJsonObject {
            put("id", "interaction_$id"); put("status", if (reason == "tool_calls") "requires_action" else "completed"); put("model", model); put("outputs", JsonArray(content))
            put("usage", buildJsonObject { put("total_input_tokens", usage?.get("input_tokens") ?: JsonNull)
                put("total_output_tokens", usage?.get("output_tokens") ?: JsonNull); put("total_tokens", usage?.get("total_tokens") ?: JsonNull) })
        }
    }
}
