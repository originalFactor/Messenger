/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.repository.room.row
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.domain.model.Agent
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

/**
 * Must exclude every Rust/Core writer and any competing Room owner across the entire block,
 * including shutdown. Implementations must provide real process/platform ownership, not a mutex
 * shared only by one caller. A failed or unprovable acquisition must not enter the block.
 */
internal interface RoomStoreOwnership {
    suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T
}

/**
 * Isolated startup composition. Core is stopped before choosing fresh vs migration and remains
 * excluded until [useRoom] returns and this function closes Room. Not installed in production.
 */
internal object RoomStoreStartup {
    private const val FRESH_KEY = "__messenger_room_fresh_v1"
    private const val FRESH_VALUE = "complete"

    /**
     * Isolated migration path for an existing Core store. The ownership provider must exclude
     * all competing Room owners and must stop/join their workers before returning; the caller
     * must additionally guarantee no code in this process opens another source or shm descriptor.
     * Old asset users must be isolated from [destinationAssets]. The mandatory [shutdown] must stop
     * and join all users, including on failure or cancellation, before Room and the source lease
     * close. Failed shutdown retains both resources and the owner until explicit recovery succeeds.
     * No platform owner or production caller is installed.
     */
    suspend fun <T> withLeasedCoreStore(source: Path, destination: Path, sourceAssets: Path, destinationAssets: Path,
        ownership: RoomStoreOwnership, shutdown: RoomManagedShutdown, openRoom: (Path) -> LocalDatabase,
        useRoom: suspend (LocalDatabase) -> T): T = ownership.withExclusiveOwnership {
            val oldStore = source.toAbsolutePath().normalize()
            val target = destination.toAbsolutePath().normalize()
            require(oldStore != target) { "Core and Room stores must be distinct" }
            CoreSourceSqliteLease.acquire(oldStore).use { lease ->
                CoreStorePairedCutover.migrate(lease, sourceAssets, target, destinationAssets,
                    { initializeEmptyRoom(it, openRoom) }, { verifyRoom(it, openRoom) })
                val room = openRoom(target)
                try {
                    checkDatabasePath(room, target)
                    check(room.rows().defaultAgents().size == 1) { "Exactly one default Agent is required" }
                    check(room.rows().getValue(RoomClearAll.PENDING_KEY) == null) { "Clear-all recovery is required" }
                    check(room.rows().getValue(RoomRestorePreflight.PENDING_KEY) == null) { "Restore recovery is required" }
                    shutdown.enter()
                    try { useRoom(room) } finally { shutdown.stopAndAwait() }
                } finally { room.close() }
            }
        }

