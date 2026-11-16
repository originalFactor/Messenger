/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.chat.KotlinTurnToolPolicy
import cc.ptoe.messenger.data.chat.RoomKotlinTurnStore
import cc.ptoe.messenger.data.chat.RuntimeWorkspaceFence
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.Provider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlin.test.*

class RoomCloudProviderGateTest {
    @Test fun builtInTransportStreamsWithoutFollowingCredentialRedirects() = runBlocking {
        val dir = Files.createTempDirectory("room-cloud-gate-http")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val redirected = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val leaks = AtomicInteger()
        redirected.createContext("/steal") { exchange -> leaks.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        redirected.start()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val useRedirect = AtomicBoolean(false)
        val credentials = java.util.concurrent.ConcurrentLinkedQueue<String>()
        server.createContext("/v1/chat/completions") { exchange ->
            credentials.add(exchange.requestHeaders.getFirst("Authorization") ?: "")
            exchange.requestBody.readBytes()
            if (useRedirect.get()) {
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${redirected.address.port}/steal")
                exchange.sendResponseHeaders(302, -1); exchange.close()
            } else {
                val body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"}}]}\n\ndata: [DONE]\n\n".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
        val host = "http://127.0.0.1:${server.address.port}"
        val dao = db.cloudSync()
        val gate = RoomCloudProviderGate.forDao(dao)
        try {
            val session = SyncSession("account", "generation")
            val user = CloudUser(id = "account", aiApiKey = "secret")
            dao.saveCredentials(StoredCloudSession(session, "messenger_session=cookie", host, user))
            dao.updateCloudUser(session, user, host)
            val round = KotlinTurnSnapshot("turn", "conversation", KotlinTurnPhase.REQUESTING_PROVIDER,
                Provider(BUILTIN_PROVIDER_ID, "Cloud", "$host/v1", "secret", createdAt = 1, updatedAt = 1),
                ChatCompletionRequestDto("model", emptyList()), cloudIdentity = KotlinCloudTurnIdentity(session.accountId, session.generation))
            val events = withTimeout(5000) { gate.stream(round).toList() }
            assertTrue(events.any { it is ChatStreamEvent.Content && it.text == "hello" })
            assertTrue(events.any { it is ChatStreamEvent.Done })
            useRedirect.set(true)
            val failure = assertFailsWith<IllegalStateException> { withTimeout(5000) { gate.stream(round).toList() } }
            assertTrue(failure.message.orEmpty().contains("302"))
            assertEquals(0, leaks.get())
            assertEquals(listOf("Bearer secret", "Bearer secret"), credentials.toList())
        } finally {
            gate.shutdown(); db.close(); server.stop(0); redirected.stop(0); dir.toFile().deleteRecursively()
        }
    }

    @Test fun expiryAndLogoutRevokeBeforeDrainingRequestsAndRejectStaleCredentials() = runBlocking {
        for (logout in listOf(false, true)) {
            val dir = Files.createTempDirectory("room-cloud-turn-gate")
            val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val logoutRequests = AtomicInteger()
            server.createContext("/api/auth/logout") { exchange ->
                logoutRequests.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            server.start()
            val host = "http://127.0.0.1:${server.address.port}"
            val client = RoomCloudHttpClient()
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val mayStop = CompletableDeferred<Unit>()
            val releaseWorkspace = CompletableDeferred<Unit>()
            val workspaceEntered = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val stopping = CompletableDeferred<Unit>()
            val dispatched = AtomicInteger()
            val upstream = KotlinTurnProvider { _, _ -> flow {
                dispatched.incrementAndGet()
                started.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { stopping.complete(Unit); mayStop.await() } }
            } }
            val gate = RoomCloudProviderGate.forTesting(db.cloudSync(), upstream)
            try {
                val dao = db.cloudSync()
                val auth = RoomCloudAuthentication(dao, client)
                val session = SyncSession("account", "generation-1")
                val user = CloudUser(id = "account", aiApiKey = "cloud-secret")
                val credentials = StoredCloudSession(session, "messenger_session=cookie", host, user)
                dao.saveCredentials(credentials)
                dao.updateCloudUser(session, user, host)
                val rows = db.rows()
                rows.upsertModels(listOf(ModelRow("model", BUILTIN_PROVIDER_ID, "model-name", "Model", true, 100,
                    null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
                RoomAgentRepository(db).ensureDefaultAgent(Agent("agent", "Agent", systemPrompt = "prompt", defaultModelId = "model",
                    temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
                rows.upsertConversations(listOf(ConversationRow("conversation", "Chat", BUILTIN_PROVIDER_ID, "agent", null,
                    null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
                val store = RoomKotlinTurnStore(db, { _, _, _, model, _, _ -> ChatCompletionRequestDto(model.modelId, emptyList()) },
                    KotlinTurnToolPolicy { _, _, _ -> true }, cloudGate = gate)
                val runner = KotlinChatTurnRunner(store, upstream, KotlinTurnToolExecutor { _, _ -> error("unexpected tool") }, scope)
                val turn = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(KotlinTurnCommand.Send("conversation", "hi", null))).turnId
                withTimeout(5000) { started.await() }
                val staleRound = store.snapshot(turn)
                val workspace = async {
                    RuntimeWorkspaceFence.forDatabase(db).mutate {
                        workspaceEntered.complete(Unit)
                        releaseWorkspace.await()
                    }
                }
                withTimeout(5000) { workspaceEntered.await() }
                val revoke = async(Dispatchers.Default) { if (logout) auth.logout(host) else auth.expireIfCurrent(session) }
                withTimeout(5000) { stopping.await() }
                assertNull(dao.currentSession())
                assertNull(dao.value("cloud_session"))
                assertFalse(workspace.isCompleted, "session revocation must not wait for the workspace fence")
                assertEquals("", dao.providerRow(BUILTIN_PROVIDER_ID)?.apiKey)
                assertNull(withTimeoutOrNull(200) { revoke.await() }, "revocation must wait until the request has stopped")
                assertFailsWith<IllegalStateException> { store.providerStream(staleRound, upstream).toList() }
                assertEquals(1, dispatched.get())
                mayStop.complete(Unit)
                withTimeout(5000) { revoke.await(); runner.awaitStopped(turn) }
                assertEquals(KotlinTurnPhase.FAILED, store.snapshot(turn).phase)
                assertNotNull(store.observeFailure(turn).first())
                assertNull(rows.getValue("kotlin_turn_active_v1:conversation"))
                assertNull(dao.providerRow(BUILTIN_PROVIDER_ID))
                assertEquals(if (logout) 1 else 0, logoutRequests.get())
                assertEquals(!logout, auth.sessionExpired.value)

                // A later login, even to the same account and with the same AI key, cannot revive
                // the old turn's captured credentials or let a stale 401 clear the new session.
                val next = session.copy(generation = "generation-2")
                dao.saveCredentials(credentials.copy(session = next))
                dao.updateCloudUser(next, user, host)
                auth.expireIfCurrent(session)
                assertEquals(next, dao.currentSession())
                assertFailsWith<IllegalStateException> { store.providerStream(staleRound, upstream).toList() }
                assertEquals(1, dispatched.get())
            } finally {
                releaseWorkspace.complete(Unit)
                mayStop.complete(Unit)
                scope.cancel()
                gate.shutdown()
                scope.coroutineContext[Job]?.join()
                client.close()
                db.close()
                server.stop(0)
                dir.toFile().deleteRecursively()
            }
        }
    }
}
