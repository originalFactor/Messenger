/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.asset.CoreAssetGate
import cc.ptoe.messenger.data.local.asset.CoreAssetMetadata
import cc.ptoe.messenger.data.local.asset.CoreAssetOwner
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LearningRecordRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.domain.model.LearningSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
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
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomRestorePreflightTest {
    private class Owner : RoomStoreOwnership, RoomClearAllOwnership {
        var held = false
        override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T = own(block)
        override suspend fun <T> withStoppedExclusiveOwner(block: suspend () -> T): T = own(block)
        private suspend fun <T> own(block: suspend () -> T): T {
            check(!held)
            held = true
            return try { block() } finally { held = false }
        }
    }
    private class Gate : CoreAssetGate {
        private val mutex = Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T): T = mutex.withLock { block() }
    }
    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step(); Unit }
    private fun room(path: Path) = Room.databaseBuilder<LocalDatabase>(name = path.toString())
        .setDriver(BundledSQLiteDriver()).build()
    private fun id(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private data class Fixture(val source: Path, val target: Path, val oldAssets: Path, val newAssets: Path,
        val originalId: String, val originalBytes: ByteArray)

    private suspend fun fixture(dir: Path): Fixture {
        val source = dir.resolve("core.db")
        val target = dir.resolve("room.db")
        val oldAssets = Files.createDirectory(dir.resolve("old-assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val newAssets = dir.resolve("new-assets")
        val bytes = "original asset".encodeToByteArray()
        val id = id(bytes)
        Files.write(oldAssets.resolve(id), bytes)
        BundledSQLiteDriver().open(source.toString()).use { db ->
            checkNotNull(javaClass.getResource("/core-store-v9.sql")).readText().split(';')
                .map(String::trim).filter(String::isNotEmpty).forEach { db.exec(it) }
            db.exec("INSERT INTO agents(id,name,systemPrompt,isDefault,createdAt,updatedAt) VALUES ('default','Default','prompt',1,1,1)")
            val metadata = CoreAssetMetadata(id, id, "application/octet-stream", bytes.size.toLong(), createdAt = 1,
                references = listOf(CoreAssetOwner("learning", "original")))
            db.prepare("INSERT INTO kv(key,value) VALUES (?,?)").use { row ->
                row.bindText(1, "local_asset:$id"); row.bindText(2, Json.encodeToString(metadata)); row.step()
            }
        }
        RoomStoreStartup.withLeasedCoreStore(source, target, oldAssets, newAssets, Owner(), RoomManagedShutdown { }, ::room) { }
        return Fixture(source, target, oldAssets, newAssets, id, bytes)
    }

    private suspend fun addCurrentReference(db: LocalDatabase, assets: Path, bytes: ByteArray): String {
        val id = id(bytes)
        Files.write(assets.resolve(id), bytes)
        db.rows().upsertValues(listOf(KeyValueRow("local_asset:$id", Json.encodeToString(
            CoreAssetMetadata(id, id, "application/octet-stream", bytes.size.toLong(), createdAt = 2, references = emptyList())))))
        val record = LearningRecord("new-note", version = 1, title = "Current note",
            source = LearningSource(snapshot = "snapshot", assetIds = listOf(id)),
            content = buildJsonObject { put("kind", "item"); put("itemKind", "note") })
        val body = Json { encodeDefaults = true }.encodeToString(record)
        db.rows().upsertLearningRecords(listOf(LearningRecordRow("new-note", "item", 1, null, "Current note", false, 0, body)))
        return id
    }

    @Test fun verifiesCurrentOwnedAssetsAndRevokesGrantsWithoutAdmittingRestore(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-restore-preflight")
        try {
            val f = fixture(dir)
            val db = room(f.target)
            val currentBytes = "created after migration".encodeToByteArray()
            val current = addCurrentReference(db, f.newAssets, currentBytes)
            val artifactId = "00000000-0000-0000-0000-000000000001"
            db.rows().upsertValues(listOf(
                KeyValueRow("artifact:$artifactId", """{"id":"$artifactId","title":"App","revisions":[{"revision":1,"files":{},"assets":{"file":"${f.originalId}"},"entry":"index.html","createdAt":1}],"createdAt":1,"updatedAt":1,"trustedOnDevice":true,"approvedDomains":["example.com"]}"""),
                KeyValueRow("artifact_execution_authorization:$artifactId", "device-grant"),
            ))
            try {
                CoreSourceSqliteLease.acquire(f.source).use { lease ->
                    val result = RoomRestorePreflight.inspect(db, f.target, f.newAssets, lease, Owner(), Gate())
                    assertEquals(2, result.verifiedAssets)
                    assertEquals(1, result.resetArtifacts)
                    assertEquals(1, result.resetExecutionGrants)
                    val artifact = Json.parseToJsonElement(db.rows().getValue("artifact:$artifactId")!!.value).jsonObject
                    assertEquals("false", artifact.getValue("trustedOnDevice").jsonPrimitive.content)
                    assertTrue(artifact.getValue("approvedDomains").jsonArray.isEmpty())
                    assertEquals(null, db.rows().getValue("artifact_execution_authorization:$artifactId"))
                    assertNotNull(db.rows().getValue(RoomRestorePreflight.PENDING_KEY))
                    assertContentEquals(currentBytes, Files.readAllBytes(f.newAssets.resolve(current)))
                }
            } finally { db.close() }
            assertFailsWith<IllegalStateException> {
                RoomStoreStartup.withLeasedCoreStore(f.source, f.target, f.oldAssets, f.newAssets, Owner(), RoomManagedShutdown { }, ::room) { }
            }
            assertContentEquals(f.originalBytes, Files.readAllBytes(f.oldAssets.resolve(f.originalId)))
            assertTrue(Files.exists(f.source))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun mismatchedAssetsAndInterruptedScanKeepRecoveryMarkerAndOriginals(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-restore-mismatch")
        try {
            val f = fixture(dir)
            val db = room(f.target)
            val newer = "new snapshot asset".encodeToByteArray()
            val id = addCurrentReference(db, f.newAssets, newer)
            try {
                CoreSourceSqliteLease.acquire(f.source).use { lease ->
                    val interrupted = object : CoreAssetGate {
                        override suspend fun <T> withExclusiveAssets(block: suspend () -> T): T = throw CancellationException("interrupted")
                    }
                    assertFailsWith<CancellationException> { RoomRestorePreflight.inspect(db, f.target, f.newAssets, lease, Owner(), interrupted) }
                    assertNotNull(db.rows().getValue(RoomRestorePreflight.PENDING_KEY))
                    Files.delete(f.newAssets.resolve(id)) // A restored DB paired with an older asset snapshot.
                    assertFails { RoomRestorePreflight.inspect(db, f.target, f.newAssets, lease, Owner(), Gate()) }
                    assertNotNull(db.rows().getValue(RoomRestorePreflight.PENDING_KEY))
                    Files.write(f.newAssets.resolve(id), "incorrect bytes".encodeToByteArray())
                    assertFails { RoomRestorePreflight.inspect(db, f.target, f.newAssets, lease, Owner(), Gate()) }
                    Files.write(f.newAssets.resolve(id), newer)
                    assertEquals(2, RoomRestorePreflight.inspect(db, f.target, f.newAssets, lease, Owner(), Gate()).verifiedAssets)
                    assertNotNull(db.rows().getValue(RoomRestorePreflight.PENDING_KEY))
                }
            } finally { db.close() }
            assertFailsWith<IllegalStateException> {
                RoomStoreStartup.withLeasedCoreStore(f.source, f.target, f.oldAssets, f.newAssets, Owner(), RoomManagedShutdown { }, ::room) { }
            }
            assertContentEquals(f.originalBytes, Files.readAllBytes(f.oldAssets.resolve(f.originalId)))
            assertTrue(Files.exists(f.source))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun wrongRoomOwnerOrMismatchedPairCannotCreateAMarker(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-restore-owner")
        try {
            val f = fixture(dir)
            val other = room(dir.resolve("unrelated.db"))
            try {
                CoreSourceSqliteLease.acquire(f.source).use { lease ->
                    assertFailsWith<IllegalStateException> { RoomRestorePreflight.inspect(other, f.target, f.newAssets, lease, Owner(), Gate()) }
                }
                assertEquals(null, other.rows().getValue(RoomRestorePreflight.PENDING_KEY))
            } finally { other.close() }
            Files.writeString(f.newAssets.resolve(CoreStorePairedCutover.ASSET_MARKER), "unrelated")
            val db = room(f.target)
            try {
                CoreSourceSqliteLease.acquire(f.source).use { lease ->
                    assertFails { RoomRestorePreflight.inspect(db, f.target, f.newAssets, lease, Owner(), Gate()) }
                }
                assertEquals(null, db.rows().getValue(RoomRestorePreflight.PENDING_KEY))
            } finally { db.close() }
            assertContentEquals(f.originalBytes, Files.readAllBytes(f.oldAssets.resolve(f.originalId)))
            assertTrue(Files.exists(f.source))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun rebindFinalizesPendingRestoreOnlyWithExactCurrentSourceIdentity(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-restore-rebind")
        try {
            val f = fixture(dir)
            val db = room(f.target)
            val authorityCalls = java.util.concurrent.atomic.AtomicInteger()
            val authority = RoomRestoreRebindAuthority { authorityCalls.incrementAndGet() }
            try {
                CoreSourceSqliteLease.acquire(f.source).use { lease ->
                    assertFailsWith<IllegalStateException> {
                        RoomRestorePreflight.finalize(db, f.target, f.newAssets, lease, Owner(), Gate(), authority)
                    }
                    assertEquals(0, authorityCalls.get())
                    RoomRestorePreflight.inspect(db, f.target, f.newAssets, lease, Owner(), Gate())
                    val first = RoomRestorePreflight.finalize(db, f.target, f.newAssets, lease, Owner(), Gate(), authority)
                    assertEquals(1, authorityCalls.get())
                    assertEquals(null, db.rows().getValue(RoomRestorePreflight.PENDING_KEY))
                    assertEquals(first.text(), db.rows().getValue(ROOM_RESTORE_REBIND_RECEIPT_KEY)?.value)
                    val repeated = RoomRestorePreflight.finalize(db, f.target, f.newAssets, lease, Owner(), Gate(), authority)
                    assertEquals(first, repeated)
                    assertEquals(2, authorityCalls.get())
                }
            } finally { db.close() }
            RoomStoreStartup.withLeasedCoreStore(f.source, f.target, f.oldAssets, f.newAssets, Owner(),
                RoomManagedShutdown { }, ::room) { admitted ->
                    assertEquals(1, admitted.rows().defaultAgents().size)
                }
            val changed = room(f.target)
            try {
                changed.rows().deleteValue(ROOM_RESTORE_REBIND_RECEIPT_KEY)
            } finally { changed.close() }
            assertFailsWith<IllegalStateException> {
                RoomStoreStartup.withLeasedCoreStore(f.source, f.target, f.oldAssets, f.newAssets, Owner(),
                    RoomManagedShutdown { }, ::room) { }
            }
            val marker = RoomRestoreRebind.markerFor(f.target)
            val original = Files.readString(marker)
            Files.writeString(marker, original.replaceFirst("messenger-room-restore-v1:", "messenger-room-restore-v2:"))
            val restoredReceipt = room(f.target)
            try { restoredReceipt.rows().upsertValues(listOf(KeyValueRow(ROOM_RESTORE_REBIND_RECEIPT_KEY, original))) }
            finally { restoredReceipt.close() }
            assertFailsWith<IllegalStateException> {
                RoomStoreStartup.withLeasedCoreStore(f.source, f.target, f.oldAssets, f.newAssets, Owner(),
                    RoomManagedShutdown { }, ::room) { }
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
