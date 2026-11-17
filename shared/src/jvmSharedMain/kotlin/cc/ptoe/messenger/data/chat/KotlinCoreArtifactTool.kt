/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.local.artifact.ArtifactData
import cc.ptoe.messenger.data.local.artifact.ArtifactDraft
import cc.ptoe.messenger.data.local.artifact.RoomArtifactService
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private const val ARTIFACTS = "messenger_artifacts"
private val codec = Json { encodeDefaults = true; explicitNulls = false }
private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"action":{"enum":["create","update","get"]},"id":{"type":"string"},"title":{"type":"string"},"description":{"type":"string"},"files":{"type":"object","additionalProperties":{"type":"string"}},"assets":{"type":"object","additionalProperties":{"type":"string"}},"entry":{"type":"string"},"expectedRevision":{"type":"integer"},"stateVersion":{"type":"integer"},"requestedDomains":{"type":"array","items":{"type":"string"}}},"required":["action"],"additionalProperties":false}""").jsonObject
internal val coreArtifactSpec = ToolSpecDto("function", ToolSpecFunctionDto(ARTIFACTS,
    "Create, update or read a local interactive application. Supply complete local files and the expected revision. Applications have no direct provider keys or native API access.", schema))

internal suspend fun coreArtifactTool(db: LocalDatabase, conversationId: String, store: () -> RoomKotlinTurnStore,
    artifacts: RoomArtifactService, now: () -> Long): KotlinCoreToolAdapter {
    return KotlinCoreToolAdapter.create(db, conversationId, store, coreArtifactSpec, "artifact_tools_enabled") { turnId, call ->
        require(call.arguments.encodeToByteArray().size <= 5 * 1024 * 1024) { "Artifact arguments exceed the limit" }
        val args = Json.parseToJsonElement(call.arguments) as? JsonObject ?: error("Invalid artifact arguments")
        require(args.keys.all { it in setOf("action", "id", "title", "description", "files", "assets", "entry", "expectedRevision", "stateVersion", "requestedDomains") }) { "Unknown artifact argument" }
        val action = args.requiredString("action")
        val artifact = when (action) {
            "get" -> artifacts.getForTool(args.requiredString("id")) { store().claimCoreToolInTransaction(turnId, call) }
            "create", "update" -> {
                val id = args.optionalString("id")
                require((action == "update") == (id != null)) { "Update requires an id; create must omit it" }
                val files = codec.decodeFromJsonElement<Map<String, String>>(requireNotNull(args["files"]) { "Files are required" })
                val assets = args["assets"]?.let { codec.decodeFromJsonElement<Map<String, String>>(it) } ?: emptyMap()
                val domains = args["requestedDomains"]?.let { codec.decodeFromJsonElement<List<String>>(it) } ?: emptyList()
                val draft = ArtifactDraft(id, args.requiredString("title"), args.optionalString("description").orEmpty(), files,
                    assets, args.optionalString("entry") ?: "index.html", args.optionalLong("expectedRevision") ?: 0,
                    args.optionalLong("stateVersion") ?: 0, domains)
                artifacts.submit(draft, now()) { store().claimCoreToolInTransaction(turnId, call) }
            }
            else -> error("Unknown artifact action")
        }
        val output = if (action == "get") codec.encodeToString(artifact)
            else "Interactive application saved: ${artifact.title} (${artifact.id}). The client can render it immediately."
        ToolExecutionResult(output, structuredContent = artifactReference(artifact))
    }
}

private fun artifactReference(artifact: ArtifactData) = buildJsonObject {
    put("version", 1); put("type", "artifact"); put("id", artifact.id); put("title", artifact.title)
    put("revision", artifact.revisions.last().revision); put("description", artifact.description)
}
