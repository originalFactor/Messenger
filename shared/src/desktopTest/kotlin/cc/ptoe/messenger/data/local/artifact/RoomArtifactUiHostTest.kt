/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.artifact

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.asset.RoomAssetGate
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.presentation.platform.ArtifactViewCallbacks
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomArtifactUiHostTest {
    private class Fixture(val path: Path) : AutoCloseable {
        val root = Files.createDirectory(path.resolve("assets")).also {
            if (Files.getFileStore(it).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
        }
        val db = Room.databaseBuilder<LocalDatabase>(name = path.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val gate = RoomAssetGate()
        val assets = CoreAssetService(root, db.assetMetadata(), gate)
        val artifacts = RoomArtifactService(db, assets)
        val shutdown = RoomManagedShutdown { }
        override fun close() { db.close(); path.toFile().deleteRecursively() }
    }

    @Test fun explicitConsentAndRevisionAreRecheckedBeforeMutations(): Unit = runBlocking {
        val fixture = Fixture(Files.createTempDirectory("artifact-ui-consent"))
        try {
            val draft = ArtifactDraft(title = "App", files = mapOf("index.html" to "hello"), requestedDomains = listOf("example.com"))
            val artifact = fixture.artifacts.submit(draft, 1)
            var changeDuringConsent = false
            val consent = object : ArtifactUiConsent {
                override suspend fun authorizeRevision(id: String, revision: Long) {
                    if (changeDuringConsent) fixture.artifacts.submit(draft.copy(id = id, expectedRevision = revision), 2)
                }
                override suspend fun grantDomains(id: String, revision: Long, domains: List<String>) {
                    if (changeDuringConsent) fixture.artifacts.submit(draft.copy(id = id, expectedRevision = revision), 3)
                }
            }
            val sandbox = ArtifactSandboxHost(ArtifactExecutionService(fixture.artifacts, fixture.assets), fixture.assets)
            val host = RoomArtifactUiHost.attach(fixture.artifacts, sandbox, consent, fixture.shutdown)
            fixture.shutdown.enter()
            assertFailsWith<IllegalStateException> { host.open(artifact.id, 1) }
            changeDuringConsent = true
            assertFailsWith<IllegalStateException> { host.authorizeRevision(artifact.id, 1) }
            assertFailsWith<IllegalStateException> { host.open(artifact.id, 2) }
            changeDuringConsent = false
            host.authorizeRevision(artifact.id, 2)
            host.grantDomains(artifact.id, 2, listOf("example.com"))
            val previousGrant = host.open(artifact.id, 2)
            host.grantDomains(artifact.id, 2, emptyList())
            assertFalse(previousGrant.active.value)
            val bound = host.restart(previousGrant)
            assertTrue(bound.active.value)
            changeDuringConsent = true
            assertFailsWith<IllegalStateException> { host.grantDomains(artifact.id, 2, listOf("example.com")) }
            assertFalse(bound.active.value)
            assertFailsWith<IllegalStateException> { host.isRevisionAuthorized(artifact.id, 2) }
            assertFailsWith<IllegalStateException> { host.restart(bound) }
            assertTrue(fixture.artifacts.get(artifact.id).approvedDomains.isEmpty())
            fixture.shutdown.stopAndAwait()
            assertFailsWith<IllegalStateException> { host.open(artifact.id, 3) }
        } finally { fixture.close() }
    }

    @Test fun restartWaitsForOldBindingAndUsesFreshIdentityWithIdenticalPackage(): Unit = runBlocking {
        val fixture = Fixture(Files.createTempDirectory("artifact-ui-restart"))
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val artifact = fixture.artifacts.submit(ArtifactDraft(title = "App", files = mapOf("index.html" to "same"),
                requestedDomains = listOf("example.com")), 1)
            val consent = object : ArtifactUiConsent {
                override suspend fun authorizeRevision(id: String, revision: Long) = Unit
                override suspend fun grantDomains(id: String, revision: Long, domains: List<String>) = Unit
            }
            val transport = ArtifactTransport { _, _, _ ->
                entered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
            }
            val service = ArtifactExecutionService(fixture.artifacts, fixture.assets,
                ArtifactDns { listOf(InetAddress.getByName("8.8.8.8")) }, transport)
            val host = RoomArtifactUiHost.attach(fixture.artifacts, ArtifactSandboxHost(service, fixture.assets), consent, fixture.shutdown)
            fixture.shutdown.enter()
            host.authorizeRevision(artifact.id, 1)
            host.grantDomains(artifact.id, 1, listOf("example.com"))
            val old = host.open(artifact.id, 1)
            var oldReply = false
            old.onMessage("""{"id":"one","method":"fetch","params":{"url":"https://example.com"}}""") { oldReply = true }
            withTimeout(5_000) { entered.await() }
            val restarted = async { host.restart(old) }
            withTimeout(5_000) { cleaning.await() }
            assertFalse(old.active.value)
            delay(50)
            assertFalse(restarted.isCompleted)
            old.onMessage("""{"id":"late","method":"getState","params":{}}""") { oldReply = true }
            release.complete(Unit)
            val fresh = withTimeout(5_000) { restarted.await() }
            assertTrue(fresh.active.value)
            assertNotSame(old.identity, fresh.identity)
            assertEquals(old.files, fresh.files)
            assertFalse(oldReply)
            val reply = CompletableDeferred<String>()
            fresh.onMessage("""{"id":"two","method":"getState","params":{}}""") { reply.complete(it) }
            assertEquals("two", Json.parseToJsonElement(withTimeout(5_000) { reply.await() }).jsonObject.getValue("id").jsonPrimitive.content)
            assertFailsWith<IllegalStateException> { host.restart(old) }
            fixture.shutdown.stopAndAwait()
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test fun shutdownJoinsAdmittedConsentBeforeOwnerCloses(): Unit = runBlocking {
        val fixture = Fixture(Files.createTempDirectory("artifact-ui-shutdown"))
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val artifact = fixture.artifacts.submit(ArtifactDraft(title = "App", files = mapOf("index.html" to "ok")), 1)
            val consent = object : ArtifactUiConsent {
                override suspend fun authorizeRevision(id: String, revision: Long) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
                }
                override suspend fun grantDomains(id: String, revision: Long, domains: List<String>) = Unit
            }
            val host = RoomArtifactUiHost.attach(fixture.artifacts,
                ArtifactSandboxHost(ArtifactExecutionService(fixture.artifacts, fixture.assets), fixture.assets), consent, fixture.shutdown)
            fixture.shutdown.enter()
            val call = async { host.authorizeRevision(artifact.id, 1) }
            withTimeout(5_000) { entered.await() }
            val stop = async { fixture.shutdown.stopAndAwait() }
            withTimeout(5_000) { cleaning.await() }
            assertFalse(stop.isCompleted)
            release.complete(Unit)
            withTimeout(5_000) { stop.await() }
            assertFailsWith<CancellationException> { call.await() }
            assertFailsWith<IllegalStateException> { host.open(artifact.id, 1) }
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test fun malformedRequestsCannotAmplifyOrForgeErrorReplies(): Unit = runBlocking {
        val fixture = Fixture(Files.createTempDirectory("artifact-ui-invalid-message"))
        try {
            val artifact = fixture.artifacts.submit(ArtifactDraft(title = "App", files = mapOf("index.html" to "ok")), 1)
            val consent = object : ArtifactUiConsent {
                override suspend fun authorizeRevision(id: String, revision: Long) = Unit
                override suspend fun grantDomains(id: String, revision: Long, domains: List<String>) = Unit
            }
            val host = RoomArtifactUiHost.attach(fixture.artifacts,
                ArtifactSandboxHost(ArtifactExecutionService(fixture.artifacts, fixture.assets), fixture.assets), consent, fixture.shutdown)
            fixture.shutdown.enter()
            host.authorizeRevision(artifact.id, 1)
            val binding = host.open(artifact.id, 1)
            val replies = Channel<String>(Channel.UNLIMITED)
            binding.onMessage("""{"id":"${"x".repeat(129)}","method":"getState","params":{}}""") { replies.trySend(it) }
            binding.onMessage("""{"id":123,"method":"getState","params":{}}""") { replies.trySend(it) }
            binding.onMessage("""{"id":"good","method":"unsupported","params":{}}""") { replies.trySend(it) }
            val answer = Json.parseToJsonElement(withTimeout(5_000) { replies.receive() }).jsonObject
            assertEquals("good", answer.getValue("id").jsonPrimitive.content)
            assertTrue("error" in answer)
            assertNull(withTimeoutOrNull(250) { replies.receive() })
            fixture.shutdown.stopAndAwait()
        } finally { fixture.close() }
    }

    @Test fun concurrentDispatchAndReplyDoNotInvertCallbackAndBindingLocks(): Unit = runBlocking {
        val fixture = Fixture(Files.createTempDirectory("artifact-callback-lock-order"))
        try {
            val artifact = fixture.artifacts.submit(ArtifactDraft(title = "App", files = mapOf("index.html" to "ok")), 1)
            val consent = object : ArtifactUiConsent {
                override suspend fun authorizeRevision(id: String, revision: Long) = Unit
                override suspend fun grantDomains(id: String, revision: Long, domains: List<String>) = Unit
            }
            val host = RoomArtifactUiHost.attach(fixture.artifacts,
                ArtifactSandboxHost(ArtifactExecutionService(fixture.artifacts, fixture.assets), fixture.assets), consent, fixture.shutdown)
            fixture.shutdown.enter()
            host.authorizeRevision(artifact.id, 1)
            val binding = host.open(artifact.id, 1)
            val firstInBinding = CountDownLatch(1)
            val secondInHandler = CountDownLatch(1)
            val firstDelivered = CountDownLatch(1)
            val secondDelivered = CountDownLatch(1)
            val secondTimedOut = AtomicBoolean(false)
            val callbacks = ArtifactViewCallbacks(handler@{ raw, reply ->
                if (raw.contains("\"second\"")) {
                    secondInHandler.countDown()
                    if (!firstDelivered.await(3, TimeUnit.SECONDS)) {
                        secondTimedOut.set(true)
                        return@handler
                    }
                }
                binding.onMessage(raw) { result ->
                    if (raw.contains("\"first\"")) {
                        firstInBinding.countDown()
                        secondInHandler.await(3, TimeUnit.SECONDS)
                    }
                    reply(result)
                }
            }, binding::stop)
            try {
                callbacks.dispatch("""{"id":"first","method":"getState","params":{}}""") { firstDelivered.countDown() }
                assertTrue(firstInBinding.await(5, TimeUnit.SECONDS))
                val second = Thread {
                    callbacks.dispatch("""{"id":"second","method":"getState","params":{}}""") { secondDelivered.countDown() }
                }.apply { isDaemon = true; start() }
                assertTrue(secondInHandler.await(5, TimeUnit.SECONDS))
                assertTrue(firstDelivered.await(5, TimeUnit.SECONDS))
                second.join(5_000)
                assertFalse(second.isAlive)
                assertFalse(secondTimedOut.get(), "handler held the callback monitor while the first reply held the binding monitor")
                assertTrue(secondDelivered.await(5, TimeUnit.SECONDS))
                callbacks.close()
                assertFalse(binding.active.value)
            } finally { callbacks.close(); fixture.shutdown.stopAndAwait() }
        } finally { fixture.close() }
    }

    @Test fun externalRevisionRefreshesWithoutAuthorizingItAndStateWritesDoNotRefresh(): Unit = runBlocking {
        val fixture = Fixture(Files.createTempDirectory("artifact-ui-external-revision"))
        try {
            val draft = ArtifactDraft(title = "App", files = mapOf("index.html" to "first"))
            val artifact = fixture.artifacts.submit(draft, 1)
            val consent = object : ArtifactUiConsent {
                override suspend fun authorizeRevision(id: String, revision: Long) = Unit
                override suspend fun grantDomains(id: String, revision: Long, domains: List<String>) = Unit
            }
            val host = RoomArtifactUiHost.attach(fixture.artifacts,
                ArtifactSandboxHost(ArtifactExecutionService(fixture.artifacts, fixture.assets), fixture.assets), consent, fixture.shutdown)
            fixture.shutdown.enter()
            val changes = Channel<Unit>(Channel.UNLIMITED)
            val observer = async { host.observeChanges(artifact.id) { changes.trySend(Unit) } }
            withTimeout(5_000) { changes.receive() }
            host.authorizeRevision(artifact.id, 1)
            withTimeout(5_000) { changes.receive() }
            val old = host.open(artifact.id, 1)
            val stateReply = CompletableDeferred<String>()
            old.onMessage("""{"id":"state","method":"setState","params":{"state":"updated"}}""") { stateReply.complete(it) }
            withTimeout(5_000) { stateReply.await() }
            assertNull(withTimeoutOrNull(250) { changes.receive() })
            // A different service instance on the same Room owner models an Agent edit.
            RoomArtifactService(fixture.db, fixture.assets).submit(draft.copy(id = artifact.id, expectedRevision = 1,
                files = mapOf("index.html" to "revised")), 2)
            withTimeout(5_000) { changes.receive() }
            assertFalse(old.active.value)
            assertFalse(host.isRevisionAuthorized(artifact.id, 2))
            assertFailsWith<IllegalStateException> { host.restart(old) }
            assertFailsWith<IllegalStateException> { host.open(artifact.id, 2) }
            host.authorizeRevision(artifact.id, 2)
            val fresh = host.open(artifact.id, 2)
            assertEquals("revised", fresh.files.getValue("index.html"))
            fixture.shutdown.stopAndAwait()
            assertFailsWith<CancellationException> { observer.await() }
        } finally { fixture.close() }
    }
}
