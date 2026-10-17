/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.ptoe.messenger.data.remote.format

import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.dto.ChatMessageDto
import cc.ptoe.messenger.data.remote.dto.ToolCallDto
import cc.ptoe.messenger.data.remote.dto.ToolCallFunctionDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.domain.model.ApiFormat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 非 Chat 协议（Responses / Messages / Interactions）的请求编码、非流式
 * 响应解码与 SSE 解码测试。三者最终都映射到与 Chat Completions 相同的
 * 事件/回复形状，因此这里断言的是共享形状。
 */
class ApiFormatCodecTest {

    private fun request(stream: Boolean) = ChatCompletionRequestDto(
        model = "m1",
        messages = listOf(
            ChatMessageDto(role = "system", content = JsonPrimitive("be nice")),
            ChatMessageDto(role = "user", content = JsonPrimitive("hi")),
            ChatMessageDto(
                role = "assistant",
                content = JsonPrimitive(""),
                toolCalls = listOf(
                    ToolCallDto(
                        id = "c1",
                        type = "function",
                        function = ToolCallFunctionDto(name = "terminal", arguments = """{"command":"ls"}""")
                    )
                )
            ),
            ChatMessageDto(role = "tool", content = JsonPrimitive("a.txt"), toolCallId = "c1")
        ),
        maxTokens = 100,
        tools = listOf(
            ToolSpecDto(
                type = "function",
                function = ToolSpecFunctionDto(
                    name = "terminal",
                    description = "run",
                    parameters = Json.parseToJsonElement("""{"type":"object"}""")
                )
            )
        ),
        stream = stream
    )

