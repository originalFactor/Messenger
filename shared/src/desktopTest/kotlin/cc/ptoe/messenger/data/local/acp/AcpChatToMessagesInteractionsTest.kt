/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class AcpChatToMessagesInteractionsTest {
    private val codec = AcpChatToMessagesInteractions { "test" }
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
    @Test fun preservesTextToolCallsAndUsageForBothTargets() {
        val input = json("""{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"answer","tool_calls":[{"id":"call","type":"function","function":{"name":"read","arguments":"{\"path\":\"a\"}"}}]}}],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}""")
        val messages = codec.convert(input, AcpRelayFormat.MESSAGES, "messenger/m")
        assertEquals("tool_use", messages["stop_reason"]!!.jsonPrimitive.content)
        assertEquals("answer", messages["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("a", messages["content"]!!.jsonArray[1].jsonObject["input"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(3, messages["usage"]!!.jsonObject["output_tokens"]!!.jsonPrimitive.int)
        val interactions = codec.convert(input, AcpRelayFormat.INTERACTIONS, "messenger/m")
        assertEquals("requires_action", interactions["status"]!!.jsonPrimitive.content)
        assertEquals("function_call", interactions["outputs"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(5, interactions["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.int)
    }
    @Test fun refusesReasoningTruncationAndAmbiguousToolArguments() {
        val reasoning = json("""{"choices":[{"finish_reason":"stop","message":{"content":"answer","reasoning_content":"thought"}}]}""")
        assertFails { codec.convert(reasoning, AcpRelayFormat.MESSAGES, "m") }
        val truncated = json("""{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}""")
        assertEquals("max_tokens", codec.convert(truncated, AcpRelayFormat.MESSAGES, "m")["stop_reason"]!!.jsonPrimitive.content)
        assertFails { codec.convert(truncated, AcpRelayFormat.INTERACTIONS, "m") }
        val duplicate = json("""{"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[{"id":"c","type":"function","function":{"name":"f","arguments":"{\"x\":1,\"x\":2}"}}]}}]}""")
        assertFails { codec.convert(duplicate, AcpRelayFormat.MESSAGES, "m") }
    }
}
