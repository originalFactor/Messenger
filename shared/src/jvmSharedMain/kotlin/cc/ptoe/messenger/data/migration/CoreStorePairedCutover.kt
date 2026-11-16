/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * Experimental paired publication for an existing Core store. Caller keeps [lease] and an
 * independently proven Room ownership gate for this whole operation and the Room lifetime.
 * A completion receipt admits only a Room database and asset directory with the same random ID
 * and source fingerprint. Original data is never removed. Incomplete or invalid objects remain
 * for offline recovery; a fully verified interrupted publication can be resumed without overwrite.
 */
internal object CoreStorePairedCutover {
    internal const val ASSET_MARKER = ".messenger-room-pair-v1"
    private const val HEADER = "messenger-room-pair-v1:"
    private val privateMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

    private data class Identity(val id: String, val fingerprint: String) {
        fun text() = "$HEADER$id:$fingerprint\n"
    }

    internal fun receiptFor(target: Path): Path = target.resolveSibling(".${target.fileName}.room-pair-v1")
    private fun stageFor(target: Path, id: String): Path = target.resolveSibling(".${target.fileName}.room-pair-$id.db")

    /** Restore preflight only: these markers establish publication identity, not backup age. */
    internal fun verifyRestorePair(lease: CoreSourceSqliteLease, destination: Path, destinationAssets: Path,
        evidence: CoreRestoreEvidence.ContentMatch) {
        val target = destination.toAbsolutePath().normalize()
        val assets = destinationAssets.toAbsolutePath().normalize()
        require(assets.parent == target.parent && assets != target) { "Room database and assets require a shared private parent" }
        requireRoomPublicationParent(requireNotNull(target.parent))
        requireRoomPublicationParent(assets)
        lease.requireDistinctDatabasePaths(target)
        val receipt = receiptFor(target)
        val assetMarker = assets.resolve(ASSET_MARKER)
        lease.requireDistinct(receipt)
        lease.requireDistinct(assetMarker)
        val expected = Identity(evidence.publicationId, evidence.previousIdentity)
        check(readMarker(receipt) == expected && readMarker(assetMarker) == expected) {
            "Restored database and asset publication markers differ"
        }
    }

