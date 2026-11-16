/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0.
 */
package cc.ptoe.messenger.data.migration

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Offline import of a Core v9 database into an already initialized, empty Room database whose
 * tables preserve the Core v9 columns. Not connected to app startup. The caller must stop Core and
 * all other source/destination writers for the entire cutover and keep the old store and owned
 * asset directory until the new app has been verified. A SQLite snapshot alone is not an asset
 * backup. Snapshot files contain credentials and user data: use a private directory and remove them
 * after successful verification (or retain them securely for recovery).
 */
internal object CoreStoreMigration {
    internal const val MARKER_KEY = "__messenger_migration_core_v9_to_room_v1"
    internal const val MARKER_VALUE = "complete"
    internal const val SOURCE_FINGERPRINT_KEY = "__messenger_migration_core_v9_source_fingerprint"
    /** Path-independent logical digest; unlike the v1 identity fingerprint, this survives restore. */
    internal const val SOURCE_CONTENT_FINGERPRINT_KEY = "__messenger_migration_core_v9_source_content_sha256_v2"

    // Parent tables precede children for foreign-key enforcement.
    private val tables = listOf(
        "providers", "models", "agents", "projects", "conversations", "messages", "sync_meta", "kv",
        "learning_records", "learning_revisions", "learning_review_states", "learning_review_attempts",
    )

