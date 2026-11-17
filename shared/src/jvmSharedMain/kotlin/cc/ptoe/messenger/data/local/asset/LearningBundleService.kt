/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import cc.ptoe.messenger.data.local.room.CoreAssetMetadataDao
import cc.ptoe.messenger.data.local.room.LearningBundleDao
import cc.ptoe.messenger.data.local.room.LearningPreviewRollback
import cc.ptoe.messenger.data.local.room.LearningResourceChange
import cc.ptoe.messenger.data.local.room.LearningContentWire
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.Base64
import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.domain.model.LearningReviewState
import cc.ptoe.messenger.domain.model.LearningReviewAttempt

internal data class LearningBundlePreview(val records: Int, val revisions: Int, val attempts: Int, val assets: Int, val assetBytes: Long, val artifacts: Int)

/** Unconnected package importer. A real shared gate must exclude every domain/asset writer and Rust. */
internal class LearningBundleService(
    private val root: Path, private val gate: CoreAssetGate, private val assets: CoreAssetMetadataDao,
    private val learning: LearningBundleDao,
) {
    private val json = Json { encodeDefaults = true; classDiscriminator = "kind" }

    suspend fun export(): String = gate.withExclusiveAssets {
        requireSafeStorage()
        val rows = learning.exportRows()
        // Corrupt row identifiers and unknown asset bindings must not be silently omitted.
        AssetReferenceAudit(AssetReferenceSnapshot(rows.records, rows.revisions, rows.attempts, rows.artifacts, rows.jobs))
        var total = 0L
        val bundledAssets = rows.assets.map { row ->
            val metadata = json.decodeFromString<CoreAssetMetadata>(row.value)
            require(row.key == "local_asset:${metadata.id}" && metadata.id == metadata.sha256) { "Invalid asset metadata" }
            total = Math.addExact(total, metadata.size)
            require(total <= 64L * 1024 * 1024) { "Learning package exceeds asset byte limit" }
            PortableAsset(metadata, Base64.getEncoder().encodeToString(CoreAssetReader(root).read(metadata.id, row.value)))
        }
        val assetIds = bundledAssets.map { it.metadata.id }.toSet()
        val artifacts = rows.artifacts.map { row ->
            val artifact = json.parseToJsonElement(row.value).jsonObject
            JsonObject(artifact + mapOf("approvedDomains" to JsonArray(emptyList()), "trustedOnDevice" to JsonPrimitive(false)))
        }
        val provenance = rows.provenance.associate { row ->
            val id = row.key.removePrefix("provenance:")
            require(id in assetIds) { "Provenance references a missing asset" }
            val entries = (json.parseToJsonElement(row.value) as JsonArray).map { it.jsonObject }
            require(entries.size <= 128) { "Provenance limit exceeded" }
            entries.forEach(LearningBundleValidation::validateProvenance)
            id to entries
        }
        val bundle = PortableLearningBundle(1, PortableLearning(1,
            rows.records.map { json.decodeFromString<LearningRecord>(it.body) },
            rows.revisions.map { json.decodeFromString<LearningRecord>(it.body) },
            rows.states.map { row -> json.decodeFromString<LearningReviewState>(row.body).also { require(it.cardId == row.card_id) { "Inconsistent review state" } } },
            rows.attempts.map { json.decodeFromString<LearningReviewAttempt>(it.body) }, false),
            bundledAssets, artifacts, provenance)
        val document = json.encodeToString(bundle)
        // Revalidate the complete exported document, including missing resource references.
        LearningBundleValidation.validate(document)
        document
    }

    suspend fun preview(document: String): LearningBundlePreview = gate.withExclusiveAssets {
        requireSafeStorage()
        val valid = LearningBundleValidation.validate(document)
        val changes = prepare(valid)
        previewTransaction(valid.bundle, changes)
        summary(valid)
    }

    suspend fun importBundle(document: String): LearningBundlePreview = gate.withExclusiveAssets {
        requireSafeStorage()
        val valid = LearningBundleValidation.validate(document)
        val changes = prepare(valid)
        // Detect every DB conflict before publishing files. The same transaction is repeated to commit.
        previewTransaction(valid.bundle, changes)
        val files = CoreAssetService(root, assets, gate)
        for (asset in valid.bundle.assets) files.stageVerifiedFile(asset.metadata, valid.bytes.getValue(asset.metadata.id))
        // Persist newly linked directory entries before committing metadata. Unsupported providers
        // fail before the transaction, leaving at most recoverable orphan files.
        syncAssetRoot(root)
        learning.importBundle(valid.bundle, changes, preview = false)
        summary(valid)
    }

    private fun requireSafeStorage() {
        requirePrivateAssetRoot(root)
    }

    private suspend fun previewTransaction(bundle: PortableLearningBundle, changes: List<LearningResourceChange>) {
        try { learning.importBundle(bundle, changes, preview = true); error("Preview did not roll back") }
        catch (_: LearningPreviewRollback) { /* expected: Room rolled the transaction back */ }
    }

    private fun summary(bundle: ValidatedLearningBundle) = LearningBundlePreview(bundle.bundle.learning.records.size,
        bundle.bundle.learning.revisions.size, bundle.bundle.learning.attempts.size, bundle.bundle.assets.size,
        bundle.bytes.values.sumOf { it.size.toLong() }, bundle.bundle.artifacts.size)

    private suspend fun prepare(valid: ValidatedLearningBundle): List<LearningResourceChange> {
        val bundle = valid.bundle
        val existing = assets.allMetadataEntries().associate { row ->
            val metadata = json.decodeFromString<CoreAssetMetadata>(row.value)
            require(CoreAssetReader.validAssetId(metadata.id) && row.key == "local_asset:${metadata.id}" && metadata.id == metadata.sha256 &&
                metadata.size in 0..CoreAssetReader.MAX_BYTES && CoreAssetReader.validMime(metadata.mimeType) &&
                metadata.references.size <= 10_000 && metadata.references.all(CoreAssetReader::validOwner)) { "Invalid existing asset metadata" }
            metadata.id to (row.value to metadata)
        }
        var count = existing.size
        var size = existing.values.fold(0L) { total, (_, asset) -> Math.addExact(total, asset.size) }
        val changes = mutableListOf<LearningResourceChange>()
        val incomingArtifactIds = bundle.artifacts.map { it.getValue("id").jsonPrimitive.content }.toSet()
        val incomingLearningIds = bundle.learning.records.map { it.id }.toSet()
        for (item in bundle.assets) {
            val incoming = item.metadata
            val prior = existing[incoming.id]
            val merged = if (prior == null) {
                count++; size = Math.addExact(size, incoming.size)
                incoming.copy(references = emptyList())
            } else {
                val stored = prior.second
                require(stored.sha256 == incoming.sha256 && stored.size == incoming.size && stored.mimeType == incoming.mimeType &&
                    stored.width == incoming.width && stored.height == incoming.height) { "Existing asset metadata conflicts" }
                CoreAssetReader(root).read(stored.id, prior.first)
                stored
            }
            var next = merged
            for (owner in incoming.references) {
                if ((owner.ownerType == "learning" && owner.ownerId in incomingLearningIds) ||
                    (owner.ownerType == "artifact" && owner.ownerId in incomingArtifactIds)) next = next.retainOwner(owner)
            }
            next = next.retainOwner(CoreAssetOwner("learningPackage", incoming.id))
            for (record in bundle.learning.records + bundle.learning.revisions) {
                val content = json.decodeFromJsonElement<LearningContentWire>(record.content)
                val referenced = incoming.id in record.source?.assetIds.orEmpty() || (content as? LearningContentWire.Material)?.assetId == incoming.id
                if (referenced && record.id in incomingLearningIds) next = next.retainOwner(CoreAssetOwner("learning", record.id))
            }
            for (artifact in bundle.artifacts) {
                val found = (artifact.getValue("revisions") as JsonArray).any { revision ->
                    revision.jsonObject["assets"]?.jsonObject?.values?.any { it.jsonPrimitive.content == incoming.id } == true
                }
                if (found) next = next.retainOwner(CoreAssetOwner("artifact", artifact.getValue("id").jsonPrimitive.content))
            }
            // Rust resets this even when an already present owner made retainOwner a no-op.
            next = next.copy(unreferencedAt = null)
            changes.add(LearningResourceChange("local_asset:${incoming.id}", prior?.first, json.encodeToString(next)))
        }
        require(count <= 10_000 && size <= 512L * 1024 * 1024) { "Asset catalog quota exceeded" }
        for (artifact in bundle.artifacts) {
            val key = "artifact:${artifact.getValue("id").jsonPrimitive.content}"
            check(learning.value(key) == null) { "Artifact already exists" }
            val sanitized = JsonObject(artifact + mapOf("approvedDomains" to JsonArray(emptyList()), "trustedOnDevice" to JsonPrimitive(false)))
            changes.add(LearningResourceChange(key, null, sanitized.toString()))
        }
        for ((id, incoming) in bundle.generationProvenance) {
            val key = "provenance:$id"
            val old = learning.value(key)
            val previous = old?.let { json.parseToJsonElement(it) as? JsonArray ?: error("Invalid stored provenance") }.orEmpty().map { it.jsonObject }
            previous.forEach(LearningBundleValidation::validateProvenance)
            val merged = (previous + incoming).distinct()
            require(merged.size <= 128) { "Provenance limit exceeded" }
            changes.add(LearningResourceChange(key, old, JsonArray(merged).toString()))
        }
        return changes
    }
}
