/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import android.content.Context
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Independent platform guarantees required for the entire ownership interval. The old Core proof
 * also covers source SQLite/shm descriptors, source and destination asset users and stable paths;
 * no source/shm descriptor may be opened or closed elsewhere in this process while leased.
 * Runtime and ACP proofs must prevent old work, including detached descendants, from resuming.
 * These callbacks must verify actual fences, not infer termination from a package update, Binder
 * disconnect, process callback or the existence of the cooperative Room marker.
 *
 * [roomWorkersStopped] is a separate proof for every Room user started by this host. All proofs
 * must remain valid until ownership is released and be safe to repeat during guarded recovery.
 */
internal class AndroidRoomCutoverProofs(
    val oldCoreAndSourceIsolated: RoomWritersStoppedProof,
    val runtimeStopped: RoomWritersStoppedProof,
    val acpStopped: RoomWritersStoppedProof,
    val roomWorkersStopped: RoomWritersStoppedProof,
) {
    suspend fun verifyPreviousWriters() {
        oldCoreAndSourceIsolated.verify()
        runtimeStopped.verify()
        acpStopped.verify()
    }

    suspend fun verifyAllWriters() {
        verifyPreviousWriters()
        roomWorkersStopped.verify()
    }
}

/**
 * Isolated Android host composition, not installed by the application. The caller owns the
 * platform fences in [AndroidRoomCutoverProofs], must retain them through release, and must stop
 * admitting work before [stopAndJoinRoomUsers] returns. A package upgrade or process death is not
 * such a fence. Failed shutdown keeps the store and owner live until [retryStop] succeeds; a
 * failed startup leaves the durable marker for explicit proof-backed [recoverFailedStart].
 */
internal class AndroidRoomCutoverHost private constructor(
    private val source: Path,
    private val destination: Path,
    private val owner: AndroidRoomDirectoryOwner,
    private val proofs: AndroidRoomCutoverProofs,
    private val shutdown: RoomManagedShutdown,
    private val openRoom: (Path) -> LocalDatabase,
    private val legacyDatabases: List<Path>,
) {
    private val mutex = Mutex()
    private var started = false
    private var running = false

    val shutdownState: StateFlow<RoomShutdownState> get() = shutdown.state

    /** Use only when there is no Core database; the startup also checks this before publication. */
    suspend fun <T> withFreshStore(useRoom: suspend (LocalDatabase) -> T): T = runOnce {
        RoomStoreStartup.withRoomStore(source, destination, owner, shutdown, openRoom,
            verifyFreshSource = ::verifyLegacyAbsent, useRoom = useRoom)
    }

    /** Migrates database and assets under the held source lease and the independent platform fences. */
    suspend fun <T> withCoreStore(sourceAssets: Path, destinationAssets: Path,
        useRoom: suspend (LocalDatabase) -> T): T = runOnce {
        RoomStoreStartup.withLeasedCoreStore(source, destination, sourceAssets, destinationAssets,
            owner, shutdown, openRoom, verifyLegacy = ::verifyLegacyAbsent, useRoom = useRoom)
    }

    private fun verifyLegacyAbsent() {
        check(legacyDatabases.all { Files.notExists(it, LinkOption.NOFOLLOW_LINKS) }) {
            "Legacy Room database or sidecar exists; recovery or migration is required"
        }
    }

    /** Retry from a separate recovery scope while a failed shutdown keeps the original call live. */
    suspend fun retryStop() = shutdown.retryStop()

    /** Abandon an unused host or recover a failed startup after all writers are independently stopped. */
    suspend fun recoverFailedStart() = withContext(NonCancellable) {
        mutex.withLock {
            check(!running) { "Room startup or shutdown is still running" }
            started = true // Recovery and subsequent startup must never race or overlap.
            owner.recoverAndRelease(RoomWritersStoppedProof { proofs.verifyAllWriters() })
        }
    }

    private suspend fun <T> runOnce(block: suspend () -> T): T {
        mutex.withLock {
            check(!started) { "Room cutover host is single-use" }
            started = true
            running = true
        }
        try { return block() }
        finally { withContext(NonCancellable) { mutex.withLock { running = false } } }
    }

    companion object {
        /** All arguments are mandatory; this factory cannot itself establish the platform fences. */
        suspend fun create(context: Context, source: Path, destination: Path,
            proofs: AndroidRoomCutoverProofs, stopAndJoinRoomUsers: suspend () -> Unit,
            openRoom: (Path) -> LocalDatabase): AndroidRoomCutoverHost {
            val legacyDatabases = listOf("messenger_database", "messenger_database.db").flatMap { name ->
                val database = context.getDatabasePath(name).toPath().toAbsolutePath().normalize()
                listOf("", "-wal", "-shm", "-journal").map { suffix ->
                    database.resolveSibling(database.fileName.toString() + suffix)
                }
            }
            val owner = AndroidRoomDirectoryOwner.acquire(context, destination,
                RoomWritersStoppedProof { proofs.verifyPreviousWriters() },
                RoomWritersStoppedProof { proofs.verifyAllWriters() })
            return AndroidRoomCutoverHost(source, destination, owner, proofs,
                RoomManagedShutdown(stopAndJoinRoomUsers), openRoom, legacyDatabases)
        }
    }
}
