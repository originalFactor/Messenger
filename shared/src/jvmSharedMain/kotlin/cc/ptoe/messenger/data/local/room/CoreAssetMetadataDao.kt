/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import cc.ptoe.messenger.data.local.asset.CoreAssetMetadata
import cc.ptoe.messenger.data.local.asset.CoreAssetOwner
import cc.ptoe.messenger.data.local.asset.AssetReferenceSnapshot
import cc.ptoe.messenger.data.local.asset.AssetReferenceAudit
import cc.ptoe.messenger.data.local.asset.CoreAssetReader
import cc.ptoe.messenger.data.local.asset.retainOwner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val assetJson = Json { encodeDefaults = true }

/** Caller holds the shared asset/domain gate and has verified immutable file bytes. */
@Dao
internal interface CoreAssetMetadataDao {
    @Query("SELECT value FROM kv WHERE `key` = :key") suspend fun metadata(key: String): String?
    @Query("SELECT value FROM kv WHERE substr(`key`, 1, 12) = 'local_asset:' ORDER BY `key`") suspend fun allMetadata(): List<String>
    @Query("SELECT * FROM learning_records") suspend fun learningRecords(): List<LearningRecordRow>
    @Query("SELECT * FROM learning_revisions") suspend fun learningRevisions(): List<LearningRevisionRow>
    @Query("SELECT * FROM learning_review_attempts") suspend fun learningAttempts(): List<LearningReviewAttemptRow>
    @Query("SELECT * FROM kv WHERE substr(`key`, 1, 9) = 'artifact:'") suspend fun artifacts(): List<KeyValueRow>
    @Query("SELECT * FROM kv WHERE substr(`key`, 1, 16) = 'local_image_job:'") suspend fun imageJobs(): List<KeyValueRow>
    @Transaction suspend fun referenceSnapshot() = AssetReferenceSnapshot(learningRecords(), learningRevisions(), learningAttempts(), artifacts(), imageJobs())
    @Query("SELECT * FROM kv WHERE substr(`key`, 1, 12) = 'local_asset:' ORDER BY `key`") suspend fun allMetadataEntries(): List<KeyValueRow>
    @Query("DELETE FROM kv WHERE `key` = :key") suspend fun deleteValue(key: String)
    @Upsert suspend fun writeMetadata(row: KeyValueRow)

    /** Caller verifies the backing bytes while holding the shared asset gate. */
    @Transaction
    suspend fun retainExisting(id: String, owner: CoreAssetOwner): CoreAssetMetadata {
        require(CoreAssetReader.validAssetId(id) && CoreAssetReader.validOwner(owner)) { "Invalid asset or owner" }
        val raw = metadata("local_asset:$id") ?: error("Asset not found")
        val asset = assetJson.decodeFromString<CoreAssetMetadata>(raw)
        require(asset.id == id && asset.sha256 == id && asset.size in 1..CoreAssetReader.MAX_BYTES &&
            CoreAssetReader.validMime(asset.mimeType) && asset.references.size <= 10_000 &&
            asset.references.all(CoreAssetReader::validOwner)) { "Invalid asset metadata" }
        val retained = asset.retainOwner(owner).copy(unreferencedAt = null)
        if (retained != asset) writeMetadata(KeyValueRow("local_asset:$id", assetJson.encodeToString(retained)))
        return retained
    }

    @Transaction
    suspend fun releaseOwner(id: String, owner: CoreAssetOwner, now: Long): CoreAssetMetadata {
        require(CoreAssetReader.validAssetId(id) && CoreAssetReader.validOwner(owner)) { "Invalid asset or owner" }
        val raw = metadata("local_asset:$id") ?: error("Asset not found")
        val asset = assetJson.decodeFromString<CoreAssetMetadata>(raw)
        require(asset.id == id && asset.sha256 == id && asset.references.all(CoreAssetReader::validOwner)) { "Invalid asset metadata" }
        AssetReferenceAudit(referenceSnapshot()).requireUnbound(id, owner)
        val kept = asset.references.filterNot { it == owner }
        val result = if (kept.size == asset.references.size) asset else asset.copy(references = kept, unreferencedAt = if (kept.isEmpty()) now else null)
        if (result != asset) writeMetadata(KeyValueRow("local_asset:$id", assetJson.encodeToString(result)))
        return result
    }

    @Transaction
    suspend fun gcCandidates(now: Long): List<CoreAssetMetadata> {
        val protected = AssetReferenceAudit(referenceSnapshot()).protectedAssetIds()
        val result = mutableListOf<CoreAssetMetadata>()
        for (row in allMetadataEntries()) {
            val asset = assetJson.decodeFromString<CoreAssetMetadata>(row.value)
            require(CoreAssetReader.validAssetId(asset.id) && row.key == "local_asset:${asset.id}" && asset.sha256 == asset.id &&
                asset.size in 0..CoreAssetReader.MAX_BYTES && CoreAssetReader.validMime(asset.mimeType) &&
                asset.references.size <= 10_000 && asset.references.all(CoreAssetReader::validOwner)) { "Invalid asset metadata" }
            val released = asset.unreferencedAt
            val oldEnough = released != null && now >= Long.MIN_VALUE + 86_400_000 && released <= now - 86_400_000
            if (asset.references.isEmpty() && oldEnough && asset.id !in protected) result.add(asset)
        }
        return result
    }

    @Transaction
    suspend fun retainOrInsert(proposed: CoreAssetMetadata, owner: CoreAssetOwner): CoreAssetMetadata {
        require(CoreAssetReader.validAssetId(proposed.id) && proposed.sha256 == proposed.id && proposed.size in 1..CoreAssetReader.MAX_BYTES &&
            CoreAssetReader.validMime(proposed.mimeType) && proposed.references.isEmpty() &&
            ((proposed.mimeType.startsWith("image/") && proposed.width != null && proposed.height != null &&
                proposed.width in 1..8192 && proposed.height in 1..8192 && proposed.width * proposed.height <= 32_000_000) ||
                (!proposed.mimeType.startsWith("image/") && proposed.width == null && proposed.height == null)) && proposed.unreferencedAt == null && CoreAssetReader.validOwner(owner)) { "Invalid asset metadata" }
        val key = "local_asset:${proposed.id}"
        val prior = metadata(key)?.let { assetJson.decodeFromString<CoreAssetMetadata>(it) }
        val result = if (prior != null) {
            require(prior.id == proposed.id && prior.sha256 == proposed.sha256 && prior.size == proposed.size &&
                prior.mimeType == proposed.mimeType && prior.width == proposed.width && prior.height == proposed.height && prior.references.size <= 10_000 && prior.references.all(CoreAssetReader::validOwner)) { "Existing asset metadata differs" }
            prior.retainOwner(owner)
        } else {
            val all = allMetadata().map { assetJson.decodeFromString<CoreAssetMetadata>(it) }
            require(all.size < 10_000 && all.all { it.size >= 0 && it.size <= 20L * 1024 * 1024 }) { "Invalid or full asset catalog" }
            val total = all.fold(0L) { n, asset -> Math.addExact(n, asset.size) }
            require(total <= 512L * 1024 * 1024 - proposed.size) { "Asset storage quota exceeded" }
            proposed.retainOwner(owner)
        }
        writeMetadata(KeyValueRow(key, assetJson.encodeToString(result)))
        return result
    }
}
