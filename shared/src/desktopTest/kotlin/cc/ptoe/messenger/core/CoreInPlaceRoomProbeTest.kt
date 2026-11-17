/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.core

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Feasibility test only. Production must not open the Core database as Room: this test does not
 * establish old-writer exclusion, backup or Windows crash and filesystem guarantees.
 */
class CoreInPlaceRoomProbeTest {
    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step(); Unit }
    private fun SQLiteConnection.scalar(sql: String): String = prepare(sql).use { rows ->
        check(rows.step()); rows.getText(0)
    }

    private fun fixture(path: Path): SQLiteConnection = BundledSQLiteDriver().open(path.toString()).also { db ->
        checkNotNull(javaClass.getResource("/core-store-v9.sql")).readText().split(';')
            .map(String::trim).filter(String::isNotEmpty).forEach { db.exec(it) }
        listOf(
            "INSERT INTO providers(id,name,baseUrl,apiKey,createdAt,updatedAt) VALUES ('p','Provider','url','secret',1,1)",
            "INSERT INTO agents(id,name,systemPrompt,isDefault,createdAt,updatedAt) VALUES ('a','Agent','prompt',1,1,1)",
            "INSERT INTO conversations(id,title,providerId,agentId,createdAt,updatedAt) VALUES ('c','Chat','p','a',1,1)",
            "INSERT INTO messages(id,conversationId,role,content,timestamp,status) VALUES ('z','c','user','first',7,'sent')",
            "INSERT INTO messages(id,conversationId,role,content,timestamp,status) VALUES ('a','c','assistant','second',7,'sent')",
            "INSERT INTO kv VALUES ('cloud_session','cookie')",
            "INSERT INTO learning_records VALUES ('l','note',1,NULL,'title',0,1,'note')",
        ).forEach { db.exec(it) }
    }

    // This deliberate downgrade exists only because the isolated Room schema is currently v1.
    // A real cutover must choose a newer schema version and a supported migration path.
    private fun migration(fail: Boolean = false) = object : Migration(9, 1) {
        override fun migrate(connection: SQLiteConnection) {
            connection.exec("CREATE INDEX IF NOT EXISTS index_conversations_projectId ON conversations(projectId)")
            if (fail) error("intentional migration failure")
        }
    }

    private fun room(path: Path, migration: Migration): LocalDatabase =
        Room.databaseBuilder<LocalDatabase>(name = path.toString())
            .setDriver(BundledSQLiteDriver()).addMigrations(migration).build()

    @Test fun roomOpensMigratedFixtureAndPreservesRowsAndMessageOrder(): Unit = runBlocking {
        val dir = Files.createTempDirectory("core-in-place-room-probe")
        val path = dir.resolve("core.db")
        try {
            fixture(path).close()
            val db = room(path, migration())
            try {
                assertEquals("cookie", db.rows().getValue("cloud_session")?.value)
                assertEquals("secret", db.rows().provider("p")?.apiKey)
                assertEquals("a", db.rows().defaultAgents().single().id)
                assertEquals(listOf("z", "a"), db.rows().observeMessages("c").first().map { it.id })
                assertEquals("note", db.learningReview().recordBody("l"))
            } finally { db.close() }
            BundledSQLiteDriver().open(path.toString()).use { raw ->
                assertEquals("1", raw.scalar("PRAGMA user_version"))
                assertEquals("ok", raw.scalar("PRAGMA quick_check"))
                assertEquals("1", raw.scalar("SELECT COUNT(*) FROM room_master_table WHERE id = 42"))
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun migrationExceptionRollsBackSchemaAndData(): Unit = runBlocking {
        val dir = Files.createTempDirectory("core-in-place-room-rollback")
        val path = dir.resolve("core.db")
        try {
            fixture(path).close()
            val db = room(path, migration(fail = true))
            try { assertFailsWith<IllegalStateException> { db.rows().getValue("cloud_session") } }
            finally { db.close() }
            BundledSQLiteDriver().open(path.toString()).use { raw ->
                assertEquals("9", raw.scalar("PRAGMA user_version"))
                assertEquals("cookie", raw.scalar("SELECT value FROM kv WHERE key='cloud_session'"))
                assertEquals("0", raw.scalar("SELECT COUNT(*) FROM sqlite_master WHERE name='index_conversations_projectId'"))
                assertEquals("0", raw.scalar("SELECT COUNT(*) FROM sqlite_master WHERE name='room_master_table'"))
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun roomRejectsUnknownColumnAndRollsBackMigration(): Unit = runBlocking {
        val dir = Files.createTempDirectory("core-in-place-room-validation")
        val path = dir.resolve("core.db")
        try {
            fixture(path).use { it.exec("ALTER TABLE kv ADD COLUMN unexpected TEXT") }
            val db = room(path, migration())
            try { assertFailsWith<IllegalStateException> { db.rows().getValue("cloud_session") } }
            finally { db.close() }
            BundledSQLiteDriver().open(path.toString()).use { raw ->
                assertEquals("9", raw.scalar("PRAGMA user_version"))
                assertEquals("cookie", raw.scalar("SELECT value FROM kv WHERE key='cloud_session'"))
                assertEquals("0", raw.scalar("SELECT COUNT(*) FROM sqlite_master WHERE name='index_conversations_projectId'"))
                assertFalse(raw.scalar("SELECT COUNT(*) FROM pragma_table_info('kv') WHERE name='unexpected'") == "0")
                assertTrue(raw.scalar("PRAGMA quick_check") == "ok")
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
