/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpResponsesStreamToChatTest {
    private fun stream() = AcpResponsesStreamToChat("messenger/m", AcpNativeToChat { "chatcmpl_snapshot" }) { "chatcmpl_stream" }
    private fun data(wire: String): List<JsonObject> = wire.lineSequence().filter { it.startsWith("data: ") && it != "data: [DONE]" }
        .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }.toList()
    private fun event(type: String, vararg fields: Pair<String, JsonElement>): String = JsonObject(mapOf("type" to JsonPrimitive(type)) + fields.toMap()).toString()
    private fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf(*fields))
    private fun str(value: String) = JsonPrimitive(value)
    private fun num(value: Int) = JsonPrimitive(value)
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun response(output: JsonArray, status: String = "completed", usage: JsonObject? = null): JsonObject = buildJsonObject {
        put("id", "resp_1"); put("object", "response"); put("status", status); put("output", output)
        if (status == "incomplete") put("incomplete_details", obj("reason" to str("max_output_tokens")))
        if (usage != null) put("usage", usage)
    }
    private fun reasoning(value: String) = json("""{"id":"r","type":"reasoning","summary":[{"type":"summary_text","text":"$value"}]}""")
    private fun message(value: String) = obj("id" to str("m"), "type" to str("message"), "role" to str("assistant"), "status" to str("completed"),
        "content" to JsonArray(listOf(obj("type" to str("output_text"), "text" to str(value), "annotations" to JsonArray(emptyList())))))
    private fun function(id: String, callId: String, name: String, arguments: String) = obj("id" to str(id), "type" to str("function_call"),
        "status" to str("completed"), "call_id" to str(callId), "name" to str(name), "arguments" to str(arguments))
    private fun delta(wire: String): JsonObject = data(wire).single().getValue("choices").jsonArray.single().jsonObject.getValue("delta").jsonObject

    @Test fun streamsReasoningAndTextThenCommitsFragmentedToolAndUsage() {
        val codec = stream()
        assertEquals("", codec.feed(event("response.created", "response" to obj("id" to str("resp_1"), "status" to str("in_progress"), "output" to JsonArray(emptyList())))))
        assertEquals("", codec.feed(event("response.output_item.added", "output_index" to num(0), "item" to obj("type" to str("reasoning"), "id" to str("r"), "summary" to JsonArray(emptyList())))))
        val thought = codec.feed(event("response.reasoning_summary_text.delta", "output_index" to num(0), "item_id" to str("r"), "summary_index" to num(0), "delta" to str("think")))
        assertEquals("think", delta(thought).getValue("reasoning_content").jsonPrimitive.content)
        assertEquals("", codec.feed(event("response.output_item.done", "output_index" to num(0), "item" to reasoning("think"))))
        assertEquals("", codec.feed(event("response.output_item.added", "output_index" to num(1), "item" to obj("type" to str("message"), "id" to str("m"), "role" to str("assistant"), "status" to str("in_progress"), "content" to JsonArray(emptyList())))))
        val text = codec.feed(event("response.output_text.delta", "output_index" to num(1), "item_id" to str("m"), "content_index" to num(0), "delta" to str("hello")))
        assertEquals("hello", delta(text).getValue("content").jsonPrimitive.content)
        assertEquals("", codec.feed(event("response.output_item.done", "output_index" to num(1), "item" to message("hello"))))
        assertEquals("", codec.feed(event("response.output_item.added", "output_index" to num(2), "item" to obj("type" to str("function_call"), "id" to str("fc"), "call_id" to str("c"), "name" to str("read"), "arguments" to str(""), "status" to str("in_progress")))))
        assertEquals("", codec.feed(event("response.function_call_arguments.delta", "output_index" to num(2), "item_id" to str("fc"), "delta" to str("{\"path\":"))))
        assertEquals("", codec.feed(event("response.function_call_arguments.delta", "output_index" to num(2), "item_id" to str("fc"), "delta" to str("\"file\"}"))))
        assertEquals("", codec.feed(event("response.function_call_arguments.done", "output_index" to num(2), "item_id" to str("fc"), "arguments" to str("{\"path\":\"file\"}"))))
        val finalOutput = JsonArray(listOf(reasoning("think"), message("hello"), function("fc", "c", "read", "{\"path\":\"file\"}")))
        val usage = json("""{"input_tokens":10,"output_tokens":5,"total_tokens":15,"input_tokens_details":{"cached_tokens":3},"output_tokens_details":{"reasoning_tokens":2}}""")
        val terminal = codec.feed(event("response.completed", "response" to response(finalOutput, usage = usage)))
        val chunks = data(terminal)
        assertEquals(2, chunks.size)
        val tool = chunks[0].getValue("choices").jsonArray.single().jsonObject.getValue("delta").jsonObject.getValue("tool_calls").jsonArray.single().jsonObject
        assertEquals(0, tool.getValue("index").jsonPrimitive.int)
        assertEquals("{\"path\":\"file\"}", tool.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content)
        val last = chunks.last()
        assertEquals("tool_calls", last.getValue("choices").jsonArray.single().jsonObject.getValue("finish_reason").jsonPrimitive.content)
        assertEquals(3, last.getValue("usage").jsonObject.getValue("prompt_tokens_details").jsonObject.getValue("cached_tokens").jsonPrimitive.int)
        assertTrue(terminal.endsWith("data: [DONE]\n\n"))
        assertTrue(codec.isFinished)
        assertFails { codec.feed(event("response.completed", "response" to response(finalOutput))) }
        assertEquals("", codec.endOfStream())
    }

    @Test fun completedSnapshotCanSupplyUnstreamedTextAndParallelCalls() {
        val codec = stream()
        assertEquals("", codec.feed(event("response.output_item.added", "output_index" to num(0), "item" to obj("type" to str("function_call"), "id" to str("f1"), "call_id" to str("c1"), "name" to str("read"), "arguments" to str("")))))
        assertEquals("", codec.feed(event("response.function_call_arguments.delta", "output_index" to num(0), "item_id" to str("f1"), "delta" to str("{}"))))
        val output = JsonArray(listOf(function("f1", "c1", "read", "{}"), function("f2", "c2", "write", "{}")))
        val chunks = data(codec.feed(event("response.completed", "response" to response(output))))
        val tools = chunks.first().getValue("choices").jsonArray.single().jsonObject.getValue("delta").jsonObject.getValue("tool_calls").jsonArray
        assertEquals(listOf("c1", "c2"), tools.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        val textOnly = stream()
        val final = textOnly.feed(event("response.completed", "response" to response(JsonArray(listOf(message("only final"))))))
        assertEquals("only final", data(final).first().getValue("choices").jsonArray.single().jsonObject.getValue("delta").jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test fun incompleteMapsToLengthAndRetainsUsage() {
        val codec = stream()
        val incompleteItem = JsonObject(message("partial") + ("status" to str("incomplete")))
        codec.feed(event("response.output_text.delta", "output_index" to num(0), "item_id" to str("m"), "delta" to str("partial")))
        codec.feed(event("response.output_item.done", "output_index" to num(0), "item" to incompleteItem))
        val result = codec.feed(event("response.incomplete", "response" to response(JsonArray(listOf(incompleteItem)), "incomplete",
            json("""{"input_tokens":4,"output_tokens":2,"total_tokens":6}"""))))
        val last = data(result).last()
        assertEquals("length", last.getValue("choices").jsonArray.single().jsonObject.getValue("finish_reason").jsonPrimitive.content)
        assertEquals(6, last.getValue("usage").jsonObject.getValue("total_tokens").jsonPrimitive.int)
    }

    @Test fun eofAndBareDoneNeverProduceSuccessfulTerminal() {
        val eof = stream(); eof.feed(event("response.output_text.delta", "delta" to str("partial")))
        val error = eof.endOfStream()
        assertTrue(data(error).single().containsKey("error"))
        assertFalse(error.contains("[DONE]"))
        assertEquals("", eof.endOfStream())
        val bare = stream(); assertFails { bare.feed("[DONE]") }
        val failed = bare.fail("invalid upstream")
        assertTrue(data(failed).single().containsKey("error"))
        assertFalse(failed.contains("[DONE]"))
        assertFails { bare.feed(event("response.completed", "response" to response(JsonArray(emptyList())))) }
    }

    @Test fun rejectsSnapshotsThatConflictOrLoseInformation() {
        fun expectFailure(events: List<String>) {
            val codec = stream()
            assertFails { for (item in events) codec.feed(item) }
            assertTrue(data(codec.fail("invalid upstream")).single().containsKey("error"))
        }
        expectFailure(listOf(event("response.output_text.delta", "delta" to str("one")), event("response.completed", "response" to response(JsonArray(listOf(message("other")))))))
        expectFailure(listOf(event("response.output_text.delta", "delta" to str("one")), event("response.completed", "response" to response(JsonArray(emptyList())))))
        expectFailure(listOf(event("response.output_text.delta", "delta" to str("text")), event("response.reasoning_summary_text.delta", "output_index" to num(1), "delta" to str("late"))))
        expectFailure(listOf(event("response.output_text.delta", "delta" to str("text")), event("response.created", "response" to obj("status" to str("in_progress")))))
        expectFailure(listOf(event("response.output_text.annotation.added", "annotation" to obj("type" to str("url_citation")))))
        expectFailure(listOf(event("response.completed", "response" to response(JsonArray(listOf(json("""{"type":"reasoning","summary":[],"encrypted_content":"opaque"}""")))))))
        expectFailure(listOf(event("response.created", "response" to obj("id" to str("one"), "status" to str("in_progress"))), event("response.completed", "response" to response(JsonArray(emptyList())))))
        expectFailure(listOf("""{"type":"response.output_text.delta","delta":"a","delta":"b"}"""))
        expectFailure(listOf(event("response.output_item.added", "output_index" to num(0), "item" to obj("id" to str("same"), "type" to str("reasoning"), "summary" to JsonArray(emptyList()))),
            event("response.output_item.added", "output_index" to num(1), "item" to obj("id" to str("same"), "type" to str("message"), "role" to str("assistant"), "content" to JsonArray(emptyList())))))
    }

    @Test fun rejectsInconsistentOrIncompleteToolFragments() {
        val codec = stream()
        codec.feed(event("response.output_item.added", "output_index" to num(0), "item" to obj("type" to str("function_call"), "id" to str("f"), "call_id" to str("c"), "name" to str("read"), "arguments" to str(""))))
        codec.feed(event("response.function_call_arguments.delta", "output_index" to num(0), "delta" to str("{")))
        assertFails { codec.feed(event("response.completed", "response" to response(JsonArray(listOf(function("f", "c", "read", "{}")))))) }
        assertTrue(data(codec.fail("incomplete call")).single().containsKey("error"))
    }

    @Test fun rejectsMalformedOrDuplicateFunctionArgumentsBeforeToolOutput() {
        for (arguments in listOf("", "not-json", "[]", "{\"x\":1,\"x\":2}")) {
            val codec = stream()
            val response = response(JsonArray(listOf(function("f", "c", "read", arguments))))
            assertFails { codec.feed(event("response.completed", "response" to response)) }
            val error = codec.fail("invalid arguments")
            assertTrue(data(error).single().containsKey("error"))
            assertFalse(error.contains("tool_calls"))
        }
    }

    @Test fun detectsDuplicateItemAndOutOfOrderEventSequences() {
        val codec = stream()
        val first = event("response.output_item.added", "sequence_number" to num(1), "item" to obj("type" to str("reasoning"), "summary" to JsonArray(emptyList())))
        codec.feed(first)
        assertFails { codec.feed(first) }
        assertTrue(data(codec.fail("duplicate")).single().containsKey("error"))
    }
}
