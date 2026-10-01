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

package cc.ptoe.messenger.domain.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Test
    fun `test McpServerConfig serialization and deserialization`() {
        val servers = listOf(
            McpServerConfig(
                id = "srv-1",
                name = "Local Everything",
                transportType = McpTransportType.STDIO,
                isEnabled = true,
                command = "npx",
                args = listOf("-y", "@modelcontextprotocol/server-everything"),
                env = mapOf("DEBUG" to "1")
            ),
            McpServerConfig(
                id = "srv-2",
                name = "Remote SSE",
                transportType = McpTransportType.SSE,
                isEnabled = false,
                url = "https://example.com/sse",
                headers = mapOf("Authorization" to "Bearer test-token")
            )
        )

        val serialized = json.encodeToString(ListSerializer(McpServerConfig.serializer()), servers)
        val deserialized = json.decodeFromString(ListSerializer(McpServerConfig.serializer()), serialized)

        assertEquals(2, deserialized.size)
        assertEquals("Local Everything", deserialized[0].name)
        assertEquals(McpTransportType.STDIO, deserialized[0].transportType)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-everything"), deserialized[0].args)
        assertEquals("Remote SSE", deserialized[1].name)
        assertEquals(McpTransportType.SSE, deserialized[1].transportType)
        assertFalse(deserialized[1].isEnabled)
    }

    @Test
    fun `test JsonRpc parsing and serialization`() {
        val req = JsonRpcRequest(
            id = 1,
            method = "tools/call",
            params = buildJsonObject {
                put("name", "echo")
                put("arguments", buildJsonObject { put("message", "hello") })
            }
        )

        val str = json.encodeToString(JsonRpcRequest.serializer(), req)
        assertTrue(str.contains("\"method\":\"tools/call\""))
        assertTrue(str.contains("\"jsonrpc\":\"2.0\""))

        val responseStr = """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"hello"}]}}"""
        val resp = json.decodeFromString<JsonRpcResponse>(responseStr)
        assertEquals(1L, resp.id)
        assertTrue(resp.error == null)
    }

    @Test
    fun `test McpClient with mock process bridge`() = runBlocking {
        var onLineCallback: ((String) -> Unit)? = null
        val mockBridge = object : McpProcessBridge {
            override suspend fun startProcess(
                command: String,
                env: Map<String, String>,
                onLine: (String) -> Unit,
                onError: (String) -> Unit,
                onClose: (Int) -> Unit
            ): Boolean {
                onLineCallback = onLine
                return true
            }

            override suspend fun sendLine(line: String): Boolean {
                val req = json.decodeFromString<JsonRpcRequest>(line)
                when (req.method) {
                    "initialize" -> {
                        val resp = """{"jsonrpc":"2.0","id":${req.id},"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"mock","version":"1.0"}}}"""
                        CoroutineScope(Dispatchers.Default).launch {
                            delay(10)
                            onLineCallback?.invoke(resp)
                        }
                    }
                    "tools/list" -> {
                        val resp = """{"jsonrpc":"2.0","id":${req.id},"result":{"tools":[{"name":"echo","description":"Echo back","inputSchema":{"type":"object","properties":{"message":{"type":"string"}}}}]}}"""
                        CoroutineScope(Dispatchers.Default).launch {
                            delay(10)
                            onLineCallback?.invoke(resp)
                        }
                    }
                    "tools/call" -> {
                        val resp = """{"jsonrpc":"2.0","id":${req.id},"result":{"content":[{"type":"text","text":"echoed back"}]}}"""
                        CoroutineScope(Dispatchers.Default).launch {
                            delay(10)
                            onLineCallback?.invoke(resp)
                        }
                    }
                }
                return true
            }

            override suspend fun close() {}
        }

        val config = McpServerConfig(
            id = "mock-1",
            name = "MockServer",
            transportType = McpTransportType.STDIO,
            command = "mock"
        )
        val client = McpClient(config) { mockBridge }

        val connected = client.connect()
        assertTrue(connected)

        val tools = client.tools.value
        assertEquals(1, tools.size)
        assertEquals("MockServer_echo", tools[0].name)
        assertEquals("Echo back", tools[0].description)

        val toolResult = tools[0].execute("""{"message":"hi"}""")
        assertEquals("echoed back", toolResult.output)
        assertFalse(toolResult.isError)

        client.close()
    }
}
