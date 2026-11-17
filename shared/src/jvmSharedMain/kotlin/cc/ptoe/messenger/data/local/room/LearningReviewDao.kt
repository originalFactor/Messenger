/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import androidx.room.Update
import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.domain.model.LearningPage
import cc.ptoe.messenger.domain.model.LearningOverview
import cc.ptoe.messenger.domain.model.LearningReviewAttempt
import cc.ptoe.messenger.domain.model.LearningReviewState
import cc.ptoe.messenger.domain.model.LearningReviewSubmission
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToLong

private val learningJson = Json { encodeDefaults = true; classDiscriminator = "kind" }
private const val DAY_MILLIS = 86_400_000L

/** SM-2 v1, using the persisted Core representation and UTC millisecond timestamps. */
internal fun scheduleLearningReview(old: LearningReviewState, rating: String, now: Long): LearningReviewState {
    val quality = when (rating) { "again" -> 1; "hard" -> 3; "good" -> 4; "easy" -> 5; else -> error("Invalid review rating") }
    require(old.version >= 0 && old.version < Long.MAX_VALUE && old.intervalDays in 0..36_500 &&
        old.repetitions >= 0 && old.ease.isFinite() && old.ease >= 1.3 && old.algorithm == "sm2-v1") {
        "Invalid review scheduler state"
    }
    val repetitions = if (quality < 3) 0L else if (old.repetitions == Long.MAX_VALUE) Long.MAX_VALUE else old.repetitions + 1
    val days = if (quality < 3) 1L else when (old.repetitions) {
        0L -> 1L
        1L -> 6L
        else -> (old.intervalDays.toDouble() * old.ease).coerceIn(1.0, 36_500.0).roundToLong()
    }
    val delta = 5 - quality
    val ease = (old.ease + 0.1 - delta * (0.08 + delta * 0.02)).coerceAtLeast(1.3)
    require(ease.isFinite()) { "Invalid review scheduler state" }
    val duration = days * DAY_MILLIS
    return old.copy(version = old.version + 1, intervalDays = days, repetitions = repetitions,
        ease = ease, dueAt = if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration,
        lastReviewedAt = now, algorithm = "sm2-v1")
}

/**
 * Unconnected migration foundation. Review submissions are atomic and use the same persisted
 * JSON as Core v9. This does not implement record writes, owned assets, or learning packages.
 */