    /** Fresh stores only. Existing Core stores require leased paired migration. */
    suspend fun <T> withRoomStore(source: Path, destination: Path, ownership: RoomStoreOwnership,
        shutdown: RoomManagedShutdown, openRoom: (Path) -> LocalDatabase,
        useRoom: suspend (LocalDatabase) -> T): T = ownership.withExclusiveOwnership {
        val target = destination.toAbsolutePath().normalize()
        val oldStore = source.toAbsolutePath().normalize()
        require(oldStore != target) { "Core and Room stores must be distinct" }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            check(!Files.isSymbolicLink(target)) { "Room target cannot be a symbolic link" }
            if (Files.exists(oldStore, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSameFile(oldStore, target)) { "Core and Room stores must not share an inode" }
            }
        }
        val parent = requireNotNull(target.parent)
        requireRoomPublicationParent(parent)
        syncRoomPublicationDirectory(parent)
        val freshWork = parent.resolve(".${target.fileName}.fresh-room-startup")
        check(!Files.exists(freshWork, LinkOption.NOFOLLOW_LINKS)) { "Interrupted fresh Room initialization requires recovery: $freshWork" }
        val cutoverWork = parent.resolve(".${target.fileName}.core-v9-cutover")
        check(!Files.exists(cutoverWork, LinkOption.NOFOLLOW_LINKS)) { "Interrupted Core migration requires recovery: $cutoverWork" }
        check(Files.notExists(oldStore, LinkOption.NOFOLLOW_LINKS)) {
            "Existing Core stores require leased database and asset migration"
        }
        initializeFresh(oldStore, target, freshWork, openRoom)
        val room = openRoom(target)
        try {
            // Room is initialized and validated before any repository receives the handle.
            checkDatabasePath(room, target)
            check(room.rows().defaultAgents().size == 1) { "Exactly one default Agent is required" }
            check(room.rows().getValue(RoomClearAll.PENDING_KEY) == null) { "Clear-all recovery is required" }
            check(room.rows().getValue(RoomRestorePreflight.PENDING_KEY) == null) { "Restore recovery is required" }
            shutdown.enter()
            try { useRoom(room) } finally { shutdown.stopAndAwait() }
        } finally { room.close() }
    }

    /** Platform factory must build Room for exactly the provided path and use bundled SQLite. */
    private suspend fun checkDatabasePath(db: LocalDatabase, expected: Path) {
        db.useWriterConnection { connection ->
            connection.usePrepared("PRAGMA database_list") { statement ->
                check(statement.step() && statement.getText(1) == "main") { "Room database unavailable" }
                val actual = statement.getText(2)
                check(actual.isNotEmpty() && Files.isSameFile(Paths.get(actual), expected)) { "Room factory opened the wrong database" }
            }
        }
    }

    private suspend fun initializeEmptyRoom(path: Path, openRoom: (Path) -> LocalDatabase) {
        val db = openRoom(path)
        try { checkDatabasePath(db, path); db.rows().upsertValues(emptyList()) } finally { db.close() }
    }

    private suspend fun verifyRoom(path: Path, openRoom: (Path) -> LocalDatabase) {
        val db = openRoom(path)
        try {
            checkDatabasePath(db, path)
            check(db.rows().defaultAgents().size == 1) { "Room database must have exactly one default Agent" }
            check(db.rows().getValue(RoomRestorePreflight.PENDING_KEY) == null) { "Restore recovery is required" }
        }
        finally { db.close() }
    }

    private suspend fun initializeFresh(source: Path, target: Path, work: Path, openRoom: (Path) -> LocalDatabase) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) { "Invalid existing Room target" }
            verifyPublishedRoomDatabase(target, ::syncRoomPublicationDirectory) { path ->
                val db = openRoom(path)
                try {
                    checkDatabasePath(db, path)
                    check(db.rows().getValue(FRESH_KEY)?.value == FRESH_VALUE) { "Existing Room target was not initialized as a fresh store" }
                    check(db.rows().defaultAgents().size == 1) { "Room database must have exactly one default Agent" }
                } finally { db.close() }
            }
            return
        }
        Files.createDirectory(work, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        var staged: Path? = null
        var failure: Throwable? = null
        try {
            val file = Files.createTempFile(work, "fresh-", ".db")
            staged = file
            val publicationId = if (roomPublicationUsesReceipt) java.util.UUID.randomUUID().toString().replace("-", "") else null
            val db = openRoom(file)
            try {
                checkDatabasePath(db, file)
                db.useWriterConnection { connection ->
                    connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                        val now = currentTimeMillis()
                        val seed = Agent(id = randomUuid(), name = Agent.DEFAULT_AGENT_NAME, systemPrompt = Agent.DEFAULT_AGENT_PROMPT,
                            description = Agent.DEFAULT_AGENT_DESCRIPTION, defaultModelId = null, temperature = 0.7f, topP = 1f,
                            maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = now, updatedAt = now)
                        db.rows().ensureDefaultAgent(seed.row())
                        val title = Agent.builtinTitleAgent(now)
                        db.cloudSync().saveAgent(title.row())
                        db.rows().upsertValues(listOfNotNull(KeyValueRow(FRESH_KEY, FRESH_VALUE),
                            publicationId?.let { KeyValueRow(ROOM_PUBLICATION_ID_KEY, it) }))
                    }
                }
            } finally { db.close() }
            verifyRoom(file, openRoom)
            CoreStoreCutover.finalizeDatabase(file)
            check(Files.notExists(source, LinkOption.NOFOLLOW_LINKS)) { "Core store appeared during fresh initialization" }
            publishRoomDatabase(file, target, publicationId, ::syncRoomPublicationDirectory) { verifyRoom(it, openRoom) }
            check(Files.notExists(source, LinkOption.NOFOLLOW_LINKS)) { "Core store appeared during fresh initialization" }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            var cleanup: Throwable? = null
            val files = mutableListOf<Path>()
            staged?.let { path ->
                files.add(path)
                listOf("-wal", "-shm", "-journal", ".lck").forEach { files.add(path.resolveSibling(path.fileName.toString() + it)) }
            }
            files.add(work)
            for (file in files) try { Files.deleteIfExists(file) } catch (error: Throwable) {
                if (cleanup == null) cleanup = error else cleanup.addSuppressed(error)
            }
            cleanup?.let { if (failure != null) failure.addSuppressed(it) else throw it }
        }
    }
}
