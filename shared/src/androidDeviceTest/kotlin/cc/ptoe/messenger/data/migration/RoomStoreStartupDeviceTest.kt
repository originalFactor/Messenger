/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import android.system.Os

/** Stub owner is for single-test isolation only, not a production writer gate. */
class RoomStoreStartupDeviceTest {
    private lateinit var dir: Path
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val source get() = dir.resolve("core.db")
    private val target get() = dir.resolve("room.db")
    private val receipt get() = dir.resolve(".room.db.room-publication")

    private class StubOwnership : RoomStoreOwnership {
        var held = false
        override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T {
            check(!held)
            held = true
            return try { block() } finally { held = false }
        }
    }

    private fun openRoom(path: Path): LocalDatabase = Room.databaseBuilder<LocalDatabase>(
        context = context, name = path.toString(),
    ).setDriver(BundledSQLiteDriver()).build()

    @Before fun setUp() {
        dir = Files.createTempDirectory(context.filesDir.toPath(), "room-startup-test-")
        Os.chmod(dir.toString(), 448)
    }

    @After fun tearDown() {
        if (::dir.isInitialized && Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun freshStoreReopensWithDataAndOneDefaultAgent() = runBlocking {
        val owner = StubOwnership()
        var defaultId = ""
        RoomStoreStartup.withRoomStore(source, target, owner, RoomManagedShutdown { }, ::openRoom) { db ->
            assertTrue(owner.held)
            defaultId = db.rows().defaultAgents().single().id
            assertEquals(1, db.cloudSync().titleCount())
            db.rows().upsertValues(listOf(KeyValueRow("test.persisted", "value")))
        }
        assertFalse(owner.held)
        assertTrue(Files.exists(receipt))
        RoomStoreStartup.withRoomStore(source, target, owner, RoomManagedShutdown { }, ::openRoom) { db ->
            assertTrue(owner.held)
            assertEquals(defaultId, db.rows().defaultAgents().single().id)
            assertEquals(1, db.cloudSync().titleCount())
            assertEquals("value", db.rows().getValue("test.persisted")?.value)
        }
        assertFalse(owner.held)
        assertFalse(Files.exists(source))
    }

    @Test fun missingReceiptPreventsReopenAndPreservesExistingTarget() = runBlocking {
        val owner = StubOwnership()
        RoomStoreStartup.withRoomStore(source, target, owner, RoomManagedShutdown { }, ::openRoom) {
            it.rows().upsertValues(listOf(KeyValueRow("test.keep", "original")))
        }
        Files.delete(receipt)
        val before = Files.readAllBytes(target)
        var opened = false
        val failure = runCatching {
            RoomStoreStartup.withRoomStore(source, target, owner, RoomManagedShutdown { }, ::openRoom) { opened = true }
        }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse(opened)
        assertArrayEquals(before, Files.readAllBytes(target))
        assertFalse(Files.exists(receipt))
        assertFalse(owner.held)
    }

    @Test fun unrelatedExistingTargetIsNeverReplaced() = runBlocking {
        val bytes = "unrelated-existing-data".toByteArray()
        Files.write(target, bytes)
        var entered = false
        val owner = StubOwnership()
        val failure = runCatching {
            RoomStoreStartup.withRoomStore(source, target, owner, RoomManagedShutdown { }, ::openRoom) { entered = true }
        }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse(entered)
        assertArrayEquals(bytes, Files.readAllBytes(target))
        assertFalse(Files.exists(receipt))
        assertFalse(owner.held)
    }

    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step(); Unit }

    @Test fun importsV9CoreWithoutRemovingSourceOrAssets() = runBlocking {
        val sql = InstrumentationRegistry.getInstrumentation().context.assets.open("core-store-v9.sql")
            .bufferedReader().use { it.readText() }
        val assetBytes = "owned migration asset".toByteArray()
        val assetId = java.security.MessageDigest.getInstance("SHA-256").digest(assetBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        BundledSQLiteDriver().open(source.toString()).use { db ->
            sql.split(';').map(String::trim).filter(String::isNotEmpty).forEach { db.exec(it) }
            db.exec("INSERT INTO agents(id,name,systemPrompt,isDefault,createdAt,updatedAt) VALUES ('core-default','Default','prompt',1,1,1)")
            db.exec("INSERT INTO kv VALUES ('migration-test','retained')")
            db.prepare("INSERT INTO kv(key, value) VALUES (?, ?)").use { row ->
                row.bindText(1, "local_asset:$assetId")
                row.bindText(2, """{"id":"$assetId","sha256":"$assetId","mimeType":"application/octet-stream","size":${assetBytes.size},"createdAt":1,"references":[]}""")
                row.step()
            }
        }
        Files.createDirectories(dir.resolve("assets"))
        Files.write(dir.resolve("assets").resolve(assetId), assetBytes)
        val asset = Files.createDirectories(dir.resolve("assets")).resolve("keep")
        Files.writeString(asset, "asset-data")
        val owner = StubOwnership()
        RoomStoreStartup.withLeasedCoreStore(source, target, asset.parent, dir.resolve("new-assets"), owner, RoomManagedShutdown { }, ::openRoom) { db ->
            assertTrue(owner.held)
            assertEquals("core-default", db.rows().defaultAgents().single().id)
            assertEquals("retained", db.rows().getValue("migration-test")?.value)
        }
        assertTrue(Files.exists(source))
        assertEquals("asset-data", Files.readString(asset))
        assertArrayEquals(assetBytes, Files.readAllBytes(dir.resolve("assets").resolve(assetId)))
        assertArrayEquals(assetBytes, Files.readAllBytes(dir.resolve("new-assets").resolve(assetId)))
        assertTrue(Files.exists(CoreStorePairedCutover.receiptFor(target)))
        RoomStoreStartup.withLeasedCoreStore(source, target, asset.parent, dir.resolve("new-assets"), owner, RoomManagedShutdown { }, ::openRoom) { db ->
            assertEquals("retained", db.rows().getValue("migration-test")?.value)
        }
    }
}
