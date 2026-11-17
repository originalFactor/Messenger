/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import android.system.Os
import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** All owners in this disposable fixture are test gates, not Android process-stop proof. */
class AndroidLegacyRecoveryCoordinatorDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: Path
    private val core get() = root.resolve("core.db")
    private val legacy get() = root.resolve("legacy.db")
    private val destination get() = root.resolve("room.db")

    private class StubOwner : RoomStoreOwnership {
        @Volatile var held = false
        override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T {
            check(!held); held = true
            return try { block() } finally { held = false }
        }
    }

    private fun openRoom(path: Path) = Room.databaseBuilder<LocalDatabase>(context = context, name = path.toString())
        .setDriver(BundledSQLiteDriver()).build()
    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step(); Unit }

    @Before fun setup() {
        root = Files.createTempDirectory(context.filesDir.toPath(), "legacy-review-device-")
        Os.chmod(root.toString(), 448)
    }

    @After fun cleanup() {
        if (::root.isInitialized && Files.exists(root)) Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private suspend fun fixture() {
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("core-store-v9.sql")
            .bufferedReader().use { it.readText() }
        BundledSQLiteDriver().open(core.toString()).use { db ->
            schema.split(';').map(String::trim).filter(String::isNotEmpty).forEach { db.exec(it) }
            db.exec("INSERT INTO agents(id,name,systemPrompt,isDefault,createdAt,updatedAt) VALUES ('a','Agent','',1,1,1)")
            db.exec("INSERT INTO providers(id,name,baseUrl,apiKey,apiFormat,createdAt,updatedAt) VALUES ('p','Provider','https://example','SECRET','chat',1,1)")
            db.exec("INSERT INTO conversations(id,title,providerId,agentId,createdAt,updatedAt) VALUES ('c','Chat','p','a',1,1)")
            db.exec("INSERT INTO kv VALUES ('device_id','local-device')")
        }
        BundledSQLiteDriver().open(legacy.toString()).use { db ->
            db.exec("PRAGMA user_version=22")
            db.exec("CREATE TABLE projects(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,workspace TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
            db.exec("CREATE TABLE conversations(id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL,projectId TEXT)")
            db.exec("CREATE TABLE providers(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,apiKey TEXT NOT NULL,apiFormat TEXT NOT NULL)")
            db.exec("INSERT INTO projects VALUES ('old','Old project','/workspace',1,2)")
            db.exec("INSERT INTO conversations VALUES ('c','Chat','old')")
            db.exec("INSERT INTO providers VALUES ('p','Provider','OTHER-SECRET','responses')")
        }
        RoomStoreStartup.withLeasedCoreStore(core, destination, root.resolve("old-assets"), root.resolve("new-assets"), StubOwner(),
            RoomManagedShutdown { }, ::openRoom) { }
    }

    private fun coordinator(owner: RoomStoreOwnership, clock: () -> Long = { 10 }) =
        AndroidLegacyRecoveryCoordinator(core, legacy, destination, root, owner, ::openRoom, clock)

    @Test fun reviewKeepsOwnerHeldUntilExplicitSelectionCommitsAndPreservesSources() = runBlocking {
        fixture()
        // The cooperative Android owner is real; these no-op external worker proofs are test-only.
        val owner = AndroidRoomDirectoryOwner.acquire(context, destination, RoomWritersStoppedProof { }, RoomWritersStoppedProof { })
        val marker = root.resolve(".room.db.room-owner.active")
        val coordinator = coordinator(owner)
        val oldCore = Files.readAllBytes(core); val oldLegacy = Files.readAllBytes(legacy)
        val work = async(Dispatchers.Default) { coordinator.run() }
        val review = withTimeout(10_000) { coordinator.state.first { it is AndroidLegacyRecoveryState.Review } } as AndroidLegacyRecoveryState.Review
        assertTrue(Files.exists(marker))
        assertFalse(review.review.toString().contains("SECRET"))
        val selection = checkNotNull(LegacyRecoveryChoices(review.review).selection(setOf("old"), setOf("c"), setOf("p")))
        assertTrue(coordinator.apply(review.review, selection))
        assertEquals(LegacyRecoveryApplied(1, 1, 1), withTimeout(10_000) { work.await() })
        assertFalse(Files.exists(marker))
        assertTrue(coordinator.state.value is AndroidLegacyRecoveryState.Completed)
        assertFalse(coordinator.apply(review.review, selection))
        val db = openRoom(destination)
        try {
            assertEquals("local-device", db.rows().project("old")?.deviceId)
            assertEquals("old", db.rows().conversation("c")?.projectId)
            assertEquals("responses", db.rows().provider("p")?.apiFormat)
            assertEquals("SECRET", db.rows().provider("p")?.apiKey)
        } finally { db.close() }
        assertArrayEquals(oldCore, Files.readAllBytes(core)); assertArrayEquals(oldLegacy, Files.readAllBytes(legacy))
    }

    @Test fun cancelReviewPreservesDestinationAndDoesNotAcceptAStaleChoice() = runBlocking {
        fixture()
        val owner = StubOwner()
        val coordinator = coordinator(owner)
        val work = async(Dispatchers.Default) { coordinator.run() }
        val review = withTimeout(10_000) { coordinator.state.first { it is AndroidLegacyRecoveryState.Review } } as AndroidLegacyRecoveryState.Review
        assertTrue(owner.held)
        val selection = checkNotNull(LegacyRecoveryChoices(review.review).selection(setOf("old"), setOf("c"), setOf("p")))
        assertTrue(coordinator.cancel())
        assertFalse(coordinator.apply(review.review, selection))
        assertNull(withTimeout(10_000) { work.await() })
        assertEquals(AndroidLegacyRecoveryState.Cancelled, coordinator.state.value)
        assertFalse(owner.held)
        val db = openRoom(destination)
        try {
            assertNull(db.rows().project("old"))
            assertNull(db.rows().conversation("c")?.projectId)
            assertEquals("chat", db.rows().provider("p")?.apiFormat)
        } finally { db.close() }
    }

    @Test fun cancellationDuringApplyNeverReportsACertainRollback() = runBlocking {
        fixture()
        val owner = StubOwner()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val coordinator = coordinator(owner) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); 10 }
        val work = async(Dispatchers.Default) { coordinator.run() }
        try {
            val review = withTimeout(10_000) { coordinator.state.first { it is AndroidLegacyRecoveryState.Review } } as AndroidLegacyRecoveryState.Review
            val selection = checkNotNull(LegacyRecoveryChoices(review.review).selection(setOf("old"), setOf("c"), setOf("p")))
            assertTrue(coordinator.apply(review.review, selection))
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            work.cancel(CancellationException("activity owner stopped"))
            release.countDown()
            work.join()
            assertEquals(AndroidLegacyRecoveryState.Uncertain, coordinator.state.value)
            assertFalse(owner.held)
        } finally { release.countDown(); work.cancel(); work.join() }
    }

    @Test fun cancellingDuringOwnerAcquisitionRetainsMarkerUntilVerifiedRecovery() = runBlocking {
        fixture()
        val entered = CompletableDeferred<Unit>()
        // The Android marker is real; no-op external worker proofs are limited to this test.
        val owner = AndroidRoomDirectoryOwner.acquire(context, destination,
            RoomWritersStoppedProof { entered.complete(Unit); awaitCancellation() }, RoomWritersStoppedProof { })
        val marker = root.resolve(".room.db.room-owner.active")
        val coordinator = coordinator(owner)
        val work = async(Dispatchers.Default) { coordinator.run() }
        withTimeout(10_000) { entered.await() }
        assertTrue(coordinator.cancel())
        withTimeout(10_000) { work.join() }
        assertEquals(AndroidLegacyRecoveryState.Cancelled, coordinator.state.value)
        assertTrue(Files.exists(marker))
        owner.recoverAndRelease(RoomWritersStoppedProof { })
        assertFalse(Files.exists(marker))
    }
}
