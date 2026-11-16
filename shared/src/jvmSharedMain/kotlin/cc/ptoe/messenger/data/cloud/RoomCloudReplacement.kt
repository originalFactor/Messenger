/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.RoomCloudSyncStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

/** Explicit, non-retrying full replacement; never installed as a production flow implicitly. */
internal class RoomCloudReplacement(
    private val db: LocalDatabase,
    private val api: RoomCloudApi,
    private val transport: KtorSyncTransport,
    private val session: SyncSession,
    private val images: CloudImageRehydrator,
    private val expire: suspend (SyncSession) -> Unit,
) {
    private val dao get() = db.cloudSync()

    /** Fail closed unless the lock's SQLite commit and directory entry can be synced. */
    private suspend fun beginDurableReplacement(): String = db.useWriterConnection { connection ->
        val file = connection.usePrepared("PRAGMA database_list") { statement ->
            check(statement.step() && statement.getText(1) == "main") { "Main database unavailable" }
            val path = statement.getText(2)
            check(path.isNotEmpty()) { "Cloud replacement requires a persistent database" }
            Paths.get(path).toAbsolutePath().normalize()
        }
        val parent = requireNotNull(file.parent)
        check(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) &&
            Files.getFileStore(parent).supportsFileAttributeView("posix")) { "Cloud replacement durability is unavailable" }
        val permissions = Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS)
        val others = setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
        check(permissions.none { it in others }) { "Cloud replacement storage must be private" }
        fun syncDirectory() = FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
        syncDirectory()
        connection.usePrepared("PRAGMA synchronous=FULL") { it.step() }
        connection.usePrepared("PRAGMA synchronous") { check(it.step() && it.getLong(0) == 2L) { "SQLite FULL sync unavailable" } }
        val lock = connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { dao.beginReplacement(session) }
        // If this fails the persisted marker is intentionally retained for recovery.
        syncDirectory()
        lock
    }
    private suspend fun cloudVersion(): Long {
        check(dao.currentSession() == session) { "Cloud session changed" }
        return api.refreshUser().also { check(it.id == session.accountId) { "Cloud account changed" } }.syncVersion
    }

    suspend fun plan(): CloudReplacementPlan {
        val before = cloudVersion()
        val local = dao.localSnapshot(session)
        val documents = linkedMapOf<SyncCollection, List<JsonObject>>()
        var safeVersion: Long? = null
        for (collection in SyncCollection.entries) {
            val rows = mutableListOf<JsonObject>()
            val seen = mutableSetOf<String>()
            var cursor: String? = null
            do {
                val page = try { transport.page(collection, 0, null, cursor) } catch (expired: CloudSessionExpired) {
                    expire(session); throw expired
                }
                rows += page.documents
                safeVersion = minOf(safeVersion ?: page.latestVersion, page.latestVersion)
                cursor = page.nextCursor
                check(cursor == null || seen.add(cursor)) { "Cloud sync cursor repeated" }
            } while (cursor != null)
            documents[collection] = rows
        }
        val after = cloudVersion()
        check(dao.localSnapshot(session) == local) { "Local data changed while preparing replacement" }
        return planCloudReplacement(before, after, PulledCloudDelta(documents, checkNotNull(safeVersion)), local)
    }

    suspend fun execute(plan: CloudReplacementPlan, onProgress: (CloudReplacementProgress) -> Unit = {}): CloudSyncResult {
        // Persisted before any remote mutation; turn admission uses the same Room writer gate.
        val lock = beginDurableReplacement()
        var mutationStarted = false
        var completed = false
        var failure: Throwable? = null
        try {
        var preparedRevision = plan.localRevision
        var currentVersion = plan.serverVersion
        val operations = object : CloudReplacementOperations {
            override suspend fun cloudVersion() = this@RoomCloudReplacement.cloudVersion()
            override suspend fun localRevision() = dao.localSnapshot(session, lock).revision
            override suspend fun delete(change: PendingSyncChange): Long {
                mutationStarted = true
                return try { transport.push(change) } catch (cancelled: CancellationException) { throw cancelled }
                catch (expired: CloudSessionExpired) { expire(session); throw expired }
                catch (error: Exception) { throw IllegalStateException("Deleting ${change.collection.path}/${change.id} failed; the remote outcome may be unknown", error) }
            }
            override suspend fun prepareLocal(plan: CloudReplacementPlan): Long {
                mutationStarted = true
                preparedRevision = dao.prepareReplacement(session, plan, lock)
                return preparedRevision
            }
            override suspend fun upload(): Long {
                currentVersion = cloudVersion()
                for (change in dao.pendingSnapshots(session, lock)) {
                    check(change.mutation == SyncMutation.UPSERT) { "Unexpected deletion remains in the replacement queue" }
                    check(localRevision() == preparedRevision) { "Local data changed during replacement upload" }
                    check(cloudVersion() == currentVersion) { "Cloud changed during replacement upload" }
                    val version = try { transport.push(change) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (expired: CloudSessionExpired) { expire(session); throw expired }
                    catch (error: Exception) { throw IllegalStateException("Uploading ${change.collection.path}/${change.id} failed; the remote outcome may be unknown", error) }
                    check(version == currentVersion + 1) { "Cloud changed during replacement upload; ${change.collection.path}/${change.id} may have been saved" }
                    dao.acknowledge(session, change, lock)
                    currentVersion = version
                }
                check(localRevision() == preparedRevision) { "Local data changed during replacement upload" }
                check(cloudVersion() == currentVersion) { "Cloud changed during replacement upload" }
                return currentVersion
            }
        }
        executeCloudReplacement(plan, operations, onProgress)
        // Pull every change since the prior durable cursor; never acknowledge the new watermark
        // before the full replacement has actually been read and committed locally.
        val delta = try { KotlinCloudSyncEngine(RoomCloudSyncStore(dao, expire, images, lock), transport).sync() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { throw CloudReplacementFailure(plan.deletions, true, error) }
        val result = CloudSyncResult(delta.latestVersion, delta.documents[SyncCollection.AGENT].orEmpty().size,
            delta.documents[SyncCollection.CONVERSATION].orEmpty().size, delta.documents[SyncCollection.PROVIDER].orEmpty().size)
        completed = true
        return result
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // Failed/unknown remote writes retain the lock for explicit recovery. If no mutation
            // began, or every stage completed, releasing is safe even when the caller is cancelled.
            if (completed || !mutationStarted) try {
                withContext(NonCancellable) { dao.finishReplacement(session, lock) }
            } catch (release: Throwable) {
                if (failure != null) failure.addSuppressed(release) else throw release
            }
        }
    }
}
