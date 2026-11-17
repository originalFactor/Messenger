/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import cc.ptoe.messenger.data.cloud.canonicalCloudBase
import cc.ptoe.messenger.data.cloud.CloudImageRehydrator
import cc.ptoe.messenger.data.cloud.CloudLocalSnapshot
import cc.ptoe.messenger.data.cloud.CloudReplacementPlan
import cc.ptoe.messenger.data.cloud.StoredCloudSession
import cc.ptoe.messenger.data.cloud.CloudUser
import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.data.cloud.PulledCloudDelta
import cc.ptoe.messenger.data.cloud.PendingSyncChange
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.data.cloud.SyncCollection
import cc.ptoe.messenger.data.cloud.SyncMutation
import cc.ptoe.messenger.data.cloud.SyncSession
import cc.ptoe.messenger.data.chat.ACTIVE_TURN_KEY_PREFIX
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

private const val ACTIVE_SESSION = "kotlin_sync_session"
private const val LOGIN_PENDING = "kotlin_cloud_login_pending"
private const val PUSH_UNCERTAIN = "kotlin_cloud_push_uncertain"
internal const val CLOUD_REPLACEMENT_LOCK_KEY = "kotlin_cloud_replacement_lock_v1"
private const val CLOUD_OPERATION_LOCK_KEY = "kotlin_cloud_operation_lock_v1"
private const val NEXT_REVISION = "kotlin_sync_revision"
private const val DATA_OWNER = "kotlin_sync_data_owner_v3"
internal const val DEFERRED_BUILTIN_PROVIDER_CLEANUP = "kotlin_builtin_provider_cleanup_v1"
internal const val BUILTIN_PROVIDER_ID = "builtin-messenger-cloud-ai"
private fun revisionKey(account: String) = "kotlin_sync_pending_revisions:$account"
private val codec = Json
private val strings = ListSerializer(String.serializer())
private val revisions = MapSerializer(String.serializer(), Long.serializer())
private fun decodeQueue(raw: String): List<String> =
    if (raw.isBlank()) emptyList() else codec.decodeFromString(strings, raw)
private fun encodeQueue(values: Collection<String>): String = codec.encodeToString(strings, values.toList())
private fun encodeSession(session: SyncSession): String = encodeQueue(listOf(session.accountId, session.generation))
private fun validSessionCookie(raw: String): Boolean = raw.startsWith("messenger_session=") &&
    raw.length > "messenger_session=".length && raw.none { it == ';' || it.isWhitespace() || it.isISOControl() }
private fun scopedIdentity(account: String, base: String): String =
    "kotlin_sync_v3:" + (base.length.toString() + ":" + base + account).encodeUtf8().sha256().hex()
private fun oldScope(account: String, base: String): String {
    val url = io.ktor.http.Url(base)
    val default = if (url.protocol.name == "https") 443 else 80
    val origin = "${url.protocol.name}://${url.host}" + if (url.port != default && url.port != 0) ":${url.port}" else ""
    return "kotlin_sync_v2:" + (origin.length.toString() + ":" + origin + account).encodeUtf8().sha256().hex()
}
private fun entry(change: PendingSyncChange): String = "${change.collection.path.dropLast(1)}:${change.id}"
private fun revisionEntry(mutation: SyncMutation, key: String) = "${mutation.name}:$key"
private fun parseEntry(key: String, mutation: SyncMutation, revision: Long): PendingSyncChange {
    val kind = key.substringBefore(':')
    val id = key.substringAfter(':', "")
    require(id.isNotEmpty()) { "Invalid pending cloud change" }
    val collection = SyncCollection.entries.firstOrNull { it.path.dropLast(1) == kind }
        ?: error("Unknown pending cloud change: $kind")
    return PendingSyncChange(collection, id, mutation, revision)
}

/**
 * Atomic queue/session metadata. Entity writes must enqueue in the same Room transaction;
 * callers must not rely on an asynchronous post-write notification for durability.
 */
@Dao
internal interface CloudSyncDao {
    @Query("SELECT * FROM sync_meta WHERE accountId = :account") suspend fun syncMeta(account: String): SyncMetaRow?
    @Query("SELECT * FROM sync_meta") suspend fun allSyncMeta(): List<SyncMetaRow>
    @Upsert suspend fun writeMeta(row: SyncMetaRow)
    @Query("DELETE FROM sync_meta WHERE accountId = :account") suspend fun deleteMeta(account: String)
    @Query("SELECT * FROM kv WHERE `key` = :key") suspend fun value(key: String): KeyValueRow?
    @Upsert suspend fun writeValue(row: KeyValueRow)
    @Query("DELETE FROM kv WHERE `key` = :key") suspend fun deleteValue(key: String)

    /** Caller holds the writer transaction. Never remove a provider still used by a turn. */
    suspend fun cleanupDeferredBuiltinProvider() {
        if (value(DEFERRED_BUILTIN_PROVIDER_CLEANUP) != null &&
            value(CLOUD_REPLACEMENT_LOCK_KEY) == null && !hasActiveTurn(ACTIVE_TURN_KEY_PREFIX)) {
            deleteCloudProvider(BUILTIN_PROVIDER_ID)
            deleteValue(DEFERRED_BUILTIN_PROVIDER_CLEANUP)
        }
    }

    /** Call in a Room write transaction; only the replacement owner may pass its token. */
    suspend fun requireReplacementAccess(session: SyncSession, token: String? = null) {
        val held = value(CLOUD_REPLACEMENT_LOCK_KEY)?.value
        check(held == null || token != null && held == encodeSession(session) + ":" + token) {
            "Cloud replacement requires completion or recovery"
        }
    }

