/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import cc.ptoe.messenger.data.local.image.ImageAdmission
import cc.ptoe.messenger.data.local.image.ImageCredentials
import cc.ptoe.messenger.data.local.image.ImageJob
import cc.ptoe.messenger.data.local.image.ImageJobStatus
import cc.ptoe.messenger.data.local.image.ImageProfile
import cc.ptoe.messenger.data.local.image.ImageRequest
import cc.ptoe.messenger.data.local.image.ImageSuccessOutput
import cc.ptoe.messenger.data.local.asset.CoreAssetMetadata
import cc.ptoe.messenger.data.local.asset.CoreAssetOwner
import cc.ptoe.messenger.data.local.asset.CoreAssetReader
import cc.ptoe.messenger.data.local.asset.LearningBundleValidation
import cc.ptoe.messenger.data.local.asset.retainOwner
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val imageJson = Json { encodeDefaults = true }
private const val JOB = "local_image_job:"
private const val PROFILE = "local_image_profile:"
private const val ENABLED = "image_generation_enabled"
private fun validIdentifier(value: String) = value.isNotEmpty() && value.length <= 256 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_:." }
internal fun imageJobId(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
private val allowedParameters = setOf("n", "size", "quality", "style", "response_format", "background", "output_format", "output_compression", "moderation")
private fun validateParameters(parameters: JsonObject, supported: Set<String>) {
    require(supported.all { it in allowedParameters } && parameters.keys.all { it in supported }) { "Unsupported image parameter" }
    for ((key, value) in parameters) {
        val p = value.jsonPrimitive
        when (key) {
            "n" -> require(!p.isString && p.longOrNull?.let { it in 1L..4L } == true) { "Invalid image count" }
            "output_compression" -> require(!p.isString && p.longOrNull?.let { it in 0L..100L } == true) { "Invalid image compression" }
            else -> require(p.isString && p.content.encodeToByteArray().size in 1..128) { "Invalid image parameter" }
        }
    }
}

/** Durable Core-compatible admission and status only; this does not issue any billable request. */
@Dao internal interface ImageJobDao {
    @Query("SELECT value FROM kv WHERE `key` = :key") suspend fun value(key: String): String?
    @Query("SELECT value FROM kv WHERE substr(`key`,1,:length) = :prefix ORDER BY `key`") suspend fun catalog(prefix: String, length: Int): List<String>
    @Query("SELECT * FROM kv WHERE substr(`key`,1,:length) = :prefix ORDER BY `key`") suspend fun entries(prefix: String, length: Int): List<KeyValueRow>
    @Query("SELECT * FROM providers WHERE id = :id") suspend fun provider(id: String): ProviderRow?
    @Upsert suspend fun write(row: KeyValueRow)
    @Query("DELETE FROM kv WHERE `key` = :key") suspend fun deleteKey(key: String)

    private suspend fun checkedProfile(profile: ImageProfile): ProviderRow {
        require(validIdentifier(profile.id) && profile.name.isNotBlank() && profile.name.encodeToByteArray().size <= 256 &&
            profile.modelId.isNotBlank() && profile.modelId.encodeToByteArray().size <= 256 && profile.adapter == "openai_images" &&
            profile.providerId != "builtin-messenger-cloud-ai") { "Invalid image profile" }
        val provider = provider(profile.providerId) ?: error("Image provider missing")
        val uri = URI(provider.baseUrl)
        require(provider.apiKey.isNotBlank() && uri.scheme in setOf("http", "https") && uri.host != null &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "Invalid image provider" }
        validateParameters(profile.defaults, profile.supportedParameters)
        return provider
    }

    @Transaction suspend fun putProfile(profile: ImageProfile) {
        checkedProfile(profile)
        val key = PROFILE + profile.id
        require(value(key) != null || catalog(PROFILE, PROFILE.length).size < 256) { "Image profile limit exceeded" }
        write(KeyValueRow(key, imageJson.encodeToString(profile)))
    }
    @Transaction suspend fun setEnabled(enabled: Boolean) = write(KeyValueRow(ENABLED, enabled.toString()))
    @Transaction suspend fun deleteProfile(id: String) { require(validIdentifier(id)); deleteKey(PROFILE + id) }
    @Transaction suspend fun profiles(): List<ImageProfile> = entries(PROFILE, PROFILE.length).map { row ->
        imageJson.decodeFromString<ImageProfile>(row.value).also { profile ->
            require(row.key == PROFILE + profile.id && validIdentifier(profile.id)) { "Invalid image profile" }
        }
    }
    @Transaction suspend fun jobs(): List<ImageJob> = entries(JOB, JOB.length).map { row ->
        val id = row.key.removePrefix(JOB)
        require(row.key == JOB + id) { "Invalid image job key" }
        read(id)
    }
    @Transaction suspend fun enabledProfiles(): List<ImageProfile> = entries(PROFILE, PROFILE.length).map { row ->
        imageJson.decodeFromString<ImageProfile>(row.value).also { profile ->
            require(row.key == PROFILE + profile.id && validIdentifier(profile.id)) { "Invalid image profile" }
        }
    }.filter { it.enabled }

    /** Deduplicate before consulting mutable capability, profile or provider settings. */
    @Transaction suspend fun findRequest(request: ImageRequest): ImageJob? {
        require(validIdentifier(request.requestId)) { "Invalid request ID" }
        val id = imageJobId(request.requestId)
        if (value(JOB + id) == null) return null
        val existing = read(id)
        check(existing.request == request) { "Request ID already identifies a different image request" }
        return existing
    }

    @Transaction suspend fun admit(request: ImageRequest, now: Long): ImageAdmission {
        require(validIdentifier(request.requestId)) { "Invalid request ID" }
        val id = imageJobId(request.requestId)
        value(JOB + id)?.let { raw ->
            val previous = imageJson.decodeFromString<ImageJob>(raw)
            check(previous.id == id && imageJobId(previous.request.requestId) == id) { "Invalid stored image job" }
            check(previous.request == request) { "Request ID already identifies a different image request" }
            return ImageAdmission(previous, false)
        }
        check(value(ENABLED) == "true") { "Image generation is disabled" }
        require(validIdentifier(request.profileId) && request.prompt.isNotBlank() && request.prompt.encodeToByteArray().size <= 32_000) { "Invalid image request" }
        val profile = value(PROFILE + request.profileId)?.let { imageJson.decodeFromString<ImageProfile>(it) } ?: error("Image profile missing")
        val provider = checkedProfile(profile)
        check(profile.enabled) { "Image profile is disabled" }
        val params = JsonObject(profile.defaults + request.parameters)
        validateParameters(params, profile.supportedParameters)
        require(catalog(JOB, JOB.length).size < 10_000) { "Image job limit exceeded" }
        val job = ImageJob(id, request, profile, params, ImageJobStatus.queued, now, now)
        write(KeyValueRow(JOB + id, imageJson.encodeToString(job)))
        return ImageAdmission(job, true, ImageCredentials(provider.baseUrl, provider.apiKey))
    }

    @Transaction suspend fun markRunning(id: String, now: Long): ImageJob {
        val job = read(id)
        check(job.status == ImageJobStatus.queued) { "Image job already started or completed" }
        return job.copy(status = ImageJobStatus.running, updatedAt = now).also { write(KeyValueRow(JOB + id, imageJson.encodeToString(it))) }
    }
    @Transaction suspend fun fail(id: String, message: String, ambiguous: Boolean, now: Long): ImageJob {
        val job = read(id)
        check(job.status == ImageJobStatus.running) { "Image job is not running" }
        val status = if (ambiguous) ImageJobStatus.unknown else ImageJobStatus.failed
        return job.copy(status = status, updatedAt = now, error = message).also { write(KeyValueRow(JOB + id, imageJson.encodeToString(it))) }
    }
    @Transaction suspend fun cancel(id: String, now: Long): ImageJob {
        val job = read(id)
        if (job.status !in setOf(ImageJobStatus.queued, ImageJobStatus.running)) return job
        return job.copy(status = ImageJobStatus.cancelled, updatedAt = now, error = "Cancelled locally; remote generation and charges may already have occurred.")
            .also { write(KeyValueRow(JOB + id, imageJson.encodeToString(it))) }
    }
    /** Files must already be hash-verified, durably staged and protected by the shared asset gate. */
    @Transaction suspend fun complete(id: String, output: List<ImageSuccessOutput>, usage: JsonElement?, now: Long): ImageJob {
        val job = read(id)
        check(job.status == ImageJobStatus.running) { "Image job is no longer running" }
        require(output.size in 1..4) { "Invalid image output count" }
        val current = entries("local_asset:", "local_asset:".length).associate { row ->
            val item = imageJson.decodeFromString<CoreAssetMetadata>(row.value)
            require(CoreAssetReader.validAssetId(item.id) && row.key == "local_asset:${item.id}" && item.id == item.sha256 &&
                item.size in 1..CoreAssetReader.MAX_BYTES && item.references.size <= 10_000 && item.references.all(CoreAssetReader::validOwner)) { "Invalid asset catalog" }
            item.id to item
        }.toMutableMap()
        var total = current.values.fold(0L) { n, asset -> Math.addExact(n, asset.size) }
        val sanitized = numericImageUsage(usage)
        for (item in output) {
            val asset = item.metadata
            require(CoreAssetReader.validAssetId(asset.id) && asset.id == asset.sha256 && asset.size in 1..CoreAssetReader.MAX_BYTES &&
                asset.mimeType in setOf("image/png", "image/jpeg", "image/webp") && asset.width != null && asset.height != null &&
                asset.width in 1..8192 && asset.height in 1..8192 && asset.width * asset.height <= 32_000_000 &&
                item.revisedPrompt?.encodeToByteArray()?.size?.let { it <= 32_000 } != false) { "Invalid generated image" }
            val previous = current[asset.id]
            val merged = if (previous == null) {
                require(current.size < 10_000) { "Asset catalog limit exceeded" }
                total = Math.addExact(total, asset.size)
                require(total <= 512L * 1024 * 1024) { "Asset catalog quota exceeded" }
                asset.copy(references = emptyList()).retainOwner(CoreAssetOwner("imageGenerationJob", id))
            } else {
                require(previous.sha256 == asset.sha256 && previous.size == asset.size && previous.mimeType == asset.mimeType &&
                    previous.width == asset.width && previous.height == asset.height) { "Existing asset conflicts" }
                previous.retainOwner(CoreAssetOwner("imageGenerationJob", id))
            }.copy(unreferencedAt = null)
            current[asset.id] = merged
            write(KeyValueRow("local_asset:${asset.id}", imageJson.encodeToString(merged)))
            val key = "provenance:${asset.id}"
            val old = value(key)?.let { imageJson.parseToJsonElement(it) as? JsonArray ?: error("Invalid stored provenance") }.orEmpty()
                .map { it as? JsonObject ?: error("Invalid stored provenance") }
            old.forEach(LearningBundleValidation::validateProvenance)
            val provenance = buildJsonObject {
                put("prompt", job.request.prompt); put("modelId", job.profile.modelId); put("parameters", job.parameters)
                put("revisedPrompt", item.revisedPrompt?.let(::JsonPrimitive) ?: JsonNull)
                put("createdAt", job.createdAt); put("completedAt", maxOf(now, job.createdAt)); put("usage", sanitized ?: JsonNull)
            }
            LearningBundleValidation.validateProvenance(provenance)
            val mergedProvenance = (old + provenance).distinct()
            require(mergedProvenance.size <= 128) { "Provenance limit exceeded" }
            write(KeyValueRow(key, JsonArray(mergedProvenance).toString()))
        }
        val completed = job.copy(status = ImageJobStatus.succeeded, updatedAt = now, assetIds = output.map { it.metadata.id },
            usage = sanitized, revisedPrompts = output.map { it.revisedPrompt }, error = null)
        write(KeyValueRow(JOB + id, imageJson.encodeToString(completed)))
        return completed
    }

    /** Caller must stop all previous job workers before running recovery. Never resubmits. */
    @Transaction suspend fun recoverInterrupted(now: Long) {
        for (row in entries(JOB, JOB.length)) {
            val job = imageJson.decodeFromString<ImageJob>(row.value)
            check(validIdentifier(job.request.requestId) && job.id == imageJobId(job.request.requestId) && row.key == JOB + job.id) { "Invalid stored image job" }
            if (job.status in setOf(ImageJobStatus.queued, ImageJobStatus.running)) {
                write(KeyValueRow(JOB + job.id, imageJson.encodeToString(job.copy(status = ImageJobStatus.unknown, updatedAt = now,
                    error = "Interrupted; remote generation and charges may have occurred. No automatic retry."))))
            }
        }
    }
    suspend fun read(id: String): ImageJob {
        require(id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid image job ID" }
        val job = value(JOB + id)?.let { imageJson.decodeFromString<ImageJob>(it) } ?: error("Image job not found")
        check(job.id == id && validIdentifier(job.request.requestId) && imageJobId(job.request.requestId) == id) { "Invalid stored image job" }
        return job
    }
}

/** Retain only bounded numeric provider counters; never persist provider-returned strings in provenance. */
private fun numericImageUsage(value: JsonElement?): JsonElement? {
    fun filter(element: JsonElement, depth: Int): JsonElement? {
        if (depth > 4) return null
        return when (element) {
            is JsonObject -> if (element.size > 64) null else JsonObject(element.mapNotNull { (key, child) ->
                if (key.length > 64 || !key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }) null
                else filter(child, depth + 1)?.let { key to it }
            }.toMap())
            is JsonPrimitive -> if (!element.isString && element.content.toDoubleOrNull()?.isFinite() == true) element else null
            else -> null
        }
    }
    return value?.let { filter(it, 0) }?.takeIf { it.toString().encodeToByteArray().size <= 8192 }
}
