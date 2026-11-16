/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpChatResponsesStreamTest {
    private fun stream(custom: Set<String> = emptySet()) = AcpChatResponsesStream("messenger/model", custom, { "resp_test" }, { 1_700_000_000 })
    private fun events(wire: String): List<JsonObject> = wire.lineSequence().filter { it.startsWith("data: ") }
        .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }.toList()
    private fun JsonObject.type() = getValue("type").jsonPrimitive.content
    private fun choice(delta: JsonObject, finish: String? = null) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("delta", delta); if (finish == null) put("finish_reason", JsonNull) else put("finish_reason", finish)
        }) })
    }.toString()
    private fun call(index: Int, id: String? = null, name: String? = null, arguments: String? = null): JsonObject = buildJsonObject {
        put("index", index); if (id != null) put("id", id)
        if (name != null || arguments != null) put("function", buildJsonObject {
            if (name != null) put("name", name); if (arguments != null) put("arguments", arguments)
        })
    }
    private fun calls(vararg entries: JsonObject) = buildJsonObject { put("tool_calls", JsonArray(entries.toList())) }

    @Test fun rustFragmentedToolFixtureIncludesUsageAfterFinish() {
        val codec = stream()
        val chunks = mutableListOf<String>()
        chunks += codec.start()
        chunks += codec.feed(choice(buildJsonObject { put("content", "Hello ") }))
        chunks += codec.feed(choice(calls(call(0, "call-1", "read", "{\"path\":"))))
        chunks += codec.feed(choice(calls(call(0, arguments = "\"file\"}")), "tool_calls"))
        chunks += codec.feed("""{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}""")
        assertFalse(codec.isFinished)
        chunks += codec.feed("[DONE]")
        val all = events(chunks.joinToString(""))
        assertEquals((0L until all.size.toLong()).toList(), all.map { it.getValue("sequence_number").jsonPrimitive.long })
        assertEquals(1, all.count { it.type() == "response.completed" })
        assertTrue(all.any { it.type() == "response.output_text.delta" && it.getValue("delta").jsonPrimitive.content == "Hello " })
        val result = all.last().getValue("response").jsonObject
        assertEquals("messenger/model", result.getValue("model").jsonPrimitive.content)
        assertEquals(17, result.getValue("usage").jsonObject.getValue("total_tokens").jsonPrimitive.int)
        val call = result.getValue("output").jsonArray[1].jsonObject
        assertEquals("call-1", call.getValue("call_id").jsonPrimitive.content)
        assertEquals("{\"path\":\"file\"}", call.getValue("arguments").jsonPrimitive.content)
        assertEquals("", codec.feed("[DONE]"))
        assertEquals("", codec.endOfStream())
        assertTrue(codec.isFinished)
    }

    @Test fun interleavedReasoningAndTextKeepOrderAndEventShapes() {
        val codec = stream()
        val wire = codec.start() + codec.feed(choice(buildJsonObject { put("reasoning_content", "think") })) +
            codec.feed(choice(buildJsonObject { put("content", "answer") })) +
            codec.feed(choice(buildJsonObject { put("reasoning", "after") })) +
            codec.feed(choice(buildJsonObject {}, "stop")) + codec.feed("[DONE]")
        val all = events(wire)
        assertEquals(listOf("response.created", "response.output_item.added", "response.reasoning_summary_part.added",
            "response.reasoning_summary_text.delta", "response.reasoning_summary_text.done", "response.reasoning_summary_part.done", "response.output_item.done",
            "response.output_item.added", "response.content_part.added", "response.output_text.delta", "response.output_text.done",
            "response.content_part.done", "response.output_item.done", "response.output_item.added", "response.reasoning_summary_part.added",
            "response.reasoning_summary_text.delta", "response.reasoning_summary_text.done", "response.reasoning_summary_part.done",
            "response.output_item.done", "response.completed"), all.map { it.type() })
        val output = all.last().getValue("response").jsonObject.getValue("output").jsonArray
        assertEquals(listOf("reasoning", "message", "reasoning"), output.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals("msg_resp_test_1", output[1].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("after", output[2].jsonObject.getValue("summary").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test fun customAndFunctionCallFragmentsSortByIndex() {
        val codec = stream(setOf("patch"))
        var wire = codec.start()
        wire += codec.feed(choice(calls(call(5, "second", "pa", "{\"in"), call(1, "first", "read", "{"))))
        wire += codec.feed(choice(calls(call(1, arguments = "}"), call(5, name = "tch", arguments = "put\":\"change\"}")), "tool_calls"))
        wire += codec.feed("[DONE]")
        val all = events(wire)
        val output = all.last().getValue("response").jsonObject.getValue("output").jsonArray
        assertEquals(listOf("function_call", "custom_tool_call"), output.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals("first", output[0].jsonObject.getValue("call_id").jsonPrimitive.content)
        assertEquals("change", output[1].jsonObject.getValue("input").jsonPrimitive.content)
        assertTrue(all.any { it.type() == "response.custom_tool_call_input.delta" && it.getValue("delta").jsonPrimitive.content == "change" })
    }

    @Test fun eofAndMissingFinishCannotBecomeSuccess() {
        val truncated = stream(); truncated.start()
        truncated.feed(choice(buildJsonObject { put("content", "partial") }, "stop"))
        val failed = events(truncated.endOfStream()).single()
        assertEquals("response.failed", failed.type())
        assertEquals("", truncated.feed("[DONE]"))
        assertEquals("", truncated.fail("another failure"))
        val missing = stream(); missing.start()
        assertFails { missing.feed("[DONE]") }
        assertFails { missing.feed(choice(buildJsonObject {}, "stop")) }
        assertEquals("response.failed", events(missing.fail("invalid upstream" )).single().type())
        assertEquals("", missing.feed("[DONE]"))
    }

    @Test fun incompleteAndNullUsageDetailsArePreserved() {
        val codec = stream(); var wire = codec.start()
        wire += codec.feed(choice(buildJsonObject { put("content", "partial") }, "length"))
        wire += codec.feed("""{"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":null}}}""")
        wire += codec.feed("[DONE]")
        val final = events(wire).last()
        assertEquals("response.incomplete", final.type())
        val result = final.getValue("response").jsonObject
        assertEquals("max_output_tokens", result.getValue("incomplete_details").jsonObject.getValue("reason").jsonPrimitive.content)
        assertEquals(12, result.getValue("usage").jsonObject.getValue("total_tokens").jsonPrimitive.int)
        assertTrue(result.getValue("usage").jsonObject.getValue("input_tokens_details").jsonObject.getValue("cached_tokens") is JsonNull)
    }

    @Test fun rejectsUnsupportedMalformedAndPostFinishContent() {
        val cases = listOf(
            """{"choices":[],"unknown":1}""",
            """{"choices":[],"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            """{"choices":[{"delta":{"content":"a","cont\u0065nt":"b"}}]}""",
            """{"choices":[{"delta":{"content":"hi","unexpected":true}}]}""",
            """{"choices":[{"delta":{"content":"hi","annotations":true}}]}""",
            """{"choices":[{"delta":{"content":"hi","refusal":"no"}}]}""",
            """{"choices":[{"delta":{"content":"hi","reasoning":"a","reasoning_content":"b"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"type":"other"}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"content_filter"}]}""",
            """{"choices":[{"delta":{}},{"delta":{}}]}""",
            """{"choices":[],"usage":{"prompt_tokens":-1}}""",
            """not json"""
        )
        for (input in cases) {
            val codec = stream(); codec.start()
            assertFails("Should reject $input") { codec.feed(input) }
            assertEquals("response.failed", events(codec.fail("invalid upstream")).single().type())
            assertEquals("", codec.feed("[DONE]"))
        }
        val late = stream(); late.start(); late.feed(choice(buildJsonObject {}, "stop"))
        assertFails { late.feed(choice(buildJsonObject { put("content", "late") })) }
        assertEquals("response.failed", events(late.fail("late content")).single().type())
    }

    @Test fun boundsToolCountAndJsonDepth() {
        val codec = stream(); codec.start()
        val tooMany = buildJsonObject { put("tool_calls", JsonArray((0..128).map { call(it, "id$it", "f", "{}") })) }
        assertFails { codec.feed(choice(tooMany)) }
        assertEquals("response.failed", events(codec.fail("too many calls")).single().type())
        val deep = stream(); deep.start()
        val nested = "[".repeat(70) + "0" + "]".repeat(70)
        assertFails { deep.feed("{\"choices\":[],\"nested\":" + nested + "}") }
        assertEquals("response.failed", events(deep.fail("too deep")).single().type())
    }

    @Test fun rejectsIncompleteAndAmbiguousCustomCalls() {
        val missing = stream(); missing.start()
        missing.feed(choice(calls(call(0, name = "read", arguments = "{}")), "tool_calls"))
        assertFails { missing.feed("[DONE]") }
        assertEquals("response.failed", events(missing.fail("incomplete call")).single().type())
        val custom = stream(setOf("patch")); custom.start()
        custom.feed(choice(calls(call(0, "id", "patch", "{\"input\":\"a\",\"input\":\"b\"}")), "tool_calls"))
        assertFails { custom.feed("[DONE]") }
        assertEquals("response.failed", events(custom.fail("invalid custom call")).single().type())
    }
}
