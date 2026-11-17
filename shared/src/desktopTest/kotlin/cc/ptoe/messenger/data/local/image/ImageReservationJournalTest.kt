/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.image

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.asset.CoreAssetGate
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ProviderRow
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import com.sun.net.httpserver.HttpServer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.nio.file.attribute.PosixFilePermissions
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageReservationJournalTest {
    private suspend fun seed(db: LocalDatabase, host: String = "https://example.test/v1") {
        db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", host, "key", "chat", false, 1, 1)))
        db.imageJobs().putProfile(ImageProfile("profile", "Profile", "p", "model", enabled = true))
        db.imageJobs().setEnabled(true)
    }

    private fun gate() = object : CoreAssetGate {
        private val mutex = kotlinx.coroutines.sync.Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T): T = mutex.withLock { block() }
    }

    @Test fun successfulPaidRequestRetiresItsDurableReservation(): Unit = runBlocking {
        val encoded = ByteArrayOutputStream().use { output ->
            check(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output))
            Base64.getEncoder().encodeToString(output.toByteArray())
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/images/generations") { exchange ->
            val body = """{"data":[{"b64_json":"$encoded"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val dir = Files.createTempDirectory("image-reservation-success")
        val root = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val http = ImagesHttpClient()
        try {
            seed(db, "http://127.0.0.1:${server.address.port}/v1")
            val assets = CoreAssetService(root, db.assetMetadata(), gate())
            val service = ImageGenerationService(db, assets, http, SafeImageDownloader(), dir,
                ImageGenerationAuthorization { }, { 100L })
            val result = service.run(ImageRequest("success", "profile", "draw"))
            assertEquals(ImageJobStatus.succeeded, result.status)
            assertTrue(ImageReservationJournal(db.imageJobs()).entries().isEmpty())
            assertTrue(Files.list(root).use { files -> files.noneMatch { it.fileName.toString().startsWith(".stage-image-reserve-") } })
        } finally { http.close(); db.close(); server.stop(0); dir.toFile().deleteRecursively() }
    }

    @Test fun imageOwnerStartupRecoversOnlyWithPriorWorkerProof(): Unit = runBlocking {
        val dir = Files.createTempDirectory("image-reservation-owner")
        val root = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val http = ImagesHttpClient()
        try {
            seed(db)
            val admitted = db.imageJobs().admit(ImageRequest("crashed", "profile", "draw"), 10)
            val job = db.imageJobs().markRunning(admitted.job.id, 10)
            val reservation = Files.createFile(root.resolve(".stage-image-reserve-crashed.tmp"))
            Files.write(reservation, ByteArray(1024))
            val journal = ImageReservationJournal(db.imageJobs())
            journal.insert(journal.record(job.id, root, reservation, 10))
            val assets = CoreAssetService(root, db.assetMetadata(), gate())
            val service = ImageGenerationService(db, assets, http, SafeImageDownloader(), dir,
                ImageGenerationAuthorization { }, { 20L })
            var proofs = 0
            val proof = PriorImageWorkersStopped { proofs++ }
            assertFailsWith<IllegalStateException> {
                RoomImageJobOwner.open(db, assets, service, RoomManagedShutdown { }, proof, { 20L })
            }
            assertTrue(Files.exists(reservation, LinkOption.NOFOLLOW_LINKS))
            db.imageJobs().fail(job.id, "Interrupted; no automatic retry.", true, 11)
            val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
            val owner = RoomImageJobOwner.open(db, assets, service, lifecycle, proof, { 20L })
            assertEquals(ImageJobStatus.unknown, db.imageJobs().read(job.id).status)
            assertFalse(Files.exists(reservation, LinkOption.NOFOLLOW_LINKS))
            assertTrue(journal.entries().isEmpty())
            assertEquals(2, proofs)
            lifecycle.stopAndAwait()
        } finally { http.close(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun recoveryRemovesOnlyJournalBoundReservationAndRejectsUnknownOrChangedFiles(): Unit = runBlocking {
        val dir = Files.createTempDirectory("image-reservation")
        val root = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            val admitted = db.imageJobs().admit(ImageRequest("interrupted", "profile", "draw"), 10)
            val job = db.imageJobs().markRunning(admitted.job.id, 10)
            val reservation = Files.createFile(root.resolve(".stage-image-reserve-known.tmp"))
            Files.write(reservation, ByteArray(1024))
            val journal = ImageReservationJournal(db.imageJobs())
            val record = journal.record(job.id, root, reservation, 10)
            journal.insert(record)

            val unknown = Files.createFile(root.resolve(".stage-image-reserve-unknown.tmp"))
            assertFailsWith<IllegalStateException> { journal.reconcile(root, emptySet()) }
            assertTrue(Files.exists(reservation, LinkOption.NOFOLLOW_LINKS))
            Files.delete(unknown)

            Files.write(reservation, ByteArray(2048))
            assertFailsWith<IllegalStateException> { journal.reconcile(root, setOf(job.id)) }
            assertFailsWith<IllegalStateException> { journal.reconcile(root, emptySet()) }
            assertTrue(Files.exists(reservation, LinkOption.NOFOLLOW_LINKS))
            Files.write(reservation, ByteArray(1024))
            val changed = db.imageJobs().value("local_image_reservation:${job.id}")!!
            val forged = Json.decodeFromString<ImageReservationRecord>(changed).copy(identity = "forged")
            db.imageJobs().write(cc.ptoe.messenger.data.local.room.KeyValueRow(
                "local_image_reservation:${job.id}", Json.encodeToString(forged)))
            assertFailsWith<IllegalStateException> { journal.reconcile(root, emptySet()) }
            db.imageJobs().write(cc.ptoe.messenger.data.local.room.KeyValueRow(
                "local_image_reservation:${job.id}", changed))

            db.imageJobs().fail(job.id, "Interrupted; no automatic retry.", true, 11)
            assertEquals(1, journal.reconcile(root, emptySet()))
            assertFalse(Files.exists(reservation, LinkOption.NOFOLLOW_LINKS))
            assertTrue(journal.entries().isEmpty())
            journal.insert(record)
            assertEquals(1, journal.reconcile(root, emptySet()), "file deletion before KV retirement must be idempotent")
            assertTrue(journal.entries().isEmpty())
            assertEquals(0, journal.reconcile(root, emptySet()))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
