/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT

/** A missing side is evidence of a difference, not evidence of deletion or import intent. */
internal data class LegacyRecoveryPair<T>(val id: String, val legacy: T?, val core: T?)
internal data class LegacyRecoveryProject(val name: String, val workspace: String, val createdAt: Long, val updatedAt: Long,
    val deviceId: String?, val deviceIdKnown: Boolean)
internal data class LegacyRecoveryConversation(val title: String, val projectId: String?)
internal data class LegacyRecoveryProvider(val name: String, val apiFormat: String?)
internal data class LegacyRoomRecoveryPreview(
    val legacyVersion: Long,
    val projects: List<LegacyRecoveryPair<LegacyRecoveryProject>>,
    val conversations: List<LegacyRecoveryPair<LegacyRecoveryConversation>>,
    val providers: List<LegacyRecoveryPair<LegacyRecoveryProvider>>,
)

/**
 * Compares selected fields only; equality is not evidence that the datasets are reconciled.
 * The caller must exclude all writers and keep both transactions and paths stable. A read-only
 * SQLite open may use WAL shared-memory sidecars, so the legacy input must be a scratch snapshot.
 * This is not an apply plan, source identity proof, or evidence that either side was deleted.
 */
internal object LegacyRoomRecoveryInspection {
    private const val MAX_ROWS = 10_000
    private const val MAX_TEXT_BYTES = 256 * 1024
    private const val MAX_TOTAL_TEXT_BYTES = 16L * 1024 * 1024
    private class Budget { var bytes = 0L }

    fun compare(legacy: SQLiteConnection, core: SQLiteConnection): LegacyRoomRecoveryPreview {
        check(legacy.inTransaction() && core.inTransaction()) { "Recovery preview requires held transactions" }
        val version = legacy.prepare("PRAGMA user_version").use { row -> check(row.step()); row.getLong(0) }
        require(version == 21L || version == 22L) { "Unsupported legacy Room schema version" }
        legacy.prepare("PRAGMA quick_check").use { row ->
            check(row.step() && row.getText(0) == "ok" && !row.step()) { "Legacy Room integrity check failed" }
        }
        CoreStoreInspector.inspectTransaction(core)
        val hasFormat = legacy.prepare("SELECT 1 FROM pragma_table_info('providers') WHERE name='apiFormat' LIMIT 1").use { it.step() }
        check(version != 22L || hasFormat) { "Legacy Room provider format is missing" }
        val budget = Budget()

        // Bound data in SQLite before getText materializes any potentially enormous field on the JVM.
        preflight(legacy, "projects", listOf("id", "name", "workspace"), budget)
        preflight(core, "projects", listOf("id", "name", "workspace", "deviceId"), budget)
        preflight(legacy, "conversations", listOf("id", "title", "projectId"), budget)
        preflight(core, "conversations", listOf("id", "title", "projectId"), budget)
        preflight(legacy, "providers", listOfNotNull("id", "name", "apiFormat".takeIf { hasFormat }), budget)
        preflight(core, "providers", listOf("id", "name", "apiFormat"), budget)
        budget.bytes = 0

        val oldProjects = rows(legacy, "SELECT id,name,workspace,createdAt,updatedAt FROM projects ORDER BY id") { row ->
            row.text(0, budget) to LegacyRecoveryProject(row.text(1, budget), row.text(2, budget), row.integer(3), row.integer(4), null, false)
        }
        val newProjects = rows(core, "SELECT id,name,workspace,createdAt,updatedAt,deviceId FROM projects ORDER BY id") { row ->
            row.text(0, budget) to LegacyRecoveryProject(row.text(1, budget), row.text(2, budget), row.integer(3), row.integer(4), row.optionalText(5, budget), true)
        }
        val oldConversations = rows(legacy, "SELECT id,title,projectId FROM conversations ORDER BY id") { row ->
            row.text(0, budget) to LegacyRecoveryConversation(row.text(1, budget), row.optionalText(2, budget))
        }
        val newConversations = rows(core, "SELECT id,title,projectId FROM conversations ORDER BY id") { row ->
            row.text(0, budget) to LegacyRecoveryConversation(row.text(1, budget), row.optionalText(2, budget))
        }
        val oldProviders = rows(legacy, "SELECT id,name,${if (hasFormat) "apiFormat" else "NULL"} FROM providers ORDER BY id") { row ->
            row.text(0, budget) to LegacyRecoveryProvider(row.text(1, budget), row.optionalText(2, budget))
        }
        val newProviders = rows(core, "SELECT id,name,apiFormat FROM providers ORDER BY id") { row ->
            row.text(0, budget) to LegacyRecoveryProvider(row.text(1, budget), row.text(2, budget))
        }
        return LegacyRoomRecoveryPreview(version, pair(oldProjects, newProjects), pair(oldConversations, newConversations), pair(oldProviders, newProviders))
    }

    private fun preflight(db: SQLiteConnection, table: String, fields: List<String>, budget: Budget) {
        val lengths = fields.map { "coalesce(length(CAST(\"$it\" AS BLOB)),0)" }
        val total = lengths.joinToString(" + ")
        val max = lengths.joinToString(",", "max(", ")")
        db.prepare("SELECT count(*), coalesce(sum($total),0), coalesce(max($max),0) FROM \"$table\"").use { row ->
            check(row.step()) { "Recovery preview preflight failed" }
            check(row.getLong(0) <= MAX_ROWS && row.getLong(2) <= MAX_TEXT_BYTES) { "Recovery preview exceeds limit" }
            budget.bytes += row.getLong(1)
            check(budget.bytes <= MAX_TOTAL_TEXT_BYTES) { "Recovery preview exceeds size limit" }
        }
    }

    private fun <T> rows(db: SQLiteConnection, sql: String, parse: (SQLiteStatement) -> Pair<String, T>): Map<String, T> =
        db.prepare("$sql LIMIT ${MAX_ROWS + 1}").use { row ->
            buildMap {
                while (row.step()) {
                    check(size < MAX_ROWS) { "Recovery preview exceeds row limit" }
                    val (id, value) = parse(row)
                    check(id.isNotBlank() && !containsKey(id)) { "Invalid recovery record identity" }
                    put(id, value)
                }
            }
        }

    private fun <T> pair(legacy: Map<String, T>, core: Map<String, T>) =
        (legacy.keys + core.keys).sorted().map { id -> LegacyRecoveryPair(id, legacy[id], core[id]) }

    private fun SQLiteStatement.text(index: Int, budget: Budget): String {
        check(getColumnType(index) == SQLITE_DATA_TEXT) { "Invalid recovery text" }
        return getText(index).also {
            val size = it.encodeToByteArray().size
            check(size <= MAX_TEXT_BYTES) { "Recovery text exceeds limit" }
            budget.bytes += size
            check(budget.bytes <= MAX_TOTAL_TEXT_BYTES) { "Recovery preview exceeds size limit" }
        }
    }
    private fun SQLiteStatement.optionalText(index: Int, budget: Budget): String? =
        if (getColumnType(index) == SQLITE_DATA_NULL) null else text(index, budget)
    private fun SQLiteStatement.integer(index: Int): Long {
        check(getColumnType(index) == SQLITE_DATA_INTEGER) { "Invalid recovery timestamp" }
        return getLong(index)
    }
}
