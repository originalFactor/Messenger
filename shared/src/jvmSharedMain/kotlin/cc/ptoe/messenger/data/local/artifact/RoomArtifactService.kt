/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.artifact

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.asset.CoreAssetMetadata
import cc.ptoe.messenger.data.local.asset.CoreAssetOwner
import cc.ptoe.messenger.data.local.asset.CoreAssetReader
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.asset.retainOwner
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.util.UUID
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Serializable internal data class ArtifactRevision(
    val revision: Long, val files: Map<String, String>, val assets: Map<String, String> = emptyMap(),
    val entry: String, val createdAt: Long, val stateVersion: Long = 0,
)
@Serializable internal data class ArtifactData(
    val id: String, val title: String, val description: String = "", val revisions: List<ArtifactRevision>,
    val requestedDomains: List<String> = emptyList(), val approvedDomains: List<String> = emptyList(),
    val state: JsonElement = JsonNull, val stateVersion: Long = 0, val trustedOnDevice: Boolean = false,
    val createdAt: Long, val updatedAt: Long,
)
internal data class ArtifactDraft(
    val id: String? = null, val title: String, val description: String = "", val files: Map<String, String>,
    val assets: Map<String, String> = emptyMap(), val entry: String = "index.html", val expectedRevision: Long = 0,
    val stateVersion: Long = 0, val requestedDomains: List<String> = emptyList(),
)
/** Process-local approval identity for one immutable, non-current revision. */
internal data class HistoricalArtifactGrant(val id: String, val revision: Long, val fingerprint: String)

/** Metadata and resource ownership only. Never executes or grants direct network/native access. */
internal class RoomArtifactService(private val db: LocalDatabase, private val assets: CoreAssetService) {
    private val json = Json { encodeDefaults = true }
    private val listeners = synchronized(registries) { registries.getOrPut(db) { ConcurrentHashMap() } }

    private companion object {
        val registries = WeakHashMap<LocalDatabase, ConcurrentHashMap<String, CopyOnWriteArraySet<() -> Unit>>>()
        const val EXECUTION_PREFIX = "artifact_execution_authorization:"
    }

    private fun invalidate(id: String) { listeners[id]?.forEach { it() } }

