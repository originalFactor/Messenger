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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import cc.ptoe.messenger.data.util.synchronizedBlock
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
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

import cc.ptoe.messenger.data.util.ioDispatcher

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
    private val closeMutex = Mutex()
    private val sendMutex = Mutex()
    private val requestMutex = Mutex()
    private val scope = CoroutineScope(ioDispatcher + Job())
    private var requestId = 0L

    private val pendingRequests = mutableMapOf<Long, CompletableDeferred<JsonRpcResponse>>()
    private val _tools = MutableStateFlow<List<ChatTool>>(emptyList())
    val tools: StateFlow<List<ChatTool>> = _tools.asStateFlow()

    private var processBridge: McpProcessBridge? = null
    private var sseEndpointUrl: String? = null
    private var httpClient: HttpClient? = null
    private var isInitialized = false
    private val connectionLock = Any()
    private var connectionGeneration = 0L
    private var connectionAlive = false
    private var connectionAttempted = false
    internal var beforeToolPublication: suspend () -> Unit = {}
    private var closed = false
    private var closing = false
    private val admissionLock = Any()
    private val admitted = mutableSetOf<Job>()
    private var stdioStarting = false

    /** Track actual coroutine completion so close can cancel and join blocked transports. */
    private suspend fun <T> withAdmission(unavailable: T, block: suspend () -> T): T = try {
        coroutineScope {
            val job = checkNotNull(currentCoroutineContext()[Job])
            val accepted = synchronizedBlock(admissionLock) {
                if (closing || closed) false else { admitted.add(job); true }
            }
            if (!accepted) return@coroutineScope unavailable
            job.invokeOnCompletion { synchronizedBlock(admissionLock) { admitted.remove(job) } }
            block()
        }
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        if (synchronizedBlock(admissionLock) { closing || closed }) unavailable else throw cancelled
    }

    suspend fun connect(): Boolean = withAdmission(false) { mutex.withLock {
        val generation = synchronizedBlock(connectionLock) {
            if (isInitialized && connectionAlive) return@withLock true
            // A failed connection can still own a process or SSE reader. Only a new
            // client, created after this one is closed, may start another transport.
            if (connectionAttempted) return@withLock false
            connectionAttempted = true
            connectionAlive = true
            ++connectionGeneration
        }
        try {
            when (config.transportType) {
                McpTransportType.STDIO -> connectStdio(generation)
                McpTransportType.SSE -> connectSse(generation)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    } }

    private suspend fun connectStdio(generation: Long): Boolean {
        val bridge = bridgeFactory()
        synchronizedBlock(admissionLock) {
            if (closing || closed) throw CancellationException("MCP client is closing")
            processBridge = bridge
            stdioStarting = true
        }
        val fullCommand = if (config.args.isNotEmpty()) {
            "${config.command} ${config.args.joinToString(" ")}"
        } else {
            config.command
        }

        val started = try {
            bridge.startProcess(
                command = fullCommand,
                env = config.env,
                onLine = { line -> handleIncomingLine(line) },
                onError = { /* stderr log */ },
                onClose = { code -> handleConnectionClosed(code, generation) }
            )
        } finally {
            synchronizedBlock(admissionLock) { stdioStarting = false }
        }
        if (!started) return false

        return initializeHandshake(generation)
    }

    private suspend fun connectSse(generation: Long): Boolean {
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
            } finally {
                endpointDeferred.completeExceptionally(IllegalStateException("MCP SSE connection ended"))
                handleConnectionClosed(-1, generation)
            }
        }

        val endpoint = withTimeoutOrNull(10_000) { endpointDeferred.await() } ?: return false
        sseEndpointUrl = endpoint
        return initializeHandshake(generation)
    }

    private suspend fun initializeHandshake(generation: Long): Boolean {
        val initParams = json.encodeToJsonElement(McpInitializeParams())
        val initResponse = sendRequest("initialize", initParams) ?: return false
        if (initResponse.error != null) return false

        // Send notifications/initialized
        sendNotification("notifications/initialized", null)

        val initialized = synchronizedBlock(connectionLock) {
            if (connectionGeneration != generation || !connectionAlive) false
            else { isInitialized = true; true }
        }
        if (!initialized) return false
        refreshTools()
        return synchronizedBlock(connectionLock) { connectionGeneration == generation && connectionAlive }
    }

    suspend fun refreshTools(): List<ChatTool> {
        val generation = synchronizedBlock(connectionLock) { connectionGeneration }
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
        beforeToolPublication()
        return synchronizedBlock(connectionLock) {
            if (generation != connectionGeneration || !connectionAlive) emptyList()
            else { _tools.value = chatTools; chatTools }
        }
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
        val registered = synchronizedBlock(connectionLock) {
            if (!connectionAlive) false else {
                synchronizedBlock(pendingRequests) { pendingRequests[reqId] = deferred }
                true
            }
        }
        if (!registered) return null

        try {
            val request = JsonRpcRequest(id = reqId, method = method, params = params)
            val text = json.encodeToString(JsonRpcRequest.serializer(), request)
            val sent = withAdmission(false) {
                when (config.transportType) {
                    McpTransportType.STDIO -> sendMutex.withLock {
                        currentCoroutineContext().ensureActive()
                        processBridge?.sendLine(text) ?: false
                    }
                    McpTransportType.SSE -> {
                        currentCoroutineContext().ensureActive()
                        val endpoint = sseEndpointUrl ?: return@withAdmission false
                        val client = httpClient ?: return@withAdmission false
                        try {
                            val resp = client.post(endpoint) {
                                contentType(ContentType.Application.Json)
                                config.headers.forEach { (k, v) -> header(k, v) }
                                setBody(text)
                            }
                            resp.status.value in 200..299
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            false
                        }
                    }
                }
            }
            if (!sent) return null
            return withTimeoutOrNull(30_000) { deferred.await() }
        } finally { synchronizedBlock(pendingRequests) { pendingRequests.remove(reqId) } }
    }

    private suspend fun sendNotification(method: String, params: JsonElement?): Unit = withAdmission(Unit) {
        val request = JsonRpcRequest(
            id = null,
            method = method,
            params = params
        )
        val text = json.encodeToString(JsonRpcRequest.serializer(), request)

        when (config.transportType) {
            McpTransportType.STDIO -> sendMutex.withLock {
                currentCoroutineContext().ensureActive()
                processBridge?.sendLine(text)
            }
            McpTransportType.SSE -> {
                val endpoint = sseEndpointUrl ?: return@withAdmission
                val client = httpClient ?: return@withAdmission
                try {
                    client.post(endpoint) {
                        contentType(ContentType.Application.Json)
                        config.headers.forEach { (k, v) -> header(k, v) }
                        setBody(text)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
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
        // The callback can arrive on a transport thread, so it uses the same
        // synchronous lock as request registration and cancellation.
        val deferred = synchronizedBlock(pendingRequests) { pendingRequests.remove(id) }
        deferred?.complete(response)
    }

    private fun handleConnectionClosed(code: Int, generation: Long = connectionGeneration) {
        synchronizedBlock(connectionLock) {
            if (generation == connectionGeneration) {
                connectionAlive = false
                isInitialized = false
                _tools.value = emptyList()
                synchronizedBlock(pendingRequests) {
                    for ((_, deferred) in pendingRequests) {
                        deferred.complete(JsonRpcResponse(error = JsonRpcError(-32000, "MCP server process exited with code $code")))
                    }
                    pendingRequests.clear()
                }
            }
        }
    }

    suspend fun close(): Unit = withContext(NonCancellable) { closeMutex.withLock {
        val (operations, earlyBridge, wasStarting) = synchronizedBlock(admissionLock) {
            closing = true
            Triple(admitted.toList(), processBridge, stdioStarting)
        }
        operations.forEach { it.cancel() }
        val job = scope.coroutineContext[Job]
        job?.cancel()
        // A synchronous Binder/pipe write may ignore coroutine cancellation. Stop the
        // process before waiting for the send lock so the blocked write can finish.
        if (config.transportType == McpTransportType.STDIO) earlyBridge?.close()
        mutex.withLock {
            sendMutex.withLock {
                if (!closed) {
                    when (config.transportType) {
                        // A process start that raced the early stop can complete later.
                        McpTransportType.STDIO -> if (wasStarting || processBridge !== earlyBridge) processBridge?.close()
                        McpTransportType.SSE -> httpClient?.close()
                    }
                    processBridge = null
                    httpClient = null
                    handleConnectionClosed(-1)
                    synchronizedBlock(admissionLock) { closed = true }
                }
            }
        }
        operations.joinAll()
        job?.join()
    } }
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
