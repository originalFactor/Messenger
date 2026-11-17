/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.asset.*
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.domain.model.LearningSource
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LearningOwnershipTest {
    private class Gate : CoreAssetGate {
        private val lock = Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T): T = lock.withLock { block() }
    }
    @Test fun recordAndSharedAssetOwnersChangeAtomically(): Unit = runBlocking {
        val dir = Files.createTempDirectory("learning-owner")
        val root = Files.createDirectory(dir.resolve("assets")).also { if (Files.getFileStore(it).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val assets = CoreAssetService(root, db.assetMetadata(), Gate())
            val other = CoreAssetOwner("artifact", "other")
            val owned = assets.importFile("bytes".toByteArray(), "text/plain", other, 1)
            val coordinator = LearningOwnership(db, assets)
            val note = LearningRecord("note", title = "Note", source = LearningSource(snapshot = "source", assetIds = listOf(owned.id)), content = buildJsonObject { put("kind", "item"); put("itemKind", "note") })
            assertFailsWith<Exception> { coordinator.save(note.copy(source = note.source!!.copy(assetIds = listOf("f".repeat(64)))), 2) }
            assertEquals(null, db.learningReview().record("note"))
            val saved = coordinator.save(note, 3)
            var metadata = Json.decodeFromString<CoreAssetMetadata>(db.assetMetadata().metadata("local_asset:${owned.id}")!!)
            assertEquals(setOf(other, CoreAssetOwner("learning", "note")), metadata.references.toSet())
            val updated = coordinator.save(saved.copy(source = null), 4)
            metadata = Json.decodeFromString(db.assetMetadata().metadata("local_asset:${owned.id}")!!)
            assertTrue(CoreAssetOwner("learning", "note") in metadata.references) // old revision owns it
            coordinator.delete(updated, 5)
            metadata = Json.decodeFromString(db.assetMetadata().metadata("local_asset:${owned.id}")!!)
            assertEquals(listOf(other), metadata.references)
            assertTrue(Files.isRegularFile(root.resolve(owned.id)))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun metadataWriteFailureRollsBackRecordAndRevision(): Unit = runBlocking {
        val dir = Files.createTempDirectory("learning-owner")
        val root = Files.createDirectory(dir.resolve("assets")).also { if (Files.getFileStore(it).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        val path = dir.resolve("room.db")
        fun open() = Room.databaseBuilder<LocalDatabase>(name = path.toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            var db = open()
            val id: String
            try { id = CoreAssetService(root, db.assetMetadata(), Gate()).importFile("bytes".toByteArray(), "text/plain", CoreAssetOwner("artifact", "other"), 1).id }
            finally { db.close() }
            BundledSQLiteDriver().open(path.toString()).use { conn ->
                conn.prepare("CREATE TRIGGER fail_owner BEFORE UPDATE ON kv WHEN OLD.key LIKE 'local_asset:%' BEGIN SELECT RAISE(ABORT, 'test'); END").use { it.step() }
            }
            db = open()
            try {
                val assets = CoreAssetService(root, db.assetMetadata(), Gate())
                val note = LearningRecord("note", title = "Note", source = LearningSource(snapshot = "source", assetIds = listOf(id)), content = buildJsonObject { put("kind", "item"); put("itemKind", "note") })
                assertFailsWith<Exception> { LearningOwnership(db, assets).save(note, 2) }
                assertEquals(null, db.learningReview().record("note"))
                assertTrue(!db.learningReview().hasRevision("note"))
                val metadata = Json.decodeFromString<CoreAssetMetadata>(db.assetMetadata().metadata("local_asset:$id")!!)
                assertEquals(listOf(CoreAssetOwner("artifact", "other")), metadata.references)
                assertTrue(Files.isRegularFile(root.resolve(id)))
            } finally { db.close() }
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun reviewSnapshotsRetainOwnedFilesAfterRecordsAreDeleted(): Unit = runBlocking {
        val dir = Files.createTempDirectory("learning-snapshot-owner")
        val root = Files.createDirectory(dir.resolve("assets")).also { if (Files.getFileStore(it).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val gate = Gate()
            val assets = CoreAssetService(root, db.assetMetadata(), gate)
            val ownership = LearningOwnership(db, assets)
            val id = assets.importFile("photo".toByteArray(), "text/plain", CoreAssetOwner("learning", "note"), 1).id
            val item = ownership.save(LearningRecord("note", title = "Note", source = LearningSource(snapshot = "s", assetIds = listOf(id)), content = buildJsonObject { put("kind", "item"); put("itemKind", "note") }), 2)
            val card = ownership.save(LearningRecord("card", title = "Card", content = buildJsonObject { put("kind", "reviewCard"); put("itemId", "note") }), 3)
            db.learningReview().submit(cc.ptoe.messenger.domain.model.LearningReviewSubmission("s1", "card", item.version, 0, "answer", "good"), 4)
            ownership.delete(card, 5)
            ownership.delete(item, 6)
            val metadata = Json.decodeFromString<CoreAssetMetadata>(db.assetMetadata().metadata("local_asset:$id")!!)
            assertTrue(CoreAssetOwner("learning", "note") in metadata.references)
            assertTrue(Files.isRegularFile(root.resolve(id)))
            assertEquals(1, db.learningReview().attempts("card").size)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

}