    private fun executionFingerprint(revision: ArtifactRevision): String {
        val bytes = json.encodeToString(revision).encodeToByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Resolve the precise package to be reviewed; historical authorization is never persisted. */
    internal suspend fun historicalGrant(id: String, revision: Long, expected: ArtifactRevision? = null): HistoricalArtifactGrant = assets.withExclusive {
        val artifact = load(id)
        require(revision > 0 && revision < artifact.revisions.last().revision) { "Artifact revision is not historical" }
        val selected = artifact.revisions.singleOrNull { it.revision == revision } ?: error("Artifact revision not found")
        check(expected == null || selected == expected) { "Artifact revision content changed" }
        HistoricalArtifactGrant(id, revision, executionFingerprint(selected))
    }

    /** Revalidate the exact historical package while holding the shared mutation gate. */
    internal suspend fun <T> withHistoricalGrant(grant: HistoricalArtifactGrant, block: suspend (ArtifactData) -> T): T =
        assets.withExclusive {
            val artifact = load(grant.id)
            val selected = artifact.revisions.singleOrNull { it.revision == grant.revision }
            check(grant.revision < artifact.revisions.last().revision && selected != null &&
                executionFingerprint(selected) == grant.fingerprint) { "Historical artifact revision changed" }
            block(artifact)
        }

    internal suspend fun isHistorical(id: String, revision: Long): Boolean = assets.withExclusive {
        val artifact = load(id)
        require(artifact.revisions.any { it.revision == revision }) { "Artifact revision not found" }
        revision < artifact.revisions.last().revision
    }

    /** Caller must be a trusted device UI, after reviewing precisely this revision. */
    suspend fun authorizeExecution(id: String, revision: Long, expected: ArtifactRevision? = null) = assets.withExclusive {
        db.useWriterConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val artifact = load(id)
                check(artifact.revisions.last().revision == revision) { "Artifact revision changed" }
                check(expected == null || artifact.revisions.last() == expected) { "Artifact revision content changed" }
                db.rows().upsertValues(listOf(
                    KeyValueRow("artifact:$id", json.encodeToString(artifact.copy(trustedOnDevice = true))),
                    KeyValueRow(EXECUTION_PREFIX + id, executionFingerprint(artifact.revisions.last())),
                ))
            }
        }
        invalidate(id)
    }

    internal suspend fun verifyRevision(id: String, revision: Long, expected: ArtifactRevision) = assets.withExclusive {
        check(expected.revision == revision && load(id).revisions.singleOrNull { it.revision == revision } == expected) {
            "Artifact revision content changed"
        }
    }

    internal suspend fun revisionSnapshot(id: String, revision: Long): ArtifactRevision = assets.withExclusive {
        load(id).revisions.singleOrNull { it.revision == revision } ?: error("Artifact revision not found")
    }

    /** Does not grant execution; a stale revision is an error rather than a false result. */
    suspend fun isExecutionAuthorized(id: String, revision: Long): Boolean = assets.withExclusive {
        val artifact = load(id)
        check(artifact.revisions.last().revision == revision) { "Artifact revision changed" }
        artifact.trustedOnDevice &&
            db.learningBundle().value(EXECUTION_PREFIX + id) == executionFingerprint(artifact.revisions.last())
    }

    /** The shared gate serializes authorization checks/dispatch with updates and revocation. */
    internal suspend fun <T> withExecutionAuthorization(id: String, revision: Long?, block: suspend (ArtifactData) -> T): T = assets.withExclusive {
        val artifact = load(id)
        val actual = artifact.revisions.last().revision
        check(artifact.trustedOnDevice && (revision == null || revision == actual) &&
            db.learningBundle().value(EXECUTION_PREFIX + id) == executionFingerprint(artifact.revisions.last())) { "Artifact revision requires device authorization" }
        block(artifact)
    }

    internal fun addListener(id: String, listener: () -> Unit) { listeners.computeIfAbsent(id) { CopyOnWriteArraySet() }.add(listener) }
    internal fun removeListener(id: String, listener: () -> Unit) { listeners[id]?.remove(listener) }

    suspend fun get(id: String): ArtifactData = assets.withExclusive { load(id) }

    /** Observe only this artifact; ordinary saved-state changes do not recreate its native view. */
    internal fun observeUiChanges(id: String): Flow<Unit> {
        UUID.fromString(id)
        val artifact = db.rows().observeValue("artifact:$id").distinctUntilChanged().map { row ->
            row?.value?.let { raw -> json.decodeFromString<ArtifactData>(raw) }?.let { artifact ->
                UiSnapshot(artifact.id, artifact.title, artifact.description, artifact.revisions,
                    artifact.requestedDomains, artifact.approvedDomains, artifact.stateVersion,
                    artifact.trustedOnDevice, artifact.updatedAt)
            }
        }.distinctUntilChanged()
        val authorization = db.rows().observeValue(EXECUTION_PREFIX + id).map { it?.value }.distinctUntilChanged()
        return combine(artifact, authorization) { snapshot, fingerprint -> snapshot to fingerprint }
            .distinctUntilChanged().map { Unit }
    }

    private data class UiSnapshot(
        val id: String, val title: String, val description: String, val revisions: List<ArtifactRevision>,
        val requestedDomains: List<String>, val approvedDomains: List<String>, val stateVersion: Long,
        val trustedOnDevice: Boolean, val updatedAt: Long,
    )

    suspend fun list(): List<ArtifactData> = assets.withExclusive {
        db.assetMetadata().artifacts().sortedBy { it.key }.map { row ->
            val id = row.key.removePrefix("artifact:")
            check(row.key == "artifact:$id") { "Invalid artifact key" }
            load(id)
        }
    }

    /** Tool reads claim authorization under the asset gate and the same Room transaction. */
    internal suspend fun getForTool(id: String, authorize: suspend () -> Unit): ArtifactData = assets.withExclusive {
        db.useWriterConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            authorize()
            load(id)
        } }
    }

    private suspend fun load(id: String): ArtifactData {
        UUID.fromString(id)
        val raw = db.learningBundle().value("artifact:$id") ?: error("Artifact not found")
        return json.decodeFromString<ArtifactData>(raw).also { artifact ->
            check(artifact.id == id && artifact.revisions.size in 1..32 && artifact.approvedDomains.all { it in artifact.requestedDomains } &&
                artifact.state.toString().encodeToByteArray().size <= 64 * 1024 && artifact.revisions.last().stateVersion == artifact.stateVersion) { "Invalid artifact metadata" }
            artifact.revisions.forEachIndexed { index, revision ->
                check(revision.revision == index.toLong() + 1) { "Invalid artifact revision" }
                validateDraft(ArtifactDraft(id, artifact.title, artifact.description, revision.files, revision.assets,
                    revision.entry, index.toLong(), revision.stateVersion, artifact.requestedDomains))
            }
        }
    }

    suspend fun submit(draft: ArtifactDraft, now: Long, authorize: suspend () -> Unit = {}): ArtifactData = assets.withExclusive {
        validateDraft(draft)
        val id = draft.id ?: UUID.randomUUID().toString()
        UUID.fromString(id)
        val verified = draft.assets.values.toSet().associateWith { assetId ->
            val raw = db.assetMetadata().metadata("local_asset:$assetId") ?: error("Artifact asset missing")
            val metadata = json.decodeFromString<CoreAssetMetadata>(raw)
            require(metadata.id == assetId && metadata.sha256 == assetId) { "Invalid asset metadata" }
            assets.verifyWithGateHeld(assetId, raw)
            raw
        }
        val saved = db.useWriterConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                authorize()
                val old = if (draft.id == null) {
                    check(db.learningBundle().value("artifact:$id") == null) { "Artifact ID collision" }
                    ArtifactData(id, draft.title, revisions = emptyList(), trustedOnDevice = true,
                        stateVersion = draft.stateVersion, createdAt = now, updatedAt = now)
                } else load(id)
                val current = old.revisions.lastOrNull()?.revision ?: 0
                check(current == draft.expectedRevision) { "Artifact revision changed" }
                require(old.revisions.size < 32) { "Artifact revision quota exceeded" }
                for ((assetId, raw) in verified) {
                    check(db.assetMetadata().metadata("local_asset:$assetId") == raw) { "Asset changed during artifact save" }
                    val metadata = json.decodeFromString<CoreAssetMetadata>(raw).retainOwner(CoreAssetOwner("artifact", id)).copy(unreferencedAt = null)
                    db.assetMetadata().writeMetadata(KeyValueRow("local_asset:$assetId", json.encodeToString(metadata)))
                }
                val changedVersion = old.stateVersion != draft.stateVersion
                val updated = old.copy(title = draft.title, description = draft.description,
                    revisions = old.revisions + ArtifactRevision(current + 1, draft.files, draft.assets, draft.entry, now, draft.stateVersion),
                    requestedDomains = draft.requestedDomains, approvedDomains = old.approvedDomains.filter { it in draft.requestedDomains },
                    state = if (changedVersion) JsonNull else old.state, stateVersion = draft.stateVersion, updatedAt = now)
                db.rows().upsertValues(listOf(KeyValueRow("artifact:$id", json.encodeToString(updated))))
                updated
            }
        }
        invalidate(id)
        saved
    }

    /** Call only from the explicit device-local authorization flow, never from generated code. */
    suspend fun authorizeRun(id: String): ArtifactData = mutate(id) { it.copy(trustedOnDevice = true) }

    /** Explicit user approval; requests cannot grant more than the artifact declared. */
    suspend fun grantDomains(id: String, domains: List<String>, expectedRevision: Long? = null): ArtifactData = mutate(id) { current ->
        check(expectedRevision == null || current.revisions.last().revision == expectedRevision) { "Artifact revision changed" }
        require(domains.size <= 16 && domains.distinct().size == domains.size && domains.all { validDomain(it) && it in current.requestedDomains }) { "Invalid network grant" }
        current.copy(approvedDomains = domains)
    }

    suspend fun saveState(id: String, revision: Long, stateVersion: Long, value: JsonElement): ArtifactData = mutate(id, invalidateExecution = false) { current ->
        check(current.trustedOnDevice && current.revisions.lastOrNull()?.revision == revision && current.stateVersion == stateVersion) { "Artifact not authorized or revision changed" }
        require(value.toString().encodeToByteArray().size <= 64 * 1024) { "Artifact state exceeds limit" }
        current.copy(state = value)
    }

    private suspend fun mutate(id: String, invalidateExecution: Boolean = true, update: (ArtifactData) -> ArtifactData): ArtifactData = assets.withExclusive {
        val saved = db.useWriterConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val updated = update(load(id))
                db.rows().upsertValues(listOf(KeyValueRow("artifact:$id", json.encodeToString(updated))))
                updated
            }
        }
        if (invalidateExecution) invalidate(id)
        saved
    }
}

