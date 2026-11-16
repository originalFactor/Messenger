/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Explicit plan for destructive cloud replacement; it never performs remote writes. */
internal data class CloudReplacementPlan(
    val serverVersion: Long,
    val localRevision: Long,
    val localDefaultId: String,
    val cloudDefaultId: String,
    val deletions: List<PendingSyncChange>,
)
internal data class CloudLocalSnapshot(
    val revision: Long,
    val defaultAgentId: String,
    val ids: Map<SyncCollection, Set<String>>,
)

/** Versions must agree across the entire fetch; IDs are validated before any remote mutation. */
internal fun planCloudReplacement(
    beforeVersion: Long,
    afterVersion: Long,
    remote: PulledCloudDelta,
    local: CloudLocalSnapshot,
): CloudReplacementPlan {
    require(beforeVersion >= 0 && beforeVersion == afterVersion && remote.latestVersion == beforeVersion) {
        "Cloud changed while preparing replacement; no remote changes were made"
    }
    require(local.defaultAgentId.isNotBlank() && local.defaultAgentId in local.ids[SyncCollection.AGENT].orEmpty()) {
        "A local default Agent is required"
    }
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    fun JsonObject.id() = text("_id")?.takeIf { it.isNotBlank() } ?: error("Cloud document has no ID")
    val activeAgents = remote.documents[SyncCollection.AGENT].orEmpty().filterNot { it.flag("deleted") }
    val defaults = activeAgents.filter { it.flag("isDefault") }
    require(defaults.size == 1) { "Cloud must contain exactly one default Agent" }
    val cloudDefault = defaults.single().id()
    require(cloudDefault != "builtin-title-agent") { "Cloud default uses a reserved Agent ID" }
    require(cloudDefault == local.defaultAgentId || cloudDefault !in local.ids[SyncCollection.AGENT].orEmpty()) {
        "Cloud default Agent ID conflicts with a local Agent"
    }
    val deletions = mutableListOf<PendingSyncChange>()
    for (collection in SyncCollection.entries) {
        val seen = mutableSetOf<String>()
        for (row in remote.documents[collection].orEmpty()) {
            val id = row.id()
            require(seen.add(id)) { "Cloud returned duplicate document IDs" }
            if (row.flag("deleted") || (collection == SyncCollection.AGENT && (id == cloudDefault || id == "builtin-title-agent")) ||
                (collection == SyncCollection.PROVIDER && id == "builtin-messenger-cloud-ai")) continue
            val retainedIds = if (collection == SyncCollection.AGENT && cloudDefault != local.defaultAgentId) {
                local.ids[collection].orEmpty() - local.defaultAgentId + cloudDefault
            } else local.ids[collection].orEmpty()
            if (id !in retainedIds) deletions += PendingSyncChange(collection, id, SyncMutation.DELETE, 0)
        }
    }
    return CloudReplacementPlan(beforeVersion, local.revision, local.defaultAgentId, cloudDefault, deletions)
}
