/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class CoreSourceSqliteLeaseTest {
    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step(); Unit }

    private fun fixture(path: Path, journal: String) {
        BundledSQLiteDriver().open(path.toString()).use { db ->
            checkNotNull(javaClass.getResource("/core-store-v9.sql")).readText().split(';')
                .map(String::trim).filter(String::isNotEmpty).forEach { db.exec(it) }
            db.exec("PRAGMA journal_mode=$journal")
            db.exec("INSERT INTO agents(id,name,systemPrompt,isDefault,createdAt,updatedAt) VALUES ('a','Agent','prompt',1,1,1)")
        }
    }

    private fun external(path: Path, sql: String): Pair<Int, String> {
        val child = ProcessBuilder("sqlite3", path.toString(), sql).redirectErrorStream(true).start()
        check(child.waitFor(10, TimeUnit.SECONDS)) { "sqlite3 did not exit" }
        return child.exitValue() to child.inputStream.bufferedReader().readText()
    }

    private fun sqliteAvailable(): Boolean = try {
        val child = ProcessBuilder("sqlite3", "-version").start()
        child.waitFor(5, TimeUnit.SECONDS) && child.exitValue() == 0
    } catch (_: Exception) { false }

    @Test
    fun importsThroughOneHeldSourceConnectionAndExcludesExternalWriters() {
        assumeTrue("sqlite3 executable required for cross-process lock verification", sqliteAvailable())
        for (journal in listOf("DELETE", "WAL")) {
            val dir = Files.createTempDirectory("core-source-lease")
            val source = dir.resolve("source.db")
            val target = dir.resolve("room.db")
            try {
                fixture(source, journal)
                val room = Room.databaseBuilder<LocalDatabase>(name = target.toString()).setDriver(BundledSQLiteDriver()).build()
                try { kotlinx.coroutines.runBlocking { room.rows().upsertValues(emptyList()) } } finally { room.close() }
                val expectedFingerprint = CoreStoreCutover.sourceIdentityFingerprint(source, CoreStoreMigration.contentFingerprint(source))
                val lease = CoreSourceSqliteLease.acquire(source)
                try {
                    assertEquals(9L, lease.inspect().version)
                    val before = lease.fingerprint()
                    assertEquals(expectedFingerprint, lease.migrationFingerprint())
                    val (blocked, reason) = external(source, "INSERT INTO kv VALUES ('external','value');")
                    assertNotEquals(0, blocked, "Legacy writer unexpectedly succeeded ($journal): $reason")
                    assertTrue(reason.contains("locked", ignoreCase = true), reason)
                    lease.importInto(target, markCompleted = true, sourceFingerprint = lease.migrationFingerprint())
                    assertEquals(before, lease.fingerprint())
                    val (stillBlocked, _) = external(source, "INSERT INTO kv VALUES ('external','value');")
                    assertNotEquals(0, stillBlocked, "Import released the source writer lock ($journal)")
                    BundledSQLiteDriver().open(target.toString()).use { copied ->
                        copied.prepare("SELECT name FROM agents WHERE id='a'").use { rows ->
                            assertTrue(rows.step())
                            assertEquals("Agent", rows.getText(0))
                        }
                        copied.prepare("SELECT value FROM kv WHERE key='__messenger_migration_core_v9_source_fingerprint'").use { rows ->
                            assertTrue(rows.step())
                            assertEquals(expectedFingerprint, rows.getText(0))
                        }
                    }
                } finally { lease.close() }
                assertEquals(0, external(source, "INSERT INTO kv VALUES ('external','value');").first)
                assertFails { lease.inspect() }
            } finally { dir.toFile().deleteRecursively() }
        }
    }

    @Test
    fun abruptLockOwnerDeathReleasesSqliteWriterLock() {
        assumeTrue("sqlite3 executable required for cross-process lock verification", sqliteAvailable())
        for (journal in listOf("DELETE", "WAL")) {
            val dir = Files.createTempDirectory("core-source-process-death")
            val source = dir.resolve("source.db")
            var owner: Process? = null
            try {
                fixture(source, journal)
                owner = ProcessBuilder("sqlite3", source.toString()).redirectErrorStream(true).start()
                owner.outputStream.bufferedWriter().apply { write("BEGIN IMMEDIATE; SELECT 'locked';\n"); flush() }
                assertEquals("locked", owner.inputStream.bufferedReader().readLine())
                assertNotEquals(0, external(source, "INSERT INTO kv VALUES ('external','value');").first)
                owner.destroyForcibly()
                assertTrue(owner.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, external(source, "INSERT INTO kv VALUES ('external','value');").first)
            } finally {
                owner?.destroyForcibly()
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun hardLinksAndWalShmReplacementFailBeforeOpeningAliases() {
        assumeTrue("sqlite3 executable required for cross-process lock verification", sqliteAvailable())
        for (journal in listOf("DELETE", "WAL")) {
            val dir = Files.createTempDirectory("core-source-alias")
            val source = dir.resolve("source.db")
            try {
                fixture(source, journal)
                val alias = dir.resolve("alias")
                Files.createLink(alias, source)
                assertFails { CoreSourceSqliteLease.acquire(source) }
                Files.delete(alias)
                CoreSourceSqliteLease.acquire(source).use { lease ->
                    Files.createLink(alias, source)
                    assertFails { lease.requireDistinct(alias) }
                    Files.delete(alias)
                    assertNotEquals(0, external(source, "INSERT INTO kv VALUES ('external','value');").first,
                        "Alias check released source lock ($journal)")
                    if (journal == "WAL") {
                        val shm = dir.resolve("source.db-shm")
                        Files.createLink(alias, shm)
                        assertFails { lease.requireDistinct(alias) }
                        Files.delete(alias)
                        assertNotEquals(0, external(source, "INSERT INTO kv VALUES ('external','value');").first,
                            "WAL alias check released source lock")
                        val saved = dir.resolve("original-shm")
                        Files.move(shm, saved)
                        try {
                            Files.createFile(shm)
                            assertFails { lease.inspect() }
                        } finally {
                            Files.deleteIfExists(shm)
                            Files.move(saved, shm)
                        }
                        assertNotEquals(0, external(source, "INSERT INTO kv VALUES ('external','value');").first)
                    }
                }
            } finally { dir.toFile().deleteRecursively() }
        }
    }

}