    /** Prevent a different coordinator starting network work while replacement owns the data. */
    @Transaction
    suspend fun beginCloudOperation(session: SyncSession): String {
        check(currentSession() == session) { "Cloud session changed" }
        requireReplacementAccess(session)
        check(value(CLOUD_OPERATION_LOCK_KEY) == null) { "Another cloud operation requires completion or recovery" }
        val token = cc.ptoe.messenger.data.util.randomUuid()
        writeValue(KeyValueRow(CLOUD_OPERATION_LOCK_KEY, encodeSession(session) + ":" + token))
        return token
    }

    @Transaction
    suspend fun finishCloudOperation(session: SyncSession, token: String) {
        check(value(CLOUD_OPERATION_LOCK_KEY)?.value == encodeSession(session) + ":" + token) { "Cloud operation lock changed" }
        deleteValue(CLOUD_OPERATION_LOCK_KEY)
    }

    /** Must be called by login while atomically binding the matching credentials. */
    @Transaction
    suspend fun activate(session: SyncSession) {
        requireReplacementAccess(session)
        require(session.accountId.isNotBlank() && session.generation.isNotBlank())
        val base = value("cloud_session_host")?.value?.let(::canonicalCloudBase) ?: "unbound"
        val owner = value(DATA_OWNER)?.value
        check(owner == null || owner == scopedIdentity(session.accountId, base)) { "Local data belongs to another cloud account or server; isolate or migrate it before switching" }
        writeValue(KeyValueRow(ACTIVE_SESSION, encodeSession(session)))
    }

    @Transaction
    suspend fun currentSession(): SyncSession? {
        val values = value(ACTIVE_SESSION)?.value?.let(::decodeQueue) ?: return null
        require(values.size == 2 && values.all { it.isNotBlank() }) { "Invalid stored cloud session identity" }
        return SyncSession(values[0], values[1])
    }

    @Transaction
    suspend fun expireIfCurrent(session: SyncSession): Boolean {
        if (currentSession() != session) return false
        deleteValue(ACTIVE_SESSION)
        return true
    }

    /** Every local business row belongs to one server base and account until explicitly migrated. */
    suspend fun scopeKey(session: SyncSession): String {
        check(currentSession() == session) { "Cloud session changed" }
        val base = value("cloud_session_host")?.value?.let(::canonicalCloudBase) ?: "unbound"
        val key = scopedIdentity(session.accountId, base)
        val owner = value(DATA_OWNER)?.value
        check(owner == null || owner == key) { "Local data belongs to another cloud account or server; isolate or migrate it before switching" }
        return key
    }

    suspend fun claimDataScope(session: SyncSession): String {
        val key = scopeKey(session)
        if (value(DATA_OWNER) == null) writeValue(KeyValueRow(DATA_OWNER, key))
        return key
    }

    /** Imported Core metadata did not record its origin. Never silently send it to a server. */
    suspend fun checkLegacyResolved(session: SyncSession) {
        scopeKey(session)
        for (old in allSyncMeta()) {
            if (old.accountId.startsWith("kotlin_sync_v3:")) continue
            check(old.cursor == 0L && decodeQueue(old.pendingUpserts).isEmpty() && decodeQueue(old.pendingDeletes).isEmpty()) {
                "Legacy cloud sync state has no verified server base; explicit recovery is required"
            }
        }
    }

    /** Call only after the user has verified the old state belongs to the active server. */
    @Transaction
    suspend fun adoptLegacyState(session: SyncSession, verifiedOrigin: String, scopedLegacy: Boolean = false) {
        requireReplacementAccess(session)
        val currentOrigin = value("cloud_session_host")?.value?.let(::canonicalCloudBase)
        require(currentOrigin != null && canonicalCloudBase(verifiedOrigin) == currentOrigin) { "Verified server does not match the active server" }
        val key = scopeKey(session)
        val oldKey = if (scopedLegacy) oldScope(session.accountId, currentOrigin) else session.accountId
        val old = syncMeta(oldKey) ?: return
        claimDataScope(session)
        val current = syncMeta(key) ?: SyncMetaRow(key, 0, "[]", "[]")
        check(current.cursor == 0L) { "Current server has already synchronized; manual reconciliation is required" }
        val newUpserts = decodeQueue(current.pendingUpserts)
        val newDeletes = decodeQueue(current.pendingDeletes)
        val changed = (newUpserts + newDeletes).toSet()
        val oldUpserts = decodeQueue(old.pendingUpserts).filterNot { it in changed }
        val oldDeletes = decodeQueue(old.pendingDeletes).filterNot { it in changed }
        val oldVersions = value(revisionKey(oldKey))?.value?.let { codec.decodeFromString(revisions, it) }.orEmpty()
        val newVersions = value(revisionKey(key))?.value?.let { codec.decodeFromString(revisions, it) }.orEmpty()
        val mergedVersions = oldVersions.filterKeys { k -> changed.none { k == revisionEntry(SyncMutation.UPSERT, it) || k == revisionEntry(SyncMutation.DELETE, it) } } + newVersions
        writeMeta(current.copy(cursor = old.cursor, pendingUpserts = encodeQueue(oldUpserts + newUpserts), pendingDeletes = encodeQueue(oldDeletes + newDeletes)))
        writeValue(KeyValueRow(revisionKey(key), codec.encodeToString(revisions, mergedVersions)))
        val maximumRevision = maxOf(value(NEXT_REVISION)?.value?.toLong() ?: 0L, mergedVersions.values.maxOrNull() ?: 0L)
        writeValue(KeyValueRow(NEXT_REVISION, maximumRevision.toString()))
        deleteValue(revisionKey(oldKey))
        deleteMeta(oldKey)
    }

    @Transaction
    suspend fun cursor(session: SyncSession, replacementToken: String? = null): Long {
        requireReplacementAccess(session, replacementToken)
        check(currentSession() == session) { "Cloud session changed" }
        checkLegacyResolved(session)
        return syncMeta(scopeKey(session))?.cursor ?: 0
    }