    suspend fun migrate(lease: CoreSourceSqliteLease, sourceAssets: Path, destination: Path, destinationAssets: Path,
        initializeRoom: suspend (Path) -> Unit, verifyRoom: suspend (Path) -> Unit): CutoverResult {
        val target = destination.toAbsolutePath().normalize()
        val assets = destinationAssets.toAbsolutePath().normalize()
        val parent = requireNotNull(target.parent)
        require(assets.parent == parent && assets != target) { "Room database and assets require a shared private parent" }
        requireRoomPublicationParent(parent)
        syncRoomPublicationDirectory(parent)
        val receipt = receiptFor(target)
        val assetMarker = assets.resolve(ASSET_MARKER)
        lease.requireDistinctDatabasePaths(target)
        lease.requireDistinct(receipt)
        lease.requireDistinct(assetMarker)
        val fingerprint = lease.migrationFingerprint()

        val identity = when {
            Files.exists(receipt, NOFOLLOW_LINKS) -> readMarker(receipt)
            Files.exists(assets, NOFOLLOW_LINKS) -> {
                requireRoomPublicationParent(assets)
                readMarker(assetMarker)
            }
            else -> {
                check(Files.notExists(target, NOFOLLOW_LINKS)) { "Room database exists without its asset directory" }
                Files.createDirectory(assets, privateMode)
                syncRoomPublicationDirectory(parent)
                val created = Identity(UUID.randomUUID().toString().replace("-", ""), fingerprint)
                writeMarker(assetMarker, created)
                created
            }
        }
        check(identity.fingerprint == fingerprint) { "Core source changed since the paired migration began" }
        requireRoomPublicationParent(assets)
        check(readMarker(assetMarker) == identity) { "Room asset identity does not match the publication" }
        val stage = stageFor(target, identity.id)
        lease.requireDistinctDatabasePaths(stage)

        if (Files.exists(receipt, NOFOLLOW_LINKS)) {
            verifyPublishedRoomDatabase(target, ::syncRoomPublicationDirectory) { path ->
                verifyDatabase(path, identity, verifyRoom)
            }
            cleanupStage(stage, target, identity)
            return CutoverResult.ALREADY_IMPORTED
        }

        // Existing complete files are verified before reuse. A partial or unexpected file is never
        // deleted or replaced, and remains available for explicit recovery.
        CoreAssetTransfer.resume(lease, sourceAssets, assets, ASSET_MARKER)
        if (Files.notExists(target, NOFOLLOW_LINKS)) {
            if (Files.notExists(stage, NOFOLLOW_LINKS)) {
                Files.createFile(stage, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                initializeRoom(stage)
                lease.importInto(stage, markCompleted = true, sourceFingerprint = fingerprint, publicationId = identity.id)
                verifyDatabase(stage, identity, verifyRoom)
                CoreStoreCutover.finalizeDatabase(stage)
            } else {
                verifyDatabase(stage, identity, verifyRoom)
                CoreStoreCutover.finalizeDatabase(stage)
            }
            publishRoomDatabase(stage, target, if (roomPublicationUsesReceipt) identity.id else null,
                ::syncRoomPublicationDirectory) { path -> verifyDatabase(path, identity, verifyRoom) }
        }
        verifyPublishedRoomDatabase(target, ::syncRoomPublicationDirectory) { path -> verifyDatabase(path, identity, verifyRoom) }
        // This is the sole admission marker and is written only after both sides are durable.
        writeMarker(receipt, identity)
        cleanupStage(stage, target, identity)
        return CutoverResult.IMPORTED
    }

    private suspend fun verifyDatabase(path: Path, identity: Identity, verifyRoom: suspend (Path) -> Unit) {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Room database is missing or invalid" }
        BundledSQLiteDriver().open(path.toString(), SQLITE_OPEN_READONLY).use { db ->
            db.prepare("PRAGMA quick_check").use { result ->
                check(result.step() && result.getText(0) == "ok" && !result.step()) { "Room database integrity check failed" }
            }
            db.prepare("SELECT key, value FROM kv WHERE key IN (?, ?, ?)").use { rows ->
                rows.bindText(1, CoreStoreMigration.MARKER_KEY)
                rows.bindText(2, CoreStoreMigration.SOURCE_FINGERPRINT_KEY)
                rows.bindText(3, ROOM_PUBLICATION_ID_KEY)
                val values = buildMap { while (rows.step()) put(rows.getText(0), rows.getText(1)) }
                check(values[CoreStoreMigration.MARKER_KEY] == CoreStoreMigration.MARKER_VALUE &&
                    values[CoreStoreMigration.SOURCE_FINGERPRINT_KEY] == identity.fingerprint &&
                    values[ROOM_PUBLICATION_ID_KEY] == identity.id) { "Room database identity does not match the publication" }
            }
        }
        verifyRoom(path)
    }

    private fun readMarker(path: Path): Identity {
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(before.isRegularFile && !before.isSymbolicLink && before.fileKey() != null && before.size() in 1L..128L) {
            "Paired migration marker is invalid"
        }
        val bytes = FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(before.size().toInt())
            while (buffer.hasRemaining()) check(channel.read(buffer) > 0) { "Incomplete paired migration marker" }
            check(channel.read(ByteBuffer.allocate(1)) == -1) { "Invalid paired migration marker" }
            buffer.array()
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(after.isRegularFile && !after.isSymbolicLink && after.fileKey() == before.fileKey() && after.size() == before.size())
        val value = bytes.toString(Charsets.US_ASCII)
        val match = Regex("^messenger-room-pair-v1:([0-9a-f]{32}):([0-9a-f]{64})\\n$").matchEntire(value)
            ?: error("Paired migration marker is incomplete")
        return Identity(match.groupValues[1], match.groupValues[2])
    }

    private fun writeMarker(path: Path, identity: Identity) {
        val buffer = ByteBuffer.wrap(identity.text().toByteArray(Charsets.US_ASCII))
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { file ->
            while (buffer.hasRemaining()) check(file.write(buffer) > 0) { "Marker write made no progress" }
            file.force(true)
        }
        syncRoomPublicationDirectory(requireNotNull(path.parent))
        check(readMarker(path) == identity)
    }

    private fun cleanupStage(stage: Path, target: Path, identity: Identity) {
        if (Files.notExists(stage, NOFOLLOW_LINKS)) return
        check(Files.isRegularFile(stage, NOFOLLOW_LINKS)) { "Invalid paired migration stage" }
        // A completed stage has these values in one transaction; do not remove an unrelated file.
        // Desktop publishes by hard link, so the already-validated target proves stage identity.
        // Avoid opening that same inode under a second name: SQLite could create a distinct WAL.
        if (!Files.isSameFile(stage, target)) {
            BundledSQLiteDriver().open(stage.toString(), SQLITE_OPEN_READONLY).use { db ->
                db.prepare("SELECT value FROM kv WHERE key=?").use { row ->
                    row.bindText(1, ROOM_PUBLICATION_ID_KEY)
                    check(row.step() && row.getText(0) == identity.id && !row.step()) { "Unrecognized paired migration stage" }
                }
            }
        }
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            check(Files.notExists(stage.resolveSibling(stage.fileName.toString() + suffix), NOFOLLOW_LINKS)) {
                "Paired migration stage needs offline recovery ($suffix)"
            }
        }
        // Room can retain an empty .lck coordination file after closing its database. It contains
        // no user records; leave it in place rather than remove a lock file another owner may hold.
        Files.delete(stage)
        syncRoomPublicationDirectory(requireNotNull(stage.parent))
    }
}
