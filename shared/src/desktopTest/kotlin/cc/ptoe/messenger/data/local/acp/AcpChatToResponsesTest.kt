/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpChatToResponsesTest {
    private val codec = AcpChatToResponses({ "resp_test" }, { 1_700_000_000 })
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun convert(raw: String, custom: Set<String> = emptySet()) = codec.convert(json(raw), "messenger/model", custom)

    @Test fun rustReasoningAndCustomToolFixture() {
        val result = convert("""{"choices":[{"message":{"role":"assistant","content":null,
            "reasoning_content":"reason","tool_calls":[{"id":"call-2","type":"function",
            "function":{"name":"apply_patch","arguments":"{\"input\":\"patch text\"}"}}]},"finish_reason":"tool_calls"}]}""", setOf("apply_patch"))
        assertEquals(json("""{"id":"resp_test","object":"response","created_at":1700000000,"status":"completed",
          "model":"messenger/model","error":null,"incomplete_details":null,"output":[
            {"type":"reasoning","id":"rs_resp_test_0","summary":[{"type":"summary_text","text":"reason"}]},
            {"type":"custom_tool_call","id":"fc_resp_test_1","call_id":"call-2","name":"apply_patch","input":"patch text"}]}
        """), result)
        assertFalse("usage" in result)
    }

    @Test fun preservesReasoningTextFunctionsAndIncompleteUsage() {
        val result = convert("""{"id":"upstream","object":"chat.completion","created":100,"model":"upstream-model",
          "choices":[{"index":0,"finish_reason":"length","message":{"role":"assistant","reasoning_content":"thought","content":"answer",
            "tool_calls":[{"id":"call-a","type":"function","function":{"name":"read","arguments":"{\"path\":\"a\"}"}},
              {"id":"call-b","type":"function","function":{"name":"write","arguments":"{}"}}]}}],
          "usage":{"prompt_tokens":5,"completion_tokens":7,"total_tokens":12,"prompt_tokens_details":{"cached_tokens":3},
            "completion_tokens_details":{"reasoning_tokens":2}}}""")
        val output = result.getValue("output").jsonArray
        assertEquals(listOf("reasoning", "message", "function_call", "function_call"), output.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals("answer", output[1].jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("{\"path\":\"a\"}", output[2].jsonObject.getValue("arguments").jsonPrimitive.content)
        assertEquals("call-b", output[3].jsonObject.getValue("call_id").jsonPrimitive.content)
        assertEquals("incomplete", result.getValue("status").jsonPrimitive.content)
        assertEquals("max_output_tokens", result.getValue("incomplete_details").jsonObject.getValue("reason").jsonPrimitive.content)
        val usage = result.getValue("usage").jsonObject
        assertEquals(12, usage.getValue("total_tokens").jsonPrimitive.int)
        assertEquals(3, usage.getValue("input_tokens_details").jsonObject.getValue("cached_tokens").jsonPrimitive.int)
        assertEquals(2, usage.getValue("output_tokens_details").jsonObject.getValue("reasoning_tokens").jsonPrimitive.int)
    }

    @Test fun nullReasoningFallsBackAndMissingUsageFieldsDefaultToZero() {
        val result = convert("""{"choices":[{"message":{"reasoning_content":null,"reasoning":"summary","content":""},"finish_reason":"stop"}],"usage":{}}""")
        assertEquals("summary", result.getValue("output").jsonArray.single().jsonObject.getValue("summary").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(0, result.getValue("usage").jsonObject.getValue("total_tokens").jsonPrimitive.int)
    }

    @Test fun retainsExplicitNullUsageDetailsFromRust() {
        val result = convert("""{"choices":[{"message":{"content":"hi"},"finish_reason":"stop"}],"usage":{
            "prompt_tokens_details":{"cached_tokens":null},"completion_tokens_details":{"reasoning_tokens":null}}}""")
        val usage = result.getValue("usage").jsonObject
        assertTrue(usage.getValue("input_tokens_details").jsonObject.getValue("cached_tokens") is JsonNull)
        assertTrue(usage.getValue("output_tokens_details").jsonObject.getValue("reasoning_tokens") is JsonNull)
    }

    @Test fun rejectsUnavailableOrUnrepresentableUpstreamData() {
        val cases = listOf(
            """{"error":{"message":"failed"},"choices":[]}""",
            """{"choices":[]}""",
            """{"choices":[{},{}]}""",
            """{"choices":[{"message":{"role":"user","content":"hi"},"finish_reason":"stop"}]}""",
            """{"choices":[{"message":{"role":"assistant","refusal":"no"},"finish_reason":"stop"}]}""",
            """{"choices":[{"message":{"content":"hi","annotations":[{}]},"finish_reason":"stop"}]}""",
            """{"choices":[{"message":{"content":[{"type":"text","text":"hi"}]},"finish_reason":"stop"}]}""",
            """{"choices":[{"message":{"content":"hi","unknown":true},"finish_reason":"stop"}]}""",
            """{"choices":[{"message":{"content":"hi","reasoning":"a","reasoning_content":"b"},"finish_reason":"stop"}]}""",
            """{"choices":[{"message":{"content":"hi"},"finish_reason":"content_filter"}]}""",
            """{"choices":[{"message":{"content":"hi"}}]}""",
            """{"choices":[{"message":{"content":"hi"},"finish_reason":"stop","logprobs":{"content":[]}}]}""",
            """{"choices":[{"message":{"content":"hi"},"finish_reason":"stop"}],"system_fingerprint":"not-representable"}""",
            """{"choices":[{"message":{"tool_calls":[{"id":"x","type":"other","function":{"name":"f","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
            """{"choices":[{"message":{"tool_calls":[{"id":"x","type":"function","function":{"name":"f"}}]},"finish_reason":"tool_calls"}]}""",
            """{"choices":[{"message":{"content":"hi"},"finish_reason":"stop"}],"usage":{"prompt_tokens":-1}}""",
            """{"choices":[{"message":{"content":"hi"},"finish_reason":"stop"}],"usage":{"prompt_tokens":2,"unexpected":1}}""",
            """{"choices":[{"message":{"content":"hi"},"finish_reason":"stop"}],"usage":{"prompt_tokens":9223372036854775807,"completion_tokens":1}}"""
        )
        for (case in cases) assertFails("Should reject $case") { convert(case) }
    }

    @Test fun customToolRequiresExactlyOneInputStringIncludingEscapes() {
        fun call(arguments: String): JsonObject = buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("finish_reason", "tool_calls")
                put("message", buildJsonObject { put("tool_calls", buildJsonArray { add(buildJsonObject {
                    put("id", "c"); put("type", "function")
                    put("function", buildJsonObject { put("name", "custom"); put("arguments", arguments) })
                }) }) })
            }) })
        }
        val accepted = codec.convert(call(""" { "in\u0070ut" : "line\n\"quoted\"" } """), "alias", setOf("custom"))
        assertEquals("line\n\"quoted\"", accepted.getValue("output").jsonArray.single().jsonObject.getValue("input").jsonPrimitive.content)
        for (args in listOf("{}", "{\"input\":5}", "{\"input\":\"a\",\"extra\":1}", "{\"input\":\"a\",\"input\":\"b\"}", "{\"input\":\"bad\\q\"}")) {
            assertFails("Should reject $args") { codec.convert(call(args), "alias", setOf("custom")) }
        }
    }

    @Test fun retainsPlainFunctionArgumentTextWithoutParsingIt() {
        val result = convert("""{"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[{"id":"a","type":"function",
            "function":{"name":"read","arguments":"not-json"}}]}}]}""")
        assertEquals("not-json", result.getValue("output").jsonArray.single().jsonObject.getValue("arguments").jsonPrimitive.content)
        assertTrue(result.getValue("incomplete_details") is JsonNull)
    }
}