    @Transaction
    suspend fun enqueue(session: SyncSession, collection: SyncCollection, id: String, mutation: SyncMutation) {
        requireReplacementAccess(session)
        val scope = claimDataScope(session)
        enqueueForScope(scope, collection, id, mutation)
    }

    /** Retain offline edits for the bound dataset, independently of an expired or deleted cookie. */
    @Transaction
    suspend fun enqueueLocal(collection: SyncCollection, id: String, mutation: SyncMutation) {
        check(value(CLOUD_REPLACEMENT_LOCK_KEY) == null) { "Cloud replacement requires completion or recovery" }
        val session = currentSession()
        val scope = if (session != null) claimDataScope(session) else value(DATA_OWNER)?.value ?: return
        check(scope.startsWith("kotlin_sync_v3:") && scope.removePrefix("kotlin_sync_v3:").let {
            it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' }
        }) { "Invalid cloud dataset identity" }
        enqueueForScope(scope, collection, id, mutation)
    }

    private suspend fun enqueueForScope(scope: String, collection: SyncCollection, id: String, mutation: SyncMutation) {
        require(id.isNotBlank())
        // These device-local builtins must never be synchronized.
        if (id == "builtin-title-agent" && collection == SyncCollection.AGENT) return
        if (id == "builtin-messenger-cloud-ai" && collection == SyncCollection.PROVIDER) return
        val row = syncMeta(scope) ?: SyncMetaRow(scope, 0, "[]", "[]")
        val key = entry(PendingSyncChange(collection, id, mutation, 0))
        val upserts = decodeQueue(row.pendingUpserts).toMutableList()
        val deletes = decodeQueue(row.pendingDeletes).toMutableList()
        upserts.removeAll { it == key }; deletes.removeAll { it == key }
        (if (mutation == SyncMutation.UPSERT) upserts else deletes).add(key)
        val previous = value(NEXT_REVISION)?.value?.toLong() ?: 0L
        check(previous < Long.MAX_VALUE) { "Cloud sync revision exhausted" }
        val next = previous + 1
        val revisionName = revisionKey(scope)
        val versionMap = value(revisionName)?.value?.let { codec.decodeFromString(revisions, it) }?.toMutableMap() ?: mutableMapOf()
        versionMap.remove(revisionEntry(SyncMutation.UPSERT, key))
        versionMap.remove(revisionEntry(SyncMutation.DELETE, key))
        versionMap[revisionEntry(mutation, key)] = next
        writeMeta(row.copy(pendingUpserts = encodeQueue(upserts), pendingDeletes = encodeQueue(deletes)))
        writeValue(KeyValueRow(revisionName, codec.encodeToString(revisions, versionMap)))
        writeValue(KeyValueRow(NEXT_REVISION, next.toString()))
    }

    /** Legacy imported entries have revision zero until edited locally. */
    @Transaction
    suspend fun pending(session: SyncSession): List<PendingSyncChange> {
        check(currentSession() == session) { "Cloud session changed" }
        checkLegacyResolved(session)
        val row = syncMeta(scopeKey(session)) ?: return emptyList()
        val versions = value(revisionKey(scopeKey(session)))?.value?.let { codec.decodeFromString(revisions, it) } ?: emptyMap()
        fun items(raw: String, mutation: SyncMutation) = decodeQueue(raw).map { key ->
            parseEntry(key, mutation, versions[revisionEntry(mutation, key)] ?: 0L)
        }
        return items(row.pendingDeletes, SyncMutation.DELETE) + items(row.pendingUpserts, SyncMutation.UPSERT)
    }

    @Transaction
    suspend fun acknowledge(session: SyncSession, change: PendingSyncChange, replacementToken: String? = null) {
        requireReplacementAccess(session, replacementToken)
        check(currentSession() == session) { "Cloud session changed" }
        val row = syncMeta(scopeKey(session)) ?: return
        val revisionName = revisionKey(scopeKey(session))
        val versionMap = value(revisionName)?.value?.let { codec.decodeFromString(revisions, it) }?.toMutableMap() ?: mutableMapOf()
        val key = entry(change)
        val revisionEntry = revisionEntry(change.mutation, key)
        if ((versionMap[revisionEntry] ?: 0L) != change.revision) return
        val remaining = if (change.mutation == SyncMutation.UPSERT) decodeQueue(row.pendingUpserts) else decodeQueue(row.pendingDeletes)
        if (key !in remaining) return
        val updated = encodeQueue(remaining.filterNot { it == key })
        writeMeta(if (change.mutation == SyncMutation.UPSERT) row.copy(pendingUpserts = updated) else row.copy(pendingDeletes = updated))
        versionMap.remove(revisionEntry)
        writeValue(KeyValueRow(revisionName, codec.encodeToString(revisions, versionMap)))
    }
    @Query("SELECT * FROM agents WHERE id = :id") suspend fun agentRow(id: String): AgentRow?
    @Query("SELECT * FROM providers WHERE id = :id") suspend fun providerRow(id: String): ProviderRow?

