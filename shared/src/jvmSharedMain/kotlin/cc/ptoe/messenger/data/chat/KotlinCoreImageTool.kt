/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.local.image.ImageGenerationService
import cc.ptoe.messenger.data.local.image.ImageGenerationAuthorization
import cc.ptoe.messenger.data.local.image.ImageJobStatus
import cc.ptoe.messenger.data.local.image.ImageRequest
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private const val IMAGE = "messenger_generate_image"
private val codec = Json { encodeDefaults = true; explicitNulls = false }
private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"action":{"type":"string","enum":["listProfiles","generate"]},"requestId":{"type":"string"},"profileId":{"type":"string"},"prompt":{"type":"string"},"parameters":{"type":"object"}},"anyOf":[{"properties":{"action":{"enum":["listProfiles"]}},"required":["action"],"maxProperties":1},{"properties":{"action":{"enum":["generate"]}},"required":["requestId","profileId","prompt"]}],"additionalProperties":false}""").jsonObject
internal val coreImageSpec = ToolSpecDto("function", ToolSpecFunctionDto(IMAGE,
    "Discover enabled image profiles or generate paid images using an explicit profile and stable requestId. Cancellation may still incur charges; never retry an unknown or failed outcome automatically.", schema))

internal suspend fun coreImageTool(db: LocalDatabase, conversationId: String, store: () -> RoomKotlinTurnStore,
    images: ImageGenerationService, authorization: ImageGenerationAuthorization): KotlinCoreToolAdapter {
    return KotlinCoreToolAdapter.create(db, conversationId, store, coreImageSpec, "image_generation_enabled") { turnId, call ->
        require(call.arguments.encodeToByteArray().size <= 64 * 1024) { "Image arguments exceed the limit" }
        val args = Json.parseToJsonElement(call.arguments) as? JsonObject ?: error("Invalid image arguments")
        require(args.keys.all { it in setOf("action", "requestId", "profileId", "prompt", "parameters") }) { "Unknown image argument" }
        when (args.optionalString("action") ?: "generate") {
            "listProfiles" -> store().executeRoomCoreTool(turnId, call) {
                require(args.keys == setOf("action")) { "listProfiles accepts only action" }
                val output = buildJsonObject { put("profiles", buildJsonArray {
                    db.imageJobs().enabledProfiles().forEach { profile -> add(buildJsonObject {
                        put("id", profile.id); put("name", profile.name); put("modelId", profile.modelId)
                    }) }
                }) }
                ToolExecutionResult(output.toString())
            }
            "generate" -> {
                val params = args["parameters"]?.let { it as? JsonObject ?: error("Invalid image parameters") } ?: JsonObject(emptyMap())
                val request = ImageRequest(args.requiredString("requestId"), args.requiredString("profileId"), args.requiredString("prompt"), params)
                try {
                    val job = images.runFromTool(request, authorization) { store().claimCoreToolInTransaction(turnId, call) }
                    val failed = job.status != ImageJobStatus.succeeded
                    if (failed) store().markCoreImageFailure(turnId, call)
                    ToolExecutionResult(codec.encodeToString(job), isError = failed, structuredContent = buildJsonObject {
                        put("version", 1); put("type", "imageGenerationJob"); put("jobId", job.id)
                        put("assetIds", JsonArray(job.assetIds.map(::JsonPrimitive))); put("status", job.status.name)
                    })
                } catch (failure: Throwable) {
                    withContext(NonCancellable) { store().markCoreImageFailure(turnId, call) }
                    throw failure
                }
            }
            else -> error("Unknown image action")
        }
    }
}
