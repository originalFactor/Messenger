/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreAssetServiceTest {
    private class Gate : CoreAssetGate {
        private val lock = Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T): T = lock.withLock { block() }
    }
    @Test fun importedBytesAndOwnersAreRetainedWithoutOverwriting() = runBlocking {
        val dir = Files.createTempDirectory("asset-service")
        val root = Files.createDirectory(dir.resolve("assets")).also { if (Files.getFileStore(it).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val gate = Gate()
            val service = CoreAssetService(root, db.assetMetadata(), gate)
            val bytes = "owned shared content".toByteArray()
            val owners = listOf(CoreAssetOwner("learning", "note"), CoreAssetOwner("artifact", "app"))
            val results = owners.map { owner -> async { service.importFile(bytes, "text/plain", owner, 123) } }.awaitAll()
            val asset = results.last()
            assertContentEquals(bytes, service.read(asset.id))
            val manifest = kotlinx.serialization.json.Json.decodeFromString<CoreAssetMetadata>(db.assetMetadata().metadata("local_asset:${asset.id}")!!)
            assertEquals(owners.toSet(), manifest.references.toSet())
            assertFailsWith<IllegalArgumentException> { service.importFile(bytes, "application/octet-stream", owners[0], 124) }
            assertFailsWith<IllegalArgumentException> { service.importFile(bytes, "image/png", owners[0], 124) }
            assertContentEquals(bytes, Files.readAllBytes(root.resolve(asset.id)))
            assertTrue(Files.list(root).use { files -> files.noneMatch { it.fileName.toString().startsWith(".stage-") } })
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun failureNeverDeletesCorruptOrSharedExistingFiles() = runBlocking {
        val dir = Files.createTempDirectory("asset-service")
        val root = Files.createDirectory(dir.resolve("assets")).also { if (Files.getFileStore(it).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val service = CoreAssetService(root, db.assetMetadata(), Gate())
            val bytes = "original".toByteArray()
            val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            Files.write(root.resolve(id), "tampered".toByteArray())
            assertFailsWith<IllegalStateException> { service.importFile(bytes, "text/plain", CoreAssetOwner("learning", "note"), 1) }
            assertEquals("tampered", Files.readString(root.resolve(id)))
            assertEquals(null, db.assetMetadata().metadata("local_asset:$id"))
            assertFalse(Files.list(root).use { files -> files.anyMatch { it.fileName.toString().startsWith(".stage-") } })
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
    @Test fun validatesPngJpegAndWebpBeforeImport(): Unit = runBlocking {
        val dir = Files.createTempDirectory("image-service")
        val root = Files.createDirectory(dir.resolve("assets")).also { if (Files.getFileStore(it).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val service = CoreAssetService(root, db.assetMetadata(), Gate())
            val owner = CoreAssetOwner("learning", "image")
            fun image(format: String): ByteArray {
                val bitmap = java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
                return java.io.ByteArrayOutputStream().use { output ->
                    check(javax.imageio.ImageIO.write(bitmap, format, output))
                    output.toByteArray()
                }
            }
            val png = image("png")
            val jpeg = image("jpeg")
            val webp = checkNotNull(javaClass.getResourceAsStream("/asset-test.webp")).readBytes()
            for ((bytes, mime) in listOf(png to "image/png", jpeg to "image/jpeg", webp to "image/webp")) {
                val asset = service.importFile(bytes, mime, owner, 10)
                assertTrue(asset.width!! > 0 && asset.height!! > 0)
                assertContentEquals(bytes, service.read(asset.id))
            }
            assertFailsWith<IllegalArgumentException> { service.importFile(png, "image/jpeg", owner, 10) }
            assertFailsWith<Exception> { service.importFile(png.copyOf(24), "image/png", owner, 10) }
            val oversized = png.copyOf()
            oversized[16] = 0; oversized[17] = 0; oversized[18] = 32; oversized[19] = 1
            assertFailsWith<Exception> { service.importFile(oversized, "image/png", owner, 10) }
            assertFailsWith<IllegalArgumentException> { service.importFile(png, "image/gif", owner, 10) }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun historicalBindingsProtectAssetsAndGcHonorsGracePeriod(): Unit = runBlocking {
        val dir = Files.createTempDirectory("asset-gc")
        val root = Files.createDirectory(dir.resolve("assets")).also { if (Files.getFileStore(it).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val service = CoreAssetService(root, db.assetMetadata(), Gate())
            val noteOwner = CoreAssetOwner("learning", "note")
            val otherOwner = CoreAssetOwner("learning", "other")
            val data = "owned".toByteArray()
            val asset = service.importFile(data, "text/plain", noteOwner, 1)
            service.importFile(data, "text/plain", otherOwner, 1)
            val record = cc.ptoe.messenger.domain.model.LearningRecord("note", version = 1, title = "Note",
                source = cc.ptoe.messenger.domain.model.LearningSource(snapshot = "photo", assetIds = listOf(asset.id)),
                content = kotlinx.serialization.json.buildJsonObject { put("kind", kotlinx.serialization.json.JsonPrimitive("item")); put("itemKind", kotlinx.serialization.json.JsonPrimitive("note")) })
            val codec = kotlinx.serialization.json.Json { encodeDefaults = true }
            val original = codec.encodeToString(cc.ptoe.messenger.domain.model.LearningRecord.serializer(), record)
            val revised = codec.encodeToString(cc.ptoe.messenger.domain.model.LearningRecord.serializer(), record.copy(version = 2, source = null, updatedAt = 2))
            db.rows().upsertLearningRecords(listOf(cc.ptoe.messenger.data.local.room.LearningRecordRow("note", "item", 2, null, "Note", false, 2, revised)))
            db.rows().upsertLearningRevisions(listOf(cc.ptoe.messenger.data.local.room.LearningRevisionRow("note", 1, original)))
            assertFailsWith<IllegalStateException> { service.release(asset.id, noteOwner, 100) }
            assertEquals(2, kotlinx.serialization.json.Json.decodeFromString<CoreAssetMetadata>(db.assetMetadata().metadata("local_asset:${asset.id}")!!).references.size)
            db.learningReview().deleteRevisions("note")
            val retained = service.release(asset.id, noteOwner, 100)
            assertEquals(listOf(otherOwner), retained.references)
            assertFailsWith<IllegalStateException> { service.collect(100 + 86_400_000) }
            service.release(asset.id, otherOwner, 200)
            assertFailsWith<IllegalStateException> { service.collect(200 + 86_400_000) }
            assertTrue(Files.exists(root.resolve(asset.id)))
            assertTrue(db.assetMetadata().metadata("local_asset:${asset.id}") != null)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun corruptedGcCandidateKeepsFileAndMetadata(): Unit = runBlocking {
        val dir = Files.createTempDirectory("asset-gc-corrupt")
        val root = Files.createDirectory(dir.resolve("assets")).also { if (Files.getFileStore(it).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val service = CoreAssetService(root, db.assetMetadata(), Gate())
            val owner = CoreAssetOwner("learning", "note")
            val asset = service.importFile("data".toByteArray(), "text/plain", owner, 1)
            service.release(asset.id, owner, 2)
            Files.writeString(root.resolve(asset.id), "evil")
            assertFailsWith<IllegalStateException> { service.collect(2 + 86_400_000) }
            assertTrue(Files.exists(root.resolve(asset.id)))
            assertTrue(db.assetMetadata().metadata("local_asset:${asset.id}") != null)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun orphanCollectionKeepsYoungCorruptAndHistoricallyReferencedFiles(): Unit = runBlocking {
        val dir = Files.createTempDirectory("asset-orphans")
        val root = Files.createDirectory(dir.resolve("assets")).also { if (Files.getFileStore(it).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        try {
            val service = CoreAssetService(root, db.assetMetadata(), Gate())
            val old = "old orphan".toByteArray(); val oldId = hash(old)
            val protected = "protected".toByteArray(); val protectedId = hash(protected)
            val corruptId = "a".repeat(64)
            val young = "young".toByteArray(); val youngId = hash(young)
            val stage = ".stage-crashed"
            Files.writeString(root.resolve(stage), "incomplete")
            Files.setLastModifiedTime(root.resolve(stage), java.nio.file.attribute.FileTime.fromMillis(100))
            for ((id, bytes) in listOf(oldId to old, protectedId to protected, corruptId to "wrong".toByteArray(), youngId to young)) Files.write(root.resolve(id), bytes)
            for (id in listOf(oldId, protectedId, corruptId)) Files.setLastModifiedTime(root.resolve(id), java.nio.file.attribute.FileTime.fromMillis(100))
            val record = cc.ptoe.messenger.domain.model.LearningRecord("note", version = 1, title = "Note", source = cc.ptoe.messenger.domain.model.LearningSource(snapshot = "source", assetIds = listOf(protectedId)), content = kotlinx.serialization.json.buildJsonObject { put("kind", kotlinx.serialization.json.JsonPrimitive("item")); put("itemKind", kotlinx.serialization.json.JsonPrimitive("note")) })
            val body = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(cc.ptoe.messenger.domain.model.LearningRecord.serializer(), record)
            db.rows().upsertLearningRevisions(listOf(cc.ptoe.messenger.data.local.room.LearningRevisionRow("note", 1, body)))
            if (Files.newDirectoryStream(dir).use { it !is java.nio.file.SecureDirectoryStream<*> }) {
                assertFailsWith<IllegalStateException> { service.auditOrphans(System.currentTimeMillis()) }
                return@runBlocking
            }
            val result = service.auditOrphans(System.currentTimeMillis())
            assertEquals(setOf(oldId, stage), result.candidates.toSet())
            assertEquals(listOf(corruptId), result.invalid)
            assertTrue(Files.exists(root.resolve(oldId)))
            assertTrue(Files.exists(root.resolve(stage)))
            for (id in listOf(protectedId, corruptId, youngId)) assertTrue(Files.exists(root.resolve(id)))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

}