@Dao
internal interface LearningReviewDao {
    @Query("SELECT body FROM learning_records WHERE id = :id") suspend fun recordBody(id: String): String?
    @Query("SELECT body FROM learning_review_states WHERE card_id = :id") suspend fun stateBody(id: String): String?
    @Query("SELECT body FROM learning_review_attempts WHERE submission_id = :id") suspend fun attemptBody(id: String): String?
    @Query("SELECT body FROM learning_review_attempts WHERE card_id = :cardId ORDER BY created_at DESC, submission_id LIMIT :limit OFFSET :offset")
    suspend fun attemptBodies(cardId: String, limit: Int, offset: Int): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM conversations WHERE id = :id)") suspend fun conversationExists(id: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :id AND conversationId = :conversationId)")
    suspend fun messageExists(id: String, conversationId: String): Boolean
    @Query("SELECT body FROM learning_records WHERE kind = :kind AND (:course IS NULL OR course_id = :course) AND (:archived IS NULL OR archived = :archived) AND (:search IS NULL OR instr(lower(body),lower(:search)) > 0) ORDER BY updated_at DESC, id")
    suspend fun recordBodies(kind: String, course: String?, archived: Boolean?, search: String?): List<String>
    @Query("SELECT body FROM learning_records") suspend fun allRecordBodies(): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM kv WHERE substr(`key`, 1, 12) = 'local_asset:')") suspend fun hasOwnedAssets(): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM learning_revisions WHERE item_id = :id)") suspend fun hasRevision(id: String): Boolean
    @Query("SELECT body FROM learning_revisions WHERE item_id = :id") suspend fun revisionBodies(id: String): List<String>
    @Upsert suspend fun writeRecord(row: LearningRecordRow)
    @Insert suspend fun insertRevision(row: LearningRevisionRow)
    @Insert suspend fun insertState(row: LearningReviewStateRow)
    @Query("DELETE FROM learning_revisions WHERE item_id = :id") suspend fun deleteRevisions(id: String)
    @Query("DELETE FROM learning_records WHERE id = :id") suspend fun deleteRecordRow(id: String)
    @Update suspend fun updateState(row: LearningReviewStateRow): Int
    @Insert suspend fun insertAttempt(row: LearningReviewAttemptRow)

    @Transaction
    suspend fun record(id: String): LearningRecord? {
        val record = recordBody(id)?.let { learningJson.decodeFromString<LearningRecord>(it) } ?: return null
        val source = record.source ?: return record
        if (source.kind != "chat") return record
        val conversation = source.conversationId
        if (conversation != null && !conversationExists(conversation)) {
            return record.copy(source = source.copy(conversationId = null, messageIds = emptyList()))
        }
        val retained = if (conversation == null) emptyList() else source.messageIds.filter { messageExists(it, conversation) }
        return record.copy(source = source.copy(messageIds = retained))
    }

    @Transaction
    suspend fun listRecords(kind: String, courseId: String? = null, search: String = "", offset: Int = 0,
                            archived: Boolean? = false, knowledgePointId: String? = null, tag: String = "", limit: Int = 50,
                            dueBefore: Long? = null): LearningPage {
        require(kind in setOf("course", "knowledgePoint", "item", "material", "plan", "task", "reviewCard")) { "Invalid learning kind" }
        val records = recordBodies(kind, courseId, archived, search.takeIf { it.isNotBlank() })
            .map { learningJson.decodeFromString<LearningRecord>(it) }
            .filter { (knowledgePointId == null || knowledgePointId in it.knowledgePointIds) && (tag.isBlank() || tag in it.tags) }
        val due = if (dueBefore == null || kind != "reviewCard") emptyMap() else records.mapNotNull { record ->
            val paused = record.content["paused"]?.jsonPrimitive?.booleanOrNull ?: false
            val state = state(record.id)
            if (!record.archived && !paused && state != null && state.dueAt <= dueBefore) record.id to state.dueAt else null
        }.toMap()
        val filtered = if (dueBefore == null) records else records.filter { it.id in due }
        val sorted = if (dueBefore != null) filtered.sortedWith(compareBy({ due[it.id] }, { it.id }))
        else if (kind == "task" || kind == "knowledgePoint") {
            filtered.sortedWith(compareBy({ it.content["position"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0 }, { it.id }))
        } else filtered
        val start = offset.coerceAtLeast(0)
        val pageSize = limit.coerceIn(1, 200)
        val page = sorted.drop(start).take(pageSize).map { requireNotNull(record(it.id)) }
        return LearningPage(page, sorted.size, start, pageSize)
    }

    /** Asset ownership must be implemented before these operations can be connected to the UI. */
    @Transaction
    suspend fun saveRecord(input: LearningRecord, now: Long, assetOwnershipManaged: Boolean = false): LearningRecord {
        check(assetOwnershipManaged || !hasOwnedAssets()) { "Learning writes require the owned asset service" }
        val idBytes = input.id.encodeToByteArray().size
        require(input.id.isNotBlank() && idBytes <= 128 && input.id.none { it.isISOControl() } &&
            input.title.isNotBlank() && input.title.encodeToByteArray().size <= 1024 && input.version in 0 until Long.MAX_VALUE &&
            input.tags.size <= 64 && input.tags.all { it.isNotBlank() && it.encodeToByteArray().size <= 128 } &&
            input.knowledgePointIds.size <= 128 && learningJson.encodeToString(input).encodeToByteArray().size <= 1024 * 1024) { "Invalid learning record" }
        require(input.source == null || input.source.kind in setOf("manual", "chat", "acp", "import")) { "Invalid source kind" }
        check(assetOwnershipManaged || input.source?.assetIds.isNullOrEmpty()) { "Learning writes require the owned asset service" }
        val content = learningJson.decodeFromJsonElement<LearningContentWire>(input.content)
        if (content is LearningContentWire.Material) {
            check(assetOwnershipManaged || (content.assetId == null && content.artifactId == null && content.materialKind !in setOf(MaterialKind.IMAGE, MaterialKind.FILE, MaterialKind.ARTIFACT))) {
                "Learning writes require the owned asset and artifact services"
            }
            if (content.materialKind in setOf(MaterialKind.IMAGE, MaterialKind.FILE)) require(!content.assetId.isNullOrBlank()) { "Missing asset" }
            if (content.materialKind == MaterialKind.ARTIFACT) require(!content.artifactId.isNullOrBlank()) { "Missing artifact" }
            require(content.materialKind != MaterialKind.LINK || content.url?.let { it.startsWith("http://") || it.startsWith("https://") } == true) { "Invalid link" }
        }
        suspend fun requireKind(id: String, kind: String): LearningRecord {
            val found = record(id) ?: error("Learning reference not found")
            require(found.kind == kind) { "Reference has incorrect learning kind" }
            return found
        }
        input.courseId?.let { requireKind(it, "course") }
        for (pointId in input.knowledgePointIds) {
            require(requireKind(pointId, "knowledgePoint").courseId == input.courseId) { "Knowledge point belongs to another course" }
        }
        when (content) {
            is LearningContentWire.Course -> require(input.courseId == null && input.knowledgePointIds.isEmpty()) { "Course cannot have a parent" }
            is LearningContentWire.KnowledgePoint -> {
                require(input.courseId != null) { "Knowledge point requires a course" }
                var parent = content.parentId
                val seen = mutableSetOf<String>()
                while (parent != null) {
                    require(parent != input.id && seen.add(parent)) { "Knowledge point cycle" }
                    val point = requireKind(parent, "knowledgePoint")
                    require(point.courseId == input.courseId) { "Parent belongs to another course" }
                    val pointContent = learningJson.decodeFromJsonElement<LearningContentWire>(point.content)
                    parent = (pointContent as LearningContentWire.KnowledgePoint).parentId
                }
            }
            is LearningContentWire.Task -> {
                require(requireKind(content.planId, "plan").courseId == input.courseId) { "Task and plan courses differ" }
                content.itemId?.let { requireKind(it, "item") }
            }
            is LearningContentWire.ReviewCard -> require(requireKind(content.itemId, "item").courseId == input.courseId) { "Card and item courses differ" }
            else -> Unit
        }
        val old = record(input.id)
        check(old != null || !hasRevision(input.id)) { "Record ID was already used" }
        check((old?.version ?: 0) == input.version) { "Learning record version changed" }
        if (old != null) {
            require(old.kind == input.kind) { "Record kind cannot change" }
            if (content is LearningContentWire.ReviewCard) require(old.field("itemId") == content.itemId) { "Review card item cannot change" }
            if (old.courseId != input.courseId) check(!hasDependents(input.id)) { "Record is referenced" }
            check(assetOwnershipManaged || old.source?.assetIds.isNullOrEmpty()) { "Learning writes require the owned asset service" }
            if (!assetOwnershipManaged && old.kind == "material") check(old.field("assetId").isEmpty() && old.field("artifactId").isEmpty()) { "Learning writes require the owned asset service" }
        }
        var source = input.source
        if (source?.kind == "chat" && source.conversationId != null && !conversationExists(source.conversationId)) {
            source = source.copy(conversationId = null, messageIds = emptyList())
        }
        val saved = input.copy(version = input.version + 1, createdAt = old?.createdAt ?: now, updatedAt = now,
            source = source, content = learningJson.encodeToJsonElement<LearningContentWire>(content).jsonObject)
        val body = learningJson.encodeToString(saved)
        writeRecord(LearningRecordRow(saved.id, saved.kind, saved.version, saved.courseId, saved.title, saved.archived, saved.updatedAt, body))
        insertRevision(LearningRevisionRow(saved.id, saved.version, body))
        if (old == null && content is LearningContentWire.ReviewCard) {
            val initial = LearningReviewState(saved.id, 0, 0, 0, 2.5, now, null, "sm2-v1")
            insertState(LearningReviewStateRow(saved.id, learningJson.encodeToString(initial)))
        }
        return saved
    }

    @Transaction
    suspend fun removeRecord(id: String, version: Long, assetOwnershipManaged: Boolean = false) {
        check(assetOwnershipManaged || !hasOwnedAssets()) { "Learning deletion requires the owned asset service" }
        val old = record(id) ?: error("Learning record not found")
        check(old.version == version) { "Learning record version changed" }
        check(assetOwnershipManaged || (old.source?.assetIds.isNullOrEmpty() && (old.kind != "material" || (old.field("assetId").isEmpty() && old.field("artifactId").isEmpty())))) {
            "Learning deletion requires the owned asset service"
        }
        check(assetOwnershipManaged || revisionBodies(id).none { body ->
            val revision = learningJson.decodeFromString<LearningRecord>(body)
            !revision.source?.assetIds.isNullOrEmpty() ||
                (revision.kind == "material" && (revision.field("assetId").isNotEmpty() || revision.field("artifactId").isNotEmpty()))
        }) { "Learning deletion requires the owned asset service" }
        check(!hasDependents(id)) { "Record is referenced" }
        deleteRevisions(id)
        deleteRecordRow(id)
    }

    private suspend fun hasDependents(id: String): Boolean = allRecordBodies().any { body ->
        val candidate = learningJson.decodeFromString<LearningRecord>(body)
        candidate.id != id && (candidate.courseId == id || id in candidate.knowledgePointIds ||
            (candidate.kind == "knowledgePoint" && candidate.field("parentId") == id) ||
            (candidate.kind == "task" && (candidate.field("planId") == id || candidate.field("itemId") == id)) ||
            (candidate.kind == "reviewCard" && candidate.field("itemId") == id))
    }

    @Transaction
    suspend fun dueRecords(now: Long, limit: Int = 200): List<LearningRecord> {
        val cards = recordBodies("reviewCard", null, false, null).map { learningJson.decodeFromString<LearningRecord>(it) }
        val due = mutableListOf<Pair<LearningRecord, Long>>()
        for (card in cards) {
            val paused = card.content["paused"]?.jsonPrimitive?.booleanOrNull ?: false
            val schedule = state(card.id)
            if (!paused && schedule != null && schedule.dueAt <= now) due.add(card to schedule.dueAt)
        }
        return due.sortedWith(compareBy({ it.second }, { it.first.id })).take(limit.coerceAtLeast(0)).map { requireNotNull(record(it.first.id)) }
    }

    @Query("SELECT COUNT(*) FROM learning_review_attempts") suspend fun attemptCount(): Int

    @Transaction
    suspend fun overview(now: Long): LearningOverview {
        val all = allRecordBodies().map { learningJson.decodeFromString<LearningRecord>(it) }.filterNot { it.archived }
        val tasks = all.filter { it.kind == "task" }
        return LearningOverview(courses = all.count { it.kind == "course" }, items = all.count { it.kind == "item" },
            materials = all.count { it.kind == "material" }, dueReviews = dueRecords(now, Int.MAX_VALUE).size,
            pendingTasks = tasks.count { it.field("status") != "completed" }, completedTasks = tasks.count { it.field("status") == "completed" },
            attempts = attemptCount(), localOnly = true)
    }

    suspend fun state(cardId: String): LearningReviewState? =
        stateBody(cardId)?.let { learningJson.decodeFromString<LearningReviewState>(it) }

    suspend fun attempts(cardId: String, limit: Int = 50, offset: Int = 0): List<LearningReviewAttempt> =
        attemptBodies(cardId, limit.coerceIn(1, 200), offset.coerceAtLeast(0))
            .map { learningJson.decodeFromString<LearningReviewAttempt>(it) }

    @Transaction
    suspend fun submit(submission: LearningReviewSubmission, now: Long): LearningReviewAttempt {
        require(submission.submissionId.isNotBlank() && submission.submissionId.encodeToByteArray().size <= 128 &&
            submission.answer.encodeToByteArray().size <= 1024 * 1024 && submission.rating in setOf("again", "hard", "good", "easy")) {
            "Invalid review submission"
        }
        attemptBody(submission.submissionId)?.let { body ->
            val existing = learningJson.decodeFromString<LearningReviewAttempt>(body)
            check(existing.submission == submission) { "Submission ID already used for different input" }
            return existing
        }
        val card = record(submission.cardId) ?: error("Review card not found")
        check(card.kind == "reviewCard") { "Reference has incorrect learning kind" }
        val paused = card.content["paused"]?.jsonPrimitive?.booleanOrNull ?: false
        check(!card.archived && !paused) { "Review card is paused or archived" }
        val item = record(card.field("itemId")) ?: error("Review item not found")
        check(item.kind == "item") { "Reference has incorrect learning kind" }
        val old = state(card.id) ?: error("Review state not found")
        check(item.version == submission.itemVersion && old.version == submission.stateVersion) { "Item or review state version changed" }
        check(old.cardId == card.id) { "Invalid review state card" }
        val next = scheduleLearningReview(old, submission.rating, now)
        val attempt = LearningReviewAttempt(submission, now, item, next)
        check(updateState(LearningReviewStateRow(card.id, learningJson.encodeToString(next))) == 1) { "Review state not found" }
        insertAttempt(LearningReviewAttemptRow(submission.submissionId, card.id, now, learningJson.encodeToString(attempt)))
        return attempt
    }
}
