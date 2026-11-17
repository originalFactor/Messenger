/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Must prove prior owners and all their workers cannot resume, not merely that their parent exited. */
internal fun interface AcpPriorOwnerBarrier { suspend fun requireStopped() }

/** Cooperative cross-process exclusion; all ACP worker owners of this database must use this gate. */
internal class AcpExclusiveOwner private constructor(private val identity: String, private val channel: FileChannel, private val lock: FileLock) {
    private val mutex = Mutex()
    private var closed = false
    private var closing = false

    /** All admission and lifecycle operations must pass through this gate. */
    suspend fun <T> withOwner(action: suspend () -> T): T = mutex.withLock {
        check(!closed && !closing && lock.isValid && channel.isOpen) { "ACP owner is closed or stopping" }
        action()
    }

    /** Shutdown must prohibit admission, cancel and join workers, and prove their side effects stopped. */
    suspend fun closeAndAwait(shutdown: suspend () -> Unit) = mutex.withLock {
        if (closed) return@withLock
        closing = true
        withContext(NonCancellable) {
            shutdown() // Failure retains the lock and leaves the owner unavailable for transfer.
            lock.release()
            channel.close()
            closed = true
            inProcess.remove(identity)
        }
    }

    companion object {
        // Opening and closing another descriptor for the same inode can release POSIX process locks.
        private val inProcess = ConcurrentHashMap.newKeySet<String>()
        /** A filesystem lock alone does not prove a crashed owner's workers have stopped. */
        suspend fun acquire(database: Path, previousOwner: AcpPriorOwnerBarrier): AcpExclusiveOwner {
            val input = database.toAbsolutePath().normalize()
            require(!Files.isSymbolicLink(input)) { "ACP database path is a symlink" }
            val db = input.toRealPath()
            val parent = requireNotNull(db.parent) { "ACP database has no private directory" }
            val parentAttrs = attrs(parent)
            val dbAttrs = attrs(db)
            require(parentAttrs.isDirectory && dbAttrs.isRegularFile && links(db) == 1L) { "ACP database identity is unsafe" }
            val parentKey = requireNotNull(parentAttrs.fileKey()) { "ACP directory identity is unavailable" }
            val dbKey = requireNotNull(dbAttrs.fileKey()) { "ACP database identity is unavailable" }
            privatePermissions(parent)
            require(Files.getOwner(parent, NOFOLLOW_LINKS) == Files.getOwner(db, NOFOLLOW_LINKS)) { "ACP database ownership differs" }
            val marker = parent.resolve(".${db.fileName}.acp-owner.lock")
            val identity = db.toString()
            check(inProcess.add(identity)) { "Another ACP owner is active" }
            val options = setOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW_LINKS)
            var channel: FileChannel? = null
            var lock: FileLock? = null
            try {
                val opened = FileChannel.open(marker, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                channel = opened
                val markerAttrs = attrs(marker)
                val markerKey = requireNotNull(markerAttrs.fileKey()) { "ACP lock identity is unavailable" }
                require(markerAttrs.isRegularFile && links(marker) == 1L) { "ACP lock identity is unsafe" }
                privatePermissions(marker)
                require(Files.getOwner(marker, NOFOLLOW_LINKS) == Files.getOwner(parent, NOFOLLOW_LINKS)) { "ACP lock ownership differs" }
                lock = try { opened.tryLock() } catch (_: OverlappingFileLockException) { null }
                check(lock != null) { "Another ACP owner is active" }
                check(attrs(parent).fileKey() == parentKey && attrs(db).fileKey() == dbKey && attrs(marker).fileKey() == markerKey &&
                    links(db) == 1L && links(marker) == 1L) { "ACP owner identities changed" }
                previousOwner.requireStopped()
                return AcpExclusiveOwner(identity, opened, lock)
            } catch (failure: Throwable) {
                try { lock?.release() } catch (release: Throwable) { failure.addSuppressed(release) }
                try { channel?.close() } catch (close: Throwable) { failure.addSuppressed(close) }
                inProcess.remove(identity)
                throw failure
            }
        }

        private fun attrs(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        private fun links(path: Path): Long = (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as? Number)?.toLong()
            ?: error("ACP file link count cannot be verified")
        private fun privatePermissions(path: Path) {
            val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
            require(permissions.none { it in setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE) }) { "ACP owner directory or lock is not private" }
        }
    }
}
