package cc.ptoe.messenger.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Core owns validation and version increments; snapshots survive their source conversation. */
@Serializable
data class LearningRecord(
    val id: String,
    val version: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val title: String,
    val courseId: String? = null,
    val knowledgePointIds: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val archived: Boolean = false,
    val source: LearningSource? = null,
    val content: JsonObject,
) {
    val kind: String get() = field("kind")
    fun field(name: String): String = content[name]?.jsonPrimitive?.contentOrNull.orEmpty()
    fun withField(name: String, value: String) = copy(content = JsonObject(content + (name to JsonPrimitive(value))))
}

@Serializable
data class LearningSource(
    val kind: String = "manual",
    val conversationId: String? = null,
    val messageIds: List<String> = emptyList(),
    val snapshot: String,
    val assetIds: List<String> = emptyList(),
)

@Serializable
data class LearningArtifactReference(val id: String, val title: String)

@Serializable
data class LearningPage(val items: List<LearningRecord>, val total: Int, val offset: Int = 0, val limit: Int = 50)

@Serializable
data class LearningOverview(
    val courses: Int = 0, val items: Int = 0, val materials: Int = 0,
    val dueReviews: Int = 0, val pendingTasks: Int = 0, val completedTasks: Int = 0,
    val attempts: Int = 0, val localOnly: Boolean = true,
)

@Serializable
data class LearningReviewState(
    val cardId: String, val version: Long, val intervalDays: Long, val repetitions: Long,
    val ease: Double, val dueAt: Long, val lastReviewedAt: Long? = null, val algorithm: String,
)

@Serializable
data class LearningReviewSubmission(
    val submissionId: String, val cardId: String, val itemVersion: Long, val stateVersion: Long,
    val answer: String, val rating: String,
)

@Serializable
data class LearningReviewAttempt(
    val submission: LearningReviewSubmission, val createdAt: Long,
    val itemSnapshot: LearningRecord, val state: LearningReviewState,
)
