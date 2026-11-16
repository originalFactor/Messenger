/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.artifact

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.asset.CoreAssetGate
import cc.ptoe.messenger.data.local.asset.CoreAssetMetadata
import cc.ptoe.messenger.data.local.asset.CoreAssetOwner
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomArtifactServiceTest {
    private class Gate : CoreAssetGate {
        private val lock = Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T) = lock.withLock { block() }
    }
    @Test fun revisionsKeepOwnersAndImportedArtifactsRequireAuthorization(): Unit = runBlocking {
        val dir = Files.createTempDirectory("artifact-service")
        val root = Files.createDirectory(dir.resolve("assets")).also { if (Files.getFileStore(it).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val assets = CoreAssetService(root, db.assetMetadata(), Gate())
            val existing = assets.importFile("resource".toByteArray(), "text/plain", CoreAssetOwner("learning", "note"), 1)
            val service = RoomArtifactService(db, assets)
            val draft = ArtifactDraft(title = "App", files = mapOf("index.html" to "<p>Hello</p>"), assets = mapOf("resource" to existing.id), requestedDomains = listOf("example.com"))
            val created = service.submit(draft, 2)
            assertTrue(created.trustedOnDevice)
            val metadata = Json.decodeFromString<CoreAssetMetadata>(db.assetMetadata().metadata("local_asset:${existing.id}")!!)
            assertTrue(CoreAssetOwner("artifact", created.id) in metadata.references)
            assertFailsWith<IllegalStateException> { service.saveState(created.id, 2, 0, JsonPrimitive("x")) }
            service.grantDomains(created.id, listOf("example.com"))
            val next = service.submit(draft.copy(id = created.id, expectedRevision = 1, assets = emptyMap(), requestedDomains = emptyList()), 3)
            assertTrue(next.approvedDomains.isEmpty())
            assertEquals(2L, next.revisions.last().revision)
            assertTrue(CoreAssetOwner("artifact", created.id) in Json.decodeFromString<CoreAssetMetadata>(db.assetMetadata().metadata("local_asset:${existing.id}")!!).references)
            assertFailsWith<IllegalStateException> { service.submit(draft.copy(id = created.id, expectedRevision = 0), 4) }
            assertFailsWith<IllegalArgumentException> { service.submit(draft.copy(id = created.id, expectedRevision = 2, files = mapOf("../bad" to "x")), 4) }
            val imported = next.copy(trustedOnDevice = false)
            db.rows().upsertValues(listOf(KeyValueRow("artifact:${created.id}", Json { encodeDefaults = true }.encodeToString(imported))))
            assertFalse(service.get(created.id).trustedOnDevice)
            assertFailsWith<IllegalStateException> { service.saveState(created.id, 2, 0, JsonPrimitive("x")) }
            service.authorizeRun(created.id)
            assertEquals(JsonPrimitive("x"), service.saveState(created.id, 2, 0, JsonPrimitive("x")).state)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
