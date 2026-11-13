package cc.ptoe.messenger.data.repository

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.domain.model.*
import cc.ptoe.messenger.domain.repository.LearningRepository
import io.ktor.http.encodeURLQueryComponent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class RustLearningRepository(private val core: CoreBridge) : LearningRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private suspend fun request(method: String, path: String, body: String? = null) = core.learningRequest(method, path, body)
    override suspend fun list(kind: String, courseId: String?, search: String, offset: Int, archived: Boolean?, knowledgePointId: String?, tag: String): LearningPage {
        val query = buildList {
            add("offset=$offset"); add("limit=50")
            courseId?.let { add("courseId=${it.encodeURLQueryComponent()}") }
            if (search.isNotBlank()) add("search=${search.encodeURLQueryComponent()}")
            archived?.let { add("archived=$it") }
            knowledgePointId?.let { add("knowledgePointId=${it.encodeURLQueryComponent()}") }
            if (tag.isNotBlank()) add("tag=${tag.encodeURLQueryComponent()}")
        }.joinToString("&")
        return json.decodeFromString(request("GET", "/learning/records/$kind?$query"))
    }
    override suspend fun get(kind: String, id: String): LearningRecord = json.decodeFromString(request("GET", "/learning/records/$kind/${id.encodeURLQueryComponent()}"))
    override suspend fun save(record: LearningRecord): LearningRecord = json.decodeFromString(request("PUT", "/learning/records/${record.kind}/${record.id.encodeURLQueryComponent()}", json.encodeToString(record)))
    override suspend fun delete(record: LearningRecord) { request("DELETE", "/learning/records/${record.kind}/${record.id.encodeURLQueryComponent()}?version=${record.version}") }
    override suspend fun artifacts(): List<LearningArtifactReference> = json.decodeFromJsonElement(json.parseToJsonElement(request("GET", "/artifacts")).jsonObject.getValue("items"))
    override suspend fun overview(): LearningOverview = json.decodeFromString(request("GET", "/learning/overview"))
    override suspend fun due(): List<LearningRecord> = json.decodeFromString<LearningPage>(request("GET", "/learning/records/reviewCard?dueBefore=${cc.ptoe.messenger.data.util.currentTimeMillis()}&limit=200")).items
    override suspend fun reviewState(cardId: String): LearningReviewState = json.decodeFromString(request("GET", "/learning/reviews/${cardId.encodeURLQueryComponent()}/state"))
    override suspend fun attempts(cardId: String): List<LearningReviewAttempt> {
        val value = json.parseToJsonElement(request("GET", "/learning/reviews/${cardId.encodeURLQueryComponent()}/attempts"))
        return json.decodeFromJsonElement(if (value is JsonArray) value else value.jsonObject.getValue("items"))
    }
    override suspend fun submit(submission: LearningReviewSubmission): LearningReviewAttempt = json.decodeFromString(request("POST", "/learning/reviews/${submission.cardId.encodeURLQueryComponent()}/submit", json.encodeToString(submission)))
    override suspend fun assetBytes(assetId: String): ByteArray = core.learningAsset(assetId)
    override suspend fun assetMimeType(assetId: String): String = json.parseToJsonElement(request("GET", "/assets/${assetId.encodeURLQueryComponent()}")).jsonObject.getValue("mimeType").jsonPrimitive.content
    override suspend fun releaseAsset(ownerId: String, assetId: String) {
        request("POST", "/assets/${assetId.encodeURLQueryComponent()}/unreference", buildJsonObject { put("ownerType", "learning"); put("ownerId", ownerId) }.toString())
    }
    override suspend fun importAsset(ownerId: String, mimeType: String, base64: String): String {
        val body = buildJsonObject {
            put("dataBase64", base64); put("mimeType", mimeType)
            putJsonObject("owner") { put("ownerType", "learning"); put("ownerId", ownerId) }
        }
        return json.parseToJsonElement(request("POST", "/assets/import", body.toString())).jsonObject.getValue("id").jsonPrimitive.content
    }
    override suspend fun captureImages(ownerId: String, images: List<MessageImage>): List<String> = images.map { image ->
        val match = Regex("^data:(image/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=\\r\\n]+)$").matchEntire(image.dataUri)
            ?: error("Image snapshot has no supported embedded image data")
        importAsset(ownerId, match.groupValues[1], match.groupValues[2])
    }
    override suspend fun exportData(): String = request("GET", "/learning/package/export")
    override suspend fun previewImport(bundle: String): String = request("POST", "/learning/package/preview", bundle)
    override suspend fun importData(bundle: String): String = request("POST", "/learning/package/import", bundle)
}
