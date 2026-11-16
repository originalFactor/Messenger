/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import cc.ptoe.messenger.data.local.room.LearningContentWire
import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.domain.model.LearningReviewAttempt
import cc.ptoe.messenger.domain.model.LearningReviewState
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull

@Serializable internal data class PortableLearning(
    val formatVersion: Int, val records: List<LearningRecord>, val revisions: List<LearningRecord>,
    val reviewStates: List<LearningReviewState>, val attempts: List<LearningReviewAttempt>,
    val externalResourcesIncluded: Boolean,
)
@Serializable internal data class PortableAsset(val metadata: CoreAssetMetadata, val dataBase64: String)
@Serializable internal data class PortableLearningBundle(
    val formatVersion: Int, val learning: PortableLearning, val assets: List<PortableAsset>,
    val artifacts: List<JsonObject>, val generationProvenance: Map<String, List<JsonObject>> = emptyMap(),
)
internal data class ValidatedLearningBundle(val bundle: PortableLearningBundle, val bytes: Map<String, ByteArray>)

/** Strict resource and format validation only; database conflicts and import transaction remain separate. */
internal object LearningBundleValidation {
    private val json = Json { classDiscriminator = "kind"; encodeDefaults = true }
    private const val JSON_LIMIT = 96 * 1024 * 1024
    private const val ASSET_LIMIT = 64 * 1024 * 1024
    fun validate(document: String): ValidatedLearningBundle {
        require(document.encodeToByteArray().size <= JSON_LIMIT) { "Learning package exceeds JSON limit" }
        val bundle = json.decodeFromString<PortableLearningBundle>(document)
        val learning = bundle.learning
        require(bundle.formatVersion == 1 && learning.formatVersion == 1 && !learning.externalResourcesIncluded &&
            bundle.assets.size <= 10_000 && bundle.artifacts.size <= 1_000 && learning.records.size <= 10_000 &&
            learning.revisions.size <= 50_000 && learning.reviewStates.size <= 10_000 && learning.attempts.size <= 50_000 &&
            json.encodeToString(PortableLearning.serializer(), learning).encodeToByteArray().size <= 16 * 1024 * 1024) { "Unsupported or oversized learning package" }
        val records = learning.records.associateBy { it.id }
        require(records.size == learning.records.size && records.values.all { it.id.isNotBlank() && it.version in 1 until Long.MAX_VALUE }) { "Invalid learning records" }
        val revisions = mutableSetOf<Pair<String, Long>>()
        for (revision in learning.revisions) {
            require(revision.version > 0 && revisions.add(revision.id to revision.version)) { "Duplicate learning revision" }
            records[revision.id]?.let { current ->
                require(revision.version <= current.version && revision.kind == current.kind &&
                    (revision.version != current.version || revision == current)) { "Invalid learning revision" }
            }
        }
        require(records.values.all { (it.id to it.version) in revisions }) { "Missing current revision" }
        val states = learning.reviewStates.associateBy { it.cardId }
        require(states.size == learning.reviewStates.size && states.values.all { state ->
            records[state.cardId]?.kind == "reviewCard" && state.version >= 0 && state.intervalDays in 0..36_500 &&
                state.repetitions >= 0 && state.ease.isFinite() && state.ease >= 1.3 && state.algorithm == "sm2-v1"
        } && records.values.filter { it.kind == "reviewCard" }.all { it.id in states }) { "Invalid review state" }
        require(learning.attempts.map { it.submission.submissionId }.toSet().size == learning.attempts.size && learning.attempts.all {
            it.submission.submissionId.isNotBlank() && it.submission.submissionId.encodeToByteArray().size <= 128 &&
                it.submission.rating in setOf("again", "hard", "good", "easy") && it.itemSnapshot.kind == "item" &&
                it.itemSnapshot.version == it.submission.itemVersion && it.state.cardId == it.submission.cardId &&
                it.state.version >= 0 && it.state.intervalDays in 0..36_500 && it.state.repetitions >= 0 &&
                it.state.ease.isFinite() && it.state.ease >= 1.3 && it.state.algorithm == "sm2-v1"
        }) { "Invalid review attempts" }
        val bytes = linkedMapOf<String, ByteArray>()
        var total = 0L
        for (entry in bundle.assets) {
            val asset = entry.metadata
            require(CoreAssetReader.validAssetId(asset.id) && asset.id == asset.sha256 && asset.size in 1..CoreAssetReader.MAX_BYTES &&
                CoreAssetReader.validMime(asset.mimeType) && asset.references.size <= 10_000 && asset.references.all(CoreAssetReader::validOwner) &&
                entry.dataBase64.length <= ((CoreAssetReader.MAX_BYTES + 2) / 3 * 4)) { "Invalid asset metadata" }
            val decoded = Base64.getDecoder().decode(entry.dataBase64)
            total += decoded.size
            require(total <= ASSET_LIMIT && decoded.size.toLong() == asset.size && bytes.put(asset.id, decoded) == null &&
                MessageDigest.getInstance("SHA-256").digest(decoded).joinToString("") { "%02x".format(it.toInt() and 255) } == asset.id) { "Invalid asset content" }
            if (asset.mimeType.startsWith("image/")) {
                val dimensions = validateOwnedImage(decoded, asset.mimeType)
                require(asset.width == dimensions.width && asset.height == dimensions.height) { "Image dimensions differ" }
            } else require(asset.width == null && asset.height == null) { "Invalid non-image dimensions" }
        }
        val artifactIds = mutableSetOf<String>()
        for (artifact in bundle.artifacts) {
            val allowed = setOf("id", "title", "description", "revisions", "requestedDomains", "approvedDomains", "state", "stateVersion", "trustedOnDevice", "createdAt", "updatedAt")
            require(artifact.keys.all { it in allowed }) { "Unknown artifact field" }
            require(artifact.keys.containsAll(setOf("id", "title", "revisions", "createdAt", "updatedAt"))) { "Incomplete artifact" }
            val id = artifact.getValue("id").jsonPrimitive.content
            UUID.fromString(id)
            val title = artifact.getValue("title").jsonPrimitive
            require(title.isString && title.content.isNotBlank() && title.content.encodeToByteArray().size <= 240 &&
                (artifact["description"]?.jsonPrimitive?.content?.encodeToByteArray()?.size ?: 0) <= 4000) { "Invalid artifact title or description" }
            val domains = artifact["requestedDomains"] as? JsonArray ?: JsonArray(emptyList())
            require(domains.size <= 16 && domains.all { element ->
                val p = element as? JsonPrimitive
                val host = p?.content.orEmpty()
                p?.isString == true && host.length in 1..253 && host.contains('.') && host == host.lowercase() &&
                    host.split('.').all { it.isNotEmpty() && !it.startsWith('-') && !it.endsWith('-') && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' } } &&
                    host.any { it in 'a'..'z' } && listOf("localhost", "local", "internal").none { host.endsWith(".$it") }
            }) { "Invalid artifact domains" }
            require(artifactIds.add(id)) { "Duplicate artifact" }
            require((artifact["approvedDomains"] as? JsonArray ?: JsonArray(emptyList())).isEmpty()) { "Network grants are not portable" }
            val revisionsArray = artifact.getValue("revisions") as? JsonArray ?: error("Invalid artifact revisions")
            require(revisionsArray.size in 1..32) { "Invalid artifact revision count" }
            for ((index, item) in revisionsArray.withIndex()) {
                val revision = item.jsonObject
                require(revision.keys.containsAll(setOf("revision", "files", "entry", "createdAt")) &&
                    revision.keys.all { it in setOf("revision", "files", "assets", "entry", "createdAt", "stateVersion") } &&
                    revision.getValue("revision").jsonPrimitive.content.toInt() == index + 1 && revision.getValue("createdAt").jsonPrimitive.longOrNull != null) { "Invalid artifact revision" }
                val files = revision.getValue("files").jsonObject
                val assets = revision["assets"]?.jsonObject ?: JsonObject(emptyMap())
                require(files.isNotEmpty() && files.size <= 128 && assets.size <= 64 && files.keys.intersect(assets.keys).isEmpty()) { "Invalid artifact resources" }
                fun validPath(path: String) = path.isNotEmpty() && path.length < 256 && path.all { it.isLetterOrDigit() && it.code < 128 || it in "_./-" } && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
                require((files.keys + assets.keys).all(::validPath) && files.values.all { it is JsonPrimitive && it.isString && it.content.encodeToByteArray().size <= 1024 * 1024 } &&
                    files.values.sumOf { it.jsonPrimitive.content.encodeToByteArray().size.toLong() } <= 4L * 1024 * 1024 &&
                    assets.values.all { it.jsonPrimitive.content in bytes }) { "Invalid artifact content" }
                val entry = revision.getValue("entry").jsonPrimitive.content
                require(entry.endsWith(".html") && entry in files) { "Invalid artifact entry" }
            }
            val latest = revisionsArray.last().jsonObject
            require((latest["stateVersion"]?.jsonPrimitive?.longOrNull ?: 0) == (artifact["stateVersion"]?.jsonPrimitive?.longOrNull ?: 0)) { "Artifact state version differs" }
            require((artifact["state"] ?: JsonNull).toString().encodeToByteArray().size <= 64 * 1024) { "Artifact state exceeds limit" }
        }
        for (record in learning.records + learning.revisions + learning.attempts.map { it.itemSnapshot }) {
            val content = json.decodeFromJsonElement<LearningContentWire>(record.content)
            require(record.source?.assetIds.orEmpty().all { it in bytes } &&
                ((content as? LearningContentWire.Material)?.assetId?.let { it in bytes } != false) &&
                ((content as? LearningContentWire.Material)?.artifactId?.let { it in artifactIds } != false)) { "Package resource is missing" }
        }
        require(bundle.generationProvenance.keys.all { it in bytes } && bundle.generationProvenance.values.all { it.size <= 128 }) { "Invalid provenance" }
        bundle.generationProvenance.values.flatten().forEach(::validateProvenance)
        return ValidatedLearningBundle(bundle, bytes)
    }
    internal fun validateProvenance(value: JsonObject) {
        require(value.keys == setOf("prompt", "modelId", "parameters", "revisedPrompt", "createdAt", "completedAt", "usage")) { "Invalid provenance fields" }
        fun boundedString(element: JsonElement?, max: Int, nullable: Boolean = false): Boolean =
            if (element == JsonNull && nullable) true else (element as? JsonPrimitive)?.let { it.isString && it.content.encodeToByteArray().size in 1..max } == true
        require(boundedString(value["prompt"], 32_000) && boundedString(value["modelId"], 256) &&
            (value["revisedPrompt"] == JsonNull || (value["revisedPrompt"] as? JsonPrimitive)?.let { it.isString && it.content.encodeToByteArray().size <= 32_000 } == true)) { "Invalid provenance text" }
        val created = value["createdAt"]?.jsonPrimitive?.longOrNull
        val completed = value["completedAt"]?.jsonPrimitive?.longOrNull
        require(created != null && completed != null && created >= 0 && completed >= created) { "Invalid provenance timestamps" }
        val params = value["parameters"] as? JsonObject ?: error("Invalid image parameters")
        val allowed = setOf("n", "size", "quality", "style", "response_format", "background", "output_format", "output_compression", "moderation")
        require(params.keys.all { it in allowed }) { "Invalid image parameter" }
        for ((key, element) in params) {
            val primitive = element as? JsonPrimitive ?: error("Invalid image parameter")
            when (key) {
                "n" -> require(!primitive.isString && primitive.longOrNull?.let { it in 1L..4L } == true) { "Invalid image count" }
                "output_compression" -> require(!primitive.isString && primitive.longOrNull?.let { it in 0L..100L } == true) { "Invalid image compression" }
                else -> require(primitive.isString && primitive.content.encodeToByteArray().size in 1..128) { "Invalid image parameter" }
            }
        }
        fun validUsage(element: JsonElement, depth: Int): Boolean {
            if (depth > 4) return false
            return when (element) {
                is JsonObject -> element.size <= 64 && element.all { (key, nested) -> key.length <= 64 && key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' } && validUsage(nested, depth + 1) }
                is JsonPrimitive -> !element.isString && element.doubleOrNull?.isFinite() == true
                else -> false
            }
        }
        val usage = value.getValue("usage")
        require(usage == JsonNull || (usage.toString().encodeToByteArray().size <= 8192 && validUsage(usage, 0))) { "Invalid provenance usage" }
    }

}
