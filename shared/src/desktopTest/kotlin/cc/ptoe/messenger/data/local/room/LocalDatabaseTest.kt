/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0. See https://www.apache.org/licenses/LICENSE-2.0
 */
package cc.ptoe.messenger.data.local.room

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalDatabaseTest {
    private val tables = listOf("providers", "models", "agents", "projects", "conversations", "messages", "sync_meta", "kv", "learning_records", "learning_revisions", "learning_review_states", "learning_review_attempts")
    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step(); Unit }
    private fun SQLiteConnection.rows(sql: String, columns: Int): Set<List<String?>> = prepare(sql).use { statement ->
        buildSet { while (statement.step()) add((0 until columns).map { if (statement.isNull(it)) null else statement.getText(it) }) }
    }

    @Test fun roomSchemaMatchesCoreV9AndRowsAreObservable() = runBlocking {
        val dir = Files.createTempDirectory("room-schema")
        val path = dir.resolve("local.db")
        try {
            val db = Room.databaseBuilder<LocalDatabase>(name = path.toString())
                .setDriver(BundledSQLiteDriver()).build()
            try {
                val project = ProjectRow("p", "project", "/work", "device", 1, 2)
                db.rows().upsertProjects(listOf(project))
                assertEquals(listOf(project), db.rows().observeProjects().first())
            } finally { db.close() }
            BundledSQLiteDriver().open(":memory:").use { expected ->
                checkNotNull(javaClass.getResource("/core-store-v9.sql")).readText().split(';')
                    .map(String::trim).filter(String::isNotEmpty).forEach { expected.exec(it) }
                expected.exec("CREATE INDEX index_conversations_projectId ON conversations(projectId)")
                BundledSQLiteDriver().open(path.toString()).use { actual ->
                    for (table in tables) {
                        // name, affinity, nullability, default and primary-key position
                        fun columns(db: SQLiteConnection) = db.rows("SELECT name,type,\"notnull\",dflt_value,pk FROM pragma_table_info('$table')", 5)
                        fun foreignKeys(db: SQLiteConnection) = db.rows("SELECT \"table\",\"from\",\"to\",on_update,on_delete FROM pragma_foreign_key_list('$table')", 5)
                        fun indexes(db: SQLiteConnection) = db.rows("SELECT name FROM pragma_index_list('$table') WHERE origin = 'c'", 1)
                        assertEquals(columns(expected), columns(actual), "$table columns")
                        assertEquals(foreignKeys(expected), foreignKeys(actual), "$table foreign keys")
                        assertEquals(indexes(expected), indexes(actual), "$table indexes")
                        for (index in indexes(expected).mapNotNull { it.single() }) {
                            assertEquals(expected.rows("SELECT name FROM pragma_index_info('$index')", 1), actual.rows("SELECT name FROM pragma_index_info('$index')", 1), "$index columns")
                        }
                    }
                }
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
