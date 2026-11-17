/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
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

/** Must prove all Core/Room writers and their workers cannot resume, including prior processes. */
internal fun interface RoomWritersStoppedProof { suspend fun verify() }

/**
 * Cooperative, fail-closed ownership for a canonical Room destination. This does not stop Rust:
 * the platform must exclude non-cooperating Core writers and provide real stop proofs. All Room
 * owners must use this lock; ACP owners must acquire their separate lock inside this lease and
 * stop their workers and release ACP ownership before this lease can be cleaned.
 *
 * A durable ACTIVE record precedes work. Exceptions retain the handle and ACTIVE; after process
 * death the journal refuses another lease until explicit verified recovery. POSIX record locks can
 * silently disappear if any same-process descriptor for the lock inode is closed; FileLock does
 * not detect that. This prototype is not suitable for production until a platform gate proves
 * that cannot happen or uses a lock primitive without that behavior.
 */
internal class DurableRoomOwnership private constructor(
    private val identity: String,
    private val parent: Path,
    private val marker: Path,
    private val journal: Path,
    private val parentKey: Any,
    private val markerKey: Any,
    private val journalKey: Any,
    private val lockChannel: FileChannel,
    private val channel: FileChannel,
    private val fileLock: FileLock,
    private val priorStopped: RoomWritersStoppedProof,
    private val allStopped: RoomWritersStoppedProof,
) : RoomStoreOwnership {
    private val mutex = Mutex()
    private var closed = false
    private var used = false

    override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T = mutex.withLock {
        check(!closed && !used) { "Room ownership is unavailable or requires recovery" }
        used = true
        validateIdentity()
        check(readState() == State.CLEAN) { "Interrupted Room ownership requires verified recovery" }
        priorStopped.verify()
        validateIdentity()
        append("ACTIVE\n")
        val result = block()
        withContext(NonCancellable) {
            allStopped.verify()
            validateIdentity()
            append("CLEAN\n")
            release()
        }
        result
    }

    /** Proof must also reconcile any interrupted migrations and establish database consistency. */
    suspend fun recoverAndRelease(recovery: RoomWritersStoppedProof) = mutex.withLock {
        check(!closed) { "Room ownership is closed" }
        withContext(NonCancellable) {
            recovery.verify()
            validateIdentity()
            // Reset only after explicit recovery proof; an interrupted write is rejected next time.
            channel.truncate(0)
            channel.position(0)
            writeFully(HEADER + "CLEAN\n")
            channel.force(true)
            release()
        }
    }

    private enum class State { CLEAN, ACTIVE }
    private fun readState(): State {
        val size = channel.size()
        check(size in HEADER.length.toLong()..MAX_JOURNAL) { "Invalid Room ownership journal" }
        val bytes = ByteBuffer.allocate(size.toInt())
        channel.position(0)
        while (bytes.hasRemaining()) check(channel.read(bytes) > 0) { "Incomplete Room ownership journal" }
        val contents = bytes.array().toString(Charsets.US_ASCII)
        check(contents.startsWith(HEADER) && contents.endsWith('\n')) { "Invalid Room ownership journal" }
        val lines = contents.removePrefix(HEADER).dropLast(1).split('\n')
        check(lines.firstOrNull() == "CLEAN") { "Invalid Room ownership journal" }
        var state = State.CLEAN
        for (line in lines.drop(1)) state = when {
            state == State.CLEAN && line == "ACTIVE" -> State.ACTIVE
            state == State.ACTIVE && line == "CLEAN" -> State.CLEAN
            else -> error("Invalid Room ownership journal")
        }
        return state
    }
    private fun append(value: String) {
        check(channel.size() + value.length <= MAX_JOURNAL) { "Room ownership journal requires verified recovery" }
        channel.position(channel.size())
        writeFully(value)
        channel.force(true)
    }
    private fun writeFully(value: String) {
        val bytes = ByteBuffer.wrap(value.toByteArray(Charsets.US_ASCII))
        while (bytes.hasRemaining()) check(channel.write(bytes) > 0) { "Cannot write Room ownership journal" }
    }
    private fun validateIdentity() {
        check(fileLock.isValid && lockChannel.isOpen && channel.isOpen && attributes(parent).fileKey() == parentKey &&
            attributes(marker).fileKey() == markerKey && links(marker) == 1L &&
            attributes(journal).fileKey() == journalKey && links(journal) == 1L) { "Room ownership identity changed" }
        CoreStoreCutover.requirePrivateParent(parent)
        privateFile(marker)
        privateFile(journal)
        check(Files.getOwner(parent, NOFOLLOW_LINKS) == Files.getOwner(marker, NOFOLLOW_LINKS)) { "Room ownership differs" }
        check(Files.getOwner(parent, NOFOLLOW_LINKS) == Files.getOwner(journal, NOFOLLOW_LINKS)) { "Room journal ownership differs" }
    }
    private fun release() {
        channel.close()
        fileLock.release()
        lockChannel.close()
        closed = true
        owners.remove(identity, this)
    }

    companion object {
        private const val HEADER = "messenger-room-ownership-v1\n"
        private const val MAX_JOURNAL = 65_536L
        // Keep failed owners reachable: garbage collection must not close and release their locks.
        private val owners = ConcurrentHashMap<String, DurableRoomOwnership>()
        private val opening = ConcurrentHashMap.newKeySet<String>()

        suspend fun acquire(destination: Path, priorStopped: RoomWritersStoppedProof,
            allStopped: RoomWritersStoppedProof): DurableRoomOwnership {
            val requested = destination.toAbsolutePath().normalize()
            val originalParent = requireNotNull(requested.parent)
            CoreStoreCutover.requirePrivateParent(originalParent)
            val parent = originalParent.toRealPath()
            require(Files.getFileStore(parent).supportsFileAttributeView("unix")) { "Room ownership identity is unsupported" }
            val target = parent.resolve(requested.fileName.toString())
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(target, NOFOLLOW_LINKS) && links(target) == 1L) { "Room destination identity is unsafe" }
            }
            val identity = target.toString()
            check(opening.add(identity)) { "Another Room owner is opening" }
            var channel: FileChannel? = null
            var journalChannel: FileChannel? = null
            var lock: FileLock? = null
            try {
                check(!owners.containsKey(identity)) { "Another Room owner is active or requires recovery" }
                val marker = parent.resolve(".${target.fileName}.room-owner.lock")
                val journal = parent.resolve(".${target.fileName}.room-owner.state")
                check(Files.exists(marker, NOFOLLOW_LINKS) || !Files.exists(journal, NOFOLLOW_LINKS)) {
                    "Room lock is missing while its journal exists; verified offline recovery is required"
                }
                val privateMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
                val options = setOf<OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE, NOFOLLOW_LINKS)
                val (opened, created) = try { FileChannel.open(marker, options, privateMode) to true }
                catch (_: FileAlreadyExistsException) {
                    FileChannel.open(marker, setOf<OpenOption>(StandardOpenOption.READ, StandardOpenOption.WRITE, NOFOLLOW_LINKS)) to false
                }
                channel = opened
                val parentKey = requireNotNull(attributes(parent).fileKey()) { "Room parent identity unavailable" }
                val markerAttrs = attributes(marker)
                val markerKey = requireNotNull(markerAttrs.fileKey()) { "Room lock identity unavailable" }
                require(markerAttrs.isRegularFile && links(marker) == 1L) { "Room lock identity is unsafe" }
                privateFile(marker)
                require(Files.getOwner(marker, NOFOLLOW_LINKS) == Files.getOwner(parent, NOFOLLOW_LINKS)) { "Room lock ownership differs" }
                lock = try { opened.tryLock() } catch (_: OverlappingFileLockException) { null }
                check(lock != null) { "Another Room owner is active" }
                val (state, stateCreated) = if (created) {
                    // A newly created lock must never adopt an orphaned state file.
                    FileChannel.open(journal, options, privateMode) to true
                } else {
                    // Missing state could have contained ACTIVE. Do not recreate it as CLEAN.
                    FileChannel.open(journal, setOf<OpenOption>(StandardOpenOption.READ, StandardOpenOption.WRITE, NOFOLLOW_LINKS)) to false
                }
                journalChannel = state
                val stateAttrs = attributes(journal)
                val journalKey = requireNotNull(stateAttrs.fileKey()) { "Room journal identity unavailable" }
                require(stateAttrs.isRegularFile && links(journal) == 1L) { "Room journal identity is unsafe" }
                val owner = DurableRoomOwnership(identity, parent, marker, journal, parentKey, markerKey, journalKey,
                    opened, state, lock, priorStopped, allStopped)
                owner.validateIdentity()
                if (stateCreated) {
                    owner.writeFully(HEADER + "CLEAN\n")
                    state.force(true)
                }
                if (created || stateCreated) {
                    FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
                }
                check(owners.putIfAbsent(identity, owner) == null)
                return owner
            } catch (failure: Throwable) {
                try { journalChannel?.close() } catch (error: Throwable) { failure.addSuppressed(error) }
                try { lock?.release() } catch (error: Throwable) { failure.addSuppressed(error) }
                try { channel?.close() } catch (error: Throwable) { failure.addSuppressed(error) }
                throw failure
            } finally { opening.remove(identity) }
        }

        private fun attributes(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        private fun links(path: Path) = (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as? Number)?.toLong()
            ?: error("Room link count cannot be verified")
        private fun privateFile(path: Path) {
            val denied = setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
            require(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).none { it in denied }) { "Room lock is not private" }
        }
    }
}
