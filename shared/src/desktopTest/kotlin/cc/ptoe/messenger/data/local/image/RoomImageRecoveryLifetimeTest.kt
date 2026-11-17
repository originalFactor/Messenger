/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.image

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.asset.RoomAssetGate
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ProviderRow
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomShutdownState
import cc.ptoe.messenger.data.migration.RoomStoreOwnership
import cc.ptoe.messenger.data.migration.RoomStoreStartup
import cc.ptoe.messenger.data.repository.room.IsolatedImageRecovery
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomImageRecoveryLifetimeTest {
    private class Ownership : RoomStoreOwnership {
        @Volatile var held = false
        override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T {
            check(!held); held = true
            return try { block() } finally { held = false }
        }
    }

    private fun room(path: Path) = Room.databaseBuilder<LocalDatabase>(name = path.toString())
        .setDriver(BundledSQLiteDriver()).build()

    @Test fun recoveryBlocksRetryAndRejectsEveryEntryAfterRoomOwnerReleases(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-image-recovery-owner")
        val assetsRoot = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val source = dir.resolve("missing-core.db")
        val target = dir.resolve("room.db")
        val owner = Ownership()
        val allowRelease = AtomicBoolean(false)
        val shutdown = RoomManagedShutdown { check(allowRelease.get()) { "External workers may still be alive" } }
        val recovery = IsolatedImageRecovery()
        val ready = CompletableDeferred<Triple<RoomImageJobOwner, LocalDatabase, String>>()
        val proofEntered = CompletableDeferred<Unit>()
        val allowProof = CompletableDeferred<Unit>()
        try {
            val startup = async {
                RoomStoreStartup.withRoomStore(source, target, owner, shutdown, ::room) { db ->
                    val gate = RoomAssetGate()
                    shutdown.registerStop { gate.shutdown() }
                    val http = ImagesHttpClient()
                    shutdown.registerStop { http.close() }
                    val assets = CoreAssetService(assetsRoot, db.assetMetadata(), gate)
                    db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "https://example.test/v1", "key", "chat", false, 1, 1)))
                    db.imageJobs().putProfile(ImageProfile("profile", "Profile", "p", "model", enabled = true))
                    db.imageJobs().setEnabled(true)
                    val service = ImageGenerationService(db, assets, http, SafeImageDownloader(), dir, ImageGenerationAuthorization { }, { 100L })
                    val images = RoomImageJobOwner.open(db, assets, service, shutdown, PriorImageWorkersStopped { }, { 100L }, recovery::register)
                    val job = db.imageJobs().admit(ImageRequest("interrupted", "profile", "draw"), 100L).job
                    ready.complete(Triple(images, db, job.id))
                }
            }
            val (images, db, id) = withTimeout(5_000) { ready.await() }
            withTimeout(5_000) { shutdown.state.first { it is RoomShutdownState.RecoveryRequired } }
            assertTrue(owner.held)
            val recovering = async(start = CoroutineStart.UNDISPATCHED) {
                recovery.recoverAfterStop(PriorImageWorkersStopped { proofEntered.complete(Unit); allowProof.await() })
            }
            withTimeout(5_000) { proofEntered.await() }
            allowRelease.set(true)
            val retrying = async(start = CoroutineStart.UNDISPATCHED) { shutdown.retryStop() }
            assertFalse(retrying.isCompleted)
            assertFalse(startup.isCompleted)
            assertTrue(owner.held)
            assertEquals(ImageJobStatus.queued, db.imageJobs().read(id).status)
            allowProof.complete(Unit)
            withTimeout(5_000) { recovering.await(); retrying.await(); startup.await() }
            assertFalse(owner.held)
            val proofCalls = AtomicInteger()
            assertFailsWith<IllegalStateException> { images.recoverAfterStop(PriorImageWorkersStopped { proofCalls.incrementAndGet() }) }
            assertFailsWith<IllegalStateException> { recovery.recoverAfterStop(PriorImageWorkersStopped { proofCalls.incrementAndGet() }) }
            assertEquals(0, proofCalls.get())
            val reopened = room(target)
            try { assertEquals(ImageJobStatus.unknown, reopened.imageJobs().read(id).status) } finally { reopened.close() }
        } finally { allowProof.complete(Unit); dir.toFile().deleteRecursively() }
    }
}
