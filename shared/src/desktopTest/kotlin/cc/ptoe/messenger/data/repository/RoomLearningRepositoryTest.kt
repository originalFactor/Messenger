/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.asset.CoreAssetGate
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.asset.LearningBundleService
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.domain.model.LearningReviewSubmission
import cc.ptoe.messenger.domain.model.LearningSource
import cc.ptoe.messenger.domain.repository.LearningRepository
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoomLearningRepositoryTest {
    private class Gate : CoreAssetGate {
        private val mutex = Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T) = mutex.withLock { block() }
    }
    @Test fun learningInterfacePersistsReviewsAndRetainsSharedResources(): Unit = runBlocking {
        val dir = Files.createTempDirectory("learning-repository")
        val root = Files.createDirectory(dir.resolve("assets")).also { if (Files.getFileStore(it).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val gate = Gate()
            val service = CoreAssetService(root, db.assetMetadata(), gate)
            val repository: LearningRepository = RoomLearningRepository(db.learningReview(), LearningOwnership(db, service), service,
                LearningBundleService(root, gate, db.assetMetadata(), db.learningBundle())) { 100L }
            val bytes = "learning asset".toByteArray()
            val asset = repository.importAsset("note", "text/plain", Base64.getEncoder().encodeToString(bytes))
            val note = repository.save(LearningRecord("note", title = "Note", source = LearningSource(snapshot = "source", assetIds = listOf(asset)), content = buildJsonObject { put("kind", "item"); put("itemKind", "note") }))
            val card = repository.save(LearningRecord("card", title = "Review", content = buildJsonObject { put("kind", "reviewCard"); put("itemId", "note") }))
            assertEquals(note, repository.get("item", "note"))
            assertEquals(1, repository.list("item").total)
            assertEquals(listOf(card), repository.due())
            assertEquals(1, repository.overview().dueReviews)
            val attempt = repository.submit(LearningReviewSubmission("submission", card.id, note.version, 0, "answer", "good"))
            assertEquals(1L, attempt.state.version)
            assertTrue(repository.due().isEmpty())
            assertContentEquals(bytes, repository.assetBytes(asset))
            assertEquals("text/plain", repository.assetMimeType(asset))
            assertFailsWith<IllegalStateException> { repository.releaseAsset("note", asset) }
            repository.delete(card)
            repository.delete(note)
            assertTrue(Files.exists(root.resolve(asset)))
            assertEquals(listOf(attempt), repository.attempts(card.id))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
