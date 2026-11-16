/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpMessagesStreamToChatTest {
    private fun stream() = AcpMessagesStreamToChat("messenger/m", AcpNativeToChat { "chatcmpl_snapshot" }) { "chatcmpl_stream" }
    private fun data(wire: String): List<JsonObject> = wire.lineSequence().filter { it.startsWith("data: ") && it != "data: [DONE]" }
        .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }.toList()
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun event(type: String, vararg fields: Pair<String, JsonElement>): String = JsonObject(mapOf("type" to JsonPrimitive(type)) + fields.toMap()).toString()
    private fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf(*fields))
    private fun str(value: String) = JsonPrimitive(value)
    private fun num(value: Int) = JsonPrimitive(value)
    private fun start(usage: JsonObject? = null) = event("message_start", "message" to buildJsonObject {
        put("id", "msg_1"); put("type", "message"); put("role", "assistant"); put("model", "upstream"); put("content", JsonArray(emptyList()));
        if (usage != null) put("usage", usage)
    })
    private fun block(index: Int, type: String, value: String = "") = event("content_block_start", "index" to num(index), "content_block" to when (type) {
        "text" -> obj("type" to str("text"), "text" to str(value))
        "thinking" -> obj("type" to str("thinking"), "thinking" to str(value))
        else -> error("Invalid fixture")
    })
    private fun tool(index: Int, id: String = "c", input: JsonObject = JsonObject(emptyMap())) = event("content_block_start", "index" to num(index),
        "content_block" to obj("type" to str("tool_use"), "id" to str(id), "name" to str("read"), "input" to input))
    private fun fragment(index: Int, type: String, field: String, text: String) = event("content_block_delta", "index" to num(index), "delta" to obj("type" to str(type), field to str(text)))
    private fun close(index: Int) = event("content_block_stop", "index" to num(index))
    private fun end(reason: String? = null, usage: JsonObject? = null) = event("message_delta", "delta" to (if (reason == null) JsonObject(emptyMap()) else obj("stop_reason" to str(reason))),
        "usage" to (usage ?: JsonNull))
    private fun delta(wire: String) = data(wire).single().getValue("choices").jsonArray.single().jsonObject.getValue("delta").jsonObject

    @Test fun waitsForMessageStopAndMergesUsageAfterFinishReason() {
        val codec = stream()
        assertEquals("", codec.feed(start(json("""{"input_tokens":10,"output_tokens":1,"cache_read_input_tokens":3,"cache_creation_input_tokens":0}"""))))
        assertEquals("", codec.feed(block(0, "text")))
        assertEquals("hello", delta(codec.feed(fragment(0, "text_delta", "text", "hello"))).getValue("content").jsonPrimitive.content)
        codec.feed(close(0)); codec.feed(tool(1))
        assertEquals("", codec.feed(fragment(1, "input_json_delta", "partial_json", "{\"path\":")))
        assertEquals("", codec.feed(fragment(1, "input_json_delta", "partial_json", "\"file\"}")))
        codec.feed(close(1)); codec.feed(end("tool_use", json("""{"output_tokens":4}""")))
        codec.feed(end(usage = json("""{"output_tokens":5}""")))
        assertFalse(codec.isFinished)
        val terminal = codec.feed(event("message_stop"))
        val chunks = data(terminal)
        assertEquals(2, chunks.size)
        val call = chunks.first().getValue("choices").jsonArray.single().jsonObject.getValue("delta").jsonObject.getValue("tool_calls").jsonArray.single().jsonObject
        assertEquals("{\"path\":\"file\"}", call.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content)
        val final = chunks.last()
        assertEquals("tool_calls", final.getValue("choices").jsonArray.single().jsonObject.getValue("finish_reason").jsonPrimitive.content)
        val usage = final.getValue("usage").jsonObject
        assertEquals(13, usage.getValue("prompt_tokens").jsonPrimitive.int)
        assertEquals(5, usage.getValue("completion_tokens").jsonPrimitive.int)
        assertEquals(18, usage.getValue("total_tokens").jsonPrimitive.int)
        assertEquals(3, usage.getValue("prompt_tokens_details").jsonObject.getValue("cached_tokens").jsonPrimitive.int)
        assertTrue(terminal.endsWith("data: [DONE]\n\n"))
        assertFails { codec.feed(event("message_stop")) }
        assertEquals("", codec.endOfStream())
    }

    @Test fun emitsThinkingBeforeTextAndPreservesEmptyReasoningMarker() {
        val codec = stream(); codec.feed(start())
        assertEquals("", delta(codec.feed(block(0, "thinking"))).getValue("reasoning_content").jsonPrimitive.content)
        assertEquals("why", delta(codec.feed(fragment(0, "thinking_delta", "thinking", "why"))).getValue("reasoning_content").jsonPrimitive.content)
        codec.feed(close(0)); codec.feed(block(1, "text"))
        assertEquals("answer", delta(codec.feed(fragment(1, "text_delta", "text", "answer"))).getValue("content").jsonPrimitive.content)
        codec.feed(close(1)); codec.feed(end("end_turn"))
        val final = data(codec.feed(event("message_stop"))).single()
        assertEquals("stop", final.getValue("choices").jsonArray.single().jsonObject.getValue("finish_reason").jsonPrimitive.content)
        assertFalse("usage" in final)
    }

    @Test fun completeInitialToolObjectAndMaxTokensAreSupported() {
        val codec = stream(); codec.feed(start()); codec.feed(tool(0, input = json("""{"path":"file"}"""))); codec.feed(close(0)); codec.feed(end("tool_use"))
        val tools = data(codec.feed(event("message_stop"))).first().getValue("choices").jsonArray.single().jsonObject.getValue("delta").jsonObject.getValue("tool_calls").jsonArray
        assertEquals(json("""{"path":"file"}"""), Json.parseToJsonElement(tools.single().jsonObject.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content))
        val length = stream(); length.feed(start()); length.feed(end("max_tokens"))
        assertEquals("length", data(length.feed(event("message_stop"))).single().getValue("choices").jsonArray.single().jsonObject.getValue("finish_reason").jsonPrimitive.content)
    }

    @Test fun eofAndBareDoneCannotProduceSuccessfulTerminal() {
        val eof = stream(); eof.feed(start()); eof.feed(end("end_turn"))
        val error = eof.endOfStream(); assertTrue(data(error).single().containsKey("error")); assertFalse(error.contains("[DONE]"))
        val bare = stream(); bare.feed(start()); assertFails { bare.feed("[DONE]") }
        assertTrue(data(bare.fail("invalid upstream")).single().containsKey("error"))
        assertFails { bare.feed(event("message_stop")) }
    }

    @Test fun rejectsSignaturesCitationsUnknownEventsAndInterleaving() {
        fun reject(events: List<String>) {
            val codec = stream(); assertFails { events.forEach { codec.feed(it) } }
            assertTrue(data(codec.fail("invalid upstream")).single().containsKey("error"))
        }
        reject(listOf(start(), event("content_block_start", "index" to num(0), "content_block" to obj("type" to str("thinking"), "thinking" to str(""), "signature" to str("secret")))))
        reject(listOf(start(), block(0, "text"), event("content_block_delta", "index" to num(0), "delta" to obj("type" to str("citations_delta"), "citation" to JsonObject(emptyMap())))))
        reject(listOf(start(), block(0, "text"), close(0), block(1, "thinking")))
        reject(listOf(start(), tool(0), close(0), block(1, "text")))
        reject(listOf(start(), event("mystery")))
        reject(listOf(start(), block(1, "text")))
        reject(listOf(start(), block(0, "text"), event("message_stop")))
        reject(listOf(start(), end("end_turn"), block(0, "text")))
        reject(listOf(start(), end("pause_turn")))
        reject(listOf(start(), end("end_turn"), end("max_tokens")))
        reject(listOf(start(), event("message_delta", "delta" to obj("stop_reason" to str("end_turn"), "stop_sequence" to str("END")))))
        reject(listOf(start(), """{"type":"message_stop","type":"message_stop"}"""))
    }

    @Test fun rejectsMalformedIncompleteDuplicateAndAmbiguousToolCalls() {
        fun reject(args: String) {
            val codec = stream(); codec.feed(start()); codec.feed(tool(0)); codec.feed(fragment(0, "input_json_delta", "partial_json", args))
            assertFails { codec.feed(close(0)) }
            assertTrue(data(codec.fail("invalid tool")).single().containsKey("error"))
        }
        for (args in listOf("{", "[]", "{\"x\":1,\"x\":2}")) reject(args)
        val incomplete = stream(); incomplete.feed(start()); incomplete.feed(tool(0)); assertFails { incomplete.feed(event("message_stop")) }
        val duplicate = stream(); duplicate.feed(start()); duplicate.feed(tool(0)); duplicate.feed(close(0))
        assertFails { duplicate.feed(tool(1)) }
        val ambiguous = stream(); ambiguous.feed(start()); ambiguous.feed(tool(0, input = json("""{"x":1}""")))
        assertFails { ambiguous.feed(fragment(0, "input_json_delta", "partial_json", "{}")) }
    }

    @Test fun rejectsUnrepresentableOrRegressingUsage() {
        val writes = stream()
        assertFails { writes.feed(start(json("""{"input_tokens":1,"output_tokens":0,"cache_creation_input_tokens":1}"""))) }
        val decrease = stream(); decrease.feed(start(json("""{"input_tokens":3,"output_tokens":4}""")))
        assertFails { decrease.feed(end("end_turn", json("""{"output_tokens":3}"""))) }
        val unknown = stream(); unknown.feed(start())
        assertFails { unknown.feed(end("end_turn", json("""{"input_tokens":1,"output_tokens":1,"server_tool_use":{"web_search_requests":1}}"""))) }
        val missing = stream(); missing.feed(start(json("""{"output_tokens":1}"""))); missing.feed(end("end_turn"))
        assertFails { missing.feed(event("message_stop")) }
    }
}