private fun validPath(path: String) = path.isNotEmpty() && path.length < 256 &&
    path.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_./-" } &&
    path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
private fun validDomain(host: String): Boolean = host.isNotEmpty() && host.length <= 253 && host.contains('.') && host == host.lowercase() &&
    host.split('.').all { it.isNotEmpty() && !it.startsWith('-') && !it.endsWith('-') && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' } } &&
    host.any { it in 'a'..'z' } && listOf("localhost", "local", "internal").none { host.endsWith(".$it") }
private fun validateDraft(draft: ArtifactDraft) {
    require(draft.title.isNotBlank() && draft.title.encodeToByteArray().size <= 240 && draft.description.encodeToByteArray().size <= 4000 &&
        draft.expectedRevision in 0..0xffff_ffffL && draft.stateVersion in 0..0xffff_ffffL) { "Invalid artifact details" }
    require(draft.files.isNotEmpty() && draft.files.size <= 128 && draft.assets.size <= 64 && draft.files.keys.intersect(draft.assets.keys).isEmpty() &&
        (draft.files.keys + draft.assets.keys).all(::validPath) && draft.files.values.all { it.encodeToByteArray().size <= 1024 * 1024 } &&
        draft.files.values.sumOf { it.encodeToByteArray().size.toLong() } <= 4L * 1024 * 1024 && draft.assets.values.all(CoreAssetReader::validAssetId) &&
        draft.entry.endsWith(".html") && draft.entry in draft.files) { "Invalid artifact files" }
    require(draft.requestedDomains.size <= 16 && draft.requestedDomains.all(::validDomain)) { "Invalid requested domains" }
}
