/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import cc.ptoe.messenger.data.local.asset.PortableLearningBundle
import cc.ptoe.messenger.domain.model.LearningRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

internal data class LearningExportRows(
    val records: List<LearningRecordRow>, val revisions: List<LearningRevisionRow>, val states: List<LearningReviewStateRow>,
    val attempts: List<LearningReviewAttemptRow>, val assets: List<KeyValueRow>, val artifacts: List<KeyValueRow>,
    val jobs: List<KeyValueRow>, val provenance: List<KeyValueRow>,
)
internal data class LearningResourceChange(val key: String, val expected: String?, val value: String)
internal class LearningPreviewRollback : RuntimeException()
private val bundleJson = Json { encodeDefaults = true; classDiscriminator = "kind" }

/** Transactional import primitives. Caller must hold the shared domain/asset gate. */
@Dao
internal interface LearningBundleDao {
    @Query("SELECT body FROM learning_records WHERE id = :id") suspend fun body(id: String): String?
    @Query("SELECT value FROM kv WHERE `key` = :key") suspend fun value(key: String): String?
    @Query("SELECT * FROM learning_records ORDER BY id") suspend fun allRecords(): List<LearningRecordRow>
    @Query("SELECT * FROM learning_revisions ORDER BY item_id, version") suspend fun allRevisions(): List<LearningRevisionRow>
    @Query("SELECT * FROM learning_review_states ORDER BY card_id") suspend fun allStates(): List<LearningReviewStateRow>
    @Query("SELECT * FROM learning_review_attempts ORDER BY created_at, submission_id") suspend fun allAttempts(): List<LearningReviewAttemptRow>
    @Query("SELECT * FROM kv WHERE substr(`key`,1,12) = 'local_asset:' ORDER BY `key`") suspend fun allAssets(): List<KeyValueRow>
    @Query("SELECT * FROM kv WHERE substr(`key`,1,9) = 'artifact:' ORDER BY `key`") suspend fun allArtifacts(): List<KeyValueRow>
    @Query("SELECT * FROM kv WHERE substr(`key`,1,16) = 'local_image_job:' ORDER BY `key`") suspend fun allJobs(): List<KeyValueRow>
    @Query("SELECT * FROM kv WHERE substr(`key`,1,11) = 'provenance:' ORDER BY `key`") suspend fun allProvenance(): List<KeyValueRow>
    @Transaction suspend fun exportRows() = LearningExportRows(allRecords(), allRevisions(), allStates(), allAttempts(), allAssets(), allArtifacts(), allJobs(), allProvenance())
    @Insert suspend fun insertRecords(rows: List<LearningRecordRow>)
    @Insert suspend fun insertRevisions(rows: List<LearningRevisionRow>)
    @Insert suspend fun insertStates(rows: List<LearningReviewStateRow>)
    @Insert suspend fun insertAttempts(rows: List<LearningReviewAttemptRow>)
    @Upsert suspend fun upsertValues(rows: List<KeyValueRow>)

    /** Preview runs exactly these writes and validation, then throws to force Room rollback. */
    @Transaction
    suspend fun importBundle(bundle: PortableLearningBundle, changes: List<LearningResourceChange>, preview: Boolean) {
        require(changes.map { it.key }.toSet().size == changes.size) { "Duplicate resource metadata" }
        for (change in changes) check(value(change.key) == change.expected) { "Resource changed during import" }
        val learning = bundle.learning
        for (record in learning.records) check(body(record.id) == null) { "Learning record already exists" }
        insertRecords(learning.records.map { record ->
            LearningRecordRow(record.id, record.kind, record.version, record.courseId, record.title, record.archived, record.updatedAt, bundleJson.encodeToString(record))
        })
        for (record in learning.records) validateRecord(record)
        insertRevisions(learning.revisions.map { LearningRevisionRow(it.id, it.version, bundleJson.encodeToString(it)) })
        insertStates(learning.reviewStates.map { LearningReviewStateRow(it.cardId, bundleJson.encodeToString(it)) })
        insertAttempts(learning.attempts.map { LearningReviewAttemptRow(it.submission.submissionId, it.submission.cardId, it.createdAt, bundleJson.encodeToString(it)) })
        upsertValues(changes.map { KeyValueRow(it.key, it.value) })
        if (preview) throw LearningPreviewRollback()
    }

    private suspend fun validateRecord(record: LearningRecord) {
        require(record.id.isNotBlank() && record.id.encodeToByteArray().size <= 128 && record.id.none(Char::isISOControl) &&
            record.title.isNotBlank() && record.title.encodeToByteArray().size <= 1024 && record.version in 1 until Long.MAX_VALUE &&
            record.tags.size <= 64 && record.tags.all { it.isNotBlank() && it.encodeToByteArray().size <= 128 } && record.knowledgePointIds.size <= 128 &&
            bundleJson.encodeToString(record).encodeToByteArray().size <= 1024 * 1024) { "Invalid learning record" }
        require(record.source == null || record.source.kind in setOf("manual", "chat", "acp", "import")) { "Invalid learning source" }
        suspend fun requireKind(id: String, kind: String): LearningRecord {
            val found = body(id)?.let { bundleJson.decodeFromString<LearningRecord>(it) } ?: error("Learning reference not found")
            require(found.kind == kind) { "Incorrect learning reference kind" }
            return found
        }
        record.courseId?.let { requireKind(it, "course") }
        for (id in record.knowledgePointIds) require(requireKind(id, "knowledgePoint").courseId == record.courseId) { "Knowledge point belongs to another course" }
        when (val content = bundleJson.decodeFromJsonElement<LearningContentWire>(record.content)) {
            is LearningContentWire.Course -> require(record.courseId == null && record.knowledgePointIds.isEmpty()) { "Course cannot have a parent" }
            is LearningContentWire.KnowledgePoint -> {
                require(record.courseId != null) { "Knowledge point needs a course" }
                var parent = content.parentId
                val seen = mutableSetOf<String>()
                while (parent != null) {
                    require(parent != record.id && seen.add(parent)) { "Knowledge point cycle" }
                    val point = requireKind(parent, "knowledgePoint")
                    require(point.courseId == record.courseId) { "Parent belongs to another course" }
                    parent = (bundleJson.decodeFromJsonElement<LearningContentWire>(point.content) as LearningContentWire.KnowledgePoint).parentId
                }
            }
            is LearningContentWire.Task -> {
                require(requireKind(content.planId, "plan").courseId == record.courseId) { "Task and plan courses differ" }
                content.itemId?.let { requireKind(it, "item") }
            }
            is LearningContentWire.ReviewCard -> require(requireKind(content.itemId, "item").courseId == record.courseId) { "Card and item courses differ" }
            is LearningContentWire.Material -> when (content.materialKind) {
                MaterialKind.LINK -> require(content.url?.let { it.startsWith("https://") || it.startsWith("http://") } == true) { "Invalid link" }
                MaterialKind.IMAGE, MaterialKind.FILE -> require(!content.assetId.isNullOrBlank()) { "Missing asset" }
                MaterialKind.ARTIFACT -> require(!content.artifactId.isNullOrBlank()) { "Missing artifact" }
                else -> Unit
            }
            else -> Unit
        }
    }
}
