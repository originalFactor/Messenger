/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
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

/**
 * Isolated local-filesystem prototype. A private owner directory is the durable ACTIVE marker.
 * All owners must cooperate. The platform must fence non-cooperating Core writers throughout
 * every lease and recovery. Recovery proof must fence every previous owner, including one
 * that has not started writing, and all of its workers so none can resume. The normal shutdown
 * proof must stop and join all writers and prevent new ones. Not installed in production.
 */
internal class RoomDirectoryOwnership private constructor(
    private val paths: Paths,
    private val ownerKey: Any,
    private val priorStopped: RoomWritersStoppedProof,
    private val allStopped: RoomWritersStoppedProof,
) : RoomStoreOwnership {
    private val mutex = Mutex()
    private var used = false
    private var closed = false
    private var guardKey: Any? = null

    override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T = mutex.withLock {
        check(!used && !closed) { "Room ownership requires recovery" }
        used = true
        paths.validateOwner(ownerKey)
        check(!Files.exists(paths.guard, NOFOLLOW_LINKS)) { "Room recovery is active" }
        priorStopped.verify()
        paths.validateOwner(ownerKey)
        check(!Files.exists(paths.guard, NOFOLLOW_LINKS)) { "Room recovery is active" }
        val result = block()
        withContext(NonCancellable) {
            allStopped.verify()
            finish()
        }
        result
    }

    /** Proves all writers are stopped and reconciles incomplete database work under the guard. */
    suspend fun recoverAndRelease(proof: RoomWritersStoppedProof) = mutex.withLock {
        check(!closed)
        withContext(NonCancellable) {
            if (guardKey == null) guardKey = paths.createGuard()
            proof.verify()
            finish()
        }
    }

    private fun finish() {
        paths.validateOwner(ownerKey)
        if (guardKey == null) guardKey = paths.createGuard()
        paths.validateGuard(requireNotNull(guardKey))
        paths.validateOwner(ownerKey)
        Files.delete(paths.owner)
        paths.syncParent()
        paths.validateGuard(requireNotNull(guardKey))
        Files.delete(paths.guard)
        paths.syncParent()
        closed = true
        active.remove(paths.identity, this)
    }

    /** A recovery directory blocks new owners while an operator verifies and reconciles state. */
    internal class Recovery private constructor(private val paths: Paths, private val ownerKey: Any, private val guardKey: Any) {
        private val mutex = Mutex()
        private var closed = false
        suspend fun recoverAndRelease(proof: RoomWritersStoppedProof) = mutex.withLock {
            check(!closed)
            withContext(NonCancellable) {
                paths.validateGuard(guardKey)
                paths.validateOwner(ownerKey)
                proof.verify()
                paths.validateGuard(guardKey)
                paths.validateOwner(ownerKey)
                Files.delete(paths.owner)
                paths.syncParent()
                paths.validateGuard(guardKey)
                Files.delete(paths.guard)
                paths.syncParent()
                closed = true
                recovering.remove(paths.identity, this@Recovery)
            }
        }
        companion object {
            internal fun create(paths: Paths, ownerKey: Any, guardKey: Any) = Recovery(paths, ownerKey, guardKey)
        }
    }

    internal class Paths(val parent: Path, val identity: String, val parentKey: Any, val owner: Path, val guard: Path) {
        fun syncParent() = FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
        fun validateParent() {
            CoreStoreCutover.requirePrivateParent(parent)
            check(attrs(parent).fileKey() == parentKey) { "Room parent identity changed" }
        }
        fun validateOwner(key: Any) { validatePrivateDirectory(owner, key) }
        fun validateGuard(key: Any) { validatePrivateDirectory(guard, key) }
        private fun validatePrivateDirectory(path: Path, key: Any) {
            validateParent()
            val attr = attrs(path)
            check(attr.isDirectory && attr.fileKey() == key) { "Room ownership directory changed" }
            require(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).none { it in denied }) { "Room ownership directory is not private" }
            check(Files.getOwner(path, NOFOLLOW_LINKS) == Files.getOwner(parent, NOFOLLOW_LINKS)) { "Room ownership differs" }
        }
        fun createGuard(): Any {
            validateParent()
            Files.createDirectory(guard, privateMode)
            syncParent()
            return requireNotNull(attrs(guard).fileKey()) { "Room recovery identity unavailable" }
        }
    }

    companion object {
        private val active = ConcurrentHashMap<String, RoomDirectoryOwnership>()
        private val recovering = ConcurrentHashMap<String, Recovery>()
        private val opening = ConcurrentHashMap.newKeySet<String>()
        private val privateMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private val denied = setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
        private fun attrs(path: Path): BasicFileAttributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        private fun paths(destination: Path): Paths {
            val requested = destination.toAbsolutePath().normalize()
            val parent = requireNotNull(requested.parent)
            CoreStoreCutover.requirePrivateParent(parent)
            val real = parent.toRealPath()
            require(Files.getFileStore(real).supportsFileAttributeView("unix")) { "Room directory identity unavailable" }
            val target = real.resolve(requested.fileName.toString())
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(target, NOFOLLOW_LINKS) &&
                    (Files.getAttribute(target, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L) { "Room target identity is unsafe" }
            }
            return Paths(real, target.toString(), requireNotNull(attrs(real).fileKey()),
                real.resolve(".${target.fileName}.room-owner.active"), real.resolve(".${target.fileName}.room-owner.recovery"))
        }

        suspend fun acquire(destination: Path, priorStopped: RoomWritersStoppedProof, allStopped: RoomWritersStoppedProof): RoomDirectoryOwnership {
            val paths = paths(destination)
            check(opening.add(paths.identity)) { "Room owner is opening" }
            try {
                check(!active.containsKey(paths.identity) && !recovering.containsKey(paths.identity)) { "Room ownership is held" }
                check(!Files.exists(paths.guard, NOFOLLOW_LINKS)) { "Room recovery is active" }
                Files.createDirectory(paths.owner, privateMode)
                paths.syncParent()
                val key = requireNotNull(attrs(paths.owner).fileKey()) { "Room owner identity unavailable" }
                paths.validateOwner(key)
                check(!Files.exists(paths.guard, NOFOLLOW_LINKS)) { "Room recovery is active" }
                val owner = RoomDirectoryOwnership(paths, key, priorStopped, allStopped)
                check(active.putIfAbsent(paths.identity, owner) == null)
                return owner
            } finally { opening.remove(paths.identity) }
        }

        /** A failed/crashed recovery leaves its guard; offline repair then needs separate fencing. */
        suspend fun acquireRecovery(destination: Path): Recovery {
            val paths = paths(destination)
            check(opening.add(paths.identity)) { "Room owner is opening" }
            try {
                check(!active.containsKey(paths.identity) && !recovering.containsKey(paths.identity)) { "Room ownership is held" }
                val guardKey = paths.createGuard()
                val owner = attrs(paths.owner)
                val ownerKey = requireNotNull(owner.fileKey()) { "Room owner identity unavailable" }
                paths.validateOwner(ownerKey)
                paths.validateGuard(guardKey)
                val recovery = Recovery.create(paths, ownerKey, guardKey)
                check(recovering.putIfAbsent(paths.identity, recovery) == null)
                return recovery
            } finally { opening.remove(paths.identity) }
        }
    }
}
