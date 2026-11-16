/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Restore prototype only. The caller must first prove exclusive ownership, stop and join every
 * worker and artifact session, and validate the complete database/assets pair. A content match
 * alone is not such proof. This operation does not rebind identity or admit Room for use.
 */
internal object CoreRestoreAuthorizationReset {
    data class Result(val artifacts: Int, val executionGrants: Int)

    suspend fun reset(db: LocalDatabase, evidence: CoreRestoreEvidence.ContentMatch): Result =
        db.useWriterConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val rows = db.rows()
                check(rows.getValue(CoreStoreMigration.MARKER_KEY)?.value == CoreStoreMigration.MARKER_VALUE &&
                    rows.getValue(CoreStoreMigration.SOURCE_FINGERPRINT_KEY)?.value == evidence.previousIdentity &&
                    rows.getValue(CoreStoreMigration.SOURCE_CONTENT_FINGERPRINT_KEY)?.value == evidence.contentHash &&
                    rows.getValue(ROOM_PUBLICATION_ID_KEY)?.value == evidence.publicationId) {
                    "Restore evidence no longer matches the Room database"
                }
                val artifacts = rows.valuesByPrefix("artifact:")
                for (row in artifacts) {
                    require(row.value.length <= 8 * 1024 * 1024) { "Artifact metadata exceeds restore limit" }
                    checkJsonDepth(row.value)
                    val objectValue = Json.parseToJsonElement(row.value) as? JsonObject ?: error("Invalid artifact metadata")
                    val id = (objectValue["id"] as? JsonPrimitive)?.contentOrNull
                    check(id != null && id == row.key.removePrefix("artifact:")) { "Invalid artifact identity" }
                    val sanitized = JsonObject(objectValue + mapOf(
                        "trustedOnDevice" to JsonPrimitive(false),
                        "approvedDomains" to JsonArray(emptyList()),
                    ))
                    rows.upsertValues(listOf(KeyValueRow(row.key, sanitized.toString())))
                }
                val grants = rows.valuesByPrefix("artifact_execution_authorization:")
                for (grant in grants) rows.deleteValue(grant.key)
                Result(artifacts.size, grants.size)
            }
        }

    // Bound parser recursion without interpreting brackets within JSON strings.
    private fun checkJsonDepth(value: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (c in value) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 64) { "Artifact metadata nesting exceeds restore limit" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Invalid artifact metadata" } }
            }
        }
        require(!quoted && depth == 0) { "Invalid artifact metadata" }
    }
}
