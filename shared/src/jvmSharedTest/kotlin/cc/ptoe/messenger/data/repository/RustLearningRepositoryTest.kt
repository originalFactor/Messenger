package cc.ptoe.messenger.data.repository

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.domain.model.LearningReviewSubmission
import cc.ptoe.messenger.domain.model.MessageImage
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class RustLearningRepositoryTest {
    private fun bridge(handler: (String, String, String?) -> String): CoreBridge = Proxy.newProxyInstance(
        CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java),
    ) { _, method, arguments ->
        check(method.name == "learningRequest") { "Unexpected bridge method ${method.name}" }
        handler(arguments[0] as String, arguments[1] as String, arguments[2] as String?)
    } as CoreBridge

    @Test fun savesCurrentVersionAndPreservesUnknownContent() = runBlocking<Unit> {
        val record = LearningRecord("item-a", 7, title = "Quadratic", content = buildJsonObject {
            put("kind", "item"); put("itemKind", "mistake"); put("question", "x² = 4")
            putJsonObject("futureField") { put("retained", true) }
        })
        val repository = RustLearningRepository(bridge { method, path, body ->
            assertEquals("PUT", method)
            assertEquals("/learning/records/item/item-a", path)
            val payload = Json.parseToJsonElement(requireNotNull(body)).jsonObject
            assertEquals(7, payload.getValue("version").jsonPrimitive.int)
            assertTrue(payload.getValue("content").jsonObject.getValue("futureField").jsonObject.getValue("retained").jsonPrimitive.boolean)
            JsonObject(payload + ("version" to JsonPrimitive(8))).toString()
        })
        assertEquals(8L, repository.save(record).version)
    }

    @Test fun captureCopiesEmbeddedBytesWithoutExposingChatCachePath() = runBlocking<Unit> {
        val repository = RustLearningRepository(bridge { method, path, body ->
            assertEquals("POST", method); assertEquals("/assets/import", path)
            val payload = Json.parseToJsonElement(requireNotNull(body)).jsonObject
            assertEquals("aGVsbG8=", payload.getValue("dataBase64").jsonPrimitive.content)
            assertEquals("image/png", payload.getValue("mimeType").jsonPrimitive.content)
            assertEquals("item-a", payload.getValue("owner").jsonObject.getValue("ownerId").jsonPrimitive.content)
            assertFalse(body.contains("chat_images"))
            """{"id":"asset-a"}"""
        })
        assertEquals(listOf("asset-a"), repository.captureImages("item-a", listOf(MessageImage("data:image/png;base64,aGVsbG8=", "/private/chat_images/source.png"))))
        assertFailsWith<IllegalStateException> { repository.captureImages("item-a", listOf(MessageImage("", "/private/chat_images/source.png"))) }
    }

    @Test fun reviewSubmissionRetainsIdempotencyAndContentVersions() = runBlocking<Unit> {
        val submission = LearningReviewSubmission("submit-once", "card-a", 4, 2, "x = ±2", "good")
        val repository = RustLearningRepository(bridge { method, path, body ->
            assertEquals("POST", method); assertEquals("/learning/reviews/card-a/submit", path)
            val value = Json.parseToJsonElement(requireNotNull(body)).jsonObject
            assertEquals("submit-once", value.getValue("submissionId").jsonPrimitive.content)
            assertEquals(4, value.getValue("itemVersion").jsonPrimitive.int)
            assertEquals(2, value.getValue("stateVersion").jsonPrimitive.int)
            error("409 stale state: reload explicitly")
        })
        val failure = assertFailsWith<IllegalStateException> { repository.submit(submission) }
        assertTrue(failure.message.orEmpty().contains("409"))
    }
}
