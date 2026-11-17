/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpMessagesInteractionsToChatTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun convert(source: String, body: String) = AcpMessagesInteractionsToChat.convert(source, json(body), "upstream")

    @Test fun rustMessagesToolHistoryFixture() {
        val result = convert("messages", """{"messages":[
          {"role":"assistant","content":[{"type":"tool_use","id":"c","name":"read","input":{"path":"file"}}]},
          {"role":"user","content":[{"type":"tool_result","tool_use_id":"c","content":"result"},{"type":"text","text":"continue"}]}
        ]}""")
        val expected = json("""{"model":"upstream","stream":false,"messages":[
          {"role":"assistant","content":"","tool_calls":[{"id":"c","type":"function","function":{"name":"read","arguments":"{\"path\":\"file\"}"}}]},
          {"role":"tool","tool_call_id":"c","content":"result"}, {"role":"user","content":"continue"}]}
        """)
        assertEquals(expected, result)
    }

    @Test fun interactionsSystemFunctionsToolsAndStreaming() {
        val result = convert("interactions", """{"model":"messenger/m","system_instruction":[{"type":"text","text":"be "},{"type":"input_text","text":"careful"}],
          "input":[{"type":"user_input","content":"read it"},{"type":"function_call","id":"c1","name":"read","arguments":{"path":"a"}},
            {"type":"function_result","call_id":"c1","result":[{"type":"output_text","text":"line 1"},{"type":"text","text":" line 2"}]},
            {"type":"model_output","content":[{"type":"text","text":"done"}]}],
          "temperature":0.4,"top_p":0.8,"max_tokens":24,"stream":true,
          "tools":[{"type":"function","name":"read","description":"Read a file","parameters":{"type":"object","properties":{"path":{"type":"string"}}}}]}""")
        val messages = result.getValue("messages").jsonArray
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant"), messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals("be careful", messages[0].jsonObject.getValue("content").jsonPrimitive.content)
        val call = messages[2].jsonObject.getValue("tool_calls").jsonArray.single().jsonObject
        assertEquals("c1", call.getValue("id").jsonPrimitive.content)
        assertEquals(json("""{"path":"a"}"""), Json.parseToJsonElement(call.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content))
        assertEquals("line 1 line 2", messages[3].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(24, result.getValue("max_tokens").jsonPrimitive.int)
        assertEquals(true, result.getValue("stream_options").jsonObject.getValue("include_usage").jsonPrimitive.boolean)
        assertEquals("read", result.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test fun messagesTextAndToolsPreserveValues() {
        val result = convert("messages", """{"system":"system", "messages":[
          {"role":"user","content":[{"type":"text","text":"a"},{"type":"text","text":" b"}]},
          {"role":"assistant","content":[{"type":"text","text":"looking"},{"type":"tool_use","id":"a","name":"read","input":{}},
            {"type":"tool_use","id":"b","name":"write","input":{"x":1}}]},
          {"role":"user","content":[{"type":"tool_result","tool_use_id":"a","content":"A","is_error":false},
            {"type":"tool_result","tool_use_id":"b","content":[{"type":"text","text":"B"}]},{"type":"text","text":"next"}]},
          {"role":"user","content":[]}],"tools":[{"name":"read","input_schema":{"type":"object"}}],"stream":null}""")
        val messages = result.getValue("messages").jsonArray
        assertEquals("a b", messages[1].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("looking", messages[2].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(listOf("a", "b"), messages[2].jsonObject.getValue("tool_calls").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(listOf("tool", "tool", "user", "user"), messages.drop(3).map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals("", messages.last().jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("", result.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject.getValue("description").jsonPrimitive.content)
        assertFalse(result.getValue("stream").jsonPrimitive.boolean)
    }

    @Test fun rejectsUnsupportedAndInvalidMessages() {
        val cases = listOf(
            """{"messages":[],"previous_response_id":"x"}""",
            """{"messages":[],"stream":"true"}""",
            """{"messages":[],"temperature":true}""",
            """{"messages":[],"max_tokens":0}""",
            """{"messages":[{"role":"system","content":"x"}]}""",
            """{"messages":[{"role":"user","content":"x","cache_control":{"type":"ephemeral"}}]}""",
            """{"messages":[{"role":"user","content":[{"type":"text","text":"x","cache_control":{"type":"ephemeral"}}]}]}""",
            """{"messages":[{"role":"user","content":[{"type":"image","source":{"type":"base64","data":"AA=="}}]}]}""",
            """{"messages":[{"role":"assistant","content":[{"type":"tool_use","id":"a","name":"f","input":{}} ,{"type":"text","text":"later"}]}]}""",
            """{"messages":[{"role":"user","content":[{"type":"text","text":"before"},{"type":"tool_result","tool_use_id":"a","content":"result"}]}]}""",
            """{"messages":[{"role":"assistant","content":[{"type":"tool_result","tool_use_id":"a","content":"result"}]}]}""",
            """{"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"a","content":"result","is_error":true}]}]}""",
            """{"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"a","content":"result","is_error":"false"}]}]}""",
            """{"messages":[{"role":"assistant","content":[{"type":"tool_use","id":"a","name":"f","input":"{}"}]}]}""",
            """{"messages":[],"tools":[{"type":"web_search_20250305","name":"web_search","input_schema":{}}]}""",
            """{"messages":[],"tools":[{"name":"f","input_schema":{},"strict":true}]}""",
            """{"messages":[],"tools":[{"name":"f","input_schema":false}]}"""
        )
        for (case in cases) assertFails("Should reject $case") { convert("messages", case) }
    }

    @Test fun rejectsUnsupportedInteractions() {
        val cases = listOf(
            """{"input":"hi","previous_interaction_id":"old"}""",
            """{"input":[{"type":"thought","summary":"x"}]}""",
            """{"input":[{"type":"user_input","content":"hi","extra":1}]}""",
            """{"input":[{"type":"function_call","id":"x","name":"f","arguments":null}]}""",
            """{"input":[{"type":"function_call","id":"x","name":"f","arguments":{},"thought_signature":"opaque"}]}""",
            """{"input":[{"type":"function_result","call_id":"x","result":[{"type":"text","text":"hi","citation":"a"}]}]}""",
            """{"input":[],"tools":[{"type":"function","name":"f","parameters":[],"description":"f"}]}"""
        )
        for (case in cases) assertFails("Should reject $case") { convert("interactions", case) }
    }

    @Test fun nullFieldsAndStringInputRemainCompatible() {
        val result = convert("interactions", """{"input":"hello","system_instruction":null,"temperature":null,"top_p":null,"max_tokens":null,"stream":false,"unused":null,"tools":[]}""")
        assertEquals("hello", result.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content)
        assertTrue(result.getValue("temperature") is JsonNull)
        assertTrue(result.getValue("tools").jsonArray.isEmpty())
        assertFalse("stream_options" in result)
    }
}
