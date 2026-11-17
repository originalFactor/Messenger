/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.cloud.CloudImageRehydrator
import cc.ptoe.messenger.data.cloud.RoomAvatarCache
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomCloudStartup
import cc.ptoe.messenger.data.migration.DurableRoomOwnership
import cc.ptoe.messenger.data.migration.RoomDirectoryOwnership
import cc.ptoe.messenger.data.migration.RoomStoreOwnership
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomWritersStoppedProof
import cc.ptoe.messenger.domain.repository.CloudFacade
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomCloudStartupTest {
    private class Ownership : RoomStoreOwnership {
        var held = false
        override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T {
            check(!held)
            held = true
            return try { block() } finally { held = false }
        }
    }
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
    private fun room(path: Path) = Room.databaseBuilder<LocalDatabase>(name = path.toString())
        .setDriver(BundledSQLiteDriver()).build()

    @Test fun freshStartupRejectsExistingCoreBeforePublishingRoom(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-cloud-existing-core")
        val source = dir.resolve("core.db")
        val target = dir.resolve("room.db")
        val ownership = Ownership()
        try {
            Files.writeString(source, "original")
            assertFailsWith<IllegalStateException> {
                RoomCloudStartup.withRoomCloud(source, target, ownership, RoomManagedShutdown { }, ::room, AppPreferences(MemoryPreferences()),
                    RoomAvatarCache(dir.resolve("avatars").toString().toPath()), CloudImageRehydrator(dir.resolve("images"))) { _, _ -> }
            }
            assertFalse(ownership.held)
            assertFalse(Files.exists(target))
            assertEquals("original", Files.readString(source))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun ownershipOutlivesRoomAndCloudOnSuccessAndCancellation(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-cloud-startup")
        val source = dir.resolve("missing-core.db")
        val target = dir.resolve("room.db")
        val ownership = Ownership()
        val preferences = AppPreferences(MemoryPreferences())
        val avatars = RoomAvatarCache(dir.resolve("avatars").toString().toPath())
        val images = CloudImageRehydrator(dir.resolve("images"))
        var db: LocalDatabase? = null
        var cloud: CloudFacade? = null
        try {
            if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) return@runBlocking
            RoomCloudStartup.withRoomCloud(source, target, ownership, RoomManagedShutdown { }, ::room, preferences, avatars, images) { database, facade ->
                assertTrue(ownership.held)
                assertEquals(1, database.rows().defaultAgents().size)
                database.rows().upsertValues(listOf(KeyValueRow("preserve", "value")))
                db = database
                cloud = facade
            }
            assertFalse(ownership.held)
            assertFailsWith<IllegalStateException> { assertNotNull(cloud).pendingLoginChoice() }
            assertFailsWith<IllegalStateException> { assertNotNull(db).rows().getValue("preserve") }
            assertFailsWith<CancellationException> {
                RoomCloudStartup.withRoomCloud(source, target, ownership, RoomManagedShutdown { }, ::room, preferences, avatars, images) { database, facade ->
                    assertTrue(ownership.held)
                    assertEquals("value", database.rows().getValue("preserve")?.value)
                    db = database
                    cloud = facade
                    throw CancellationException("cancelled during use")
                }
            }
            assertFalse(ownership.held)
            assertFailsWith<IllegalStateException> { assertNotNull(cloud).pendingLoginChoice() }
            assertFailsWith<IllegalStateException> { assertNotNull(db).rows().getValue("preserve") }
            assertFalse(Files.exists(source))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun cancellationRequiresRecoveryBeforeRoomCanBeReopened(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-cloud-recovery")
        val source = dir.resolve("missing-core.db")
        val target = dir.resolve("room.db")
        val preferences = AppPreferences(MemoryPreferences())
        val avatars = RoomAvatarCache(dir.resolve("avatars").toString().toPath())
        val images = CloudImageRehydrator(dir.resolve("images"))
        val stopped = RoomWritersStoppedProof { }
        try {
            if (!Files.getFileStore(dir).supportsFileAttributeView("unix")) return@runBlocking
            val owner = DurableRoomOwnership.acquire(target, stopped, stopped)
            assertFailsWith<CancellationException> {
                RoomCloudStartup.withRoomCloud(source, target, owner, RoomManagedShutdown { }, ::room, preferences, avatars, images) { db, _ ->
                    db.rows().upsertValues(listOf(KeyValueRow("retained", "value")))
                    throw CancellationException("interrupted")
                }
            }
            assertFailsWith<IllegalStateException> { DurableRoomOwnership.acquire(target, stopped, stopped) }
            owner.recoverAndRelease(stopped)
            val recovered = DurableRoomOwnership.acquire(target, stopped, stopped)
            RoomCloudStartup.withRoomCloud(source, target, recovered, RoomManagedShutdown { }, ::room, preferences, avatars, images) { db, _ ->
                assertEquals("value", db.rows().getValue("retained")?.value)
            }
            assertFalse(Files.exists(source))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun directoryLeaseRetainsRoomAfterCancellationUntilProvenRecovery(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-cloud-directory")
        val source = dir.resolve("missing-core.db")
        val target = dir.resolve("room.db")
        val preferences = AppPreferences(MemoryPreferences())
        val avatars = RoomAvatarCache(dir.resolve("avatars").toString().toPath())
        val images = CloudImageRehydrator(dir.resolve("images"))
        val stopped = RoomWritersStoppedProof { }
        try {
            if (!Files.getFileStore(dir).supportsFileAttributeView("unix")) return@runBlocking
            val owner = RoomDirectoryOwnership.acquire(target, stopped, stopped)
            assertFailsWith<CancellationException> {
                RoomCloudStartup.withRoomCloud(source, target, owner, RoomManagedShutdown { }, ::room, preferences, avatars, images) { db, _ ->
                    db.rows().upsertValues(listOf(KeyValueRow("retained", "value")))
                    throw CancellationException("interrupted")
                }
            }
            assertFailsWith<Exception> { RoomDirectoryOwnership.acquire(target, stopped, stopped) }
            owner.recoverAndRelease(stopped)
            val recovered = RoomDirectoryOwnership.acquire(target, stopped, stopped)
            RoomCloudStartup.withRoomCloud(source, target, recovered, RoomManagedShutdown { }, ::room, preferences, avatars, images) { db, _ ->
                assertEquals("value", db.rows().getValue("retained")?.value)
            }
            assertFalse(Files.exists(source))
        } finally { dir.toFile().deleteRecursively() }
    }
}
