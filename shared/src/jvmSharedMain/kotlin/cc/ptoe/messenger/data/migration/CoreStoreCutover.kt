/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0.
 */
package cc.ptoe.messenger.data.migration

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

/** The implementation must stop Core and exclude all source and destination writers throughout. */
internal interface CoreCutoverGate {
    suspend fun <T> withCoreStopped(block: suspend () -> T): T
}

internal enum class CutoverResult { IMPORTED, ALREADY_IMPORTED }

/**
 * Prepare and publish an independent Room database; never edits the Core database or its assets.
 * Both Room callbacks must close all database handles before returning. The caller supplies a private
 * destination directory and must retain the source, WAL and assets through verification/recovery.
 * This coordinator is not wired to app startup yet.
 */
internal object CoreStoreCutover {

    suspend fun migrate(
        source: Path,
        destination: Path,
        gate: CoreCutoverGate,
        initializeRoom: suspend (Path) -> Unit,
        verifyRoom: suspend (Path) -> Unit,
    ): CutoverResult = migrateWithDirectorySync(source, destination, gate, initializeRoom, ::syncRoomPublicationDirectory, verifyRoom)

    /** Injectable only so filesystem failure paths can be exercised without changing the host. */
    internal suspend fun migrateWithDirectorySync(
        source: Path,
        destination: Path,
        gate: CoreCutoverGate,
        initializeRoom: suspend (Path) -> Unit,
        syncParent: (Path) -> Unit,
        verifyRoom: suspend (Path) -> Unit,
    ): CutoverResult = gate.withCoreStopped {
        val target = destination.toAbsolutePath().normalize()
        val oldStore = source.toAbsolutePath().normalize()
        require(oldStore != target) { "Core and Room stores must be distinct" }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            check(!Files.isSymbolicLink(target)) { "Room target cannot be a symbolic link" }
            if (Files.exists(oldStore, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSameFile(oldStore, target)) { "Core and Room stores must not share an inode" }
            }
        }
        val parent = requireNotNull(target.parent) { "Destination must have a parent directory" }
        requireRoomPublicationParent(parent)
        // Preflight before creating anything; unsupported directory fsync is not a durable cutover.
        syncParent(parent)
        // A previous process may have died with unverified user data or credentials here. Do not
        // guess whether it is safe to delete: fail closed until the directory is inspected.
        val work = parent.resolve(".${target.fileName}.core-v9-cutover")
        check(!Files.exists(work, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            "An interrupted Core migration requires recovery: $work"
        }
        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            verifyPublishedRoomDatabase(target, syncParent) { path ->
                check(!Files.isSymbolicLink(path) && Files.isRegularFile(path) && hasMarker(path)) {
                    "Destination exists without a completed Core migration"
                }
                check(storedFingerprint(path) == sourceFingerprint(source, source)) {
                    "Core source changed since migration; manual recovery is required"
                }
                verifyRoom(path)
            }
            return@withCoreStopped CutoverResult.ALREADY_IMPORTED
        }
        Files.createDirectory(work, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
        ))
        var staged: Path? = null
        var snapshot: Path? = null
        var failure: Throwable? = null
        try {
            val temporary = Files.createTempFile(work, "room-", ".db")
            staged = temporary
            initializeRoom(temporary)
            snapshot = CoreStoreMigration.snapshot(source, work)
            val fingerprint = sourceFingerprint(source, snapshot)
            val publicationId = if (roomPublicationUsesReceipt) java.util.UUID.randomUUID().toString().replace("-", "") else null
            CoreStoreMigration.importSnapshot(snapshot, temporary, markCompleted = true, sourceFingerprint = fingerprint,
                publicationId = publicationId)
            verifyRoom(temporary)
            finalizeDatabase(temporary)
            publishRoomDatabase(temporary, target, publicationId, syncParent, verifyRoom)
            CutoverResult.IMPORTED
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanup = mutableListOf<Path>()
            snapshot?.let(cleanup::add)
            staged?.let { file ->
                cleanup.add(file)
                for (suffix in listOf("-wal", "-shm", "-journal", ".lck")) {
                    cleanup.add(file.resolveSibling(file.fileName.toString() + suffix))
                }
            }
            cleanup.add(work)
            var cleanupFailure: Throwable? = null
            for (path in cleanup) try { Files.deleteIfExists(path) } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
            }
            cleanupFailure?.let { if (failure != null) failure.addSuppressed(it) else throw it }
        }
    }

    private fun hasMarker(path: Path): Boolean =
        BundledSQLiteDriver().open(path.toString(), SQLITE_OPEN_READONLY).use { db ->
            db.prepare("SELECT value FROM kv WHERE key = ?").use { statement ->
                statement.bindText(1, CoreStoreMigration.MARKER_KEY)
                statement.step() && statement.getText(0) == CoreStoreMigration.MARKER_VALUE
            }
        }

    private fun storedFingerprint(path: Path): String? =
        BundledSQLiteDriver().open(path.toString(), SQLITE_OPEN_READONLY).use { db ->
            db.prepare("SELECT value FROM kv WHERE key = ?").use { statement ->
                statement.bindText(1, CoreStoreMigration.SOURCE_FINGERPRINT_KEY)
                if (statement.step()) statement.getText(0) else null
            }
        }

    private fun sourceFingerprint(source: Path, content: Path): String {
        return sourceIdentityFingerprint(source, CoreStoreMigration.contentFingerprint(content))
    }

    /** Compose the same fingerprint when content was read through an already held source lease. */
    internal fun sourceIdentityFingerprint(source: Path, contentHash: String): String {
        val absolute = source.toAbsolutePath().normalize()
        val attributes = Files.readAttributes(absolute, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink) { "Core source must be a regular file" }
        val key = requireNotNull(attributes.fileKey()) { "Core source identity is unavailable" }.toString()
        val path = absolute.toRealPath().toString()
        val identity = listOf(path, key, contentHash).joinToString("\u0000").toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(identity).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    internal fun requirePrivateParent(parent: Path) {
        require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) && Files.getFileStore(parent).supportsFileAttributeView("posix")) {
            "Private Room storage cannot be verified on this platform"
        }
        val permissions = Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS)
        val others = setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
        require(permissions.none { it in others }) { "Room storage must be private" }
    }

    internal fun finalizeDatabase(path: Path) {
        BundledSQLiteDriver().open(path.toString(), SQLITE_OPEN_READWRITE).use { db ->
            db.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { statement ->
                check(statement.step() && statement.getLong(0) == 0L) { "Room checkpoint is busy" }
            }
            db.prepare("PRAGMA journal_mode=DELETE").use { statement ->
                check(statement.step() && statement.getText(0).equals("delete", ignoreCase = true)) {
                    "Could not finalize Room journal"
                }
            }
        }
        FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
    }
}
