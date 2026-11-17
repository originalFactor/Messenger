/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import android.app.Application
import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android app-private cooperative Room ownership. Atomic directories, not process-associated
 * SQLite/file record locks, exclude other participating owners. They do not stop or exclude an
 * older Core, uncooperative same-UID code, or external workers: the host must independently fence
 * them and keep private path components stable before supplying either stop proof. No production
 * host currently supplies those proofs or installs this owner.
 *
 * An ACTIVE directory is synced before Room may open. Failure leaves it in place. Recovery first
 * creates a separately synced guard, proves every previous owner and worker cannot resume, and
 * only then removes ACTIVE and the guard. A failed/interrupted recovery leaves the guard for
 * separate offline repair. This owner accepts only the application's main process and filesDir.
 */
internal class AndroidRoomDirectoryOwner private constructor(
    private val paths: Paths,
    private val ownerIdentity: Identity,
    private val priorStopped: RoomWritersStoppedProof,
    private val allStopped: RoomWritersStoppedProof,
) : RoomStoreOwnership {
    private val mutex = Mutex()
    private var used = false
    private var closed = false
    private var guardIdentity: Identity? = null

    override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T = mutex.withLock {
        check(!used && !closed) { "Room ownership requires recovery" }
        used = true
        paths.validateOwner(ownerIdentity)
        check(!exists(paths.guard)) { "Room recovery is active" }
        priorStopped.verify()
        paths.validateOwner(ownerIdentity)
        check(!exists(paths.guard)) { "Room recovery is active" }
        val result = block()
        withContext(NonCancellable) {
            allStopped.verify()
            finish()
        }
        result
    }

    /** Caller must separately prove all old owners and workers, including unstarted ones, stopped. */
    suspend fun recoverAndRelease(proof: RoomWritersStoppedProof) = mutex.withLock {
        check(!closed) { "Room owner is closed" }
        withContext(NonCancellable) {
            if (guardIdentity == null) guardIdentity = paths.createGuard()
            proof.verify()
            finish()
        }
    }

    private fun finish() {
        paths.validateOwner(ownerIdentity)
        if (guardIdentity == null) guardIdentity = paths.createGuard()
        paths.validateGuard(checkNotNull(guardIdentity))
        paths.validateOwner(ownerIdentity)
        Files.delete(paths.owner)
        paths.syncParent()
        paths.validateGuard(checkNotNull(guardIdentity))
        Files.delete(paths.guard)
        paths.syncParent()
        closed = true
        active.remove(paths.key, this)
    }

    internal class Recovery private constructor(
        private val paths: Paths,
        private val ownerIdentity: Identity,
        private val guardIdentity: Identity,
    ) {
        private val mutex = Mutex()
        private var closed = false

        suspend fun recoverAndRelease(proof: RoomWritersStoppedProof) = mutex.withLock {
            check(!closed) { "Room recovery is closed" }
            withContext(NonCancellable) {
                paths.validateGuard(guardIdentity)
                paths.validateOwner(ownerIdentity)
                proof.verify()
                paths.validateGuard(guardIdentity)
                paths.validateOwner(ownerIdentity)
                Files.delete(paths.owner)
                paths.syncParent()
                paths.validateGuard(guardIdentity)
                Files.delete(paths.guard)
                paths.syncParent()
                closed = true
                recovering.remove(paths.key, this@Recovery)
            }
        }

        companion object {
            internal fun create(paths: Paths, owner: Identity, guard: Identity) = Recovery(paths, owner, guard)
        }
    }

    internal data class Identity(val device: Long, val inode: Long)

    internal class Paths(
        val key: String,
        private val dataRoot: Path,
        private val dataRootIdentity: Identity,
        private val root: Path,
        private val rootIdentity: Identity,
        private val ancestors: List<Pair<Path, Identity>>,
        val owner: Path,
        val guard: Path,
    ) {
        private val parent get() = checkNotNull(owner.parent)

        fun validateParent() {
            validateDirectory(dataRoot, dataRootIdentity)
            check(appFilesDirectory(root) == rootIdentity) { "App files directory identity changed" }
            ancestors.forEach { (path, identity) -> validateDirectory(path, identity) }
            // Android's platform-created filesDir can be 0771. The database parent itself
            // must still meet the strict private-directory and fsync requirements.
            if (parent == root) validateDirectory(root, rootIdentity)
        }
        fun validateOwner(identity: Identity) { validateParent(); validateDirectory(owner, identity) }
        fun validateGuard(identity: Identity) { validateParent(); validateDirectory(guard, identity) }
        fun syncParent() { validateParent(); AndroidRoomFiles.syncPrivateDirectory(parent); validateParent() }
        fun createGuard(): Identity {
            validateParent()
            Os.mkdir(guard.toString(), 448) // 0700; exclusive and atomic
            syncParent()
            return privateDirectory(guard)
        }
    }

    companion object {
        // Retain failed owners: a caller dropping its reference must not bypass recovery.
        private val active = ConcurrentHashMap<String, AndroidRoomDirectoryOwner>()
        private val recovering = ConcurrentHashMap<String, Recovery>()
        private val opening = ConcurrentHashMap.newKeySet<String>()

        suspend fun acquire(context: Context, destination: Path, priorStopped: RoomWritersStoppedProof,
            allStopped: RoomWritersStoppedProof): AndroidRoomDirectoryOwner {
            val paths = paths(context, destination)
            check(opening.add(paths.key)) { "Room owner is opening" }
            try {
                check(!active.containsKey(paths.key) && !recovering.containsKey(paths.key)) { "Room ownership is held" }
                paths.validateParent()
                check(!exists(paths.guard)) { "Room recovery is active" }
                Os.mkdir(paths.owner.toString(), 448)
                paths.syncParent()
                val ownerIdentity = privateDirectory(paths.owner)
                paths.validateOwner(ownerIdentity)
                check(!exists(paths.guard)) { "Room recovery is active" }
                val owner = AndroidRoomDirectoryOwner(paths, ownerIdentity, priorStopped, allStopped)
                check(active.putIfAbsent(paths.key, owner) == null)
                return owner
            } finally { opening.remove(paths.key) }
        }

        /** Only verified offline recovery may remove an ACTIVE marker from a prior process. */
        suspend fun acquireRecovery(context: Context, destination: Path): Recovery {
            val paths = paths(context, destination)
            check(opening.add(paths.key)) { "Room owner is opening" }
            try {
                check(!active.containsKey(paths.key) && !recovering.containsKey(paths.key)) { "Room ownership is held" }
                val guard = paths.createGuard()
                val owner = privateDirectory(paths.owner)
                paths.validateOwner(owner)
                paths.validateGuard(guard)
                val recovery = Recovery.create(paths, owner, guard)
                check(recovering.putIfAbsent(paths.key, recovery) == null)
                return recovery
            } finally { opening.remove(paths.key) }
        }

        private fun paths(context: Context, destination: Path): Paths {
            require(context.applicationInfo.uid == Os.getuid() && Application.getProcessName() == context.packageName) {
                "Room ownership is limited to the app's main process"
            }
            val dataRoot = context.dataDir.toPath().toAbsolutePath().normalize()
            val root = context.filesDir.toPath().toAbsolutePath().normalize()
            require(root.parent == dataRoot) { "App files directory is outside its private data directory" }
            val target = destination.toAbsolutePath().normalize()
            val parent = requireNotNull(target.parent)
            require(parent.startsWith(root) && target.fileName.toString() !in setOf("", ".", "..")) {
                "Room destination must be in the app-private files directory"
            }
            // Android creates filesDir with mode 0771 on some releases. Its app-private 0700
            // data-directory ancestor prevents traversal by other UIDs; preserve both identities.
            val dataRootIdentity = privateDirectory(dataRoot)
            val rootIdentity = appFilesDirectory(root)
            val ancestors = mutableListOf<Pair<Path, Identity>>()
            var current = root
            for (part in root.relativize(parent)) {
                current = current.resolve(part)
                ancestors.add(current to privateDirectory(current))
            }
            if (exists(target)) privateFile(target)
            val name = target.fileName.toString()
            if (parent == root) privateDirectory(parent)
            return Paths(target.toString(), dataRoot, dataRootIdentity, root, rootIdentity, ancestors,
                parent.resolve(".$name.room-owner.active"), parent.resolve(".$name.room-owner.recovery"))
        }

        private fun identity(stat: StructStat) = Identity(stat.st_dev, stat.st_ino)
        private fun appFilesDirectory(path: Path): Identity {
            val stat = Os.lstat(path.toString())
            require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Os.getuid() && stat.st_gid == Os.getgid() &&
                (stat.st_mode and 6) == 0) { "App files directory identity is unsafe" }
            return identity(stat)
        }
        private fun privateDirectory(path: Path): Identity {
            val stat = Os.lstat(path.toString())
            require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Os.getuid() && (stat.st_mode and 63) == 0) {
                "Room directory must be private and owned by this app"
            }
            return identity(stat)
        }
        private fun validateDirectory(path: Path, expected: Identity) {
            check(privateDirectory(path) == expected) { "Room directory identity changed" }
        }
        private fun privateFile(path: Path) {
            val stat = Os.lstat(path.toString())
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Os.getuid() && stat.st_nlink == 1L &&
                (stat.st_mode and 63) == 0) { "Room destination identity is unsafe" }
        }
        private fun exists(path: Path): Boolean = try { Os.lstat(path.toString()); true }
            catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) false else throw error }
    }
}
