/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0.
 */
package cc.ptoe.messenger.data.migration

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CoreStoreInspectorTest {
    private val tables = listOf(
        "providers", "models", "agents", "projects", "conversations", "messages", "sync_meta", "kv",
        "learning_records", "learning_revisions", "learning_review_states", "learning_review_attempts",
    )

    @Test
    fun reportsRowsIncludingUncheckpointedWalWithoutChangingTheStore() {
        val dir = Files.createTempDirectory("core-store-inspect")
        val path = dir.resolve("store.db")
        try {
            BundledSQLiteDriver().open(path.toString()).use { db ->
                fun exec(sql: String) = db.prepare(sql).use { it.step(); Unit }
                exec("PRAGMA journal_mode=WAL")
                exec("PRAGMA wal_autocheckpoint=0")
                tables.forEach { exec("CREATE TABLE \"$it\" (value TEXT)") }
                exec("PRAGMA user_version=9")
                exec("INSERT INTO kv VALUES ('secret')")
                exec("INSERT INTO learning_records VALUES ('record')")
                val inspection = CoreStoreInspector.inspect(path.toString())
                assertEquals(9L, inspection.version)
                assertEquals(1L, inspection.rowCounts["kv"])
                assertEquals(1L, inspection.rowCounts["learning_records"])
                assertFalse(inspection.toString().contains("secret"))
                db.prepare("SELECT value FROM kv").use { statement ->
                    kotlin.test.assertTrue(statement.step())
                    assertEquals("secret", statement.getText(0))
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsUnknownVersionMissingTablesAndMissingFile() {
        val dir = Files.createTempDirectory("core-store-inspect")
        val path = dir.resolve("store.db")
        try {
            assertFailsWith<Exception> { CoreStoreInspector.inspect(path.toString()) }
            assertFalse(Files.exists(path))
            BundledSQLiteDriver().open(path.toString()).use { db ->
                db.prepare("PRAGMA user_version=10").use { it.step() }
                assertFailsWith<IllegalStateException> { CoreStoreInspector.inspect(path.toString()) }
                db.prepare("PRAGMA user_version=9").use { it.step() }
                assertFailsWith<IllegalStateException> { CoreStoreInspector.inspect(path.toString()) }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
