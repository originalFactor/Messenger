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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The platform owner must prevent all old and new database, network, task, file and preference
 * users from accessing the store throughout the block. It must not resume them after failure;
 * recovery must hold the same exclusive ownership before it retries the reset.
 */
internal interface RoomClearAllOwnership {
    suspend fun <T> withStoppedExclusiveOwner(block: suspend () -> T): T
}

/**
 * Platform implementation for the exact files and preferences owned by this store. [checkReady]
 * must fail unless the platform can prove no old Core database, asset copy or backup remains and
 * can perform durable, idempotent deletion without touching the live Room database or its marker.
 * [clearAndVerify] must clear preferences, owned assets and app caches, preserve publication files,
 * flush directory updates, and verify that no owned data remains. It must be safe to repeat after
 * interruption. No production implementation is provided by this prototype.
 */
internal interface RoomClearAllExternal {
    suspend fun checkReady()
    suspend fun clearAndVerify()
}

/** Exact identity retained after a migrated Core source has been verifiably retired. */
internal data class RoomCoreSourceIdentity(
    val sourceFingerprint: String,
    val publicationId: String,
    val contentHash: String?,
)

/**
 * Repeatable proof that the exact Core source, durable sidecars, backups, receipts and owned
 * source assets cannot be used to recreate deleted records. It must also protect credentials.
 * No platform implementation is supplied by this prototype; a test stub is not such a proof.
 */
internal fun interface RoomCoreSourceRetirement {
    suspend fun verifyRetired(identity: RoomCoreSourceIdentity)
}

/**
 * Isolated clear-all coordinator. Fresh stores need the external cleanup proof. A migrated or
 * previously retired store additionally needs an exact, repeatable Core source retirement proof;
 * its durable receipt prevents clear-all from silently turning a migrated store into a fresh one.
 * A pending marker blocks normal startup until offline recovery.
 * The caller must destroy and recreate all in-memory services after a successful clear.
 */
