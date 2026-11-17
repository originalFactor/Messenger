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

package cc.ptoe.messenger.domain.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cc.ptoe.messenger.data.local.AppPreferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertIs

class McpTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Test fun `tool outcomes distinguish replies from uncertain dispatch`() = runBlocking {
        var lineCallback: ((String) -> Unit)? = null
        var closeCallback: ((Int) -> Unit)? = null
        var mode = "success"
        var rawResult = "{}"
        var envelopeFields = ""
        var calls = 0
        val bridge = object : McpProcessBridge {
            override suspend fun startProcess(command: String, env: Map<String, String>, onLine: (String) -> Unit,
                onError: (String) -> Unit, onClose: (Int) -> Unit): Boolean {
                lineCallback = onLine; closeCallback = onClose
                return true
            }
            override suspend fun sendLine(line: String): Boolean {
                val request = json.decodeFromString<JsonRpcRequest>(line)
                when (request.method) {
                    "initialize" -> lineCallback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"mock","version":"1"}}}""")
                    "tools/list" -> lineCallback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":{"tools":[]}}""")
                    "tools/call" -> {
                        calls++
                        when (mode) {
                            "success" -> lineCallback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":{"content":[{"type":"text","text":"done"}]}}""")
                            "tool-error" -> lineCallback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":{"content":[{"type":"text","text":"tool error"}],"isError":true}}""")
                            "protocol-error" -> lineCallback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"error":{"code":-32001,"message":"server error"}}""")
                            "malformed" -> lineCallback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":{}}""")
                            "raw" -> lineCallback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":$rawResult}""")
                            "envelope" -> lineCallback?.invoke("""{"jsonrpc":"2.0","id":${request.id},$envelopeFields}""")
                            "missing-version" -> lineCallback?.invoke("""{"id":${request.id},"result":{"content":[]}}""")
                            "write-failed" -> return false
                            "write-threw" -> error("partial write is possible")
                            "closed" -> closeCallback?.invoke(9)
                        }
                    }
                }
                return true
            }
            override suspend fun close() = Unit
        }
        val client = McpClient(McpServerConfig(id = "mock", name = "Mock", transportType = McpTransportType.STDIO, command = "mock")) { bridge }
        try {
            assertIs<McpToolCallOutcome.NotDispatched>(client.callToolOutcome("write", "{}"))
            assertEquals(0, calls)
            assertTrue(client.connect())
            val success = assertIs<McpToolCallOutcome.Result>(client.callToolOutcome("write", "{}"))
            assertEquals("done", success.result.output)
            assertEquals("done", client.callTool("write", "{}").output)
            mode = "tool-error"
            assertTrue(assertIs<McpToolCallOutcome.Result>(client.callToolOutcome("write", "{}")).result.isError)
            mode = "protocol-error"
            val error = assertIs<McpToolCallOutcome.ProtocolError>(client.callToolOutcome("write", "{}"))
            assertEquals(-32001, error.code)
            assertEquals("MCP error (-32001): server error", client.callTool("write", "{}").output)
            mode = "malformed"
            assertIs<McpToolCallOutcome.Unknown>(client.callToolOutcome("write", "{}"))
            mode = "raw"
            val malformedBlocks = listOf(
                """{"type":"text"}""", """{"type":"text","text":12}""",
                """{"type":"image","mimeType":"image/png"}""", """{"type":"image","data":"abc"}""",
                """{"type":"image","data":"%%%+","mimeType":"image/png"}""",
                """{"type":"audio","data":"abc","mimeType":false}""",
                """{"type":"resource_link","uri":"file:///a"}""",
                """{"type":"resource","resource":{"uri":"file:///a"}}""",
                """{"type":"resource","resource":{"uri":"file:///a","text":false,"blob":"abc"}}""",
                """{"type":"future","value":"opaque"}""", """42"""
            )
            for (block in malformedBlocks) {
                rawResult = """{"content":[$block]}"""
                assertIs<McpToolCallOutcome.Unknown>(client.callToolOutcome("write", "{}"), block)
            }
            rawResult = """{"content":[],"structuredContent":42}"""
            assertIs<McpToolCallOutcome.Unknown>(client.callToolOutcome("write", "{}"))
            rawResult = """{"content":[{"type":"text","text":"hello"},{"type":"image","data":"YWJj","mimeType":"image/png"},{"type":"audio","data":"YWJj","mimeType":"audio/wav"},{"type":"resource_link","name":"a","uri":"file:///a"},{"type":"resource","resource":{"uri":"file:///b","blob":"YWJj","mimeType":"application/octet-stream"}},{"type":"resource","resource":{"uri":"file:///c","text":"content"}}],"structuredContent":{"answer":42}}"""
            val opaque = assertIs<McpToolCallOutcome.Result>(client.callToolOutcome("write", "{}"))
            assertEquals(json.parseToJsonElement(rawResult), opaque.rawResult)
            assertEquals("hello\n\n\n\n\n", client.callTool("write", "{}").output)
            mode = "envelope"
            for (fields in listOf(
                """"error":null,"result":{"content":[{"type":"text","text":"legacy"}]}""",
                """"method":"tools/call","result":{"content":[{"type":"text","text":"legacy"}]}""",
                """"params":{},"result":{"content":[{"type":"text","text":"legacy"}]}"""
            )) {
                envelopeFields = fields
                assertIs<McpToolCallOutcome.Unknown>(client.callToolOutcome("write", "{}"), fields)
                assertEquals("legacy", client.callTool("write", "{}").output)
            }
            mode = "missing-version"
            assertIs<McpToolCallOutcome.Unknown>(client.callToolOutcome("write", "{}"))
            mode = "write-failed"
            assertIs<McpToolCallOutcome.Unknown>(client.callToolOutcome("write", "{}"))
            mode = "write-threw"
            assertIs<McpToolCallOutcome.Unknown>(client.callToolOutcome("write", "{}"))
            assertFailsWith<IllegalStateException> { client.callTool("write", "{}") }
            mode = "closed"
            assertIs<McpToolCallOutcome.Unknown>(client.callToolOutcome("write", "{}"))
            assertIs<McpToolCallOutcome.NotDispatched>(client.callToolOutcome("write", "{}"))
            assertEquals(31, calls)
        } finally { client.close() }
    }

    @Test
    fun `tool inventory waits for initial server discovery`() = runBlocking {
        val preferences = object : DataStore<Preferences> {
            override val data = MutableStateFlow<Preferences>(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val updated = transform(data.value)
                data.value = updated
                return updated
            }
        }
        val appPreferences = AppPreferences(preferences)
        val config = McpServerConfig(id = "mock", name = "Mock", transportType = McpTransportType.STDIO, command = "mock")
        appPreferences.setMcpServersJson(json.encodeToString(ListSerializer(McpServerConfig.serializer()), listOf(config)))
        val initializeSent = CompletableDeferred<Unit>()
        val allowInitialize = CompletableDeferred<Unit>()
        var lineCallback: ((String) -> Unit)? = null
        val bridge = object : McpProcessBridge {
            override suspend fun startProcess(command: String, env: Map<String, String>, onLine: (String) -> Unit,
                onError: (String) -> Unit, onClose: (Int) -> Unit): Boolean {
                lineCallback = onLine
                return true
            }
            override suspend fun sendLine(line: String): Boolean {
                val request = json.decodeFromString<JsonRpcRequest>(line)
                when (request.method) {
                    "initialize" -> {
                        initializeSent.complete(Unit)
                        CoroutineScope(Dispatchers.Default).launch {
                            allowInitialize.await()
                            lineCallback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"mock","version":"1"}}}""")
                        }
                    }
                    "tools/list" -> lineCallback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":{"tools":[{"name":"echo","description":"Echo","inputSchema":{"type":"object"}}]}}""")
                }
                return true
            }
            override suspend fun close() {}
        }
        val manager = McpManager(appPreferences) { bridge }
        withTimeout(5_000) { initializeSent.await() }
        assertFalse(manager.toolInventoryReady.value)
        allowInitialize.complete(Unit)
        withTimeout(5_000) { manager.toolInventoryReady.first { it } }
        assertEquals("Mock_echo", manager.activeTools.value.single().name)
        manager.removeServer("mock")
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
