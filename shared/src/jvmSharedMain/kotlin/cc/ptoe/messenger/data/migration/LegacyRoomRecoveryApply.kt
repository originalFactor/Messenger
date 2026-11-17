/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.room.Transactor
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.chat.ACTIVE_TURN_KEY_PREFIX
import cc.ptoe.messenger.data.chat.RuntimeWorkspaceFence
import cc.ptoe.messenger.data.cloud.SyncCollection
import cc.ptoe.messenger.data.cloud.SyncMutation
import cc.ptoe.messenger.data.local.room.CLOUD_REPLACEMENT_LOCK_KEY
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ProjectRow
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Proves the scratch snapshot's exact original and excludes its writers and path replacement. */
internal interface LegacyRecoveryProvenance {
    suspend fun <T> withVerifiedSnapshot(snapshot: Path, block: suspend () -> T): T
}

/** Each ID is a deliberate user choice; absent choices never produce writes. */
internal data class LegacyRecoverySelection(
    val projects: Set<String> = emptySet(),
    val associations: Set<String> = emptySet(),
    val providerFormats: Set<String> = emptySet(),
    /** Required to create a missing project; must equal the currently stored device identity. */
    val newProjectDeviceId: String? = null,
)
internal data class LegacyRecoveryApplied(val projects: Int, val associations: Int, val providerFormats: Int)
/** The Room values shown to the user contain only the same non-secret fields as the source preview. */
internal data class LegacyRecoveryReview(
    val source: LegacyRoomRecoveryPreview,
    val roomDeviceId: String?,
    val roomProjects: Map<String, LegacyRecoveryProject?>,
    val roomConversations: Map<String, LegacyRecoveryConversation?>,
    val roomProviders: Map<String, LegacyRecoveryProvider?>,
)

/**
 * Isolated, one-shot recovery. [ownership] must exclude every Core, legacy and Room writer,
 * including other processes and detached workers, and keep all paths stable until the callback
 * returns and Room closes. The caller must supply a WAL-aware private scratch copy, not an
 * original legacy file. This component does not establish those platform guarantees or expose
 * a production entry point. A returned selection is the user's explicit decision, not a guess
 * based on missing rows. No provider keys are included in the displayed preview.
 */
internal object LegacyRoomRecoveryApply {
    private const val MAX_SELECTIONS = 1_000
    private val formats = setOf("chat", "responses", "messages", "interactions")

