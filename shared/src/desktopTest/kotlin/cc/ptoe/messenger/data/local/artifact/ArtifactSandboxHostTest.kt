/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.artifact

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.asset.CoreAssetGate
import cc.ptoe.messenger.data.local.asset.CoreAssetOwner
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactSandboxHostTest {
    private class Gate : CoreAssetGate {
        private val mutex = Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T): T = mutex.withLock { block() }
    }

    @Test fun messagesAreBoundToTheAuthorizedInstanceAndRevision(): Unit = runBlocking {
        val dir = Files.createTempDirectory("artifact-bound-host")
        val root = Files.createDirectory(dir.resolve("assets"))
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val assets = CoreAssetService(root, db.assetMetadata(), Gate())
            val png = ByteArrayOutputStream().use { output ->
                check(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output)); output.toByteArray()
            }
            val image = assets.importFile(png, "image/png", CoreAssetOwner("learning", "owner"), 1)
            val artifacts = RoomArtifactService(db, assets)
            val firstDraft = ArtifactDraft(title = "One", files = mapOf("index.html" to "one"), assets = mapOf("pic.png" to image.id), requestedDomains = listOf("example.com"))
            val first = artifacts.submit(firstDraft, 1)
            val second = artifacts.submit(ArtifactDraft(title = "Two", files = mapOf("index.html" to "two")), 1)
            val dns = ArtifactDns { listOf(InetAddress.getByName("8.8.8.8")) }
            val transport = ArtifactTransport { _, _, _ -> ArtifactFetchResponse(200, "reply") }
            val host = ArtifactSandboxHost(ArtifactExecutionService(artifacts, assets, dns, transport), assets)
            assertFailsWith<IllegalStateException> { host.open(first.id) }
            artifacts.authorizeExecution(first.id, 1); artifacts.authorizeExecution(second.id, 1)
            artifacts.grantDomains(first.id, listOf("example.com"))
            val one = host.open(first.id)
            val two = host.open(second.id)
            try {
                val data = one.sandboxPackage.files.getValue("pic.png").substringAfter(',')
                assertContentEquals(png, java.util.Base64.getDecoder().decode(data))
                assertEquals("one", one.sandboxPackage.files.getValue("index.html"))
                assertEquals("two", two.sandboxPackage.files.getValue("index.html"))
                suspend fun request(binding: ArtifactSandboxBinding, raw: String): kotlinx.serialization.json.JsonObject {
                    var reply: String? = null
                    binding.dispatch(raw) { reply = it }
                    return Json.parseToJsonElement(checkNotNull(reply)).jsonObject
                }
                val write = request(one, """{"id":"1","method":"setState","params":{"state":"first-only"}}""")
                assertEquals("first-only", write.getValue("data").jsonObject.getValue("state").jsonPrimitive.content)
                assertEquals(JsonPrimitive("first-only"), artifacts.get(first.id).state)
                assertEquals(kotlinx.serialization.json.JsonNull, artifacts.get(second.id).state)
                assertFailsWith<IllegalArgumentException> {
                    one.dispatch("""{"id":"2","method":"getState","params":{},"artifactId":"${second.id}"}""") { }
                }
                val fetch = request(one, """{"id":"3","method":"fetch","params":{"url":"https://example.com"}}""")
                assertEquals("reply", fetch.getValue("data").jsonObject.getValue("body").jsonPrimitive.content)
                val denied = request(two, """{"id":"4","method":"fetch","params":{"url":"https://example.com"}}""")
                assertTrue("error" in denied)
                artifacts.submit(firstDraft.copy(id = first.id, expectedRevision = 1), 2)
                val stale = request(one, """{"id":"5","method":"getState","params":{}}""")
                assertTrue("error" in stale)
                assertEquals(kotlinx.serialization.json.JsonNull,
                    request(two, """{"id":"6","method":"getState","params":{}}""").getValue("data"))
            } finally { one.close(); two.close() }
            assertFailsWith<IllegalStateException> { one.dispatch("""{"id":"7","method":"getState","params":{}}""") { } }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun closeCancelsAndWaitsForTheBoundHostsInFlightWork(): Unit = runBlocking {
        val dir = Files.createTempDirectory("artifact-host-stop")
        val root = Files.createDirectory(dir.resolve("assets"))
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val assets = CoreAssetService(root, db.assetMetadata(), Gate())
            val artifacts = RoomArtifactService(db, assets)
            val artifact = artifacts.submit(ArtifactDraft(title = "App", files = mapOf("index.html" to "ok"), requestedDomains = listOf("example.com")), 1)
            artifacts.authorizeExecution(artifact.id, 1); artifacts.grantDomains(artifact.id, listOf("example.com"))
            suspend fun verify(cancelCallerFirst: Boolean) {
                val entered = CompletableDeferred<Unit>(); val stopping = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val transport = ArtifactTransport { _, _, _ ->
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { withContext(NonCancellable) { stopping.complete(Unit); release.await() } }
                }
                val host = ArtifactSandboxHost(ArtifactExecutionService(artifacts, assets, ArtifactDns { listOf(InetAddress.getByName("8.8.8.8")) }, transport), assets)
                val binding = host.open(artifact.id)
                var replied = false
                val call = async { binding.dispatch("""{"id":"1","method":"fetch","params":{"url":"https://example.com"}}""") { replied = true } }
                withTimeout(5_000) { entered.await() }
                if (cancelCallerFirst) {
                    call.cancel()
                    withTimeout(5_000) { stopping.await() }
                }
                binding.stop()
                assertFailsWith<IllegalStateException> { binding.dispatch("""{"id":"later","method":"getState","params":{}}""") { } }
                val close = async { binding.close() }
                withTimeout(5_000) { stopping.await() }
                assertFalse(close.isCompleted)
                release.complete(Unit)
                withTimeout(5_000) { close.await() }
                assertFailsWith<CancellationException> { call.await() }
                assertFalse(replied)
                binding.close()
            }
            verify(cancelCallerFirst = false)
            verify(cancelCallerFirst = true)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
