/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.domain.model.LearningSource
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LearningBundleServiceTest {
    private class Gate : CoreAssetGate {
        private val mutex = Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T) = mutex.withLock { block() }
    }
    @Test fun previewRollsBackAndImportPreservesResourcesAndResetsGrants(): Unit = runBlocking {
        val dir = Files.createTempDirectory("learning-bundle")
        val root = Files.createDirectory(dir.resolve("assets"))
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val bytes = "resource".toByteArray()
            val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val note = LearningRecord("note", version = 1, title = "Note", source = LearningSource(snapshot = "retained", assetIds = listOf(id)), content = buildJsonObject { put("kind", "item"); put("itemKind", "note") })
            val asset = PortableAsset(CoreAssetMetadata(id, id, "text/plain", bytes.size.toLong(), createdAt = 1, references = emptyList()), Base64.getEncoder().encodeToString(bytes))
            val artifactId = "00000000-0000-0000-0000-000000000001"
            val artifact = Json.parseToJsonElement("""{"id":"$artifactId","title":"App","revisions":[{"revision":1,"files":{"index.html":"<p>Hi</p>"},"assets":{"image":"$id"},"entry":"index.html","createdAt":1}],"approvedDomains":[],"trustedOnDevice":true,"createdAt":1,"updatedAt":1}""").jsonObject
            val bundle = PortableLearningBundle(1, PortableLearning(1, listOf(note), listOf(note), emptyList(), emptyList(), false), listOf(asset), listOf(artifact))
            val document = Json { encodeDefaults = true }.encodeToString(bundle)
            val service = LearningBundleService(root, Gate(), db.assetMetadata(), db.learningBundle())
            assertEquals(1, service.preview(document).records)
            assertEquals(null, db.learningBundle().body("note"))
            assertEquals(null, db.learningBundle().value("local_asset:$id"))
            assertFalse(Files.exists(root.resolve(id)))
            assertEquals(1, service.importBundle(document).assets)
            assertContentEquals(bytes, Files.readAllBytes(root.resolve(id)))
            val saved = Json.decodeFromString<CoreAssetMetadata>(db.learningBundle().value("local_asset:$id")!!)
            assertTrue(CoreAssetOwner("learning", "note") in saved.references)
            assertTrue(CoreAssetOwner("artifact", artifactId) in saved.references)
            assertTrue(CoreAssetOwner("learningPackage", id) in saved.references)
            val savedArtifact = Json.parseToJsonElement(db.learningBundle().value("artifact:$artifactId")!!).jsonObject
            assertEquals("false", savedArtifact.getValue("trustedOnDevice").jsonPrimitive.content)
            assertTrue(savedArtifact.getValue("approvedDomains").jsonArray.isEmpty())
            val exported = LearningBundleValidation.validate(service.export())
            assertContentEquals(bytes, exported.bytes[id])
            val exportedArtifact = exported.bundle.artifacts.single()
            assertEquals("false", exportedArtifact.getValue("trustedOnDevice").jsonPrimitive.content)
            assertTrue(exportedArtifact.getValue("approvedDomains").jsonArray.isEmpty())
            assertFailsWith<Exception> { service.importBundle(document) }
            assertContentEquals(bytes, Files.readAllBytes(root.resolve(id)))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun missingReferenceFailsPreviewWithoutPublishingBytes(): Unit = runBlocking {
        val dir = Files.createTempDirectory("learning-bundle")
        val root = Files.createDirectory(dir.resolve("assets"))
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val item = LearningRecord("item", version = 1, title = "Item", courseId = "missing", content = buildJsonObject { put("kind", "item"); put("itemKind", "note") })
            val bundle = PortableLearningBundle(1, PortableLearning(1, listOf(item), listOf(item), emptyList(), emptyList(), false), emptyList(), emptyList())
            val service = LearningBundleService(root, Gate(), db.assetMetadata(), db.learningBundle())
            val document = Json { encodeDefaults = true }.encodeToString(bundle)
            assertFailsWith<IllegalStateException> { service.preview(document) }
            assertEquals(null, db.learningBundle().body("item"))
            assertTrue(Files.list(root).use { !it.findAny().isPresent })
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
