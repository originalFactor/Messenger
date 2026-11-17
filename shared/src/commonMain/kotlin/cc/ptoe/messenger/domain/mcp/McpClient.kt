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

/** A protocol error is an actual reply, but does not prove that no side effect occurred. */
internal sealed interface McpToolCallOutcome {
    /** Retain the complete response so non-text blocks are not lost by the legacy text view. */
    data class Result(val result: ToolExecutionResult, val rawResult: JsonObject) : McpToolCallOutcome
    data class ProtocolError(val code: Int, val message: String) : McpToolCallOutcome
    data object NotDispatched : McpToolCallOutcome
    data class Unknown(val legacyResult: ToolExecutionResult, val legacyFailure: Exception? = null) : McpToolCallOutcome
}

private sealed interface McpPendingResponse {
    data class Reply(val response: JsonRpcResponse, val validProtocol: Boolean) : McpPendingResponse
    data class Closed(val code: Int) : McpPendingResponse
}

private sealed interface McpRequestOutcome {
    data class Reply(val response: JsonRpcResponse, val validProtocol: Boolean) : McpRequestOutcome
    data object NotDispatched : McpRequestOutcome
    data class Unknown(val closeCode: Int? = null, val legacyFailure: Exception? = null) : McpRequestOutcome
}

private fun validMcpBase64(value: String?): Boolean {
    if (value == null || value.length % 4 != 0) return false
    var padding = 0
    if (value.endsWith('=')) padding++
    if (value.endsWith("==")) padding++
    fun digit(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }
    val end = value.length - padding
    if ((0 until end).any { digit(value[it]) < 0 }) return false
    if (padding > 0 && end == 0) return false
    return when (padding) {
        1 -> (digit(value[end - 1]) and 3) == 0
        2 -> (digit(value[end - 1]) and 15) == 0
        else -> true
    }
}

