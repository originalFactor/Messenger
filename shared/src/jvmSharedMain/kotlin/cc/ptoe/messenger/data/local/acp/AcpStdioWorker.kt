/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** The implementation owns one loopback server and must close its streams and all active calls. */
internal interface AcpGatewayEndpoint {
    val url: String
    suspend fun closeAndAwait()
}
internal fun interface AcpGatewayEndpointFactory {
    suspend fun open(gateway: AcpGatewayCore, sse: Boolean): AcpGatewayEndpoint
}
internal interface AcpStdioEvents {
    suspend fun restoreStarted(replay: Boolean) {}
    suspend fun restoreFinished(success: Boolean) {}
    suspend fun update(turnId: String?, update: JsonObject) {}
    suspend fun permission(turnId: String, prompt: AcpPermissionPrompt?) {}
}

/** ACP v1 stdio client for restoring an existing local session. No shell or file callback is exposed. */
internal class AcpStdioWorker(
    private val transport: AcpLineTransport,
    private val endpoints: AcpGatewayEndpointFactory,
    private val discovery: suspend () -> String?,
    private val events: AcpStdioEvents,
) : AcpWorkerProcess, AcpNewSessionBinding {
    private data class Active(val turnId: String, val broker: AcpPermissionBroker, val response: Deferred<JsonElement>)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val gate = Mutex()
    private var session: String? = null
    private var startupBinding: AcpWorkerBinding? = null
    private var peer: AcpJsonRpcPeer? = null
    private var endpoint: AcpGatewayEndpoint? = null
    private var active: Active? = null
    private var gatewayAvailable = false

    override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore): AcpWorkerReady {
        check(startupBinding == null) { "ACP worker already started" }
        startupBinding = binding
        val connection = AcpJsonRpcPeer(transport, scope, ::notification, ::incomingRequest)
        peer = connection
        connection.start(binding, configuration)
        val initialized = connection.request("initialize", buildJsonObject {
            put("protocolVersion", 1)
            put("clientCapabilities", buildJsonObject { put("fs", buildJsonObject { put("readTextFile", false); put("writeTextFile", false) }); put("terminal", false) })
            put("clientInfo", buildJsonObject { put("name", "Messenger"); put("version", "1") })
        }) as? JsonObject ?: error("Invalid ACP initialization")
        check((initialized["protocolVersion"] as? JsonPrimitive)?.intOrNull == 1) { "Unsupported ACP protocol version" }
        val capabilities = initialized["agentCapabilities"] as? JsonObject ?: JsonObject(emptyMap())
        val mcp = capabilities["mcpCapabilities"] as? JsonObject ?: JsonObject(emptyMap())
        val sse = (mcp["sse"] as? JsonPrimitive)?.booleanOrNull == true
        val http = (mcp["http"] as? JsonPrimitive)?.booleanOrNull == true
        val restore = (capabilities["sessionCapabilities"] as? JsonObject)?.get("resume") is JsonObject
        val load = (capabilities["loadSession"] as? JsonPrimitive)?.booleanOrNull == true
        val existing = binding.agentSessionId.isNotBlank()
        check(!existing || restore || load) { "This ACP agent cannot restore sessions. Create a new session." }
        val servers = configuration.mcpServers.toMutableList()
        val gatewayName = if (configuration.allowDelegation) "Messenger Agents" else "Messenger Skills"
        if (sse || http) require(servers.none { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull == gatewayName }) { "Configured MCP server conflicts with the gateway name" }
        require(servers.all { server ->
            val type = ((server as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
            when (type) { null, "stdio" -> true; "sse" -> sse; "http" -> http; else -> false }
        }) { "ACP agent does not support the configured SSE MCP server" }
        if (sse || http) {
            // Publish the endpoint into worker state before cancellation can return from setup.
            val opened = withContext(NonCancellable) { endpoints.open(gateway, sse).also { endpoint = it } }
            currentCoroutineContext().ensureActive()
            servers += buildJsonObject { put("name", gatewayName); put("type", if (sse) "sse" else "http"); put("url", opened.url); put("headers", JsonArray(emptyList())) }
            gatewayAvailable = true
        }
        if (existing) session = binding.agentSessionId
        val replay = existing && !restore && load
        events.restoreStarted(replay)
        val result = try {
            val response = connection.request(if (!existing) "session/new" else if (restore) "session/resume" else "session/load", buildJsonObject {
                if (existing) put("sessionId", binding.agentSessionId)
                put("cwd", binding.workspace); put("mcpServers", JsonArray(servers))
            }) as? JsonObject ?: error("Invalid ACP session response")
            events.restoreFinished(true)
            response
        } catch (failure: Throwable) {
            withContext(NonCancellable) { events.restoreFinished(false) }
            throw failure
        }
        val sessionId = if (existing) binding.agentSessionId else (result["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: error("ACP agent returned no session ID")
        require(sessionId.isNotBlank() && sessionId.length <= 1024 && sessionId.none { it.isISOControl() }) { "Invalid ACP session ID" }
        session = sessionId
        return AcpWorkerReady(gatewayAvailable, sessionId)
    }

    override fun bindCreatedSession(binding: AcpWorkerBinding) {
        val initial = checkNotNull(startupBinding) { "ACP worker was not started" }
        check(initial.agentSessionId.isEmpty() && binding.copy(agentSessionId = "") == initial && binding.agentSessionId == session) {
            "ACP created session binding changed"
        }
        startupBinding = binding
    }

    override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker): JsonObject {
        val connection = checkNotNull(peer) { "ACP worker is not started" }
        val sessionId = checkNotNull(session) { "ACP session is unavailable" }
        val catalog = discovery()
        check(catalog == null || gatewayAvailable) { "Skills require ACP MCP SSE or HTTP support" }
        val prompt = if (catalog == null) content else JsonArray(content + buildJsonObject { put("type", "text"); put("text", catalog) })
        val response = scope.async(start = CoroutineStart.LAZY) {
            connection.request("session/prompt", buildJsonObject { put("sessionId", sessionId); put("prompt", prompt) })
        }
        val state = Active(turnId, permissions, response)
        var installed = false
        try {
            withContext(NonCancellable) {
                gate.withLock { check(active == null) { "ACP prompt already active" }; active = state; installed = true }
                response.start()
            }
            currentCoroutineContext().ensureActive()
            val result = response.await() as? JsonObject ?: error("Invalid ACP prompt response")
            val reason = (result["stopReason"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("Missing ACP stop reason")
            return if (reason == "cancelled") buildJsonObject { put("type", "Cancelled") }
                else buildJsonObject { put("type", "Finished"); put("stopReason", reason) }
        } finally {
            // An interrupted request stays reachable until cancelAndConfirm or stopAndAwait.
            if (installed && response.isCompleted) withContext(NonCancellable) { gate.withLock { if (active === state) active = null } }
        }
    }

    override suspend fun cancelAndConfirm(): Boolean {
        val state = gate.withLock { active } ?: return true
        val connection = checkNotNull(peer)
        val sessionId = checkNotNull(session)
        connection.notify("session/cancel", buildJsonObject { put("sessionId", sessionId) })
        return try {
            val response = state.response.await() as? JsonObject
            val acknowledged = (response?.get("stopReason") as? JsonPrimitive)?.contentOrNull != null
            if (acknowledged) gate.withLock { if (active === state) active = null }
            acknowledged
        } catch (_: Exception) { false }
    }

    private suspend fun notification(method: String, params: JsonObject) {
        if (method != "session/update") return
        check((params["sessionId"] as? JsonPrimitive)?.contentOrNull == session) { "ACP update belongs to another session" }
        val update = params["update"] as? JsonObject ?: error("Invalid ACP session update")
        val turnId = gate.withLock { active?.turnId }
        events.update(turnId, update)
    }

    private suspend fun incomingRequest(method: String, params: JsonObject): JsonElement {
        if (method != "session/request_permission") error("Unsupported ACP client request")
        check((params["sessionId"] as? JsonPrimitive)?.contentOrNull == session) { "Permission belongs to another ACP session" }
        val state = gate.withLock { active } ?: return cancelledPermission()
        val options = (params["options"] as? JsonArray)?.map { raw ->
            val option = raw as? JsonObject ?: error("Invalid ACP permission option")
            AcpPermissionOption((option["optionId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("Missing option ID"),
                (option["name"] as? JsonPrimitive)?.contentOrNull ?: "", (option["kind"] as? JsonPrimitive)?.contentOrNull ?: "")
        } ?: error("Missing ACP permission options")
        val tool = params["toolCall"] as? JsonObject ?: JsonObject(emptyMap())
        val choice = state.broker.request(state.turnId, options, (tool["title"] as? JsonPrimitive)?.contentOrNull ?: "", tool["rawInput"]?.toString() ?: "") {
            events.permission(state.turnId, it)
        }
        return if (choice == null) cancelledPermission() else buildJsonObject { put("outcome", buildJsonObject { put("outcome", "selected"); put("optionId", choice) }) }
    }
    private fun cancelledPermission() = buildJsonObject { put("outcome", buildJsonObject { put("outcome", "cancelled") }) }

    override suspend fun stopAndAwait(): Unit = withContext(NonCancellable) {
        gate.withLock { active?.broker }?.close()
        var failure: Throwable? = null
        try { endpoint?.closeAndAwait() } catch (error: Throwable) { failure = error }
        try { peer?.closeAndAwait() ?: transport.stopAndAwait() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        }
        job.cancelAndJoin()
        gate.withLock { active = null }
        failure?.let { throw it }
        Unit
    }
}
