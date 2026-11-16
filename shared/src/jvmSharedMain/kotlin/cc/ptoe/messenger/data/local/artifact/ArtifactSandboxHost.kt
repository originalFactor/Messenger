/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.artifact

import cc.ptoe.messenger.data.local.asset.CoreAssetService
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/** Immutable package for exactly one authorized artifact revision and one host instance. */
internal data class ArtifactSandboxPackage(val files: Map<String, String>, val entry: String, val stateJson: String)

/**
 * Unconnected boundary for a trusted WebView host. The caller must create a fresh host instance
 * for each binding, route messages from only that instance to it, and close both together. A
 * message never selects an artifact or receives an execution capability or native credential.
 */
internal class ArtifactSandboxHost(private val executions: ArtifactExecutionService, private val assets: CoreAssetService) {
    suspend fun open(id: String): ArtifactSandboxBinding {
        val session = executions.open(id)
        try {
            val (revision, _) = session.packageSnapshot()
            val files = revision.files.toMutableMap()
            var assetBytes = 0L
            for ((path, assetId) in revision.assets) {
                val metadata = assets.metadata(assetId)
                require(metadata.mimeType in supportedMime) { "Unsupported sandbox asset type" }
                val bytes = session.readAsset(path)
                assetBytes = Math.addExact(assetBytes, bytes.size.toLong())
                require(assetBytes <= 48L * 1024 * 1024) { "Sandbox asset package exceeds limit" }
                files[path] = "data:${metadata.mimeType};base64,${Base64.getEncoder().encodeToString(bytes)}"
            }
            // Recheck revision authorization after all asset reads before handing out a package.
            val (_, currentState) = session.packageSnapshot()
            return ArtifactSandboxBinding(session, ArtifactSandboxPackage(files.toMap(), revision.entry, currentState.toString()), revision.stateVersion)
        } catch (failure: Throwable) {
            try { withContext(NonCancellable) { session.close() } } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private companion object {
        val supportedMime = setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/svg+xml",
            "audio/mpeg", "audio/wav", "video/mp4", "font/woff2")
    }
}

/** Owned by one trusted host instance; close cancels and joins all admitted messages. */
internal class ArtifactSandboxBinding internal constructor(
    private val execution: ArtifactExecutionSession,
    val sandboxPackage: ArtifactSandboxPackage,
    private val stateVersion: Long,
) {
    private val lock = Any()
    private val closing = Mutex()
    private var closed = false
    private val active = mutableMapOf<String, Deferred<JsonElement>>()
    private val json = Json

    @Serializable
    private data class FetchInput(val url: String, val method: String = "GET", val body: String? = null, val contentType: String? = null)

    /** [reply] belongs to this host instance and must itself reject delivery after native teardown. */
    suspend fun dispatch(raw: String, reply: (String) -> Unit) = supervisorScope {
        require(raw.encodeToByteArray().size <= 1024 * 1024) { "Sandbox message exceeds limit" }
        val request = json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid sandbox message")
        require(request.keys == setOf("id", "method", "params")) { "Invalid sandbox message fields" }
        val id = (request["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        require(id != null && id.isNotEmpty() && id.encodeToByteArray().size <= 128 && id.none(Char::isISOControl)) { "Invalid sandbox message id" }
        val method = (request["method"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("Invalid sandbox method")
        val params = request["params"] as? JsonObject ?: error("Invalid sandbox parameters")
        val task = async(start = CoroutineStart.LAZY) { execute(method, params) }
        synchronized(lock) {
            check(!closed) { "Sandbox is closed" }
            check(active.size < 16 && id !in active) { "Sandbox request limit exceeded or duplicate id" }
            active[id] = task
            // The caller can be cancelled before a child finishes non-cancellable cleanup.
            // Keep it registered until the child itself has actually stopped.
            task.invokeOnCompletion { synchronized(lock) { if (active[id] === task) active.remove(id) } }
        }
        val answer = try {
            task.start()
            buildJsonObject { put("id", id); put("data", task.await()) }.toString()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { buildJsonObject { put("id", id); put("error", "Application request failed") }.toString() }
        synchronized(lock) { if (!closed) reply(answer) }
    }

    private suspend fun execute(method: String, params: JsonObject): JsonElement = when (method) {
        "getState" -> { require(params.isEmpty()) { "Unexpected state parameters" }; execution.getState() }
        "setState" -> {
            require(params.keys == setOf("state")) { "Invalid state parameters" }
            val saved = execution.setState(params.getValue("state"))
            buildJsonObject { put("state", saved); put("stateVersion", stateVersion) }
        }
        "fetch" -> {
            val request = json.decodeFromJsonElement<FetchInput>(params)
            val response = execution.fetch(ArtifactFetchRequest(request.url, request.method, request.body, request.contentType))
            buildJsonObject { put("status", response.status); put("body", response.body) }
        }
        else -> error("Unsupported sandbox operation")
    }

    /** Synchronously prevents further admission, so a native teardown cannot start new work. */
    fun stop() {
        val tasks = synchronized(lock) { closed = true; active.values.toList() }
        tasks.forEach { it.cancel() }
    }

    /** The host must stop native callbacks too; this only owns messages admitted to this binding. */
    suspend fun close() = withContext(NonCancellable) {
        closing.withLock {
            stop()
            val tasks = synchronized(lock) { active.values.toList() }
            try { execution.close() } finally { tasks.joinAll() }
        }
    }
}
