/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.core

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomShutdownState
import cc.ptoe.messenger.data.migration.RoomStoreOwnership
import cc.ptoe.messenger.data.migration.RoomStoreStartup
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomStoreStartupTest {
    private fun openRoom(path: Path): LocalDatabase =
        Room.databaseBuilder<LocalDatabase>(name = path.toString()).setDriver(BundledSQLiteDriver()).build()
    private class Ownership : RoomStoreOwnership {
        var held = false
        override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T {
            check(!held)
            held = true
            return try { block() } finally { held = false }
        }
    }
    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step(); Unit }
    private fun fixture(source: java.nio.file.Path): SQLiteConnection = BundledSQLiteDriver().open(source.toString()).also { db ->
        checkNotNull(javaClass.getResource("/core-store-v9.sql")).readText().split(';').map(String::trim).filter(String::isNotEmpty).forEach { db.exec(it) }
    }
    @Test fun freshStoreSeedsOneDefaultAndOwnsRoomForEntireUse(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-startup-fresh")
        val source = dir.resolve("core/store.db")
        val target = dir.resolve("room.db")
        val owner = Ownership()
        var leaked: LocalDatabase? = null
        try {
            if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) {
                assertFailsWith<IllegalArgumentException> { RoomStoreStartup.withRoomStore(source, target, owner, RoomManagedShutdown { }, ::openRoom) {} }
                assertFalse(Files.exists(target))
                return@runBlocking
            }
            RoomStoreStartup.withRoomStore(source, target, owner, RoomManagedShutdown { }, ::openRoom) { db ->
                assertTrue(owner.held)
                assertEquals(1, db.rows().defaultAgents().size)
                assertEquals(1, db.cloudSync().titleCount())
                assertTrue(db.rows().defaultAgents().single().toolsEnabled)
                db.rows().upsertValues(listOf(KeyValueRow("test", "preserved")))
                leaked = db
            }
            assertFalse(owner.held)
            assertFailsWith<IllegalStateException> { leaked!!.rows().getValue("test") }
            RoomStoreStartup.withRoomStore(source, target, owner, RoomManagedShutdown { }, ::openRoom) { db ->
                assertEquals("preserved", db.rows().getValue("test")?.value)
                assertEquals(1, db.rows().defaultAgents().size)
            }
            // A later Core database cannot be silently mistaken for the fresh target.
            Files.createDirectories(source.parent)
            fixture(source).use { it.exec("INSERT INTO kv VALUES ('new-core-data','preserve')") }
            assertFailsWith<IllegalStateException> { RoomStoreStartup.withRoomStore(source, target, owner, RoomManagedShutdown { }, ::openRoom) {} }
            assertTrue(Files.exists(source))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun existingCoreStoreRequiresLeasedMigration(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-startup-core")
        val source = dir.resolve("store.db")
        val target = dir.resolve("room.db")
        try {
            fixture(source).use { core ->
                core.exec("PRAGMA journal_mode=WAL")
                core.exec("INSERT INTO kv VALUES ('retained','wal-value')")
                assertFailsWith<IllegalStateException> {
                    RoomStoreStartup.withRoomStore(source, target, Ownership(), RoomManagedShutdown { }, ::openRoom) { }
                }
                assertFalse(Files.exists(target))
                core.prepare("SELECT value FROM kv WHERE key='retained'").use {
                    assertTrue(it.step()); assertEquals("wal-value", it.getText(0))
                }
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun interruptedOrUnrelatedFilesAreNeverOverwritten(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-startup-recovery")
        val source = dir.resolve("missing-core.db")
        val target = dir.resolve("room.db")
        val work = dir.resolve(".room.db.fresh-room-startup")
        try {
            if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) return@runBlocking
            Files.createDirectory(work)
            Files.writeString(work.resolve("unknown.db"), "keep")
            assertFailsWith<IllegalStateException> { RoomStoreStartup.withRoomStore(source, target, Ownership(), RoomManagedShutdown { }, ::openRoom) {} }
            assertEquals("keep", Files.readString(work.resolve("unknown.db")))
            assertFalse(Files.exists(target))
            Files.delete(work.resolve("unknown.db")); Files.delete(work)
            Files.writeString(target, "unrelated")
            assertFailsWith<Exception> { RoomStoreStartup.withRoomStore(source, target, Ownership(), RoomManagedShutdown { }, ::openRoom) {} }
            assertEquals("unrelated", Files.readString(target))
            val denied = object : RoomStoreOwnership {
                override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T = error("Core writer still running")
            }
            assertFailsWith<IllegalStateException> { RoomStoreStartup.withRoomStore(source, target, denied, RoomManagedShutdown { }, ::openRoom) {} }
            assertNotNull(Files.readString(target))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun factoryOpeningAnotherDatabaseCannotPublishTarget(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-startup-factory")
        val source = dir.resolve("missing-core.db")
        val target = dir.resolve("room.db")
        val other = dir.resolve("other.db")
        try {
            if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) return@runBlocking
            assertFailsWith<IllegalStateException> {
                RoomStoreStartup.withRoomStore(source, target, Ownership(), RoomManagedShutdown { }, { openRoom(other) }) {}
            }
            assertFalse(Files.exists(target))
            assertFalse(Files.exists(source))
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun freshStoreRetainsOwnershipAndRoomUntilStopRecovery(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-startup-recover-stop")
        try {
            if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) return@runBlocking
            val owner = Ownership()
            var safe = false
            val shutdown = RoomManagedShutdown { check(safe) { "worker still active" } }
            val entered = CompletableDeferred<LocalDatabase>()
            supervisorScope {
                val use = async {
                    RoomStoreStartup.withRoomStore(dir.resolve("missing.db"), dir.resolve("room.db"), owner, shutdown, ::openRoom) { db ->
                        entered.complete(db)
                        error("host failed")
                    }
                }
                val db = entered.await()
                withTimeout(10_000) { shutdown.state.first { it is RoomShutdownState.RecoveryRequired } }
                assertTrue(owner.held)
                assertFalse(use.isCompleted)
                assertEquals(1, db.rows().defaultAgents().size)
                safe = true
                shutdown.retryStop()
                assertFailsWith<IllegalStateException> { use.await() }
                assertFalse(owner.held)
                assertFailsWith<IllegalStateException> { db.rows().defaultAgents() }
            }
        } finally { dir.toFile().deleteRecursively() }
    }

}
