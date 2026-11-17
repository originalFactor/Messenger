/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpResponsesToChatTest {
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun convert(raw: String) = AcpResponsesToChat.convert(json(raw), "upstream-model")

    @Test fun rustCustomToolAndReasoningHistoryFixture() {
        val converted = convert("""{"model":"messenger/m", "input":[
            {"type":"message","role":"user","content":[{"type":"input_text","text":"edit"}]},
            {"type":"reasoning","id":"rs","summary":[{"type":"summary_text","text":"think"}]},
            {"type":"custom_tool_call","call_id":"call-1","name":"apply_patch","input":"patch text"},
            {"type":"custom_tool_call_output","call_id":"call-1","output":"ok"}
        ],"tools":[{"type":"custom","name":"apply_patch","format":{"type":"text"}}]}""")
        val messages = converted.body.getValue("messages").jsonArray
        assertEquals("think", messages[1].jsonObject.getValue("reasoning_content").jsonPrimitive.content)
        val function = messages[1].jsonObject.getValue("tool_calls").jsonArray.single().jsonObject.getValue("function").jsonObject
        assertEquals("{\"input\":\"patch text\"}", function.getValue("arguments").jsonPrimitive.content)
        assertEquals("call-1", messages[2].jsonObject.getValue("tool_call_id").jsonPrimitive.content)
        assertEquals(setOf("apply_patch"), converted.customTools)
        assertEquals("upstream-model", converted.body.getValue("model").jsonPrimitive.content)
        assertFalse(converted.stream)
        assertFalse(converted.toString().contains("patch text"))
    }

    @Test fun mapsMixedContentAndPreservesFunctionCallOrder() {
        val converted = convert("""{"instructions":"obey", "input":[
          {"role":"developer","content":"setup"},
          {"role":"user","content":[{"type":"input_text","text":"look"},{"type":"input_image","image_url":"data:image/png;base64,AA==","detail":"high"}]},
          {"type":"reasoning","summary":[{"type":"summary_text","text":"why"}]},
          {"role":"assistant","content":[{"type":"output_text","text":"answer"}]},
          {"type":"function_call","call_id":"a","name":"read","arguments":"{\"x\":1}"},
          {"type":"function_call","call_id":"b","name":"write","arguments":"{}"},
          {"type":"function_call_output","call_id":"a","output":"result"}
        ]}""")
        val expected = json("""{"model":"upstream-model","stream":false,"messages":[
          {"role":"system","content":"obey"},{"role":"system","content":"setup"},
          {"role":"user","content":[{"type":"text","text":"look"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AA==","detail":"high"}}]},
          {"role":"assistant","content":[{"type":"text","text":"answer"}],"reasoning_content":"why","tool_calls":[
            {"id":"a","type":"function","function":{"name":"read","arguments":"{\"x\":1}"}},
            {"id":"b","type":"function","function":{"name":"write","arguments":"{}"}}]},
          {"role":"tool","tool_call_id":"a","content":"result"}
        ]}""")
        assertEquals(expected, converted.body)
    }

    @Test fun mapsStructuredOutputOptionsAndToolDefinitions() {
        val converted = convert("""{"input":"hi","stream":true,"max_output_tokens":17,"temperature":0.2,"top_p":0.8,
          "parallel_tool_calls":false,"metadata":{"tenant":"x"},"service_tier":"fast","prompt_cache_key":"cache","safety_identifier":"safe",
          "reasoning":{"effort":"high","summary":null},"include":[],
          "text":{"verbosity":"low","format":{"type":"json_schema","name":"answer","strict":true,"schema":{"type":"object"}}},
          "tools":[{"type":"function","name":"read","description":"Read","strict":true,"parameters":{"type":"object"}},
                   {"type":"custom","name":"patch","description":"Patch","format":{"type":"text"}}],
          "tool_choice":{"type":"custom","name":"patch"}}""")
        val body = converted.body
        assertTrue(converted.stream)
        assertEquals(true, body.getValue("stream_options").jsonObject.getValue("include_usage").jsonPrimitive.boolean)
        assertEquals(17, body.getValue("max_tokens").jsonPrimitive.int)
        assertEquals("high", body.getValue("reasoning_effort").jsonPrimitive.content)
        assertEquals("low", body.getValue("verbosity").jsonPrimitive.content)
        assertEquals("answer", body.getValue("response_format").jsonObject.getValue("json_schema").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("patch", body.getValue("tool_choice").jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
        val tool = body.getValue("tools").jsonArray[1].jsonObject.getValue("function").jsonObject
        assertEquals("string", tool.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("input").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(setOf("patch"), converted.customTools)
        assertEquals("x", body.getValue("metadata").jsonObject.getValue("tenant").jsonPrimitive.content)
    }

    @Test fun rejectsUnsupportedInputsRatherThanDroppingThem() {
        val cases = listOf(
            """{"input":"hi","previous_response_id":"old"}""",
            """{"input":"hi","store":true}""",
            """{"input":"hi","include":null}""",
            """{"input":"hi","include":["web_search"]}""",
            """{"input":"hi","include":["reasoning.encrypted_content"]}""",
            """{"input":"hi","stream":"true"}""",
            """{"input":"hi","stream":1}""",
            """{"input":"hi","store":"false"}""",
            """{"input":[{"type":"reasoning","encrypted_content":"opaque","summary":[]}]}""",
            """{"input":[{"type":"reasoning","summary":[{"type":"summary_text","text":"x","extra":1}]}]}""",
            """{"input":[{"role":"user","content":[{"type":"input_text","text":"x","annotations":[{}]}]}]}""",
            """{"input":[{"role":"user","content":[{"type":"input_text","text":"x","annotations":true}]}]}""",
            """{"input":[{"role":"user","content":[{"type":"input_text","text":"x","annotations":null}]}]}""",
            """{"input":[{"role":"user","content":[{"type":"input_text","text":"x","logprobs":[]}]}]}""",
            """{"input":[{"role":"user","content":[{"type":"input_audio","data":"x"}]}]}""",
            """{"input":"hi","tools":[{"type":"web_search","name":"search"}]}""",
            """{"input":"hi","tools":[{"type":"custom","name":"patch","format":{"type":"grammar"}}]}""",
            """{"input":"hi","tools":[{"type":"custom","name":"patch","format":{"type":"text","extra":1}}]}""",
            """{"input":"hi","text":{"format":{"type":"other"}}}""",
            """{"input":"hi","reasoning":{"other":true}}""",
            """{"input":"hi","reasoning":{"summary":"auto"}}""",
            """{"input":"hi","tool_choice":{"type":"function","name":"x","extra":true}}""",
            """{"input":"hi","tool_choice":{"type":"web_search","name":"search"}}"""
        )
        for (case in cases) assertFails("Should reject $case") { convert(case) }
    }

    @Test fun emptyAnnotationsAndNullUnknownOptionalFieldsArePreservedSafely() {
        val converted = convert("""{"input":[
          {"role":"user","content":[{"type":"input_text","text":"hi","annotations":[],"logprobs":null}]},
          {"type":"reasoning","summary":[{"type":"summary_text","text":"why","future":null}]}
        ],"store":null,"stream":null,"reasoning":{"summary":null},
        "tools":[{"type":"custom","name":"patch","format":{"type":"text","future":null}}],
        "tool_choice":{"type":"custom","name":"patch","future":null}}""")
        assertFalse(converted.stream)
        assertEquals("hi", converted.body.getValue("messages").jsonArray.first().jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("why", converted.body.getValue("messages").jsonArray.last().jsonObject.getValue("reasoning_content").jsonPrimitive.content)
        assertEquals(setOf("patch"), converted.customTools)
    }

    @Test fun nullHandlingAndEmptyToolsMatchRust() {
        val converted = convert("""{"input":"hi","tools":[],"store":false,"stream":null,"temperature":null,"unknown":null}""")
        assertFalse(converted.stream)
        assertFalse("tools" in converted.body)
        assertFalse("temperature" in converted.body)
        assertFalse("unknown" in converted.body)
        assertEquals(emptySet(), converted.customTools)
    }
}
