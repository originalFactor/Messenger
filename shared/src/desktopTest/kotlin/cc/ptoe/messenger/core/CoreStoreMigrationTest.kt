/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0.
 */
package cc.ptoe.messenger.core

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.migration.CoreStoreMigration
import cc.ptoe.messenger.data.migration.ROOM_PUBLICATION_ID_KEY
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.repository.room.RoomMessageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreStoreMigrationTest {
    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step(); Unit }
    private fun SQLiteConnection.scalar(sql: String): String = prepare(sql).use { it.step(); it.getText(0) }
    private fun fixture(path: Path) = BundledSQLiteDriver().open(path.toString()).also { db ->
        checkNotNull(javaClass.getResource("/core-store-v9.sql")).readText().split(';')
            .map(String::trim).filter(String::isNotEmpty).forEach { db.exec(it) }
    }

    @Test
    fun importsCommittedWalRowsAndLeavesSourceAndAssetsIntact() {
        val dir = Files.createTempDirectory("core-migration")
        val source = dir.resolve("source.db")
        val target = dir.resolve("room.db")
        val asset = dir.resolve("image.bin")
        try {
            Files.writeString(asset, "owned asset")
            initializeRoom(target)
            fixture(source).use { db ->
                db.exec("PRAGMA journal_mode=WAL")
                db.exec("PRAGMA wal_autocheckpoint=0")
                listOf(
                    "INSERT INTO providers(id,name,baseUrl,apiKey,createdAt,updatedAt) VALUES ('p','provider','url','secret',1,1)",
                    "INSERT INTO models(id,providerId,modelId,displayName,createdAt) VALUES ('m','p','model','Model',1)",
                    "INSERT INTO agents(id,name,systemPrompt,isDefault,createdAt,updatedAt) VALUES ('a','Agent','prompt',1,1,1)",
                    "INSERT INTO projects(id,name,workspace,createdAt,updatedAt) VALUES ('p1','Project','/workspace',1,1)",
                    "INSERT INTO conversations(id,title,providerId,agentId,projectId,createdAt,updatedAt) VALUES ('c','Chat','p','a','p1',1,1)",
                    "INSERT INTO messages(id,conversationId,role,content,timestamp,status) VALUES ('msg','c','user','hello',1,'sent')",
                    "INSERT INTO sync_meta(accountId,cursor,pendingUpserts,pendingDeletes) VALUES ('account',42,'[1]','[2]')",
                    "INSERT INTO kv VALUES ('cloud_session','cookie')",
                    "INSERT INTO kv VALUES ('local_asset:one','image.bin')",
                    "INSERT INTO learning_records VALUES ('l','note',1,NULL,'title',0,1,'{\"text\":\"note\"}')",
                    "INSERT INTO learning_revisions VALUES ('l',1,'revision')",
                    "INSERT INTO learning_review_states VALUES ('l','state')",
                    "INSERT INTO learning_review_attempts VALUES ('attempt','l',1,'attempt body')",
                ).forEach { db.exec(it) }
                val snapshot = CoreStoreMigration.snapshot(source, dir)
                try {
                    CoreStoreMigration.importSnapshot(snapshot, target)
                    fixtureRead(target).use { copied ->
                        assertEquals("secret", copied.scalar("SELECT apiKey FROM providers WHERE id='p'"))
                        assertEquals("hello", copied.scalar("SELECT content FROM messages WHERE id='msg'"))
                        assertEquals("cookie", copied.scalar("SELECT value FROM kv WHERE key='cloud_session'"))
                        assertEquals("image.bin", copied.scalar("SELECT value FROM kv WHERE key='local_asset:one'"))
                        assertEquals("42", copied.scalar("SELECT cursor FROM sync_meta"))
                        assertEquals("attempt body", copied.scalar("SELECT body FROM learning_review_attempts"))
                    }
                    runBlocking {
                        val copied = room(target)
                        try {
                            assertEquals("cookie", copied.rows().getValue("cloud_session")?.value)
                        } finally { copied.close() }
                    }
                    assertEquals("secret", db.scalar("SELECT apiKey FROM providers WHERE id='p'"))
                    assertEquals("owned asset", Files.readString(asset))
                    assertFailsWith<IllegalStateException> { CoreStoreMigration.importSnapshot(snapshot, target) }
                } finally {
                    Files.deleteIfExists(snapshot)
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun migrationPreservesLegacyMessageOrderWhenTimestampsTie() {
        val dir = Files.createTempDirectory("core-migration-message-order")
        val source = dir.resolve("source.db")
        val target = dir.resolve("room.db")
        try {
            fixture(source).use { db ->
                db.exec("INSERT INTO agents(id,name,systemPrompt,isDefault,createdAt,updatedAt) VALUES ('a','Agent','prompt',1,1,1)")
                db.exec("INSERT INTO conversations(id,title,providerId,agentId,createdAt,updatedAt) VALUES ('c','Chat','p','a',1,1)")
                db.exec("INSERT INTO messages(id,conversationId,role,content,timestamp,status) VALUES ('z','c','user','z',10,'sent')")
                db.exec("INSERT INTO messages(id,conversationId,role,content,timestamp,status) VALUES ('a','c','user','a',10,'sent')")
                val originalFingerprint = CoreStoreMigration.contentFingerprint(source)
                db.exec("DELETE FROM messages WHERE id='z'")
                db.exec("INSERT INTO messages(id,conversationId,role,content,timestamp,status) VALUES ('z','c','user','z',10,'sent')")
                assertTrue(originalFingerprint != CoreStoreMigration.contentFingerprint(source))
            }
            initializeRoom(target)
            val snapshot = CoreStoreMigration.snapshot(source, dir)
            CoreStoreMigration.importSnapshot(snapshot, target)
            runBlocking {
                val db = room(target)
                try {
                    val messages = RoomMessageRepository(db)
                    assertEquals(listOf("a", "z"), messages.getByConversationId("c").first().map { it.id })
                    assertEquals(listOf("a"), messages.getBefore("c", "z", 2).map { it.id })
                } finally { db.close() }
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun publicationIdIsCommittedWithImportAndReservedKeyCollisionRollsBack() {
        val dir = Files.createTempDirectory("core-migration-publication")
        val source = dir.resolve("source.db")
        val target = dir.resolve("room.db")
        val id = "0123456789abcdef0123456789abcdef"
        try {
            fixture(source).use { db ->
                db.exec("INSERT INTO agents(id,name,systemPrompt,isDefault,createdAt,updatedAt) VALUES ('a','Agent','prompt',1,1,1)")
                db.exec("INSERT INTO kv VALUES ('$ROOM_PUBLICATION_ID_KEY','source-owned')")
            }
            initializeRoom(target)
            val snapshot = CoreStoreMigration.snapshot(source, dir)
            assertFailsWith<Exception> {
                CoreStoreMigration.importSnapshot(snapshot, target, markCompleted = true, publicationId = id)
            }
            fixtureRead(target).use { db ->
                assertEquals("0", db.scalar("SELECT COUNT(*) FROM agents"))
                assertEquals("0", db.scalar("SELECT COUNT(*) FROM kv"))
            }
            fixtureRead(source).use { it.exec("DELETE FROM kv WHERE key = '$ROOM_PUBLICATION_ID_KEY'") }
            val clean = CoreStoreMigration.snapshot(source, dir)
            CoreStoreMigration.importSnapshot(clean, target, markCompleted = true, publicationId = id)
            fixtureRead(target).use { db ->
                assertEquals(id, db.scalar("SELECT value FROM kv WHERE key='$ROOM_PUBLICATION_ID_KEY'"))
                assertEquals("complete", db.scalar("SELECT value FROM kv WHERE key='${CoreStoreMigration.MARKER_KEY}'"))
                assertEquals("1", db.scalar("SELECT COUNT(*) FROM agents"))
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun rollsBackAllRowsWhenDestinationInsertFails() {
        val dir = Files.createTempDirectory("core-migration")
        val source = dir.resolve("source.db")
        val target = dir.resolve("room.db")
        try {
            fixture(source).use { db ->
                db.exec("INSERT INTO providers(id,name,baseUrl,apiKey,createdAt,updatedAt) VALUES ('p','provider','url','key',1,1)")
                db.exec("INSERT INTO agents(id,name,systemPrompt,isDefault,createdAt,updatedAt) VALUES ('a','Agent','prompt',1,1,1)")
            }
            initializeRoom(target)
            fixtureRead(target).use { it.exec("CREATE TRIGGER fail_agents BEFORE INSERT ON agents BEGIN SELECT RAISE(ABORT, 'test'); END") }
            val snapshot = CoreStoreMigration.snapshot(source, dir)
            assertFailsWith<Exception> { CoreStoreMigration.importSnapshot(snapshot, target) }
            fixtureRead(target).use { db ->
                assertEquals("0", db.scalar("SELECT COUNT(*) FROM providers"))
                assertEquals("0", db.scalar("SELECT COUNT(*) FROM agents"))
            }
            assertTrue(Files.isRegularFile(source))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsUnknownTablesAndColumnsWithoutLeavingPartialDataOrSnapshots() {
        val dir = Files.createTempDirectory("core-migration")
        val source = dir.resolve("source.db")
        val target = dir.resolve("room.db")
        try {
            fixture(source).use { it.exec("CREATE TABLE future_data (value TEXT)") }
            assertFailsWith<IllegalStateException> { CoreStoreMigration.snapshot(source, dir) }
            Files.list(dir).use { files ->
                assertEquals(listOf("source.db"), files.map { it.fileName.toString() }.sorted().toList())
            }
            fixtureRead(source).use { db ->
                db.exec("DROP TABLE future_data")
                db.exec("ALTER TABLE providers ADD COLUMN future_value TEXT")
            }
            initializeRoom(target)
            val snapshot = CoreStoreMigration.snapshot(source, dir)
            assertFailsWith<IllegalStateException> { CoreStoreMigration.importSnapshot(snapshot, target) }
            fixtureRead(target).use { assertEquals("0", it.scalar("SELECT COUNT(*) FROM providers")) }
            val generated = dir.resolve("generated.db")
            fixture(generated).use { it.exec("ALTER TABLE providers ADD COLUMN derived TEXT GENERATED ALWAYS AS (name) VIRTUAL") }
            val generatedSnapshot = CoreStoreMigration.snapshot(generated, dir)
            assertFailsWith<IllegalStateException> { CoreStoreMigration.importSnapshot(generatedSnapshot, target) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun room(path: Path) = Room.databaseBuilder<LocalDatabase>(name = path.toString())
        .setDriver(BundledSQLiteDriver()).build()

    private fun initializeRoom(path: Path) = runBlocking {
        val db = room(path)
        try { db.rows().upsertValues(emptyList()) } finally { db.close() }
    }

    private fun fixtureRead(path: Path) = BundledSQLiteDriver().open(path.toString())
}
