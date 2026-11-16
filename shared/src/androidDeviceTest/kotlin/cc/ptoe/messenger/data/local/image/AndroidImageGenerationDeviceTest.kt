/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.image

import android.graphics.Bitmap
import android.system.Os
import android.util.Base64
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import cc.ptoe.messenger.data.local.asset.CoreAssetGate
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ProviderRow
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AndroidImageGenerationDeviceTest {
    private lateinit var dir: Path
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Gate : CoreAssetGate {
        private val lock = Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T) = lock.withLock { block() }
    }

    @Before fun setUp() { dir = Files.createTempDirectory(context.filesDir.toPath(), "image-device-"); Os.chmod(dir.toString(), 448) }
    @After fun tearDown() {
        if (::dir.isInitialized && Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test fun generatesAndPersistsOwnedImageInPrivateAndroidStorage() = runBlocking {
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        val encoded = ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP) }
        bitmap.recycle()
        runImageServer(200, """{"data":[{"b64_json":"$encoded"}]}""") { url, requests ->
            val root = Files.createDirectory(dir.resolve("assets")); Os.chmod(root.toString(), 448)
            val db = Room.databaseBuilder<LocalDatabase>(context, dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
            val http = ImagesHttpClient()
            try {
                db.rows().upsertProviders(listOf(ProviderRow("p", "provider", url, "test-key", "chat", false, 1, 1)))
                db.imageJobs().putProfile(ImageProfile("profile", "Profile", "p", "image-model", enabled = true)); db.imageJobs().setEnabled(true)
                val assets = CoreAssetService(root, db.assetMetadata(), Gate())
                val service = ImageGenerationService(db, assets, http, SafeImageDownloader(), dir, ImageGenerationAuthorization {}, { 1000 })
                val job = withTimeout(20_000) { service.run(ImageRequest("device-request", "profile", "draw")) }
                assertEquals(ImageJobStatus.succeeded, job.status)
                assertEquals(1, requests.get())
                assertTrue(assets.read(job.assetIds.single()).isNotEmpty())
            } finally { http.close(); db.close() }
        }
    }

    @Test fun providerFailureIsNotRetried() = runBlocking {
        runImageServer(503, "unavailable") { url, requests ->
            val root = Files.createDirectory(dir.resolve("assets")); Os.chmod(root.toString(), 448)
            val db = Room.databaseBuilder<LocalDatabase>(context, dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
            val http = ImagesHttpClient()
            try {
                db.rows().upsertProviders(listOf(ProviderRow("p", "provider", url, "test-key", "chat", false, 1, 1)))
                db.imageJobs().putProfile(ImageProfile("profile", "Profile", "p", "image-model", enabled = true)); db.imageJobs().setEnabled(true)
                val service = ImageGenerationService(db, CoreAssetService(root, db.assetMetadata(), Gate()), http, SafeImageDownloader(), dir, ImageGenerationAuthorization {}, { 1000 })
                val job = withTimeout(20_000) { service.run(ImageRequest("device-request", "profile", "draw")) }
                assertEquals(ImageJobStatus.unknown, job.status)
                assertEquals(1, requests.get())
            } finally { http.close(); db.close() }
        }
    }

    private suspend fun runImageServer(status: Int, body: String, action: suspend (String, AtomicInteger) -> Unit) {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val worker = thread(isDaemon = true, name = "local-images-test") {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream()
                    val headers = ByteArrayOutputStream()
                    var last = ""
                    while (!last.endsWith("\r\n\r\n") && headers.size() < 32768) {
                        val byte = input.read(); if (byte < 0) break
                        headers.write(byte); last = (last + byte.toChar()).takeLast(4)
                    }
                    val text = headers.toString("UTF-8")
                    val length = Regex("(?im)^content-length:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
                    repeat(length) { if (input.read() < 0) error("Short request") }
                    requests.incrementAndGet()
                    val bytes = body.toByteArray()
                    val response = "HTTP/1.1 $status ${if (status == 200) "OK" else "Service Unavailable"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nRetry-After: 0\r\nConnection: close\r\n\r\n".toByteArray()
                    socket.getOutputStream().apply { write(response); write(bytes); flush() }
                }
            } catch (_: java.io.IOException) { }
        }
        try { action("http://127.0.0.1:${server.localPort}/v1", requests) }
        finally { server.close(); worker.join(5000) }
    }
}
