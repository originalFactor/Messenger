/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository

import cc.ptoe.messenger.data.local.asset.CoreAssetOwner
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.asset.LearningBundleService
import cc.ptoe.messenger.data.local.room.LearningReviewDao
import cc.ptoe.messenger.domain.model.*
import cc.ptoe.messenger.domain.repository.LearningRepository
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Unconnected Kotlin implementation. Real exclusive gate and asset-aware record mutations remain required. */
internal class RoomLearningRepository(
    private val dao: LearningReviewDao,
    private val ownership: LearningOwnership,
    private val assets: CoreAssetService,
    private val packages: LearningBundleService,
    private val now: () -> Long,
) : LearningRepository {
    override suspend fun list(kind: String, courseId: String?, search: String, offset: Int, archived: Boolean?, knowledgePointId: String?, tag: String) =
        dao.listRecords(kind, courseId, search, offset, archived, knowledgePointId, tag)
    override suspend fun get(kind: String, id: String): LearningRecord = requireNotNull(dao.record(id)).also { require(it.kind == kind) }
    override suspend fun save(record: LearningRecord) = ownership.save(record, now())
    override suspend fun delete(record: LearningRecord) = ownership.delete(record, now())
    override suspend fun overview() = dao.overview(now())
    override suspend fun artifacts() = ownership.artifacts()
    override suspend fun due() = dao.dueRecords(now())
    override suspend fun reviewState(cardId: String): LearningReviewState {
        require(requireNotNull(dao.record(cardId)).kind == "reviewCard")
        return requireNotNull(dao.state(cardId))
    }
    override suspend fun attempts(cardId: String) = dao.attempts(cardId)
    override suspend fun submit(submission: LearningReviewSubmission) = assets.withExclusive { dao.submit(submission, now()) }
    override suspend fun assetBytes(assetId: String) = assets.read(assetId)
    override suspend fun assetMimeType(assetId: String) = assets.metadata(assetId).mimeType
    override suspend fun releaseAsset(ownerId: String, assetId: String) { assets.release(assetId, CoreAssetOwner("learning", ownerId), now()) }
    override suspend fun importAsset(ownerId: String, mimeType: String, base64: String): String {
        require(base64.length <= ((20L * 1024 * 1024 + 2) / 3 * 4)) { "Asset exceeds size limit" }
        val bytes = Base64.getDecoder().decode(base64)
        return assets.importFile(bytes, mimeType, CoreAssetOwner("learning", ownerId), now()).id
    }
    override suspend fun captureImages(ownerId: String, images: List<MessageImage>): List<String> = images.map { image ->
        val match = Regex("^data:(image/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=\\r\\n]+)$").matchEntire(image.dataUri)
            ?: error("Image snapshot has no supported embedded image data")
        importAsset(ownerId, match.groupValues[1], match.groupValues[2].replace("\r", "").replace("\n", ""))
    }
    override suspend fun exportData() = packages.export()
    override suspend fun previewImport(bundle: String): String = packages.preview(bundle).toJson()
    override suspend fun importData(bundle: String): String {
        val preview = packages.importBundle(bundle)
        return buildJsonObject {
            put("imported", true)
            put("preview", Json.parseToJsonElement(preview.toJson()))
        }.toString()
    }

    private fun cc.ptoe.messenger.data.local.asset.LearningBundlePreview.toJson() = buildJsonObject {
        put("formatVersion", 1); put("records", records); put("revisions", revisions); put("attempts", attempts)
        put("assetCount", assets); put("assetBytes", assetBytes); put("artifactCount", artifacts)
        put("externalResourcesIncluded", true)
        put("warnings", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("Imported applications require fresh execution and network permission."))
        })
    }.toString()
}
