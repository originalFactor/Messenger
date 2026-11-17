/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.domain.chat.KotlinTurnProvider
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType
import okhttp3.Response
import okio.BufferedSink
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.*

class RoomCloudExternalProviderGateTest {
    private fun providerRequest(host: String, key: String) = Request.Builder()
        .url("$host/v1/chat/completions").header("Authorization", "Bearer $key").post(object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.writeUtf8("{}") }
        }).build()
    private suspend fun call(permit: RoomCloudProviderGate.ExternalProviderPermit, request: Request): Int = suspendCancellableCoroutine { continuation ->
        val submitted = AtomicReference<Call?>()
        continuation.invokeOnCancellation { submitted.get()?.cancel() }
        try {
            val call = permit.enqueue(request, object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    response.use { if (continuation.isActive) continuation.resume(it.code) }
                }
            })
            submitted.set(call)
            if (!continuation.isActive) call.cancel()
        } catch (failure: Throwable) { if (continuation.isActive) continuation.resumeWithException(failure) }
    }

    @Test fun revocationBeforeEnqueueRejectsEvenAfterNewSessionAndNeverDispatches(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cloud-external-admission")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("db").toString()).setDriver(BundledSQLiteDriver()).build()
        val dao = db.cloudSync()
        val gate = RoomCloudProviderGate.forTesting(dao, KotlinTurnProvider { _, _ -> flow { awaitCancellation() } })
        val old = SyncSession("account", "old")
        val fresh = SyncSession("account", "fresh")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> calls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.start()
        try {
            val user = CloudUser(id = "account", aiApiKey = "key")
            val host = "http://127.0.0.1:${server.address.port}"
            dao.saveCredentials(StoredCloudSession(old, "messenger_session=old", host, user)); dao.updateCloudUser(old, user, host)
            val provider = checkNotNull(dao.providerRow(BUILTIN_PROVIDER_ID))
            var captured: RoomCloudProviderGate.ExternalProviderPermit? = null
            val result = async { runCatching { gate.withExternalProviderRequest(old, provider) { permit ->
                captured = permit; entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                call(permit, providerRequest(host, "key"))
            } } }
            withTimeout(5_000) { entered.await() }
            gate.invalidate(old)
            release.complete(Unit)
            assertTrue(withTimeout(5_000) { result.await() }.isFailure)
            gate.revoke(old)
            dao.saveCredentials(StoredCloudSession(fresh, "messenger_session=new", host, user)); dao.updateCloudUser(fresh, user, host)
            assertFailsWith<IllegalStateException> { checkNotNull(captured).enqueue(providerRequest(host, "key"), object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit
                override fun onResponse(call: Call, response: Response) = response.close()
            }) }
            assertEquals(0, calls.get())
        } finally { release.complete(Unit); gate.shutdown(); server.stop(0); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun logoutCancelsAndJoinsAnAdmittedHttpRequestWithoutRetry(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cloud-external-revoke")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("db").toString()).setDriver(BundledSQLiteDriver()).build()
        val dao = db.cloudSync()
        val gate = RoomCloudProviderGate.forTesting(dao, KotlinTurnProvider { _, _ -> flow { awaitCancellation() } })
        val session = SyncSession("account", "generation")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val serverCount = AtomicInteger(); val cleaned = CompletableDeferred<Unit>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try { serverCount.incrementAndGet(); entered.countDown(); release.await(10, TimeUnit.SECONDS); exchange.sendResponseHeaders(200, -1) }
            catch (_: Exception) { } finally { exchange.close() }
        }
        server.start()
        try {
            val user = CloudUser(id = "account", aiApiKey = "key")
            val host = "http://127.0.0.1:${server.address.port}"
            dao.saveCredentials(StoredCloudSession(session, "messenger_session=cookie", host, user)); dao.updateCloudUser(session, user, host)
            val provider = checkNotNull(dao.providerRow(BUILTIN_PROVIDER_ID))
            val result = async { runCatching { gate.withExternalProviderRequest(session, provider) { permit ->
                try { call(permit, providerRequest(host, "key")) }
                finally { withContext(NonCancellable) { cleaned.complete(Unit) } }
            } } }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertTrue(withTimeout(5_000) { gate.revoke(session) })
            assertTrue(cleaned.isCompleted)
            assertTrue(withTimeout(5_000) { result.await() }.isFailure)
            assertNull(dao.currentSession())
            assertEquals(1, serverCount.get())
        } finally { release.countDown(); gate.shutdown(); server.stop(0); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun providerKey401DoesNotRevokeTheAuthenticatedAccount(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cloud-external-key-401")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("db").toString()).setDriver(BundledSQLiteDriver()).build()
        val dao = db.cloudSync()
        val gate = RoomCloudProviderGate.forTesting(dao, KotlinTurnProvider { _, _ -> flow { awaitCancellation() } })
        val session = SyncSession("account", "generation")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> exchange.sendResponseHeaders(401, -1); exchange.close() }
        server.start()
        try {
            val user = CloudUser(id = "account", aiApiKey = "bad-key")
            val host = "http://127.0.0.1:${server.address.port}"
            dao.saveCredentials(StoredCloudSession(session, "messenger_session=cookie", host, user)); dao.updateCloudUser(session, user, host)
            val provider = checkNotNull(dao.providerRow(BUILTIN_PROVIDER_ID))
            var attempted = false
            assertFails { gate.withExternalProviderRequest(session, provider.copy(apiKey = "changed")) { attempted = true; Unit } }
            assertFalse(attempted)
            val wrongHost = provider.copy(baseUrl = "https://other.example/v1")
            dao.saveProvider(wrongHost)
            assertFails { gate.withExternalProviderRequest(session, wrongHost) { attempted = true; Unit } }
            assertFalse(attempted)
            dao.saveProvider(provider)
            assertFails { gate.withExternalProviderRequest(session, provider) { error("rejected before enqueue") } }
            assertFailsWith<IllegalStateException> {
                withTimeout(5_000) { gate.withExternalProviderRequest(session, provider) {
                    withContext(NonCancellable + Dispatchers.Default) { gate.revoke(session) }
                } }
            }
            assertEquals(session, dao.currentSession())
            assertEquals(401, gate.withExternalProviderRequest(session, provider) { permit ->
                assertFails { call(permit, providerRequest(host, "wrong-key")) }
                val status = call(permit, providerRequest(host, "bad-key"))
                assertFails { call(permit, providerRequest(host, "bad-key")) }
                status
            })
            assertEquals(session, dao.currentSession())
        } finally { gate.shutdown(); server.stop(0); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun gateOwnedClientDoesNotFollowRedirectsOrRetryProviderErrors(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cloud-external-no-retry")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("db").toString()).setDriver(BundledSQLiteDriver()).build()
        val dao = db.cloudSync()
        val gate = RoomCloudProviderGate.forTesting(dao, KotlinTurnProvider { _, _ -> flow { awaitCancellation() } })
        val session = SyncSession("account", "generation")
        val redirected = AtomicInteger(); val calls = AtomicInteger(); val redirect = java.util.concurrent.atomic.AtomicBoolean(true)
        val other = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        other.createContext("/") { exchange -> redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        other.start()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet(); exchange.requestBody.readBytes()
            if (redirect.get()) exchange.responseHeaders.add("Location", "http://127.0.0.1:${other.address.port}/steal")
            exchange.sendResponseHeaders(if (redirect.get()) 307 else 503, -1); exchange.close()
        }
        server.start()
        try {
            val user = CloudUser(id = "account", aiApiKey = "key")
            val host = "http://127.0.0.1:${server.address.port}"
            dao.saveCredentials(StoredCloudSession(session, "messenger_session=cookie", host, user)); dao.updateCloudUser(session, user, host)
            val provider = checkNotNull(dao.providerRow(BUILTIN_PROVIDER_ID))
            assertEquals(307, gate.withExternalProviderRequest(session, provider) { call(it, providerRequest(host, "key")) })
            redirect.set(false)
            assertEquals(503, gate.withExternalProviderRequest(session, provider) { call(it, providerRequest(host, "key")) })
            assertEquals(2, calls.get())
            assertEquals(0, redirected.get())
            assertEquals(session, dao.currentSession())
        } finally { gate.shutdown(); server.stop(0); other.stop(0); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun returningEarlyStillJoinsCallbackAndRejectsCallbackSelfRevocation(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cloud-external-callback")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("db").toString()).setDriver(BundledSQLiteDriver()).build()
        val dao = db.cloudSync()
        val gate = RoomCloudProviderGate.forTesting(dao, KotlinTurnProvider { _, _ -> flow { awaitCancellation() } })
        val session = SyncSession("account", "generation")
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val rejected = java.util.concurrent.atomic.AtomicBoolean(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> exchange.requestBody.readBytes(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.start()
        try {
            val user = CloudUser(id = "account", aiApiKey = "key")
            val host = "http://127.0.0.1:${server.address.port}"
            dao.saveCredentials(StoredCloudSession(session, "messenger_session=cookie", host, user)); dao.updateCloudUser(session, user, host)
            val provider = checkNotNull(dao.providerRow(BUILTIN_PROVIDER_ID))
            val work = async(Dispatchers.Default) { gate.withExternalProviderRequest(session, provider) { permit ->
                permit.enqueue(providerRequest(host, "key"), object : Callback {
                    override fun onFailure(call: Call, e: IOException) { entered.completeExceptionally(e) }
                    override fun onResponse(call: Call, response: Response) {
                        try { runBlocking { withContext(NonCancellable) { gate.revoke(session) } } }
                        catch (_: IllegalStateException) { rejected.set(true) }
                        entered.complete(Unit)
                        release.await(5, TimeUnit.SECONDS)
                    }
                })
                entered.await()
            } }
            withTimeout(5_000) { entered.await() }
            val logout = async(Dispatchers.Default) { gate.revoke(session) }
            delay(50)
            assertFalse(logout.isCompleted)
            assertTrue(rejected.get())
            release.countDown()
            assertTrue(withTimeout(5_000) { logout.await() })
            withTimeout(5_000) { work.join() }
            assertNull(dao.currentSession())
        } finally { release.countDown(); gate.shutdown(); server.stop(0); db.close(); dir.toFile().deleteRecursively() }
    }
}