internal class RoomClearAll(
    private val db: LocalDatabase,
    private val ownership: RoomClearAllOwnership,
    private val external: RoomClearAllExternal,
    private val now: () -> Long = ::currentTimeMillis,
    private val sourceRetirement: RoomCoreSourceRetirement? = null,
) {
    private data class Retirement(val identity: RoomCoreSourceIdentity, val receipt: String)

    suspend fun clear() = ownership.withStoppedExclusiveOwner {
        external.checkReady()
        val retirement = inspectRetirement()
        requireRetirementProof(retirement)
        withContext(NonCancellable) {
            val id = randomUuid()
            resetRows(id, retirement)
            finish(id, retirement)
        }
    }

    /** Reopen only under independent exclusive ownership; this never repeats the row deletion. */
    suspend fun resume() = ownership.withStoppedExclusiveOwner {
        external.checkReady()
        withContext(NonCancellable) {
            val id = requireNotNull(db.rows().getValue(PENDING_KEY)?.value) { "No clear-all recovery is pending" }
            check(id.matches(UUID_PATTERN)) { "Invalid clear-all recovery marker" }
            val retirement = readRetirementReceipt()
            requireRetirementProof(retirement)
            finish(id, retirement)
        }
    }

    private suspend fun inspectRetirement(): Retirement? = db.useWriterConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
            val rows = db.rows()
            check(rows.getValue(PENDING_KEY) == null) { "Clear-all recovery is required" }
            val fresh = rows.getValue(FRESH_KEY)?.value == "complete"
            val migrationMarker = rows.getValue(CoreStoreMigration.MARKER_KEY)?.value
            val existingReceipt = rows.getValue(RETIRED_SOURCE_KEY)?.value
            if (migrationMarker == CoreStoreMigration.MARKER_VALUE) {
                check(existingReceipt == null) { "Migrated store already has a retirement receipt" }
                val sourceFingerprint = checkNotNull(rows.getValue(CoreStoreMigration.SOURCE_FINGERPRINT_KEY)?.value) {
                    "Migrated Core source identity is missing"
                }
                val publicationId = checkNotNull(rows.getValue(ROOM_PUBLICATION_ID_KEY)?.value) {
                    "Migrated publication identity is missing"
                }
                val contentHash = rows.getValue(CoreStoreMigration.SOURCE_CONTENT_FINGERPRINT_KEY)?.value
                val identity = validatedIdentity(sourceFingerprint, publicationId, contentHash)
                Retirement(identity, receipt(identity))
            } else {
                check(migrationMarker == null && rows.getValue(CoreStoreMigration.SOURCE_FINGERPRINT_KEY) == null &&
                    rows.getValue(CoreStoreMigration.SOURCE_CONTENT_FINGERPRINT_KEY) == null) {
                    "Unrecognized migration state requires recovery"
                }
                if (existingReceipt != null) Retirement(parseReceipt(existingReceipt), existingReceipt)
                else {
                    check(fresh) { "Migrated or unrecognized stores require a verified Core retirement before clear-all" }
                    null
                }
            }
        }
    }

    private suspend fun readRetirementReceipt(): Retirement? = db.useWriterConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
            db.rows().getValue(RETIRED_SOURCE_KEY)?.value?.let { raw -> Retirement(parseReceipt(raw), raw) }
        }
    }

    private suspend fun requireRetirementProof(retirement: Retirement?) {
        if (retirement == null) return
        checkNotNull(sourceRetirement) { "Migrated Core source retirement proof is unavailable" }
            .verifyRetired(retirement.identity)
    }

    private fun validatedIdentity(sourceFingerprint: String, publicationId: String, contentHash: String?): RoomCoreSourceIdentity {
        require(sourceFingerprint.matches(SOURCE_IDENTITY_PATTERN) && publicationId.matches(PUBLICATION_PATTERN) &&
            (contentHash == null || contentHash.matches(SOURCE_IDENTITY_PATTERN))) { "Invalid migrated Core source identity" }
        return RoomCoreSourceIdentity(sourceFingerprint, publicationId, contentHash)
    }

    private fun receipt(identity: RoomCoreSourceIdentity) =
        "${identity.sourceFingerprint}:${identity.publicationId}:${identity.contentHash.orEmpty()}"

    private fun parseReceipt(raw: String): RoomCoreSourceIdentity {
        val parts = raw.split(':')
        require(parts.size == 3) { "Invalid Core retirement receipt" }
        val identity = validatedIdentity(parts[0], parts[1], parts[2].takeIf(String::isNotEmpty))
        check(receipt(identity) == raw) { "Invalid Core retirement receipt" }
        return identity
    }

    private suspend fun resetRows(id: String, retirement: Retirement?) = db.useWriterConnection { connection ->
        // The marker and cleared rows must survive interruption together before file removal.
        connection.usePrepared("PRAGMA synchronous=FULL") { it.step() }
        connection.usePrepared("PRAGMA synchronous") { check(it.step() && it.getLong(0) == 2L) { "SQLite FULL sync unavailable" } }
        connection.usePrepared("PRAGMA secure_delete=ON") { check(it.step() && it.getLong(0) == 1L) { "SQLite secure delete unavailable" } }
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val rows = db.rows()
            check(rows.getValue(PENDING_KEY) == null) { "Clear-all recovery is required" }
            val fresh = rows.getValue(FRESH_KEY)?.value == "complete"
            val migrationMarker = rows.getValue(CoreStoreMigration.MARKER_KEY)?.value
            val existingReceipt = rows.getValue(RETIRED_SOURCE_KEY)?.value
            check(if (retirement == null) fresh && migrationMarker == null && existingReceipt == null
            else (migrationMarker == CoreStoreMigration.MARKER_VALUE || fresh) &&
                (existingReceipt == null || existingReceipt == retirement.receipt)) {
                "Store identity changed before clear-all"
            }
            val schemaVersion = connection.usePrepared("PRAGMA user_version") { check(it.step()); it.getLong(0) }
            check(schemaVersion == 1L) { "Unsupported Room schema for clear-all" }
            val tables = connection.usePrepared("SELECT name FROM sqlite_master WHERE type='table'") { statement ->
                buildSet { while (statement.step()) add(statement.getText(0)) }
            }
            check(tables.filterNot { it.startsWith("sqlite_") || it in INTERNAL_TABLES }.toSet() == RESET_TABLES.toSet()) {
                "Unknown Room tables require a clear-all migration"
            }
            val publication = rows.getValue(ROOM_PUBLICATION_ID_KEY)
            for (table in RESET_TABLES) connection.usePrepared("DELETE FROM $table") { it.step() }
            val time = now()
            val seed = Agent(id = randomUuid(), name = Agent.DEFAULT_AGENT_NAME, systemPrompt = Agent.DEFAULT_AGENT_PROMPT,
                description = Agent.DEFAULT_AGENT_DESCRIPTION, defaultModelId = null, temperature = 0.7f, topP = 1f,
                maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = time, updatedAt = time)
            rows.upsertAgents(listOf(seed.row(), Agent.builtinTitleAgent(time).row()))
            rows.upsertValues(listOfNotNull(KeyValueRow(FRESH_KEY, "complete"), publication, KeyValueRow(PENDING_KEY, id),
                retirement?.let { KeyValueRow(RETIRED_SOURCE_KEY, it.receipt) }))
            check(rows.defaultAgents().size == 1) { "Exactly one default Agent is required" }
        }
    }

    private suspend fun finish(id: String, retirement: Retirement?) {
        requireRetirementProof(retirement)
        db.useWriterConnection { connection ->
            check(db.rows().getValue(FRESH_KEY)?.value == "complete" &&
                db.rows().getValue(CoreStoreMigration.MARKER_KEY) == null &&
                db.rows().getValue(CoreStoreMigration.SOURCE_FINGERPRINT_KEY) == null &&
                db.rows().getValue(CoreStoreMigration.SOURCE_CONTENT_FINGERPRINT_KEY) == null &&
                db.rows().getValue(RETIRED_SOURCE_KEY)?.value == retirement?.receipt &&
                db.rows().getValue(PENDING_KEY)?.value == id && db.rows().defaultAgents().size == 1) {
                "Clear-all recovery state is invalid"
            }
            // Old WAL frames can contain deleted values. A busy checkpoint keeps the marker and
            // ownership for recovery; this is not a secure-erasure guarantee for the filesystem.
            connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") {
                check(it.step() && it.getLong(0) == 0L) { "SQLite checkpoint is busy" }
            }
        }
        external.clearAndVerify()
        db.useWriterConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val rows = db.rows()
                check(rows.getValue(PENDING_KEY)?.value == id) { "Clear-all recovery marker changed" }
                check(rows.getValue(RETIRED_SOURCE_KEY)?.value == retirement?.receipt) { "Core retirement receipt changed" }
                check(rows.defaultAgents().size == 1) { "Exactly one default Agent is required" }
                rows.deleteValue(PENDING_KEY)
            }
        }
    }

    companion object {
        internal const val PENDING_KEY = "__messenger_room_clear_all_pending_v1"
        internal const val RETIRED_SOURCE_KEY = "__messenger_room_retired_core_source_v1"
        private const val FRESH_KEY = "__messenger_room_fresh_v1"
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val SOURCE_IDENTITY_PATTERN = Regex("[0-9a-f]{64}")
        private val PUBLICATION_PATTERN = Regex("[0-9a-f]{32}")
        private val INTERNAL_TABLES = setOf("room_master_table", "android_metadata")
        // Children first, so this remains valid with foreign-key enforcement enabled.
        private val RESET_TABLES = listOf("messages", "learning_review_attempts", "learning_review_states",
            "learning_revisions", "learning_records", "conversations", "models", "projects", "providers",
            "agents", "sync_meta", "kv")
    }
}
