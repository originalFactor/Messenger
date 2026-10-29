/*
 * Copyright 2026 originalFactor
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
package cc.ptoe.messenger.core

import cc.ptoe.messenger.data.remote.NetworkClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RestCoreBridgeIntegrationTest {
    @Test
    fun crudRoundTripsThroughTheLoopbackServer() = runBlocking {
        val dir = Files.createTempDirectory("messenger-rest-bridge")
        val core = CoreHandle.open(
            storePath = dir.resolve("store.db").toString(),
            legacyDbDir = null,
            nowMs = 1L,
        )
        val server = core.startHttpServer(0u)
        val bridge = RestCoreBridge(server.localAddr(), server.token())
        try {
            val agent = """
                {"id":"a1","name":"Default","avatar":null,"system_prompt":"help",
                 "description":"","default_model_id":null,"temperature":null,"top_p":null,
                 "max_tokens":null,"reasoning_effort":null,"is_default":true,
                 "follow_default_system_prompt":false,"follow_default_model":false,
                 "follow_default_temperature":false,"follow_default_top_p":false,
                 "follow_default_max_tokens":false,"follow_default_reasoning_effort":false,
                 "market_agent_id":null,"market_agent_version":null,"market_agent_role":null,
                 "role":"chat","tools_enabled":false,"tools_follow_default":false,
                 "tools_config":"","created_at":1,"updated_at":1}
            """.trimIndent()
            bridge.upsertAgentJson(agent)
            assertEquals("a1", NetworkClient.json.parseToJsonElement(
                assertNotNull(bridge.getDefaultAgentJson())
            ).let { it as kotlinx.serialization.json.JsonObject }["id"]!!.toString().trim('"'))
            assertEquals(1, NetworkClient.json.parseToJsonElement(bridge.listAgentsJson()).jsonArray.size)

            bridge.kvSet("hello", "world")
            assertEquals("world", bridge.kvGet("hello"))

            val provider = """
                {"id":"p1","name":"Test","base_url":"http://localhost","api_key":"sk",
                 "api_format":"chat","created_at":1,"updated_at":1}
            """.trimIndent()
            bridge.upsertProviderJson(provider)
            assertEquals(1, NetworkClient.json.parseToJsonElement(bridge.listProvidersJson()).jsonArray.size)
            assertTrue(bridge.cloudGetServerUrl().isNotBlank())
        } finally {
            server.stop()
        }
    }
    @Test
    fun chatSseRoundTripsThroughRestCoreBridge() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val provider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello from REST\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            this.executor = executor
            start()
        }
        val dir = Files.createTempDirectory("messenger-rest-chat")
        val core = CoreHandle.open(
            storePath = dir.resolve("store.db").toString(),
            legacyDbDir = null,
            nowMs = 1L,
        )
        val server = core.startHttpServer(0u)
        val bridge = RestCoreBridge(server.localAddr(), server.token())
        try {
            bridge.upsertProviderJson(
                """{"id":"p1","name":"Test","base_url":"http://127.0.0.1:${provider.address.port}",
                    "api_key":"sk","api_format":"chat","created_at":1,"updated_at":1}"""
            )
            bridge.upsertModelJson(
                """{"id":"m1","provider_id":"p1","model_id":"test-model","display_name":"test",
                    "is_enabled":true,"context_window":0,"input_rate":null,"output_rate":null,
                    "cache_read_rate":null,"input_modalities":"text","output_modalities":"text",
                    "supports_tool_calling":false,"supports_thinking":false,
                    "supports_json_output":false,"supports_temperature":true,"created_at":1}"""
            )
            bridge.upsertAgentJson(
                """{"id":"a1","name":"Agent","avatar":null,"system_prompt":"help","description":"",
                    "default_model_id":"m1","temperature":null,"top_p":null,"max_tokens":null,
                    "reasoning_effort":null,"is_default":true,"follow_default_system_prompt":false,
                    "follow_default_model":false,"follow_default_temperature":false,
                    "follow_default_top_p":false,"follow_default_max_tokens":false,
                    "follow_default_reasoning_effort":false,"market_agent_id":null,
                    "market_agent_version":null,"market_agent_role":null,"role":"chat",
                    "tools_enabled":false,"tools_follow_default":false,"tools_config":"",
                    "created_at":1,"updated_at":1}"""
            )
            bridge.upsertConversationJson(
                """{"id":"c1","title":"chat","provider_id":"p1","agent_id":"a1","project_id":null,
                    "override_model_id":null,"override_temperature":null,"override_top_p":null,
                    "override_max_tokens":null,"override_reasoning_effort":null,
                    "override_tools_enabled":null,"override_tools_config":null,"writable":false,
                    "created_at":1,"updated_at":1,"last_message":"Hi","reasoning_format":null,
                    "context_summary":null,"context_summary_until":0,"context_tokens":0,
                    "context_tokens_at":0}"""
            )
            bridge.upsertMessageJson(
                """{"id":"u1","conversation_id":"c1","role":"user","content":"Hi",
                    "parts_json":null,"timestamp":1,"status":"sent","error_message":null}"""
            )
            val events = mutableListOf<String>()
            bridge.runTurn(
                config = TurnConfigBridge(
                    conversationId = "c1",
                    modelId = "test-model",
                    baseUrl = "http://127.0.0.1:${provider.address.port}",
                    apiKey = "sk",
                    apiFormat = "chat",
                    systemPrompt = "help",
                    temperature = null,
                    topP = null,
                    maxTokens = null,
                    reasoningEffort = null,
                    toolNames = emptyList(),
                    writable = false,
                    contextWindow = 0,
                    summarizePrompt = "summarize",
                    titleAgentId = null,
                    titleAgentSystemPrompt = null,
                    titleAgentModelId = null,
                    hasWorkspace = false,
                    workspaceNote = "",
                ),
                toolExecutor = { _, _ -> "unused" to false },
                onEventJson = events::add,
            )
            assertTrue(events.any { it.contains("\"type\":\"TextDelta\"") })
            assertTrue(events.any { it.contains("\"type\":\"Finished\"") })
            val messages = NetworkClient.json.parseToJsonElement(
                bridge.listMessagesByConversationJson("c1")
            ).jsonArray
            assertEquals(2, messages.size)
            assertTrue(messages.last().toString().contains("Hello from REST"))
        } finally {
            server.stop()
            provider.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun toolRequestRoundTripsOverSseAndRest() = runBlocking {
        val requests = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        val provider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = if (requests.getAndIncrement() == 0) {
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"echo\",\"arguments\":\"{\\\"value\\\":\\\"x\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                } else {
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Tool completed\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                }
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            this.executor = executor
            start()
        }
        val dir = Files.createTempDirectory("messenger-rest-tool-chat")
        val core = CoreHandle.open(
            storePath = dir.resolve("store.db").toString(),
            legacyDbDir = null,
            nowMs = 1L,
        )
        val server = core.startHttpServer(0u)
        val bridge = RestCoreBridge(server.localAddr(), server.token())
        try {
            bridge.upsertProviderJson(
                """{"id":"p1","name":"Test","base_url":"http://127.0.0.1:${provider.address.port}",
                    "api_key":"sk","api_format":"chat","created_at":1,"updated_at":1}"""
            )
            bridge.upsertModelJson(
                """{"id":"m1","provider_id":"p1","model_id":"test-model","display_name":"test",
                    "is_enabled":true,"context_window":0,"input_rate":null,"output_rate":null,
                    "cache_read_rate":null,"input_modalities":"text","output_modalities":"text",
                    "supports_tool_calling":true,"supports_thinking":false,
                    "supports_json_output":false,"supports_temperature":true,"created_at":1}"""
            )
            bridge.upsertAgentJson(
                """{"id":"a1","name":"Agent","avatar":null,"system_prompt":"help","description":"",
                    "default_model_id":"m1","temperature":null,"top_p":null,"max_tokens":null,
                    "reasoning_effort":null,"is_default":true,"follow_default_system_prompt":false,
                    "follow_default_model":false,"follow_default_temperature":false,
                    "follow_default_top_p":false,"follow_default_max_tokens":false,
                    "follow_default_reasoning_effort":false,"market_agent_id":null,
                    "market_agent_version":null,"market_agent_role":null,"role":"chat",
                    "tools_enabled":true,"tools_follow_default":false,"tools_config":"",
                    "created_at":1,"updated_at":1}"""
            )
            bridge.upsertConversationJson(
                """{"id":"c1","title":"tools","provider_id":"p1","agent_id":"a1","project_id":null,
                    "override_model_id":null,"override_temperature":null,"override_top_p":null,
                    "override_max_tokens":null,"override_reasoning_effort":null,
                    "override_tools_enabled":null,"override_tools_config":null,"writable":false,
                    "created_at":1,"updated_at":1,"last_message":"Use a tool","reasoning_format":null,
                    "context_summary":null,"context_summary_until":0,"context_tokens":0,
                    "context_tokens_at":0}"""
            )
            bridge.upsertMessageJson(
                """{"id":"u1","conversation_id":"c1","role":"user","content":"Use a tool",
                    "parts_json":null,"timestamp":1,"status":"sent","error_message":null}"""
            )
            var toolCalls = 0
            val events = mutableListOf<String>()
            bridge.runTurn(
                config = TurnConfigBridge(
                    conversationId = "c1",
                    modelId = "test-model",
                    baseUrl = "http://127.0.0.1:${provider.address.port}",
                    apiKey = "sk",
                    apiFormat = "chat",
                    systemPrompt = "help",
                    temperature = null,
                    topP = null,
                    maxTokens = null,
                    reasoningEffort = null,
                    toolNames = listOf("echo"),
                    writable = false,
                    contextWindow = 0,
                    summarizePrompt = "summarize",
                    titleAgentId = null,
                    titleAgentSystemPrompt = null,
                    titleAgentModelId = null,
                    hasWorkspace = false,
                    workspaceNote = "",
                    tools = listOf(
                        ToolSpecBridge(
                            name = "echo",
                            description = "Echo a value",
                            parametersJson = """{"type":"object"}""",
                        )
                    ),
                ),
                toolExecutor = { name, _ ->
                    assertEquals("echo", name)
                    toolCalls++
                    "tool-output" to false
                },
                onEventJson = events::add,
            )
            assertEquals(1, toolCalls)
            assertTrue(events.any { it.contains("\"type\":\"ToolCallRequested\"") })
            assertTrue(events.any { it.contains("\"type\":\"ToolCallFinished\"") })
            assertTrue(events.any { it.contains("\"type\":\"Finished\"") })
            val messages = NetworkClient.json.parseToJsonElement(
                bridge.listMessagesByConversationJson("c1")
            ).jsonArray
            assertEquals(4, messages.size)
            assertTrue(messages.last().toString().contains("Tool completed"))
        } finally {
            server.stop()
            provider.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun fullHistoryReadFollowsPagesBeyondTheServerLimit() = runBlocking {
        val dir = Files.createTempDirectory("messenger-rest-history")
        val core = CoreHandle.open(
            storePath = dir.resolve("store.db").toString(),
            legacyDbDir = null,
            nowMs = 1L,
        )
        val server = core.startHttpServer(0u)
        val bridge = RestCoreBridge(server.localAddr(), server.token())
        try {
            bridge.upsertAgentJson(
                """{"id":"a1","name":"Agent","avatar":null,"system_prompt":"help","description":"",
                    "default_model_id":null,"temperature":null,"top_p":null,"max_tokens":null,
                    "reasoning_effort":null,"is_default":true,"follow_default_system_prompt":false,
                    "follow_default_model":false,"follow_default_temperature":false,
                    "follow_default_top_p":false,"follow_default_max_tokens":false,
                    "follow_default_reasoning_effort":false,"market_agent_id":null,
                    "market_agent_version":null,"market_agent_role":null,"role":"chat",
                    "tools_enabled":false,"tools_follow_default":false,"tools_config":"",
                    "created_at":1,"updated_at":1}"""
            )
            bridge.upsertConversationJson(
                """{"id":"c1","title":"history","provider_id":"p1","agent_id":"a1",
                    "project_id":null,"override_model_id":null,"override_temperature":null,
                    "override_top_p":null,"override_max_tokens":null,
                    "override_reasoning_effort":null,"override_tools_enabled":null,
                    "override_tools_config":null,"writable":false,"created_at":1,"updated_at":1,
                    "last_message":null,"reasoning_format":null,"context_summary":null,
                    "context_summary_until":0,"context_tokens":0,"context_tokens_at":0}"""
            )
            repeat(501) { index ->
                bridge.upsertMessageJson(
                    """{"id":"m$index","conversation_id":"c1","role":"user",
                        "content":"message $index","parts_json":null,"timestamp":$index,
                        "status":"sent","error_message":null}"""
                )
            }

            val messages = NetworkClient.json.parseToJsonElement(
                bridge.listMessagesByConversationJson("c1")
            ).jsonArray
            assertEquals(501, messages.size)
            assertEquals("m0", messages.first().jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals("m500", messages.last().jsonObject.getValue("id").jsonPrimitive.content)
        } finally {
            server.stop()
        }
    }
}
