package cc.ptoe.messenger.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.domain.model.*
import cc.ptoe.messenger.domain.repository.LearningRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.reflect.KClass

fun learningId(): String = "learning-${currentTimeMillis()}-${Random.nextLong().toULong().toString(16)}"

data class LearningUiState(
    val tab: String = "today", val page: LearningPage = LearningPage(emptyList(), 0),
    val courses: List<LearningRecord> = emptyList(), val points: List<LearningRecord> = emptyList(),
    val plans: List<LearningRecord> = emptyList(), val overview: LearningOverview = LearningOverview(),
    val artifacts: List<LearningArtifactReference> = emptyList(),
    val courseId: String? = null, val search: String = "", val showArchived: Boolean = false,
    val knowledgePointId: String? = null, val tag: String = "",
    val selected: LearningRecord? = null, val editing: LearningRecord? = null,
    val busy: Boolean = false, val error: String? = null,
    val reviewCard: LearningRecord? = null, val reviewItem: LearningRecord? = null,
    val reviewState: LearningReviewState? = null, val attempts: List<LearningReviewAttempt> = emptyList(),
    val answer: String = "", val revealed: Boolean = false, val submissionId: String = "",
    val transfer: String? = null, val importPreview: String? = null,
)

class LearningViewModel(private val repository: LearningRepository) : ViewModel() {
    private val mutable = MutableStateFlow(LearningUiState())
    private val draftAssets = mutableMapOf<String, MutableSet<String>>()
    val state = mutable.asStateFlow()
    init { refresh() }
    private fun request(action: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(error = e.message ?: e.toString()) }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    private suspend fun all(kind: String): List<LearningRecord> {
        val records = mutableListOf<LearningRecord>()
        do { val page = repository.list(kind, archived = null, offset = records.size); records += page.items }
        while (records.size < page.total && page.items.isNotEmpty())
        return records
    }
    private suspend fun reload() {
        val s = mutable.value
        val courses = all("course"); val plans = all("plan"); val points = all("knowledgePoint")
        val overview = repository.overview()
        val artifacts = repository.artifacts()
        val page = when (s.tab) {
            "today" -> repository.due().let { LearningPage(it, it.size) }
            "media" -> LearningPage(emptyList(), 0)
            else -> repository.list(s.tab, s.courseId, s.search, archived = s.showArchived, knowledgePointId = s.knowledgePointId, tag = s.tag)
        }
        mutable.value = mutable.value.copy(courses = courses, plans = plans, points = points, overview = overview, page = page, artifacts = artifacts)
    }
    private suspend fun releaseDraftAssets() {
        for ((ownerId, assetIds) in draftAssets.toMap()) {
            for (assetId in assetIds.toList()) {
                repository.releaseAsset(ownerId, assetId)
                assetIds.remove(assetId)
            }
            draftAssets.remove(ownerId)
        }
    }
    fun discardCapture(onDone: () -> Unit) = request {
        releaseDraftAssets()
        mutable.value = mutable.value.copy(editing = null)
        onDone()
    }
    fun capture(messages: List<Message>) = request {
        releaseDraftAssets()
        val id = learningId()
        val images = messages.flatMap { message -> message.parts.filterIsInstance<ContentPart.Image>().map { it.image } }
        val assets = images.map { image ->
            repository.captureImages(id, listOf(image)).single().also { draftAssets.getOrPut(id) { mutableSetOf() }.add(it) }
        }
        val snapshot = messages.joinToString("\n\n") { it.content }
        val first = messages.first()
        val source = LearningSource(if (first.conversationId.startsWith("acp:")) "acp" else "chat", first.conversationId, messages.map { it.id }, snapshot, assets)
        mutable.value = mutable.value.copy(editing = LearningRecord(id, title = first.content.lineSequence().firstOrNull()?.take(100).orEmpty(), source = source,
            content = buildJsonObject { put("kind", "item"); put("itemKind", "mistake"); put("question", first.content)
                if (messages.size > 1) put("explanation", messages.drop(1).joinToString("\n\n") { it.content }) }))
    }
    fun importMaterial(bytes: ByteArray, mimeType: String, name: String) = request {
        val record = requireNotNull(mutable.value.editing)
        val assetId = repository.importAsset(record.id, mimeType, kotlin.io.encoding.Base64.Default.encode(bytes))
        if (record.version == 0L || (record.field("assetId") != assetId && assetId !in record.source?.assetIds.orEmpty())) {
            draftAssets.getOrPut(record.id) { mutableSetOf() }.add(assetId)
        }
        mutable.value = mutable.value.copy(editing = record.withField("assetId", assetId).let { if (it.title.isBlank()) it.copy(title = name) else it })
    }
    fun reportError(message: String) { mutable.value = mutable.value.copy(error = message) }
    fun refresh() = request { reload() }
    fun selectTab(tab: String) { if (mutable.value.busy) return; mutable.value = mutable.value.copy(tab = tab, selected = null, knowledgePointId = null, tag = "", search = "", courseId = if (tab == "course") null else mutable.value.courseId); refresh() }
    fun filter(courseId: String?, search: String, archived: Boolean, knowledgePointId: String? = mutable.value.knowledgePointId, tag: String = mutable.value.tag) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(courseId = courseId, search = search, showArchived = archived, selected = null,
            knowledgePointId = knowledgePointId.takeIf { courseId == mutable.value.courseId }, tag = tag); refresh()
    }
    fun more() = request {
        val s = mutable.value
        val page = repository.list(s.tab, s.courseId, s.search, s.page.items.size, s.showArchived, s.knowledgePointId, s.tag)
        mutable.value = mutable.value.copy(page = page.copy(items = s.page.items + page.items))
    }
    /** Explicit retargeting is safe when the adaptive detail pane reuses this ViewModel. */
    fun loadRecord(id: String?) {
        mutable.value = mutable.value.copy(selected = mutable.value.page.items.find { it.id == id })
    }
    fun edit(record: LearningRecord) { if (!mutable.value.busy) mutable.value = mutable.value.copy(editing = record) }
    fun draft(record: LearningRecord) { if (!mutable.value.busy) mutable.value = mutable.value.copy(editing = record) }
    fun closeEditor() = request {
        releaseDraftAssets()
        mutable.value = mutable.value.copy(editing = null)
    }
    fun create(kind: String) {
        val fields = when (kind) {
            "course" -> mapOf("description" to JsonPrimitive(""))
            "knowledgePoint" -> mapOf("description" to JsonPrimitive(""), "position" to JsonPrimitive(0))
            "item" -> mapOf("itemKind" to JsonPrimitive("mistake"), "question" to JsonPrimitive(""))
            "material" -> mapOf("materialKind" to JsonPrimitive("text"), "text" to JsonPrimitive(""))
            "plan" -> mapOf("goal" to JsonPrimitive(""))
            "task" -> mapOf("planId" to JsonPrimitive(mutable.value.plans.firstOrNull { it.courseId == mutable.value.courseId && !it.archived }?.id.orEmpty()), "status" to JsonPrimitive("pending"), "position" to JsonPrimitive(0))
            else -> emptyMap()
        }
        edit(LearningRecord(learningId(), title = "", courseId = if (kind == "course") null else mutable.value.courseId, content = JsonObject(fields + ("kind" to JsonPrimitive(kind)))))
    }
    fun save(record: LearningRecord) = request {
        val saved = repository.save(record)
        val retained = saved.source?.assetIds.orEmpty().toSet() + saved.field("assetId").takeIf(String::isNotBlank).orEmpty()
        draftAssets[saved.id]?.removeAll(retained)
        mutable.value = mutable.value.copy(editing = null, selected = saved)
        releaseDraftAssets()
        reload()
    }
    fun delete(record: LearningRecord) = request {
        repository.delete(record); mutable.value = mutable.value.copy(selected = null); reload()
    }
    fun archive(record: LearningRecord) = save(record.copy(archived = !record.archived))
    fun complete(record: LearningRecord) = save(record.withField("status", if (record.field("status") == "completed") "pending" else "completed"))
    fun addReview(item: LearningRecord) = request {
        repository.save(LearningRecord(learningId(), title = item.title, courseId = item.courseId,
            content = buildJsonObject { put("kind", "reviewCard"); put("itemId", item.id); put("paused", false) }))
        reload()
    }
    fun pause(card: LearningRecord) = save(card.copy(content = JsonObject(card.content + ("paused" to JsonPrimitive(card.field("paused") != "true")))))
    fun startReview(card: LearningRecord) = request {
        val item = repository.get("item", card.field("itemId")); val reviewState = repository.reviewState(card.id)
        val attempts = repository.attempts(card.id)
        mutable.value = mutable.value.copy(reviewCard = card, reviewItem = item, reviewState = reviewState,
            attempts = attempts, answer = "", revealed = false, submissionId = learningId())
    }
    fun answer(text: String) { mutable.value = mutable.value.copy(answer = text) }
    fun reveal() { mutable.value = mutable.value.copy(revealed = true) }
    fun stopReview() { if (!mutable.value.busy) mutable.value = mutable.value.copy(reviewCard = null, reviewItem = null, reviewState = null) }
    fun rate(rating: String) = request {
        val s = mutable.value
        val card = requireNotNull(s.reviewCard); val item = requireNotNull(s.reviewItem); val reviewState = requireNotNull(s.reviewState)
        repository.submit(LearningReviewSubmission(s.submissionId, card.id, item.version, reviewState.version, s.answer, rating))
        mutable.value = mutable.value.copy(reviewCard = null, reviewItem = null, reviewState = null)
        reload()
    }
    fun exportMaterial(record: LearningRecord, export: (ByteArray, String) -> Unit) = request {
        val id = record.field("assetId")
        val mime = repository.assetMimeType(id)
        val bytes = repository.assetBytes(id)
        export(bytes, mime)
    }
    fun exportData() = request { mutable.value = mutable.value.copy(transfer = repository.exportData(), importPreview = null) }
    fun transfer(text: String) { mutable.value = mutable.value.copy(transfer = text, importPreview = null) }
    fun closeTransfer() { if (!mutable.value.busy) mutable.value = mutable.value.copy(transfer = null, importPreview = null) }
    fun previewImport() = request { mutable.value = mutable.value.copy(importPreview = repository.previewImport(requireNotNull(mutable.value.transfer))) }
    fun importData() = request {
        repository.importData(requireNotNull(mutable.value.transfer))
        mutable.value = mutable.value.copy(transfer = null, importPreview = null); reload()
    }
    fun clearError() { mutable.value = mutable.value.copy(error = null) }
    companion object {
        fun factory(repository: LearningRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = LearningViewModel(repository) as T
        }
    }
}
