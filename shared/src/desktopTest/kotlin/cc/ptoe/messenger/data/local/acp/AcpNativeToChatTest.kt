/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpNativeToChatTest {
    private val codec = AcpNativeToChat { "chatcmpl_test" }
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun convert(source: String, raw: String) = codec.convert(source, json(raw), "messenger/m")
    private fun message(value: JsonObject) = value.getValue("choices").jsonArray.single().jsonObject.getValue("message").jsonObject
    private fun finish(value: JsonObject) = value.getValue("choices").jsonArray.single().jsonObject.getValue("finish_reason").jsonPrimitive.content

    @Test fun responsesPreservesReasoningTextCallsAndUsage() {
        val result = convert("responses", """{"id":"resp_1","object":"response","model":"upstream","status":"completed","output":[
          {"type":"reasoning","id":"rs","summary":[{"type":"summary_text","text":"think"}]},
          {"type":"message","id":"m","role":"assistant","status":"completed","content":[{"type":"output_text","text":"answer","annotations":[]}]},
          {"type":"function_call","id":"f","call_id":"c","name":"read","arguments":"{\"path\":\"file\"}","status":"completed"}],
          "error":null,"incomplete_details":null,"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15,
          "input_tokens_details":{"cached_tokens":3},"output_tokens_details":{"reasoning_tokens":2}}}""")
        assertEquals("chatcmpl_test", result.getValue("id").jsonPrimitive.content)
        assertEquals("messenger/m", result.getValue("model").jsonPrimitive.content)
        assertEquals("think", message(result).getValue("reasoning_content").jsonPrimitive.content)
        assertEquals("answer", message(result).getValue("content").jsonPrimitive.content)
        assertEquals("{\"path\":\"file\"}", message(result).getValue("tool_calls").jsonArray.single().jsonObject.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content)
        assertEquals("tool_calls", finish(result))
        val usage = result.getValue("usage").jsonObject
        assertEquals(3, usage.getValue("prompt_tokens_details").jsonObject.getValue("cached_tokens").jsonPrimitive.int)
        assertEquals(2, usage.getValue("completion_tokens_details").jsonObject.getValue("reasoning_tokens").jsonPrimitive.int)
    }

    @Test fun messagesAccountsForCacheReadAndPreservesToolCalls() {
        val result = convert("messages", """{"id":"msg_1","type":"message","role":"assistant","model":"claude",
          "content":[{"type":"text","text":"looking"},{"type":"tool_use","id":"c","name":"read","input":{"path":"file"}}],
          "stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":7,
          "cache_read_input_tokens":3,"cache_creation_input_tokens":0,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":0}}}""")
        assertEquals("looking", message(result).getValue("content").jsonPrimitive.content)
        val call = message(result).getValue("tool_calls").jsonArray.single().jsonObject
        assertEquals("c", call.getValue("id").jsonPrimitive.content)
        assertEquals(json("""{"path":"file"}"""), Json.parseToJsonElement(call.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content))
        val usage = result.getValue("usage").jsonObject
        assertEquals(8, usage.getValue("prompt_tokens").jsonPrimitive.int)
        assertEquals(15, usage.getValue("total_tokens").jsonPrimitive.int)
        assertEquals(3, usage.getValue("prompt_tokens_details").jsonObject.getValue("cached_tokens").jsonPrimitive.int)
    }

    @Test fun interactionsMatchesEquivalentStepsAndOutputsIncludingArgumentOrder() {
        val result = convert("interactions", """{"id":"i","object":"interaction","status":"completed",
          "steps":[{"type":"thought","summary":[{"type":"text","text":"why"}]},
            {"type":"model_output","content":[{"type":"text","text":"answer"}]},
            {"type":"function_call","id":"c","name":"read","arguments":{"a":1,"b":2}}],
          "outputs":[{"type":"thought","summary":[{"type":"text","text":"why"}]},
            {"type":"text","text":"answer"},{"type":"function_call","id":"c","name":"read","arguments":{"b":2,"a":1}}],
          "usage":{"total_input_tokens":10,"total_output_tokens":4,"total_thought_tokens":2,"total_cached_tokens":3,"total_tool_use_tokens":0,"total_tokens":16}}""")
        assertEquals("why", message(result).getValue("reasoning_content").jsonPrimitive.content)
        assertEquals("answer", message(result).getValue("content").jsonPrimitive.content)
        assertEquals("tool_calls", finish(result))
        val usage = result.getValue("usage").jsonObject
        assertEquals(6, usage.getValue("completion_tokens").jsonPrimitive.int)
        assertEquals(2, usage.getValue("completion_tokens_details").jsonObject.getValue("reasoning_tokens").jsonPrimitive.int)
        assertEquals(3, usage.getValue("prompt_tokens_details").jsonObject.getValue("cached_tokens").jsonPrimitive.int)
    }

    @Test fun flatInteractionsOutputsAndIncompleteResponsesAreSupported() {
        val result = convert("interactions", """{"status":"completed","outputs":[{"type":"text","text":"hello"}]}""")
        assertEquals("hello", message(result).getValue("content").jsonPrimitive.content)
        assertEquals("stop", finish(result))
        assertFalse("usage" in result)
        val partial = convert("responses", """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[
          {"type":"message","role":"assistant","status":"incomplete","content":[{"type":"output_text","text":"part","annotations":[]}]}]}""")
        assertEquals("length", finish(partial))
    }

    @Test fun interactionsActionRequiresACompleteToolCall() {
        val result = convert("interactions", """{"status":"requires_action","outputs":[{"type":"function_call","id":"c","name":"read","arguments":{}}]}""")
        assertEquals("tool_calls", finish(result))
        assertFails { convert("interactions", """{"status":"requires_action","outputs":[]}""") }
    }

    @Test fun conflictingOutputsAndUnrepresentableOrderAreRejected() {
        val cases = listOf(
            "interactions" to """{"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"one"}]}],"outputs":[{"type":"text","text":"two"}]}""",
            "responses" to """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"first"}]},{"type":"reasoning","summary":[{"type":"summary_text","text":"later"}]}]}""",
            "responses" to """{"status":"completed","output":[{"type":"function_call","call_id":"c","name":"f","arguments":"{}"},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"later"}]}]}""",
            "responses" to """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"one"}]},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"two"}]}]}""",
            "messages" to """{"role":"assistant","stop_reason":"end_turn","content":[{"type":"text","text":"a"},{"type":"thinking","thinking":"later"}]}""",
            "messages" to """{"role":"assistant","stop_reason":"tool_use","content":[{"type":"tool_use","id":"c","name":"f","input":{}},{"type":"text","text":"later"}]}""",
            "interactions" to """{"status":"completed","steps":[{"type":"function_call","id":"c","name":"f","arguments":{}},{"type":"model_output","content":[{"type":"text","text":"later"}]}]}"""
        )
        for ((source, body) in cases) assertFails("Should reject $body") { convert(source, body) }
    }

    @Test fun signaturesAnnotationsUnsupportedStatusAndUnknownFieldsAreRejected() {
        val cases = listOf(
            "responses" to """{"status":"completed","output":[{"type":"reasoning","summary":[],"encrypted_content":"secret"}]}""",
            "responses" to """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"x","annotations":[{"type":"url_citation"}]}]}]}""",
            "responses" to """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"x","logprobs":[{}]}]}]}""",
            "responses" to """{"status":"completed","output":[],"unknown":true}""",
            "responses" to """{"status":"completed","output":[],"model":{"bad":1}}""",
            "responses" to """{"status":"completed","output":[{"type":"message","id":42,"role":"assistant","content":[]}]}""",
            "responses" to """{"status":"incomplete","incomplete_details":{"reason":"content_filter"},"output":[]}""",
            "responses" to """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"function_call","call_id":"a","name":"f","arguments":"{}"}]}""",
            "messages" to """{"role":"assistant","stop_reason":"end_turn","content":[{"type":"thinking","thinking":"why","signature":"opaque"}]}""",
            "messages" to """{"role":"assistant","stop_reason":"end_turn","content":[{"type":"text","text":"x","citations":[{}]}]}""",
            "messages" to """{"role":"assistant","stop_reason":"stop_sequence","stop_sequence":"END","content":[]}""",
            "messages" to """{"role":"assistant","stop_reason":"tool_use","content":[]}""",
            "interactions" to """{"status":"completed","steps":[{"type":"thought","signature":"opaque","summary":[]}]}""",
            "interactions" to """{"status":"completed","steps":[{"type":"function_call","id":"c","name":"f","arguments":{},"signature":"opaque"}]}""",
            "interactions" to """{"status":"incomplete","steps":[]}"""
        )
        for ((source, body) in cases) assertFails("Should reject $body") { convert(source, body) }
    }

    @Test fun accountingCannotBeDroppedOrInvented() {
        val cases = listOf(
            "responses" to """{"status":"completed","output":[],"usage":{"input_tokens":2,"output_tokens":3,"total_tokens":99}}""",
            "responses" to """{"status":"completed","output":[],"usage":{"input_tokens":2,"output_tokens":3,"output_tokens_details":{"audio_tokens":1}}}""",
            "responses" to """{"status":"completed","output":[],"usage":{"input_tokens":2,"output_tokens":3,"input_tokens_details":{"cached_tokens":3}}}""",
            "messages" to """{"role":"assistant","stop_reason":"end_turn","content":[],"usage":{"input_tokens":2,"output_tokens":1,"cache_creation_input_tokens":1}}""",
            "messages" to """{"role":"assistant","stop_reason":"end_turn","content":[],"usage":{"input_tokens":2,"output_tokens":1,"cache_creation":{"ephemeral_5m_input_tokens":1}}}""",
            "messages" to """{"role":"assistant","stop_reason":"end_turn","content":[],"usage":{"input_tokens":9223372036854775807,"output_tokens":1,"cache_read_input_tokens":1}}""",
            "interactions" to """{"status":"completed","steps":[{"type":"thought","summary":[]}],"usage":{"total_input_tokens":1,"total_output_tokens":1,"total_tokens":2}}""",
            "interactions" to """{"status":"completed","steps":[],"usage":{"total_input_tokens":1,"total_output_tokens":1,"total_thought_tokens":1,"total_tokens":2}}""",
            "interactions" to """{"status":"completed","steps":[],"usage":{"total_input_tokens":1,"total_output_tokens":1,"total_tool_use_tokens":1,"total_tokens":2}}""",
            "interactions" to """{"status":"completed","steps":[],"usage":{"total_input_tokens":1,"total_output_tokens":1,"total_tokens":2,"input_tokens_by_modality":[{"modality":"text","tokens":1}]}}"""
        )
        for ((source, body) in cases) assertFails("Should reject $body") { convert(source, body) }
    }

    @Test fun toolCallsAreBoundedAndUnique() {
        fun tool(id: String) = buildJsonObject { put("type", "function_call"); put("call_id", id); put("name", "f"); put("arguments", "{}") }
        fun payload(calls: List<JsonObject>) = buildJsonObject { put("status", "completed"); put("output", JsonArray(calls)) }
        assertFails { codec.convert("responses", payload(listOf(tool("x"), tool("x"))), "model") }
        assertFails { codec.convert("responses", payload((0..128).map { tool("id$it") }), "model") }
    }

    @Test fun nullDetailsAndEmptyFieldsRemainHarmless() {
        val result = convert("responses", """{"status":"completed","output":[],"unused":null,"usage":{"input_tokens":2,"output_tokens":3,
          "input_tokens_details":{"cached_tokens":null},"output_tokens_details":{"reasoning_tokens":null}}}""")
        val usage = result.getValue("usage").jsonObject
        assertTrue(usage.getValue("prompt_tokens_details").jsonObject.getValue("cached_tokens") is JsonNull)
        assertTrue(usage.getValue("completion_tokens_details").jsonObject.getValue("reasoning_tokens") is JsonNull)
        assertEquals(5, usage.getValue("total_tokens").jsonPrimitive.int)
    }
}
