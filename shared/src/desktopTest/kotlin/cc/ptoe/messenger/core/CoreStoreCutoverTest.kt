/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0.
 */
package cc.ptoe.messenger.core

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.CoreCutoverGate
import cc.ptoe.messenger.data.migration.CoreStoreCutover
import cc.ptoe.messenger.data.migration.CoreStoreMigration
import cc.ptoe.messenger.data.migration.CutoverResult
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreStoreCutoverTest {
    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step(); Unit }
    private fun fixture(path: Path): SQLiteConnection = BundledSQLiteDriver().open(path.toString()).also { db ->
        checkNotNull(javaClass.getResource("/core-store-v9.sql")).readText().split(';')
            .map(String::trim).filter(String::isNotEmpty).forEach { db.exec(it) }
    }
    private fun room(path: Path) = Room.databaseBuilder<LocalDatabase>(name = path.toString())
        .setDriver(BundledSQLiteDriver()).build()
    private suspend fun initialize(path: Path) {
        val db = room(path)
        try { db.rows().upsertValues(emptyList()) } finally { db.close() }
    }
    private class Gate : CoreCutoverGate {
        var held = false
        var entries = 0
        override suspend fun <T> withCoreStopped(block: suspend () -> T): T {
            check(!held)
            held = true
            entries++
            return try { block() } finally { held = false }
        }
    }

    @Test fun cutoverIncludesWalPreservesAssetsAndNeverImportsTwice() = runBlocking {
        val dir = Files.createTempDirectory("core-cutover")
        val source = dir.resolve("store.db")
        val target = dir.resolve("room.db")
        val assets = Files.createDirectory(dir.resolve("assets"))
        Files.writeString(assets.resolve("asset"), "owned")
        val gate = Gate()
        try {
            fixture(source).use { db ->
                db.exec("PRAGMA journal_mode=WAL")
                db.exec("PRAGMA wal_autocheckpoint=0")
                db.exec("INSERT INTO kv VALUES ('cloud_session','cookie')")
                db.exec("INSERT INTO kv VALUES ('local_asset:x','assets/asset')")
                suspend fun init(path: Path) {
                    assertTrue(gate.held)
                    if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(path.parent)))
                    }
                    initialize(path)
                }
                suspend fun verify(path: Path) {
                    assertTrue(gate.held)
                    val database = room(path)
                    try { assertEquals("cookie", database.rows().getValue("cloud_session")?.value) }
                    finally { database.close() }
                }
                assertEquals(CutoverResult.IMPORTED, CoreStoreCutover.migrate(source, target, gate, ::init, ::verify))
                assertEquals(CutoverResult.ALREADY_IMPORTED, CoreStoreCutover.migrate(source, target, gate, ::init, ::verify))
                assertEquals(2, gate.entries)
                db.exec("INSERT INTO kv VALUES ('later-change','must-not-be-lost')")
                assertFailsWith<IllegalStateException> { CoreStoreCutover.migrate(source, target, gate, ::init, ::verify) }
                assertTrue(Files.exists(target))
                db.exec("DELETE FROM kv WHERE key='later-change'")
                assertEquals(CutoverResult.ALREADY_IMPORTED, CoreStoreCutover.migrate(source, target, gate, ::init, ::verify))
                val otherSource = dir.resolve("other-store.db")
                fixture(otherSource).use { other ->
                    other.exec("INSERT INTO kv VALUES ('cloud_session','cookie')")
                    other.exec("INSERT INTO kv VALUES ('local_asset:x','assets/asset')")
                }
                assertFailsWith<IllegalStateException> { CoreStoreCutover.migrate(otherSource, target, gate, ::init, ::verify) }
                if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                    assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
                }
                assertFalse(gate.held)
                assertEquals("owned", Files.readString(assets.resolve("asset")))
                db.prepare("SELECT value FROM kv WHERE key='cloud_session'").use {
                    assertTrue(it.step()); assertEquals("cookie", it.getText(0))
                }
                assertTrue(Files.list(dir).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".room-cutover-") } })
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun directorySyncFailureNeverReportsSuccessfulPublication() = runBlocking {
        val dir = Files.createTempDirectory("core-cutover-sync")
        val source = dir.resolve("store.db")
        val target = dir.resolve("room.db")
        try {
            fixture(source).use { it.exec("INSERT INTO kv VALUES ('secret','preserved')") }
            assertFailsWith<UnsupportedOperationException> {
                CoreStoreCutover.migrateWithDirectorySync(source, target, Gate(), ::initialize, { throw UnsupportedOperationException("unsupported") }) {}
            }
            assertFalse(Files.exists(target))
            var calls = 0
            assertFailsWith<java.io.IOException> {
                CoreStoreCutover.migrateWithDirectorySync(source, target, Gate(), ::initialize, { path ->
                    if (++calls == 2) throw java.io.IOException("injected parent fsync failure")
                    java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
                }) {}
            }
            assertTrue(Files.exists(target))
            assertTrue(Files.exists(source))
            // A retry verifies the same source and fsyncs the parent before reporting completion.
            assertEquals(CutoverResult.ALREADY_IMPORTED, CoreStoreCutover.migrate(source, target, Gate(), ::initialize) {})
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun nonPrivateDestinationParentIsRejectedBeforeCreatingOrOpeningAnything() = runBlocking {
        val dir = Files.createTempDirectory("core-cutover-public")
        val source = dir.resolve("store.db")
        val target = dir.resolve("room.db")
        try {
            if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) return@runBlocking
            Files.writeString(source, "preserve")
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx--x---"))
            var opened = false
            assertFailsWith<IllegalArgumentException> {
                CoreStoreCutover.migrate(source, target, Gate(), { opened = true }) { opened = true }
            }
            assertFalse(opened)
            assertFalse(Files.exists(target))
            assertFalse(Files.exists(dir.resolve(".room.db.core-v9-cutover")))
            assertEquals("preserve", Files.readString(source))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun failureLeavesSourceAndNeverPublishesTarget() = runBlocking {
        val dir = Files.createTempDirectory("core-cutover")
        val source = dir.resolve("store.db")
        val target = dir.resolve("room.db")
        val gate = Gate()
        try {
            fixture(source).use { db -> db.exec("INSERT INTO kv VALUES ('secret','keep')") }
            assertFailsWith<IllegalStateException> {
                CoreStoreCutover.migrate(source, target, gate, ::initialize) { error("verification failed") }
            }
            assertFalse(Files.exists(target))
            assertFalse(gate.held)
            assertEquals(listOf("store.db"), Files.list(dir).use { it.map { p -> p.fileName.toString() }.sorted().toList() })
            BundledSQLiteDriver().open(source.toString()).use { db ->
                db.prepare("SELECT value FROM kv WHERE key='secret'").use { assertTrue(it.step()); assertEquals("keep", it.getText(0)) }
            }
            Files.writeString(target, "unrelated")
            assertFailsWith<Exception> { CoreStoreCutover.migrate(source, target, gate, ::initialize) {} }
            assertEquals("unrelated", Files.readString(target))
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun interruptedWorkAndPublicationRaceFailClosed() = runBlocking {
        val dir = Files.createTempDirectory("core-cutover")
        val source = dir.resolve("store.db")
        val target = dir.resolve("room.db")
        val work = dir.resolve(".room.db.core-v9-cutover")
        try {
            fixture(source).close()
            Files.createDirectory(work)
            Files.writeString(work.resolve("unverified.db"), "preserve")
            assertFailsWith<IllegalStateException> {
                CoreStoreCutover.migrate(source, target, Gate(), ::initialize) {}
            }
            assertEquals("preserve", Files.readString(work.resolve("unverified.db")))
            assertFalse(Files.exists(target))
            Files.delete(work.resolve("unverified.db"))
            Files.delete(work)
            assertFailsWith<Exception> {
                CoreStoreCutover.migrate(source, target, Gate(), ::initialize) {
                    Files.writeString(target, "new unrelated file")
                }
            }
            assertEquals("new unrelated file", Files.readString(target))
            assertFalse(Files.exists(work))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun markerCollisionRollsBackDataAndMarkerTogether() = runBlocking {
        val dir = Files.createTempDirectory("core-cutover")
        val source = dir.resolve("store.db")
        val target = dir.resolve("room.db")
        try {
            fixture(source).use { db ->
                db.exec("INSERT INTO providers(id,name,baseUrl,apiKey,createdAt,updatedAt) VALUES ('p','provider','url','key',1,1)")
                db.exec("INSERT INTO kv VALUES ('__messenger_migration_core_v9_to_room_v1','existing')")
            }
            initialize(target)
            val snapshot = CoreStoreMigration.snapshot(source, dir)
            assertFailsWith<Exception> { CoreStoreMigration.importSnapshot(snapshot, target, markCompleted = true) }
            BundledSQLiteDriver().open(target.toString()).use { db ->
                db.prepare("SELECT COUNT(*) FROM providers").use { assertTrue(it.step()); assertEquals(0L, it.getLong(0)) }
                db.prepare("SELECT COUNT(*) FROM kv").use { assertTrue(it.step()); assertEquals(0L, it.getLong(0)) }
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun symbolicLinksAreRejectedWhenSupported() = runBlocking {
        val dir = Files.createTempDirectory("core-cutover")
        val source = dir.resolve("store.db")
        val target = dir.resolve("room.db")
        val link = dir.resolve("source-link.db")
        try {
            fixture(source).close()
            try { Files.createSymbolicLink(link, source) } catch (_: UnsupportedOperationException) { return@runBlocking }
            catch (_: java.io.IOException) { return@runBlocking }
            catch (_: SecurityException) { return@runBlocking }
            assertFailsWith<IllegalArgumentException> { CoreStoreMigration.snapshot(link, dir) }
            Files.createSymbolicLink(target, source)
            assertFailsWith<IllegalStateException> {
                CoreStoreCutover.migrate(source, target, Gate(), ::initialize) {}
            }
            assertTrue(Files.isSymbolicLink(target))
            assertTrue(Files.isRegularFile(source))
        } finally { dir.toFile().deleteRecursively() }
    }

}
