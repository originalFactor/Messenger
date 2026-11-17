/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import cc.ptoe.messenger.data.local.room.LearningContentWire
import cc.ptoe.messenger.data.local.room.LearningRecordRow
import cc.ptoe.messenger.data.local.room.LearningRevisionRow
import cc.ptoe.messenger.data.local.room.LearningReviewAttemptRow
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.domain.model.LearningReviewAttempt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Immutable DB snapshot captured in one transaction while the common asset/domain gate is held. */
internal data class AssetReferenceSnapshot(
    val records: List<LearningRecordRow>, val revisions: List<LearningRevisionRow>, val attempts: List<LearningReviewAttemptRow>,
    val artifacts: List<KeyValueRow>, val imageJobs: List<KeyValueRow>,
)

/** Conservative audit only; no owner or file is removed. Malformed or unknown formats fail closed. */
internal class AssetReferenceAudit(snapshot: AssetReferenceSnapshot) {
    private val json = Json { classDiscriminator = "kind" }
    private val records = snapshot.records.map { row ->
        json.decodeFromString<LearningRecord>(row.body).also { record ->
            require(record.id == row.id && record.kind == row.kind && record.version == row.version && record.courseId == row.course_id &&
                record.title == row.title && record.archived == row.archived && record.updatedAt == row.updated_at) { "Inconsistent learning record" }
        }
    } + snapshot.revisions.map { row ->
        json.decodeFromString<LearningRecord>(row.body).also { record ->
            require(record.id == row.item_id && record.version == row.version) { "Inconsistent learning revision" }
        }
    }
    private val attempts = snapshot.attempts.map { row ->
        json.decodeFromString<LearningReviewAttempt>(row.body).also { attempt ->
            require(attempt.submission.submissionId == row.submission_id && attempt.submission.cardId == row.card_id &&
                attempt.createdAt == row.created_at) { "Inconsistent review attempt" }
        }
    }
    private val recordRefs = records.map { it to assetIds(it) }
    private val attemptRefs = attempts.map { it to assetIds(it.itemSnapshot) }
    private val artifacts = snapshot.artifacts.map { row ->
        parseArtifact(json.parseToJsonElement(row.value).jsonObject).also { require(row.key == "artifact:${it.first}") { "Inconsistent artifact key" } }
    }
    private val jobs = snapshot.imageJobs.map { row ->
        parseJob(json.parseToJsonElement(row.value).jsonObject).also { require(row.key == "local_image_job:${it.id}") { "Inconsistent image job key" } }
    }

    private fun assetIds(record: LearningRecord): Set<String> {
        val content = json.decodeFromJsonElement<LearningContentWire>(record.content)
        require(record.source == null || record.source.kind in setOf("chat", "acp", "manual", "import")) { "Unknown source kind" }
        val ids = (record.source?.assetIds.orEmpty() + listOfNotNull((content as? LearningContentWire.Material)?.assetId)).toSet()
        require(ids.all(CoreAssetReader::validAssetId)) { "Invalid learning asset ID" }
        return ids
    }

    fun protectedAssetIds(): Set<String> = buildSet {
        recordRefs.forEach { addAll(it.second) }
        attemptRefs.forEach { addAll(it.second) }
        artifacts.forEach { addAll(it.second) }
        jobs.forEach { addAll(it.assets) }
    }

    fun requireUnbound(id: String, owner: CoreAssetOwner) {
        require(CoreAssetReader.validAssetId(id) && CoreAssetReader.validOwner(owner)) { "Invalid asset or owner" }
        val bound = when (owner.ownerType) {
            "learning" -> recordRefs.any { (record, ids) -> record.id == owner.ownerId && id in ids } || attemptRefs.any { (attempt, ids) ->
                (attempt.submission.cardId == owner.ownerId || attempt.itemSnapshot.id == owner.ownerId) && id in ids
            }
            "artifact" -> artifacts.any { it.first == owner.ownerId && id in it.second }
            "imageGenerationJob" -> jobs.any { it.id == owner.ownerId && (it.active || id in it.assets) }
            // Unknown owner types may describe external bindings; do not guess that release is safe.
            else -> error("Unsupported asset owner type")
        }
        check(!bound) { "Asset is still bound to its owner" }
    }

    private fun parseArtifact(obj: JsonObject): Pair<String, Set<String>> {
        require(obj.keys.all { it in setOf("id", "title", "description", "revisions", "requestedDomains", "approvedDomains", "state", "stateVersion", "trustedOnDevice", "createdAt", "updatedAt") }) { "Unknown artifact fields" }
        require(obj.keys.containsAll(setOf("id", "title", "revisions", "createdAt", "updatedAt"))) { "Incomplete artifact" }
        val id = obj.getValue("id").jsonPrimitive.content
        java.util.UUID.fromString(id)
        val revisions = obj.getValue("revisions") as? JsonArray ?: error("Invalid artifact revisions")
        val ids = revisions.flatMap { value ->
            val revision = value.jsonObject
            require(revision.keys.all { it in setOf("revision", "files", "assets", "entry", "createdAt", "stateVersion") }) { "Unknown artifact revision fields" }
            require(revision.keys.containsAll(setOf("revision", "files", "entry", "createdAt"))) { "Incomplete artifact revision" }
            revision["assets"]?.jsonObject?.values?.map { it.jsonPrimitive.content }.orEmpty()
        }.toSet()
        require(ids.all(CoreAssetReader::validAssetId)) { "Invalid artifact asset ID" }
        return id to ids
    }

    private data class Job(val id: String, val active: Boolean, val assets: Set<String>)
    private fun parseJob(obj: JsonObject): Job {
        require(obj.keys.all { it in setOf("id", "request", "profile", "parameters", "status", "createdAt", "updatedAt", "assetIds", "error", "usage", "revisedPrompts") }) { "Unknown image job fields" }
        require(obj.keys.containsAll(setOf("id", "request", "profile", "parameters", "status", "createdAt", "updatedAt", "assetIds", "error", "usage"))) { "Incomplete image job" }
        val request = obj.getValue("request").jsonObject
        val profile = obj.getValue("profile").jsonObject
        require(request.keys.containsAll(setOf("requestId", "profileId", "prompt")) && request.keys.all { it in setOf("requestId", "profileId", "prompt", "parameters") } &&
            profile.keys.containsAll(setOf("id", "name", "providerId", "modelId")) && profile.keys.all { it in setOf("id", "name", "providerId", "modelId", "adapter", "enabled", "supportedParameters", "defaults") }) { "Unknown image job schema" }
        val status = obj.getValue("status").jsonPrimitive.content
        require(status in setOf("queued", "running", "succeeded", "failed", "cancelled", "unknown")) { "Unknown image job status" }
        val ids = (obj.getValue("assetIds") as JsonArray).map { it.jsonPrimitive.content }.toSet()
        require(ids.all(CoreAssetReader::validAssetId)) { "Invalid image job asset ID" }
        return Job(obj.getValue("id").jsonPrimitive.content, status == "queued" || status == "running", ids)
    }
}
