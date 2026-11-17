/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

private const val SKILLS = "messenger_skills"
private const val AGENTS = "messenger_agents"
private const val IMAGE = "messenger_generate_image"
private val CORE_TOOLS = setOf("messenger_learning", "messenger_artifacts", IMAGE)
private val SUPPORTED = CORE_TOOLS + SKILLS + AGENTS

internal data class AcpGatewayTool(val name: String, val description: String, val schema: JsonObject)

/** Must recheck live feature opt-ins and turn authorization again immediately before any side effect. */
internal interface AcpGatewayBackend {
    suspend fun liveTools(): List<AcpGatewayTool>
    suspend fun execute(turnId: String, name: String, arguments: JsonObject): ToolExecutionResult
}

/** Transport-independent JSON-RPC core; the caller must bind a private loopback transport. */
internal class AcpGatewayCore(
    private val backend: AcpGatewayBackend,
    allowDelegationAtStartup: Boolean,
    allowedCoreToolsAtStartup: Set<String>,
) {
    private class Turn(val id: String) {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val calls = mutableMapOf<String, Deferred<ToolExecutionResult>>()
        var imageFailed = false
        val imageGate = Mutex()
    }
    private val allowDelegation = allowDelegationAtStartup
    private val coreScope = allowedCoreToolsAtStartup.toSet().also { require(it.all { name -> name in CORE_TOOLS }) }
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val gate = Mutex()
    private var turn: Turn? = null
    private var closing = false
    private var closed = false
    /** Supply only to the owning worker over its private configuration channel. */
    fun capability(): String = secret
    override fun toString() = "AcpGatewayCore(capability=redacted)"

    suspend fun beginTurn(id: String) = gate.withLock {
        require(id.isNotBlank() && id.length <= 256)
        check(!closed && !closing && turn == null) { "ACP worker already has an active turn" }
        turn = Turn(id)
    }
    /** Cancels calls and waits for actual completion before allowing another turn. */
    suspend fun endTurn(id: String) = withContext(NonCancellable) {
        val prior = gate.withLock {
            val current = turn ?: return@withContext
            check(current.id == id) { "ACP turn changed" }
            turn = null; closing = true; current
        }
        try { prior.job.cancelAndJoin() }
        finally { gate.withLock { closing = false } }
    }
    suspend fun close() = withContext(NonCancellable) {
        val prior = gate.withLock { closed = true; closing = true; val current = turn; turn = null; current }
        try { prior?.job?.cancelAndJoin() }
        finally { gate.withLock { closing = false } }
    }
    private suspend fun authenticate(candidate: String) {
        if (!MessageDigest.isEqual(secret.toByteArray(Charsets.US_ASCII), candidate.toByteArray(Charsets.US_ASCII))) throw SecurityException("Unknown ACP capability")
        gate.withLock { check(!closed) { "ACP gateway closed" } }
    }
    private suspend fun visibleTools(): List<AcpGatewayTool> {
        val live = backend.liveTools()
        require(live.size <= 128 && live.map { it.name }.distinct().size == live.size && live.all { it.name in SUPPORTED }) { "Invalid ACP tool catalog" }
        val skill = AcpGatewayTool(SKILLS, "Read an enabled Agent Skill's bundled UTF-8 file.", Json.parseToJsonElement(
            """{"type":"object","properties":{"name":{"type":"string"},"path":{"type":"string"}},"required":["name"],"additionalProperties":false}""").jsonObject)
        return listOf(skill) + live.filter { it.name != SKILLS && (it.name in coreScope || (it.name == AGENTS && allowDelegation)) }
    }

    /** Null denotes a JSON-RPC notification; bad capability must be mapped to HTTP 404. */
    suspend fun request(capability: String, request: JsonObject): JsonObject? {
        authenticate(capability)
        require(request.toString().encodeToByteArray().size <= 1024 * 1024) { "ACP gateway request too large" }
        require((request["jsonrpc"] as? JsonPrimitive)?.contentOrNull == "2.0") { "Invalid JSON-RPC version" }
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull ?: error("Missing JSON-RPC method")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        if (method == "notifications/cancelled") {
            val requestId = params["requestId"]?.toString() ?: return null
            gate.withLock { turn?.calls?.get(requestId)?.cancel() }
            return null
        }
        val id = request["id"]?.takeUnless { it is JsonNull } ?: return null
        require(id is JsonPrimitive && (id.isString || id.longOrNull != null)) { "Invalid JSON-RPC request ID" }
        fun success(value: JsonElement) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", value) }
        fun error(code: Int, text: String) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject { put("code", code); put("message", text) }) }
        return when (method) {
            "initialize" -> success(buildJsonObject {
                val version = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
                put("protocolVersion", if (version in setOf("2024-11-05", "2025-03-26", "2025-06-18")) version!! else "2025-03-26")
                put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
                put("serverInfo", buildJsonObject { put("name", "Messenger Tools"); put("version", "1") })
            })
            "ping" -> success(JsonObject(emptyMap()))
            "tools/list" -> success(buildJsonObject { put("tools", JsonArray(visibleTools().map { tool -> buildJsonObject {
                put("name", tool.name); put("description", tool.description); put("inputSchema", tool.schema)
            } })) })
            "tools/call" -> {
                val name = (params["name"] as? JsonPrimitive)?.contentOrNull ?: return error(-32602, "Missing tool name")
                if (visibleTools().none { it.name == name }) return error(-32601, "Method not found")
                val arguments = params["arguments"] as? JsonObject ?: if (params["arguments"] == null) JsonObject(emptyMap()) else return error(-32602, "Invalid tool arguments")
                val result = execute(id.toString(), name, arguments)
                success(buildJsonObject {
                    put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", result.output) })))
                    put("isError", result.isError)
                    result.structuredContent?.let { put("structuredContent", it) }
                })
            }
            else -> error(-32601, "Method not found")
        }
    }

    private suspend fun execute(id: String, name: String, args: JsonObject): ToolExecutionResult {
        var selected: Pair<Turn, Deferred<ToolExecutionResult>>? = null
        var failed = true
        try {
            withContext(NonCancellable) {
                selected = gate.withLock {
                    val owner = turn ?: return@withLock null
                    if (owner.calls.containsKey(id) || owner.calls.size >= 32 || (name == IMAGE && owner.imageFailed)) return@withLock null
                    val operation = owner.scope.async(start = CoroutineStart.LAZY) {
                        suspend fun invoke(): ToolExecutionResult {
                            if (visibleTools().none { it.name == name }) return ToolExecutionResult("Tool permission was revoked", isError = true)
                            return backend.execute(owner.id, name, args)
                        }
                        if (name != IMAGE) invoke() else owner.imageGate.withLock imageGate@ {
                            if (gate.withLock { owner.imageFailed || turn !== owner }) return@imageGate ToolExecutionResult(
                                "A previous image generation failed; start a new user turn before trying again.", isError = true)
                            var success = false
                            try { invoke().also { success = !it.isError } }
                            finally {
                                if (!success) withContext(NonCancellable) { gate.withLock { owner.imageFailed = true } }
                            }
                        }
                    }
                    owner.calls[id] = operation
                    owner to operation
                }
            }
            val (_, operation) = selected ?: return ToolExecutionResult("ACP turn is unavailable, the request is duplicated, or a previous image generation failed", isError = true)
            currentCoroutineContext().ensureActive()
            operation.start()
            return operation.await().also { failed = it.isError }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            return ToolExecutionResult("Tool call cancelled", isError = true)
        } catch (failure: Exception) {
            return ToolExecutionResult(failure.message ?: "Tool call failed", isError = true)
        } finally {
            val pair = selected
            if (pair != null) withContext(NonCancellable) {
                val (owner, operation) = pair
                operation.cancelAndJoin()
                gate.withLock {
                    if (name == IMAGE && failed) owner.imageFailed = true
                    if (owner.calls[id] === operation) owner.calls.remove(id)
                }
            }
        }
    }
}
