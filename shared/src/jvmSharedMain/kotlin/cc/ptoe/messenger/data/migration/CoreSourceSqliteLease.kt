/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Experimental source-only SQLite writer lease. This is not a complete ownership gate: callers
 * must independently exclude old asset writers, prevent pathname replacement, and ensure that
 * this process never opens/closes another descriptor for the source database or its WAL shm file.
 * On POSIX systems doing so can silently release SQLite's locks even while this connection lives.
 * It is not connected to production startup.
 *
 * Read and copy through this connection exclusively. A second connection may open the destination
 * only. Keep the lease until every new Room writer has stopped and joined; process death releases
 * SQLite's locks. An existing legacy process may resume writing once the lease closes, so restart
 * must also compare the captured source fingerprint before using the migrated database.
 */
internal class CoreSourceSqliteLease private constructor(
    private val source: Path,
    private val sourceKey: Any,
    private val walShmKey: Any?,
    private val db: SQLiteConnection,
) : AutoCloseable {
    private var closed = false

    fun inspect(): CoreStoreInspection {
        verifyIdentity()
        return CoreStoreInspector.inspectTransaction(db)
    }

    /** Caller provides an isolated scratch snapshot and excludes path replacement and old writers. */
    fun previewLegacySnapshot(snapshot: Path): LegacyRoomRecoveryPreview {
        verifyIdentity()
        val path = snapshot.toAbsolutePath().normalize()
        val sourcePaths = listOf("").plus(listOf("-wal", "-shm", "-journal")).map { suffix ->
            source.resolveSibling(source.fileName.toString() + suffix)
        }
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val candidate = path.resolveSibling(path.fileName.toString() + suffix)
            if (Files.exists(candidate, NOFOLLOW_LINKS)) {
                val attributes = Files.readAttributes(candidate, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                check(attributes.isRegularFile && !attributes.isSymbolicLink && coreSourceLinkCount(candidate) == 1L) {
                    "Legacy snapshot and sidecars must be regular files without hard links"
                }
                for (sourcePath in sourcePaths) {
                    if (Files.exists(sourcePath, NOFOLLOW_LINKS)) check(!Files.isSameFile(candidate, sourcePath)) {
                        "Legacy snapshot aliases Core source"
                    }
                }
            } else {
                check(suffix.isNotEmpty() && Files.notExists(candidate, NOFOLLOW_LINKS)) { "Legacy snapshot is unavailable" }
            }
        }
        verifyIdentity()
        return BundledSQLiteDriver().open(path.toString(), SQLITE_OPEN_READONLY).use { legacy ->
            legacy.prepare("BEGIN").use { it.step() }
            try { LegacyRoomRecoveryInspection.compare(legacy, db).also { verifyIdentity() } }
            finally { legacy.prepare("ROLLBACK").use { it.step() } }
        }
    }

    fun fingerprint(): String {
        verifyIdentity()
        return CoreStoreMigration.contentFingerprintTransaction(db)
    }

    /** Matches the cutover marker without opening a second source SQLite connection. */
    fun migrationFingerprint(): String {
        val value = CoreStoreCutover.sourceIdentityFingerprint(source, fingerprint())
        verifyIdentity()
        return value
    }

    fun importInto(destination: Path, markCompleted: Boolean = false, sourceFingerprint: String? = null,
        publicationId: String? = null) {
        requireDistinctDatabasePaths(destination)
        val contentFingerprint = if (markCompleted) fingerprint() else null
        CoreStoreMigration.importTransaction(db, destination, markCompleted, sourceFingerprint, publicationId, contentFingerprint)
        verifyIdentity()
    }

    /** Metadata is read from the held transaction; file bytes require a separate asset migration. */
    fun assetMetadata(): List<Pair<String, String>> {
        verifyIdentity()
        return db.prepare("SELECT key, value FROM kv WHERE substr(key, 1, 12) = 'local_asset:' ORDER BY key").use { rows ->
            buildList {
                while (rows.step()) {
                    check(size < 10_000) { "Too many Core assets" }
                    add(rows.getText(0).removePrefix("local_asset:") to rows.getText(1))
                }
            }
        }
    }

    /** Reject aliases before opening any file: closing an alias may release POSIX SQLite locks. */
    internal fun requireDistinct(path: Path) {
        verifyIdentity()
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            check(!Files.isSameFile(source, path)) { "Path aliases Core source" }
            val shm = source.resolveSibling(source.fileName.toString() + "-shm")
            if (Files.exists(shm, NOFOLLOW_LINKS)) {
                check(!Files.isSameFile(shm, path)) { "Path aliases Core WAL shared memory" }
            } else check(Files.notExists(shm, NOFOLLOW_LINKS)) { "Cannot inspect Core WAL shared memory" }
        } else check(Files.notExists(path, NOFOLLOW_LINKS)) { "Cannot inspect migration path" }
    }

    internal fun requireDistinctDatabasePaths(path: Path) {
        requireDistinct(path)
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            requireDistinct(path.resolveSibling(path.fileName.toString() + suffix))
        }
    }

    private fun verifyIdentity() {
        check(!closed) { "Core source lease is closed" }
        val now = Files.readAttributes(source, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(now.isRegularFile && !now.isSymbolicLink && now.fileKey() == sourceKey && coreSourceLinkCount(source) == 1L) {
            "Core source identity changed or has hard links"
        }
        if (walShmKey != null) {
            val shm = source.resolveSibling(source.fileName.toString() + "-shm")
            val current = Files.readAttributes(shm, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            check(current.isRegularFile && !current.isSymbolicLink && current.fileKey() == walShmKey && coreSourceLinkCount(shm) == 1L) {
                "Core WAL shared memory identity changed or has hard links"
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { db.prepare("ROLLBACK").use { it.step() } } finally { db.close() }
    }

    companion object {
        fun acquire(source: Path): CoreSourceSqliteLease {
            val path = source.toAbsolutePath().normalize()
            val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(before.isRegularFile && !before.isSymbolicLink) { "Core source must be a regular file" }
            val key = requireNotNull(before.fileKey()) { "Core source identity unavailable" }
            require(coreSourceLinkCount(path) == 1L) { "Core source must not have hard links" }
            val db = BundledSQLiteDriver().open(path.toString(), SQLITE_OPEN_READWRITE)
            try {
                db.prepare("PRAGMA busy_timeout=0").use { it.step() }
                db.prepare("BEGIN IMMEDIATE").use { it.step() }
                val walMode = db.prepare("PRAGMA journal_mode").use { row ->
                    check(row.step()) { "Core journal mode unavailable" }
                    row.getText(0).equals("wal", ignoreCase = true)
                }
                val shmKey = if (walMode) {
                    val shm = path.resolveSibling(path.fileName.toString() + "-shm")
                    val attributes = Files.readAttributes(shm, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    check(attributes.isRegularFile && !attributes.isSymbolicLink && coreSourceLinkCount(shm) == 1L) {
                        "Core WAL shared memory must be a regular file without hard links"
                    }
                    requireNotNull(attributes.fileKey()) { "Core WAL identity unavailable" }
                } else null
                val lease = CoreSourceSqliteLease(path, key, shmKey, db)
                lease.verifyIdentity()
                return lease
            } catch (failure: Throwable) {
                try { db.close() } catch (close: Throwable) { failure.addSuppressed(close) }
                throw failure
            }
        }
    }
}
