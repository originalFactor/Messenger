/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.image

import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.local.artifact.RoomArtifactService
import cc.ptoe.messenger.data.local.asset.CoreAssetGate
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.asset.RoomAssetGate
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ProviderRow
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomShutdownState
import cc.ptoe.messenger.data.repository.room.KotlinRoomMediaBridge
import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomImageJobOwnerTest {
    private class Gate : CoreAssetGate {
        private val mutex = Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T): T = mutex.withLock { block() }
    }
    private fun privateDir(path: Path) = Files.createDirectory(path).also {
        if (Files.getFileStore(it).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
    }
    private suspend fun waitForStop(owner: RoomImageJobOwner) = withTimeout(5_000) {
        while (owner.hasActiveJobs()) delay(10)
    }

    @Test fun uiCancellationStopsAModelRequestUsingTheSameService(): Unit = runBlocking {
        val calls = AtomicInteger()
        val uiChecks = AtomicInteger()
        val modelChecks = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/v1/images/generations") { exchange ->
            try { calls.incrementAndGet(); exchange.requestBody.readBytes(); entered.countDown(); release.await(10, TimeUnit.SECONDS) }
            catch (_: Exception) { } finally { exchange.close() }
        }
        server.start()
        val dir = Files.createTempDirectory("image-owner-model-cancel")
        val root = privateDir(dir.resolve("assets"))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val http = ImagesHttpClient()
        try {
            val assets = CoreAssetService(root, db.assetMetadata(), Gate())
            db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "http://127.0.0.1:${server.address.port}/v1", "secret", "chat", false, 1, 1)))
            db.imageJobs().putProfile(ImageProfile("profile", "Images", "p", "model", enabled = true))
            db.imageJobs().setEnabled(true)
            val service = ImageGenerationService(db, assets, http, SafeImageDownloader(), dir,
                ImageGenerationAuthorization { uiChecks.incrementAndGet() }, { 100L })
            val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
            val owner = RoomImageJobOwner.open(db, assets, service, lifecycle, PriorImageWorkersStopped { }, { 100L })
            val request = ImageRequest("model-shared", "profile", "draw")
            val model = async { runCatching { service.runFromTool(request, ImageGenerationAuthorization { modelChecks.incrementAndGet() }) { } } }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val id = checkNotNull(db.imageJobs().findRequest(request)).id
            assertEquals(ImageJobStatus.cancelled, owner.cancel(id).status)
            assertTrue(withTimeout(5_000) { model.await() }.isFailure)
            assertEquals(ImageJobStatus.cancelled, db.imageJobs().read(id).status)
            assertEquals(1, modelChecks.get()); assertEquals(0, uiChecks.get()); assertEquals(1, calls.get())
            lifecycle.stopAndAwait()
        } finally {
            release.countDown(); http.close(); db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun cancellingUiAfterAdmissionDoesNotCancelOrReplayThePaidRequest(): Unit = runBlocking {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val png = ByteArrayOutputStream().use { output ->
            check(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output))
            output.toByteArray()
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/v1/images/generations") { exchange ->
            try {
                calls.incrementAndGet(); exchange.requestBody.readBytes(); entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                val body = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            } catch (_: Exception) { exchange.close() }
        }
        server.start()
        val dir = Files.createTempDirectory("image-owner-ui-detached")
        val root = privateDir(dir.resolve("assets"))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val http = ImagesHttpClient()
        var owner: RoomImageJobOwner? = null
        try {
            val assets = CoreAssetService(root, db.assetMetadata(), Gate())
            db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "http://127.0.0.1:${server.address.port}/v1", "secret", "chat", false, 1, 1)))
            db.imageJobs().putProfile(ImageProfile("profile", "Images", "p", "model", enabled = true))
            db.imageJobs().setEnabled(true)
            val service = ImageGenerationService(db, assets, http, SafeImageDownloader(), dir, ImageGenerationAuthorization {}, { 100L })
            val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
            val imageOwner = RoomImageJobOwner.open(db, assets, service, lifecycle, PriorImageWorkersStopped { }, { 100L })
            owner = imageOwner
            val request = ImageRequest("ui-detached", "profile", "draw")
            val admitted = CompletableDeferred<ImageJob>()
            val ui = launch {
                admitted.complete(imageOwner.submit(request))
                awaitCancellation()
            }
            val job = withTimeout(5_000) { admitted.await() }
            assertEquals(ImageJobStatus.running, job.status)
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            ui.cancelAndJoin()
            assertTrue(imageOwner.hasActiveJobs())
            assertEquals(job.id, imageOwner.submit(request).id)
            assertEquals(1, calls.get())
            release.countDown()
            waitForStop(imageOwner)
            val completed = imageOwner.submit(request)
            assertEquals(job.id, completed.id)
            assertEquals(ImageJobStatus.succeeded, completed.status)
            assertContentEquals(png, assets.read(completed.assetIds.single()))
            assertEquals(1, calls.get())
            lifecycle.stopAndAwait()
        } finally {
            release.countDown()
            withContext(NonCancellable) { owner?.shutdown() }
            http.close(); db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun duplicateUiSubmissionsShareOnePaidRequestAndPreserveUnknownOutcome(): Unit = runBlocking {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/v1/images/generations") { exchange ->
            try {
                calls.incrementAndGet(); exchange.requestBody.readBytes(); entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                val body = "provider unavailable".toByteArray()
                exchange.sendResponseHeaders(503, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            } catch (_: Exception) { exchange.close() }
        }
        server.start()
        val dir = Files.createTempDirectory("image-owner-dedupe")
        val root = privateDir(dir.resolve("assets"))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val http = ImagesHttpClient()
        try {
            val assets = CoreAssetService(root, db.assetMetadata(), Gate())
            db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "http://127.0.0.1:${server.address.port}/v1", "secret", "chat", false, 1, 1)))
            db.imageJobs().putProfile(ImageProfile("profile", "Images", "p", "model", enabled = true))
            db.imageJobs().setEnabled(true)
            val service = ImageGenerationService(db, assets, http, SafeImageDownloader(), dir, ImageGenerationAuthorization {}, { 100L })
            val lifecycle = RoomManagedShutdown { }
            lifecycle.enter()
            val owner = RoomImageJobOwner.open(db, assets, service, lifecycle, PriorImageWorkersStopped { }, { 100L })
            val delegate = CoreBridge::class.java.cast(Proxy.newProxyInstance(CoreBridge::class.java.classLoader,
                arrayOf(CoreBridge::class.java)) { _, _, _ -> error("Unexpected fallback") })
            val bridge = KotlinRoomMediaBridge(delegate, db, assets, RoomArtifactService(db, assets), imageOwner = owner) { 100L }
            val body = """{"requestId":"once","profileId":"profile","prompt":"draw"}"""
            val result = Json.parseToJsonElement(bridge.learningRequest("POST", "/image-generation/jobs", body)).jsonObject
            val id = result.getValue("id").jsonPrimitive.content
            assertEquals("running", result.getValue("status").jsonPrimitive.content)
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertTrue(bridge.hasActiveTasks())
            val duplicate = (1..4).map { async { owner.submit(ImageRequest("once", "profile", "draw")) } }.map { it.await() }
            assertTrue(duplicate.all { it.id == id })
            assertFailsWith<IllegalStateException> { owner.submit(ImageRequest("once", "profile", "different")) }
            db.imageJobs().setEnabled(false)
            assertEquals(id, owner.submit(ImageRequest("once", "profile", "draw")).id)
            release.countDown()
            waitForStop(owner)
            val failed = db.imageJobs().read(id)
            assertEquals(ImageJobStatus.unknown, failed.status)
            assertTrue(failed.error.orEmpty().contains("charges"))
            assertEquals(id, owner.submit(ImageRequest("once", "profile", "draw")).id)
            assertEquals(1, calls.get())
            lifecycle.stopAndAwait()
            assertFailsWith<IllegalStateException> { owner.submit(ImageRequest("next", "profile", "draw")) }
        } finally {
            release.countDown(); http.close(); db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun explicitCancelIsPersistedAndStopsOwnedWorker(): Unit = runBlocking {
        val calls = AtomicInteger(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/v1/images/generations") { exchange ->
            try { calls.incrementAndGet(); exchange.requestBody.readBytes(); entered.countDown(); release.await(10, TimeUnit.SECONDS); exchange.close() }
            catch (_: Exception) { exchange.close() }
        }
        server.start()
        val dir = Files.createTempDirectory("image-owner-cancel")
        val root = privateDir(dir.resolve("assets"))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val http = ImagesHttpClient()
        try {
            val assets = CoreAssetService(root, db.assetMetadata(), Gate())
            db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "http://127.0.0.1:${server.address.port}/v1", "secret", "chat", false, 1, 1)))
            db.imageJobs().putProfile(ImageProfile("profile", "Images", "p", "model", enabled = true)); db.imageJobs().setEnabled(true)
            val service = ImageGenerationService(db, assets, http, SafeImageDownloader(), dir, ImageGenerationAuthorization {}, { 100L })
            val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
            val owner = RoomImageJobOwner.open(db, assets, service, lifecycle, PriorImageWorkersStopped { }, { 100L })
            val job = owner.submit(ImageRequest("cancel", "profile", "draw"))
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertEquals(ImageJobStatus.cancelled, owner.cancel(job.id).status)
            waitForStop(owner)
            assertEquals(ImageJobStatus.cancelled, db.imageJobs().read(job.id).status)
            assertTrue(db.imageJobs().read(job.id).error.orEmpty().contains("charges"))
            assertEquals(1, calls.get())
            lifecycle.stopAndAwait()
        } finally { release.countDown(); http.close(); db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively() }
    }

    @Test fun shutdownWaitsForAdmissionCleanupAndRecoveryRequiresPriorProof(): Unit = runBlocking {
        val dir = Files.createTempDirectory("image-owner-stop")
        val root = privateDir(dir.resolve("assets"))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val http = ImagesHttpClient()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val assets = CoreAssetService(root, db.assetMetadata(), Gate())
            db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "http://127.0.0.1:1/v1", "secret", "chat", false, 1, 1)))
            db.imageJobs().putProfile(ImageProfile("profile", "Images", "p", "model", enabled = true)); db.imageJobs().setEnabled(true)
            val interrupted = db.imageJobs().admit(ImageRequest("interrupted", "profile", "old"), 1).job
            db.imageJobs().markRunning(interrupted.id, 2)
            val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
            val service = ImageGenerationService(db, assets, http, SafeImageDownloader(), dir, ImageGenerationAuthorization {}, { 100L }, {
                entered.countDown(); release.await(10, TimeUnit.SECONDS)
            })
            assertFailsWith<IllegalStateException> {
                RoomImageJobOwner.open(db, assets, service, lifecycle, PriorImageWorkersStopped { error("old worker alive") }, { 100L })
            }
            assertEquals(ImageJobStatus.running, db.imageJobs().read(interrupted.id).status)
            val owner = RoomImageJobOwner.open(db, assets, service, lifecycle, PriorImageWorkersStopped { }, { 100L })
            assertEquals(ImageJobStatus.unknown, db.imageJobs().read(interrupted.id).status)
            val pending = async { owner.submit(ImageRequest("new", "profile", "draw")) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val stop = async { lifecycle.stopAndAwait() }
            delay(100)
            assertFalse(stop.isCompleted)
            release.countDown()
            withTimeout(5_000) { stop.await() }
            assertFailsWith<CancellationException> { pending.await() }
            val stopped = checkNotNull(db.imageJobs().findRequest(ImageRequest("new", "profile", "draw")))
            assertTrue(stopped.status in setOf(ImageJobStatus.failed, ImageJobStatus.unknown))
            assertTrue(stopped.error.orEmpty().contains("No automatic retry"))
        } finally { release.countDown(); http.close(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun failedShutdownCanRecoverAfterAssetGateCloses(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/v1/images/generations") { exchange ->
            try { exchange.requestBody.readBytes(); entered.countDown(); release.await(10, TimeUnit.SECONDS); exchange.close() }
            catch (_: Exception) { exchange.close() }
        }
        server.start()
        val dir = Files.createTempDirectory("image-owner-gate-recovery")
        val root = privateDir(dir.resolve("assets"))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val http = ImagesHttpClient()
        try {
            val gate = RoomAssetGate()
            val assets = CoreAssetService(root, db.assetMetadata(), gate)
            db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "http://127.0.0.1:${server.address.port}/v1", "secret", "chat", false, 1, 1)))
            db.imageJobs().putProfile(ImageProfile("profile", "Images", "p", "model", enabled = true))
            db.imageJobs().setEnabled(true)
            val service = ImageGenerationService(db, assets, http, SafeImageDownloader(), dir, ImageGenerationAuthorization {}, { 100L })
            val lifecycle = RoomManagedShutdown { }
            lifecycle.registerStop { gate.shutdown() }
            lifecycle.enter()
            val owner = RoomImageJobOwner.open(db, assets, service, lifecycle, PriorImageWorkersStopped { }, { 100L })
            val job = owner.submit(ImageRequest("gate-recovery", "profile", "draw"))
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            db.useWriterConnection { connection ->
                connection.usePrepared("CREATE TRIGGER fail_image_update BEFORE UPDATE ON kv WHEN NEW.`key` LIKE 'local_image_job:%' BEGIN SELECT RAISE(ABORT, 'temporary storage failure'); END") { it.step() }
            }
            val stopping = async { lifecycle.stopAndAwait() }
            withTimeout(5_000) { lifecycle.state.first { it is RoomShutdownState.RecoveryRequired } }
            assertFailsWith<IllegalStateException> { gate.withExclusiveAssets { } }
            assertFailsWith<IllegalStateException> { owner.recoverAfterStop(PriorImageWorkersStopped { error("worker proof missing") }) }
            db.useWriterConnection { connection -> connection.usePrepared("DROP TRIGGER fail_image_update") { it.step() } }
            owner.recoverAfterStop(PriorImageWorkersStopped { })
            lifecycle.retryStop()
            withTimeout(5_000) { stopping.await() }
            assertEquals(ImageJobStatus.unknown, db.imageJobs().read(job.id).status)
            assertFailsWith<IllegalStateException> { gate.withExclusiveAssets { } }
        } finally {
            release.countDown(); http.close(); db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively()
        }
    }
}
