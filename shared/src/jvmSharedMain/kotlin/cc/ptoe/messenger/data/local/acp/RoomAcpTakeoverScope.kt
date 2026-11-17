/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.BUILTIN_PROVIDER_ID
import cc.ptoe.messenger.data.local.room.CLOUD_REPLACEMENT_LOCK_KEY
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ModelRow
import cc.ptoe.messenger.data.local.room.ProviderRow
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Private, per-worker BYOK scope. Provider credentials never leave the catalog. The caller must
 * retain the same configuration fence across all profile, model, provider and Cloud writers.
 */
internal class RoomAcpTakeoverScope private constructor(
    private val db: LocalDatabase,
    val models: List<AcpTakeoverModel>,
    val preferredModelId: String?,
    private val salt: ByteArray,
    private val digests: Map<String, ByteArray>,
) : AcpRelayCatalog {
    override fun toString() = "RoomAcpTakeoverScope(redacted)"

    override suspend fun find(id: String): AcpRelayModel? = db.useWriterConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            check(db.rows().getValue(CLOUD_REPLACEMENT_LOCK_KEY) == null) { "Cloud replacement requires completion or recovery" }
            val expected = digests[id] ?: return@withTransaction null
            connection.usePrepared("SELECT $individualMaximum, ($totalLength) FROM models m JOIN providers p ON p.id=m.providerId WHERE m.id=?") { statement ->
                statement.bindText(1, id)
                if (statement.step()) check(statement.getLong(0) <= MAX_FIELD && statement.getLong(1) <= MAX_TOTAL) { "ACP takeover model exceeds limit" }
            }
            val model = readModel(db, id) ?: return@withTransaction null
            if (!model.relay.enabled || model.relay.providerId == BUILTIN_PROVIDER_ID || !MessageDigest.isEqual(expected, digest(salt, model))) null else model.relay
        }
    }

    companion object {
        private const val MAX_FIELD = 1024L * 1024
        private const val MAX_TOTAL = 16L * 1024 * 1024
        private val lengths = listOf("m.id", "m.providerId", "m.modelId", "m.displayName", "m.inputModalities", "m.outputModalities",
            "m.metadata", "m.metadataOverrides", "p.id", "p.name", "p.baseUrl", "p.apiKey", "p.apiFormat")
            .map { "coalesce(length(CAST($it AS BLOB)),0)" }
        private val individualMaximum = lengths.joinToString(",", "max(", ")")
        private val totalLength = lengths.joinToString(" + ")
        private const val filter = "m.isEnabled=1 AND p.id<>? AND p.apiFormat IN ('auto','chat','responses','messages','interactions')"
        suspend fun capture(db: LocalDatabase): RoomAcpTakeoverScope = db.useWriterConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val rows = db.rows()
                check(rows.getValue(CLOUD_REPLACEMENT_LOCK_KEY) == null) { "Cloud replacement requires completion or recovery" }
                val preferred = connection.usePrepared("SELECT defaultModelId,coalesce(length(CAST(defaultModelId AS BLOB)),0) FROM agents WHERE isDefault=1 LIMIT 2") { statement ->
                    check(statement.step()) { "Exactly one default Agent is required" }
                    check(statement.getLong(1) <= 512) { "ACP preferred model exceeds limit" }
                    val value = if (statement.isNull(0)) null else statement.getText(0)
                    check(!statement.step()) { "Exactly one default Agent is required" }
                    value
                }
                if (preferred != null) connection.usePrepared("SELECT 1 FROM models m JOIN providers p ON p.id=m.providerId WHERE m.id=? AND p.id=?") { statement ->
                    statement.bindText(1, preferred); statement.bindText(2, BUILTIN_PROVIDER_ID)
                    check(!statement.step()) {
                        "ACP provider takeover for the built-in Cloud model is unavailable"
                    }
                }
                connection.usePrepared("SELECT count(*),coalesce(sum($totalLength),0),coalesce(max($individualMaximum),0) FROM models m JOIN providers p ON p.id=m.providerId WHERE $filter") { statement ->
                    statement.bindText(1, BUILTIN_PROVIDER_ID)
                    check(statement.step() && statement.getLong(0) <= 256 && statement.getLong(1) <= MAX_TOTAL && statement.getLong(2) <= MAX_FIELD) {
                        "ACP takeover model scope exceeds limit"
                    }
                }
                val ids = connection.usePrepared("SELECT m.id FROM models m JOIN providers p ON p.id=m.providerId WHERE $filter ORDER BY m.createdAt,m.id LIMIT 257") { statement ->
                    statement.bindText(1, BUILTIN_PROVIDER_ID)
                    buildList { while (statement.step()) add(statement.getText(0)) }
                }
                check(ids.size <= 256) { "ACP takeover model scope exceeds limit" }
                val salt = ByteArray(32).also(SecureRandom()::nextBytes)
                val snapshots = ids.mapNotNull { readModel(db, it) }.filter { it.relay.enabled && it.relay.providerId != BUILTIN_PROVIDER_ID }
                val public = snapshots.map { AcpTakeoverModel(it.relay.id, it.relay.name, it.relay.format.wire, true) }
                check(preferred != null && public.any { it.id == preferred }) {
                    "Select an enabled supported BYOK model on the default Agent before ACP provider takeover"
                }
                val digests = snapshots.associate { it.relay.id to digest(salt, it) }
                RoomAcpTakeoverScope(db, public, preferred, salt, digests)
            }
        }

        private class Snapshot(val model: ModelRow, val provider: ProviderRow, val relay: AcpRelayModel) {
            override fun toString() = "AcpTakeoverSnapshot(redacted)"
        }
        private suspend fun readModel(db: LocalDatabase, id: String): Snapshot? {
            val row = db.rows().model(id) ?: return null
            val provider = db.rows().provider(row.providerId) ?: return null
            val format = when (provider.apiFormat) {
                "auto", "chat" -> AcpRelayFormat.CHAT
                "responses" -> AcpRelayFormat.RESPONSES
                "messages" -> AcpRelayFormat.MESSAGES
                "interactions" -> AcpRelayFormat.INTERACTIONS
                else -> return null
            }
            return Snapshot(row, provider, AcpRelayModel(row.id, row.displayName, provider.id, row.createdAt, row.isEnabled, row.modelId, provider.baseUrl, provider.apiKey, format))
        }

        private fun digest(salt: ByteArray, snapshot: Snapshot): ByteArray = MessageDigest.getInstance("SHA-256").run {
            update(salt)
            val m = snapshot.model; val p = snapshot.provider
            for (value in listOf(m.id, m.providerId, m.modelId, m.displayName, m.isEnabled, m.contextWindow, m.inputRate, m.outputRate,
                m.cacheReadRate, m.cacheWriteRate, m.inputModalities, m.outputModalities, m.supportsToolCalling, m.supportsThinking,
                m.supportsJsonOutput, m.supportsTemperature, m.createdAt, m.metadata, m.metadataOverrides,
                p.id, p.name, p.baseUrl, p.apiKey, p.apiFormat, p.nativeWebSearch, p.createdAt, p.updatedAt)) {
                if (value == null) { update(0.toByte()); continue }
                update(1.toByte())
                val bytes = value.toString().toByteArray(Charsets.UTF_8)
                update(ByteBuffer.allocate(4).putInt(bytes.size).array()); update(bytes)
            }
            digest()
        }
    }
}
