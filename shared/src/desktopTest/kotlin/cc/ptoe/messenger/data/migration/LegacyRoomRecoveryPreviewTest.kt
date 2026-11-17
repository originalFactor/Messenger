/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyRoomRecoveryPreviewTest {
    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step(); Unit }

    private fun core(path: Path) {
        BundledSQLiteDriver().open(path.toString()).use { db ->
            checkNotNull(javaClass.getResource("/core-store-v9.sql")).readText().split(';')
                .map(String::trim).filter(String::isNotEmpty).forEach { db.exec(it) }
            db.exec("INSERT INTO agents(id,name,systemPrompt,isDefault,createdAt,updatedAt) VALUES ('a','Agent','',1,1,1)")
            db.exec("INSERT INTO providers(id,name,baseUrl,apiKey,apiFormat,createdAt,updatedAt) VALUES ('p','Core provider','https://example','SECRET-CORE','chat',1,1)")
            db.exec("INSERT INTO providers(id,name,baseUrl,apiKey,apiFormat,createdAt,updatedAt) VALUES ('core-only','Core only','https://example','SECRET-CORE','chat',1,1)")
            db.exec("INSERT INTO projects(id,name,workspace,deviceId,createdAt,updatedAt) VALUES ('p1','New name','/new','device',1,5)")
            db.exec("INSERT INTO projects(id,name,workspace,createdAt,updatedAt) VALUES ('core-only','Core only','/core',2,3)")
            db.exec("INSERT INTO conversations(id,title,providerId,agentId,projectId,createdAt,updatedAt) VALUES ('c','New title','p','a',NULL,1,1)")
            db.exec("INSERT INTO conversations(id,title,providerId,agentId,projectId,createdAt,updatedAt) VALUES ('core-only','Core only','p','a','p1',1,1)")
        }
    }

    private fun legacy(db: SQLiteConnection, version: Int) {
        if (version >= 21) {
            db.exec("CREATE TABLE projects(id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, workspace TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            db.exec("CREATE TABLE conversations(id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, projectId TEXT)")
        } else {
            db.exec("CREATE TABLE conversations(id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL)")
        }
        db.exec("CREATE TABLE providers(id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, apiKey TEXT NOT NULL${if (version >= 22) ", apiFormat TEXT NOT NULL" else ""})")
        db.exec("PRAGMA user_version=$version")
        if (version >= 21) {
            db.exec("INSERT INTO projects VALUES ('p1','Old name','/old',1,2),('legacy-only','Old only','/legacy',2,3)")
            db.exec("INSERT INTO conversations VALUES ('c','Old title','p1'),('legacy-only','Old only','legacy-only')")
        } else {
            db.exec("INSERT INTO conversations VALUES ('c','Old title'),('legacy-only','Old only')")
        }
        db.exec(if (version >= 22) "INSERT INTO providers VALUES ('p','Old provider','SECRET-LEGACY','responses'),('legacy-only','Old only','SECRET-LEGACY','messages')"
            else "INSERT INTO providers VALUES ('p','Old provider','SECRET-LEGACY'),('legacy-only','Old only','SECRET-LEGACY')")
    }

    @Test fun comparisonShowsBothSidesWithoutInferringDeletionOrExposingKeys() {
        val dir = Files.createTempDirectory("legacy-room-preview")
        try {
            val corePath = dir.resolve("core.db"); core(corePath)
            for (version in listOf(21, 22)) {
                val oldPath = dir.resolve("legacy-$version.db")
                BundledSQLiteDriver().open(oldPath.toString()).use { legacy(it, version) }
                val beforeOld = Files.readAllBytes(oldPath); val beforeCore = Files.readAllBytes(corePath)
                CoreSourceSqliteLease.acquire(corePath).use { lease ->
                    val preview = lease.previewLegacySnapshot(oldPath)
                    assertEquals(version.toLong(), preview.legacyVersion)
                    assertEquals(listOf("core-only", "legacy-only", "p1"), preview.projects.map { it.id })
                    assertNull(preview.projects.first().legacy)
                    assertNull(preview.projects[1].core)
                    assertEquals("/old", preview.projects.last().legacy?.workspace)
                    assertFalse(checkNotNull(preview.projects.last().legacy).deviceIdKnown)
                    assertEquals("/new", preview.projects.last().core?.workspace)
                    assertTrue(checkNotNull(preview.projects.last().core).deviceIdKnown)
                    assertEquals("device", preview.projects.last().core?.deviceId)
                    val conversation = preview.conversations.single { it.id == "c" }
                    assertEquals("p1", conversation.legacy?.projectId)
                    assertNull(conversation.core?.projectId)
                    val provider = preview.providers.single { it.id == "p" }
                    assertEquals(if (version == 22) "responses" else null, provider.legacy?.apiFormat)
                    assertEquals("chat", provider.core?.apiFormat)
                    assertFalse(preview.toString().contains("SECRET"))
                }
                assertTrue(beforeOld.contentEquals(Files.readAllBytes(oldPath)))
                assertTrue(beforeCore.contentEquals(Files.readAllBytes(corePath)))
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun rejectsUnsupportedOrIncompleteLegacySchemas() {
        val dir = Files.createTempDirectory("legacy-room-preview-invalid")
        try {
            val corePath = dir.resolve("core.db"); core(corePath)
            CoreSourceSqliteLease.acquire(corePath).use { lease ->
                for (version in listOf(19, 23)) {
                    val oldPath = dir.resolve("legacy-$version.db")
                    BundledSQLiteDriver().open(oldPath.toString()).use { db ->
                        db.exec("PRAGMA user_version=$version")
                        db.exec("CREATE TABLE providers(id TEXT PRIMARY KEY, name TEXT)")
                    }
                    val failure = assertFails { lease.previewLegacySnapshot(oldPath) }
                    assertTrue(failure.message.orEmpty().contains("Unsupported legacy Room schema version"))
                }
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun v20PreviewKeepsProviderAndTitleComparisonWithoutProjectRecovery(): Unit {
        val dir = Files.createTempDirectory("legacy-room-v20")
        try {
            val corePath = dir.resolve("core.db"); core(corePath)
            val oldPath = dir.resolve("legacy-20.db")
            BundledSQLiteDriver().open(oldPath.toString()).use { legacy(it, 20) }
            CoreSourceSqliteLease.acquire(corePath).use { lease ->
                val preview = lease.previewLegacySnapshot(oldPath)
                assertEquals(20L, preview.legacyVersion)
                assertFalse(preview.projectsAvailable)
                assertTrue(preview.projects.all { it.legacy == null })
                val conversation = preview.conversations.single { it.id == "c" }
                assertEquals("Old title", conversation.legacy?.title)
                assertNull(conversation.legacy?.projectId)
                assertEquals("New title", conversation.core?.title)
                val provider = preview.providers.single { it.id == "p" }
                assertEquals("Old provider", provider.legacy?.name)
                assertNull(provider.legacy?.apiFormat)
                assertEquals("chat", provider.core?.apiFormat)
                assertFalse(preview.toString().contains("SECRET"))
                val review = LegacyRecoveryReview(preview, "device", emptyMap(), emptyMap(), emptyMap())
                val choices = LegacyRecoveryChoices(review)
                assertFalse(choices.canRestoreProject("p1"))
                assertFalse(choices.canRestoreAssociation("c", setOf("p1")))
                assertNull(choices.selection(setOf("p1"), emptySet(), emptySet()))
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun readsCommittedWalFromIsolatedCopyWithoutChangingOriginalFiles() {
        val dir = Files.createTempDirectory("legacy-room-preview-wal")
        try {
            val corePath = dir.resolve("core.db"); core(corePath)
            val original = dir.resolve("original.db")
            BundledSQLiteDriver().open(original.toString()).use { writer ->
                writer.exec("PRAGMA journal_mode=WAL")
                writer.exec("PRAGMA wal_autocheckpoint=0")
                legacy(writer, 22)
                val wal = dir.resolve("original.db-wal")
                val oldDb = Files.readAllBytes(original); val oldWal = Files.readAllBytes(wal)
                val snapshot = dir.resolve("snapshot.db")
                Files.copy(original, snapshot)
                Files.copy(wal, dir.resolve("snapshot.db-wal"))
                CoreSourceSqliteLease.acquire(corePath).use { lease ->
                    val preview = lease.previewLegacySnapshot(snapshot)
                    assertEquals("responses", preview.providers.single { it.id == "p" }.legacy?.apiFormat)
                }
                assertTrue(oldDb.contentEquals(Files.readAllBytes(original)))
                assertTrue(oldWal.contentEquals(Files.readAllBytes(wal)))
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun rejectsAliasesAndOversizedFieldsBeforeMaterializingRecords() {
        val dir = Files.createTempDirectory("legacy-room-preview-limits")
        try {
            val corePath = dir.resolve("core.db"); core(corePath)
            val snapshot = dir.resolve("snapshot.db")
            BundledSQLiteDriver().open(snapshot.toString()).use { db ->
                legacy(db, 22)
                db.exec("UPDATE projects SET workspace = replace(hex(zeroblob(300000)), '00', 'x') WHERE id='p1'")
            }
            val sidecar = dir.resolve("snapshot.db-wal")
            Files.createSymbolicLink(sidecar, corePath)
            CoreSourceSqliteLease.acquire(corePath).use { lease ->
                assertFails { lease.previewLegacySnapshot(corePath) }
                assertFails { lease.previewLegacySnapshot(snapshot) }
                Files.delete(sidecar)
                val failure = assertFails { lease.previewLegacySnapshot(snapshot) }
                assertTrue(failure.message.orEmpty().contains("exceeds limit"))
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
