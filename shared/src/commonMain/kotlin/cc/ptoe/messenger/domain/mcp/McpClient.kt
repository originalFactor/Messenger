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

import cc.ptoe.messenger.domain.tool.ChatTool
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class McpClient(
    val config: McpServerConfig,
    private val bridgeFactory: () -> McpProcessBridge = { createPlatformMcpBridge() }
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val mutex = Mutex()
    private val requestMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var requestId = 0L

    private val pendingRequests = mutableMapOf<Long, CompletableDeferred<JsonRpcResponse>>()
    private val _tools = MutableStateFlow<List<ChatTool>>(emptyList())
    val tools: StateFlow<List<ChatTool>> = _tools.asStateFlow()

    private var processBridge: McpProcessBridge? = null
    private var sseEndpointUrl: String? = null
    private var httpClient: HttpClient? = null
    private var isInitialized = false

    suspend fun connect(): Boolean = mutex.withLock {
        if (isInitialized) return true
        return try {
            when (config.transportType) {
                McpTransportType.STDIO -> connectStdio()
                McpTransportType.SSE -> connectSse()
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun connectStdio(): Boolean {
        val bridge = bridgeFactory()
        processBridge = bridge
        val fullCommand = if (config.args.isNotEmpty()) {
            "${config.command} ${config.args.joinToString(" ")}"
        } else {
            config.command
        }

        val started = bridge.startProcess(
            command = fullCommand,
            env = config.env,
            onLine = { line -> handleIncomingLine(line) },
            onError = { /* stderr log */ },
            onClose = { code ->
                handleConnectionClosed(code)
            }
        )
        if (!started) return false

        return initializeHandshake()
    }

    private suspend fun connectSse(): Boolean {
        val client = HttpClient {
            install(SSE)
            install(ContentNegotiation) {
                json(json)
            }
        }
        httpClient = client
        val endpointDeferred = CompletableDeferred<String>()

        scope.launch {
            try {
                client.sse(
                    urlString = config.url,
                    request = {
                        config.headers.forEach { (k, v) -> header(k, v) }
                    }
                ) {
                    incoming.collect { event ->
                        when (event.event) {
                            "endpoint" -> {
                                val url = event.data?.trim().orEmpty()
                                val resolvedUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
                                    url
                                } else {
                                    val base = config.url.substringBeforeLast('/')
                                    "$base/${url.removePrefix("/")}"
                                }
                                sseEndpointUrl = resolvedUrl
                                endpointDeferred.complete(resolvedUrl)
                            }
                            "message" -> {
                                event.data?.let { handleIncomingLine(it) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!endpointDeferred.isCompleted) {
                    endpointDeferred.completeExceptionally(e)
                }
            }
        }

        val endpoint = withTimeoutOrNull(10_000) { endpointDeferred.await() } ?: return false
        sseEndpointUrl = endpoint
        return initializeHandshake()
    }

    private suspend fun initializeHandshake(): Boolean {
        val initParams = json.encodeToJsonElement(McpInitializeParams())
        val initResponse = sendRequest("initialize", initParams) ?: return false
        if (initResponse.error != null) return false

        // Send notifications/initialized
        sendNotification("notifications/initialized", null)

        isInitialized = true
        refreshTools()
        return true
    }

    suspend fun refreshTools(): List<ChatTool> {
        val response = sendRequest("tools/list", null) ?: return emptyList()
        val result = response.result?.let {
            runCatching { json.decodeFromJsonElement<McpToolsListResult>(it) }.getOrNull()
        } ?: return emptyList()

        val chatTools = result.tools.map { mcpTool ->
            McpChatTool(
                serverName = config.name,
                toolDef = mcpTool,
                client = this
            )
        }
        _tools.value = chatTools
        return chatTools
    }

    suspend fun callTool(name: String, argumentsJson: String): ToolExecutionResult {
        val argsElement = runCatching {
            json.parseToJsonElement(argumentsJson)
        }.getOrDefault(JsonObject(emptyMap()))

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive(name),
                "arguments" to argsElement
            )
        )

        val response = sendRequest("tools/call", params)
            ?: return ToolExecutionResult("Tool call failed: server timeout or disconnected.", isError = true)

        if (response.error != null) {
            return ToolExecutionResult("MCP error (${response.error.code}): ${response.error.message}", isError = true)
        }

        val callResult = response.result?.let {
            runCatching { json.decodeFromJsonElement<McpCallToolResult>(it) }.getOrNull()
        }

        if (callResult != null) {
            val textOutput = callResult.content.joinToString("\n") { it.text.orEmpty() }
            return ToolExecutionResult(
                output = textOutput.ifEmpty { "(no output)" },
                isError = callResult.isError
            )
        }

        return ToolExecutionResult(response.result?.toString() ?: "(no result)", isError = false)
    }

    suspend fun sendRequest(method: String, params: JsonElement?): JsonRpcResponse? {
        val reqId = requestMutex.withLock { ++requestId }
        val deferred = CompletableDeferred<JsonRpcResponse>()
        synchronized(pendingRequests) {
            pendingRequests[reqId] = deferred
        }

        val request = JsonRpcRequest(
            id = reqId,
            method = method,
            params = params
        )
        val text = json.encodeToString(JsonRpcRequest.serializer(), request)

        val sent = when (config.transportType) {
            McpTransportType.STDIO -> processBridge?.sendLine(text) ?: false
            McpTransportType.SSE -> {
                val endpoint = sseEndpointUrl ?: return null
                val client = httpClient ?: return null
                try {
                    val resp = client.post(endpoint) {
                        contentType(ContentType.Application.Json)
                        config.headers.forEach { (k, v) -> header(k, v) }
                        setBody(text)
                    }
                    resp.status.value in 200..299
                } catch (e: Exception) {
                    false
                }
            }
        }

        if (!sent) {
            synchronized(pendingRequests) { pendingRequests.remove(reqId) }
            return null
        }

        return withTimeoutOrNull(30_000) {
            deferred.await()
        } ?: run {
            synchronized(pendingRequests) { pendingRequests.remove(reqId) }
            null
        }
    }

    private suspend fun sendNotification(method: String, params: JsonElement?) {
        val request = JsonRpcRequest(
            id = null,
            method = method,
            params = params
        )
        val text = json.encodeToString(JsonRpcRequest.serializer(), request)

        when (config.transportType) {
            McpTransportType.STDIO -> processBridge?.sendLine(text)
            McpTransportType.SSE -> {
                val endpoint = sseEndpointUrl ?: return
                val client = httpClient ?: return
                runCatching {
                    client.post(endpoint) {
                        contentType(ContentType.Application.Json)
                        config.headers.forEach { (k, v) -> header(k, v) }
                        setBody(text)
                    }
                }
            }
        }
    }

    private fun handleIncomingLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        val response = runCatching {
            json.decodeFromString<JsonRpcResponse>(trimmed)
        }.getOrNull() ?: return

        val id = response.id ?: return
        val deferred = synchronized(pendingRequests) {
            pendingRequests.remove(id)
        }
        deferred?.complete(response)
    }

    private fun handleConnectionClosed(code: Int) {
        isInitialized = false
        synchronized(pendingRequests) {
            for ((_, deferred) in pendingRequests) {
                deferred.complete(
                    JsonRpcResponse(
                        error = JsonRpcError(-32000, "MCP server process exited with code $code")
                    )
                )
            }
            pendingRequests.clear()
        }
        _tools.value = emptyList()
    }

    suspend fun close() = mutex.withLock {
        isInitialized = false
        when (config.transportType) {
            McpTransportType.STDIO -> processBridge?.close()
            McpTransportType.SSE -> httpClient?.close()
        }
        processBridge = null
        httpClient = null
        _tools.value = emptyList()
    }
}

class McpChatTool(
    val serverName: String,
    val toolDef: McpToolDefinition,
    private val client: McpClient
) : ChatTool {
    override val name: String = "${serverName}_${toolDef.name}"
    override val description: String = toolDef.description ?: "MCP tool ${toolDef.name} from $serverName"
    override val parametersJson: String = toolDef.inputSchema.toString()

    override suspend fun execute(argumentsJson: String): ToolExecutionResult {
        return client.callTool(toolDef.name, argumentsJson)
    }
}
