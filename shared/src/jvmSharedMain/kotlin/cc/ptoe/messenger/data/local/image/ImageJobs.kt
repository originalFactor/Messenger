/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.image

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable internal data class ImageProfile(
    val id: String, val name: String, val providerId: String, val modelId: String,
    val adapter: String = "openai_images", val enabled: Boolean = false,
    val supportedParameters: Set<String> = emptySet(), val defaults: JsonObject = JsonObject(emptyMap()),
)
@Serializable internal data class ImageRequest(
    val requestId: String, val profileId: String, val prompt: String, val parameters: JsonObject = JsonObject(emptyMap()),
)
@Serializable internal enum class ImageJobStatus { queued, running, succeeded, failed, cancelled, unknown }
@Serializable internal data class ImageJob(
    val id: String, val request: ImageRequest, val profile: ImageProfile, val parameters: JsonObject,
    val status: ImageJobStatus, val createdAt: Long, val updatedAt: Long, val assetIds: List<String> = emptyList(),
    val error: String? = null, val usage: kotlinx.serialization.json.JsonElement? = null,
    val revisedPrompts: List<String?> = emptyList(),
)
internal class ImageCredentials(val baseUrl: String, val apiKey: String) {
    override fun toString(): String = "ImageCredentials(redacted)"
}
internal data class ImageAdmission(val job: ImageJob, val created: Boolean, val credentials: ImageCredentials? = null)

internal data class ImageSuccessOutput(val metadata: cc.ptoe.messenger.data.local.asset.CoreAssetMetadata, val revisedPrompt: String?)