private fun validMcpContent(content: JsonArray): Boolean = content.all { value ->
    val block = value as? JsonObject ?: return@all false
    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    when (block.string("type")) {
        "text" -> block.string("text") != null
        "image", "audio" -> validMcpBase64(block.string("data")) && !block.string("mimeType").isNullOrBlank()
        "resource_link" -> !block.string("name").isNullOrBlank() && !block.string("uri").isNullOrBlank()
        "resource" -> {
            val resource = block["resource"] as? JsonObject
            if (resource == null) false else {
                val hasText = resource.containsKey("text")
                val hasBlob = resource.containsKey("blob")
                !resource.string("uri").isNullOrBlank() && (hasText != hasBlob) &&
                    (!hasText || resource.string("text") != null) && (!hasBlob || validMcpBase64(resource.string("blob"))) &&
                    (resource["mimeType"] == null || resource.string("mimeType") != null)
            }
        }
        else -> false
    }
}

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

    private val pendingRequests = mutableMapOf<Long, CompletableDeferred<McpPendingResponse>>()
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

    suspend fun callTool(name: String, argumentsJson: String): ToolExecutionResult =
        when (val outcome = callToolOutcome(name, argumentsJson)) {
            is McpToolCallOutcome.Result -> outcome.result
            is McpToolCallOutcome.ProtocolError -> ToolExecutionResult("MCP error (${outcome.code}): ${outcome.message}", isError = true)
            McpToolCallOutcome.NotDispatched -> ToolExecutionResult("Tool call failed: server timeout or disconnected.", isError = true)
            is McpToolCallOutcome.Unknown -> outcome.legacyFailure?.let { throw it } ?: outcome.legacyResult
        }

    /** Cancellation after sending must be treated as an unknown outcome by the caller. */
    internal suspend fun callToolOutcome(name: String, argumentsJson: String): McpToolCallOutcome {
        val argsElement = runCatching {
            json.parseToJsonElement(argumentsJson)
        }.getOrDefault(JsonObject(emptyMap()))

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive(name),
                "arguments" to argsElement
            )
        )

        val outcome = when (val outcome = sendRequestOutcome("tools/call", params)) {
            is McpRequestOutcome.Reply -> outcome
            McpRequestOutcome.NotDispatched -> return McpToolCallOutcome.NotDispatched
            is McpRequestOutcome.Unknown -> return McpToolCallOutcome.Unknown(outcome.legacyToolResult(), outcome.legacyFailure)
        }
        val response = outcome.response

        if (response.error != null) {
            if (response.result != null || !outcome.validProtocol) return McpToolCallOutcome.Unknown(
                ToolExecutionResult("MCP error (${response.error.code}): ${response.error.message}", isError = true))
            return McpToolCallOutcome.ProtocolError(response.error.code, response.error.message)
        }

        val callResult = response.result?.let {
            runCatching { json.decodeFromJsonElement<McpCallToolResult>(it) }.getOrNull()
        }

        if (callResult != null) {
            val textOutput = callResult.content.joinToString("\n") { it.text.orEmpty() }
            val result = ToolExecutionResult(
                output = textOutput.ifEmpty { "(no output)" },
                isError = callResult.isError
            )
            // The serializer has defaults for absent content. Such a malformed reply
            // cannot prove the invocation completed even if legacy UI can display it.
            val rawResult = response.result as? JsonObject
            val content = rawResult?.get("content")
            return if (outcome.validProtocol && rawResult != null && content is JsonArray && validMcpContent(content) &&
                (!rawResult.containsKey("structuredContent") || rawResult["structuredContent"] is JsonObject)) McpToolCallOutcome.Result(result, rawResult)
            else McpToolCallOutcome.Unknown(result)
        }

        return McpToolCallOutcome.Unknown(ToolExecutionResult(response.result?.toString() ?: "(no result)", isError = false))
    }

    private fun McpRequestOutcome.Unknown.legacyToolResult() = if (closeCode != null)
        ToolExecutionResult("MCP error (-32000): MCP server process exited with code $closeCode", isError = true)
    else ToolExecutionResult("Tool call failed: server timeout or disconnected.", isError = true)

    suspend fun sendRequest(method: String, params: JsonElement?): JsonRpcResponse? =
        when (val outcome = sendRequestOutcome(method, params)) {
            is McpRequestOutcome.Reply -> outcome.response
            McpRequestOutcome.NotDispatched -> null
            is McpRequestOutcome.Unknown -> outcome.legacyFailure?.let { throw it }
                ?: outcome.closeCode?.let { JsonRpcResponse(error = JsonRpcError(-32000, "MCP server process exited with code $it")) }
        }

    private suspend fun sendRequestOutcome(method: String, params: JsonElement?): McpRequestOutcome {
        val reqId = requestMutex.withLock { ++requestId }
        val deferred = CompletableDeferred<McpPendingResponse>()
        val registered = synchronizedBlock(connectionLock) {
            if (!connectionAlive) false else {
                synchronizedBlock(pendingRequests) { pendingRequests[reqId] = deferred }
                true
            }
        }
        if (!registered) return McpRequestOutcome.NotDispatched

        try {
            val request = JsonRpcRequest(id = reqId, method = method, params = params)
            val text = json.encodeToString(JsonRpcRequest.serializer(), request)
            var started = false
            var sendFailure: Exception? = null
            val sent = withAdmission(false) {
                when (config.transportType) {
                    McpTransportType.STDIO -> sendMutex.withLock {
                        currentCoroutineContext().ensureActive()
                        val bridge = processBridge ?: return@withLock false
                        started = true
                        try { bridge.sendLine(text) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { sendFailure = failure; false }
                    }
                    McpTransportType.SSE -> {
                        currentCoroutineContext().ensureActive()
                        val endpoint = sseEndpointUrl ?: return@withAdmission false
                        val client = httpClient ?: return@withAdmission false
                        try {
                            started = true
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
            if (!sent) return if (started) McpRequestOutcome.Unknown(legacyFailure = sendFailure) else McpRequestOutcome.NotDispatched
            return when (val response = withTimeoutOrNull(30_000) { deferred.await() }) {
                is McpPendingResponse.Reply -> McpRequestOutcome.Reply(response.response, response.validProtocol)
                is McpPendingResponse.Closed -> McpRequestOutcome.Unknown(response.code)
                null -> McpRequestOutcome.Unknown()
            }
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
        val envelope = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
        val version = envelope?.get("jsonrpc") as? JsonPrimitive
        val hasResult = envelope?.containsKey("result") == true
        val hasError = envelope?.containsKey("error") == true
        val validEnvelope = envelope != null && version?.isString == true && version.content == "2.0" &&
            (hasResult != hasError) && !envelope.containsKey("method") && !envelope.containsKey("params") &&
            (!hasError || envelope["error"] is JsonObject)
        deferred?.complete(McpPendingResponse.Reply(response, validEnvelope))
    }

    private fun handleConnectionClosed(code: Int, generation: Long = connectionGeneration) {
        synchronizedBlock(connectionLock) {
            if (generation == connectionGeneration) {
                connectionAlive = false
                isInitialized = false
                _tools.value = emptyList()
                synchronizedBlock(pendingRequests) {
                    for ((_, deferred) in pendingRequests) {
                        deferred.complete(McpPendingResponse.Closed(code))
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