    suspend fun run(
        corePath: Path,
        legacySnapshot: Path,
        destination: Path,
        ownership: RoomStoreOwnership,
        provenance: LegacyRecoveryProvenance,
        openRoom: (Path) -> LocalDatabase,
        now: () -> Long,
        choose: suspend (LegacyRecoveryReview) -> LegacyRecoverySelection?,
    ): LegacyRecoveryApplied? = ownership.withExclusiveOwnership { provenance.withVerifiedSnapshot(legacySnapshot) {
        CoreSourceSqliteLease.acquire(corePath).use { lease ->
            val target = destination.toAbsolutePath().normalize()
            lease.requireDistinctDatabasePaths(target)
            requireDisjointSnapshotAndTarget(legacySnapshot, target)
            val targetIdentity = identity(target)
            val sourceFingerprint = lease.migrationFingerprint()
            val legacyFingerprint = lease.legacySnapshotFingerprint(legacySnapshot)
            val preview = lease.previewLegacySnapshot(legacySnapshot)
            check(lease.legacySnapshotFingerprint(legacySnapshot) == legacyFingerprint) { "Legacy snapshot changed during preview" }
            val room = openRoom(target)
            try {
                verifyRoom(room, target, targetIdentity, sourceFingerprint)
                val baseline = baseline(room, preview)
                currentCoroutineContext().ensureActive()
                val selected = choose(baseline.review)?.let { LegacyRecoverySelection(
                    it.projects.toSet(), it.associations.toSet(), it.providerFormats.toSet(), it.newProjectDeviceId,
                ) } ?: return@withVerifiedSnapshot null
                currentCoroutineContext().ensureActive()
                val count = selected.projects.size.toLong() + selected.associations.size + selected.providerFormats.size
                require(count in 1..MAX_SELECTIONS.toLong()) { "Select at least one bounded recovery action" }
                check(lease.migrationFingerprint() == sourceFingerprint) { "Core source changed after preview" }
                check(lease.legacySnapshotFingerprint(legacySnapshot) == legacyFingerprint) { "Legacy snapshot changed after preview" }
                check(identity(target) == targetIdentity) { "Room destination changed after preview" }
                val timestamp = now().also { require(it >= 0) }
                RuntimeWorkspaceFence.forDatabase(room).mutate {
                    room.useWriterConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                        currentCoroutineContext().ensureActive()
                        apply(room, preview, selected, baseline, sourceFingerprint, timestamp)
                    } }
                }
            } finally { room.close() }
        }
    } }

    private data class Baseline(val projects: Map<String, String?>, val conversations: Map<String, String?>,
        val providers: Map<String, String?>, val review: LegacyRecoveryReview)

    private suspend fun baseline(db: LocalDatabase, preview: LegacyRoomRecoveryPreview): Baseline =
        db.useReaderConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
            // Room entities include fields absent from the UI, including provider credentials.
            // Bound them before materializing and retain only private hashes, never the rows.
            var total = 0L
            val fields = mapOf(
                "projects" to listOf("id", "name", "workspace", "deviceId"),
                "providers" to listOf("id", "name", "baseUrl", "apiKey", "apiFormat"),
                "conversations" to listOf("id", "title", "providerId", "agentId", "projectId", "overrideModelId",
                    "overrideReasoningEffort", "overrideToolsConfig", "lastMessage", "reasoningFormat", "contextSummary"),
            )
            for ((table, columns) in fields) {
                val sizes = columns.map { "coalesce(length(CAST(\"$it\" AS BLOB)),0)" }
                val sum = sizes.joinToString(" + ")
                val maximum = sizes.joinToString(",", "max(", ")")
                connection.usePrepared("SELECT count(*), coalesce(sum($sum),0), coalesce(max($maximum),0) FROM \"$table\"") { row ->
                    check(row.step() && row.getLong(0) <= 10_000 && row.getLong(2) <= 256 * 1024) { "Recovery destination exceeds limit" }
                    total += row.getLong(1)
                    check(total <= 16L * 1024 * 1024) { "Recovery destination exceeds size limit" }
                }
            }
            val rows = db.rows()
            val projects = preview.projects.filter { it.legacy != null }.associate { it.id to rows.project(it.id) }
            val conversations = preview.conversations.filter { it.legacy != null }.associate { it.id to rows.conversation(it.id) }
            val providers = preview.providers.filter { it.legacy != null }.associate { it.id to rows.provider(it.id) }
            Baseline(projects.mapValues { it.value?.let(::projectHash) }, conversations.mapValues { it.value?.let(::conversationHash) },
                providers.mapValues { it.value?.let(::providerHash) }, LegacyRecoveryReview(preview,
                    rows.getValue("device_id")?.value?.takeIf { it.isNotBlank() },
                    projects.mapValues { (_, row) -> row?.let { LegacyRecoveryProject(it.name, it.workspace, it.createdAt, it.updatedAt, it.deviceId, true) } },
                    conversations.mapValues { (_, row) -> row?.let { LegacyRecoveryConversation(it.title, it.projectId) } },
                    providers.mapValues { (_, row) -> row?.let { LegacyRecoveryProvider(it.name, it.apiFormat) } }))
        } }

    private fun requireDisjointSnapshotAndTarget(snapshot: Path, target: Path) {
        fun paths(path: Path): List<Path> {
            val base = path.toAbsolutePath().normalize().toString()
            return listOf("", "-wal", "-shm", "-journal").map { Paths.get(base + it) }
        }
        for (source in paths(snapshot)) for (destination in paths(target)) {
            check(source != destination && !(Files.exists(source, NOFOLLOW_LINKS) && Files.exists(destination, NOFOLLOW_LINKS) &&
                Files.isSameFile(source, destination))) { "Legacy snapshot aliases Room destination" }
        }
    }

    private fun digest(vararg fields: Any?): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (value in fields) {
            if (value == null) { hash.update(0.toByte()); continue }
            hash.update(1.toByte())
            val text = when (value) { is Double -> value.toBits().toString(); else -> value.toString() }
            val bytes = text.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun projectHash(row: ProjectRow) = digest(row.id, row.name, row.workspace, row.deviceId, row.createdAt, row.updatedAt)
    private fun providerHash(row: cc.ptoe.messenger.data.local.room.ProviderRow) =
        digest(row.id, row.name, row.baseUrl, row.apiKey, row.apiFormat, row.nativeWebSearch, row.createdAt, row.updatedAt)
    private fun conversationHash(row: cc.ptoe.messenger.data.local.room.ConversationRow) = digest(row.id, row.title, row.providerId, row.agentId,
        row.projectId, row.overrideModelId, row.overrideTemperature, row.overrideTopP, row.overrideMaxTokens, row.overrideReasoningEffort,
        row.overrideToolsEnabled, row.overrideToolsConfig, row.writable, row.createdAt, row.updatedAt, row.lastMessage, row.reasoningFormat,
        row.contextSummary, row.contextSummaryUntil, row.contextTokens, row.contextTokensAt)

    private fun identity(path: Path): Any {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(attrs.isRegularFile && !attrs.isSymbolicLink && coreSourceLinkCount(path) == 1L) { "Invalid Room destination" }
        return requireNotNull(attrs.fileKey()) { "Room destination identity unavailable" }
    }

    private suspend fun verifyRoom(db: LocalDatabase, path: Path, expectedIdentity: Any, fingerprint: String) {
        db.useReaderConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
            connection.usePrepared("PRAGMA database_list") { row ->
                check(row.step() && row.getText(1) == "main" && Files.isSameFile(Paths.get(row.getText(2)), path)) { "Room factory opened the wrong database" }
            }
            check(identity(path) == expectedIdentity) { "Room destination changed" }
            requirePublication(db, fingerprint)
        } }
    }

    private suspend fun requirePublication(db: LocalDatabase, fingerprint: String) {
        val rows = db.rows()
        check(rows.getValue(CoreStoreMigration.MARKER_KEY)?.value == CoreStoreMigration.MARKER_VALUE &&
            rows.getValue(CoreStoreMigration.SOURCE_FINGERPRINT_KEY)?.value == fingerprint &&
            rows.getValue(ROOM_PUBLICATION_ID_KEY)?.value?.matches(Regex("[0-9a-f]{32}")) == true) {
            "Room destination does not match the Core migration"
        }
        check(rows.getValue(CLOUD_REPLACEMENT_LOCK_KEY) == null && rows.getValue(RoomRestorePreflight.PENDING_KEY) == null &&
            rows.getValue(RoomClearAll.PENDING_KEY) == null) { "Room destination requires recovery" }
    }

    private suspend fun apply(db: LocalDatabase, preview: LegacyRoomRecoveryPreview, selection: LegacyRecoverySelection, baseline: Baseline,
        fingerprint: String, timestamp: Long): LegacyRecoveryApplied {
        requirePublication(db, fingerprint)
        val rows = db.rows()
        check(!db.cloudSync().hasActiveTurn(ACTIVE_TURN_KEY_PREFIX)) { "Active turns must stop before recovery" }
        val projects = preview.projects.associateBy { it.id }
        val conversations = preview.conversations.associateBy { it.id }
        val providers = preview.providers.associateBy { it.id }
        val localDevice = rows.getValue("device_id")?.value?.takeIf { it.isNotBlank() }

        // Validate every selection before writing anything. Compare exactly what was displayed;
        // preserve all destination fields that this recovery does not explicitly select.
        val projectWrites = selection.projects.sorted().map { id ->
            val pair = requireNotNull(projects[id]) { "Unknown project selection" }
            val old = requireNotNull(pair.legacy) { "Legacy project is unavailable" }
            val current = rows.project(id)
            check(id in baseline.projects && current?.let(::projectHash) == baseline.projects[id]) { "Project changed after preview" }
            check(current?.let { LegacyRecoveryProject(it.name, it.workspace, it.createdAt, it.updatedAt, it.deviceId, true) } == pair.core) { "Project changed after preview" }
            if (current == null) {
                val device = localDevice ?: error("Device identity is unavailable")
                check(selection.newProjectDeviceId == device) { "Confirm the current device for restored projects" }
                ProjectRow(id, old.name, old.workspace, device, old.createdAt, maxOf(old.updatedAt, timestamp))
            } else {
                check(current.deviceId == null || localDevice != null && current.deviceId == localDevice) { "Project is not owned by this device" }
                current.copy(name = old.name, workspace = old.workspace, updatedAt = maxOf(current.updatedAt, timestamp))
            }
        }
        val projectIds = projectWrites.map { it.id }.toSet()
        val conversationWrites = selection.associations.sorted().map { id ->
            val pair = requireNotNull(conversations[id]) { "Unknown conversation selection" }
            val old = requireNotNull(pair.legacy) { "Legacy conversation is unavailable" }
            val expected = requireNotNull(pair.core) { "Core conversation is unavailable" }
            val projectId = requireNotNull(old.projectId) { "Legacy conversation has no project association" }
            val project = requireNotNull(projects[projectId]?.takeIf { it.legacy != null }) { "Legacy project is unavailable" }
            if (projectId !in projectIds) {
                val currentProject = rows.project(projectId) ?: error("Restore the selected project first")
                check(currentProject.deviceId == null || localDevice != null && currentProject.deviceId == localDevice) { "Project is not owned by this device" }
                check(projectId in baseline.projects && projectHash(currentProject) == baseline.projects[projectId] &&
                    LegacyRecoveryProject(currentProject.name, currentProject.workspace, currentProject.createdAt,
                        currentProject.updatedAt, currentProject.deviceId, true) == project.core) { "Project changed after preview" }
            }
            val current = rows.conversation(id) ?: error("Conversation was deleted after preview")
            check(id in baseline.conversations && conversationHash(current) == baseline.conversations[id]) { "Conversation changed after preview" }
            check(LegacyRecoveryConversation(current.title, current.projectId) == expected) { "Conversation changed after preview" }
            current.copy(projectId = projectId, updatedAt = maxOf(current.updatedAt, timestamp))
        }
        val providerWrites = selection.providerFormats.sorted().map { id ->
            val pair = requireNotNull(providers[id]) { "Unknown provider selection" }
            val old = requireNotNull(pair.legacy) { "Legacy provider is unavailable" }
            val expected = requireNotNull(pair.core) { "Core provider is unavailable" }
            val format = requireNotNull(old.apiFormat) { "Legacy provider protocol is unknown" }
            require(format in formats) { "Unsupported legacy provider protocol" }
            val current = rows.provider(id) ?: error("Provider was deleted after preview")
            check(id in baseline.providers && providerHash(current) == baseline.providers[id]) { "Provider changed after preview" }
            check(LegacyRecoveryProvider(current.name, current.apiFormat) == expected) { "Provider changed after preview" }
            current.copy(apiFormat = format, updatedAt = maxOf(current.updatedAt, timestamp))
        }

        for (row in projectWrites) { rows.upsertProjects(listOf(row)); db.cloudSync().enqueueLocal(SyncCollection.PROJECT, row.id, SyncMutation.UPSERT) }
        for (row in conversationWrites) { rows.upsertConversations(listOf(row)); db.cloudSync().enqueueLocal(SyncCollection.CONVERSATION, row.id, SyncMutation.UPSERT) }
        for (row in providerWrites) { rows.upsertProviders(listOf(row)); db.cloudSync().enqueueLocal(SyncCollection.PROVIDER, row.id, SyncMutation.UPSERT) }
        return LegacyRecoveryApplied(projectWrites.size, conversationWrites.size, providerWrites.size)
    }
}