    @Test
    fun `messages request moves system out and pairs tool blocks`() {
        val body = ApiFormatRequests.encode(ApiFormat.MESSAGES, request(stream = true))
        assertEquals("be nice", body["system"]!!.jsonPrimitive.content)
        assertEquals(100, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        val messages = body["messages"]!!.jsonArray
        // user, assistant tool_use, then one user turn carrying tool_result.
        assertEquals(3, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        val toolUse = messages[1].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_use", toolUse["type"]!!.jsonPrimitive.content)
        assertEquals("terminal", toolUse["name"]!!.jsonPrimitive.content)
        val toolResult = messages[2].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", toolResult["type"]!!.jsonPrimitive.content)
        assertEquals("c1", toolResult["tool_use_id"]!!.jsonPrimitive.content)
        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("terminal", tool["name"]!!.jsonPrimitive.content)
        assertNotNull(tool["input_schema"])
    }

    @Test
    fun `responses request uses instructions and function items`() {
        val body = ApiFormatRequests.encode(ApiFormat.RESPONSES, request(stream = false))
        assertEquals("be nice", body["instructions"]!!.jsonPrimitive.content)
        assertEquals(100, body["max_output_tokens"]!!.jsonPrimitive.content.toInt())
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals("message", input[0]["type"]!!.jsonPrimitive.content)
        assertEquals("input_text", input[0]["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("function_call", input[1]["type"]!!.jsonPrimitive.content)
        assertEquals("c1", input[1]["call_id"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", input[2]["type"]!!.jsonPrimitive.content)
        assertEquals(false, body["stream"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `interactions request is stateless and maps generation config`() {
        val body = ApiFormatRequests.encode(ApiFormat.INTERACTIONS, request(stream = true))
        assertEquals(false, body["store"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("be nice", body["system_instruction"]!!.jsonPrimitive.content)
        val generation = body["generation_config"]!!.jsonObject
        assertEquals(100, generation["max_output_tokens"]!!.jsonPrimitive.content.toInt())
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals("user_input", input[0]["type"]!!.jsonPrimitive.content)
        assertEquals("function_call", input[1]["type"]!!.jsonPrimitive.content)
        assertEquals("function_result", input[2]["type"]!!.jsonPrimitive.content)
        assertEquals("c1", input[2]["call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `format paths and auth headers`() {
        assertEquals("/chat/completions", ApiFormatRequests.path(ApiFormat.CHAT))
        assertEquals("/responses", ApiFormatRequests.path(ApiFormat.RESPONSES))
        assertEquals("/messages", ApiFormatRequests.path(ApiFormat.MESSAGES))
        assertEquals("/interactions", ApiFormatRequests.path(ApiFormat.INTERACTIONS))
        assertEquals(
            mapOf("x-api-key" to "sk", "anthropic-version" to "2023-06-01"),
            ApiFormatRequests.authHeaders(ApiFormat.MESSAGES, "sk")
        )
        assertEquals(
            mapOf("x-goog-api-key" to "sk"),
            ApiFormatRequests.authHeaders(ApiFormat.INTERACTIONS, "sk")
        )
        assertTrue(ApiFormatRequests.authHeaders(ApiFormat.CHAT, "sk").isEmpty())
    }

    @Test
    fun `non streaming replies decode into the chat completion shape`() {
        val messages = ApiFormatStreamParser.decodeCompletion(
            ApiFormat.MESSAGES,
            Json.parseToJsonElement(
                """
                {"content":[
                  {"type":"thinking","thinking":"think"},
                  {"type":"text","text":"hello"},
                  {"type":"tool_use","id":"c9","name":"glob","input":{"p":"*"}}
                ],"stop_reason":"tool_use",
                "usage":{"input_tokens":10,"output_tokens":4}}
                """.trimIndent()
            ).jsonObject
        )
        val interactions = ApiFormatStreamParser.decodeCompletion(
            ApiFormat.INTERACTIONS,
            Json.parseToJsonElement(
                """
                {"steps":[
                  {"type":"thought","summary":[{"type":"text","text":"think"}]},
                  {"type":"model_output","content":[{"type":"text","text":"hello"}]},
                  {"type":"function_call","id":"c9","name":"glob","arguments":{"p":"*"}}
                ],"usage":{"total_input_tokens":10,"total_output_tokens":4,"total_tokens":14}}
                """.trimIndent()
            ).jsonObject
        )
        val responses = ApiFormatStreamParser.decodeCompletion(
            ApiFormat.RESPONSES,
            Json.parseToJsonElement(
                """
                {"status":"completed","output":[
                  {"type":"reasoning","summary":[{"type":"summary_text","text":"think"}]},
                  {"type":"message","content":[{"type":"output_text","text":"hello"}]},
                  {"type":"function_call","id":"fc_1","call_id":"c9","name":"glob","arguments":"{\"p\":\"*\"}"}
                ],"usage":{"input_tokens":10,"output_tokens":4,"total_tokens":14}}
                """.trimIndent()
            ).jsonObject
        )
        for (decoded in listOf(messages, interactions, responses)) {
            val message = decoded.choices.first().message
            assertEquals("hello", message.content.jsonPrimitive.content)
            assertEquals("think", message.reasoningContent)
            assertEquals("c9", message.toolCalls!!.single().id)
            assertEquals("glob", message.toolCalls!!.single().function!!.name)
            assertEquals("tool_calls", decoded.choices.first().finishReason)
            assertEquals(10, decoded.usage!!.promptTokens)
            assertEquals(4, decoded.usage!!.completionTokens)
        }
    }

    private fun decodeEvents(format: ApiFormat, payloads: List<String>): List<ChatStreamEvent> =
        runBlocking {
            ApiFormatStreamParser.parseToEvents(format, flowOf(*payloads.toTypedArray())).toList()
        }

    private fun contents(events: List<ChatStreamEvent>): List<String> = events
        .filterIsInstance<ChatStreamEvent.Content>()
        .map { it.text }

    @Test
    fun `messages stream wraps reasoning and accumulates tool use`() {
        val events = decodeEvents(
            ApiFormat.MESSAGES,
            listOf(
                """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0}}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"think"}}""",
                """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"hello"}}""",
                """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"c9","name":"glob"}}""",
                """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"p\":\"*\"}"}}""",
                """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":4}}""",
                """{"type":"message_stop"}"""
            )
        )
        assertEquals(listOf("<think>", "think", "</think>\n", "hello"), contents(events))
        assertTrue(events.any { it is ChatStreamEvent.ReasoningDetected && it.format == "reasoning_summary" })
        val done = events.filterIsInstance<ChatStreamEvent.Done>()
        assertEquals("tool_calls", done.last().finishReason)
        val call = done.last().toolCalls.single()
        assertEquals("c9", call.callId)
        assertEquals("""{"p":"*"}""", call.arguments)
        assertEquals(4, done.last().usage!!.completionTokens)
    }

    @Test
    fun `interactions stream decodes steps`() {
        val events = decodeEvents(
            ApiFormat.INTERACTIONS,
            listOf(
                """{"event_type":"step.delta","index":0,"delta":{"type":"text","text":"hello"}}""",
                """{"event_type":"step.start","index":1,"step":{"type":"function_call","id":"c9","name":"glob"}}""",
                """{"event_type":"step.delta","index":1,"delta":{"type":"arguments_delta","arguments":"{\"p\":\"*\"}"}}""",
                """{"event_type":"interaction.completed","interaction":{"steps":[],"usage":{"total_input_tokens":10,"total_output_tokens":4,"total_tokens":14}}}"""
            )
        )
        assertEquals(listOf("hello"), contents(events))
        val call = events.filterIsInstance<ChatStreamEvent.Done>().last().toolCalls.single()
        assertEquals("glob", call.name)
    }

    @Test
    fun `responses stream decodes deltas and completion`() {
        val events = decodeEvents(
            ApiFormat.RESPONSES,
            listOf(
                """{"type":"response.output_text.delta","delta":"hello"}""",
                """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"c9","name":"glob"}}""",
                """{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\"p\":\"*\"}"}""",
                """{"type":"response.completed","response":{"status":"completed","output":[],"usage":{"input_tokens":10,"output_tokens":4,"total_tokens":14}}}"""
            )
        )
        assertEquals(listOf("hello"), contents(events))
        val call = events.filterIsInstance<ChatStreamEvent.Done>().last().toolCalls.single()
        assertEquals("c9", call.callId)
        assertEquals("""{"p":"*"}""", call.arguments)
    }

    @Test
    fun `auto resolves to chat on the wire`() {
        assertEquals(ApiFormat.CHAT, ApiFormat.AUTO.requestFormat)
        assertEquals(ApiFormat.MESSAGES, ApiFormat.MESSAGES.requestFormat)
        assertEquals(ApiFormat.CHAT, ApiFormat.fromWireName("auto").requestFormat)
        assertEquals(ApiFormat.MESSAGES, ApiFormat.fromWireName("messages"))
        assertEquals(ApiFormat.CHAT, ApiFormat.fromWireName(null))
        assertEquals(ApiFormat.CHAT, ApiFormat.fromWireName("nonsense"))
    }
}
