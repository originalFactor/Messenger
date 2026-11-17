/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.cloud.SyncCollection
import cc.ptoe.messenger.data.cloud.SyncSession
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyRoomRecoveryApplyTest {
    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step(); Unit }
    private fun room(path: Path) = Room.databaseBuilder<LocalDatabase>(name = path.toString()).setDriver(BundledSQLiteDriver()).build()

    private class Owner : RoomStoreOwnership {
        var held = false
        override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T {
            check(!held); held = true
            return try { block() } finally { held = false }
        }
    }
    private class Provenance : LegacyRecoveryProvenance {
        var held = false
        var allowed = true
        override suspend fun <T> withVerifiedSnapshot(snapshot: Path, block: suspend () -> T): T {
            check(allowed && !held); held = true
            return try { block() } finally { held = false }
        }
    }
    private data class Fixture(val root: Path, val core: Path, val legacy: Path, val target: Path)
    private suspend fun fixture(projectOwner: String? = null): Fixture {
        val root = Files.createTempDirectory("legacy-room-recovery-apply")
        val core = root.resolve("core.db"); val legacy = root.resolve("legacy-snapshot.db"); val target = root.resolve("room.db")
        BundledSQLiteDriver().open(core.toString()).use { db ->
            checkNotNull(javaClass.getResource("/core-store-v9.sql")).readText().split(';').map(String::trim).filter(String::isNotEmpty).forEach { db.exec(it) }
            db.exec("INSERT INTO agents(id,name,systemPrompt,isDefault,createdAt,updatedAt) VALUES ('a','Agent','',1,1,1)")
            db.exec("INSERT INTO providers(id,name,baseUrl,apiKey,apiFormat,createdAt,updatedAt) VALUES ('p','Provider','https://example','SECRET','chat',1,1)")
            if (projectOwner != null) db.exec("INSERT INTO projects VALUES ('old','Remote project','/remote',${if (projectOwner == "legacy") "NULL" else "'other-device'"},1,1)")
            db.exec("INSERT INTO conversations(id,title,providerId,agentId,createdAt,updatedAt) VALUES ('c','Chat','p','a',1,1)")
            db.exec("INSERT INTO kv VALUES ('device_id','local-device'),('kotlin_sync_session','[\"account\",\"generation\"]'),('cloud_session_host','https://cloud.example')")
        }
        BundledSQLiteDriver().open(legacy.toString()).use { db ->
            db.exec("PRAGMA user_version=22")
            db.exec("CREATE TABLE projects(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,workspace TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
            db.exec("CREATE TABLE conversations(id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL,projectId TEXT)")
            db.exec("CREATE TABLE providers(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,apiKey TEXT NOT NULL,apiFormat TEXT NOT NULL)")
            db.exec("INSERT INTO projects VALUES ('old','Old project','/workspace',1,2)")
            db.exec("INSERT INTO conversations VALUES ('c','Chat','old')")
            db.exec("INSERT INTO providers VALUES ('p','Provider','OTHER-SECRET','responses')")
        }
        RoomStoreStartup.withLeasedCoreStore(core, target, root.resolve("old-assets"), root.resolve("new-assets"), Owner(),
            RoomManagedShutdown { }, ::room) { }
        return Fixture(root, core, legacy, target)
    }
    private fun selection() = LegacyRecoverySelection(setOf("old"), setOf("c"), setOf("p"), "local-device")

    @Test fun explicitSelectionAppliesAtomicallyAndQueuesSyncWithoutChangingSources(): Unit = runBlocking {
        val f = fixture(); val owner = Owner(); val provenance = Provenance()
        try {
            val coreBefore = Files.readAllBytes(f.core); val oldBefore = Files.readAllBytes(f.legacy)
            val result = LegacyRoomRecoveryApply.run(f.core, f.legacy, f.target, owner, provenance, ::room, { 10 }) { preview ->
                assertTrue(owner.held && provenance.held)
                assertEquals("local-device", preview.roomDeviceId)
                assertEquals("/workspace", preview.source.projects.single { it.id == "old" }.legacy?.workspace)
                assertNull(preview.roomProjects["old"])
                assertEquals("chat", preview.roomProviders["p"]?.apiFormat)
                assertEquals("responses", preview.source.providers.single { it.id == "p" }.legacy?.apiFormat)
                assertFalse(preview.toString().contains("SECRET"))
                selection()
            }
            assertEquals(LegacyRecoveryApplied(1, 1, 1), result)
            assertFalse(owner.held || provenance.held)
            val db = room(f.target)
            try {
                assertEquals("local-device", db.rows().project("old")?.deviceId)
                assertEquals("/workspace", db.rows().project("old")?.workspace)
                assertEquals("old", db.rows().conversation("c")?.projectId)
                assertEquals("responses", db.rows().provider("p")?.apiFormat)
                assertEquals("SECRET", db.rows().provider("p")?.apiKey)
                assertEquals(10L, db.rows().conversation("c")?.updatedAt)
                assertEquals(10L, db.rows().provider("p")?.updatedAt)
                val pending = db.cloudSync().pending(SyncSession("account", "generation"))
                assertTrue(pending.any { it.collection == SyncCollection.PROJECT && it.id == "old" })
                assertTrue(pending.any { it.collection == SyncCollection.CONVERSATION && it.id == "c" })
                assertTrue(pending.any { it.collection == SyncCollection.PROVIDER && it.id == "p" })
            } finally { db.close() }
            assertTrue(coreBefore.contentEquals(Files.readAllBytes(f.core)))
            assertTrue(oldBefore.contentEquals(Files.readAllBytes(f.legacy)))
        } finally { f.root.toFile().deleteRecursively() }
    }

    @Test fun foreignOwnedProjectCannotBeModifiedOrAssociated(): Unit = runBlocking {
        val f = fixture(projectOwner = "foreign")
        try {
            for (choice in listOf(LegacyRecoverySelection(projects = setOf("old")), LegacyRecoverySelection(associations = setOf("c")))) {
                assertFails { LegacyRoomRecoveryApply.run(f.core, f.legacy, f.target, Owner(), Provenance(), ::room, { 10 }) { review ->
                    assertEquals("other-device", review.roomProjects["old"]?.deviceId)
                    choice
                } }
            }
            val db = room(f.target)
            try {
                assertEquals("/remote", db.rows().project("old")?.workspace)
                assertNull(db.rows().conversation("c")?.projectId)
            } finally { db.close() }
        } finally { f.root.toFile().deleteRecursively() }
    }

    @Test fun explicitlySelectedLegacyUnownedProjectRetainsItsOwnership(): Unit = runBlocking {
        val f = fixture(projectOwner = "legacy")
        try {
            LegacyRoomRecoveryApply.run(f.core, f.legacy, f.target, Owner(), Provenance(), ::room, { 10 }) {
                LegacyRecoverySelection(projects = setOf("old"), associations = setOf("c"))
            }
            val db = room(f.target)
            try {
                assertEquals("/workspace", db.rows().project("old")?.workspace)
                assertNull(db.rows().project("old")?.deviceId)
                assertEquals("old", db.rows().conversation("c")?.projectId)
            } finally { db.close() }
        } finally { f.root.toFile().deleteRecursively() }
    }

    @Test fun destinationCannotBeOpenedAsLegacySnapshot(): Unit = runBlocking {
        val f = fixture()
        try {
            var opened = false
            assertFails { LegacyRoomRecoveryApply.run(f.core, f.target, f.target, Owner(), Provenance(),
                { path -> opened = true; room(path) }, { 10 }) { selection() } }
            assertFalse(opened)
        } finally { f.root.toFile().deleteRecursively() }
    }

    @Test fun offlineReviewReadsCommittedLegacyWalAndKeepsOriginalBytes(): Unit = runBlocking {
        val f = fixture(); val owner = Owner()
        try {
            BundledSQLiteDriver().open(f.legacy.toString()).use { anchor ->
                anchor.exec("PRAGMA journal_mode=WAL")
                anchor.exec("PRAGMA wal_autocheckpoint=0")
                anchor.exec("UPDATE projects SET workspace='/committed-wal' WHERE id='old'")
                val sourceBefore = Files.readAllBytes(f.legacy)
                val wal = f.root.resolve("legacy-snapshot.db-wal")
                val walBefore = Files.readAllBytes(wal)
                val result = LegacyRoomRecoverySession.run(f.core, f.legacy, f.target, f.root, owner, ::room, { 10 }) { review ->
                    assertTrue(owner.held)
                    assertEquals("/committed-wal", review.source.projects.single { it.id == "old" }.legacy?.workspace)
                    assertFalse(review.toString().contains("SECRET"))
                    selection()
                }
                assertEquals(LegacyRecoveryApplied(1, 1, 1), result)
                assertTrue(sourceBefore.contentEquals(Files.readAllBytes(f.legacy)))
                assertTrue(walBefore.contentEquals(Files.readAllBytes(wal)))
                val db = room(f.target)
                try { assertEquals("/committed-wal", db.rows().project("old")?.workspace) } finally { db.close() }
                Files.list(f.root).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().startsWith("legacy-recovery-") }) }
            }
        } finally { f.root.toFile().deleteRecursively() }
    }

    @Test fun offlineReviewRejectsChangedOriginalAndOverlappingSources(): Unit = runBlocking {
        val f = fixture()
        try {
            var opened = false
            assertFails { LegacyRoomRecoverySession.run(f.core, f.core, f.target, f.root, Owner(),
                { opened = true; room(it) }, { 10 }) { selection() } }
            assertFalse(opened)
            BundledSQLiteDriver().open(f.legacy.toString()).use { anchor ->
                assertFails { LegacyRoomRecoverySession.run(f.core, f.legacy, f.target, f.root, Owner(), ::room, { 10 }) {
                    anchor.exec("UPDATE projects SET workspace='/changed' WHERE id='old'")
                    selection()
                } }
            }
            val db = room(f.target)
            try {
                assertNull(db.rows().project("old"))
                assertNull(db.rows().conversation("c")?.projectId)
                assertEquals("chat", db.rows().provider("p")?.apiFormat)
            } finally { db.close() }
            Files.list(f.root).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().startsWith("legacy-recovery-") }) }
        } finally { f.root.toFile().deleteRecursively() }
    }

    @Test fun offlineReviewPreservesNonemptyRollbackJournalForSeparateRecovery(): Unit = runBlocking {
        val f = fixture()
        try {
            val journal = f.root.resolve("legacy-snapshot.db-journal")
            val evidence = "legacy rollback evidence".toByteArray()
            Files.write(journal, evidence)
            var opened = false
            val failure = assertFailsWith<IllegalStateException> {
                LegacyRoomRecoverySession.run(f.core, f.legacy, f.target, f.root, Owner(),
                    { opened = true; room(it) }, { 10 }) { selection() }
            }
            assertTrue(failure.message.orEmpty().contains("rollback journal"))
            assertFalse(opened)
            assertTrue(evidence.contentEquals(Files.readAllBytes(journal)))
            Files.list(f.root).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().startsWith("legacy-recovery-") }) }
        } finally { f.root.toFile().deleteRecursively() }
    }

    @Test fun offlineReviewCancellationRemovesScratchWithoutApplying(): Unit = runBlocking {
        val f = fixture()
        try {
            val original = Files.readAllBytes(f.legacy)
            assertFailsWith<CancellationException> {
                LegacyRoomRecoverySession.run(f.core, f.legacy, f.target, f.root, Owner(), ::room, { 10 }) {
                    throw CancellationException("user cancelled review")
                }
            }
            assertTrue(original.contentEquals(Files.readAllBytes(f.legacy)))
            val db = room(f.target)
            try { assertNull(db.rows().project("old")) } finally { db.close() }
            Files.list(f.root).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().startsWith("legacy-recovery-") }) }
        } finally { f.root.toFile().deleteRecursively() }
    }

    @Test fun destinationWalAliasToCoreSidecarIsRejectedBeforeRoomOpen(): Unit = runBlocking {
        val f = fixture()
        try {
            val sourceWal = f.root.resolve("core.db-wal")
            val targetWal = f.root.resolve("room.db-wal")
            // Keep an already-open idle connection alive across the lease so SQLite keeps its
            // real committed WAL; no other source descriptor opens or closes while leased.
            BundledSQLiteDriver().open(f.core.toString()).use { anchor ->
                anchor.exec("PRAGMA journal_mode=WAL")
                anchor.exec("PRAGMA wal_autocheckpoint=0")
                anchor.exec("INSERT INTO kv VALUES ('wal-test','evidence')")
                val bytes = Files.readAllBytes(sourceWal)
                assertTrue(bytes.isNotEmpty())
                Files.createLink(targetWal, sourceWal)
                var opened = false
                try {
                    val failure = assertFailsWith<IllegalStateException> { LegacyRoomRecoveryApply.run(f.core, f.legacy, f.target, Owner(), Provenance(),
                        { path -> opened = true; room(path) }, { 10 }) { selection() } }
                    assertTrue(failure.message.orEmpty().contains("aliases Core source"))
                    assertFalse(opened)
                    assertTrue(bytes.contentEquals(Files.readAllBytes(sourceWal)))
                } finally { Files.deleteIfExists(targetWal) }
            }
        } finally { f.root.toFile().deleteRecursively() }
    }

    @Test fun changedDestinationRejectsAllSelectedWritesAndDoesNotHoldWriterDuringReview(): Unit = runBlocking {
        val f = fixture(); val owner = Owner(); val provenance = Provenance()
        var opened: LocalDatabase? = null
        try {
            assertFails { LegacyRoomRecoveryApply.run(f.core, f.legacy, f.target, owner, provenance,
                { path -> room(path).also { opened = it } }, { 10 }) {
                val db = checkNotNull(opened)
                val provider = checkNotNull(db.rows().provider("p"))
                db.rows().upsertProviders(listOf(provider.copy(apiKey = "newer-key")))
                selection()
            } }
            val db = room(f.target)
            try {
                assertNull(db.rows().project("old"))
                assertNull(db.rows().conversation("c")?.projectId)
                assertEquals("chat", db.rows().provider("p")?.apiFormat)
                assertEquals("newer-key", db.rows().provider("p")?.apiKey)
                assertTrue(db.cloudSync().pending(SyncSession("account", "generation")).isEmpty())
            } finally { db.close() }
        } finally { f.root.toFile().deleteRecursively() }
    }

    @Test fun changedSnapshotDeniedProvenanceAndCancellationCannotApply(): Unit = runBlocking {
        val f = fixture(); val owner = Owner(); val provenance = Provenance()
        try {
            provenance.allowed = false
            assertFails { LegacyRoomRecoveryApply.run(f.core, f.legacy, f.target, owner, provenance, ::room, { 10 }) { selection() } }
            provenance.allowed = true
            assertFails { LegacyRoomRecoveryApply.run(f.core, f.legacy, f.target, owner, provenance, ::room, { 10 }) {
                throw CancellationException("review cancelled")
            } }
            assertFails { LegacyRoomRecoveryApply.run(f.core, f.legacy, f.target, owner, provenance, ::room, { 10 }) {
                BundledSQLiteDriver().open(f.legacy.toString()).use { db -> db.exec("UPDATE projects SET workspace='/changed' WHERE id='old'") }
                selection()
            } }
            val db = room(f.target)
            try {
                assertNull(db.rows().project("old"))
                assertNull(db.rows().conversation("c")?.projectId)
                assertEquals("chat", db.rows().provider("p")?.apiFormat)
            } finally { db.close() }
        } finally { f.root.toFile().deleteRecursively() }
    }
}
