/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LearningRecordRow
import cc.ptoe.messenger.data.local.room.LearningRevisionRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.domain.model.LearningSource
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssetReferenceAuditTest {
    @Test fun historicalLearningArtifactsAndJobsProtectSharedFiles(): Unit = runBlocking {
        val dir = Files.createTempDirectory("asset-audit")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val learningId = "a".repeat(64)
            val artifactId = "b".repeat(64)
            val jobId = "c".repeat(64)
            val record = LearningRecord("note", version = 1, title = "Note", source = LearningSource(snapshot = "snapshot", assetIds = listOf(learningId)), content = buildJsonObject { put("kind", "item"); put("itemKind", "note") })
            val body = Json { encodeDefaults = true }.encodeToString(record)
            db.rows().upsertLearningRecords(listOf(LearningRecordRow("note", "item", 1, null, "Note", false, 0, body)))
            db.rows().upsertLearningRevisions(listOf(LearningRevisionRow("note", 1, body)))
            val artifact = """{"id":"00000000-0000-0000-0000-000000000001","title":"App","revisions":[{"revision":1,"files":{},"assets":{"img":"$artifactId"},"entry":"index.html","createdAt":1}],"createdAt":1,"updatedAt":1}"""
            val job = """{"id":"job","request":{"requestId":"req","profileId":"profile","prompt":"p"},"profile":{"id":"profile","name":"Name","providerId":"p","modelId":"model"},"parameters":{},"status":"running","createdAt":1,"updatedAt":1,"assetIds":["$jobId"],"error":null,"usage":null}"""
            db.rows().upsertValues(listOf(KeyValueRow("artifact:00000000-0000-0000-0000-000000000001", artifact), KeyValueRow("local_image_job:job", job)))
            val audit = AssetReferenceAudit(db.assetMetadata().referenceSnapshot())
            assertEquals(setOf(learningId, artifactId, jobId), audit.protectedAssetIds())
            assertFailsWith<IllegalStateException> { audit.requireUnbound(learningId, CoreAssetOwner("learning", "note")) }
            assertFailsWith<IllegalStateException> { audit.requireUnbound(artifactId, CoreAssetOwner("artifact", "00000000-0000-0000-0000-000000000001")) }
            assertFailsWith<IllegalStateException> { audit.requireUnbound(learningId, CoreAssetOwner("imageGenerationJob", "job")) }
            assertFailsWith<IllegalStateException> { audit.requireUnbound(jobId, CoreAssetOwner("unknown", "owner")) }
            audit.requireUnbound(learningId, CoreAssetOwner("learning", "other"))
            assertFailsWith<IllegalArgumentException> { AssetReferenceAudit(AssetReferenceSnapshot(emptyList(), emptyList(), emptyList(), listOf(KeyValueRow("artifact:wrong", artifact)), emptyList())) }
            assertFailsWith<IllegalArgumentException> { AssetReferenceAudit(AssetReferenceSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), listOf(KeyValueRow("local_image_job:wrong", job)))) }
            assertFailsWith<IllegalArgumentException> { AssetReferenceAudit(AssetReferenceSnapshot(emptyList(), emptyList(), emptyList(), listOf(KeyValueRow("artifact:00000000-0000-0000-0000-000000000001", artifact.dropLast(1) + ",\"future\":true}")), emptyList())) }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
