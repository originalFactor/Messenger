/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.image

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.asset.CoreAssetGate
import cc.ptoe.messenger.data.local.asset.CoreAssetMetadata
import cc.ptoe.messenger.data.local.asset.CoreAssetOwner
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ProviderRow
import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageGenerationServiceTest {
    private class Gate : CoreAssetGate {
        private val lock = Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T): T = lock.withLock { block() }
    }
    private fun png(): ByteArray = ByteArrayOutputStream().use { stream ->
        check(ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", stream))
        stream.toByteArray()
    }
    @Test fun successPersistsJobAssetsAndSanitizedProvenanceAtomically(): Unit = runBlocking {
        val calls = AtomicInteger()
        val encoded = Base64.getEncoder().encodeToString(png())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/images/generations") { exchange ->
            calls.incrementAndGet()
            check(exchange.requestMethod == "POST")
            check(exchange.requestHeaders.getFirst("Authorization") == "Bearer test-key")
            val response = """{"data":[{"b64_json":"$encoded","revised_prompt":"revised"}],"usage":{"images":1,"secret":"do-not-store"}}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong()); exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val dir = Files.createTempDirectory("images-service")
        val root = Files.createDirectory(dir.resolve("assets"))
        Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val http = ImagesHttpClient()
        try {
            db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "http://127.0.0.1:${server.address.port}/v1", "test-key", "chat", false, 1, 1)))
            db.imageJobs().putProfile(ImageProfile("profile", "Profile", "p", "image-model", enabled = true))
            db.imageJobs().setEnabled(true)
            val service = ImageGenerationService(db, CoreAssetService(root, db.assetMetadata(), Gate()), http, SafeImageDownloader(), dir, ImageGenerationAuthorization {}, { 123L })
            val request = ImageRequest("same-request", "profile", "draw a tree")
            val result = service.run(request)
            assertEquals(ImageJobStatus.succeeded, result.status)
            assertEquals(1, calls.get())
            val id = result.assetIds.single()
            val metadata = Json.decodeFromString<CoreAssetMetadata>(db.assetMetadata().metadata("local_asset:$id")!!)
            assertTrue(CoreAssetOwner("imageGenerationJob", result.id) in metadata.references)
            assertEquals(2L, metadata.width); assertEquals(3L, metadata.height)
            assertTrue(Files.isRegularFile(root.resolve(id)))
            val provenance = db.imageJobs().value("provenance:$id")!!
            assertFalse(provenance.contains("do-not-store")); assertTrue(provenance.contains("revised"))
            assertEquals(result, service.run(request))
            assertEquals(1, calls.get())
            assertFailsWith<IllegalStateException> { service.run(request.copy(prompt = "different")) }
            assertEquals(1, calls.get())
        } finally { http.close(); db.close(); server.stop(0); dir.toFile().deleteRecursively() }
    }

    @Test fun httpFailureAndMalformedSuccessNeverRetry(): Unit = runBlocking {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/images/generations") { exchange ->
            val call = calls.incrementAndGet()
            if (call == 1) {
                exchange.responseHeaders.add("Retry-After", "0")
                val body = "provider unavailable".toByteArray()
                exchange.sendResponseHeaders(503, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            } else if (call == 2) {
                val body = "not-json".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            } else {
                // Valid provider response; local image validation fails after the paid call.
                val body = """{"data":[{"b64_json":"AAAA"}]}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
        val dir = Files.createTempDirectory("images-service")
        val root = Files.createDirectory(dir.resolve("assets"))
        Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val http = ImagesHttpClient()
        try {
            db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "http://127.0.0.1:${server.address.port}/v1", "test-key", "chat", false, 1, 1)))
            db.imageJobs().putProfile(ImageProfile("profile", "Profile", "p", "model", enabled = true))
            db.imageJobs().setEnabled(true)
            val service = ImageGenerationService(db, CoreAssetService(root, db.assetMetadata(), Gate()), http, SafeImageDownloader(), dir, ImageGenerationAuthorization {}, { 1L })
            val failed = ImageRequest("http-error", "profile", "draw")
            val serverError = service.run(failed)
            assertEquals(ImageJobStatus.unknown, serverError.status)
            assertTrue(serverError.error!!.contains("charges may have occurred"))
            assertEquals(1, calls.get())
            assertEquals(ImageJobStatus.unknown, service.run(failed).status)
            assertEquals(1, calls.get())
            val unknown = ImageRequest("bad-response", "profile", "draw")
            val ambiguous = service.run(unknown)
            assertEquals(ImageJobStatus.unknown, ambiguous.status)
            assertTrue(ambiguous.error!!.contains("charges may have occurred"))
            assertTrue(ambiguous.error!!.contains("No automatic retry"))
            assertEquals(ImageJobStatus.unknown, service.run(unknown).status)
            assertEquals(2, calls.get())
            val badImage = ImageRequest("bad-image", "profile", "draw")
            val localFailure = service.run(badImage)
            assertEquals(ImageJobStatus.failed, localFailure.status)
            assertTrue(localFailure.error!!.contains("responded successfully"))
            assertTrue(localFailure.error!!.contains("charges may have occurred"))
            assertTrue(localFailure.error!!.contains("No automatic retry"))
            assertEquals(localFailure, service.run(badImage))
            assertEquals(3, calls.get())
            assertTrue(Files.list(root).use { it.findAny().isEmpty })
            val orphan = root.resolve(".stage-existing.tmp")
            java.io.RandomAccessFile(orphan.toFile(), "rw").use { it.setLength(450L * 1024 * 1024) }
            val quotaRequest = ImageRequest("quota", "profile", "draw")
            assertFailsWith<IllegalArgumentException> { service.run(quotaRequest) }
            assertEquals(3, calls.get())
            Files.delete(orphan)
            assertEquals(ImageJobStatus.failed, service.run(quotaRequest).status)
            assertEquals(4, calls.get())
            val failedSync = ImageGenerationService(db, CoreAssetService(root, db.assetMetadata(), Gate()), http, SafeImageDownloader(), dir,
                ImageGenerationAuthorization {}, { 1L }, { error("injected directory sync failure") })
            val syncRequest = ImageRequest("sync-failure", "profile", "draw")
            assertFailsWith<IllegalStateException> { failedSync.run(syncRequest) }
            assertEquals(ImageJobStatus.failed, service.run(syncRequest).status)
            assertEquals(4, calls.get())
            lateinit var task: Job
            val cancelled = ImageGenerationService(db, CoreAssetService(root, db.assetMetadata(), Gate()), http, SafeImageDownloader(), dir,
                ImageGenerationAuthorization {}, { 1L }, { task.cancel() })
            val cancelledRequest = ImageRequest("cancel-after-commit", "profile", "draw")
            task = launch(start = CoroutineStart.LAZY) { cancelled.run(cancelledRequest) }
            task.start()
            task.join()
            val interrupted = service.run(cancelledRequest)
            assertEquals(ImageJobStatus.unknown, interrupted.status)
            assertTrue(interrupted.error!!.contains("charges may have occurred"))
            assertEquals(4, calls.get())
            assertTrue(Files.list(root).use { it.findAny().isEmpty })
        } finally { http.close(); db.close(); server.stop(0); dir.toFile().deleteRecursively() }
    }

    @Test fun privateAndDocumentationAddressesAreRejected() {
        for (ip in listOf("127.0.0.1", "10.1.2.3", "169.254.1.1", "100.64.1.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "::1", "2001:db8::1")) {
            assertFalse(isPublicAddress(InetAddress.getByName(ip)), ip)
        }
        assertTrue(isPublicAddress(InetAddress.getByName("8.8.8.8")))
    }
}
