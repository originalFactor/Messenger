/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.local.artifact.ArtifactDraft
import cc.ptoe.messenger.data.local.artifact.RoomArtifactService
import cc.ptoe.messenger.data.local.asset.CoreAssetOwner
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.image.ImageProfile
import cc.ptoe.messenger.data.local.image.ImageRequest
import cc.ptoe.messenger.data.local.image.RoomImageJobOwner
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Unconnected trusted-UI media data boundary. All services must share this Room owner and its
 * asset gate. Sandbox execution requires a separate identity-bound lifecycle host. Image jobs
 * require a registered process owner; they fail explicitly when none is supplied.
 */
internal class KotlinRoomMediaBridge(
    private val delegate: CoreBridge,
    private val db: LocalDatabase,
    private val assets: CoreAssetService,
    private val artifacts: RoomArtifactService,
    private val imageOwner: RoomImageJobOwner? = null,
    private val now: () -> Long,
) : CoreBridge by delegate {
    private val json = Json { encodeDefaults = true }

    @Serializable
    private data class Submission(
        val id: String? = null,
        val title: String,
        val description: String = "",
        val files: Map<String, String>,
        val assets: Map<String, String> = emptyMap(),
        val entry: String = "index.html",
        val expectedRevision: Long = 0,
        val stateVersion: Long = 0,
        val requestedDomains: List<String> = emptyList(),
    )

    override suspend fun learningAsset(id: String): ByteArray = assets.read(id)

    override suspend fun hasActiveTasks(): Boolean = imageOwner?.hasActiveJobs() == true || delegate.hasActiveTasks()

    override suspend fun learningRequest(method: String, path: String, body: String?): String {
        if (!(path == "/assets" || path.startsWith("/assets/") || path == "/artifacts" ||
                path.startsWith("/artifacts/") || path == "/image-generation" || path.startsWith("/image-generation/"))) {
            return delegate.learningRequest(method, path, body)
        }
        require(body == null || body.encodeToByteArray().size <= 5 * 1024 * 1024) { "Media request exceeds size limit" }
        val parts = path.split('/')
        fun input(): String = requireNotNull(body) { "Media request body is required" }
        fun noBody() { require(body == null) { "Unexpected media request body" } }
        fun flag(): Boolean {
            val obj = json.parseToJsonElement(input()) as? JsonObject ?: error("Settings must be an object")
            val value = obj["enabled"] as? JsonPrimitive ?: error("Enabled boolean required")
            return value.takeUnless { it.isString }?.booleanOrNull ?: error("Enabled boolean required")
        }
        fun enabled(value: Boolean) = buildJsonObject { put("enabled", value) }.toString()

        return when {
            method == "GET" && path == "/artifacts" -> {
                noBody()
                val items = artifacts.list()
                buildJsonObject { put("items", buildJsonArray { items.forEach { artifact ->
                    add(buildJsonObject {
                        put("id", artifact.id); put("title", artifact.title); put("description", artifact.description)
                        put("revision", artifact.revisions.last().revision); put("updatedAt", artifact.updatedAt)
                    })
                } }) }.toString()
            }
            method == "GET" && path == "/artifacts/settings" -> { noBody(); enabled(db.rows().getValue("artifact_tools_enabled")?.value == "true") }
            method == "PUT" && path == "/artifacts/settings" -> {
                val value = flag(); db.rows().upsertValues(listOf(KeyValueRow("artifact_tools_enabled", value.toString()))); enabled(value)
            }
            method == "GET" && parts.size == 3 && parts[1] == "artifacts" -> { noBody(); json.encodeToString(artifacts.get(parts[2])) }
            method == "POST" && path == "/artifacts" -> {
                val request = json.decodeFromString<Submission>(input())
                require(request.id == null) { "New artifact must omit id" }
                json.encodeToString(artifacts.submit(request.draft(null), now()))
            }
            method == "PUT" && parts.size == 3 && parts[1] == "artifacts" -> {
                val request = json.decodeFromString<Submission>(input())
                require(request.id == null || request.id == parts[2]) { "Artifact ID mismatch" }
                json.encodeToString(artifacts.submit(request.draft(parts[2]), now()))
            }
            method == "GET" && parts.size == 3 && parts[1] == "assets" -> { noBody(); json.encodeToString(assets.metadata(parts[2])) }
            method == "POST" && parts.size == 4 && parts[1] == "assets" && parts[3] == "references" ->
                json.encodeToString(assets.retain(parts[2], json.decodeFromString<CoreAssetOwner>(input())))
            method == "GET" && path == "/image-generation/capability" -> { noBody(); enabled(db.imageJobs().value("image_generation_enabled") == "true") }
            method == "PUT" && path == "/image-generation/capability" -> { val value = flag(); db.imageJobs().setEnabled(value); enabled(value) }
            method == "GET" && path == "/image-generation/profiles" -> { noBody(); json.encodeToString(db.imageJobs().profiles()) }
            method == "PUT" && parts.size == 4 && parts[1] == "image-generation" && parts[2] == "profiles" -> {
                val profile = json.decodeFromString<ImageProfile>(input())
                require(profile.id == parts[3]) { "Profile ID mismatch" }
                db.imageJobs().putProfile(profile); json.encodeToString(profile)
            }
            method == "DELETE" && parts.size == 4 && parts[1] == "image-generation" && parts[2] == "profiles" -> {
                noBody(); db.imageJobs().deleteProfile(parts[3]); buildJsonObject { put("deleted", true) }.toString()
            }
            method == "GET" && path == "/image-generation/jobs" -> { noBody(); imageOwner?.checkHealthy(); json.encodeToString(db.imageJobs().jobs()) }
            method == "POST" && path == "/image-generation/jobs" -> {
                val owner = checkNotNull(imageOwner) { "Image job owner is unavailable" }
                json.encodeToString(owner.submit(json.decodeFromString<ImageRequest>(input())))
            }
            method == "GET" && parts.size == 4 && parts[1] == "image-generation" && parts[2] == "jobs" -> {
                noBody(); imageOwner?.checkHealthy(); json.encodeToString(db.imageJobs().read(parts[3]))
            }
            method == "POST" && parts.size == 5 && parts[1] == "image-generation" && parts[2] == "jobs" && parts[4] == "cancel" -> {
                noBody(); val owner = checkNotNull(imageOwner) { "Image job owner is unavailable" }
                json.encodeToString(owner.cancel(parts[3]))
            }
            else -> error("Media operation unavailable until its Kotlin execution host is installed")
        }
    }

    private fun Submission.draft(id: String?) = ArtifactDraft(id, title, description, files, assets, entry,
        expectedRevision, stateVersion, requestedDomains)
}
