/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LearningRecordRow
import cc.ptoe.messenger.data.local.room.LearningReviewAttemptRow
import cc.ptoe.messenger.data.local.room.LearningReviewStateRow
import cc.ptoe.messenger.data.local.room.LearningRevisionRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.MessageRow
import cc.ptoe.messenger.data.local.room.ModelRow
import cc.ptoe.messenger.data.local.room.ProjectRow
import cc.ptoe.messenger.data.local.room.ProviderRow
import cc.ptoe.messenger.data.local.room.SyncMetaRow
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomClearAllTest {
    private class Ownership : RoomStoreOwnership, RoomClearAllOwnership {
        var held = false
        override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T = hold(block)
        override suspend fun <T> withStoppedExclusiveOwner(block: suspend () -> T): T = hold(block)
        private suspend fun <T> hold(block: suspend () -> T): T {
            check(!held)
            held = true
            return try { block() } finally { held = false }
        }
    }
    private class External(private val file: Path, private val owner: Ownership) : RoomClearAllExternal {
        var allowed = true
        var failCleanup = false
        var cleanupCalls = 0
        override suspend fun checkReady() { check(owner.held && allowed) { "Unverified owner or files" } }
        override suspend fun clearAndVerify() {
            check(owner.held)
            cleanupCalls++
            if (failCleanup) error("Interrupted file cleanup")
            Files.deleteIfExists(file)
            check(Files.notExists(file))
        }
    }
    private fun openRoom(path: Path) = Room.databaseBuilder<LocalDatabase>(name = path.toString())
        .setDriver(BundledSQLiteDriver()).build()

    private suspend fun populate(db: LocalDatabase) {
        val rows = db.rows()
        val agent = rows.defaultAgents().single().id
        rows.upsertProviders(listOf(ProviderRow("provider", "provider", "https://example.test", "secret", "chat", false, 1, 1)))
        rows.upsertModels(listOf(ModelRow("model", "provider", "id", "model", true, 0, null, null, null, null,
            "text", "text", false, false, false, false, 1, null, null)))
        rows.upsertProjects(listOf(ProjectRow("project", "project", "/tmp", null, 1, 1)))
        rows.upsertConversations(listOf(ConversationRow(id = "conversation", title = "chat", providerId = "provider", agentId = agent,
            projectId = "project", overrideModelId = null, overrideTemperature = null, overrideTopP = null,
            overrideMaxTokens = null, overrideReasoningEffort = null, overrideToolsEnabled = null, overrideToolsConfig = null,
            writable = false, createdAt = 1, updatedAt = 1, lastMessage = null, reasoningFormat = null,
            contextSummary = null, contextSummaryUntil = 0, contextTokens = 0, contextTokensAt = 0)))
        rows.upsertMessages(listOf(MessageRow("message", "conversation", "user", "private", null, 1, "complete", null)))
        rows.upsertLearningRecords(listOf(LearningRecordRow("card", "reviewCard", 1, null, "private", false, 1, "{}")))
        rows.upsertLearningRevisions(listOf(LearningRevisionRow("card", 1, "private")))
        rows.upsertLearningReviewStates(listOf(LearningReviewStateRow("card", "{}")))
        rows.upsertLearningReviewAttempts(listOf(LearningReviewAttemptRow("attempt", "card", 1, "{}")))
        rows.upsertSyncMeta(listOf(SyncMetaRow("account", 2, "[]", "[]")))
        rows.upsertValues(listOf(KeyValueRow("cloud_session", "cookie"), KeyValueRow("artifact:one", "private"),
            KeyValueRow("local_asset:one", "private"), KeyValueRow("control_grant", "secret")))
    }

    @Test fun freshClearRemovesRowsAndOwnedFilesAndSeedsExactlyOneDefault(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-clear-all")
        val source = dir.resolve("missing-core.db")
        val target = dir.resolve("room.db")
        val file = Files.writeString(dir.resolve("owned-test-file"), "private")
        val owner = Ownership()
        try {
            if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) return@runBlocking
            RoomStoreStartup.withRoomStore(source, target, owner, RoomManagedShutdown { }, ::openRoom) { db ->
                populate(db)
                // A separate test gate simulates a platform proof that every user was stopped.
                val resetOwner = Ownership()
                val resetExternal = External(file, resetOwner)
                RoomClearAll(db, resetOwner, resetExternal) { 42 }.clear()
                assertFalse(Files.exists(file))
                assertEquals(1, resetExternal.cleanupCalls)
                val rows = db.rows()
                assertEquals(1, rows.defaultAgents().size)
                assertEquals(1, db.cloudSync().titleCount())
                assertNull(rows.getValue(RoomClearAll.PENDING_KEY))
                assertNull(rows.getValue("cloud_session"))
                assertNull(rows.getValue("artifact:one"))
                assertNull(rows.getValue("local_asset:one"))
                assertEquals("complete", rows.getValue("__messenger_room_fresh_v1")?.value)
                db.useWriterConnection { connection ->
                    for (table in listOf("providers", "models", "projects", "conversations", "messages", "sync_meta",
                        "learning_records", "learning_revisions", "learning_review_states", "learning_review_attempts")) {
                        connection.usePrepared("SELECT COUNT(*) FROM $table") { assertTrue(it.step()); assertEquals(0L, it.getLong(0)) }
                    }
                }
            }
            RoomStoreStartup.withRoomStore(source, target, owner, RoomManagedShutdown { }, ::openRoom) { db ->
                assertEquals(1, db.rows().defaultAgents().size)
                assertNull(db.rows().getValue("cloud_session"))
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun interruptedCleanupBlocksStartupUntilExplicitRecovery(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-clear-recovery")
        val source = dir.resolve("missing-core.db")
        val target = dir.resolve("room.db")
        val file = Files.writeString(dir.resolve("owned-test-file"), "private")
        val owner = Ownership()
        val resetOwner = Ownership()
        val external = External(file, resetOwner)
        try {
            if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) return@runBlocking
            RoomStoreStartup.withRoomStore(source, target, owner, RoomManagedShutdown { }, ::openRoom) { db ->
                populate(db)
                external.failCleanup = true
                assertFailsWith<IllegalStateException> { RoomClearAll(db, resetOwner, external).clear() }
                assertNotNull(db.rows().getValue(RoomClearAll.PENDING_KEY))
                assertEquals(1, db.rows().defaultAgents().size)
                assertTrue(Files.exists(file))
            }
            assertFailsWith<IllegalStateException> {
                RoomStoreStartup.withRoomStore(source, target, owner, RoomManagedShutdown { }, ::openRoom) { error("must not open") }
            }
            external.failCleanup = false
            val recovery = openRoom(target)
            try { RoomClearAll(recovery, resetOwner, external).resume() } finally { recovery.close() }
            assertFalse(Files.exists(file))
            RoomStoreStartup.withRoomStore(source, target, owner, RoomManagedShutdown { }, ::openRoom) { db ->
                assertNull(db.rows().getValue(RoomClearAll.PENDING_KEY))
                assertEquals(1, db.rows().defaultAgents().size)
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun missingProofAndMigratedSourceFailBeforeAnyDataIsRemoved(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-clear-denied")
        val source = dir.resolve("missing-core.db")
        val target = dir.resolve("room.db")
        val file = Files.writeString(dir.resolve("owned-test-file"), "private")
        val owner = Ownership()
        val resetOwner = Ownership()
        val external = External(file, resetOwner)
        try {
            if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) return@runBlocking
            RoomStoreStartup.withRoomStore(source, target, owner, RoomManagedShutdown { }, ::openRoom) { db ->
                populate(db)
                external.allowed = false
                assertFailsWith<IllegalStateException> { RoomClearAll(db, resetOwner, external).clear() }
                assertEquals("cookie", db.rows().getValue("cloud_session")?.value)
                assertTrue(Files.exists(file))
                external.allowed = true
                db.rows().upsertValues(listOf(KeyValueRow(CoreStoreMigration.MARKER_KEY, CoreStoreMigration.MARKER_VALUE)))
                assertFailsWith<IllegalStateException> { RoomClearAll(db, resetOwner, external).clear() }
                assertEquals("cookie", db.rows().getValue("cloud_session")?.value)
                assertTrue(Files.exists(file))
                assertEquals(0, external.cleanupCalls)
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun unrecognizedTablesPreventAnyReset(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-clear-schema")
        val source = dir.resolve("missing-core.db")
        val target = dir.resolve("room.db")
        val file = Files.writeString(dir.resolve("owned-test-file"), "private")
        val owner = Ownership()
        val resetOwner = Ownership()
        val external = External(file, resetOwner)
        try {
            if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) return@runBlocking
            RoomStoreStartup.withRoomStore(source, target, owner, RoomManagedShutdown { }, ::openRoom) { db ->
                populate(db)
                db.useWriterConnection { connection ->
                    connection.usePrepared("CREATE TABLE unexpected_user_data (value TEXT)") { it.step() }
                }
                assertFailsWith<IllegalStateException> { RoomClearAll(db, resetOwner, external).clear() }
                assertEquals("cookie", db.rows().getValue("cloud_session")?.value)
                assertTrue(Files.exists(file))
                assertEquals(0, external.cleanupCalls)
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