    /** Return a self-contained snapshot including committed WAL content. Caller owns its lifetime. */
    fun snapshot(source: Path, privateDirectory: Path): Path {
        require(Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Core store does not exist" }
        require(Files.isDirectory(privateDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Snapshot directory does not exist" }
        val snapshot = Files.createTempFile(privateDirectory, "core-v9-", ".db")
        try {
            BundledSQLiteDriver().open(source.toString(), SQLITE_OPEN_READONLY).use { db ->
                db.prepare("VACUUM INTO ?").use { statement ->
                    statement.bindText(1, snapshot.toAbsolutePath().toString())
                    statement.step()
                }
            }
            CoreStoreInspector.inspect(snapshot.toString())
            return snapshot
        } catch (failure: Throwable) {
            try { Files.deleteIfExists(snapshot) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /**
     * Copy every supported table atomically. Reject non-empty destinations and any column mismatch,
     * including unknown source columns, rather than lose data. The target must have been initialized
     * by Room and closed before this method runs. Nothing in the source is changed.
     */
    fun importSnapshot(snapshot: Path, destination: Path, markCompleted: Boolean = false, sourceFingerprint: String? = null,
        publicationId: String? = null, sourceContentFingerprint: String? = null) {
        require(publicationId == null || (markCompleted && publicationId.matches(Regex("[0-9a-f]{32}"))))
        require(sourceContentFingerprint == null || (markCompleted && sourceContentFingerprint.matches(Regex("[0-9a-f]{64}"))))
        require(Files.isRegularFile(snapshot) && Files.isRegularFile(destination)) { "Both databases must exist" }
        require(!Files.isSameFile(snapshot, destination)) { "Source and destination must differ" }
        BundledSQLiteDriver().open(snapshot.toString(), SQLITE_OPEN_READONLY).use { source ->
            source.execute("BEGIN")
            try { importTransaction(source, destination, markCompleted, sourceFingerprint, publicationId, sourceContentFingerprint) }
            finally { source.execute("ROLLBACK") }
        }
    }

    /** Reads only from the caller's transaction; no second source connection is opened. */
    internal fun importTransaction(source: SQLiteConnection, destination: Path, markCompleted: Boolean = false,
        sourceFingerprint: String? = null, publicationId: String? = null, sourceContentFingerprint: String? = null) {
        require(publicationId == null || (markCompleted && publicationId.matches(Regex("[0-9a-f]{32}"))))
        require(sourceContentFingerprint == null || (markCompleted && sourceContentFingerprint.matches(Regex("[0-9a-f]{64}"))))
        require(Files.isRegularFile(destination)) { "Destination database must exist" }
        val expected = CoreStoreInspector.inspectTransaction(source)
        sourceContentFingerprint?.let { check(it == contentFingerprintTransaction(source)) { "Core source content fingerprint differs" } }
        BundledSQLiteDriver().open(destination.toString(), SQLITE_OPEN_READWRITE).use { target ->
            target.execute("PRAGMA foreign_keys = ON")
            target.execute("BEGIN IMMEDIATE")
            try {
                for (table in tables) {
                    val fromColumns = source.columns(table)
                    val toColumns = target.columns(table)
                    check(fromColumns.isNotEmpty() && fromColumns.toSet() == toColumns.toSet() &&
                        fromColumns.size == toColumns.size) { "Incompatible table: $table" }
                    check(target.scalarLong("SELECT COUNT(*) FROM ${quote(table)}") == 0L) {
                        "Destination table is not empty: $table"
                    }
                }
                for (table in tables) {
                    val columns = source.columns(table)
                    val columnList = columns.joinToString(",") { quote(it) }
                    val placeholders = columns.joinToString(",") { "?" }
                    // Message rowid breaks timestamp ties in Core. Preserve that
                    // insertion order when assigning rowids in the new store.
                    val ordering = if (table == "messages") " ORDER BY rowid" else ""
                    source.prepare("SELECT $columnList FROM ${quote(table)}$ordering").use { rows ->
                        target.prepare("INSERT INTO ${quote(table)} ($columnList) VALUES ($placeholders)").use { insert ->
                            while (rows.step()) {
                                for (index in columns.indices) insert.copyValue(index + 1, rows, index)
                                insert.step()
                                insert.reset()
                                insert.clearBindings()
                            }
                        }
                    }
                    check(target.scalarLong("SELECT COUNT(*) FROM ${quote(table)}") == expected.rowCounts.getValue(table)) {
                        "Imported row count differs: $table"
                    }
                }
                if (markCompleted) {
                    target.prepare("INSERT INTO kv(key, value) VALUES (?, ?)").use { marker ->
                        marker.bindText(1, MARKER_KEY)
                        marker.bindText(2, MARKER_VALUE)
                        marker.step()
                    }
                    sourceFingerprint?.let { value ->
                        target.prepare("INSERT INTO kv(key, value) VALUES (?, ?)").use { marker ->
                            marker.bindText(1, SOURCE_FINGERPRINT_KEY)
                            marker.bindText(2, value)
                            marker.step()
                        }
                    }
                    sourceContentFingerprint?.let { value ->
                        target.prepare("INSERT INTO kv(key, value) VALUES (?, ?)").use { marker ->
                            marker.bindText(1, SOURCE_CONTENT_FINGERPRINT_KEY)
                            marker.bindText(2, value)
                            marker.step()
                        }
                    }
                    publicationId?.let { value ->
                        target.prepare("INSERT INTO kv(key, value) VALUES (?, ?)").use { marker ->
                            marker.bindText(1, ROOM_PUBLICATION_ID_KEY)
                            marker.bindText(2, value)
                            marker.step()
                        }
                    }
                }
                target.prepare("PRAGMA foreign_key_check").use { check(!it.step()) { "Imported foreign keys are invalid" } }
                target.execute("COMMIT")
            } catch (failure: Throwable) {
                try { target.execute("ROLLBACK") } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
                throw failure
            }
        }
    }

    /** Hash supported logical data and message insertion order, including committed WAL content. */
    fun contentFingerprint(path: Path): String = BundledSQLiteDriver().open(path.toString(), SQLITE_OPEN_READONLY).use { db ->
        db.execute("BEGIN")
        try { contentFingerprintTransaction(db) }
        finally { db.execute("ROLLBACK") }
    }

    /** Hash only through the caller's existing source transaction. */
    internal fun contentFingerprintTransaction(db: SQLiteConnection): String {
        CoreStoreInspector.inspectTransaction(db)
        val digest = MessageDigest.getInstance("SHA-256")
        fun bytes(value: ByteArray) {
            digest.update(ByteBuffer.allocate(8).putLong(value.size.toLong()).array())
            digest.update(value)
        }
        fun text(value: String) = bytes(value.toByteArray(Charsets.UTF_8))
        db.prepare("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE substr(name,1,7) != 'sqlite_' ORDER BY type,name").use { rows ->
            while (rows.step()) {
                digest.update(1.toByte())
                for (index in 0..3) {
                    val type = rows.getColumnType(index)
                    digest.update(type.toByte())
                    if (type == SQLITE_DATA_TEXT) text(rows.getText(index)) else check(type == SQLITE_DATA_NULL)
                }
            }
            digest.update(0.toByte())
        }
        for (table in tables) {
            text(table)
            val columns = db.columns(table)
            val primary = db.prepare("PRAGMA table_xinfo(${quote(table)})").use { rows ->
                buildList { while (rows.step()) {
                    val order = rows.getLong(5).toInt()
                    if (order > 0) add(order to rows.getText(1))
                } }.sortedBy { it.first }.map { it.second }
            }
            check(primary.isNotEmpty()) { "Core table has no primary key: $table" }
            bytes(ByteBuffer.allocate(8).putLong(columns.size.toLong()).array())
            columns.forEach(::text)
            // Reordering equal-timestamp messages changes chat history even
            // when all column values remain identical.
            val orderBy = if (table == "messages") "rowid" else primary.joinToString(",") { quote(it) }
            db.prepare("SELECT ${columns.joinToString(",") { quote(it) }} FROM ${quote(table)} ORDER BY $orderBy").use { rows ->
                while (rows.step()) {
                    digest.update(1.toByte())
                    for (index in columns.indices) {
                        val type = rows.getColumnType(index)
                        digest.update(type.toByte())
                        when (type) {
                            SQLITE_DATA_NULL -> Unit
                            SQLITE_DATA_INTEGER -> bytes(ByteBuffer.allocate(8).putLong(rows.getLong(index)).array())
                            SQLITE_DATA_FLOAT -> bytes(ByteBuffer.allocate(8).putLong(rows.getDouble(index).toBits()).array())
                            SQLITE_DATA_TEXT -> text(rows.getText(index))
                            SQLITE_DATA_BLOB -> bytes(rows.getBlob(index))
                            else -> error("Unsupported SQLite value type")
                        }
                    }
                }
                digest.update(0.toByte())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun quote(identifier: String) = "\"${identifier.replace("\"", "\"\"")}\""
    private fun SQLiteConnection.execute(sql: String) = prepare(sql).use { it.step(); Unit }
    private fun SQLiteConnection.scalarLong(sql: String): Long = prepare(sql).use {
        check(it.step()) { "Expected a database result" }
        it.getLong(0)
    }
    private fun SQLiteConnection.columns(table: String): List<String> =
        prepare("PRAGMA table_xinfo(${quote(table)})").use { statement ->
            buildList { while (statement.step()) {
                check(statement.getLong(6) == 0L) { "Unsupported hidden or generated column in $table" }
                add(statement.getText(1))
            } }
        }

    private fun SQLiteStatement.copyValue(targetIndex: Int, source: SQLiteStatement, sourceIndex: Int) {
        when (source.getColumnType(sourceIndex)) {
            SQLITE_DATA_NULL -> bindNull(targetIndex)
            SQLITE_DATA_INTEGER -> bindLong(targetIndex, source.getLong(sourceIndex))
            SQLITE_DATA_FLOAT -> bindDouble(targetIndex, source.getDouble(sourceIndex))
            SQLITE_DATA_TEXT -> bindText(targetIndex, source.getText(sourceIndex))
            SQLITE_DATA_BLOB -> bindBlob(targetIndex, source.getBlob(sourceIndex))
            else -> error("Unsupported SQLite value type")
        }
    }
}
