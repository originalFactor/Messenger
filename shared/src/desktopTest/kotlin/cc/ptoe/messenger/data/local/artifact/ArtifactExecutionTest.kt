/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.artifact

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.asset.CoreAssetGate
import cc.ptoe.messenger.data.local.asset.CoreAssetOwner
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.KeyValueRow
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.yield
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactExecutionTest {
    private class Gate : CoreAssetGate {
        private val lock = Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T): T = lock.withLock { block() }
    }
    @Test fun limiterBoundsRatesAcrossSessionsAndRecoversAfterWindow() {
        var now = 0L
        val limiter = ArtifactRequestLimiter(maxGlobal = 2, maxSession = 1, globalPerMinute = 3, sessionPerMinute = 2, clock = { now })
        val first = ArtifactRequestLimiter.Session()
        val second = ArtifactRequestLimiter.Session()
        limiter.acquire(first)
        assertFailsWith<IllegalStateException> { limiter.acquire(first) }
        limiter.acquire(second)
        assertFailsWith<IllegalStateException> { limiter.acquire(ArtifactRequestLimiter.Session()) }
        limiter.release(first)
        limiter.release(second)
        limiter.acquire(first)
        limiter.release(first)
        assertFailsWith<IllegalStateException> { limiter.acquire(first) }
        assertFailsWith<IllegalStateException> { limiter.acquire(second) }
        now += TimeUnit.MINUTES.toNanos(1)
        limiter.acquire(first)
        limiter.release(first)
    }

    @Test fun concurrentRequestsAreBoundedAndCancellationReleasesSlots(): Unit = runBlocking {
        val dir = Files.createTempDirectory("artifact-request-limits")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val assets = CoreAssetService(Files.createDirectory(dir.resolve("assets")).also { if (Files.getFileStore(it).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }, db.assetMetadata(), Gate())
            val artifacts = RoomArtifactService(db, assets)
            val app = artifacts.submit(ArtifactDraft(title = "App", files = mapOf("index.html" to "ok"), requestedDomains = listOf("example.com")), 1)
            artifacts.authorizeExecution(app.id, 1)
            artifacts.grantDomains(app.id, listOf("example.com"))
            val started = Channel<Unit>(Channel.UNLIMITED)
            val transport = ArtifactTransport { _, _, _ -> started.send(Unit); awaitCancellation() }
            val dns = ArtifactDns { listOf(InetAddress.getByName("8.8.8.8")) }
            val limiter = ArtifactRequestLimiter(maxGlobal = 2, maxSession = 1)
            val execution = ArtifactExecutionService(artifacts, assets, dns, transport, limiter)
            val first = execution.open(app.id)
            val second = execution.open(app.id)
            val third = execution.open(app.id)
            val request = ArtifactFetchRequest("https://example.com/")
            val firstCall = async { first.fetch(request) }
            started.receive()
            assertFailsWith<IllegalStateException> { first.fetch(request) }
            val secondCall = async { second.fetch(request) }
            started.receive()
            assertFailsWith<IllegalStateException> { third.fetch(request) }
            first.close()
            assertFailsWith<CancellationException> { firstCall.await() }
            val thirdCall = async { third.fetch(request) }
            started.receive()
            second.close()
            third.close()
            assertFailsWith<CancellationException> { secondCall.await() }
            assertFailsWith<CancellationException> { thirdCall.await() }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
    @Test fun sessionPinsRevisionEnforcesGrantsAndRechecksRevocation(): Unit = runBlocking {
        val dir = Files.createTempDirectory("artifact-execution")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val gate = Gate()
            val assets = CoreAssetService(Files.createDirectory(dir.resolve("assets")).also { if (Files.getFileStore(it).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }, db.assetMetadata(), gate)
            val resource = assets.importFile("owned".toByteArray(), "text/plain", CoreAssetOwner("learning", "a"), 1)
            val artifacts = RoomArtifactService(db, assets)
            val draft = ArtifactDraft(title = "App", files = mapOf("index.html" to "hi"), assets = mapOf("owned.txt" to resource.id), requestedDomains = listOf("example.com"))
            val app = artifacts.submit(draft, 1)
            val calls = AtomicInteger()
            var block = false
            var started = CompletableDeferred<Unit>()
            var cancelled = false
            val dns = ArtifactDns { listOf(InetAddress.getByName("8.8.8.8")) }
            val transport = ArtifactTransport { url, addresses, request ->
                calls.incrementAndGet()
                assertEquals("example.com", url.host)
                assertEquals("8.8.8.8", addresses.single().hostAddress)
                assertEquals("POST", request.method)
                if (block) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled = true }
                }
                ArtifactFetchResponse(200, "ok")
            }
            val execution = ArtifactExecutionService(artifacts, assets, dns, transport)
            assertFailsWith<IllegalStateException> { execution.open(app.id) }
            artifacts.authorizeExecution(app.id, 1)
            val session = execution.open(app.id)
            assertContentEquals("owned".toByteArray(), session.readAsset("owned.txt"))
            assertFailsWith<IllegalStateException> { session.readAsset("other") }
            assertEquals(JsonPrimitive("state"), session.setState(JsonPrimitive("state")))
            val request = ArtifactFetchRequest("https://example.com/api", "POST", "{}")
            assertFailsWith<IllegalArgumentException> { session.fetch(request) }
            artifacts.grantDomains(app.id, listOf("example.com"))
            assertEquals(ArtifactFetchResponse(200, "ok"), session.fetch(request))
            for (bad in listOf("http://example.com", "https://user@example.com", "https://example.com:444", "https://evil.example.com"))
                assertFailsWith<IllegalArgumentException> { session.fetch(request.copy(url = bad)) }
            assertFailsWith<IllegalArgumentException> { session.fetch(request.copy(method = "DELETE")) }
            assertFailsWith<IllegalArgumentException> { session.fetch(request.copy(body = "x".repeat(65537))) }
            assertFailsWith<IllegalArgumentException> { session.fetch(request.copy(contentType = "application/octet-stream")) }
            val unsafe = ArtifactExecutionService(artifacts, assets, ArtifactDns { listOf(InetAddress.getByName("127.0.0.1")) }, transport).open(app.id)
            assertFailsWith<IllegalArgumentException> { unsafe.fetch(request) }
            assertEquals(1, calls.get())
            val dnsStarted = CompletableDeferred<Unit>()
            val blockedDns = ArtifactExecutionService(artifacts, assets, ArtifactDns {
                dnsStarted.complete(Unit)
                awaitCancellation()
            }, transport).open(app.id)
            val waitingForDns = async { blockedDns.fetch(request) }
            dnsStarted.await()
            artifacts.grantDomains(app.id, emptyList())
            assertFailsWith<CancellationException> { waitingForDns.await() }
            assertEquals(1, calls.get())
            blockedDns.close()
            artifacts.grantDomains(app.id, listOf("example.com"))
            block = true
            val pending = async { session.fetch(request) }
            started.await()
            artifacts.grantDomains(app.id, emptyList())
            assertFailsWith<CancellationException> { pending.await() }
            assertTrue(cancelled)
            assertEquals(2, calls.get())
            artifacts.submit(draft.copy(id = app.id, expectedRevision = 1), 2)
            assertFailsWith<IllegalStateException> { session.getState() }
            assertFailsWith<IllegalStateException> { execution.open(app.id) }
            assertFailsWith<IllegalStateException> { artifacts.authorizeExecution(app.id, 1) }
            artifacts.authorizeExecution(app.id, 2)
            val reviewed = execution.open(app.id)
            artifacts.grantDomains(app.id, listOf("example.com"))
            started = CompletableDeferred()
            cancelled = false
            val pendingClose = async { reviewed.fetch(request) }
            started.await()
            reviewed.close()
            assertFailsWith<CancellationException> { pendingClose.await() }
            assertTrue(cancelled)
            assertFailsWith<IllegalStateException> { reviewed.getState() }
            unsafe.close()
            assertFailsWith<IllegalStateException> { unsafe.getState() }

            // A close waits for a state transaction that entered first.
            val stateSession = execution.open(app.id)
            lateinit var writer: kotlinx.coroutines.Deferred<JsonPrimitive>
            lateinit var closer: kotlinx.coroutines.Deferred<Unit>
            gate.withExclusiveAssets {
                writer = async(start = CoroutineStart.UNDISPATCHED) { stateSession.setState(JsonPrimitive("final")) as JsonPrimitive }
                closer = async { stateSession.close() }
                yield()
                assertFalse(closer.isCompleted)
            }
            assertEquals(JsonPrimitive("final"), writer.await())
            closer.await()
            assertFailsWith<IllegalStateException> { stateSession.setState(JsonPrimitive("late")) }
            val imported = artifacts.get(app.id).copy(trustedOnDevice = false)
            db.rows().upsertValues(listOf(KeyValueRow("artifact:${app.id}", Json { encodeDefaults = true }.encodeToString(imported))))
            assertFailsWith<IllegalStateException> { execution.open(app.id) }
            artifacts.authorizeExecution(app.id, 2)
            execution.open(app.id).close()
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