    /** A raw Cloud provider request may be admitted only for this exact session and host. */
    @Transaction
    suspend fun requireExternalProvider(session: SyncSession, expected: ProviderRow) {
        check(currentSession() == session && value(DEFERRED_BUILTIN_PROVIDER_CLEANUP) == null &&
            value(CLOUD_REPLACEMENT_LOCK_KEY) == null) { "Cloud session requires recovery" }
        val host = value("cloud_session_host")?.value ?: error("Cloud session host is unavailable")
        check(expected.id == BUILTIN_PROVIDER_ID && expected.apiKey.isNotBlank() && expected.apiFormat == "chat" &&
            !expected.nativeWebSearch && runCatching { canonicalCloudBase(host) + "/v1" }.getOrNull() == expected.baseUrl &&
            providerRow(BUILTIN_PROVIDER_ID) == expected) { "Cloud provider changed or does not match the active session" }
    }
    @Query("SELECT * FROM models WHERE providerId = :id") suspend fun providerModels(id: String): List<ModelRow>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun projectRow(id: String): ProjectRow?
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun conversationRow(id: String): ConversationRow?
    @Query("SELECT EXISTS(SELECT 1 FROM kv WHERE substr(`key`, 1, length(:prefix)) = :prefix)")
    suspend fun hasActiveTurn(prefix: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM conversations AS c INNER JOIN kv AS active ON active.`key` = :prefix || c.id WHERE c.agentId = :agentId)")
    suspend fun agentHasActiveTurn(prefix: String, agentId: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM conversations AS c INNER JOIN kv AS active ON active.`key` = :prefix || c.id WHERE c.projectId = :projectId)")
    suspend fun projectHasActiveTurn(prefix: String, projectId: String): Boolean
    @Query("SELECT COUNT(*) FROM agents WHERE isDefault = 1") suspend fun defaultCount(): Int
    @Query("SELECT COUNT(*) FROM agents WHERE role = 'title'") suspend fun titleCount(): Int
    @Query("UPDATE agents SET isDefault = 0 WHERE isDefault = 1 AND id != :id") suspend fun demoteDefaults(id: String)
    @Query("UPDATE agents SET role = 'chat' WHERE role = 'title' AND id != :id") suspend fun demoteTitles(id: String)
    @Upsert suspend fun saveAgent(row: AgentRow)
    @Upsert suspend fun saveProvider(row: ProviderRow)
    @Upsert suspend fun saveModels(rows: List<ModelRow>)
    @Upsert suspend fun saveProject(row: ProjectRow)
    @Upsert suspend fun saveConversation(row: ConversationRow)
    @Upsert suspend fun saveMessages(rows: List<MessageRow>)
    @Query("DELETE FROM agents WHERE id = :id AND isDefault = 0") suspend fun deleteCloudAgent(id: String)
    @Query("DELETE FROM providers WHERE id = :id") suspend fun deleteCloudProvider(id: String)
    @Query("UPDATE agents SET marketAgentId = NULL, marketAgentVersion = NULL, marketAgentRole = NULL WHERE marketAgentId IS NOT NULL OR marketAgentVersion IS NOT NULL OR marketAgentRole IS NOT NULL") suspend fun clearMarketLinks()
    @Query("DELETE FROM models WHERE providerId = :id") suspend fun deleteCloudModels(id: String)
    @Query("DELETE FROM projects WHERE id = :id") suspend fun deleteCloudProject(id: String)
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun deleteCloudConversation(id: String)
    @Query("DELETE FROM messages WHERE conversationId = :id") suspend fun deleteCloudMessages(id: String)
    @Query("DELETE FROM conversations") suspend fun clearCloudConversations()
    @Query("DELETE FROM projects") suspend fun clearCloudProjects()
    @Query("DELETE FROM providers WHERE id != 'builtin-messenger-cloud-ai'") suspend fun clearCloudProviders()
    @Query("DELETE FROM agents WHERE id != 'builtin-title-agent'") suspend fun clearCloudAgents()

