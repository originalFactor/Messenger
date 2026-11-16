/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.asset.AssetReferenceAudit
import cc.ptoe.messenger.data.local.asset.CoreAssetGate
import cc.ptoe.messenger.data.local.asset.CoreAssetMetadata
import cc.ptoe.messenger.data.local.asset.CoreAssetReader
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Explicit, offline restore preflight. The owner must prevent every old and new database, asset,
 * preferences and worker user from accessing either copy for the entire operation. The supplied
 * gate must be the same one used by every Room asset/domain writer. The source SQLite lease must
 * stay held, and no other descriptor for that source or its shared-memory inode may be opened or
 * closed in this process. No production caller or platform ownership proof is supplied here.
 *
 * A successful result means the current database's referenced asset bytes are present and have
 * valid hashes, and that artifact execution/network grants were revoked. It does not prove backup
 * chronology, completeness of external files, worker exclusion, or safe source-identity rebind.
 * The durable pending marker deliberately remains: ordinary startup must not admit this store
 * until a future fenced, crash-safe identity rebind and publication protocol is implemented.
 */
internal object RoomRestorePreflight {
    internal const val PENDING_KEY = "__messenger_room_restore_pending_v1"
    private val json = Json { ignoreUnknownKeys = false }

    data class Result(val verifiedAssets: Int, val resetArtifacts: Int, val resetExecutionGrants: Int)

    suspend fun inspect(
        db: LocalDatabase,
        destination: Path,
        destinationAssets: Path,
        lease: CoreSourceSqliteLease,
        ownership: RoomClearAllOwnership,
        assets: CoreAssetGate,
    ): Result = ownership.withStoppedExclusiveOwner {
        val target = destination.toAbsolutePath().normalize()
        val root = destinationAssets.toAbsolutePath().normalize()
        lease.requireDistinctDatabasePaths(target)
        val evidence = CoreRestoreAssessment.assess(lease, target) as? CoreRestoreEvidence.ContentMatch
            ?: error("Core restore source contents cannot be proven")
        CoreStorePairedCutover.verifyRestorePair(lease, target, root, evidence)
        val marker = "v1:${evidence.publicationId}:${evidence.previousIdentity}:${evidence.contentHash}"

        // Commit this first: interruption or failed verification must not admit the restored store.
        withContext(NonCancellable) {
            db.useWriterConnection { connection ->
                checkDatabasePath(connection, target)
                connection.usePrepared("PRAGMA synchronous=FULL") { it.step() }
                connection.usePrepared("PRAGMA synchronous") { check(it.step() && it.getLong(0) == 2L) { "SQLite FULL sync unavailable" } }
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    val rows = db.rows()
                    check(rows.getValue(RoomClearAll.PENDING_KEY) == null) { "Clear-all recovery is required" }
                    check(rows.getValue(CoreStoreMigration.MARKER_KEY)?.value == CoreStoreMigration.MARKER_VALUE &&
                        rows.getValue(CoreStoreMigration.SOURCE_FINGERPRINT_KEY)?.value == evidence.previousIdentity &&
                        rows.getValue(CoreStoreMigration.SOURCE_CONTENT_FINGERPRINT_KEY)?.value == evidence.contentHash &&
                        rows.getValue(ROOM_PUBLICATION_ID_KEY)?.value == evidence.publicationId) {
                        "Restore evidence no longer matches the Room database"
                    }
                    val previous = rows.getValue(PENDING_KEY)?.value
                    check(previous == null || previous == marker) { "Another restore requires recovery" }
                    rows.upsertValues(listOf(KeyValueRow(PENDING_KEY, marker)))
                }
            }
        }

        assets.withExclusiveAssets {
            db.useWriterConnection { connection ->
                checkDatabasePath(connection, target)
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    check(db.rows().getValue(PENDING_KEY)?.value == marker) { "Restore marker changed" }
                    // Read all domain references and explicit owners in one SQLite transaction.
                    val dao = db.assetMetadata()
                    val protected = AssetReferenceAudit(dao.referenceSnapshot()).protectedAssetIds()
                    val entries = dao.allMetadataEntries()
                    check(entries.size <= 10_000) { "Asset catalog exceeds restore limit" }
                    val metadata = entries.associate { row ->
                        val item = json.decodeFromString<CoreAssetMetadata>(row.value)
                        require(CoreAssetReader.validAssetId(item.id) && row.key == "local_asset:${item.id}" &&
                            item.sha256 == item.id && item.size in 0..CoreAssetReader.MAX_BYTES &&
                            CoreAssetReader.validMime(item.mimeType) && item.references.size <= 10_000 &&
                            item.references.all(CoreAssetReader::validOwner)) { "Invalid restored asset metadata" }
                        item.id to (item to row.value)
                    }
                    check(metadata.size == entries.size) { "Duplicate restored asset metadata" }
                    val referenced = protected + metadata.values.filter { it.first.references.isNotEmpty() }.map { it.first.id }
                    check(referenced.all(metadata::containsKey)) { "Restored asset metadata is missing" }
                    val reader = CoreAssetReader(root)
                    for (id in referenced.sorted()) {
                        lease.requireDistinct(root.resolve(id))
                        reader.read(id, metadata.getValue(id).second)
                    }
                    val reset = CoreRestoreAuthorizationReset.reset(db, evidence)
                    Result(referenced.size, reset.artifacts, reset.executionGrants)
                }
            }
        }
    }

    private suspend fun checkDatabasePath(connection: androidx.room.Transactor, expected: Path) {
        connection.usePrepared("PRAGMA database_list") { statement ->
            check(statement.step() && statement.getText(1) == "main") { "Room database unavailable" }
            val actual = statement.getText(2)
            check(actual.isNotEmpty() && Files.isSameFile(Paths.get(actual), expected)) { "Room factory opened the wrong database" }
        }
    }
}
