/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Offline user-review entry. [ownership] must exclude all old Core, legacy, Room and detached
 * workers, including SQLite checkpointers, and prevent pathname replacement until return. A
 * point-in-time process check is insufficient. No production host currently supplies that proof.
 * Originals are never opened by SQLite or written; a private WAL-aware scratch copy is discarded.
 */
internal object LegacyRoomRecoverySession {
    private const val MAX_BYTES = 1024L * 1024 * 1024
    private val durableSuffixes = listOf("", "-wal", "-journal")
    private val allSuffixes = listOf("", "-wal", "-shm", "-journal")
    private val fileMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    private val directoryMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

    suspend fun run(core: Path, legacy: Path, destination: Path, scratchParent: Path,
        ownership: RoomStoreOwnership, openRoom: (Path) -> LocalDatabase, now: () -> Long,
        choose: suspend (LegacyRecoveryReview) -> LegacyRecoverySelection?): LegacyRecoveryApplied? =
        ownership.withExclusiveOwnership {
            val source = legacy.toAbsolutePath().normalize()
            val corePath = core.toAbsolutePath().normalize()
            val target = destination.toAbsolutePath().normalize()
            // Before any source/Core SQLite descriptor opens, compare all main and sidecar paths.
            requireDisjoint(source, corePath, target)
            val parent = scratchParent.toAbsolutePath().normalize()
            // Android verifies private ownership and mode with lstat/fstat; Files.getFileStore
            // can be denied there even for an app-private directory. Desktop uses its POSIX gate.
            requireRoomPublicationParent(parent)
            check(Files.getOwner(parent, NOFOLLOW_LINKS) == Files.getOwner(source, NOFOLLOW_LINKS)) {
                "Legacy source and private scratch must have the same owner"
            }
            val directory = Files.createTempDirectory(parent, "legacy-recovery-", directoryMode)
            val snapshot = directory.resolve("snapshot.db")
            try {
                val original = capture(source, snapshot)
                check(original["-journal"]?.size.let { it == null || it == 0L }) {
                    "A nonempty legacy rollback journal requires separate offline recovery"
                }
                val borrowed = object : RoomStoreOwnership {
                    override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T = block()
                }
                val provenance = object : LegacyRecoveryProvenance {
                    override suspend fun <T> withVerifiedSnapshot(candidate: Path, block: suspend () -> T): T {
                        check(candidate.toAbsolutePath().normalize() == snapshot && capture(source, null) == original) {
                            "Legacy source changed before review"
                        }
                        return block()
                    }
                }
                LegacyRoomRecoveryApply.run(corePath, snapshot, target, borrowed, provenance, openRoom, now) { review ->
                    val selection = choose(review)
                    currentCoroutineContext().ensureActive()
                    check(capture(source, null) == original) { "Legacy source changed during review" }
                    selection
                }
            } finally {
                // These are freshly created private scratch files; the original files are retained.
                for (suffix in listOf("-journal", "-wal", "-shm", "")) Files.deleteIfExists(directory.resolve("snapshot.db$suffix"))
                Files.delete(directory)
            }
        }

    private data class Evidence(val identity: Any, val size: Long, val modified: java.nio.file.attribute.FileTime, val digest: String)

    /** Copy all durable SQLite bytes, or re-read them to verify exact source identity and content. */
    private suspend fun capture(source: Path, destination: Path?): Map<String, Evidence?> {
        var total = 0L
        return buildMap {
            for (suffix in durableSuffixes) {
                currentCoroutineContext().ensureActive()
                val path = source.resolveSibling(source.fileName.toString() + suffix)
                if (Files.notExists(path, NOFOLLOW_LINKS)) {
                    check(suffix.isNotEmpty()) { "Legacy source is missing" }
                    put(suffix, null)
                    continue
                }
                val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                check(before.isRegularFile && !before.isSymbolicLink && coreSourceLinkCount(path) == 1L) { "Legacy source identity is unsafe" }
                val key = requireNotNull(before.fileKey()) { "Legacy source identity is unavailable" }
                check(before.size() >= 0 && before.size() <= MAX_BYTES - total) { "Legacy source exceeds recovery limit" }
                total += before.size()
                val digest = MessageDigest.getInstance("SHA-256")
                val target = destination?.resolveSibling(destination.fileName.toString() + suffix)
                val output = target?.let { FileChannel.open(it, setOf<OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS), fileMode) }
                try {
                    FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { input ->
                        val bytes = ByteBuffer.allocate(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(bytes)
                            if (count < 0) break
                            check(count > 0) { "Legacy source read made no progress" }
                            bytes.flip()
                            digest.update(bytes.asReadOnlyBuffer())
                            while (output != null && bytes.hasRemaining()) check(output.write(bytes) > 0) { "Legacy snapshot write made no progress" }
                            bytes.clear()
                        }
                        output?.force(true)
                    }
                } finally { output?.close() }
                val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                check(after.isRegularFile && !after.isSymbolicLink && after.fileKey() == key && coreSourceLinkCount(path) == 1L &&
                    after.size() == before.size() && after.lastModifiedTime() == before.lastModifiedTime()) { "Legacy source changed during snapshot" }
                put(suffix, Evidence(key, before.size(), before.lastModifiedTime(), digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }))
            }
        }
    }

    private fun requireDisjoint(vararg databases: Path) {
        val groups = databases.map { db -> allSuffixes.map { db.resolveSibling(db.fileName.toString() + it) } }
        for (i in groups.indices) for (j in i + 1 until groups.size) for (a in groups[i]) for (b in groups[j]) {
            check(a != b) { "Recovery database paths overlap" }
            val aExists = Files.exists(a, NOFOLLOW_LINKS)
            val bExists = Files.exists(b, NOFOLLOW_LINKS)
            check(aExists || Files.notExists(a, NOFOLLOW_LINKS)) { "Cannot inspect recovery path" }
            check(bExists || Files.notExists(b, NOFOLLOW_LINKS)) { "Cannot inspect recovery path" }
            if (aExists && bExists) check(!Files.isSameFile(a, b)) { "Recovery database paths alias" }
        }
    }
}
