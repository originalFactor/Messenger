/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpChatToNativeTest {
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun convert(target: String, raw: String) = AcpChatToNative.convert(target, json(raw), "upstream")

    @Test fun responsesPreservesOrderedMultimodalInputAndFunctions() {
        val result = convert("responses", """{"model":"messenger/m","stream":true,"temperature":0.3,"top_p":0.9,"max_tokens":42,"reasoning_effort":"high",
          "messages":[{"role":"system","content":"instructions"}, {"role":"user","content":[
            {"type":"text","text":"before"},{"type":"image_url","image_url":{"url":"https://example.test/a.png","detail":"high"}},
            {"type":"text","text":"after"}]},
            {"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"read","arguments":"{\"path\":\"a\"}"}}]},
            {"role":"tool","tool_call_id":"c1","content":"done"}],
          "tools":[{"type":"function","function":{"name":"read","description":"Read","parameters":{"type":"object"},"strict":true}}]}""")
        assertEquals("upstream", result.getValue("model").jsonPrimitive.content)
        assertEquals("instructions", result.getValue("instructions").jsonPrimitive.content)
        val input = result.getValue("input").jsonArray
        assertEquals(listOf("input_text", "input_image", "input_text"), input[0].jsonObject.getValue("content").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals("high", input[0].jsonObject.getValue("content").jsonArray[1].jsonObject.getValue("detail").jsonPrimitive.content)
        assertEquals("{\"path\":\"a\"}", input[1].jsonObject.getValue("arguments").jsonPrimitive.content)
        assertEquals("done", input[2].jsonObject.getValue("output").jsonPrimitive.content)
        assertEquals(42, result.getValue("max_output_tokens").jsonPrimitive.int)
        assertEquals("high", result.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertTrue(result.getValue("tools").jsonArray.single().jsonObject.getValue("strict").jsonPrimitive.boolean)
    }

    @Test fun messagesPreservesImageOrderAndGroupsToolResults() {
        val result = convert("messages", """{"max_tokens":100,"temperature":0.2,"top_p":0.8,"stream":false,"messages":[
          {"role":"system","content":"be useful"}, {"role":"user","content":[{"type":"text","text":"before"},
            {"type":"image_url","image_url":{"url":"data:image/png;base64,AA=="}},{"type":"text","text":"after"}]},
          {"role":"assistant","content":null,"tool_calls":[{"id":"a","type":"function","function":{"name":"read","arguments":"{}"}},
            {"id":"b","type":"function","function":{"name":"write","arguments":"{\"x\":1}"}}]},
          {"role":"tool","tool_call_id":"a","content":"A"},{"role":"tool","tool_call_id":"b","content":"B"},
          {"role":"user","content":"next"}],"tools":[{"type":"function","function":{"name":"read","parameters":{"type":"object"}}}]}""")
        val messages = result.getValue("messages").jsonArray
        val content = messages[0].jsonObject.getValue("content").jsonArray
        assertEquals(listOf("text", "image", "text"), content.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals("image/png", content[1].jsonObject.getValue("source").jsonObject.getValue("media_type").jsonPrimitive.content)
        assertEquals(listOf("tool_use", "tool_use"), messages[1].jsonObject.getValue("content").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals(listOf("a", "b"), messages[2].jsonObject.getValue("content").jsonArray.map { it.jsonObject.getValue("tool_use_id").jsonPrimitive.content })
        assertEquals("next", messages[3].jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(100, result.getValue("max_tokens").jsonPrimitive.int)
        assertEquals("read", result.getValue("tools").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test fun interactionsPreservesFunctionsAndSupportedGenerationSettings() {
        val result = convert("interactions", """{"stream":true,"max_tokens":100,"reasoning_effort":"medium","messages":[
          {"role":"system","content":"system"},{"role":"user","content":"question"},
          {"role":"assistant","content":null,"tool_calls":[{"id":"a","type":"function","function":{"name":"read","arguments":"{\"path\":\"file\"}"}}]},
          {"role":"tool","tool_call_id":"a","content":"result"}],"tools":[{"type":"function","function":{"name":"read","parameters":{}}}]}""")
        val input = result.getValue("input").jsonArray
        assertEquals(listOf("user_input", "function_call", "function_result"), input.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals("file", input[1].jsonObject.getValue("arguments").jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals("result", input[2].jsonObject.getValue("result").jsonPrimitive.content)
        assertEquals("system", result.getValue("system_instruction").jsonPrimitive.content)
        assertEquals(100, result.getValue("generation_config").jsonObject.getValue("max_output_tokens").jsonPrimitive.int)
        assertEquals("medium", result.getValue("generation_config").jsonObject.getValue("thinking_level").jsonPrimitive.content)
        assertFalse(result.getValue("store").jsonPrimitive.boolean)
    }

    @Test fun rejectsFieldsAndParametersThatCannotBePreserved() {
        val cases = listOf(
            """{"messages":[],"tool_choice":"auto"}""",
            """{"messages":[],"stream_options":{"include_usage":true}}""",
            """{"messages":[],"reasoning_effort":"none"}""",
            """{"messages":[],"max_tokens":0}""",
            """{"messages":[],"temperature":true}""",
            """{"messages":[],"stream":"true"}""",
            """{"messages":[{"role":"system","content":"a"},{"role":"system","content":"b"}]}""",
            """{"messages":[{"role":"user","content":"a"},{"role":"system","content":"later"}]}""",
            """{"messages":[{"role":"user","content":"a","name":"user"}]}""",
            """{"messages":[{"role":"assistant","content":"a","reasoning_content":"secret"}]}""",
            """{"messages":[{"role":"assistant","content":"text","tool_calls":[{"id":"a","type":"function","function":{"name":"f","arguments":"{}"}}]}]}""",
            """{"messages":[{"role":"assistant","content":null}]}""",
            """{"messages":[{"role":"user","content":[]}]}""",
            """{"messages":[{"role":"assistant","content":[{"type":"image_url","image_url":{"url":"https://example.test/a.png"}}]}]}""",
            """{"messages":[{"role":"user","content":[{"type":"text","text":"a","cache_control":{"type":"ephemeral"}}]}]}""",
            """{"messages":[{"role":"tool","tool_call_id":"a","content":[{"type":"text","text":"result"}]}]}""",
            """{"messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"a","type":"function","function":{"name":"f","arguments":"{}"}},{"id":"a","type":"function","function":{"name":"g","arguments":"{}"}}]}]}""",
            """{"messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"a","type":"function","function":{"name":"f","arguments":"invalid"}}]}]}""",
            """{"messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"a","type":"function","function":{"name":"f","arguments":"[]"}}]}]}""",
            """{"messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"a","type":"function","function":{"name":"f","arguments":"{\"x\":1,\"x\":2}"}}]}]}""",
            """{"messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"a","type":"function","function":{"name":"f","arguments":"{\"x\":1,\"\\u0078\":2}"}}]}]}""",
            """{"messages":[],"tools":[{"type":"function","function":{"name":"f","parameters":{},"unknown":true}}]}"""
        )
        for (case in cases) assertFails("Should reject $case") { convert("responses", case) }
    }

    @Test fun refusesTargetSpecificLossAndMissingMaxTokens() {
        assertFails { convert("messages", """{"messages":[]}""") }
        assertFails { convert("messages", """{"messages":[],"max_tokens":10,"reasoning_effort":"high"}""") }
        assertFails { convert("interactions", """{"messages":[],"temperature":0.5}""") }
        assertFails { convert("interactions", """{"messages":[],"top_p":0.5}""") }
        assertFails { convert("interactions", """{"messages":[],"reasoning_effort":"none"}""") }
        val detail = """{"max_tokens":10,"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://example.test/a.png","detail":"high"}}]}]}"""
        assertFails { convert("messages", detail) }
        assertFails { convert("interactions", detail) }
        val invalidData = """{"max_tokens":10,"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png,not-base64"}}]}]}"""
        assertFails { convert("messages", invalidData) }
        assertFails { convert("interactions", invalidData) }
        val strict = """{"max_tokens":10,"messages":[],"tools":[{"type":"function","function":{"name":"f","parameters":{},"strict":true}}]}"""
        assertFails { convert("messages", strict) }
        assertFails { convert("interactions", strict) }
    }

    @Test fun emptyValuesAndNullFieldsDoNotInventContent() {
        val result = convert("responses", """{"messages":[{"role":"system","content":""},{"role":"user","content":""}],"tools":[],"stream":null,"unknown":null}""")
        assertEquals("", result.getValue("instructions").jsonPrimitive.content)
        assertEquals("", result.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertTrue(result.getValue("tools").jsonArray.isEmpty())
        assertFalse(result.getValue("stream").jsonPrimitive.boolean)
    }
    @Test fun includeUsageIsAllowedOnlyForStreaming() {
        val value = convert("responses", """{"stream":true,"stream_options":{"include_usage":true},"messages":[{"role":"user","content":"hi"}]}""")
        assertTrue(value["stream"]!!.jsonPrimitive.boolean)
        assertFalse("stream_options" in value)
        assertFails { convert("responses", """{"stream":true,"stream_options":{"include_usage":false},"messages":[]}""") }
    }

}
