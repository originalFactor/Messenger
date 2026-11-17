/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.domain.model.LearningReviewState
import cc.ptoe.messenger.domain.model.LearningReviewSubmission
import cc.ptoe.messenger.domain.model.LearningSource
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningReviewDaoTest {
    private val json = Json { encodeDefaults = true }
    private fun recordRow(record: LearningRecord) = LearningRecordRow(record.id, record.kind, record.version,
        record.courseId, record.title, record.archived, record.updatedAt, json.encodeToString(record))
    private val initial = LearningReviewState("card", 0, 0, 0, 2.5, 10, null, "sm2-v1")
    private val item = LearningRecord("item", version = 1, title = "Item",
        source = LearningSource(kind = "chat", conversationId = "deleted", messageIds = listOf("old"), snapshot = "retained"),
        content = buildJsonObject { put("kind", "item"); put("itemKind", "note") })
    private val card = LearningRecord("card", version = 1, title = "Card",
        content = buildJsonObject { put("kind", "reviewCard"); put("itemId", "item"); put("paused", false) })

    @Test fun goldenSm2RatingsAndSaturation() {
        for ((rating, ease, repetitions) in listOf(Triple("again", 1.96, 0L), Triple("hard", 2.36, 1L), Triple("good", 2.5, 1L), Triple("easy", 2.6, 1L))) {
            val state = scheduleLearningReview(initial, rating, 123)
            assertTrue(kotlin.math.abs(state.ease - ease) < 1e-9)
            assertEquals(repetitions, state.repetitions)
            assertEquals(86_400_123L, state.dueAt)
        }
        val first = scheduleLearningReview(initial, "good", 0)
        val second = scheduleLearningReview(first, "good", 100L * 86_400_000)
        assertEquals(6, second.intervalDays)
        val third = scheduleLearningReview(second, "good", 106L * 86_400_000)
        assertEquals(15, third.intervalDays)
        assertEquals(0, scheduleLearningReview(third, "again", 0).repetitions)
        assertEquals(Long.MAX_VALUE, scheduleLearningReview(initial, "easy", Long.MAX_VALUE - 1).dueAt)
        assertFailsWith<IllegalArgumentException> { scheduleLearningReview(initial.copy(version = Long.MAX_VALUE), "good", 0) }
    }

    @Test fun submissionsAreAtomicVersionedAndGloballyIdempotent() = runBlocking {
        val dir = Files.createTempDirectory("learning-room")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            db.rows().upsertLearningRecords(listOf(recordRow(item), recordRow(card)))
            db.rows().upsertLearningReviewStates(listOf(LearningReviewStateRow("card", json.encodeToString(initial))))
            val dao = db.learningReview()
            val request = LearningReviewSubmission("s1", "card", 1, 0, "answer", "good")
            val result = dao.submit(request, 100)
            assertEquals(1L, result.state.version)
            assertEquals("retained", result.itemSnapshot.source?.snapshot)
            assertNull(result.itemSnapshot.source?.conversationId)
            assertEquals(emptyList(), result.itemSnapshot.source?.messageIds)
            assertEquals(result, dao.submit(request, 200))
            assertFailsWith<IllegalStateException> { dao.submit(request.copy(answer = "different"), 200) }
            assertFailsWith<IllegalStateException> { dao.submit(request.copy(submissionId = "stale"), 200) }
            assertEquals(listOf(result), dao.attempts("card"))
            assertEquals(result.state, dao.state("card"))
            val next = request.copy(submissionId = "s2", stateVersion = 1)
            val raced = listOf("s2", "s3").map { id -> async { runCatching { dao.submit(next.copy(submissionId = id), 300) } } }.awaitAll()
            assertEquals(1, raced.count { it.isSuccess })
            assertEquals(2L, assertNotNull(dao.state("card")).version)
            assertEquals(2, dao.attempts("card").size)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
    @Test fun readsCoreJsonAndRollsBackStateWhenAttemptInsertFails() = runBlocking {
        val dir = Files.createTempDirectory("learning-core-json")
        val path = dir.resolve("room.db")
        fun open() = Room.databaseBuilder<LocalDatabase>(name = path.toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            var db = open()
            try {
                // These bodies follow the persisted Rust serde camelCase format.
                val itemJson = """{"id":"i","version":3,"createdAt":1,"updatedAt":2,"title":"Item","courseId":null,"knowledgePointIds":[],"tags":[],"archived":false,"source":null,"content":{"kind":"item","itemKind":"note","question":"","originalAnswer":"","correctAnswer":"","explanation":"","errorReason":""}}"""
                val cardJson = """{"id":"c","version":1,"createdAt":1,"updatedAt":2,"title":"Card","courseId":null,"knowledgePointIds":[],"tags":[],"archived":false,"source":null,"content":{"kind":"reviewCard","itemId":"i","paused":false}}"""
                val stateJson = """{"cardId":"c","version":4,"intervalDays":6,"repetitions":2,"ease":2.5,"dueAt":20,"lastReviewedAt":10,"algorithm":"sm2-v1"}"""
                db.rows().upsertLearningRecords(listOf(LearningRecordRow("i", "item", 3, null, "Item", false, 2, itemJson), LearningRecordRow("c", "reviewCard", 1, null, "Card", false, 2, cardJson)))
                db.rows().upsertLearningReviewStates(listOf(LearningReviewStateRow("c", stateJson)))
            } finally { db.close() }
            BundledSQLiteDriver().open(path.toString()).use { conn ->
                conn.prepare("CREATE TRIGGER fail_review BEFORE INSERT ON learning_review_attempts BEGIN SELECT RAISE(ABORT, 'test'); END").use { it.step() }
            }
            db = open()
            try {
                val dao = db.learningReview()
                val request = LearningReviewSubmission("s", "c", 3, 4, "answer", "good")
                assertEquals("note", dao.record("i")?.field("itemKind"))
                assertFailsWith<Exception> { dao.submit(request, 100) }
                assertEquals(4L, dao.state("c")?.version)
                assertEquals(emptyList(), dao.attempts("c"))
            } finally { db.close() }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun recordLifecycleVersionReferencesPaginationAndAssetProtection() = runBlocking {
        val dir = Files.createTempDirectory("learning-records")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val dao = db.learningReview()
            fun draft(id: String, title: String, kind: String, course: String? = null, configure: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
                LearningRecord(id, title = title, courseId = course, content = buildJsonObject { put("kind", kind); configure() })
            val course = dao.saveRecord(draft("course", "Course", "course"), 10)
            val point = dao.saveRecord(draft("point", "Point", "knowledgePoint", "course"), 11)
            val child = dao.saveRecord(draft("child", "Child", "knowledgePoint", "course") { put("parentId", "point") }, 12)
            assertFailsWith<IllegalArgumentException> {
                dao.saveRecord(point.copy(content = buildJsonObject { put("kind", "knowledgePoint"); put("parentId", "child") }), 13)
            }
            val item = dao.saveRecord(draft("item", "Needle", "item", "course") { put("itemKind", "exercise") }.copy(tags = listOf("math"), knowledgePointIds = listOf("point")), 20)
            val card = dao.saveRecord(draft("card", "Review", "reviewCard", "course") { put("itemId", "item") }, 21)
            assertEquals(0L, dao.state("card")?.version)
            assertEquals(listOf(item), dao.listRecords("item", courseId = "course", search = "needle", tag = "math", knowledgePointId = "point").items)
            assertEquals(1, dao.listRecords("item", courseId = "course").total)
            assertFailsWith<IllegalStateException> { dao.saveRecord(item.copy(version = 0), 22) }
            assertFailsWith<IllegalStateException> { dao.removeRecord("item", item.version) }
            val updated = dao.saveRecord(item.copy(title = "Updated"), 23)
            assertEquals(2L, updated.version)
            assertEquals(20L, updated.createdAt)
            assertEquals(23L, updated.updatedAt)
            assertFailsWith<IllegalStateException> { dao.removeRecord("item", item.version) }
            dao.removeRecord("card", card.version)
            assertNull(dao.state("card"))
            dao.removeRecord("item", updated.version)
            assertFailsWith<IllegalStateException> { dao.removeRecord("point", point.version) }
            dao.removeRecord("child", child.version)
            dao.removeRecord("point", point.version)
            dao.removeRecord("course", course.version)
            assertEquals(0, dao.listRecords("course").total)
            val safe = dao.saveRecord(draft("safe", "Safe", "item") { put("itemKind", "note") }, 30)
            db.rows().upsertValues(listOf(KeyValueRow("local_asset:owned", "opaque metadata")))
            assertFailsWith<IllegalStateException> { dao.removeRecord(safe.id, safe.version) }
            assertFailsWith<IllegalStateException> { dao.saveRecord(safe.copy(title = "No"), 31) }
            assertEquals(safe, dao.record("safe"))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

}
