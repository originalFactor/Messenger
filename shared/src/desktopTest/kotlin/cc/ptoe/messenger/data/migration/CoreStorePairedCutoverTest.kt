/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.cloud.CloudImageRehydrator
import cc.ptoe.messenger.data.cloud.RoomAvatarCache
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

class CoreStorePairedCutoverTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step(); Unit }
    private fun room(path: Path) = Room.databaseBuilder<LocalDatabase>(name = path.toString())
        .setDriver(BundledSQLiteDriver()).build()
    private class Owner : RoomStoreOwnership {
        var held = false
        override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T {
            check(!held)
            held = true
            return try { block() } finally { held = false }
        }
    }
    private fun fixture(dbPath: Path, assets: Path): Pair<String, ByteArray> {
        Files.createDirectory(assets, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val bytes = "durable asset".encodeToByteArray()
        val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        Files.write(assets.resolve(id), bytes)
        BundledSQLiteDriver().open(dbPath.toString()).use { db ->
            checkNotNull(javaClass.getResource("/core-store-v9.sql")).readText().split(';')
                .map(String::trim).filter(String::isNotEmpty).forEach { db.exec(it) }
            db.exec("INSERT INTO agents(id,name,systemPrompt,isDefault,createdAt,updatedAt) VALUES ('default','Default','prompt',1,1,1)")
            db.prepare("INSERT INTO kv(key,value) VALUES (?,?)").use { row ->
                row.bindText(1, "local_asset:$id")
                row.bindText(2, """{"id":"$id","sha256":"$id","mimeType":"application/octet-stream","size":${bytes.size},"createdAt":1,"references":[]}""")
                row.step()
            }
        }
        return id to bytes
    }

    @Test fun importsAndReopensTheSameDatabaseAssetPair() = runBlocking {
        val dir = Files.createTempDirectory("paired-cutover")
        try {
            val source = dir.resolve("old.db")
            val target = dir.resolve("room.db")
            val oldAssets = dir.resolve("old-assets")
            val newAssets = dir.resolve("new-assets")
            val (id, bytes) = fixture(source, oldAssets)
            val owner = Owner()
            var cloud: cc.ptoe.messenger.domain.repository.CloudFacade? = null
            RoomCloudStartup.withLeasedCoreCloud(source, target, oldAssets, newAssets, owner, RoomManagedShutdown { }, ::room,
                AppPreferences(MemoryPreferences()), RoomAvatarCache(dir.resolve("avatars").toString().toPath()),
                CloudImageRehydrator(dir.resolve("images"))) { db, facade ->
                assertTrue(owner.held)
                assertEquals("default", db.rows().defaultAgents().single().id)
                assertContentEquals(bytes, Files.readAllBytes(newAssets.resolve(id)))
                db.rows().upsertValues(listOf(KeyValueRow("post-cutover", "preserved")))
                cloud = facade
            }
            assertFalse(owner.held)
            assertFailsWith<IllegalStateException> { checkNotNull(cloud).pendingLoginChoice() }
            assertTrue(Files.isRegularFile(CoreStorePairedCutover.receiptFor(target)))
            assertContentEquals(bytes, Files.readAllBytes(oldAssets.resolve(id)))
            RoomStoreStartup.withLeasedCoreStore(source, target, oldAssets, newAssets, owner, RoomManagedShutdown { }, ::room) { db ->
                assertEquals("preserved", db.rows().getValue("post-cutover")?.value)
            }
            assertTrue(Files.isRegularFile(source))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun resumesACompletedPairBeforeReceiptAndRejectsCorruptedPartialAssets() = runBlocking {
        val dir = Files.createTempDirectory("paired-recovery")
        try {
            val source = dir.resolve("old.db")
            val target = dir.resolve("room.db")
            val oldAssets = dir.resolve("old-assets")
            val newAssets = dir.resolve("new-assets")
            val (id, bytes) = fixture(source, oldAssets)
            val owner = Owner()
            RoomStoreStartup.withLeasedCoreStore(source, target, oldAssets, newAssets, owner, RoomManagedShutdown { }, ::room) { }
            val receipt = CoreStorePairedCutover.receiptFor(target)
            Files.delete(receipt) // Simulate death after both sides are durable, before the receipt.
            RoomStoreStartup.withLeasedCoreStore(source, target, oldAssets, newAssets, owner, RoomManagedShutdown { }, ::room) { }
            assertTrue(Files.isRegularFile(receipt))
            Files.delete(receipt)
            Files.write(newAssets.resolve(id), "corrupted data".encodeToByteArray())
            assertFails { RoomStoreStartup.withLeasedCoreStore(source, target, oldAssets, newAssets, owner, RoomManagedShutdown { }, ::room) { } }
            assertFalse(Files.exists(receipt))
            assertContentEquals(bytes, Files.readAllBytes(oldAssets.resolve(id)))
            assertEquals("corrupted data", Files.readString(newAssets.resolve(id)))
            assertTrue(Files.isRegularFile(source) && Files.isRegularFile(target))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun rejectsUnrelatedExistingTargetWithoutOverwritingIt() = runBlocking {
        val dir = Files.createTempDirectory("paired-unrelated")
        try {
            val source = dir.resolve("old.db")
            val target = dir.resolve("room.db")
            val oldAssets = dir.resolve("old-assets")
            val newAssets = dir.resolve("new-assets")
            fixture(source, oldAssets)
            Files.writeString(target, "unrelated")
            assertFails { RoomStoreStartup.withLeasedCoreStore(source, target, oldAssets, newAssets, Owner(), RoomManagedShutdown { }, ::room) { } }
            assertEquals("unrelated", Files.readString(target))
            assertFalse(Files.exists(newAssets))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun restoreEvidenceAndAuthorizationResetFailClosed() = runBlocking {
        val dir = Files.createTempDirectory("paired-restore")
        try {
            val source = dir.resolve("old.db")
            val target = dir.resolve("room.db")
            val oldAssets = dir.resolve("old-assets")
            val newAssets = dir.resolve("new-assets")
            fixture(source, oldAssets)
            RoomStoreStartup.withLeasedCoreStore(source, target, oldAssets, newAssets, Owner(), RoomManagedShutdown { }, ::room) { db ->
                db.rows().upsertValues(listOf(
                    KeyValueRow("artifact:one", """{"id":"one","trustedOnDevice":true,"approvedDomains":["example.com"],"state":{"x":1},"unknown":"preserved"}"""),
                    KeyValueRow("artifact_execution_authorization:one", "grant"),
                    KeyValueRow("artifact_execution_authorization:orphan", "grant"),
                    KeyValueRow("unrelated", "preserved"),
                ))
            }
            val restoredSource = dir.resolve("restored.db")
            Files.copy(source, restoredSource)
            CoreSourceSqliteLease.acquire(restoredSource).use { lease ->
                val evidence = assertIs<CoreRestoreEvidence.ContentMatch>(CoreRestoreAssessment.assess(lease, target))
                val database = room(target)
                try {
                    val result = CoreRestoreAuthorizationReset.reset(database, evidence)
                    assertEquals(1, result.artifacts)
                    assertEquals(2, result.executionGrants)
                    val artifact = Json.parseToJsonElement(database.rows().getValue("artifact:one")!!.value).jsonObject
                    assertFalse(artifact.getValue("trustedOnDevice").jsonPrimitive.boolean)
                    assertTrue(artifact.getValue("approvedDomains").jsonArray.isEmpty())
                    assertEquals("preserved", artifact.getValue("unknown").jsonPrimitive.content)
                    assertEquals(1, artifact.getValue("state").jsonObject.getValue("x").jsonPrimitive.content.toInt())
                    assertEquals("preserved", database.rows().getValue("unrelated")?.value)
                    assertTrue(database.rows().valuesByPrefix("artifact_execution_authorization:").isEmpty())
                    assertEquals(0, CoreRestoreAuthorizationReset.reset(database, evidence).executionGrants)
                    database.rows().upsertValues(listOf(
                        KeyValueRow("artifact:one", """{"id":"one","trustedOnDevice":true}"""),
                        KeyValueRow("artifact:two", "broken"),
                        KeyValueRow("artifact_execution_authorization:one", "grant"),
                    ))
                    assertFails { CoreRestoreAuthorizationReset.reset(database, evidence) }
                    assertTrue(Json.parseToJsonElement(database.rows().getValue("artifact:one")!!.value)
                        .jsonObject.getValue("trustedOnDevice").jsonPrimitive.boolean)
                    assertEquals("grant", database.rows().getValue("artifact_execution_authorization:one")?.value)
                    database.rows().deleteValue(CoreStoreMigration.SOURCE_CONTENT_FINGERPRINT_KEY)
                    assertFails { CoreRestoreAuthorizationReset.reset(database, evidence) }
                } finally { database.close() }
                assertEquals(CoreRestoreEvidence.LegacyMarker, CoreRestoreAssessment.assess(lease, target))
            }
            BundledSQLiteDriver().open(restoredSource.toString()).use { db ->
                db.exec("INSERT INTO kv VALUES ('changed','value')")
            }
            // Restore the content marker in Room to demonstrate changed-source detection.
            val db = room(target)
            try {
                val sourceHash = CoreStoreMigration.contentFingerprint(source)
                db.rows().upsertValues(listOf(KeyValueRow(CoreStoreMigration.SOURCE_CONTENT_FINGERPRINT_KEY, sourceHash)))
            } finally { db.close() }
            CoreSourceSqliteLease.acquire(restoredSource).use { lease ->
                assertEquals(CoreRestoreEvidence.ContentChanged, CoreRestoreAssessment.assess(lease, target))
            }
        } finally { dir.toFile().deleteRecursively() }
    }


    @Test fun failedShutdownRetainsRoomSourceLeaseAndOwnerUntilRecovery() = runBlocking {
        org.junit.Assume.assumeTrue("sqlite3 is required", try {
            ProcessBuilder("sqlite3", "-version").start().waitFor() == 0
        } catch (_: Exception) { false })
        val dir = Files.createTempDirectory("paired-shutdown")
        try {
            val source = dir.resolve("old.db")
            val target = dir.resolve("room.db")
            val oldAssets = dir.resolve("old-assets")
            val newAssets = dir.resolve("new-assets")
            fixture(source, oldAssets)
            val owner = Owner()
            var attempts = 0
            var anotherStopped = 0
            val shutdown = RoomManagedShutdown { attempts++; check(attempts > 1) { "worker still active" } }
            shutdown.registerStop { anotherStopped++ }
            val opened = CompletableDeferred<LocalDatabase>()
            supervisorScope {
                val use = async {
                    RoomStoreStartup.withLeasedCoreStore(source, target, oldAssets, newAssets, owner, shutdown, ::room) { db ->
                        opened.complete(db)
                        error("host failed")
                    }
                }
                val db = opened.await()
                withTimeout(10_000) { shutdown.state.first { it is RoomShutdownState.RecoveryRequired } }
                assertTrue(owner.held)
                assertFalse(use.isCompleted)
                assertEquals("default", db.rows().defaultAgents().single().id)
                assertEquals(1, anotherStopped, "remaining stop actions must run even after a failure")
                val process = ProcessBuilder("sqlite3", source.toString(), "INSERT INTO kv VALUES ('other','writer')")
                    .redirectErrorStream(true).start()
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(process.exitValue() != 0, "source lease was released before workers stopped")
                shutdown.retryStop()
                assertFails { use.await() }
                assertFalse(owner.held)
                assertEquals(RoomShutdownState.Stopped, shutdown.state.value)
                assertEquals(2, attempts)
                assertEquals(1, anotherStopped, "successful actions must not be repeated")
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun cancellationStillHoldsOwnershipUntilShutdownRecovery() = runBlocking {
        val dir = Files.createTempDirectory("paired-cancel")
        try {
            val source = dir.resolve("old.db")
            val oldAssets = dir.resolve("old-assets")
            fixture(source, oldAssets)
            val owner = Owner()
            var safe = false
            val shutdown = RoomManagedShutdown { check(safe) { "worker still active" } }
            val entered = CompletableDeferred<Unit>()
            supervisorScope {
                val job = async {
                    RoomStoreStartup.withLeasedCoreStore(source, dir.resolve("room.db"), oldAssets,
                        dir.resolve("new-assets"), owner, shutdown, ::room) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
                entered.await()
                job.cancel()
                withTimeout(10_000) { shutdown.state.first { it is RoomShutdownState.RecoveryRequired } }
                assertTrue(owner.held)
                assertFalse(job.isCompleted)
                safe = true
                shutdown.retryStop()
                assertFails { job.await() }
                assertFalse(owner.held)
            }
        } finally { dir.toFile().deleteRecursively() }
    }

}
