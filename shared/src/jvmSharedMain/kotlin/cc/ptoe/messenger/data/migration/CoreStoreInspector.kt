/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0.
 */
package cc.ptoe.messenger.data.migration

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY

/** Counts only: no provider keys, session cookies, messages, or other user data leave the store. */
internal data class CoreStoreInspection(val version: Long, val rowCounts: Map<String, Long>)

/**
 * Inspect an existing Core store in one SQLite read transaction. This never creates or migrates
 * a database. SQLite itself reads the WAL when present, so a raw copy of store.db is not a safe
 * substitute for this operation. The eventual migration must stop the Core writer before cutover.
 *
 * This is a preflight, not a complete schema or migration validation. Reject unsupported versions
 * and missing tables rather than silently interpreting a different store as an empty one.
 */
internal object CoreStoreInspector {
    private const val SUPPORTED_VERSION = 9L
    private val tables = listOf(
        "providers", "models", "agents", "projects", "conversations", "messages", "sync_meta", "kv",
        "learning_records", "learning_revisions", "learning_review_states", "learning_review_attempts",
    )

    fun inspect(path: String): CoreStoreInspection {
        require(path.isNotBlank()) { "Store path must not be blank" }
        BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY).use { db ->
            db.exec("BEGIN")
            try {
                return inspectTransaction(db)
            } finally {
                db.exec("ROLLBACK")
            }
        }
    }

    /** Caller owns a live transaction; this never opens another descriptor for its database. */
    internal fun inspectTransaction(db: SQLiteConnection): CoreStoreInspection {
        val version = db.longValue("PRAGMA user_version")
        check(version == SUPPORTED_VERSION) { "Unsupported Core store schema version: $version" }
        db.prepare("PRAGMA quick_check").use { statement ->
            check(statement.step() && statement.getText(0) == "ok" && !statement.step()) {
                "Core store integrity check failed"
            }
        }
        val found = buildSet {
            db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").use { statement ->
                while (statement.step()) add(statement.getText(0))
            }
        }
        val missing = tables.filterNot(found::contains)
        check(missing.isEmpty()) { "Core store is missing required tables: ${missing.joinToString()}" }
        val unknown = found.filterNot { it in tables || it.startsWith("sqlite_") }
        check(unknown.isEmpty()) { "Core store has unsupported tables: ${unknown.joinToString()}" }
        return CoreStoreInspection(version, tables.associateWith { db.longValue("SELECT COUNT(*) FROM \"$it\"") })
    }

    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step(); Unit }

    private fun SQLiteConnection.longValue(sql: String): Long = prepare(sql).use {
        check(it.step()) { "Core store query returned no result" }
        it.getLong(0)
    }
}
