package cc.ptoe.messenger.domain.repository

import cc.ptoe.messenger.domain.model.*

interface LearningRepository {
    suspend fun list(kind: String, courseId: String? = null, search: String = "", offset: Int = 0, archived: Boolean? = false, knowledgePointId: String? = null, tag: String = ""): LearningPage
    suspend fun get(kind: String, id: String): LearningRecord
    suspend fun save(record: LearningRecord): LearningRecord
    suspend fun delete(record: LearningRecord)
    suspend fun overview(): LearningOverview
    suspend fun artifacts(): List<LearningArtifactReference>
    suspend fun due(): List<LearningRecord>
    suspend fun reviewState(cardId: String): LearningReviewState
    suspend fun attempts(cardId: String): List<LearningReviewAttempt>
    suspend fun submit(submission: LearningReviewSubmission): LearningReviewAttempt
    suspend fun assetBytes(assetId: String): ByteArray
    suspend fun assetMimeType(assetId: String): String
    suspend fun releaseAsset(ownerId: String, assetId: String)
    suspend fun importAsset(ownerId: String, mimeType: String, base64: String): String
    suspend fun captureImages(ownerId: String, images: List<MessageImage>): List<String>
    suspend fun exportData(): String
    suspend fun previewImport(bundle: String): String
    suspend fun importData(bundle: String): String
}