    /** Apply all collections and cursor together. Reject unsafe restore or dependency conflicts. */
    @Transaction
    suspend fun applyPull(session: SyncSession, delta: PulledCloudDelta, replaceLocal: Boolean,
        images: CloudImageRehydrator? = null, staged: MutableList<java.nio.file.Path> = mutableListOf(), replacementToken: String? = null) {
        try {
        requireReplacementAccess(session, replacementToken)
        check(currentSession() == session) { "Cloud session changed" }
        val queued = pending(session)
        claimDataScope(session)
        check(!replaceLocal || queued.isEmpty()) { "Cannot restore while local cloud changes are pending" }
        val protected = queued.map { it.collection to it.id }.toSet()
        fun blocked(collection: SyncCollection, id: String) = (collection to id) in protected
        val agents = delta.documents[SyncCollection.AGENT].orEmpty()
        // The same Room writer transaction admits turns and applies a pull, so a turn cannot
        // start between these checks and the changes. Abort the whole pull and leave its cursor
        // unchanged; a later explicit sync can apply the deferred cloud changes.
        if (replaceLocal) check(!hasActiveTurn(ACTIVE_TURN_KEY_PREFIX)) { "Cloud restore conflicts with an active turn" }
        for (doc in agents) {
            val id = doc.syncId()
            if (id != Agent.BUILTIN_TITLE_AGENT_ID && !blocked(SyncCollection.AGENT, id)) {
                check(!agentHasActiveTurn(ACTIVE_TURN_KEY_PREFIX, id)) { "Cloud agent change conflicts with an active turn" }
            }
        }
        for (doc in delta.documents[SyncCollection.PROJECT].orEmpty()) {
            val id = doc.syncId()
            if (!blocked(SyncCollection.PROJECT, id)) {
                check(!projectHasActiveTurn(ACTIVE_TURN_KEY_PREFIX, id)) { "Cloud project change conflicts with an active turn" }
            }
        }
        for (doc in delta.documents[SyncCollection.CONVERSATION].orEmpty()) {
            val id = doc.syncId()
            if (!blocked(SyncCollection.CONVERSATION, id)) {
                check(value(ACTIVE_TURN_KEY_PREFIX + id) == null) { "Cloud conversation change conflicts with an active turn" }
            }
        }
        // Running turns may re-read their provider by ID, which is recorded in a turn record
        // rather than reliably reflected in the conversation's providerId. Defer any provider
        // mutation while a turn is active until that reference can be resolved safely.
        if (delta.documents[SyncCollection.PROVIDER].orEmpty().any {
                val id = it.syncId()
                id != "builtin-messenger-cloud-ai" && !blocked(SyncCollection.PROVIDER, id)
            }) {
            check(!hasActiveTurn(ACTIVE_TURN_KEY_PREFIX)) { "Cloud provider change conflicts with an active turn" }
        }
        val defaults = agents.filter { !it.tombstone() && it.agentRow(null).isDefault && !blocked(SyncCollection.AGENT, it.syncId()) }
        check(defaults.size <= 1) { "Cloud contains multiple default agents" }
        // A different active Agent may follow the default's tool/model settings. Updating the
        // current default or demoting it for an incoming default therefore affects that turn too.
        if (defaults.isNotEmpty() || agents.any { doc ->
                val id = doc.syncId()
                !doc.tombstone() && !blocked(SyncCollection.AGENT, id) && agentRow(id)?.isDefault == true
            }) {
            check(!hasActiveTurn(ACTIVE_TURN_KEY_PREFIX)) { "Cloud default Agent change conflicts with an active turn" }
        }
        if (replaceLocal) {
            check(defaults.size == 1) { "Cloud restore has no default agent" }
            clearCloudConversations(); clearCloudProjects(); clearCloudProviders(); clearCloudAgents()
        }
        val orderedAgents = defaults + agents.filter { it !in defaults }
        for (doc in orderedAgents) {
            val id = doc.syncId()
            if (id == Agent.BUILTIN_TITLE_AGENT_ID || blocked(SyncCollection.AGENT, id)) continue
            val old = agentRow(id)
            if (doc.tombstone()) {
                if (old?.isDefault == true) continue
                // Cascading this delete could destroy a conversation with unsent edits.
                check(queued.none { it.collection == SyncCollection.CONVERSATION && conversationRow(it.id)?.agentId == id }) { "Cloud agent deletion conflicts with a pending conversation" }
                deleteCloudAgent(id)
            } else {
                val row = doc.agentRow(old)
                if (row.isDefault) {
                    check(queued.none { it.collection == SyncCollection.AGENT && it.id != id && agentRow(it.id)?.isDefault == true }) { "Cloud default conflicts with a pending agent" }
                    demoteDefaults(id)
                }
                if (row.role == Agent.ROLE_TITLE) {
                    check(queued.none { it.collection == SyncCollection.AGENT && it.id != id && agentRow(it.id)?.role == Agent.ROLE_TITLE }) { "Cloud title role conflicts with a pending agent" }
                    demoteTitles(id)
                }
                // A stale non-default update must not remove the only default.
                saveAgent(if (old?.isDefault == true && !row.isDefault && defaults.isEmpty()) row.copy(isDefault = true) else row)
            }
        }
        for (doc in delta.documents[SyncCollection.PROVIDER].orEmpty()) {
            val id = doc.syncId()
            if (id == "builtin-messenger-cloud-ai" || blocked(SyncCollection.PROVIDER, id)) continue
            if (doc.tombstone()) deleteCloudProvider(id) else {
                val old = providerRow(id)
                val models = doc.modelRows(id, providerModels(id).associateBy { it.id })
                saveProvider(doc.providerRow(old, replaceLocal)); deleteCloudModels(id); saveModels(models)
            }
        }
        for (doc in delta.documents[SyncCollection.PROJECT].orEmpty()) {
            val id = doc.syncId()
            if (blocked(SyncCollection.PROJECT, id)) continue
            if (doc.tombstone()) {
                check(queued.none { it.collection == SyncCollection.CONVERSATION && conversationRow(it.id)?.projectId == id }) { "Cloud project deletion conflicts with a pending conversation" }
                deleteCloudProject(id)
            } else saveProject(doc.projectRow(projectRow(id)))
        }
        for (doc in delta.documents[SyncCollection.CONVERSATION].orEmpty()) {
            val id = doc.syncId()
            if (blocked(SyncCollection.CONVERSATION, id)) continue
            if (doc.tombstone()) deleteCloudConversation(id) else {
                val localDoc = images?.stage(doc, staged) ?: CloudImageRehydrator.rejectUnstagedImages(doc)
                val row = localDoc.conversationRow()
                check(agentRow(row.agentId) != null) { "Cloud conversation references an unavailable agent" }
                check(row.projectId == null || projectRow(row.projectId) != null) { "Cloud conversation references an unavailable project" }
                val messages = localDoc.messageRows()
                saveConversation(row); deleteCloudMessages(id); saveMessages(messages)
            }
        }
        if (staged.isNotEmpty()) requireNotNull(images).syncDirectory()
        check(defaultCount() == 1) { "Cloud sync must preserve exactly one default agent" }
        if (titleCount() == 0) {
            val now = cc.ptoe.messenger.data.util.currentTimeMillis()
            val seed = Agent.builtinTitleAgent(now)
            val old = agentRow(seed.id)
            saveAgent(old?.copy(role = Agent.ROLE_TITLE) ?: AgentRow(seed.id, seed.name, null, seed.systemPrompt, "", null,
                seed.temperature.toDouble(), seed.topP.toDouble(), null, null, false, false, false, false, false, false, false,
                null, null, null, Agent.ROLE_TITLE, false, false, "", now, now))
        }
        val row = syncMeta(scopeKey(session)) ?: SyncMetaRow(scopeKey(session), 0, "[]", "[]")
        check(delta.latestVersion >= 0) { "Invalid cloud cursor" }
        writeMeta(row.copy(cursor = delta.latestVersion))
        } catch (failure: Throwable) {
            try { images?.cleanCreated(staged) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY timestamp, id") suspend fun cloudMessages(id: String): List<MessageRow>

    /** Snapshot and revision are read in one transaction. Missing rows fail without dropping the queue. */
    @Transaction
    suspend fun pendingSnapshots(session: SyncSession, replacementToken: String? = null): List<PendingSyncChange> {
        requireReplacementAccess(session, replacementToken)
        return pending(session).map { change ->
            if (change.mutation == SyncMutation.DELETE) change else {
                val body = when (change.collection) {
                    SyncCollection.AGENT -> requireNotNull(agentRow(change.id)) { "Pending agent is missing" }.cloudRequest()
                    SyncCollection.PROVIDER -> requireNotNull(providerRow(change.id)) { "Pending provider is missing" }.cloudRequest(providerModels(change.id))
                    SyncCollection.PROJECT -> requireNotNull(projectRow(change.id)) { "Pending project is missing" }.cloudRequest()
                    SyncCollection.CONVERSATION -> requireNotNull(conversationRow(change.id)) { "Pending conversation is missing" }.cloudRequest(cloudMessages(change.id))
                }
                change.copy(body = body)
            }
        }
    }

    /** Reads the imported Core KV session and binds a fresh session identity atomically. */
    @Transaction
    suspend fun restoreCredentials(generation: String, origin: String): StoredCloudSession? {
        val cookie = value("cloud_session")?.value?.takeIf(::validSessionCookie) ?: return null
        val host = value("cloud_session_host")?.value ?: return null
        if (runCatching { canonicalCloudBase(host) }.getOrNull() != origin) return null
        val userJson = value("cloud_user_json")?.value ?: return null
        val user = NetworkClient.json.decodeFromString(CloudUser.serializer(), userJson)
        if (user.id.isBlank()) return null
        val current = currentSession()
        check(current == null || current.accountId == user.id) { "Stored cloud session identity does not match account" }
        val session = current ?: SyncSession(user.id, generation).also { activate(it) }
        return StoredCloudSession(session, cookie, origin, user)
    }

    @Transaction
    suspend fun saveCredentials(value: StoredCloudSession) {
        check(this.value(CLOUD_REPLACEMENT_LOCK_KEY) == null) { "Cloud replacement requires completion or recovery" }
        check(this.value(CLOUD_OPERATION_LOCK_KEY) == null) { "Another cloud operation requires completion or recovery" }
        cleanupDeferredBuiltinProvider()
        check(this.value(DEFERRED_BUILTIN_PROVIDER_CLEANUP) == null) { "Cloud provider cleanup is waiting for active turns" }
        require(value.session.accountId == value.user.id && value.user.id.isNotBlank())
        require(validSessionCookie(value.cookie))
        val base = canonicalCloudBase(value.host)
        val owner = this.value(DATA_OWNER)?.value
        val targetKey = scopedIdentity(value.session.accountId, base)
        check(owner == null || owner == targetKey) { "Local data belongs to another cloud account or server; isolate or migrate it before switching" }
        val possibleLegacy = oldScope(value.session.accountId, base)
        for (row in allSyncMeta()) {
            if (row.accountId == targetKey || row.accountId == value.session.accountId || row.accountId == possibleLegacy) continue
            check(row.cursor == 0L && decodeQueue(row.pendingUpserts).isEmpty() && decodeQueue(row.pendingDeletes).isEmpty()) {
                "Cloud state belongs to another account or server; isolate or recover it before switching"
            }
        }
        val oldSession = currentSession()
        val oldHost = this.value("cloud_session_host")?.value
        if (oldSession != null && oldSession != value.session) {
            check(!hasActiveTurn(ACTIVE_TURN_KEY_PREFIX)) { "Cloud login conflicts with an active turn" }
        }
        if (oldSession?.accountId != value.session.accountId || oldHost != value.host) {
            check(!hasActiveTurn(ACTIVE_TURN_KEY_PREFIX)) { "Cloud login conflicts with an active turn" }
            deleteCloudProvider(BUILTIN_PROVIDER_ID)
            deleteValue("last_builtin_models_sync_at")
            clearMarketLinks()
        }
        writeValue(KeyValueRow(LOGIN_PENDING, encodeSession(value.session)))
        writeValue(KeyValueRow("cloud_session", value.cookie))
        writeValue(KeyValueRow("cloud_session_host", base))
        writeValue(KeyValueRow("cloud_user_json", NetworkClient.json.encodeToString(CloudUser.serializer(), value.user)))
        activate(value.session)
    }

    @Transaction
    suspend fun clearCredentialsIfCurrent(session: SyncSession): Boolean {
        if (currentSession() != session) return false
        if (value(LOGIN_PENDING)?.value == encodeSession(session)) deleteValue(LOGIN_PENDING)
        deleteValue("cloud_session"); deleteValue("cloud_session_host"); deleteValue("cloud_user_json")
        deleteValue(ACTIVE_SESSION)
        val builtin = providerRow(BUILTIN_PROVIDER_ID)
        if (builtin != null) {
            // Revocation is immediate even when an in-flight turn still references this row.
            saveProvider(builtin.copy(apiKey = ""))
            writeValue(KeyValueRow(DEFERRED_BUILTIN_PROVIDER_CLEANUP, "pending"))
        }
        if (value(CLOUD_REPLACEMENT_LOCK_KEY) == null) {
            cleanupDeferredBuiltinProvider()
            deleteValue("last_builtin_models_sync_at")
            clearMarketLinks()
        }
        return true
    }

    /** Update a market link without overwriting edits made during the network request. */
    /** Retained until a successful push or explicit successful recovery. */
    @Transaction
    suspend fun automaticPushUncertain(): Boolean = value(PUSH_UNCERTAIN) != null

    @Transaction
    suspend fun beginAutomaticPush(session: SyncSession) {
        requireReplacementAccess(session)
        val scope = claimDataScope(session)
        check(value(PUSH_UNCERTAIN) == null) { "Previous cloud push outcome is unknown; retry explicitly" }
        writeValue(KeyValueRow(PUSH_UNCERTAIN, scope))
    }

    /** Explicit upload may resolve an uncertain push, but stays uncertain until it succeeds. */
    @Transaction
    suspend fun beginExplicitPush(session: SyncSession) {
        requireReplacementAccess(session)
        val scope = claimDataScope(session)
        val previous = value(PUSH_UNCERTAIN)?.value
        check(previous == null || previous == scope) { "Cloud push belongs to another dataset" }
        if (previous == null) writeValue(KeyValueRow(PUSH_UNCERTAIN, scope))
    }

    @Transaction
    suspend fun finishPush(session: SyncSession) {
        requireReplacementAccess(session)
        val scope = claimDataScope(session)
        val previous = value(PUSH_UNCERTAIN)?.value
        check(previous == null || previous == scope) { "Cloud push belongs to another dataset" }
        if (previous != null) deleteValue(PUSH_UNCERTAIN)
    }

    /** A sign-in requires a choice before any automatic or manual sync can run. */
    @Transaction
    suspend fun loginChoicePending(): Boolean {
        val current = currentSession() ?: return false
        return value(LOGIN_PENDING)?.value == encodeSession(current)
    }

    @Transaction
    suspend fun finishLoginChoice(session: SyncSession) {
        requireReplacementAccess(session)
        check(currentSession() == session && value(LOGIN_PENDING)?.value == encodeSession(session)) { "Cloud login choice or session changed" }
        deleteValue(LOGIN_PENDING)
    }

    @Transaction
    suspend fun commitMarketAgent(session: SyncSession, expected: AgentRow?, updated: AgentRow) {
        requireReplacementAccess(session)
        check(currentSession() == session) { "Cloud session changed" }
        check(agentRow(updated.id) == expected) { "Agent changed while the market request was in progress; the remote request may have succeeded" }
        if (expected == null) check(!updated.isDefault) { "Market import cannot replace the default Agent" }
        else check(updated.isDefault == expected.isDefault) { "Market operations cannot change the default Agent" }
        saveAgent(updated)
        enqueue(session, SyncCollection.AGENT, updated.id, SyncMutation.UPSERT)
    }

    /** Refresh user and provision the account-scoped built-in Provider in one transaction. */
    @Transaction
    suspend fun updateCloudUser(session: SyncSession, user: CloudUser, serverUrl: String) {
        requireReplacementAccess(session)
        check(currentSession() == session && user.id == session.accountId) { "Cloud session changed" }
        check(value(DEFERRED_BUILTIN_PROVIDER_CLEANUP) == null) { "Cloud provider cleanup is waiting for active turns" }
        if (!user.aiApiKey.isNullOrBlank()) check(!hasActiveTurn(ACTIVE_TURN_KEY_PREFIX)) { "Cloud provider change conflicts with an active turn" }
        writeValue(KeyValueRow("cloud_user_json", NetworkClient.json.encodeToString(CloudUser.serializer(), user)))
        val key = user.aiApiKey?.takeIf { it.isNotBlank() } ?: return
        val id = "builtin-messenger-cloud-ai"
        val now = cc.ptoe.messenger.data.util.currentTimeMillis()
        val old = providerRow(id)
        saveProvider(old?.copy(baseUrl = "${serverUrl.trimEnd('/')}/v1", apiKey = key, updatedAt = now)
            ?: ProviderRow(id, "Messenger Cloud AI", "${serverUrl.trimEnd('/')}/v1", key, "chat", false, now, now))
    }

    @Transaction
    suspend fun replaceBuiltinModels(session: SyncSession, expectedProvider: ProviderRow, entries: List<kotlinx.serialization.json.JsonObject>, now: Long): Int {
        requireReplacementAccess(session)
        check(currentSession() == session) { "Cloud session changed" }
        val id = "builtin-messenger-cloud-ai"
        check(expectedProvider.id == id && providerRow(id) == expectedProvider) { "Built-in provider changed during model sync" }
        val old = providerModels(id).associateBy { it.modelId }
        check(entries.isNotEmpty() || old.isEmpty()) { "Built-in model sync returned no models; keeping the existing catalog" }
        val rows = entries.map { it.builtinModelRow(id, old, now) }
        check(rows.map { it.modelId }.distinct().size == rows.size) { "Duplicate built-in model IDs" }
        deleteCloudModels(id)
        saveModels(rows)
        writeValue(KeyValueRow("last_builtin_models_sync_at", now.toString()))
        return rows.size
    }

    @Query("SELECT id FROM agents WHERE id != 'builtin-title-agent'") suspend fun syncAgentIds(): List<String>
    @Query("SELECT id FROM providers WHERE id != 'builtin-messenger-cloud-ai'") suspend fun syncProviderIds(): List<String>
    @Query("SELECT id FROM projects") suspend fun syncProjectIds(): List<String>
    @Query("SELECT id FROM conversations WHERE id NOT LIKE 'acp:%'") suspend fun syncConversationIds(): List<String>

    /** Queue a complete local snapshot without clearing existing deletions. */
    @Transaction
    suspend fun enqueueFullSnapshot(session: SyncSession, replacementToken: String? = null) {
        requireReplacementAccess(session, replacementToken)
        check(currentSession() == session) { "Cloud session changed" }
        check(defaultCount() == 1) { "Exactly one default Agent is required" }
        val scope = claimDataScope(session)
        for (id in syncAgentIds()) enqueueForScope(scope, SyncCollection.AGENT, id, SyncMutation.UPSERT)
        for (id in syncProviderIds()) enqueueForScope(scope, SyncCollection.PROVIDER, id, SyncMutation.UPSERT)
        for (id in syncProjectIds()) enqueueForScope(scope, SyncCollection.PROJECT, id, SyncMutation.UPSERT)
        for (id in syncConversationIds()) enqueueForScope(scope, SyncCollection.CONVERSATION, id, SyncMutation.UPSERT)
    }

    @Query("SELECT * FROM agents WHERE isDefault = 1") suspend fun replacementDefaults(): List<AgentRow>
    @Query("UPDATE conversations SET agentId = :newId WHERE agentId = :oldId") suspend fun reparentConversations(oldId: String, newId: String)

    /** Acquired in the same SQLite writer transaction used for admitting a turn. */
    @Transaction
    suspend fun beginReplacement(session: SyncSession): String {
        check(currentSession() == session) { "Cloud session changed" }
        check(!hasActiveTurn(ACTIVE_TURN_KEY_PREFIX)) { "Cloud replacement conflicts with an active turn" }
        check(value(CLOUD_REPLACEMENT_LOCK_KEY) == null) { "A prior cloud replacement requires recovery" }
        check(value(CLOUD_OPERATION_LOCK_KEY) == null) { "Another cloud operation requires completion or recovery" }
        val token = cc.ptoe.messenger.data.util.randomUuid()
        writeValue(KeyValueRow(CLOUD_REPLACEMENT_LOCK_KEY, encodeSession(session) + ":" + token))
        return token
    }

    @Transaction
    suspend fun finishReplacement(session: SyncSession, token: String) {
        check(value(CLOUD_REPLACEMENT_LOCK_KEY)?.value == encodeSession(session) + ":" + token) { "Cloud replacement lock changed" }
        deleteValue(CLOUD_REPLACEMENT_LOCK_KEY)
    }

    @Transaction
    suspend fun localSnapshot(session: SyncSession, replacementToken: String? = null): CloudLocalSnapshot {
        requireReplacementAccess(session, replacementToken)
        check(currentSession() == session) { "Cloud session changed" }
        checkLegacyResolved(session)
        val defaults = replacementDefaults()
        check(defaults.size == 1) { "Exactly one default Agent is required" }
        val revision = value(NEXT_REVISION)?.value?.toLong() ?: 0L
        return CloudLocalSnapshot(revision, defaults.single().id, mapOf(
            SyncCollection.AGENT to syncAgentIds().toSet(), SyncCollection.PROVIDER to syncProviderIds().toSet(),
            SyncCollection.PROJECT to syncProjectIds().toSet(), SyncCollection.CONVERSATION to syncConversationIds().toSet(),
        ))
    }

    /** Atomically rekey the default Agent, preserve conversations, and enqueue the full snapshot. */
    @Transaction
    suspend fun prepareReplacement(session: SyncSession, plan: CloudReplacementPlan, token: String): Long {
        check(value(CLOUD_REPLACEMENT_LOCK_KEY)?.value == encodeSession(session) + ":" + token) { "Cloud replacement lock changed" }
        check(!hasActiveTurn(ACTIVE_TURN_KEY_PREFIX)) { "Cloud replacement conflicts with an active turn" }
        val snapshot = localSnapshot(session, token)
        check(snapshot.revision == plan.localRevision && snapshot.defaultAgentId == plan.localDefaultId) { "Local data changed during replacement" }
        check(plan.cloudDefaultId != Agent.BUILTIN_TITLE_AGENT_ID) { "Cloud default uses a reserved Agent ID" }
        // Validate every imported queue entry before modifying anything.
        pending(session)
        if (plan.localDefaultId != plan.cloudDefaultId) {
            check(agentRow(plan.cloudDefaultId) == null) { "Cloud default Agent ID conflicts with a local Agent" }
            val old = requireNotNull(agentRow(plan.localDefaultId))
            demoteDefaults(plan.cloudDefaultId)
            saveAgent(old.copy(id = plan.cloudDefaultId, isDefault = true))
            reparentConversations(plan.localDefaultId, plan.cloudDefaultId)
            deleteCloudAgent(plan.localDefaultId)
            // Do not upload the now-nonexistent former default from an imported pending queue.
            val meta = syncMeta(scopeKey(session)) ?: SyncMetaRow(scopeKey(session), 0, "[]", "[]")
            val oldKey = "agent:${plan.localDefaultId}"
            writeMeta(meta.copy(pendingUpserts = encodeQueue(decodeQueue(meta.pendingUpserts).filterNot { it == oldKey }),
                pendingDeletes = encodeQueue(decodeQueue(meta.pendingDeletes).filterNot { it == oldKey })))
            val name = revisionKey(scopeKey(session))
            val versions = value(name)?.value?.let { codec.decodeFromString(revisions, it) }?.toMutableMap() ?: mutableMapOf()
            versions.remove(revisionEntry(SyncMutation.UPSERT, oldKey)); versions.remove(revisionEntry(SyncMutation.DELETE, oldKey))
            writeValue(KeyValueRow(name, codec.encodeToString(revisions, versions)))
        }
        // The executor already deleted every remote-only live row and checked the cloud version;
        // any remaining queued deletion describes an absent/tombstoned row and is satisfied.
        val meta = syncMeta(scopeKey(session)) ?: SyncMetaRow(scopeKey(session), 0, "[]", "[]")
        val versionsKey = revisionKey(scopeKey(session))
        val versions = value(versionsKey)?.value?.let { codec.decodeFromString(revisions, it) }?.toMutableMap() ?: mutableMapOf()
        for (key in decodeQueue(meta.pendingDeletes)) versions.remove(revisionEntry(SyncMutation.DELETE, key))
        writeMeta(meta.copy(pendingDeletes = "[]"))
        writeValue(KeyValueRow(versionsKey, codec.encodeToString(revisions, versions)))
        enqueueFullSnapshot(session, token)
        return localSnapshot(session, token).revision
    }

}
